package com.datagovernance.ai.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * embedding 配置（docs/13 §1.1）。
 *
 * <p>{@code provider} 三选一：
 * <ul>
 *   <li>{@code none}（默认）：**明确不可用** —— 混合检索会回报"向量路不可用"，不假装有语义检索；</li>
 *   <li>{@code hash}：确定性哈希向量，**仅用于打通链路与回归测试**，不是语义模型；</li>
 *   <li>{@code openai}：OpenAI 兼容的 embedding 服务（生产）。</li>
 * </ul>
 *
 * <p>{@code dimension} 必须与所选模型一致：拿两种模型的向量算余弦会得到
 * "有分数但没意义"的结果，因此平台会校验并拒绝不一致的向量。
 */
@ConfigurationProperties(prefix = "dg.ai.embedding")
public class EmbeddingProperties {

    private String provider = "none";
    private String baseUrl = "";
    private String model = "";
    private String apiKey = "";
    private int dimension = 256;
    private int timeoutSeconds = 10;

    /** 无 ANN 索引时的扫描上限：超过就先截断并显式回报（绝不静默只算一部分）。 */
    private int maxScan = 20000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxScan() {
        return maxScan;
    }

    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }
}
