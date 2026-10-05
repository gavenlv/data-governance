package com.datagovernance.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.resolve.TableResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 引擎侧访问审计摄入（能力 {@code policy.engine-audit-ingest}，docs/09 §9.7）。
 *
 * <p>它补上的是审计闭环的最后一块拼图。在它之前，平台只能回答"谁被批准了什么"，
 * 回答不了两个审计真正在意的问题：
 * <ol>
 *   <li><b>批准了但从来没被用过</b> → 权限该不该回收（此前只能靠人工判断，见
 *       {@link AccessAuditService#leastPrivilegeReview} 的"证据不足"分支）；</li>
 *   <li><b>被访问了但从来没被批准过</b> → 有没有绕过治理的直连访问
 *       （这正是覆盖率度量里那条"直连绕过无法检测"的盲区）。</li>
 * </ol>
 *
 * <p>三条纪律（都来自审计场景的真实教训）：
 * <ul>
 *   <li><b>原始记录留着</b>：解析规则会变，原始日志不会 —— 没有原文就无法重放；</li>
 *   <li><b>解析失败也入库</b>（{@code resolved=false} + 原因）：丢弃等于宣称"这次访问没发生"；</li>
 *   <li><b>幂等</b>：日志采集器重启重放是常态，没有去重键会把一次访问记成很多次，污染统计。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：本实现接受**推送**（引擎/日志采集器把记录发过来），
 * 不主动去读 Trino/Ranger 的日志文件或数据库 —— 那属于部署环境的采集配置，
 * 且各企业落盘格式差异极大。因此"接入了多少"这件事由 {@link #coverage} 显式回报，
 * 而不是假设已在运行。
 */
@Service
public class EngineAuditService {

    /** 支持解析的引擎类型（与 DDL 的 CHECK 约束一致）。 */
    private static final List<String> ENGINES = List.of("trino", "ranger", "warehouse", "superset", "other");

    /** 单批上限：超限拒绝而不是截断（截断会让人以为"这批都收了"）。 */
    private static final int MAX_BATCH = 20000;

    private final JdbcTemplate jdbc;
    private final TableResolver resolver;

    public EngineAuditService(JdbcTemplate jdbc, TableResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    /** 一批引擎审计记录。 */
    public record IngestRequest(String engine, String namespace, List<Map<String, Object>> records) {
    }

    /**
     * 摄入一批记录。
     *
     * <p>返回里必须同时给出**接受 / 重复 / 未解析 / 被拒**四个数字：
     * 只报"成功 N 条"会掩盖"其中 300 条根本没解析出资源"这种问题。
     */
    @Transactional
    public Map<String, Object> ingest(IngestRequest request, String actor) {
        if (request == null || request.engine() == null || request.engine().isBlank()) {
            throw new AccessPolicyException("必须给出 engine（trino / ranger / warehouse / superset / other）");
        }
        String engine = request.engine().trim().toLowerCase(java.util.Locale.ROOT);
        if (!ENGINES.contains(engine)) {
            throw new AccessPolicyException("不支持的 engine：" + engine + "（支持 " + String.join(" / ", ENGINES) + "）");
        }
        String namespace = request.namespace() == null || request.namespace().isBlank()
                ? "prod" : request.namespace().trim();
        List<Map<String, Object>> records = request.records() == null ? List.of() : request.records();
        if (records.isEmpty()) {
            throw new AccessPolicyException("records 不能为空");
        }
        if (records.size() > MAX_BATCH) {
            // 与 Edge Agent 同样的纪律：超限拒绝而不是截断 —— 截断会让人以为"这批都收了"
            throw new AccessPolicyException("单批记录数 " + records.size() + " 超过上限 " + MAX_BATCH
                    + "：请分批推送（**不会截断后写入**，截断会让人以为这批都收了）");
        }

        resolver.clearCache();
        int accepted = 0;
        int duplicated = 0;
        int unresolved = 0;
        int rejected = 0;
        List<String> problems = new ArrayList<>();
        Instant windowFrom = null;
        Instant windowTo = null;

        for (Map<String, Object> raw : records) {
            Normalized normalized;
            try {
                normalized = normalize(engine, namespace, raw);
            } catch (AccessPolicyException e) {
                rejected++;
                if (problems.size() < 5) {
                    problems.add(e.getMessage());
                }
                continue;
            }
            String urn = resolver.resolve(normalized.resourceRaw(), namespace);
            String note = urn == null
                    ? "未能唯一解析到平台资产（0 条或多条命中即不解析）：该记录仍保留，可用于人工核对"
                    : null;
            if (urn == null) {
                unresolved++;
            }
            // text[] 用 TextArrays.of 显式绑定：JdbcTemplate 直接传 String[] 会在执行期
            // 抛 SQLFeatureNotSupportedException（这个坑在本项目出现过 5 次，见该类注释）
            final Normalized item = normalized;
            final String resolvedUrn = urn;
            final String resolveNote = note;
            int rows = jdbc.update(connection -> {
                java.sql.PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO engine_audit_record (engine, record_hash, external_id, event_time, actor,
                                                         operation, resource_raw, resource_urn, columns,
                                                         rows_scanned, bytes_scanned, succeeded, source_ip,
                                                         resolved, resolve_note, raw, ingested_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                        ON CONFLICT (engine, record_hash) DO NOTHING
                        """);
                ps.setString(1, engine);
                ps.setString(2, item.hash());
                ps.setString(3, item.externalId());
                ps.setTimestamp(4, java.sql.Timestamp.from(item.eventTime()));
                ps.setString(5, item.actor());
                ps.setString(6, item.operation());
                ps.setString(7, item.resourceRaw());
                ps.setString(8, resolvedUrn);
                ps.setArray(9, com.datagovernance.core.sql.TextArrays.of(connection, item.columns()));
                if (item.rowsScanned() == null) {
                    ps.setNull(10, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(10, item.rowsScanned());
                }
                if (item.bytesScanned() == null) {
                    ps.setNull(11, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(11, item.bytesScanned());
                }
                ps.setBoolean(12, item.succeeded());
                ps.setString(13, item.sourceIp());
                ps.setBoolean(14, resolvedUrn != null);
                ps.setString(15, resolveNote);
                ps.setString(16, toJson(item.raw()));
                ps.setString(17, actor);
                return ps;
            });
            if (rows == 0) {
                duplicated++;
            } else {
                accepted++;
            }
            if (windowFrom == null || normalized.eventTime().isBefore(windowFrom)) {
                windowFrom = normalized.eventTime();
            }
            if (windowTo == null || normalized.eventTime().isAfter(windowTo)) {
                windowTo = normalized.eventTime();
            }
        }

        jdbc.update("""
                INSERT INTO engine_audit_ingest (engine, received, accepted, duplicated, unresolved, rejected,
                                                 window_from, window_to, note, ingested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, engine, records.size(), accepted, duplicated, unresolved, rejected,
                windowFrom == null ? null : java.sql.Timestamp.from(windowFrom),
                windowTo == null ? null : java.sql.Timestamp.from(windowTo),
                problems.isEmpty() ? null : String.join("; ", problems), actor);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", engine);
        payload.put("namespace", namespace);
        payload.put("received", records.size());
        payload.put("accepted", accepted);
        payload.put("duplicated", duplicated);
        payload.put("unresolved", unresolved);
        payload.put("rejected", rejected);
        payload.put("rejectedSamples", problems);
        payload.put("windowFrom", windowFrom);
        payload.put("windowTo", windowTo);
        payload.put("note", "重复记录按内容哈希去重（日志采集器重启重放不会污染统计）；"
                + "未解析到平台资产的记录**仍然保留**（resolved=false + 原因），"
                + "丢弃等于宣称这次访问没有发生");
        return payload;
    }

    /** 一条记录归一化后的形态。 */
    private record Normalized(String hash, String externalId, Instant eventTime, String actor,
                              String operation, String resourceRaw, List<String> columns,
                              Long rowsScanned, Long bytesScanned, boolean succeeded, String sourceIp,
                              Map<String, Object> raw) {
    }

    /**
     * 归一化：兼容 Trino 查询事件、Ranger 访问审计、通用仓库日志三类字段命名。
     *
     * <p>字段名不统一是这一层的**本质困难**：不同引擎的审计日志字段名完全不同
     * （Trino 用 {@code user}/{@code queryId}，Ranger 用 {@code reqUser}/{@code resource}）。
     * 因此这里做别名映射，并要求"时间 + 主体 + 资源"三要素齐备，缺一即拒（并说明缺什么）。
     */
    private Normalized normalize(String engine, String namespace, Map<String, Object> raw) {
        Instant eventTime = firstInstant(raw, "eventTime", "event_time", "timestamp", "time", "queryTime",
                "eventTimeMillis", "createTime", "accessTime");
        String actor = firstString(raw, "actor", "user", "reqUser", "requestUser", "principal",
                "accessedBy", "username");
        String resource = firstString(raw, "resource", "resourceRaw", "table", "tableName", "object",
                "resourceName", "target");
        if (eventTime == null) {
            throw new AccessPolicyException("记录缺少时间字段（eventTime/timestamp/time 之一）");
        }
        if (actor == null) {
            throw new AccessPolicyException("记录缺少主体字段（user/reqUser/actor 之一）");
        }
        if (resource == null) {
            throw new AccessPolicyException("记录缺少资源字段（resource/table/object 之一）");
        }
        String operation = firstString(raw, "operation", "op", "access", "action", "queryType");
        String externalId = firstString(raw, "externalId", "queryId", "id", "requestId");
        List<String> columns = stringList(raw, "columns", "columnList", "accessedColumns");
        Long rows = firstLong(raw, "rowsScanned", "rows", "inputRows", "processedRows");
        Long bytes = firstLong(raw, "bytesScanned", "bytes", "inputBytes", "processedBytes");
        Boolean ok = firstBoolean(raw, "succeeded", "success", "result");
        String sourceIp = firstString(raw, "sourceIp", "clientIp", "remoteAddr", "ip");

        // 去重键：引擎 + 引擎侧 ID（有的话）优先；没有则用"时间+主体+资源+操作+列集合"
        // ——刻意**不含原始 JSON 全量**，否则同一查询多几个无关字段就会被当成新记录
        String keySource = externalId != null
                ? engine + "|" + externalId
                : engine + "|" + eventTime + "|" + actor + "|" + resource + "|"
                        + (operation == null ? "" : operation) + "|" + String.join(",", columns);
        return new Normalized(sha256(keySource), externalId, eventTime, actor,
                operation == null ? "SELECT" : operation.toUpperCase(java.util.Locale.ROOT),
                resource.trim(), columns, rows, bytes, ok == null || ok, sourceIp,
                Map.of("engine", engine, "namespace", namespace, "received", raw));
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 覆盖情况：**"接入了多少"必须可查**。
     *
     * <p>没有这个接口，"引擎审计已接入"就只是一句话 —— 而审计结论的可信度取决于
     * "观测窗口有多长、覆盖了哪些引擎、多少比例解析到了资产"。
     */
    public Map<String, Object> coverage() {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> byEngine = jdbc.queryForList("""
                SELECT engine, COUNT(*) AS records, MIN(event_time) AS window_from, MAX(event_time) AS window_to,
                       COUNT(*) FILTER (WHERE resolved) AS resolved,
                       COUNT(*) FILTER (WHERE NOT resolved) AS unresolved,
                       COUNT(DISTINCT actor) AS actors
                  FROM engine_audit_record GROUP BY engine ORDER BY records DESC
                """);
        payload.put("byEngine", byEngine);
        payload.put("configured", !byEngine.isEmpty());
        payload.put("lastIngest", jdbc.queryForList("""
                SELECT engine, received, accepted, duplicated, unresolved, rejected,
                       window_from, window_to, note, ingested_at
                  FROM engine_audit_ingest ORDER BY ingested_at DESC LIMIT 5
                """));
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM engine_audit_record", Integer.class);
        Integer unresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM engine_audit_record WHERE NOT resolved", Integer.class);
        payload.put("totalRecords", total);
        payload.put("unresolvedRecords", unresolved);
        payload.put("resolutionNote", (unresolved != null && unresolved > 0)
                ? "有 " + unresolved + " 条记录未能解析到平台资产：它们**没有被丢弃**，"
                        + "可在 GET /api/v1/access/engine-audit 中查看并按需补采资产"
                : "全部记录都解析到了平台资产");
        payload.put("howToFeed", """
                推送方式（任选其一，都是同一套 API）：
                  1. Trino：订阅 query 完成事件（event listener），把 user/queryId/表名/列名 发到
                     POST /api/v1/access/engine-audit；
                  2. Ranger：把访问审计落库后用采集脚本批量推送；
                  3. 数仓：导出查询日志（JSONL）后用 tools/engine_audit_load.py 分批推送。
                幂等键是"引擎 + 引擎侧 ID（或时间+主体+资源+操作+列）"，因此**重放安全**。""");
        return payload;
    }

    /** 原始记录查询（审计取证用；支持按资源/主体/时间窗过滤）。 */
    public List<Map<String, Object>> records(String resourceUrn, String actor, Integer days, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, engine, external_id, event_time, actor, operation, resource_raw, resource_urn,
                       columns, rows_scanned, succeeded, source_ip, resolved, resolve_note, ingested_at
                  FROM engine_audit_record WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (resourceUrn != null && !resourceUrn.isBlank()) {
            sql.append(" AND resource_urn = ?");
            params.add(resourceUrn);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = ?");
            params.add(actor);
        }
        if (days != null && days > 0) {
            sql.append(" AND event_time > now() - (? || ' days')::interval");
            params.add(String.valueOf(days));
        }
        sql.append(" ORDER BY event_time DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 1000));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 每个授权最近一次被使用的证据（供最小权限复盘使用）。
     *
     * <p>返回"该主体 + 该资源"在窗口内是否被真实访问过，以及最近访问时间。
     * <b>观测窗口短于授权时长时不能当作"未使用"</b> —— 因此同时返回窗口起止，由调用方判断。
     */
    public Map<String, Object> usageEvidence(String subject, String resourceUrn, int days) {
        Map<String, Object> evidence = jdbc.queryForMap("""
                SELECT COUNT(*) AS queries,
                       MAX(event_time) AS last_used_at,
                       COALESCE(SUM(rows_scanned), 0) AS rows_scanned,
                       COUNT(DISTINCT operation) AS operations
                  FROM engine_audit_record
                 WHERE actor = ? AND resource_urn = ?
                   AND event_time > now() - (? || ' days')::interval
                """, subject, resourceUrn, String.valueOf(Math.max(days, 1)));
        Map<String, Object> payload = new LinkedHashMap<>(evidence);
        payload.put("windowDays", Math.max(days, 1));
        payload.put("observabilityNote", "usage 只能证明「用过」，不能证明「没用过」——"
                + "若观测窗口短于授权时长，或该引擎尚未接入审计，则「未使用」不成立");
        return payload;
    }

    /**
     * 观测窗口：判断"证据是否充分"的依据。
     *
     * <p>这里刻意同时给出**天与小时**：只看天会把"刚接入一小时"算成 0 天，
     * 于是"有使用证据"也被当成"没有数据"（这个错误真实发生过）。
     * 而真正的判断标准不是窗口多长，是**窗口有没有覆盖授权的整个生命周期** ——
     * 见 {@link #coversGrantLife}。
     */
    public Map<String, Object> observationWindow() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT MIN(event_time) AS window_from, MAX(event_time) AS window_to, COUNT(*) AS records
                  FROM engine_audit_record
                """);
        Map<String, Object> payload = new LinkedHashMap<>(row);
        Object from = row.get("window_from");
        long records = row.get("records") == null ? 0 : ((Number) row.get("records")).longValue();
        if (from == null) {
            payload.put("windowDays", 0);
            payload.put("windowHours", 0);
            payload.put("note", "没有任何引擎审计记录：既不能证明「用过」，也不能证明「没用过」");
            return payload;
        }
        java.time.Duration span = java.time.Duration.between(
                ((java.sql.Timestamp) from).toInstant(), Instant.now());
        payload.put("windowDays", span.toDays());
        payload.put("windowHours", span.toHours());
        payload.put("note", records + " 条记录，跨度 " + span.toHours() + " 小时");
        return payload;
    }

    /**
     * 观测窗口是否覆盖了某个授权的整个生命周期。
     *
     * <p>这是"能不能判定未使用"的**唯一**正确标准：只有当我们从**授权创建之前**就开始观测，
     * 「零访问」才真的意味着「从未被使用」。窗口比授权晚开始，就只能是"证据不足"——
     * 用 7 天日志去否定一条 200 天前的授权，是审计里最常见的错误结论。
     */
    public boolean coversGrantLife(java.time.Instant grantedAt) {
        if (grantedAt == null) {
            return false;
        }
        Object from = jdbc.queryForObject(
                "SELECT MIN(event_time) FROM engine_audit_record", java.sql.Timestamp.class);
        return from != null && !((java.sql.Timestamp) from).toInstant().isAfter(grantedAt);
    }

    public boolean hasAnyRecord() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM engine_audit_record", Integer.class);
        return count != null && count > 0;
    }

    /**
     * 未授权但实际被访问的资源（"绕过治理的访问"信号）。
     *
     * <p>这是接入引擎审计之后**新出现**的能力：此前的覆盖率报告里写着
     * "直连绕过无法检测"，接入仓库/引擎日志后，直连访问会留下记录，
     * 于是"访问了但没有对应授权"就变成一个可查询的事实。
     */
    public Map<String, Object> unapprovedAccess(int days, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.actor, r.resource_urn, r.resource_raw, r.engine,
                       COUNT(*) AS queries, MAX(r.event_time) AS last_access_at,
                       MIN(r.event_time) AS first_access_at
                  FROM engine_audit_record r
                 WHERE r.resolved AND r.event_time > now() - (? || ' days')::interval
                   AND NOT EXISTS (
                        SELECT 1 FROM access_grant g
                         WHERE g.status = 'ACTIVE' AND g.subject = r.actor
                           AND (g.resource_urn = r.resource_urn
                                OR (g.granularity = 'DATASET' AND r.resource_urn LIKE g.resource_urn || '.%')))
                 GROUP BY r.actor, r.resource_urn, r.resource_raw, r.engine
                 ORDER BY queries DESC LIMIT ?
                """, String.valueOf(Math.max(days, 1)), Math.min(Math.max(limit, 1), 200));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowDays", Math.max(days, 1));
        payload.put("count", rows.size());
        payload.put("unapproved", rows);
        payload.put("note", "这些是**实际发生但平台没有对应授权**的访问：可能是直连绕过治理点，"
                + "也可能是授权记录还没补。两种都需要人工确认 —— 平台只提供事实，不自动定性。");
        return payload;
    }

    // ------------------------------------------------------------------ 工具

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String firstString(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static Instant firstInstant(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                long millis = number.longValue();
                // 秒级与毫秒级时间戳都可能出现：小于 10^11 视为秒
                return millis < 100_000_000_000L ? Instant.ofEpochSecond(millis) : Instant.ofEpochMilli(millis);
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                return Instant.parse(text);
            } catch (RuntimeException ignored) {
                try {
                    return java.time.OffsetDateTime.parse(text).toInstant();
                } catch (RuntimeException ignoredToo) {
                    try {
                        return java.sql.Timestamp.valueOf(text.replace('T', ' ').replace("Z", "")).toInstant();
                    } catch (RuntimeException noWay) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Long firstLong(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    // 继续找下一个别名
                }
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value != null) {
                String text = String.valueOf(value).trim();
                if ("SUCCESS".equalsIgnoreCase(text) || "ALLOWED".equalsIgnoreCase(text)
                        || "true".equalsIgnoreCase(text) || "1".equals(text)) {
                    return true;
                }
                if ("FAILED".equalsIgnoreCase(text) || "DENIED".equalsIgnoreCase(text)
                        || "false".equalsIgnoreCase(text) || "0".equals(text)) {
                    return false;
                }
            }
        }
        return null;
    }

    private static List<String> stringList(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value instanceof List<?> list) {
                return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
            }
            if (value instanceof String text && !text.isBlank()) {
                return java.util.Arrays.stream(text.split(",")).map(String::trim)
                        .filter(item -> !item.isEmpty()).toList();
            }
        }
        return List.of();
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
