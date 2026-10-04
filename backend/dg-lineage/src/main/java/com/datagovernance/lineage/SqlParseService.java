package com.datagovernance.lineage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
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

    public SqlParseService(SqlParseSidecarClient sidecar, TableResolver resolver,
                           MetadataService metadata, JdbcTemplate jdbc) {
        this.sidecar = sidecar;
        this.resolver = resolver;
        this.metadata = metadata;
        this.jdbc = jdbc;
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
        List<String> unresolved = new ArrayList<>();
        List<Map<String, Object>> statementSummaries = new ArrayList<>();

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
        payload.put("samplesRecorded", samplesRecorded);
        payload.put("unresolvedTables", unresolved.stream().distinct().toList());
        payload.put("unresolvedNote", unresolved.isEmpty() ? null
                : "未解析到 URN 的表名不会写边（宁可缺边不猜）。常见原因：该表尚未被采集，"
                        + "或 SQL 里用了与平台不一致的 schema 前缀。");
        payload.put("details", statementSummaries);
        if (dryRun) {
            payload.put("note", "dryRun=true：只解析不写库（用于预览与调试方言）");
        }
        log.info("SQL 解析入库：语句 {}，表级边 {}，列级边 {}，未解析表 {}，样本 {}",
                parsed.statements(), tableEdges, columnEdges,
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
