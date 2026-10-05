package com.datagovernance.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLO 与事故管理（docs/09 §9.4）。
 *
 * <p>两类目标刻意分开：
 * <ul>
 *   <li><b>SLO</b> 回答"我们承诺的服务水平守住了吗" —— 达成率 + **错误预算**，时序落库；</li>
 *   <li><b>事故</b> 回答"出事了谁在处理、影响谁" —— 时间线 + 影响面（直接调用血缘影响分析）
 *       + 复盘，并且**每次事故必须沉淀出一条规则或检测器**（文档点名的闭环）。</li>
 * </ul>
 *
 * <p>达成率是**真实算出来的**，不是配出来的：新鲜度来自 profile_metric 的 freshness_seconds，
 * 质量通过率来自 rule_run，可用性来自 collect_run。没有数据时明确回报
 * {@code no_data}，而不是给一个 100% 的假达成率。
 */
@Service
public class SloService {

    private final JdbcTemplate jdbc;
    private final com.datagovernance.lineage.ImpactService impact;
    private final AnomalyService anomalies;

    public SloService(JdbcTemplate jdbc, com.datagovernance.lineage.ImpactService impact,
                      AnomalyService anomalies) {
        this.jdbc = jdbc;
        this.impact = impact;
        this.anomalies = anomalies;
    }

    // ------------------------------------------------------------------ 定义

    @Transactional
    public Map<String, Object> upsertSlo(Map<String, Object> document) {
        String name = str(document.get("name"));
        String type = str(document.get("sloType"));
        if (name == null || name.isBlank()) {
            throw new QualityException("SLO 缺少 name");
        }
        if (!List.of("freshness", "quality_pass_rate", "availability", "schema_stability").contains(type)) {
            throw new QualityException("不支持的 sloType：" + type
                    + "（freshness / quality_pass_rate / availability / schema_stability）");
        }
        Double target = number(document.get("target"));
        if (target == null || target <= 0 || target > 1) {
            throw new QualityException("target 必须在 (0,1] 之间：" + target);
        }
        if ("freshness".equals(type) && number(document.get("thresholdSeconds")) == null) {
            throw new QualityException("freshness 类 SLO 必须给出 thresholdSeconds（多旧算不合格）");
        }
        jdbc.update("""
                INSERT INTO slo_definition (name, description, slo_type, target, threshold_seconds,
                                            window_days, resource_scope, owner, status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (name) DO UPDATE
                    SET description = EXCLUDED.description, slo_type = EXCLUDED.slo_type,
                        target = EXCLUDED.target, threshold_seconds = EXCLUDED.threshold_seconds,
                        window_days = EXCLUDED.window_days, resource_scope = EXCLUDED.resource_scope,
                        owner = EXCLUDED.owner, status = EXCLUDED.status, updated_at = now()
                """, name, str(document.get("description")), type, target,
                number(document.get("thresholdSeconds")) == null ? null
                        : number(document.get("thresholdSeconds")).intValue(),
                document.get("windowDays") == null ? 30 : ((Number) document.get("windowDays")).intValue(),
                toJson(document.get("resourceScope") == null ? Map.of() : document.get("resourceScope")),
                str(document.get("owner")),
                document.get("status") == null ? "ACTIVE" : String.valueOf(document.get("status")));
        return Map.of("name", name, "sloType", type, "target", target,
                "note", "达成率由平台**实际数据**计算（新鲜度取 profile_metric、通过率取 rule_run、"
                        + "可用性取 collect_run）；没有数据时会明确回报 no_data，不给假的 100%");
    }

    public List<Map<String, Object>> listSlos() {
        return jdbc.queryForList("""
                SELECT d.name, d.description, d.slo_type, d.target, d.threshold_seconds, d.window_days,
                       d.resource_scope, d.owner, d.status, d.updated_at,
                       s.attainment AS last_attainment, s.measured_at AS last_measured_at,
                       s.met AS last_met, s.error_budget_remaining
                  FROM slo_definition d
                  LEFT JOIN LATERAL (
                        SELECT attainment, measured_at, met, error_budget_remaining
                          FROM slo_snapshot WHERE slo_name = d.name
                         ORDER BY measured_at DESC LIMIT 1) s ON TRUE
                 ORDER BY d.name
                """);
    }

    // ------------------------------------------------------------------ 度量

