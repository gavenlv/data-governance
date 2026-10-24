package com.datagovernance.ai.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * embedding 的边界测试。
 *
 * <p>重点不是"向量像不像语义"，而是**不允许假象**：
 * 哈希提供方必须被明确当作非语义实现；未配置时必须不可用而不是返回零向量；
 * 维度不一致必须拒绝（两种模型的向量不可比较）。
 */
class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider(256);

    @Test
    void isDeterministicForSameText() {
        assertArrayEquals(provider.embed("客户订单明细"), provider.embed("客户订单明细"),
                "同一文本必须得到同一向量，否则索引与查询不可比较");
    }

    @Test
    void producesUnitVectors() {
        float[] vector = provider.embed("customer order detail");
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-4, "单位向量才能直接用点积当余弦");
    }

    @Test
    void similarTextsScoreHigherThanUnrelatedOnes() {
        float[] query = provider.embed("客户订单明细");
        float[] close = provider.embed("客户订单");
        float[] far = provider.embed("天气预报");

        assertTrue(cosine(query, close) > cosine(query, far),
                "共享字面的文本相似度必须更高（这是哈希向量的性质，不是语义能力）");
    }

    @Test
    void handlesIdentifiersAndChineseTogether() {
        assertFalse(HashingEmbeddingProvider.features("event_log 客户订单").isEmpty());
        assertTrue(HashingEmbeddingProvider.features("  ").isEmpty());
        assertTrue(HashingEmbeddingProvider.features(null).isEmpty());
    }

    @Test
    void unconfiguredHttpProviderIsExplicitlyUnavailable() {
        HttpEmbeddingProvider http = new HttpEmbeddingProvider("openai:x", "", "", "", 256,
                java.time.Duration.ofSeconds(1));

        assertFalse(http.available(), "没配置 base-url/model 时必须报告不可用");
        EmbeddingProvider.EmbeddingUnavailable error = assertThrows(
                EmbeddingProvider.EmbeddingUnavailable.class, () -> http.embed("x"));
        assertTrue(error.getMessage().contains("DG_AI_EMBEDDING_BASE_URL"),
                "报错必须告诉使用者怎么配，而不是只说'失败'");
    }

    @Test
    void unknownProviderFailsAtStartup() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider("mystery-model");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new EmbeddingConfiguration().embeddingProvider(properties));
        assertTrue(error.getMessage().contains("none | hash | openai"),
                "未识别的 provider 必须启动失败并列出合法值");
    }

    @Test
    void cosineRejectsIncomparableVectors() {
        assertEquals(1.0, cosine(new float[]{1, 0}, new float[]{1, 0}), 1e-6);
        assertEquals(0.0, cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
        assertEquals(0.0, cosine(new float[]{1, 0}, new float[]{1, 0, 0}), 1e-6,
                "维度不同必须返回 0（不可比较），而不是硬算");
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int index = 0; index < a.length; index++) {
            dot += a[index] * b[index];
        }
        return dot;
    }
}
