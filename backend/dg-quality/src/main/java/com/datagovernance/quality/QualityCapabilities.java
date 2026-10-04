package com.datagovernance.quality;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-quality 的能力声明。
 *
 * <p>Batch 2 实现了四项：剖析、规则引擎、ODCS 契约、CI 门禁。
 * 异常检测（L3）与 SLO/事故（Phase 2）仍为未实现 —— 状态由本类单点声明，
 * 界面与接口共用，不做第二份清单。
 */
@Component
public class QualityCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("quality.profiling", "数据剖析 Profiling", "D4 质量与可观测",
                        "docs/09 §9.4",
                        "行数/空值率/唯一值/分位数/长度分布/top-k + 新鲜度；"
                                + "采样优先哈希取模（可重复可分片），TABLESAMPLE 次之，不用 LIMIT 头部采样；"
                                + "精度分开存（EXACT 实算 vs ESTIMATED 估算），高密级列只输出统计量不输出具体值"),
                CapabilityDescriptor.implemented("quality.rules", "质量规则引擎", "D4 质量与可观测",
                        "docs/09 §9.4",
                        "统一 IR（保留 engineHints）+ 三种前端：YAML 声明式 / SQL 断言 / dbt tests；"
                                + "编译为源库 SQL 执行（平台不搬数据）；规则定义是实体（有 Owner 与版本历史）；"
                                + "结果留痕含编译产物；「没跑成」记为 SKIPPED 而不是 PASS"),
                CapabilityDescriptor.notImplemented("quality.anomaly", "异常检测与数据漂移", "D4 质量与可观测",
                        "docs/09 §9.4", "Phase 2",
                        "静态阈值 → MAD 稳健 Z → STL 分解分层；PSI/KS 检测分布漂移；上游告警抑制下游",
                        List.of("未实现（Batch 5）",
                                "基线数据已具备：profile_metric 是时序表，可直接用于 MAD/STL",
                                "设计要点：季节性对齐是成败关键；误报率是第一优先级指标")),
                CapabilityDescriptor.notImplemented("quality.slo-incident", "SLO 与事故管理", "D4 质量与可观测",
                        "docs/09 §9.4", "Phase 2",
                        "新鲜度/质量/可用性 SLO、事故时间线、复盘并沉淀为新规则",
                        List.of("未实现（Batch 5）",
                                "已具备的底座：新鲜度与通过率数据（profile_metric / rule_run）都在时序表里",
                                "Python 参考实现的告警状态机（去重/冷却/恢复）可作为 SLO 达成的判定基准")),
                CapabilityDescriptor.implemented("quality.contract", "数据契约（ODCS）", "D5 数据契约",
                        "docs/09 §9.5",
                        "ODCS 兼容契约（apiVersion 与 version 分开治理）；契约是一等实体，版本历史复用 aspect_history；"
                                + "兼容性 diff 引擎（删列/收窄类型/可选改必填/改语义/改主键 = 破坏性，"
                                + "并校验版本号是否诚实）；运行时违约事件（按内容去重计数）；消费者订阅 + "
                                + "**未登记消费者**（血缘上实际在读但没登记的人）；豁免必须带到期时间"),
                CapabilityDescriptor.implemented("quality.ci-gate", "契约与治理 CI 门禁", "D5 数据契约",
                        "docs/09 §9.5",
                        "PR 阶段一次回答三件事：兼容性（含版本号诚实性）+ 血缘影响面 + 治理属性齐备；"
                                + "破坏性变更默认 BLOCK，治理属性缺失默认 WARN；判定留痕可查；"
                                + "参考 CLI（tools/ci/contract_gate.py）可直接用作 CI 步骤"),
                CapabilityDescriptor.notImplemented("quality.ci-plugin", "GitHub / GitLab 官方插件", "D5 数据契约",
                        "docs/09 §9.5", "Phase 2",
                        "官方 Action / GitLab CI 组件，PR 评论回写受影响下游清单",
                        List.of("未实现：当前提供的是门禁 API + 可直接调用的参考脚本（exit code 语义）",
                                "未做的部分：插件打包与 PR 评论回写（需要平台外凭据与 Webhook 生命周期管理）")));
    }
}
