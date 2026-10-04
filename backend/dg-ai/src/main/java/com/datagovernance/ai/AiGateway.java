package com.datagovernance.ai;

import com.datagovernance.model.Unimplemented;

/**
 * AI 能力的占位入口（骨架，未实现）。
 *
 * <p>注意：即使实现，本类的写方法也只能产出 {@code Suggestion} 而非直接写元数据
 * （docs/13 §3 的铁律）。因此这里的占位不是「暂时返回空」，而是「架构上就禁止直写」。
 */
@Unimplemented(doc = "docs/13", phase = "Phase 4",
        summary = "建议引擎（描述/标签/血缘）、语义检索、MCP Agent 接口、语义层接入")
public class AiGateway {

    public void suggestDescription(String urn) {
        throw new NotImplementedYet("AI 描述建议");
    }

    public void ask(String question) {
        throw new NotImplementedYet("自然语言问答");
    }

    public static class NotImplementedYet extends UnsupportedOperationException {
        public NotImplementedYet(String capability) {
            super(capability + " 尚未实现（见 /api/v1/capabilities 的 state 与 notes）");
        }
    }
}
