package com.datagovernance.sdk.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 平台 REST API 的 Java 客户端（docs/09 §9.10）。
 *
 * <p>设计上刻意保持"薄"：**它不复制平台的业务逻辑，只负责传输与类型**。
 * 任何在 SDK 里做的判定（比如"这个资产对我可见吗"）都会与服务端漂移，
 * 而漂移的 SDK 比没有 SDK 更危险 —— 调用方会以为它和服务端一致。
 *
 * <p>三条约定：
 * <ol>
 *   <li><b>认证失败不等于无权限</b>：401/403 分别映射（{@link DgApiException#isUnauthorized()} /
 *       {@link DgApiException#isForbidden()}），调用方不该靠消息文本猜。</li>
 *   <li><b>不做自动重试</b>：写操作（采集、策略下发、审批）不幂等，客户端擅自重试会造出
 *       重复记录。只有调用方知道哪些请求可以安全重试，因此把 {@link DgApiException#isRetryable()}
 *       交给它判断，SDK 不替它决定。</li>
 *   <li><b>未识别的字段不报错</b>：服务端加字段不应让旧客户端崩溃（向前兼容），
 *       因此 DTO 全部 {@code ignoreUnknown}。</li>
 * </ol>
 *
 * <p>覆盖范围就是 CLI 与嵌入式组件用到的那部分读接口；写接口通过 {@link #post(String, Object)}
 * 暴露为"原样 JSON"，不编造类型 —— 契约没稳定的东西，过早固化只会制造假 API。
 */
public final class DgClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;

    private DgClient(Builder builder) {
        this.baseUrl = builder.baseUrl.endsWith("/") ? builder.baseUrl.substring(0, builder.baseUrl.length() - 1)
                : builder.baseUrl;
        this.token = builder.token;
        this.timeout = builder.timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(builder.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 便捷构造：{@code DgClient.of(url, token)}。 */
    public static DgClient of(String baseUrl, String token) {
        return builder().baseUrl(baseUrl).token(token).build();
    }

    // ------------------------------------------------------------------ 读接口

    /** 能力清单（平台唯一的"做完了哪些"真相源）。 */
    public Capabilities capabilities() {
        JsonNode root = getJson("/api/v1/capabilities");
        JsonNode summary = root.path("summary");
        List<Capability> items = new ArrayList<>();
        JsonNode domains = root.path("domains");
        domains.fields().forEachRemaining(entry -> entry.getValue().forEach(item -> items.add(new Capability(
                item.path("id").asText(), item.path("name").asText(), item.path("domain").asText(),
                item.path("status").asText(), item.path("summary").asText()))));
        return new Capabilities(
                summary.path("total").asInt(), summary.path("implemented").asInt(),
                summary.path("partial").asInt(), summary.path("notImplemented").asInt(), items);
    }

    /** 资产列表（受调用者可见性约束：服务端已做前置过滤）。 */
    public AssetPage assets(int limit) {
        return read("/api/v1/assets?limit=" + Math.max(1, limit), AssetPage.class);
    }

    /** 资产详情（结构 / Aspect / Owner / 分级）。返回原始 JSON：详情形状随实体类型变化，固化类型会撒谎。 */
    public JsonNode asset(String urn) {
        return getJson("/api/v1/assets/" + path(urn));
    }

    /** 全文检索（服务端前置授权过滤，未授权资产不会出现在结果里）。 */
    public SearchResult search(String query, int limit) {
        return read("/api/v1/search?q=" + query(query) + "&limit=" + Math.max(1, limit), SearchResult.class);
    }

    /** 血缘图（深度有界；服务端会显式回报截断）。 */
    public LineageGraph lineage(String urn, String direction, int depth) {
        return read("/api/v1/lineage/graph?urn=" + query(urn) + "&direction=" + query(direction)
                + "&depth=" + Math.max(1, depth), LineageGraph.class);
    }

    /** 影响分析 / 爆炸半径。 */
    public JsonNode impact(String urn, int depth) {
        return getJson("/api/v1/lineage/impact/" + path(urn) + "?depth=" + Math.max(1, depth));
    }

    /** 采集健康度。 */
    public JsonNode collectHealth() {
        return getJson("/api/v1/collect/health");
    }

    /** 原样读取任意路径（平台新增接口不需要等 SDK 发版）。 */
    public JsonNode getJson(String path) {
        return parse(send("GET", path, null));
    }

    /** 原样提交 JSON。 */
    public JsonNode post(String path, Object body) {
        return parse(send("POST", path, body));
    }

    // ------------------------------------------------------------------ 传输

    private String send(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            String json;
            try {
                json = body == null ? "" : MAPPER.writeValueAsString(body);
            } catch (IOException e) {
                throw new IllegalArgumentException("无法序列化请求体", e);
            }
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 连不上与"服务端返回错误"必须可区分：前者通常是配错地址或服务没起
            throw new DgClientException("无法连接平台 " + baseUrl + "：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DgClientException("请求被中断", e);
        }

        if (response.statusCode() >= 400) {
            throw toApiException(response.statusCode(), response.body());
        }
        return response.body();
    }

    private static DgApiException toApiException(int status, String body) {
        String error = null;
        String message = null;
        String hint = null;
        try {
            JsonNode node = MAPPER.readTree(body);
            error = node.path("error").asText(null);
            message = node.path("message").asText(null);
            hint = node.path("hint").asText(null);
        } catch (IOException ignored) {
            // 非 JSON 错误体（例如代理返回的 HTML）：保留状态码，消息里带上截断的原文
            message = body == null || body.isBlank() ? null : body.substring(0, Math.min(200, body.length()));
        }
        return new DgApiException(status, error, message, hint);
    }

    private <T> T read(String path, Class<T> type) {
        String body = send("GET", path, null);
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new DgClientException("无法解析平台响应为 " + type.getSimpleName() + "：" + e.getMessage(), e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new DgClientException("无法解析平台响应：" + e.getMessage(), e);
        }
    }

    /** 路径段编码：URN 含 {@code :} 与空格，必须整体编码后再拼。 */
    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ DTO

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(int total, int implemented, int partial, int notImplemented,
                               List<Capability> items) {
        public List<Capability> byStatus(String status) {
            return items == null ? List.of() : items.stream()
                    .filter(item -> status.equalsIgnoreCase(item.status())).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capability(String id, String name, String domain, String status, String summary) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssetPage(int count, List<AssetSummary> assets, List<String> visibleLevels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssetSummary(String urn,
                               @JsonProperty("entity_type") String entityType,
                               String namespace, String lifecycle,
                               @JsonProperty("updated_at") String updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String query, int count, List<SearchHit> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchHit(String urn, String entityType, String displayName, String namespace,
                            String platform, String container, String description,
                            List<String> tags, List<String> owners) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineageGraph(String urn, String direction, int maxDepth,
                               List<LineageNode> nodes, JsonNode edges) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineageNode(String urn, int depth) {
    }

    /** 连接/解析层面的失败（不是平台返回的业务错误）。 */
    public static class DgClientException extends RuntimeException {
        public DgClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {
        private String baseUrl = "http://127.0.0.1:8081";
        private String token;
        private Duration timeout = Duration.ofSeconds(20);

        public Builder baseUrl(String baseUrl) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                this.baseUrl = baseUrl.trim();
            }
            return this;
        }

        public Builder token(String token) {
            this.token = token == null ? null : token.trim();
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                this.timeout = timeout;
            }
            return this;
        }

        public DgClient build() {
            return new DgClient(this);
        }
    }

    /** 供测试/诊断：当前生效的配置摘要（不含令牌）。 */
    public Map<String, Object> describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("baseUrl", baseUrl);
        info.put("authenticated", token != null && !token.isBlank());
        info.put("timeoutSeconds", timeout.toSeconds());
        return info;
    }
}
