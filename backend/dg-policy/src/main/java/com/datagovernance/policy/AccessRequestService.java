package com.datagovernance.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 访问申请 → 审批 → 授权 → 到期回收 → 定期复核（docs/09 §9.7、docs/20 §8）。
 *
 * <p>这是本平台被认定的**核心差异化**所在：执行层（Trino/Ranger/数仓原生策略）是商品，
 * 而"策略的生命周期闭环"才是产品。因此本类刻意不做数据面执行，只做生命周期：
 * <ol>
 *   <li>申请时<b>自动填充</b>分级/路由/最小粒度建议/SLA —— 让业务人员不用理解权限模型；</li>
 *   <li>审批时<b>禁止自批</b>并按角色校验，判定留痕；</li>
 *   <li>批准即生成<b>带到期时间</b>的授权记录（没有到期时间的授权就是永久权限）；</li>
 *   <li><b>到期自动回收</b>（定时扫描），并支持人工吊销；</li>
 *   <li>定期复核：给出"保留/回收/需更多信息"的建议，**没有使用数据时不假装"未使用"**。</li>
 * </ol>
 */
@Service
public class AccessRequestService {

    private static final List<String> KNOWN_PERMISSIONS = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final EngineAuditService engineAudit;

    public AccessRequestService(JdbcTemplate jdbc, MetadataService metadata, EngineAuditService engineAudit) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.engineAudit = engineAudit;
    }

    // ------------------------------------------------------------------ 申请

    /** 提交申请：自动填充分级、路由、SLA 与最小粒度建议。 */
    @Transactional
    public Map<String, Object> submit(SubmitRequest request) {
        List<String> problems = new ArrayList<>();
        if (request.requester() == null || request.requester().isBlank()) {
            problems.add("缺少申请人身份");
        }
        if (request.resourceUrn() == null || request.resourceUrn().isBlank()) {
            problems.add("缺少资源 URN");
        }
        if (request.purpose() == null || request.purpose().trim().length() < 4) {
            problems.add("用途必填且不能过短（用途是审批的依据，不是形式）：" + request.purpose());
        }
        List<String> permissions = request.permissions() == null || request.permissions().isEmpty()
                ? List.of("SELECT") : request.permissions().stream().map(String::toUpperCase).toList();
        for (String permission : permissions) {
            if (!KNOWN_PERMISSIONS.contains(permission)) {
                problems.add("未知权限：" + permission + "（支持 " + KNOWN_PERMISSIONS + "）");
            }
        }
        if (!problems.isEmpty()) {
            throw new AccessPolicyException("申请不合法：" + String.join("；", problems));
        }

        String granularity = "COLUMN".equalsIgnoreCase(request.granularity()) ? "COLUMN" : "DATASET";
        if ("COLUMN".equals(granularity) && (request.columnName() == null || request.columnName().isBlank())) {
            throw new AccessPolicyException("列级申请必须给出列名");
        }

        // 分级快照：用于路由与 SLA。事后有人改了分级，历史判定不应被改写
        String classification = metadata.getAspect(request.resourceUrn(), "classification")
                .map(data -> String.valueOf(data.get("level")))
                .orElse(null);
        String domain = metadata.getAspect(request.resourceUrn(), "domain")
                .map(data -> String.valueOf(data.get("name")))
                .orElse(null);

        AccessRouting.Route route = AccessRouting.route(classification, granularity,
                request.purpose(), request.durationDays(), request.requester());
        Instant now = Instant.now();
        Instant slaDue = AccessRouting.slaDueAt(now, route.slaHours());

        // text[] 列必须用 createArrayOf 传入：直接把 String[] 交给 setObject 会抛
        // SQLFeatureNotSupportedException（pgjdbc 的 setObjectArray 未实现）
        final List<String> finalPermissions = permissions;
        Long id = jdbc.query(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO access_request (requester, resource_urn, column_name, granularity, permissions,
                                                purpose, duration_days, classification, domain, status, route,
                                                approvers, sla_due_at, granularity_suggestion)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?)
                    RETURNING id
                    """);
            ps.setString(1, request.requester());
            ps.setString(2, request.resourceUrn());
            ps.setString(3, request.columnName());
            ps.setString(4, granularity);
            ps.setArray(5, connection.createArrayOf("text", finalPermissions.toArray()));
            ps.setString(6, request.purpose().trim());
            ps.setInt(7, request.durationDays() == null ? 90 : request.durationDays());
            ps.setString(8, classification);
            ps.setString(9, domain);
            ps.setString(10, route.route());
            ps.setArray(11, connection.createArrayOf("text", route.approvers().toArray()));
            ps.setTimestamp(12, java.sql.Timestamp.from(slaDue));
            ps.setString(13, route.minGranularitySuggestion());
            return ps;
        }, rs -> rs.next() ? rs.getLong(1) : null);

        recordEvent(request.requester(), "REQUEST_SUBMITTED", request.resourceUrn(), null,
                route.route(), Map.of("requestId", id, "granularity", granularity,
                        "permissions", permissions, "classification", classification == null ? "L2" : classification),
                "platform");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id);
        payload.put("status", "SUBMITTED");
        payload.put("resource", request.resourceUrn());
        payload.put("granularity", granularity);
        payload.put("permissions", permissions);
        payload.put("classification", classification == null ? "L2（默认）" : classification);
        payload.put("route", route.route());
        payload.put("approvers", route.approvers());
        payload.put("slaDueAt", slaDue);
        payload.put("granularitySuggestion", route.minGranularitySuggestion());
        payload.put("notes", route.notes());
        return payload;
    }

    // ------------------------------------------------------------------ 审批

    /** 审批（批准/拒绝）。批准时生成带到期时间的授权记录。 */
    @Transactional
    public Map<String, Object> decide(long requestId, String decision, String approver,
                                      List<String> approverRoles, String note) {
        Map<String, Object> row = findRequest(requestId);
        String status = String.valueOf(row.get("status"));
        if (!List.of("SUBMITTED", "IN_REVIEW").contains(status)) {
            throw new AccessPolicyException("申请当前状态为 " + status + "，不能再次裁决");
        }
        String requireDecision = decision == null ? "" : decision.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("APPROVED", "REJECTED").contains(requireDecision)) {
            throw new AccessPolicyException("decision 必须是 APPROVED 或 REJECTED");
        }

        List<String> requiredRoles = stringList(row.get("approvers"));
        List<String> problems = AccessRouting.authorizeDecision(approverRoles, requiredRoles,
                String.valueOf(row.get("requester")), approver);
        if (!problems.isEmpty()) {
            throw new AccessPolicyException("无权裁决该申请：" + String.join("；", problems));
        }
        if ("REJECTED".equals(requireDecision) && (note == null || note.isBlank())) {
            throw new AccessPolicyException("拒绝必须说明原因（申请人有权知道被拒的理由）");
        }

        Instant now = Instant.now();
        jdbc.update("""
                UPDATE access_request
                   SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?, updated_at = now()
                 WHERE id = ?
                """, requireDecision, approver, java.sql.Timestamp.from(now), note, requestId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("status", requireDecision);
        payload.put("decidedBy", approver);

        if ("APPROVED".equals(requireDecision)) {
            Integer durationDays = row.get("duration_days") == null ? 90
                    : ((Number) row.get("duration_days")).intValue();
            Instant expiresAt = AccessRouting.expiresAt(now, durationDays);
            final List<String> grantPermissions = stringList(row.get("permissions"));
            Long grantId = jdbc.query(connection -> {
                java.sql.PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO access_grant (request_id, subject, resource_urn, column_name, granularity,
                                                  permissions, purpose, granted_by, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """);
                ps.setLong(1, requestId);
                ps.setString(2, String.valueOf(row.get("requester")));
                ps.setString(3, String.valueOf(row.get("resource_urn")));
                ps.setString(4, row.get("column_name") == null ? null : String.valueOf(row.get("column_name")));
                ps.setString(5, String.valueOf(row.get("granularity")));
                ps.setArray(6, connection.createArrayOf("text", grantPermissions.toArray()));
                ps.setString(7, row.get("purpose") == null ? null : String.valueOf(row.get("purpose")));
                ps.setString(8, approver);
                ps.setTimestamp(9, java.sql.Timestamp.from(expiresAt));
                return ps;
            }, rs -> rs.next() ? rs.getLong(1) : null);
            payload.put("grantId", grantId);
            payload.put("expiresAt", expiresAt);
            payload.put("durationDays", durationDays);
            recordEvent(approver, "GRANT_CREATED", String.valueOf(row.get("resource_urn")), "ALLOW",
                    "批准申请 #" + requestId, Map.of("grantId", grantId, "subject", row.get("requester"),
                            "expiresAt", expiresAt.toString()), "platform");
        } else {
            recordEvent(approver, "REQUEST_REJECTED", String.valueOf(row.get("resource_urn")), "DENY",
                    note, Map.of("requestId", requestId), "platform");
        }
        recordEvent(approver, "REQUEST_" + requireDecision, String.valueOf(row.get("resource_urn")),
                null, note, Map.of("requestId", requestId), "platform");
        return payload;
    }

    /** 撤回申请（申请人自己）。 */
    public Map<String, Object> withdraw(long requestId, String requester) {
        Map<String, Object> row = findRequest(requestId);
        if (!String.valueOf(row.get("requester")).equalsIgnoreCase(requester)) {
            throw new AccessPolicyException("只有申请人本人可以撤回申请");
        }
        if (!List.of("SUBMITTED", "IN_REVIEW").contains(String.valueOf(row.get("status")))) {
            throw new AccessPolicyException("当前状态不能撤回：" + row.get("status"));
        }
        jdbc.update("UPDATE access_request SET status = 'WITHDRAWN', updated_at = now() WHERE id = ?", requestId);
        recordEvent(requester, "REQUEST_WITHDRAWN", String.valueOf(row.get("resource_urn")), null,
                null, Map.of("requestId", requestId), "platform");
        return Map.of("requestId", requestId, "status", "WITHDRAWN");
    }

    // ------------------------------------------------------------------ 回收

    /**
     * 到期回收：把到期的授权标记为 EXPIRED。
     *
     * <p>由定时任务调用。**必须有这一步**：只发不回收的授权体系，等于没有期限。
     */
    @Transactional
    public Map<String, Object> expireGrants() {
        List<Map<String, Object>> expired = jdbc.queryForList("""
                UPDATE access_grant SET status = 'EXPIRED'
                 WHERE status = 'ACTIVE' AND expires_at < now()
                RETURNING id, subject, resource_urn, expires_at
                """);
        for (Map<String, Object> grant : expired) {
            recordEvent("system", "GRANT_EXPIRED", String.valueOf(grant.get("resource_urn")), "DENY",
                    "授权到期自动回收", Map.of("grantId", grant.get("id"), "subject", grant.get("subject")),
                    "platform");
        }
        // 申请层面：长期未处理的申请标为过期，避免"僵尸待办"
        int staleRequests = jdbc.update("""
                UPDATE access_request SET status = 'EXPIRED', updated_at = now()
                 WHERE status IN ('SUBMITTED', 'IN_REVIEW')
                   AND sla_due_at < now() - interval '30 days'
                """);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expiredGrants", expired.size());
        payload.put("expiredRequests", staleRequests);
        payload.put("note", "到期自动回收是「授权必须有期限」的执行点；"
                + "只发不回收的授权体系等价于没有期限");
        return payload;
    }

    /** 人工吊销授权（复核未通过、离职、事故处置）。 */
    public Map<String, Object> revoke(long grantId, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AccessPolicyException("吊销授权必须说明原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT subject, resource_urn, status FROM access_grant WHERE id = ?", grantId);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("授权不存在：" + grantId);
        }
        jdbc.update("""
                UPDATE access_grant SET status = 'REVOKED', revoked_by = ?, revoked_at = now(),
                                       revoke_reason = ?
                 WHERE id = ?
                """, actor, reason, grantId);
        recordEvent(actor, "GRANT_REVOKED", String.valueOf(rows.get(0).get("resource_urn")), "DENY",
                reason, Map.of("grantId", grantId, "subject", rows.get(0).get("subject")), "platform");
        return Map.of("grantId", grantId, "status", "REVOKED", "reason", reason,
                "note", "吊销后执行层的策略需要重新编译下发才会真正生效（见 policy.compiler）");
    }

    // ------------------------------------------------------------------ 查询

    public List<Map<String, Object>> listRequests(String status, String requester, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.requester, r.resource_urn, r.column_name, r.granularity, r.permissions,
                       r.purpose, r.duration_days, r.classification, r.status, r.route, r.approvers,
                       r.sla_due_at, r.escalated, r.decided_by, r.decided_at, r.decision_note,
                       r.granularity_suggestion, r.created_at,
                       (r.sla_due_at IS NOT NULL AND r.sla_due_at < now()
                        AND r.status IN ('SUBMITTED','IN_REVIEW')) AS overdue
                  FROM access_request r WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            params.add(status);
        }
        if (requester != null && !requester.isBlank()) {
            sql.append(" AND r.requester = ?");
            params.add(requester);
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> listGrants(String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT g.id, g.subject, g.resource_urn, g.column_name, g.granularity, g.permissions,
                       g.purpose, g.granted_by, g.granted_at, g.expires_at, g.status,
                       g.last_reviewed_at,
                       EXTRACT(DAY FROM now() - g.granted_at)::int AS age_days,
                       EXTRACT(DAY FROM g.expires_at - now())::int AS days_to_expiry
                  FROM access_grant g WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND g.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY g.granted_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /** 治理概览：待办、超期、即将到期、授权分布（供界面与运营）。 */
    public Map<String, Object> overview() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingRequests", jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM access_request
                 WHERE status IN ('SUBMITTED', 'IN_REVIEW') GROUP BY 1 ORDER BY 1
                """));
        payload.put("overdueRequests", jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_request
                 WHERE status IN ('SUBMITTED', 'IN_REVIEW') AND sla_due_at < now()
                """, Integer.class));
        payload.put("decisionStats", jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM access_request
                 WHERE status IN ('APPROVED', 'REJECTED') GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("activeGrants", jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_grant WHERE status = 'ACTIVE'", Integer.class));
        payload.put("expiringSoon", jdbc.queryForList("""
                SELECT id, subject, resource_urn, expires_at,
                       EXTRACT(DAY FROM expires_at - now())::int AS days_left
                  FROM access_grant
                 WHERE status = 'ACTIVE' AND expires_at < now() + interval '30 days'
                 ORDER BY expires_at LIMIT 20
                """));
        payload.put("granularityMix", jdbc.queryForList("""
                SELECT granularity, COUNT(*) AS count FROM access_grant GROUP BY 1
                """));
        payload.put("note", "「待办/超期/即将到期」是访问治理真正需要盯的三个数："
                + "没有它们，授权会自然演变为永久权限");
        return payload;
    }

    // ------------------------------------------------------------------ 复核

    /**
     * 生成复核清单（access review 批次）。
     *
     * <p>使用证据来自 {@link EngineAuditService}（引擎侧真实访问）。
     * 三种情况下都会返回 {@code NEED_MORE_INFO}，且理由各不相同 ——
     * 因为"为什么判不了"本身是复核人需要的信息：
     * <ol>
     *   <li>**完全没接入**引擎审计；</li>
     *   <li>接入了，但**观测窗口短于授权时长**（只看了 7 天日志不能否定 200 天前的授权）；</li>
     *   <li>接入了且窗口够长，但授权**过新**（刚批的授权没被用上很正常）。</li>
     * </ol>
     */
    public Map<String, Object> reviewCampaign(String campaign, String reviewer, int limit) {
        List<Map<String, Object>> grants = jdbc.queryForList("""
                SELECT g.id, g.subject, g.resource_urn, g.column_name, g.granularity, g.permissions,
                       g.granted_at, g.expires_at, g.purpose,
                       EXTRACT(DAY FROM now() - g.granted_at)::int AS age_days
                  FROM access_grant g
                 WHERE g.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM access_review r
                                    WHERE r.grant_id = g.id AND r.campaign = ?)
                 ORDER BY g.granted_at
                 LIMIT ?
                """, campaign, Math.min(Math.max(limit, 1), 200));

        Map<String, Object> window = engineAudit.observationWindow();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> grant : grants) {
            long ageDays = ((Number) grant.get("age_days")).longValue();
            java.time.Instant grantedAt = grant.get("granted_at") == null ? null
                    : ((java.sql.Timestamp) grant.get("granted_at")).toInstant();
            Map<String, Object> evidence = usageEvidence(
                    String.valueOf(grant.get("resource_urn")), String.valueOf(grant.get("subject")));
            Boolean observedUsage;
            if (evidence == null) {
                observedUsage = null;                       // 没接入：判不了
            } else if (!engineAudit.coversGrantLife(grantedAt)) {
                observedUsage = null;                       // 窗口没覆盖授权全生命周期：依然判不了
            } else if (ageDays < 30) {
                observedUsage = null;                       // 过新：判不了
            } else {
                observedUsage = ((Number) evidence.get("count")).intValue() > 0;
            }
            AccessRouting.ReviewSuggestion suggestion = AccessRouting.reviewSuggestion(
                    ageDays, observedUsage, "90d");
            Map<String, Object> item = new LinkedHashMap<>(grant);
            item.put("suggestedDecision", suggestion.decision());
            item.put("suggestionReason", suggestion.reason());
            item.put("evidenceWindow", window);
            item.put("evidence", evidence == null
                    ? Map.of("usageDataAvailable", false, "observationWindow", window,
                    "note", "尚未接入引擎审计：无法给出使用情况（不假设未使用）。"
                            + "接入方式见 GET /api/v1/access/engine-audit/coverage")
                    : evidence);
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaign", campaign);
        payload.put("reviewer", reviewer);
        payload.put("count", items.size());
        payload.put("items", items);
        payload.put("suggestionLegend", Map.of(
                "KEEP", "建议保留",
                "REVOKE", "建议回收",
                "NEED_MORE_INFO", "证据不足，需人工查证（缺使用数据时的默认结论）"));
        return payload;
    }

    /** 记录复核判定：REVOKE 会同时吊销授权。 */
    @Transactional
    public Map<String, Object> recordReview(String campaign, long grantId, String reviewer,
                                            String decision, String reason) {
        String value = decision == null ? "" : decision.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("KEEP", "REVOKE", "NEED_MORE_INFO").contains(value)) {
            throw new AccessPolicyException("decision 必须是 KEEP / REVOKE / NEED_MORE_INFO");
        }
        if ("REVOKE".equals(value) && (reason == null || reason.isBlank())) {
            throw new AccessPolicyException("回收授权必须说明原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT resource_urn, subject FROM access_grant WHERE id = ?", grantId);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("授权不存在：" + grantId);
        }
        jdbc.update("""
                INSERT INTO access_review (campaign, grant_id, reviewer, decision, reason, evidence)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, campaign, grantId, reviewer, value, reason,
                toJson(Map.of("usageDataAvailable", usageEvidence(
                        String.valueOf(rows.get(0).get("resource_urn")),
                        String.valueOf(rows.get(0).get("subject"))) != null)));
        jdbc.update("UPDATE access_grant SET last_reviewed_at = now() WHERE id = ?", grantId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaign", campaign);
        payload.put("grantId", grantId);
        payload.put("decision", value);
        if ("REVOKE".equals(value)) {
            payload.putAll(revoke(grantId, reviewer, "复核回收：" + reason));
        }
        return payload;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 使用证据：从 access_event 里找该主体对该资源的**访问决策记录**。
     *
     * <p>诚实边界：这只覆盖"平台侧的访问决策"（申请/批准/吊销），
     * **不包含真实查询**（引擎侧的 query log 未接入）。因此返回 null 表示"没有数据"，
     * 而不是"没有使用"。
     */
    /**
     * 使用证据：委托给 {@link EngineAuditService}（引擎侧真实访问）。
     *
     * <p>返回 {@code null} 表示**根本没有使用数据**（未接入或窗口内无记录），
     * 调用方必须把它与"用过"和"没用过"区分开 —— 这三者在复核里对应三种不同结论。
     */
    private Map<String, Object> usageEvidence(String resourceUrn, String subject) {
        Map<String, Object> window = engineAudit.observationWindow();
        if (window.get("records") == null || ((Number) window.get("records")).longValue() == 0) {
            return null;
        }
        int windowDays = window.get("windowDays") == null ? 0 : ((Number) window.get("windowDays")).intValue();
        Map<String, Object> usage = engineAudit.usageEvidence(subject, resourceUrn, Math.max(windowDays, 1));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("count", usage.getOrDefault("queries", 0));
        evidence.put("lastSeen", usage.get("last_used_at"));
        evidence.put("rowsScanned", usage.getOrDefault("rows_scanned", 0));
        evidence.put("windowDays", Math.max(windowDays, 1));
        evidence.put("source", "engine_audit_record");
        evidence.put("usageDataAvailable", true);
        return evidence;
    }

    private Map<String, Object> findRequest(long requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM access_request WHERE id = ?", requestId);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("访问申请不存在：" + requestId);
        }
        return rows.get(0);
    }

    private void recordEvent(String subject, String action, String resourceUrn, String decision,
                             String reason, Map<String, Object> detail, String source) {
        jdbc.update("""
                INSERT INTO access_event (subject, action, resource_urn, decision, reason, detail, source)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, subject, action, resourceUrn, decision, reason, toJson(detail), source);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof java.sql.Array array) {
            try {
                Object raw = array.getArray();
                if (raw instanceof Object[] items) {
                    List<String> out = new ArrayList<>();
                    for (Object item : items) {
                        out.add(item == null ? null : String.valueOf(item));
                    }
                    return out;
                }
            } catch (java.sql.SQLException e) {
                return List.of();
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? null : String.valueOf(item)).toList();
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

    /** 提交申请的入参。 */
    public record SubmitRequest(String requester, String resourceUrn, String columnName,
                                String granularity, List<String> permissions, String purpose,
                                Integer durationDays) {
    }
}