    /** 计算并落库一名 SLO 的达成率。 */
    @Transactional
    public Map<String, Object> measure(String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM slo_definition WHERE name = ?", name);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("SLO 不存在：" + name);
        }
        Map<String, Object> slo = rows.get(0);
        String type = String.valueOf(slo.get("slo_type"));
        int windowDays = ((Number) slo.get("window_days")).intValue();
        double target = ((Number) slo.get("target")).doubleValue();
        String prefix = prefixOf(str(slo.get("resource_scope")));

        Measurement measurement = switch (type) {
            case "freshness" -> measureFreshness(prefix, windowDays,
                    ((Number) slo.get("threshold_seconds")).longValue());
            case "quality_pass_rate" -> measureQualityPassRate(prefix, windowDays);
            case "availability" -> measureAvailability(prefix, windowDays);
            case "schema_stability" -> measureSchemaStability(prefix, windowDays);
            default -> throw new QualityException("不支持的 sloType：" + type);
        };

        if (measurement.totalEvents() == 0) {
            // 没有数据时必须明说：给 100% 会让人以为"很健康"
            jdbc.update("""
                    INSERT INTO slo_snapshot (slo_name, window_start, total_events, good_events, attainment,
                                              met, error_budget_remaining, detail)
                    VALUES (?, now() - (? || ' days')::interval, 0, 0, 0, FALSE, NULL, CAST(? AS jsonb))
                    """, name, String.valueOf(windowDays),
                    toJson(Map.of("no_data", true, "reason", measurement.note())));
            return Map.of("slo", name, "sloType", type, "attainment", null, "met", false,
                    "noData", true, "note", measurement.note());
        }

        double attainment = (double) measurement.goodEvents() / measurement.totalEvents();
        boolean met = attainment >= target;
        // 错误预算：1 − target 是允许的失败比例；remaining = 还剩多少（负值说明已超支）
        double budget = 1.0 - target;
        double used = 1.0 - attainment;
        Double remaining = budget <= 0 ? (met ? 1.0 : -1.0) : Math.round((budget - used) * 10000) / 10000.0;

