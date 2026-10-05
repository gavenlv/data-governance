package com.datagovernance.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 访问申请的路由与 SLA 判定（docs/09 §9.7）。
 *
 * <p>抽成纯函数的理由：路由决定了"谁能批别人的数据权限"，这是整个治理体系里
 * 最不能靠试错验证的一段逻辑。错误路由有两种后果 —— 太宽松（分级高的数据被低权限的人批了）
 * 与太严格（没人能批，流程僵死）。因此规则要写死、要可断言。
 *
 * <p>路由规则（与设计文档一致）：
 * <ol>
 *   <li><b>分级决定审批层级</b>：L3/L4 需要数据管家（STEWARD）会签；L1/L2 只需资产 Owner；</li>
 *   <li><b>列级申请的 SLA 更宽</b>（粒度小、风险低），整表申请更短（风险大要更快拍板）；</li>
 *   <li><b>申请人与审批人不能是同一人</b>（自批自用是最常见的权限漏洞）；</li>
 *   <li>申请一律要求用途与期限：没有用途的申请无法判断必要性。</li>
 * </ol>
 */
public final class AccessRouting {

    /** 分级 → 审批链所需的角色。 */
    private static final List<String> HIGH_LEVEL_ROLES = List.of("STEWARD", "ADMIN");

    private AccessRouting() {
    }

    /**
     * 一次申请的路由建议。
     *
     * @param route        路由说明（给人看的，必须写清楚"为什么是他批"）
     * @param approvers    需要的审批角色
     * @param slaHours     SLA（小时）
     * @param minGranularitySuggestion 最小粒度建议（给列不给表）
     */
    public record Route(String route, List<String> approvers, int slaHours,
                        String minGranularitySuggestion, List<String> notes) {

        public Route {
            approvers = List.copyOf(approvers);
            notes = List.copyOf(notes);
        }
    }

    public static Route route(String classification, String granularity, String purpose,
                              Integer durationDays, String requester) {
        List<String> notes = new ArrayList<>();
        List<String> approvers = new ArrayList<>();

        String level = classification == null || classification.isBlank()
                ? "L2" : classification.toUpperCase(Locale.ROOT);
        boolean high = "L3".equals(level) || "L4".equals(level);
        if (high) {
            approvers.addAll(HIGH_LEVEL_ROLES);
            notes.add("分级 " + level + "：需要数据管家（STEWARD）会签 —— 高分级数据不允许只由 Owner 单点批准");
        } else {
            approvers.add("STEWARD");
            notes.add("分级 " + level + "：由数据管家或资产 Owner 审批");
        }

        // 粒度：列级风险小、SLA 更宽；整表申请风险大、SLA 更紧
        boolean columnLevel = "COLUMN".equalsIgnoreCase(granularity);
        int slaHours = columnLevel ? 72 : 48;
        notes.add(columnLevel
                ? "列级申请：SLA 72 小时（粒度小、影响面可控）"
                : "整表申请：SLA 48 小时（权限面大，需要更快拍板）");

        String suggestion = columnLevel
                ? "已按列申请，符合最小粒度原则"
                : "建议改为**列级申请**（只申请真正需要的列）—— 整表授权通常不是业务真实需求，"
                        + "而是「不想麻烦」的结果";

        if (purpose == null || purpose.isBlank() || purpose.trim().length() < 4) {
            notes.add("⚠️ 用途描述过短：审批人无法据此判断必要性（用途是审批的依据，不是形式）");
        }
        if (durationDays != null && durationDays > 365) {
            notes.add("⚠️ 申请期限 " + durationDays + " 天：超过一年通常意味着这其实是长期岗位职责，"
                    + "应当通过角色授权而不是单次申请解决");
        }
        if (requester != null && !requester.isBlank()) {
            notes.add("审批人不会包含申请人本人（" + requester + "）：自批自用是最常见的权限漏洞");
        }

        String route = "分级 " + level + " · " + (columnLevel ? "列级" : "整表")
                + " → " + String.join(" + ", approvers);
        return new Route(route, approvers, slaHours, suggestion, notes);
    }

    /**
     * 审批人是否被允许裁决该申请。
     *
     * @param approverRoles 审批人角色集合
     * @param requester     申请人
     * @param approver      审批人
     */
    public static List<String> authorizeDecision(List<String> approverRoles, List<String> requiredRoles,
                                                 String requester, String approver) {
        List<String> problems = new ArrayList<>();
        if (approver == null || approver.isBlank()) {
            problems.add("审批人身份缺失");
            return problems;
        }
        if (requester != null && requester.equalsIgnoreCase(approver)) {
            problems.add("申请人不能审批自己的申请（自批自用）");
        }
        boolean allowed = approverRoles != null && approverRoles.stream()
                .anyMatch(role -> "ADMIN".equals(role) || requiredRoles.contains(role));
        if (!allowed) {
            problems.add("当前角色 " + approverRoles + " 不在审批链 " + requiredRoles + " 中");
        }
        return problems;
    }

    /** 审批通过后的到期时间：期限来自申请，且有硬上限。 */
    public static Instant expiresAt(Instant approvedAt, Integer durationDays) {
        int days = durationDays == null || durationDays <= 0 ? 90 : Math.min(durationDays, 3650);
        return approvedAt.plus(Duration.ofDays(days));
    }

    public static Instant slaDueAt(Instant submittedAt, int slaHours) {
        return submittedAt.plus(Duration.ofHours(slaHours));
    }

    /**
     * 复核建议：根据授权时长与是否在用给出"保留 / 回收"的建议。
     *
     * <p><b>没有使用数据时必须显式说明</b>，而不是默认"看起来没被用过" ——
     * 那会导致复核人凭感觉回收，把在用的权限砍掉。
     *
     * @param observedUsage {@code null} 表示**证据不足**（未接入引擎审计、观测窗口短于授权时长、
     *                      或授权过新），调用方必须把理由写清楚，而不是压成"未使用"
     */
    public static ReviewSuggestion reviewSuggestion(long grantedDaysAgo, Boolean observedUsage,
                                                    String reviewIntervalDays) {
        if (observedUsage == null) {
            return new ReviewSuggestion("NEED_MORE_INFO",
                    "缺少可判定的使用证据：无法判断该授权是否仍被使用。"
                            + "可能是尚未接入引擎审计、观测窗口短于授权时长，或授权过新。"
                            + "这里**不假设「未使用」** —— 请向申请人确认或从引擎侧查证后再决定"
                            + "（接入与覆盖情况见 GET /api/v1/access/engine-audit/coverage）");
        }
        if (!observedUsage && grantedDaysAgo >= 90) {
            return new ReviewSuggestion("REVOKE",
                    "授权已 " + grantedDaysAgo + " 天且窗口内无使用记录：符合最小权限原则的回收条件");
        }
        if (!observedUsage) {
            return new ReviewSuggestion("KEEP",
                    "无使用记录但授权时间较短（" + grantedDaysAgo + " 天）：暂不建议回收，下次复核再看");
        }
        return new ReviewSuggestion("KEEP", "窗口内有使用记录：保留");
    }

    public record ReviewSuggestion(String decision, String reason) {
    }
}
