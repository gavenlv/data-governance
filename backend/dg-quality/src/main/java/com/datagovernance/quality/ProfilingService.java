package com.datagovernance.quality;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
import com.datagovernance.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据剖析（docs/09 §9.4）。
 *
 * <p>三条来自设计文档、必须落到实现里的约束：
 * <ol>
 *   <li><b>精度来源必须标注</b>：{@code row_count} 来自 {@code pg_class.reltuples} 是**估算值**，
 *       与实算可能量级不同。因此估算值与实算值分开存（{@code row_count} / {@code row_count_estimate}），
 *       并各自带 {@code precision} 与 {@code precision_source}。基于估算值触发告警是真实的误报来源。</li>
 *   <li><b>采样方式有优劣</b>：优先哈希取模（可重复、可分片）、{@code TABLESAMPLE} 次之，
 *       {@code LIMIT} 头部采样**不用**（有偏）。采样结果一律标 {@code ESTIMATED} —— 不静默外推。</li>
 *   <li><b>隐私约束</b>：高密级（L3/L4）列**默认只输出统计量**，不输出具体值（min/max/top-k 都不给），
 *       因为 top-k 值正是 PII 泄露最常见的路径。</li>
 * </ol>
 */
@Service
public class ProfilingService {

    private static final Logger log = LoggerFactory.getLogger(ProfilingService.class);

    private static final int TOP_K = 5;
    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile(
            "(?i).*(int|numeric|decimal|real|double|float|serial|money).*");
    private static final java.util.regex.Pattern TEMPORAL = java.util.regex.Pattern.compile(
            "(?i).*(date|time|timestamp).*");

    private final MetadataService metadata;
    private final ModelRegistry registry;
    private final JdbcTemplate jdbc;

