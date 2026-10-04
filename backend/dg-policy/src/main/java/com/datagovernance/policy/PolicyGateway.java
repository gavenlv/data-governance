package com.datagovernance.policy;

import com.datagovernance.model.Unimplemented;

/**
 * 访问治理入口。
 *
 * <p>本类当前<b>部分实现</b>：
 * <ul>
 *   <li>{@link #authorize} —— 已实现，委托 {@link AccessPolicy}（RBAC + ABAC 唯一判定入口）；</li>
 *   <li>{@link #submitAccessRequest} / {@link #compilePolicies} —— 仍未实现，
 *       调用即抛 {@link NotImplementedYet}，由 API 层转为 501 + 设计说明。</li>
 * </ul>
 *
 * <p>按 docs/20 §8 的定位修正：本平台在访问治理上的差异化**不是「策略编译下发」本身**
 * （Trino SystemAccessControl / Ranger / Lake Formation 等执行件已存在），
 * 而是**策略的生命周期闭环**：建模 → 申请 → 审批 → 下发 → 覆盖率度量 → 到期回收 → 审计。
 * 该闭环属 Batch 4，本批只落地底座（RBAC + 分级可见性）。
 */
public class PolicyGateway {

    /** 授权判定（已实现）：权限点不足或分级不可见时抛 {@link AccessDenied}。 */
    public void authorize(Subject subject, String permission) {
        AccessPolicy.authorize(subject, permission);
    }

    @Unimplemented(doc = "docs/09 §9.7", phase = "Phase 3",
            summary = "访问申请 → 审批 → 授权凭据 → 到期回收 → 定期复核")
    public void submitAccessRequest(String urn, String purpose) {
        throw new NotImplementedYet("访问申请与审批");
    }

    @Unimplemented(doc = "ADR-008", phase = "Phase 3",
            summary = "把策略编译为 Trino/Spark 行过滤与列掩码并灰度下发")
    public void compilePolicies() {
        throw new NotImplementedYet("策略编译与下发");
    }

    public static class NotImplementedYet extends UnsupportedOperationException {
        public NotImplementedYet(String capability) {
            super(capability + " 尚未实现（见 /api/v1/capabilities 的 state 与 notes）");
        }
    }
}
