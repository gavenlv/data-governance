package com.datagovernance.ai;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-ai 的能力声明（docs/13）。
 *
 * <p>核心纪律（docs/13 §3）：<b>AI 永远不直接写正式元数据</b> ——
 * 一切 AI 产出先落 {@code Suggestion}（含 provenance / 置信度 / 证据引用），
 * 人工接受后才写入。没有审计的 AI 元数据比没有元数据更危险。
 *
 * <p>本模块的诚实边界（写在这里而不是只在文档里）：
 * <ul>
 *   <li>建议引擎的<b>规则型生成器</b>已实现；<b>大模型生成</b>需要在 {@code ai_config} 配置模型端点，
 *       未配置时 {@code POST /api/v1/ai/suggestions/llm} 直接报错（502），不退回模板假装调用过；</li>
 *   <li>混合检索实际有<b>两路</b>（词法 + 术语表扩展，RRF 融合）；<b>向量路未实现</b>（无 embedding 服务），
 *       因此能力状态是 PARTIAL 而不是 IMPLEMENTED；</li>
 *   <li>MCP 只实现核心子集（initialize / tools/list / tools/call / ping），未实现
 *       resources、prompts、sampling、notifications 等其余规范面。</li>
 * </ul>
 */
@Component
public class AiCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("ai.suggestion", "AI 建议引擎", "D8 治理运营",
                        "docs/13 §3",
                        "三类确定性生成器（无主资产 / 缺描述 / 敏感列未分级）+ 建议收件箱 + 采纳率统计；"
                                + "采纳必须由人触发，写入来源标记 AI_GENERATED，驳回必须给理由",
                        List.of("生成器：rule-based（三类：无主资产 / 缺描述 / 敏感列未分级），立即可用",
                                "大模型生成：**未配置端点时明确失败（502）**；即使配置了端点，"
                                        + "本轮也**没有实现 HTTP 调用与提示词模板**（代码里明说，不返回假文本）",
                                "已有待审同类建议、或 7 天内已裁决过的，不会重复生成（否则驳回等于没驳回）",
                                "纪律：AI 只写 Suggestion，绝不直写 aspect",
                                "验收：e2e 覆盖 生成→幂等→收件箱→采纳→aspect 落库（source=AI_GENERATED）→驳回需理由")),
                CapabilityDescriptor.partial("ai.semantic-search", "语义检索", "D2 目录与发现",
                        "docs/13 §1.1", "Phase 4",
                        "混合检索：词法召回（含标识符切分与中文二元切分）+ 术语表同义扩展，"
                                + "RRF（k=60）融合，授权分级前置过滤",
                        List.of("已实现：两路召回 + RRF 融合 + 术语表查询扩展",
                                "**未实现**：向量召回（无 embedding 服务），因此不宣称语义相似度",
                                "安全要点：可见分级作为必传参数在 SQL 前置过滤，不是取回后再筛",
                                "验收：e2e 覆盖同义术语（如「客户」→ customer）能召回、越权分级不返回")),
                CapabilityDescriptor.implemented("ai.mcp", "MCP / Agent 接口", "D8 治理运营",
                        "docs/13 §6",
                        "MCP JSON-RPC 子集（initialize / tools/list / tools/call / ping），"
                                + "工具集含检索、资产详情、血缘、影响分析、质量结果、术语与契约；"
                                + "**工具清单与每次调用都按调用者权限裁剪**，每次调用写 access_event 审计；"
                                + "Agent 的写路径只有 propose_aspect（提建议），**不存在直写元数据的工具**",
                        List.of("已实现：核心子集（4 个方法 / 11 个工具）；"
                                        + "**未实现** resources / prompts / sampling / notifications / stdio 传输",
                                "安全要点：以调用者身份执行，不绕过 AccessPolicy；写操作只走建议或审批",
                                "验收：e2e 覆盖 清单按角色裁剪（admin 11 → reader 10）、检索调用、"
                                        + "越权被拒、提建议不直写元数据、未实现方法 -32601、调用全留痕")),
                CapabilityDescriptor.implemented("ai.semantic-layer", "语义层与指标", "D2 目录与发现",
                        "docs/13 §1.1",
                        "接入 dbt 语义层 / Cube / 平台原生 YAML，落地为 Metric 实体 + metric_definition，"
                                + "并建立 指标 ← 依赖列 的 consumedBy 血缘，口径可追溯到物理列与上游数据集",
                        List.of("已实现：YAML 接入（三种来源格式）+ 指标实体 + 口径血缘 + 指标详情接口",
                                "未解析到平台 URN 的列会**显式报告**（宁可缺边也不猜）",
                                "未实现：从 BI 工具反向同步指标（Superset 仅采集数据集/看板）",
                                "验收：e2e 覆盖 ingest → 指标可查 → 列级 consumedBy 边上溯；"
                                        + "不支持的 sourceFormat 被明确拒绝（422）")));
    }
}
