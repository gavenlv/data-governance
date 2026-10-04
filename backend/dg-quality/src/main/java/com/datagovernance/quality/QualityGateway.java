package com.datagovernance.quality;

import com.datagovernance.model.Unimplemented;

/**
 * 质量子系统的入口。
 *
 * <p>本类当前<b>部分实现</b>：
 * <ul>
 *   <li>{@link #profile} / {@link #submitRules} —— 已实现，委托
 *       {@link ProfilingService} 与 {@link RuleExecutionService}；</li>
 *   <li>{@link #detectAnomalies} / {@link #openIncident} —— 仍未实现，调用即抛
 *       {@link NotImplementedYet}，由 API 层转为 501 + 设计说明。</li>
 * </ul>
 *
 * <p>保留"未实现即抛错"的语义很重要：返回空列表会让使用者以为"没有质量问题"，
 * 而实际上是"检测还没做"（docs/09 §9.2 点名要避免的静默失败）。
 */
public class QualityGateway {

    @Unimplemented(doc = "docs/09 §9.4", phase = "Phase 2",
            summary = "无规则/少规则下的异常检测：MAD 稳健 Z、STL 分解、PSI/KS 分布漂移")
    public void detectAnomalies() {
        throw new NotImplementedYet("异常检测");
    }

    @Unimplemented(doc = "docs/09 §9.4", phase = "Phase 2",
            summary = "SLO 达成统计与事故时间线（故障复盘必须沉淀出一条新规则）")
    public void openIncident() {
        throw new NotImplementedYet("SLO 与事故管理");
    }

    /** 未实现能力的统一信号。 */
    public static class NotImplementedYet extends UnsupportedOperationException {
        public NotImplementedYet(String capability) {
            super(capability + " 尚未实现（见 /api/v1/capabilities 的 state 与 notes）");
        }
    }
}