    public ProfilingService(MetadataService metadata, ModelRegistry registry, JdbcTemplate jdbc) {
        this.metadata = metadata;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** 一次剖析请求。 */
    public record ProfileRequest(
            String jdbcUrl,
            String username,
            String password,
            List<String> datasetUrns,
            Integer samplePercent,
            Boolean includeTopK,
            String actor) {
    }

    public Map<String, Object> profile(ProfileRequest request) {
        if (request.datasetUrns() == null || request.datasetUrns().isEmpty()) {
            throw new QualityException("至少要指定一个数据集 URN");
        }
        int samplePercent = request.samplePercent() == null ? 100 : request.samplePercent();
        if (samplePercent < 1 || samplePercent > 100) {
            throw new QualityException("samplePercent 必须在 1–100 之间：" + samplePercent);
        }
        boolean includeTopK = !Boolean.FALSE.equals(request.includeTopK());
        java.sql.Timestamp windowStart = jdbc.queryForObject(
                "SELECT date_trunc('day', now())", java.sql.Timestamp.class);

        List<Map<String, Object>> datasets = new ArrayList<>();
        long started = System.currentTimeMillis();

        try (Connection connection = DriverManager.getConnection(
                request.jdbcUrl(), request.username(), request.password())) {
            connection.setReadOnly(true);
            for (String urn : request.datasetUrns()) {
                datasets.add(profileDataset(connection, urn, samplePercent, includeTopK,
                        windowStart, request.actor()));
            }
        } catch (SQLException e) {
            throw new QualityException("连接数据源失败：" + e.getMessage(), e);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasets", datasets);
        payload.put("samplePercent", samplePercent);
        payload.put("windowStart", windowStart);
        payload.put("durationMs", System.currentTimeMillis() - started);
        payload.put("precisionNote", "precision=EXACT 表示实算；ESTIMATED 表示来自源系统估算或采样，"
                + "两者都被存储但不可混用（估算值不应作为告警阈值依据）");
        return payload;
    }

    // ------------------------------------------------------------------ 单表

    private Map<String, Object> profileDataset(Connection connection, String urn, int samplePercent,
                                               boolean includeTopK, java.sql.Timestamp windowStart,
                                               String actor) {
        Map<String, Object> schema = metadata.getAspect(urn, "datasetSchema")
                .orElseThrow(() -> new QualityException("数据集没有 datasetSchema，无法剖析（先采集）：" + urn));

        List<Map<String, Object>> fields = asMapList(schema.get("fields"));
        List<String> primaryKey = asStringList(schema.get("primaryKey"));
        String tableName = RuleCompiler.qualifiedTable(urn);

        Sampling sampling = sampling(connection, tableName, primaryKey, samplePercent);
        String fromClause = tableName + sampling.suffix();

        Map<String, Object> datasetResult = new LinkedHashMap<>();
        datasetResult.put("urn", urn);
        datasetResult.put("table", tableName);
        datasetResult.put("sampling", Map.of(
                "method", sampling.method(),
                "ratio", sampling.ratio(),
                "precision", sampling.precision(),
                "note", sampling.note()));

        // 表级行数（实算/采样）+ 源系统估算值（pg_class.reltuples），两者分开存且各自标注精度
        long scannedRows = scalar(connection, "SELECT COUNT(*)::numeric FROM " + fromClause).longValue();
        writeMetric(urn, null, "row_count", (double) scannedRows, null, sampling.precision(),
                sampling.precisionSource(), sampling, null, windowStart, actor);
        datasetResult.put("rowCount", scannedRows);

        Long estimated = estimatedRows(connection, tableName);
        if (estimated != null) {
            writeMetric(urn, null, "row_count_estimate", estimated.doubleValue(), null, "ESTIMATED",
                    "pg_class.reltuples", null, null, windowStart, actor);
            datasetResult.put("rowCountEstimate", estimated);
            datasetResult.put("rowCountEstimateSource", "pg_class.reltuples（估算值，不是实算）");
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            columns.add(profileColumn(connection, urn, tableName, fromClause, field, sampling,
                    includeTopK, windowStart, actor));
        }
        datasetResult.put("columns", columns);
        datasetResult.put("columnCount", columns.size());
        log.info("剖析完成 {}：{} 行，{} 列（{}）", urn, scannedRows, columns.size(), sampling.method());
        return datasetResult;
    }

    private Map<String, Object> profileColumn(Connection connection, String datasetUrn, String table,
                                              String fromClause, Map<String, Object> field,
                                              Sampling sampling, boolean includeTopK,
                                              java.sql.Timestamp windowStart, String actor) {
        String name = String.valueOf(field.get("name"));
        String type = field.get("type") == null ? "" : String.valueOf(field.get("type"));
        String columnRef = RuleCompiler.requireIdentifier(name, "column");
        ColumnType columnType = ColumnType.of(type);
        boolean numeric = columnType.numeric();
        boolean temporal = columnType.temporal();

        String classification = columnClassification(datasetUrn, name);
        boolean sensitive = "L3".equals(classification) || "L4".equals(classification);
        // min/max/top-k 只对有意义的类型生成：uuid 没有 MIN()、json 没有等值比较。
        // 之前这里无脑生成 MIN(col)，在真实库上直接报错（"函数 min(uuid) 不存在"）。
        boolean valueStats = columnType.orderable() && !sensitive;

        StringBuilder sql = new StringBuilder("SELECT COUNT(*)::numeric AS total, ")
                .append("COUNT(").append(columnRef).append(")::numeric AS non_null");
        if (columnType.equatable()) {
            sql.append(", COUNT(DISTINCT ").append(columnRef).append(")::numeric AS distinct_cnt");
        } else {
            sql.append(", NULL::numeric AS distinct_cnt");
        }
        if (valueStats) {
            sql.append(", MIN(").append(columnRef).append(")::text AS min_v, MAX(")
                    .append(columnRef).append(")::text AS max_v");
        }
        sql.append(", MIN(length(").append(columnRef).append("::text))::numeric AS min_len, MAX(length(")
                .append(columnRef).append("::text))::numeric AS max_len");
        if (numeric) {
            sql.append(", percentile_cont(0.5) WITHIN GROUP (ORDER BY ").append(columnRef)
                    .append(")::numeric AS p50, percentile_cont(0.95) WITHIN GROUP (ORDER BY ")
                    .append(columnRef).append(")::numeric AS p95");
        }
        sql.append(" FROM ").append(fromClause);

        Map<String, Object> row = queryRow(connection, sql.toString());
        double total = toDouble(row.get("total"));
        double nonNull = toDouble(row.get("non_null"));
        double nullCount = total - nonNull;
        double nullRatio = total == 0 ? 0.0 : nullCount / total;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", type);
        result.put("classification", classification);
        result.put("sensitive", sensitive);

        Map<String, Object> metrics = new LinkedHashMap<>();
        putMetric(metrics, "null_count", nullCount);
        putMetric(metrics, "null_ratio", nullRatio);
        if (columnType.equatable()) {
            putMetric(metrics, "distinct_count", toDouble(row.get("distinct_cnt")));
        }
        putMetric(metrics, "min_length", toDouble(row.get("min_len")));
        putMetric(metrics, "max_length", toDouble(row.get("max_len")));
        if (numeric) {
            putMetric(metrics, "p50", toDouble(row.get("p50")));
            putMetric(metrics, "p95", toDouble(row.get("p95")));
        }
        if (valueStats) {
            result.put("minValue", row.get("min_v"));
            result.put("maxValue", row.get("max_v"));
        } else if (sensitive) {
            result.put("valueSuppressed", "列分级 " + classification + "：按隐私约束只输出统计量，不输出具体值"
                    + "（top-k 与 min/max 都可能泄露 PII）");
        } else {
            result.put("valueSuppressed", "类型 " + type + " 不支持有意义的 min/max 或等值统计"
                    + "（例如 uuid 没有 MIN()、json 没有等值比较）—— 这是类型能力边界的显式标注，不是采集失败");
        }
        result.put("metrics", metrics);
        result.put("precision", sampling.precision());

        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            writeMetric(datasetUrn, name, entry.getKey(), toDouble(entry.getValue()), null,
                    sampling.precision(), sampling.precisionSource(), sampling, null, windowStart, actor);
        }
        if (valueStats && row.get("min_v") != null) {
            writeMetric(datasetUrn, name, "min", null, Map.of("value", row.get("min_v"),
                            "ordering", columnType.family() + "（按该类型原生排序）"),
                    sampling.precision(), sampling.precisionSource(), sampling, null, windowStart, actor);
        }
        if (valueStats && row.get("max_v") != null) {
            writeMetric(datasetUrn, name, "max", null, Map.of("value", row.get("max_v"),
                            "ordering", columnType.family() + "（按该类型原生排序）"),
                    sampling.precision(), sampling.precisionSource(), sampling, null, windowStart, actor);
        }
        if (valueStats && temporal) {
            double lag = freshnessSeconds(connection, fromClause, columnRef);
            if (lag >= 0) {
                putMetric(metrics, "freshness_seconds", lag);
                writeMetric(datasetUrn, name, "freshness_seconds", lag, null, sampling.precision(),
                        sampling.precisionSource(), sampling, null, windowStart, actor);
            }
        }
        if (includeTopK && valueStats) {
            List<Map<String, Object>> topK = topValues(connection, fromClause, columnRef);
            result.put("topK", topK);
            if (!topK.isEmpty()) {
                writeMetric(datasetUrn, name, "top_k", null, Map.of("values", topK),
                        sampling.precision(), sampling.precisionSource(), sampling, null, windowStart, actor);
            }
        }

        writeColumnProfileAspect(datasetUrn, name, row, total, nullRatio, !valueStats,
                sampling, actor);
        return result;
    }

    /**
     * 列类型能力（决定能算哪些统计量）。
     *
     * <p>为什么需要它：SQL 聚合函数并非对所有类型都成立 —— {@code MIN(uuid)} 在 PostgreSQL 不存在，
     * {@code COUNT(DISTINCT json)} 也不合法。不做这层判断就会在真实库上直接报错，
     * 而"报错"会让人以为是平台坏了，而不是"这个指标对该类型无意义"。
     */
    private record ColumnType(String family, boolean numeric, boolean temporal, boolean orderable,
                              boolean equatable) {

        static ColumnType of(String rawType) {
            String type = rawType == null ? "" : rawType.toLowerCase(java.util.Locale.ROOT).trim();
            String base = type.replaceAll("\\(.*\\)", "").replace("[]", "").trim();
            boolean numeric = NUMERIC.matcher(base).matches();
            boolean temporal = TEMPORAL.matcher(base).matches();
            boolean textish = base.contains("char") || base.contains("text") || base.contains("string");
            boolean uuid = base.contains("uuid");
            boolean json = base.contains("json");
            boolean binary = base.contains("bytea") || base.contains("blob") || base.contains("binary");
            boolean bool = base.contains("bool");
            String family = numeric ? "numeric" : temporal ? "temporal" : textish ? "string"
                    : uuid ? "uuid" : json ? "json" : binary ? "binary" : bool ? "boolean" : "other";
            // uuid/text/数值/时间可以排序（uuid 无 MIN()，因此单独排除）；json 既不排序也不等值比较
            boolean orderable = (numeric || temporal || textish || bool) && !uuid && !json && !binary;
            boolean equatable = !json && !base.contains("xml");
            return new ColumnType(family, numeric, temporal, orderable, equatable);
        }
    }

    // -------------------------------------------------------------- 采样策略

    private record Sampling(String method, Double ratio, String precision, String precisionSource,
                            String suffix, String note) {
    }

    /**
     * 选择采样方式（docs/09 §9.4 的取舍）：
     * 全量 → 哈希取模（有主键时，可重复可分片）→ {@code TABLESAMPLE}（无主键时）。
     * <b>不使用 {@code LIMIT} 头部采样</b>：它按物理顺序取前 N 行，在按时间追加的表上系统性有偏。
     */
    private Sampling sampling(Connection connection, String table, List<String> primaryKey,
                              int samplePercent) {
        if (samplePercent >= 100) {
            return new Sampling("full_scan", 1.0, "EXACT", "profiling 实算", "",
                    "全表扫描：结果为实算值（EXACT）");
        }
        double ratio = samplePercent / 100.0;
        if (!primaryKey.isEmpty()) {
            List<String> keyExpr = new ArrayList<>();
            for (String key : primaryKey) {
                keyExpr.add("COALESCE(" + RuleCompiler.requireIdentifier(key, "primaryKey") + "::text, '')");
            }
            String hashExpr = "MOD(ABS(HASHTEXT(CONCAT(" + String.join(", ", keyExpr)
                    + "))), 100) < " + samplePercent;
            return new Sampling("hash_modulo", ratio, "ESTIMATED",
                    "哈希取模采样（主键 " + String.join(",", primaryKey) + "）",
                    " WHERE " + hashExpr,
                    "哈希取模：可重复、可分片、与物理顺序无关；结果为估算值（ESTIMATED），未做总体外推");
        }
        return new Sampling("tablesample_system", ratio, "ESTIMATED", "TABLESAMPLE SYSTEM",
                " TABLESAMPLE SYSTEM (" + samplePercent + ")",
                "无主键，退化为 TABLESAMPLE SYSTEM（块级采样）：结果波动较大，仅作趋势参考");
    }

    // ------------------------------------------------------------ 指标落库

    private void writeMetric(String datasetUrn, String column, String metric, Double valueNum,
                             Map<String, Object> valueJson, String precision, String precisionSource,
                             Sampling sampling, String runId, java.sql.Timestamp windowStart,
                             String actor) {
        jdbc.update("""
                INSERT INTO profile_metric
                    (dataset_urn, column_name, metric, window_start, value_num, value_json,
                     precision, precision_source, sampling_method, sample_ratio, row_count, run_id)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, NULL, ?)
                ON CONFLICT (dataset_urn, COALESCE(column_name, ''), metric, window_start) DO UPDATE
                    SET value_num = EXCLUDED.value_num,
                        value_json = EXCLUDED.value_json,
                        precision = EXCLUDED.precision,
                        precision_source = EXCLUDED.precision_source,
                        sampling_method = EXCLUDED.sampling_method,
                        sample_ratio = EXCLUDED.sample_ratio,
                        run_id = EXCLUDED.run_id,
                        created_at = now()
                """, datasetUrn, column, metric, windowStart, valueNum,
                valueJson == null ? null : toJson(valueJson),
                precision, precisionSource,
                sampling == null ? null : sampling.method(),
                sampling == null || sampling.ratio() == null ? null : sampling.ratio().floatValue(),
                runId);
    }

    /** 把列画像写回列实体的 columnProfile aspect —— 让剖析结果在资产页可见（模型里已有该 aspect）。 */
    private void writeColumnProfileAspect(String datasetUrn, String columnName,
                                          Map<String, Object> row, double total, double nullRatio,
                                          boolean sensitive, Sampling sampling, String actor) {
        try {
            String columnUrn = com.datagovernance.core.UrnUtils.column(datasetUrn, columnName);
            if (!metadata.entityExists(columnUrn)) {
                metadata.ensureEntity(columnUrn, "Column", columnName, null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rowCount", (long) total);
            data.put("nullRatio", nullRatio);
            data.put("distinctCount", (long) toDouble(row.get("distinct_cnt")));
            if (!sensitive) {
                if (row.get("min_v") != null) {
                    data.put("minValue", String.valueOf(row.get("min_v")));
                }
                if (row.get("max_v") != null) {
                    data.put("maxValue", String.valueOf(row.get("max_v")));
                }
            }
            data.put("sampledAt", java.time.Instant.now().toString());
            data.put("precision", "EXACT".equals(sampling.precision()) ? "EXACT" : "ESTIMATED");
            data.put("precisionSource", sampling.precisionSource());
            metadata.upsertAspect(columnUrn, "columnProfile", data, "AUTO_COLLECTED", null, null);
        } catch (RuntimeException e) {
            // 画像只是可见性增强，不能因为它失败就丢掉已经算出来的指标
            log.warn("写入列画像 aspect 失败（指标已入库）：{} {}", columnName, e.getMessage());
        }
    }

    // ------------------------------------------------------------- 低层查询

    private Long estimatedRows(Connection connection, String qualifiedTable) {
        // 注意两点（都踩过）：
        //   1) format() 的格式串里 %I 在 Java 字符串里无需转义，但**参数必须显式 ::text** ——
        //      format(text, VARIADIC "any") 遇到未定类型的占位符会报 "could not determine data type"；
        //   2) 失败必须打日志：静默返回 null 会让"估算值这一能力没生效"完全不可见。
        String sql = "SELECT reltuples::bigint FROM pg_class WHERE oid = "
                + "format('%I.%I', ?::text, ?::text)::regclass";
        String[] parts = qualifiedTable.replace("\"", "").split("\\.", 2);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            log.warn("读取 pg_class.reltuples 估算行数失败（不影响实算指标）：{}", e.getMessage());
            return null;
        }
    }

    private double freshnessSeconds(Connection connection, String fromClause, String columnRef) {
        try {
            Double value = scalar(connection, "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MAX("
                    + columnRef + "))), -1)::numeric FROM " + fromClause);
            return value == null ? -1 : value;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private List<Map<String, Object>> topValues(Connection connection, String fromClause,
                                                String columnRef) {
        String sql = "SELECT " + columnRef + "::text AS v, COUNT(*)::numeric AS c FROM " + fromClause
                + " WHERE " + columnRef + " IS NOT NULL GROUP BY 1 ORDER BY c DESC, 1 LIMIT " + TOP_K;
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(Map.of("value", rs.getString("v"), "count", rs.getLong("c")));
            }
        } catch (SQLException e) {
            log.debug("top-k 计算失败（忽略）：{}", e.getMessage());
        }
        return out;
    }

    private Double scalar(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).doubleValue() : null;
        } catch (SQLException e) {
            throw new QualityException("执行剖析 SQL 失败：" + e.getMessage()
                    + "（SQL: " + sql + "）", e);
        }
    }

    private Map<String, Object> queryRow(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Map.of();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof java.math.BigDecimal decimal) {
                    value = decimal.doubleValue();
                }
                row.put(md.getColumnLabel(i), value);
            }
            return row;
        } catch (SQLException e) {
            throw new QualityException("执行剖析 SQL 失败：" + e.getMessage() + "（SQL: " + sql + "）", e);
        }
    }

    /** 读取列分级（用于隐私约束）。 */
    private String columnClassification(String datasetUrn, String columnName) {
        try {
            String columnUrn = com.datagovernance.core.UrnUtils.column(datasetUrn, columnName);
            return metadata.getAspect(columnUrn, "classification")
                    .map(data -> data.get("level"))
                    .map(String::valueOf)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((key, val) -> entry.put(String.valueOf(key), val));
                out.add(entry);
            }
        }
        return out;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static void putMetric(Map<String, Object> metrics, String key, double value) {
        metrics.put(key, Math.round(value * 1e6) / 1e6);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 供排障：当前 registry 里可用的 aspect（确认模型驱动生效）。 */
    public List<String> availableAspects() {
        return List.copyOf(registry.aspectTypeNames());
    }
}
