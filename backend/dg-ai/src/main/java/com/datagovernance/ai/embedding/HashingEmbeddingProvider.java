package com.datagovernance.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **确定性哈希向量**（dev / 回归测试专用，明确不是语义模型）。
 *
 * <p>它把文本切成特征（英文标识符 + 中文二元组），用 SHA-256 把它们映射到固定维度并累加，
 * 最后单位化。因此：
 * <ul>
 *   <li>同样的文本永远得到同样的向量（可回归、可复现）；</li>
 *   <li>共享字面的文本相似度高，**语义相近但用词不同则不会** —— 它不是语义检索；</li>
 *   <li>它的价值是让"向量召回 → RRF 融合 → 授权前置过滤 → 截断回报"这条链路可被端到端验证，
 *       而不需要外部模型服务。</li>
 * </ul>
 *
 * <p>生产必须配置真实 embedding（{@code dg.ai.embedding.provider=openai}）。
 * 把 Hashing 用上生产会得到"看起来有语义检索、其实是词法"的效果 —— 这正是本项目反复避免的假象。
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "hash-256";

    private final int dimension;

    public HashingEmbeddingProvider(int dimension) {
        this.dimension = dimension <= 0 ? 256 : dimension;
    }

    @Override
    public String id() {
        return ID + "-" + dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        for (String feature : features(text)) {
            byte[] digest = sha256(feature);
            int index = Math.floorMod(readInt(digest, 0), dimension);
            // 用摘要的另一段决定符号，减少桶冲突带来的系统性偏移
            float sign = (digest[4] & 1) == 0 ? 1.0f : -1.0f;
            vector[index] += sign;
        }
        return normalize(vector);
    }

    static List<String> features(String text) {
        List<String> features = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return features;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String chunk : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            if (chunk.isBlank()) {
                continue;
            }
            boolean han = chunk.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN);
            if (han) {
                // 中文：单字 + 相邻二元组（与检索侧 bigram 切分同构，保证"能召回"）
                for (int index = 0; index < chunk.length(); index++) {
                    features.add(String.valueOf(chunk.charAt(index)));
                    if (index + 1 < chunk.length()) {
                        features.add(chunk.substring(index, index + 2));
                    }
                }
            } else {
                features.add(chunk);
                for (int index = 0; index + 2 < chunk.length(); index++) {
                    features.add(chunk.substring(index, index + 3));
                }
            }
        }
        return features;
    }

    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm <= 0) {
            return vector;
        }
        double length = Math.sqrt(norm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / length);
        }
        return vector;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JRE 缺少 SHA-256", e);
        }
    }
}
