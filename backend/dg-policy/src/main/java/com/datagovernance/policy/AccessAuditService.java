package com.datagovernance.policy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 访问审计与最小权限复盘（docs/09 §9.7、docs/20 §8）。
 *
 * <p>审计要回答的问题不是"系统记录了什么"，而是这三类**取证问题**：
 * <ol>
 *   <li><b>谁在什么时候获得了什么权限、依据什么</b>（申请 → 审批 → 授权 → 策略版本）；</li>
 *   <li><b>这条权限现在还需要吗</b>（复核 + 使用证据）；</li>
 *   <li><b>权限判断本身有没有被绕过</b>（这一条本平台<b>做不到</b>，必须说清楚）。</li>
 * </ol>
 *
 * <p>诚实边界：平台只能审计**平台自身的决策**（申请/审批/吊销/策略下发）。
 * 引擎侧的真实查询与直连访问需要引擎审计日志，本平台尚未接入 ——
 * 因此导出报告里会显式标注"审计覆盖范围"，
 * 避免让合规方以为"平台没记录 = 没有人访问过"。
 */
@Service
public class AccessAuditService {

    private final JdbcTemplate jdbc;

    public AccessAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 访问事件（平台侧决策）。 */
    public List<Map<String, Object>> events(String subject, String resourceUrn, String source,
                                            Integer limitDays, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, subject, action, resource_urn, decision, reason, detail, source, occurred_at
                  FROM access_event WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND subject = ?");
            params.add(subject);
        }
        if (resourceUrn != null && !resourceUrn.isBlank()) {
            sql.append(" AND resource_urn = ?");
            params.add(resourceUrn);
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            params.add(source);
        }
        if (limitDays != null && limitDays > 0) {
            sql.append(" AND occurred_at > now() - (? || ' days')::interval");
            params.add(String.valueOf(limitDays));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 1000));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 审计报告：一次合规取证所需要的完整链路。
     *
     * <p>报告里必须包含 <b>coverageNote</b> —— 说明本审计**不覆盖**引擎侧的真实查询与直连访问。
     * 一份不说明覆盖范围的审计报告，比没有报告更危险。
     */
    public Map<String, Object> report(int limitDays) {
        int days = limitDays <= 0 ? 90 : limitDays;
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now());
        payload.put("window", Map.of("from", since, "days", days));

        payload.put("requestFunnel", jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM access_request
                 WHERE created_at > now() - (? || ' days')::interval GROUP BY 1 ORDER BY 2 DESC
                """, String.valueOf(days)));
        payload.put("decisionLatency", jdbc.queryForList("""
                SELECT COUNT(*) AS decided,
                       ROUND(AVG(EXTRACT(EPOCH FROM (decided_at - created_at)) / 3600.0)::numeric, 2)
                           AS avg_hours,
                       ROUND(MAX(EXTRACT(EPOCH FROM (decided_at - created_at)) / 3600.0)::numeric, 2)
                           AS max_hours
                  FROM access_request
                 WHERE decided_at IS NOT NULL AND created_at > now() - (? || ' days')::interval
                """, String.valueOf(days)));
        payload.put("grantsByStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) AS count FROM access_grant GROUP BY 1 ORDER BY 2 DESC"));
        payload.put("grantsByClassification", jdbc.queryForList("""
                SELECT COALESCE(r.classification, 'L2') AS classification, COUNT(*) AS count
                  FROM access_grant g LEFT JOIN access_request r ON r.id = g.request_id
                 GROUP BY 1 ORDER BY 1
                """));
        payload.put("revokedGrants", jdbc.queryForList("""
                SELECT g.id, g.subject, g.resource_urn, g.revoked_by, g.revoked_at, g.revoke_reason
                  FROM access_grant g WHERE g.status IN ('REVOKED', 'EXPIRED')
                 ORDER BY g.revoked_at DESC NULLS LAST LIMIT 20
                """));
        payload.put("expiringIn30Days", jdbc.queryForList("""
                SELECT id, subject, resource_urn, expires_at FROM access_grant
                 WHERE status = 'ACTIVE' AND expires_at < now() + interval '30 days'
                 ORDER BY expires_at LIMIT 20
                """));
        payload.put("recentPolicyDeployments", jdbc.queryForList("""
                SELECT id, target, bundle_hash, status, artifact_count, deployed_by, created_at
                  FROM policy_deployment ORDER BY created_at DESC LIMIT 10
                """));
        payload.put("auditLogEntries", jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE created_at > now() - (? || ' days')::interval
                """, Integer.class, String.valueOf(days)));

        payload.put("coverageNote", Map.of(
                "covered", List.of(
                        "平台自身的访问治理决策：申请、审批、授权、吊销、到期回收、复核",
                        "策略的建模、编译、下发与回滚（含产物哈希）",
                        "元数据变更审计（audit_log 的哈希链）"),
                "notCovered", List.of(
                        "**引擎侧的真实查询**（谁查了哪些行/列）：需要查询日志或引擎审计日志，未接入",
                        "**直连数仓 JDBC 的访问**：绕过 Trino/策略执行点，平台看不到",
                        "**BI 工具内的查询**：仅在 BI 提供审计接口时才能获取，未接入"),
                "implication", "报告里「没有记录」不等于「没有发生」——合规结论需结合引擎侧审计共同判断"));
        return payload;
    }

    /**
     * 最小权限复盘：把授权按"是否在用 / 是否过期 / 是否复核过"分类，给出回收候选。
     *
     * <p>关键点：<b>没有使用数据时归入"证据不足"，而不是"未使用"</b>。
     */
    public Map<String, Object> leastPrivilegeReview(int limit) {
        List<Map<String, Object>> grants = jdbc.queryForList("""
                SELECT g.id, g.subject, g.resource_urn, g.granularity, g.permissions, g.granted_by,
                       g.granted_at, g.expires_at, g.status, g.last_reviewed_at,
                       EXTRACT(DAY FROM now() - g.granted_at)::int AS age_days
                  FROM access_grant g WHERE g.status = 'ACTIVE'
                 ORDER BY g.granted_at LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));

        List<Map<String, Object>> revokeCandidates = new ArrayList<>();
        List<Map<String, Object>> insufficientEvidence = new ArrayList<>();
        List<Map<String, Object>> wideGrants = new ArrayList<>();
        for (Map<String, Object> grant : grants) {
            long ageDays = ((Number) grant.get("age_days")).longValue();
            Map<String, Object> item = new LinkedHashMap<>(grant);
            if ("DATASET".equals(grant.get("granularity")) && ageDays >= 30) {
                item.put("reason", "整表授权且已存在 " + ageDays + " 天：确认是否可收窄为列级");
                wideGrants.add(item);
            }
            if (grant.get("last_reviewed_at") == null && ageDays >= 180) {
                item.put("reason", "授权已 " + ageDays + " 天从未复核");
                revokeCandidates.add(item);
            } else if (grant.get("last_reviewed_at") == null) {
                insufficientEvidence.add(item);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeGrants", grants.size());
        payload.put("neverReviewed", revokeCandidates);
        payload.put("neverReviewedRecent", insufficientEvidence);
        payload.put("granularityCandidates", wideGrants);
        payload.put("usageDataAvailable", false);
        payload.put("note", "本平台尚未接入查询日志，**无法判断授权是否在用**；"
                + "因此「回收候选」只基于授权时长与复核状态，不基于使用情况。"
                + "把「未使用」当成「没有记录」是权限误回收的常见原因");
        return payload;
    }
}
