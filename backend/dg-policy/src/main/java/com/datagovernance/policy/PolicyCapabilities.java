package com.datagovernance.policy;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-policy 的能力声明。
 *
 * <p>按 docs/20 §8 的定位修正：本平台在访问治理上的差异化**不是「策略编译下发」本身**
 * （Trino SystemAccessControl / Ranger / Lake Formation 等执行件已存在），
 * 而是**策略的生命周期闭环**：建模 → 申请 → 审批 → 下发 → 覆盖率度量 → 到期回收 → 审计。
 */
@Component
public class PolicyCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("policy.rbac", "平台功能权限（RBAC）", "D7 访问治理",
                        "docs/09 §9.7",
                        "角色 ADMIN/STEWARD/EDITOR/READER → 权限点；未知角色丢弃（拒绝静默提权）；"
                                + "接口按权限点强制，越权返回 403"),
                CapabilityDescriptor.implemented("policy.abac", "资产可见性（ABAC）", "D7 访问治理",
                        "docs/09 §9.7",
                        "按分类分级 L1–L4 过滤可见资产；AccessPolicy 是唯一判定入口，"
                                + "搜索与详情共用同一判定，且检索走前置过滤（后过滤会泄露总数与分面）"),
                CapabilityDescriptor.implemented("policy.access-request", "访问申请与审批", "D7 访问治理",
                        "docs/09 §9.7",
                        "申请自动填充分级/路由/SLA/**最小粒度建议**（给列不给表）→ 审批（禁止自批，按审批链校验角色）"
                                + "→ 生成**带到期时间**的授权记录 → 到期自动回收（常驻任务）→ 人工吊销 → "
                                + "定期复核（保留/回收/需更多信息）；全链路写 access_event 供取证"),
                CapabilityDescriptor.partial("policy.compiler", "策略编译与下发", "D7 访问治理",
                        "ADR-008", "Phase 3",
                        "业务可读的策略建模（YAML/JSON）→ 纯函数编译器（快照可测）→ 四类产物："
                                + "Trino 行过滤谓词/列掩码、数仓 GRANT/REVOKE + 行访问策略与掩码、"
                                + "BI 可见性配置、SDK 令牌声明 → 产物按版本归档（内容哈希）→ "
                                + "bundle 下发（落盘，可接 GitOps）+ 部署记录 + 回滚 → **覆盖率度量**"
                                + "（未被任何策略覆盖的高分级资产 + 已知盲区显式列出）",
                        List.of("**不实现执行引擎**（docs/20 §8 的定位修正）：产物不直接推送到 Trino/Ranger，"
                                        + "由客户的发布流程接入；Trino AccessControl 插件本身不在范围内",
                                "**直连绕过无法检测**：用户直连数仓 JDBC 会绕过执行点，"
                                        + "真实检测需要引擎审计日志或网络层数据（未接入），覆盖率数字不含这一风险",
                                "BI 目标只产出配置片段，未对接 Superset/Tableau 的实际下发 API")),
                CapabilityDescriptor.implemented("policy.audit", "访问审计与最小权限复盘", "D7 访问治理",
                        "docs/09 §9.7",
                        "访问事件（申请/审批/授权/吊销/回收/复核）全留痕 + 合规审计报告（申请漏斗、"
                                + "审批时延、按分级分布、吊销记录、策略下发记录）+ 最小权限复盘"
                                + "（**使用证据来自引擎审计**）；报告的审计覆盖范围**按当前实际数据动态生成**："
                                + "引擎审计接入了就写「已覆盖 + 观测窗口 + 解析率」，没接入仍写「未覆盖」"),
                CapabilityDescriptor.implemented("policy.engine-audit-ingest", "引擎侧访问审计摄入", "D7 访问治理",
                        "docs/09 §9.7",
                        "接受 Trino 查询事件 / Ranger 访问审计 / 数仓查询日志的**推送** → 归一化三类字段命名 → "
                                + "解析到平台资产（解析失败也入库并说明原因）→ 内容哈希幂等去重；"
                                + "由此得到两个此前答不了的审计结论：**「批准了但零访问」（可回收）** 与 "
                                + "**「被访问但无授权」（绕过治理的直连访问）**",
                        List.of("已实现：推送式摄入 + 字段别名归一 + 资产解析 + 幂等去重 + 覆盖情况回报 + "
                                        + "未授权访问清单 + 最小权限复盘与复核清单的使用证据",
                                "**未实现：日志文件/数据库的主动拉取** —— 只接受推送，因为各企业日志落盘格式与"
                                        + "位置差异极大，属部署侧采集配置；提供 tools/engine_audit_load.py 作为参考采集器",
                                "判断纪律：只有**观测窗口 ≥ 授权存在时长**且授权 ≥ 30 天时，「零访问」才被当作可回收；"
                                        + "否则一律进「证据不足」（误回收权限比多留一条权限严重得多）",
                                "验收：e2e 覆盖 摄入→幂等→未解析保留→覆盖情况→使用证据→未授权访问清单→"
                                        + "审计报告覆盖范围动态更新")));
    }
}
