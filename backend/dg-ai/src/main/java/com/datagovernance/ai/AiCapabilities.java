package com.datagovernance.ai;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-ai 的能力声明。
 *
 * <p><b>本模块当前为骨架，未实现任何能力</b>（docs/13，Phase 4 交付）。
 *
 * <p>核心纪律（docs/13 §3）：<b>AI 永远不直接写正式元数据</b> ——
 * 一切 AI 产出先落 {@code Suggestion}（含 provenance / 置信度 / 证据引用），
 * 人工接受后才写入。没有审计的 AI 元数据比没有元数据更危险。
 */
@Component
public class AiCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.notImplemented("ai.suggestion", "AI 建议引擎", "D8 治理运营",
                        "docs/13 §3", "Phase 4",
                        "描述/标签/术语映射/血缘的候选建议，全部带 provenance 与置信度，人工确认后生效",
                        List.of("未实现", "纪律：AI 只写 Suggestion，绝不直写 aspect",
                                "前提条件：核心资产治理覆盖率 ≥ 60%，否则是「用 AI 放大垃圾」")),
                CapabilityDescriptor.notImplemented("ai.semantic-search", "语义检索与问答", "D2 目录与发现",
                        "docs/13 §1.1", "Phase 4",
                        "向量 + 关键词混合检索（RRF 融合 + rerank）；问答必须基于语义层而非裸 SQL",
                        List.of("未实现", "设计要点：检索必须前置授权过滤，不能取回结果再筛")),
                CapabilityDescriptor.notImplemented("ai.mcp", "MCP / Agent 接口", "D8 治理运营",
                        "docs/13 §6", "Phase 4",
                        "以标准协议把目录能力暴露给外部 Agent（搜索/取血缘/查术语/提交建议）",
                        List.of("未实现", "安全要点：以调用者身份执行，写操作只走建议或审批，全量调用审计",
                                "MCP 2026-07-28 为正式规范且已转入 Linux Foundation 治理")),
                CapabilityDescriptor.notImplemented("ai.semantic-layer", "语义层与指标", "D2 目录与发现",
                        "docs/13 §1.1", "Phase 4",
                        "接入 dbt Semantic Layer / Cube / Apache Ossie，把指标口径挂到物理列与血缘上",
                        List.of("未实现", "没有语义层，AI 问答的准确率天花板很低")));
    }
}
