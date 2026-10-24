package com.datagovernance.ai.embedding;

/**
 * 文本向量化（embedding）的可插拔接口（docs/13 §1.1）。
 *
 * <p>为什么做成接口而不是直接写死某家服务：
 * <ol>
 *   <li>企业环境里 embedding 可能是 OpenAI 兼容网关、自建模型或本地推理，URL 与鉴权各不相同；</li>
 *   <li>没有配置模型时，平台必须**明确不可用**，而不是用"看起来像向量"的东西冒充语义检索；</li>
 *   <li>测试需要确定性：{@link HashingEmbeddingProvider} 让向量链路的每个环节都能回归，
 *       但它**不是语义模型**，绝不能在生产里当成语义检索用。</li>
 * </ol>
 */
public interface EmbeddingProvider {

    /** 稳定的提供方标识，落到 {@code search_vector.provider}；换模型必须换标识。 */
    String id();

    /** 维度；与库中已有向量不一致时拒绝比较（不同模型向量不可比）。 */
    int dimension();

    /** 是否可用（未配置模型时为 false —— 调用方据此明确回报"向量路不可用"）。 */
    boolean available();

    /**
     * 把文本转成单位向量。
     *
     * @throws EmbeddingUnavailable 未配置或调用失败；错误信息必须包含"怎么配"
     */
    float[] embed(String text);

    /** 不可用时抛出（映射为 502，与 AI 未配置的语义一致：缺配置，不是调用方写错）。 */
    class EmbeddingUnavailable extends RuntimeException {
        public EmbeddingUnavailable(String message) {
            super(message);
        }
    }
}
