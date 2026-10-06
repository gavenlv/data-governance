package com.datagovernance.lineage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datagovernance.core.MetadataService;
import com.datagovernance.core.resolve.TableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL 静态解析入库（docs/09 §9.2）。
 *
 * <p>分工：<b>侧车只解析，控制面负责入库</b>。这样"谁是写入者"没有歧义，
 * 也不会出现两套 URN 解析规则。
 *
 * <p>两条不可妥协的行为：
 * <ol>
 *   <li><b>表名解析不上就不写边</b>，并把表名记进 {@code unresolvedTables} —— 宁可缺边不猜；</li>
 *   <li><b>解析不够好的结果必须落样本库</b>（{@code lineage_parse_sample}）。这是把
 *       "方言覆盖率"变成可运营指标的唯一办法，否则用户无法区分"没有血缘"与"解析没解析出来"。</li>
 * </ol>
 */
@Service
public class SqlParseService {

    private static final Logger log = LoggerFactory.getLogger(SqlParseService.class);

    /** 与 Python 参考实现一致的置信度映射。 */
    private static final Map<String, Double> CONFIDENCE = Map.of(
            "exact", 0.8,
            "derived", 0.65,
            "table_level_only", 0.5,
            "failed", 0.0);

    private final SqlParseSidecarClient sidecar;
    private final TableResolver resolver;
    private final MetadataService metadata;
    private final JdbcTemplate jdbc;
    private final SqlParseL2Validator l2Validator;

    public SqlParseService(SqlParseSidecarClient sidecar, TableResolver resolver,
                           MetadataService metadata, JdbcTemplate jdbc) {
        this.sidecar = sidecar;
        this.resolver = resolver;
        this.metadata = metadata;
        this.jdbc = jdbc;
        // L2 的输入是"平台已采集的 schema"，因此直接查 aspect 表（datasetSchema.fields）
        this.l2Validator = new SqlParseL2Validator(this::knownColumns);
    }

    /**
     * 平台已知的列名（来自已采集的 {@code datasetSchema}）。
     *
     * <p>查不到就返回空集合 —— L2 会据此记一条"缺 schema"的发现，而不是猜。
     * 每次解析会话内缓存，避免同一条 SQL 里反复查同一个上游。
     */
    private final Map<String, Set<String>> columnCache = new LinkedHashMap<>();

