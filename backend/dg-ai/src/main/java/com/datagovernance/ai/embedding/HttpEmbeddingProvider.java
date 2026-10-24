package com.datagovernance.ai.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的 embedding 服务（生产路径）。
 *
 * <p>只依赖 {@code POST {base-url}/embeddings} 这个通用形状，因此对接 Azure OpenAI 网关、
 * 自建 vLLM、通义/智谱兼容层都不需要改代码。三件必须做对的事：
 * <ol>
 *   <li><b>未配置就明确不可用</b>：{@link #available()} 为 false，混合检索会显式回报
 *       "向量路不可用"，而不是静默退化成词法；</li>
 *   <li><b>维度必须诚实</b>：响应维度与配置不一致时直接失败 —— 拿两种模型的向量算余弦
 *       会得到"看起来有分数、其实没有意义"的结果；</li>
 *   <li><b>失败要可诊断</b>：错误信息带上 HTTP 状态与响应片段，但不带 API key。</li>
 * </ol>
 */
public final class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int dimension;
    private final Duration timeout;
    private final HttpClient http;

    public HttpEmbeddingProvider(String id, String baseUrl, String model, String apiKey,
                                 int dimension, Duration timeout) {
        this.id = id;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.dimension = dimension;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean available() {
        return !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    @Override
    public float[] embed(String text) {
        if (!available()) {
            throw new EmbeddingUnavailable(
                    "embedding 服务未配置：设置 DG_AI_EMBEDDING_PROVIDER=openai 与 "
                            + "DG_AI_EMBEDDING_BASE_URL / DG_AI_EMBEDDING_MODEL（可选 DG_AI_EMBEDDING_API_KEY）。"
                            + "未配置时平台不会假装有语义检索");
        }
        try {
            String body = MAPPER.writeValueAsString(Map.of("model", model,
                    "input", text == null ? "" : text));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new EmbeddingUnavailable("embedding 服务返回 HTTP " + response.statusCode() + "："
                        + snippet(response.body()));
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode embedding = root.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new EmbeddingUnavailable("embedding 响应缺少 data[0].embedding：" + snippet(response.body()));
            }
            if (embedding.size() != dimension) {
                throw new EmbeddingUnavailable("embedding 维度不一致：配置 " + dimension
                        + "，响应 " + embedding.size()
                        + "。换模型必须同步改 dg.ai.embedding.dimension 并重建向量，"
                        + "否则新旧向量不可比较");
            }
            List<Float> values = new ArrayList<>(embedding.size());
            embedding.forEach(node -> values.add((float) node.asDouble()));
            float[] vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) {
                vector[index] = values.get(index);
            }
            return HashingEmbeddingProvider.normalize(vector);
        } catch (EmbeddingUnavailable e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingUnavailable("调用 embedding 服务失败：" + e.getMessage());
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    @Override
    public String toString() {
        return "HttpEmbeddingProvider{" + id + ", model=" + model + ", dimension=" + dimension + "}";
    }
}
