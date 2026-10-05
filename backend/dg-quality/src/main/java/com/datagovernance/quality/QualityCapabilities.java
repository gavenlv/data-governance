package com.datagovernance.quality;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-quality 的能力声明。
 *
 * <p>Batch 2 实现了四项：剖析、规则引擎、ODCS 契约、CI 门禁；
 * Batch 5 补齐异常检测（L2/L3）与 SLO/事故管理。CI 官方插件仍未实现 —— 状态由本类单点声明，
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
                CapabilityDescriptor.implemented("quality.anomaly", "异常检测与数据漂移", "D4 质量与可观测",
                        "docs/09 §9.4",
                        "L2 = MAD 稳健 Z（中位数 ± k·MAD，抗离群点）；L3 = 季节性 MAD（按「周期内位置」分组取基线，"
                                + "解决周内规律造成的误报）；**样本不足显式跳过**（少于 7 个点不判定，"
                                + "避免噪声被当成异常）；上游异常传导到下游时**抑制**下游告警（防告警风暴）",
                        List.of("已实现：mad / seasonal_mad 两种方法 + 传导抑制 + 概览",
                                "未实现：PSI / KS 分布漂移检验（需要分布快照而非统计量）与 STL 分解（自实现 STL 风险高，"
                                        + "当前用位置分组的季节性基线替代）",
                                "L1 静态阈值不在这里重复实现：那是质量规则（quality.rules）的职责",
                                "验收：e2e 构造越界序列 → 产出检测；样本不足序列 → 出现在 skipped 而不是被当成正常")),
                CapabilityDescriptor.implemented("quality.slo-incident", "SLO 与事故管理", "D4 质量与可观测",
                        "docs/09 §9.4",
                        "四类 SLO（新鲜度 / 质量通过率 / 可用性 / schema 稳定性）达成率由平台**实际数据**计算："
                                + "新鲜度取剖析时间、通过率取 rule_run、可用性取 collect_run、稳定性取 schema 变更天数；"
                                + "错误预算 = 1 − target 扣减；无数据时回报 no_data（不给假的 100%）；"
                                + "事故带血缘影响面清单与时间线；**解决必须二选一**：关联沉淀出的规则，"
                                + "或说明为什么不需要（闭环不留空话）；可从异常批量开事故（同一主资产只开一个）",
                        List.of("已实现：四类 SLO + 达成率快照 + 错误预算 + 事故时间线/影响面/闭环规则 + MTTR 概览",
                                "明确不做：告警通道（邮件/IM/on-call 分派）—— 那是外部系统集成，不是本平台的判定逻辑",
                                "验收：e2e 覆盖 定义 SLO → 度量 → 无数据时 no_data → 开会事故 → "
                                        + "不带闭环规则的解决被拒 422 → 带规则解决成功")),
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
                CapabilityDescriptor.implemented("quality.ci-plugin", "GitHub / GitLab 官方插件", "D5 数据契约",
                        "docs/09 §9.5",
                        "可直接接入流水线的插件包：GitHub 复合 Action（12 个输入，含 Job Summary、"
                                + "产物归档、PR 评论回写）+ GitLab CI 组件模板（含 MR/主干两条规则）；"
                                + "两者共用同一套判定与同一份 Markdown 报告（结论先给、证据在后、"
                                + "**最后写明「本次没检查什么」**）；"
                                + "PR 评论按 marker **upsert**（同一 PR 只保留一条，不会每次 push 刷屏）",
                        List.of("已实现：GitHub Action（.github/actions/contract-gate）+ GitLab 组件"
                                        + "（ci/contract-gate.gitlab-ci.yml）+ 回写脚本（tools/ci/pr_comment.py）"
                                        + "+ 本仓库自己的流水线（.github/workflows/ci.yml，同时是该插件的活样本）",
                                "**未做：Marketplace 发布与版本标签** —— 本仓库内可直接用 `uses: ./.github/actions/...`；"
                                        + "对外发布需要在组织账号下建仓库与打 tag，属发布流程而非实现",
                                "**未验证：真实 GitHub/GitLab 实例上的回写** —— 回写的请求形状用本地 stub API 验证"
                                        + "（端点、marker、创建/更新两条路径），但真实实例需要仓库凭证",
                                "设计取舍：回写失败**不让整步失败**（否则团队会直接删掉这一步）；"
                                        + "平台不可达时默认降级为「不阻断 + 明确标注未执行」",
                                "验收：e2e 覆盖 报告与退出码、破坏性变更阻断（退出码 1）、"
                                        + "GitHub/GitLab 各自的正确端点与 upsert 路径、无凭证时跳过而不失败")));
    }
}
