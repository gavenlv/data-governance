package com.datagovernance.sdk.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 传输层的行为测试。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 做桩，而不是 Mockito：这里要验证的是
 * **真正发出去的请求长什么样**（路径编码、鉴权头、错误映射），
 * mock 掉 HTTP 层就等于把要测的东西测没了。
 */
class DgClientTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/api/v1/capabilities", """
                {"summary":{"total":45,"implemented":39,"partial":5,"notImplemented":1},
                 "domains":{"D1":[{"id":"core.x","name":"X","domain":"D1","status":"IMPLEMENTED","summary":"s"}]}}
                """);
        respond("/api/v1/assets", """
                {"count":1,"assets":[{"urn":"urn:dg:Dataset:t.a.b","entity_type":"Dataset",
                  "namespace":"t","lifecycle":"ACTIVE","updated_at":"2026-01-01T00:00:00Z"}],
                 "visibleLevels":["L1","L2"]}
                """);
        respond("/api/v1/search", """
                {"query":"event","count":1,"results":[{"urn":"urn:dg:Dataset:t.a.b","entityType":"Dataset",
                  "displayName":"event_log","namespace":"t","platform":"postgresql","container":"pg.public",
                  "description":"d","tags":[],"owners":[]}]}
                """);
        respond("/api/v1/lineage/graph", """
                {"urn":"urn:dg:Dataset:t.a.b","direction":"downstream","maxDepth":2,
                 "nodes":[{"urn":"urn:dg:Dataset:t.c.d","depth":1}],
                 "edges":[{"from":"urn:dg:Dataset:t.a.b","to":"urn:dg:Dataset:t.c.d"}]}
                """);
        respond("/api/v1/collect/health", "{\"sources\":[{},{}]}");
        respond("/api/v1/secret", "{\"error\":\"forbidden\",\"message\":\"无权执行该操作\",\"hint\":\"申请访问\"}", 403);
        respond("/api/v1/missing", "{\"error\":\"not_found\",\"message\":\"资产不存在\"}", 404);
        respond("/api/v1/boom", "<html>bad gateway</html>", 502);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String path, String body) {
        respond(path, body, 200);
    }

    private void respond(String path, String body, int status) {
        server.createContext(path, exchange -> {
            try {
                lastAuthorization.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                // 用 String.valueOf 包一层：无查询串时 getQuery() 返回 null，
                // 而 ConcurrentHashMap 不接受 null 值（第一版就在这里 NPE，表现为"连接被关闭"）
                lastQuery.put(path, String.valueOf(exchange.getRequestURI().getQuery()));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (Throwable failure) {
                // 桩服务端自己的异常必须打印：否则客户端只会看到"连接被关闭"，
                // 看起来像被测代码坏了，实际是测试桩坏了
                failure.printStackTrace();
                throw failure;
            }
        });
    }

    @Test
    void sendsBearerTokenAndParsesCapabilities() {
        DgClient client = DgClient.of(baseUrl, "tkn");
        DgClient.Capabilities capabilities = client.capabilities();

        assertEquals("Bearer tkn", lastAuthorization.get("/api/v1/capabilities"),
                "鉴权头必须按 Bearer 发送，否则所有调用都会 401");
        assertEquals(45, capabilities.total());
        assertEquals(1, capabilities.items().size());
        assertEquals("core.x", capabilities.items().get(0).id());
    }

    @Test
    void omitsAuthorizationWhenNoToken() {
        DgClient.of(baseUrl, null).capabilities();
        assertTrue(lastAuthorization.get("/api/v1/capabilities").equals("null"),
                "没有令牌时不应发送 Authorization 头（否则错误信息会误导成'令牌无效'）");
    }

    @Test
    void mapsAssetPageAndQueryParameters() {
        DgClient.AssetPage page = DgClient.of(baseUrl, "t").assets(5);

        assertEquals(1, page.count());
        assertEquals("Dataset", page.assets().get(0).entityType(), "snake_case 字段应映射到 entityType");
        assertEquals("limit=5", lastQuery.get("/api/v1/assets"), "limit 必须真的带上");
    }

    @Test
    void mapsSearchResults() {
        DgClient.SearchResult result = DgClient.of(baseUrl, "t").search("event", 10);
        assertEquals("event_log", result.results().get(0).displayName());
        assertTrue(lastQuery.get("/api/v1/search").contains("q=event"));
    }

    @Test
    void mapsLineageGraphWithEdges() {
        DgClient.LineageGraph graph = DgClient.of(baseUrl, "t").lineage("urn:dg:Dataset:t.a.b", "downstream", 2);
        assertEquals(1, graph.nodes().size());
        assertTrue(graph.edges().isArray() && !graph.edges().isEmpty(), "边必须原样保留（类型未固化但数据要全）");
        assertTrue(lastQuery.get("/api/v1/lineage/graph").contains("depth=2"));
    }

    @Test
    void distinguishesForbiddenFromUnauthorized() {
        DgClient client = DgClient.of(baseUrl, "t");

        DgApiException forbidden = assertThrows(DgApiException.class, () -> client.getJson("/api/v1/secret"));
        assertEquals(403, forbidden.status());
        assertTrue(forbidden.isForbidden());
        assertFalse(forbidden.isUnauthorized(), "401 与 403 必须可区分：一个是没登录，一个是权限不够");
        assertEquals("无权执行该操作", forbidden.getMessage());
        assertEquals("申请访问", forbidden.hint(), "平台的 hint 要原样带出（那是排障信息）");
        assertFalse(forbidden.isRetryable(), "403 不该被重试");
    }

    @Test
    void mapsNotFoundAndNonJsonErrors() {
        DgClient client = DgClient.of(baseUrl, "t");

        DgApiException missing = assertThrows(DgApiException.class, () -> client.getJson("/api/v1/missing"));
        assertTrue(missing.isNotFound());
        assertEquals("not_found", missing.error());

        DgApiException boom = assertThrows(DgApiException.class, () -> client.getJson("/api/v1/boom"));
        assertEquals(502, boom.status());
        assertTrue(boom.isRetryable(), "5xx 才值得退避重试");
        assertTrue(boom.getMessage().contains("bad gateway"), "非 JSON 错误体也要保留可读原文");
    }

    @Test
    void reportsConnectionFailureSeparately() {
        DgClient client = DgClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .token("t")
                .timeout(java.time.Duration.ofSeconds(2))
                .build();

        DgClient.DgClientException error = assertThrows(DgClient.DgClientException.class,
                () -> client.getJson("/api/v1/capabilities"));
        assertTrue(error.getMessage().contains("无法连接平台"), "连不上必须与'服务端返回错误'区分");
    }

    @Test
    void normalizesBaseUrlTrailingSlashAndDescribesWithoutToken() {
        DgClient client = DgClient.builder().baseUrl(baseUrl + "/").token("secret-token").build();

        Map<String, Object> info = client.describe();
        assertEquals(baseUrl, info.get("baseUrl"));
        assertEquals(true, info.get("authenticated"));
        assertFalse(String.valueOf(info).contains("secret-token"), "诊断摘要不能泄漏令牌");

        // 末尾斜杠被归一化后，路径拼接不会出现 //api
        assertNotNull(client.capabilities());
    }

    @Test
    void exposesRawJsonForUnknownEndpoints() {
        DgClient client = DgClient.of(baseUrl, "t");
        assertEquals(2, client.collectHealth().path("sources").size());
        assertNotNull(client.getJson("/api/v1/capabilities").path("summary"));
        assertEquals(List.of("L1", "L2"), client.assets(1).visibleLevels());
    }
}