    private Set<String> knownColumns(String datasetUrn) {
        if (datasetUrn == null) {
            return Set.of();
        }
        return columnCache.computeIfAbsent(datasetUrn, urn -> {
            try {
                String schemaJson = jdbc.query("""
                        SELECT data::text FROM aspect WHERE urn = ? AND aspect_type = 'datasetSchema'
                        """, rs -> rs.next() ? rs.getString(1) : null, urn);
                if (schemaJson == null) {
                    return Set.of();
                }
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(schemaJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                if (!(parsed.get("fields") instanceof List<?> fields)) {
                    return Set.of();
                }
                Set<String> columns = new LinkedHashSet<>();
                for (Object item : fields) {
                    if (item instanceof Map<?, ?> field && field.get("name") != null) {
                        columns.add(String.valueOf(field.get("name")).toLowerCase(java.util.Locale.ROOT));
                    }
                }
                return columns;
            } catch (Exception e) {
                log.debug("读取 datasetSchema 失败（L2 将记为缺 schema）：{} → {}", urn, e.getMessage());
                return Set.of();
            }
        });
    }

    /** L2 发现的登记（失败不影响血缘写入：发现是运营信息，血缘是产出）。 */
    private int recordFindings(List<SqlParseL2Validator.Finding> findings, String targetUrn,
                               String statementHash, String dialect, String actor) {
        int recorded = 0;
        for (SqlParseL2Validator.Finding finding : findings) {
            try {
                jdbc.update("""
                        INSERT INTO lineage_check_finding
                            (check_type, severity, statement_hash, target_urn, resource, message,
                             evidence, source, actor)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        """, finding.checkType(), finding.severity(), statementHash, targetUrn,
                        finding.resource(), finding.message(), toJson(finding.evidence()),
                        SqlParseL2Validator.EDGE_SOURCE, actor);
                recorded++;
            } catch (RuntimeException e) {
                log.warn("登记 L2 发现失败（不影响血缘）：{}", e.getMessage());
            }
        }
        return recorded;
    }

    @Transactional
    public Map<String, Object> ingest(String sql, String dialect, String namespace, String actor,
                                      boolean recordSamples, boolean dryRun) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        String effectiveDialect = dialect == null || dialect.isBlank() ? "hive" : dialect;
        String effectiveNamespace = namespace == null || namespace.isBlank() ? "prod" : namespace;

        resolver.clearCache();
        SqlParseSidecarClient.ParseResponse parsed =
                sidecar.parseStatements(sql, effectiveDialect, effectiveNamespace, null);

        int tableEdges = 0;
        int columnEdges = 0;
        int failed = 0;
        int downgraded = 0;
        int samplesRecorded = 0;
        int l2EdgesTotal = 0;
        int l2FindingsTotal = 0;
        int findingsRecorded = 0;
        List<String> unresolved = new ArrayList<>();
        List<Map<String, Object>> statementSummaries = new ArrayList<>();
        columnCache.clear();

        for (SqlParseSidecarClient.ParseStatement statement : parsed.results()) {
            String parseLevel = statement.parseLevel() == null ? "derived" : statement.parseLevel();
            if ("failed".equals(parseLevel)) {
                failed++;
            } else if (!"exact".equals(parseLevel)) {
                downgraded++;
            }

            if (!dryRun && recordSamples && !"exact".equals(parseLevel)) {
                samplesRecorded += recordSample(statement, parseLevel, effectiveDialect);
            }

            String targetUrn = statement.targetTable() == null
                    ? null : resolver.resolve(statement.targetTable(), effectiveNamespace);
            if (statement.targetTable() != null && targetUrn == null) {
                unresolved.add(statement.targetTable());
            }

            Map<String, String> resolvedSources = new LinkedHashMap<>();
            List<String> sources = statement.sourceTables() == null ? List.of() : statement.sourceTables();
            for (String rawSource : sources) {
                String urn = resolver.resolve(rawSource, effectiveNamespace);
                if (urn == null) {
                    unresolved.add(rawSource);
                } else {
                    resolvedSources.put(rawSource.toLowerCase(java.util.Locale.ROOT), urn);
                }
            }

            if (!dryRun && targetUrn != null) {
                metadata.ensureEntity(targetUrn, "Dataset", null, null);
                for (String sourceUrn : resolvedSources.values()) {
                    metadata.ensureEntity(sourceUrn, "Dataset", null, null);
                    metadata.upsertEdge(sourceUrn, targetUrn, "derivesFrom",
                            LineageService.SOURCE_SQL_PARSE, confidenceFor(parseLevel),
                            null, null, null, "VALUE", parseLevel, null, null);
                    tableEdges++;
                }
            }

            List<SqlParseSidecarClient.ColumnEdge> edges =
                    statement.columnEdges() == null ? List.of() : statement.columnEdges();
            for (SqlParseSidecarClient.ColumnEdge edge : edges) {
                String sourceUrn = resolvedSources.get(
                        edge.fromTable() == null ? "" : edge.fromTable().toLowerCase(java.util.Locale.ROOT));
                if (sourceUrn == null && edge.fromTable() != null) {
                    sourceUrn = resolver.resolve(edge.fromTable(), effectiveNamespace);
                }
                if (sourceUrn == null) {
                    unresolved.add(edge.fromTable());
                    continue;
                }
                if (dryRun || targetUrn == null) {
                    columnEdges++;
                    continue;
                }
                String fromColumnUrn = TableResolver.columnUrn(sourceUrn, edge.fromColumn());
                String toColumnUrn = TableResolver.columnUrn(targetUrn, edge.toColumn());
                metadata.ensureEntity(fromColumnUrn, "Column", null, null);
                metadata.ensureEntity(toColumnUrn, "Column", null, null);
                metadata.upsertEdge(fromColumnUrn, toColumnUrn, "derivesFrom",
                        LineageService.SOURCE_SQL_PARSE,
                        edge.confidence() > 0 ? edge.confidence() : confidenceFor(parseLevel),
                        edge.transform(), edge.expression(), edge.cardinality(),
                        edge.dependencyKind() == null ? "VALUE" : edge.dependencyKind(),
                        edge.parseLevel() == null ? parseLevel : edge.parseLevel(),
                        null, null);
                columnEdges++;
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("statementType", statement.statementType());
            summary.put("targetTable", statement.targetTable());
            summary.put("targetUrn", targetUrn);
            summary.put("sourceTables", sources);
            summary.put("resolvedSources", resolvedSources.values());
            summary.put("parseLevel", parseLevel);
            summary.put("columnEdges", edges.size());
            summary.put("error", statement.error());
            summary.put("warnings", statement.warnings());

            // ---- L2 校验：用平台已有的 schema 把"解析不出来"变成"推得出来" ----
            // 只在解析器没能给出 exact 结果时才需要（exact 的语句没有信息缺口）
            if (!"exact".equals(parseLevel)) {
                SqlParseL2Validator.Result l2 = l2Validator.validate(targetUrn,
                        List.copyOf(resolvedSources.values()),
                        statement.warnings() == null ? List.of() : statement.warnings(),
                        parseLevel, "failed".equals(parseLevel));
                int l2Edges = 0;
                for (SqlParseL2Validator.ResolvedEdge resolved : l2.edges()) {
                    l2Edges++;
                    if (dryRun || targetUrn == null) {
                        continue;
                    }
                    String fromColumnUrn = TableResolver.columnUrn(resolved.fromUrn(), resolved.fromColumn());
                    String toColumnUrn = TableResolver.columnUrn(resolved.toUrn(), resolved.toColumn());
                    metadata.ensureEntity(fromColumnUrn, "Column", null, null);
                    metadata.ensureEntity(toColumnUrn, "Column", null, null);
                    metadata.upsertEdge(fromColumnUrn, toColumnUrn, "derivesFrom",
                            SqlParseL2Validator.EDGE_SOURCE, resolved.confidence(),
                            resolved.transform(), resolved.expression(), null, "VALUE",
                            resolved.parseLevel(), null, null);
                }
                if (!dryRun) {
                    findingsRecorded += recordFindings(l2.findings(), targetUrn,
                            sha256(statement.sql() == null ? "" : statement.sql()), effectiveDialect, actor);
                }
                l2EdgesTotal += l2Edges;
                l2FindingsTotal += l2.findings().size();
                summary.put("l2Edges", l2Edges);
                summary.put("l2Findings", l2.findings().stream().map(SqlParseL2Validator.Finding::checkType).toList());
            }
            statementSummaries.add(summary);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dialect", parsed.dialect());
        payload.put("sqlglotVersion", parsed.sqlglotVersion());
        payload.put("namespace", effectiveNamespace);
        payload.put("actor", actor);
        payload.put("dryRun", dryRun);
        payload.put("statements", parsed.statements());
        payload.put("failed", failed);
        payload.put("downgraded", downgraded);
        payload.put("tableEdges", tableEdges);
        payload.put("columnEdges", columnEdges);
        payload.put("l2Edges", l2EdgesTotal);
        payload.put("l2Findings", l2FindingsTotal);
        payload.put("l2FindingsRecorded", findingsRecorded);
        payload.put("l2Note", (l2EdgesTotal + l2FindingsTotal) == 0 ? null
                : "L2 校验层：用**平台已采集的 schema** 补出解析器做不到的列级血缘（SELECT * 展开、"
                        + "无表限定列消歧）。补出的边来源标记为 " + SqlParseL2Validator.EDGE_SOURCE
                        + "、parseLevel=derived、置信度 0.6–0.65 —— 它不是 SQL 文本直接给出的，"
                        + "使用者有权区分。推不出来的部分登记在 /api/v1/lineage/checks，不猜。");
        payload.put("samplesRecorded", samplesRecorded);
        payload.put("unresolvedTables", unresolved.stream().distinct().toList());
        payload.put("unresolvedNote", unresolved.isEmpty() ? null
                : "未解析到 URN 的表名不会写边（宁可缺边不猜）。常见原因：该表尚未被采集，"
                        + "或 SQL 里用了与平台不一致的 schema 前缀。");
        payload.put("details", statementSummaries);
        if (dryRun) {
            payload.put("note", "dryRun=true：只解析不写库（用于预览与调试方言）");
        }
        log.info("SQL 解析入库：语句 {}，表级边 {}，列级边 {}，L2 补边 {}，L2 发现 {}，未解析表 {}，样本 {}",
                parsed.statements(), tableEdges, columnEdges, l2EdgesTotal, findingsRecorded,
                payload.get("unresolvedTables"), samplesRecorded);
        return payload;
    }

    /**
     * 登记"不够好"的解析样本（按 (dialect, sql_hash, parse_level) 聚合计数）。
     *
     * <p>这是把方言覆盖率变成可运营指标的关键：能看出"哪类 SQL 解析不了、有多少"。
     */
    private int recordSample(SqlParseSidecarClient.ParseStatement statement, String parseLevel,
                             String dialect) {
        String sqlText = statement.sql() == null ? "" : statement.sql();
        String hash = sha256(sqlText);
        String excerpt = sqlText.length() > 500 ? sqlText.substring(0, 500) : sqlText;
        String warnings = toJson(statement.warnings() == null ? List.of() : statement.warnings());
        try {
            jdbc.update("""
                    INSERT INTO lineage_parse_sample
                        (dialect, statement_type, parse_level, error, warnings, sql_hash,
                         sql_excerpt, sql_full, occurrences, first_seen, last_seen)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, 1, now(), now())
                    ON CONFLICT (dialect, sql_hash, parse_level) DO UPDATE
                        SET occurrences = lineage_parse_sample.occurrences + 1,
                            last_seen = now(),
                            error = COALESCE(EXCLUDED.error, lineage_parse_sample.error)
                    """,
                    dialect, statement.statementType(), parseLevel, statement.error(),
                    warnings, hash, excerpt, sqlText);
            return 1;
        } catch (RuntimeException e) {
            // 样本登记失败不能回滚整次解析：血缘本身是有效产出，样本只是运营指标
            log.warn("登记解析样本失败（不影响已写入的血缘）：{}", e.getMessage());
            return 0;
        }
    }

    private static double confidenceFor(String parseLevel) {
        return CONFIDENCE.getOrDefault(parseLevel, 0.5);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
