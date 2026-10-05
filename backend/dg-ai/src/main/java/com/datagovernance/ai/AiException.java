package com.datagovernance.ai;

/**
 * AI 能力错误。
 *
 * <p>分两类，因为它们的 HTTP 语义完全不同（这也是本类存在的理由）：
 * <ul>
 *   <li>{@link #KIND_NOT_CONFIGURED}「能力没配置」→ 502：这是<b>部署问题</b>，不是调用者写错了请求。
 *       典型场景：没配大模型端点却要求 LLM 生成 —— 平台必须明说，绝不退回模板冒充 AI；</li>
 *   <li>{@link #KIND_INVALID}「请求/内容不合法」→ 422：调用者能改。</li>
 * </ul>
 */
public class AiException extends RuntimeException {

    /** 能力未配置（缺大模型端点、缺 embedding 服务等）→ HTTP 502。 */
    public static final String KIND_NOT_CONFIGURED = "NOT_CONFIGURED";
    /** 请求或内容不合法（格式无法识别、状态非法等）→ HTTP 422。 */
    public static final String KIND_INVALID = "INVALID";

    private final String kind;

    public AiException(String message) {
        this(KIND_INVALID, message);
    }

    public AiException(String kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** 能力未配置：调用者改请求也没用，需要部署侧配置。 */
    public static AiException notConfigured(String message) {
        return new AiException(KIND_NOT_CONFIGURED, message);
    }

    public String kind() {
        return kind;
    }

    public boolean isNotConfigured() {
        return KIND_NOT_CONFIGURED.equals(kind);
    }
}