        jdbc.update("""
                INSERT INTO slo_snapshot (slo_name, window_start, total_events, good_events, attainment,
                                          met, error_budget_remaining, detail)
                VALUES (?, now() - (? || ' days')::interval, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, name, String.valueOf(windowDays), measurement.totalEvents(), measurement.goodEvents(),
                attainment, met, remaining, toJson(measurement.detail()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slo", name);
        payload.put("sloType", type);
        payload.put("target", target);
        payload.put("windowDays", windowDays);
        payload.put("totalEvents", measurement.totalEvents());
        payload.put("goodEvents", measurement.goodEvents());
        payload.put("attainment", Math.round(attainment * 10000) / 10000.0);
        payload.put("met", met);
        payload.put("errorBudgetRemaining", remaining);
        payload.put("budgetNote", remaining != null && remaining < 0
                ? "错误预算已超支：应当冻结非必要变更并优先修复（这是 SLO 的实际用途）"
                : "错误预算仍在预算内");
        payload.put("detail", measurement.detail());
        return payload;
    }

    private record Measurement(int totalEvents, int goodEvents, String note, Map<String, Object> detail) {
    }

    /** 新鲜度：用 profile_metric.freshness_seconds 在窗口内的每一次测量作为一个事件。 */
    private Measurement measureFreshness(String prefix, int windowDays, long thresholdSeconds) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pm.value_num, pm.window_start, pm.dataset_urn
                  FROM profile_metric pm
                 WHERE pm.metric = 'freshness_seconds' AND pm.value_num IS NOT NULL
                   AND pm.window_start > now() - (? || ' days')::interval
                   AND (CAST(? AS text) IS NULL OR pm.dataset_urn LIKE ?)
                """, String.valueOf(windowDays), prefix, prefix == null ? null : prefix + "%");
        if (rows.isEmpty()) {
            return new Measurement(0, 0,
                    "窗口内没有新鲜度测量：需要先对数据集跑剖析（profiling 才会产出 freshness_seconds）",
                    Map.of());
        }
        int good = 0;
        double worst = 0;
        for (Map<String, Object> row : rows) {
            double value = ((Number) row.get("value_num")).doubleValue();
            if (value >= 0 && value <= thresholdSeconds) {
                good++;
            }
            worst = Math.max(worst, value);
        }
        return new Measurement(rows.size(), good, null,
                Map.of("thresholdSeconds", thresholdSeconds,
                        "worstObservedSeconds", Math.round(worst),
                        "measuredSeries", rows.size()));
    }

    /** 质量通过率：窗口内 rule_run 的 PASS 占比。 */
    private Measurement measureQualityPassRate(String prefix, int windowDays) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM rule_run r
                 WHERE r.started_at > now() - (? || ' days')::interval
                   AND (CAST(? AS text) IS NULL OR r.dataset_urn LIKE ?)
                 GROUP BY 1
                """, String.valueOf(windowDays), prefix, prefix == null ? null : prefix + "%");
        int total = 0;
        int pass = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int count = ((Number) row.get("count")).intValue();
            total += count;
            byStatus.put(String.valueOf(row.get("status")), count);
            if ("PASS".equals(row.get("status"))) {
                pass = count;
            }
        }
        if (total == 0) {
            return new Measurement(0, 0,
                    "窗口内没有规则执行记录：需要先注册质量规则并执行（SKIPPED 不计入达成率）",
                    Map.of());
        }
        // SKIPPED 不算失败也不算成功：从分母里剔除，否则"没跑成"会被当成服务不达标
        int skipped = byStatus.getOrDefault("SKIPPED", 0);
        return new Measurement(total - skipped, pass, null,
                Map.of("byStatus", byStatus, "skippedExcluded", skipped,
                        "note", "SKIPPED（没能判定）从分母中剔除：它既不是通过也不是失败"));
    }

    /** 可用性：窗口内 collect_run 的 SUCCEEDED 占比。 */
    private Measurement measureAvailability(String prefix, int windowDays) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM collect_run
                 WHERE started_at > now() - (? || ' days')::interval
                 GROUP BY 1
                """, String.valueOf(windowDays));
        int total = 0;
        int ok = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int count = ((Number) row.get("count")).intValue();
            total += count;
            byStatus.put(String.valueOf(row.get("status")), count);
            if ("SUCCEEDED".equals(row.get("status"))) {
                ok = count;
            }
        }
        if (total == 0) {
            return new Measurement(0, 0, "窗口内没有采集运行记录", Map.of());
        }
        return new Measurement(total, ok, null, Map.of("byStatus", byStatus));
    }

    /** 结构稳定性：窗口内 schema 变更次数（用 artifact/事件流的 aspect 变更近似）。 */
    private Measurement measureSchemaStability(String prefix, int windowDays) {
        Integer changes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM event_log
                 WHERE event_type = 'ASPECT_UPSERTED' AND aspect_type = 'datasetSchema'
                   AND created_at > now() - (? || ' days')::interval
                """, Integer.class, String.valueOf(windowDays));
        int total = Math.max(1, windowDays);
        int changeCount = changes == null ? 0 : changes;
        // 目标语义：每天允许的变更次数上限 = target × 天数；作为"好事件"折算
        int good = Math.max(0, total - changeCount);
        return new Measurement(total, good, null,
                Map.of("schemaChanges", changeCount, "daysInWindow", windowDays,
                        "note", "以「窗口内没有 schema 变更的天数」作为达成口径（变更本身不一定是坏事，"
                                + "但高频变更是风险信号）"));
    }

    // ------------------------------------------------------------------ 事故

    /** 创建事故：从异常检测或人工发起，并自动算影响面。 */
    @Transactional
    public Map<String, Object> openIncident(Map<String, Object> document, String actor) {
        String title = str(document.get("title"));
        if (title == null || title.isBlank()) {
            throw new QualityException("事故必须给出 title");
        }
        String primary = str(document.get("primaryUrn"));
        List<String> affected = new ArrayList<>();
        Map<String, Object> impactSummary = Map.of();
        if (primary != null) {
            Map<String, Object> result = impact.analyze(primary, "downstream", 3, 0.0, false);
            affected.add(primary);
            for (Map<String, Object> node : asMapList(result.get("nodes"))) {
                affected.add(String.valueOf(node.get("urn")));
            }
            impactSummary = Map.of("affectedCount", result.get("affectedCount"),
                    "criticalCount", result.get("criticalCount"),
                    "truncated", result.get("reachedMaxDepth"));
        }

        String source = str(document.get("source"));
        // 校验在服务层完成：让 DB 的 CHECK 约束去兜底会变成 500（用户看到的是"服务器错误"而不是"source 不合法"）
        String resolvedSource = source == null ? "manual" : source;
        if (!List.of("manual", "anomaly", "slo", "contract_violation").contains(resolvedSource)) {
            throw new QualityException("不支持的 source：" + resolvedSource
                    + "（manual / anomaly / slo / contract_violation）");
        }
        String severity = document.get("severity") == null
                ? "MEDIUM" : String.valueOf(document.get("severity"));
        if (!List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)) {
            throw new QualityException("不支持的 severity：" + severity
                    + "（INFO / LOW / MEDIUM / HIGH / CRITICAL）");
        }
        Long id = jdbc.query(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO incident (title, severity, status, primary_urn, affected_urns, source,
                                          source_ref, owner, detected_at, created_by)
                    VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, now(), ?) RETURNING id
                    """);
            ps.setString(1, title);
            ps.setString(2, severity);
            ps.setString(3, primary);
            ps.setArray(4, connection.createArrayOf("text", affected.toArray()));
            ps.setString(5, resolvedSource);
            ps.setString(6, str(document.get("sourceRef")));
            ps.setString(7, str(document.get("owner")));
            ps.setString(8, actor);
            return ps;
        }, rs -> rs.next() ? rs.getLong(1) : null);

        addEvent(id, "DETECTED", title, actor, Map.of("impact", impactSummary));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", id);
        payload.put("title", title);
        payload.put("status", "OPEN");
        payload.put("affectedCount", affected.size() - (primary == null ? 0 : 1));
        payload.put("impact", impactSummary);
        payload.put("note", "影响面由血缘影响分析直接给出（受影响资产清单）；"
                + "事故的闭环要求：解决时必须沉淀出一条规则或检测器");
        return payload;
    }

    /** 从最近的未抑制异常自动开事故（联动 quality.anomaly）。 */
    @Transactional
    public Map<String, Object> openFromAnomalies(int minutes, String actor) {
        List<Map<String, Object>> found = anomalies.recentUnsuppressed(minutes);
        if (found.isEmpty()) {
            return Map.of("opened", 0,
                    "note", "该时间窗内没有被抑制之外的异常（PROPAGATED 已被上游抑制）");
        }
        // 同一主资产只开一个事故（避免一个故障开 N 个事故）
        Map<String, List<Map<String, Object>>> byDataset = new LinkedHashMap<>();
        for (Map<String, Object> row : found) {
            byDataset.computeIfAbsent(String.valueOf(row.get("dataset_urn")), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> opened = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byDataset.entrySet()) {
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM incident
                     WHERE primary_urn = ? AND status IN ('OPEN', 'MITIGATING')
                    """, Integer.class, entry.getKey());
            if (existing != null && existing > 0) {
                continue;
            }
            String worst = entry.getValue().stream()
                    .map(row -> String.valueOf(row.get("severity")))
                    .max(java.util.Comparator.comparingInt(AnomalyService::severityRank)).orElse("MEDIUM");
            opened.add(openIncident(Map.of(
                    "title", "质量异常：" + entry.getKey() + "（" + entry.getValue().size() + " 项指标越界）",
                    "severity", worst,
                    "primaryUrn", entry.getKey(),
                    "source", "anomaly",
                    "sourceRef", String.valueOf(entry.getValue().get(0).get("id"))), actor));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opened", opened.size());
        payload.put("incidents", opened);
        payload.put("note", "同一主资产只开一个事故：一个故障开 N 个事故会让响应者无从下手");
        return payload;
    }

    public List<Map<String, Object>> listIncidents(String status, int limit) {
        return jdbc.queryForList("""
                SELECT i.id, i.title, i.severity, i.status, i.primary_urn, i.affected_urns, i.source,
                       i.source_ref, i.owner, i.started_at, i.detected_at, i.resolved_at,
                       i.closed_loop_rule_urn, i.postmortem,
                       (SELECT COUNT(*) FROM incident_event e WHERE e.incident_id = i.id) AS event_count
                  FROM incident i
                 WHERE (CAST(? AS text) IS NULL OR i.status = ?)
                 ORDER BY i.started_at DESC LIMIT ?
                """, status, status, Math.min(Math.max(limit, 1), 200));
    }

    public Map<String, Object> incident(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM incident WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("事故不存在：" + id);
        }
        Map<String, Object> payload = new LinkedHashMap<>(rows.get(0));
        payload.put("timeline", jdbc.queryForList("""
                SELECT event_type, message, actor, detail, occurred_at
                  FROM incident_event WHERE incident_id = ? ORDER BY occurred_at
                """, id));
        return payload;
    }

    public Map<String, Object> addEvent(long incidentId, String type, String message, String actor,
                                        Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO incident_event (incident_id, event_type, message, actor, detail)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                """, incidentId, type, message, actor, toJson(detail == null ? Map.of() : detail));
        return Map.of("incidentId", incidentId, "eventType", type, "message", message);
    }

    /**
     * 解决事故：**必须关联一条沉淀出来的规则或检测器**，否则闭环就是空话。
     *
     * <p>这是 docs/09 §9.4 点名的闭环："每次故障必须产出一条规则或一个检测器"。
     * 允许 {@code noRuleNeeded} + 理由（有些事故确实是运维动作而非数据问题），
     * 但必须留痕说明为什么不需要。
     */
    @Transactional
    public Map<String, Object> resolve(long id, String actor, String closedLoopRuleUrn,
                                       String noRuleNeededReason, Map<String, Object> postmortem) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM incident WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("事故不存在：" + id);
        }
        if ((closedLoopRuleUrn == null || closedLoopRuleUrn.isBlank())
                && (noRuleNeededReason == null || noRuleNeededReason.isBlank())) {
            throw new QualityException("解决事故必须二选一：关联一条沉淀出的规则/检测器（closedLoopRuleUrn），"
                    + "或说明为什么这次不需要新规则（noRuleNeededReason）—— "
                    + "「每次故障必须产出一条规则或检测器」是闭环的定义");
        }
        jdbc.update("""
                UPDATE incident SET status = 'RESOLVED', resolved_at = now(),
                                    closed_loop_rule_urn = ?, postmortem = CAST(? AS jsonb)
                 WHERE id = ?
                """, closedLoopRuleUrn, toJson(postmortem == null ? Map.of() : postmortem), id);
        addEvent(id, "RESOLVED", closedLoopRuleUrn != null && !closedLoopRuleUrn.isBlank()
                ? "已解决，沉淀出规则：" + closedLoopRuleUrn
                : "已解决，无需新规则：" + noRuleNeededReason, actor,
                Map.of("closedLoopRuleUrn", closedLoopRuleUrn == null ? "" : closedLoopRuleUrn,
                        "noRuleNeededReason", noRuleNeededReason == null ? "" : noRuleNeededReason));
        return Map.of("incidentId", id, "status", "RESOLVED",
                "closedLoopRuleUrn", closedLoopRuleUrn == null ? "" : closedLoopRuleUrn,
                "note", "闭环已记录：事后可以回答「这次故障最后沉淀出了什么」");
    }

    public Map<String, Object> overview() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("byStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) AS count FROM incident GROUP BY 1 ORDER BY 2 DESC"));
        payload.put("bySeverity", jdbc.queryForList("""
                SELECT severity, COUNT(*) AS count FROM incident
                 WHERE status IN ('OPEN', 'MITIGATING') GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("mttrHours", jdbc.queryForList("""
                SELECT ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - started_at)) / 3600.0)::numeric, 2) AS avg_hours,
                       COUNT(*) AS resolved_count
                  FROM incident WHERE resolved_at IS NOT NULL
                """));
        payload.put("withoutRule", jdbc.queryForList("""
                SELECT id, title FROM incident
                 WHERE status = 'RESOLVED' AND (closed_loop_rule_urn IS NULL OR closed_loop_rule_urn = '')
                 LIMIT 10
                """));
        payload.put("sloAttainment", jdbc.queryForList("""
                SELECT DISTINCT ON (slo_name) slo_name, attainment, met, error_budget_remaining, measured_at
                  FROM slo_snapshot ORDER BY slo_name, measured_at DESC
                """));
        payload.put("note", "MTTR 与「已解决但没有沉淀规则」的事故数，是这套机制是否真的在闭环的两个观察点");
        return payload;
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static String prefixOf(String resourceScopeJson) {
        if (resourceScopeJson == null || resourceScopeJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> scope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resourceScopeJson, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object prefixes = scope.get("prefixes");
            if (prefixes instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            return scope.get("prefix") == null ? null : String.valueOf(scope.get("prefix"));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Instant now() {
        return Instant.now();
    }
}
