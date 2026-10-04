package com.datagovernance.ingestion.connectors;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * BigQuery 连接器（docs/09 §9.1、docs/11 §1.1 的白名单核心源）。
 *
 * <p>实现选择：<b>直接用 BigQuery REST API + 服务账号 JWT</b>，而不是拉入
 * {@code google-cloud-bigquery}（它会把 gRPC、protobuf、auth 库等一大串依赖带进来）。
 * 我们只做三件只读的事：列数据集、列表、读表 schema —— REST 完全够用，
 * 自签 JWT（RS256）换 access token 也就几十行标准代码。
 *
 * <p>为什么这件事值得在文档里写清楚：连接器的依赖体积会长期影响构建、打包与安全补丁面。
 * "能用官方 SDK 就用"是默认正确的，但当 SDK 的体积主要花在我们用不到的能力上时，
 * 直接打 HTTP 是更负责的选择。
 *
 * <p>配置方式（DSN）：
 * <pre>
 *   bigquery://my-project
 *   bigquery://my-project?credentials=/path/to/service-account.json
 *   bigquery://my-project?emulator=http://127.0.0.1:9050   # 模拟器/无鉴权端点
 * </pre>
 * 凭据也可用环境变量 {@code GOOGLE_APPLICATION_CREDENTIALS} 指定；
 * 两者都没有时<b>直接报错</b>，不会静默退化成匿名请求（那会得到一个费解的 401）。
 */
public class BigQuerySource implements Source {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_ENDPOINT = "https://bigquery.googleapis.com/bigquery/v2";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    private final String project;
    private final String endpoint;
    private final String credentialsPath;
    private final List<String> includeDatasets;
    private final List<String> includeTables;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    public BigQuerySource(String project, String credentialsPath, String emulatorEndpoint,
                          List<String> includeDatasets, List<String> includeTables) {
        this.project = project;
        this.credentialsPath = credentialsPath;
        this.endpoint = emulatorEndpoint == null || emulatorEndpoint.isBlank()
                ? DEFAULT_ENDPOINT
                : emulatorEndpoint.replaceAll("/+$", "") + "/bigquery/v2";
        this.includeDatasets = includeDatasets == null ? List.of() : includeDatasets;
        this.includeTables = includeTables == null ? List.of() : includeTables;
    }

    /** 从 DSN 构造。 */
    public static BigQuerySource fromDsn(String dsn, List<String> datasets, List<String> tables) {
        String value = dsn == null ? "" : dsn.trim();
        String project = value;
        String credentials = null;
        String emulator = null;
        if (value.contains("://")) {
            String scheme = value.substring(0, value.indexOf("://")).toLowerCase(java.util.Locale.ROOT);
            // 方案必须显式校验：否则 `mysql://host/db` 会被当成"项目名是 host/db"悄悄接受，
            // 最后卡在一个费解的 API 错误上（而不是"你连错源了"）
            if (!scheme.equals("bigquery") && !scheme.equals("bq")) {
                throw new IllegalStateException("BigQuery DSN 的协议必须是 bigquery://，收到 " + scheme + "://");
            }
            String rest = value.substring(value.indexOf("://") + 3);
            int query = rest.indexOf('?');
            String path = query < 0 ? rest : rest.substring(0, query);
            project = path.replaceAll("/+$", "");
            if (query >= 0) {
                for (String pair : rest.substring(query + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = pair.substring(0, eq);
                    String raw = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    switch (key) {
                        case "credentials" -> credentials = raw;
                        case "emulator", "endpoint", "baseUrl" -> emulator = raw;
                        case "project" -> project = raw;
                        default -> { }
                    }
                }
            }
        }
        if (credentials == null) {
            credentials = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        }
        if (emulator == null) {
            emulator = System.getenv("BIGQUERY_EMULATOR_HOST");
        }
        if (project == null || project.isBlank()) {
            throw new IllegalStateException("BigQuery DSN 缺少项目 ID：期望 bigquery://<project>?...");
        }
        return new BigQuerySource(project, credentials, emulator, datasets, tables);
    }

    @Override
    public String name() {
        return "bigquery";
    }

    @Override
    public String platform() {
        return "bigquery";
    }

    @Override
    public Stream<RawModels.RawDataset> extract() {
        List<RawModels.RawDataset> datasets = new ArrayList<>();
        for (String datasetId : listDatasets()) {
            if (!includeDatasets.isEmpty() && !includeDatasets.contains(datasetId)) {
                continue;
            }
            for (Map<String, Object> table : listTables(datasetId)) {
                String tableId = str(table.get("tableId"));
                if (!includeTables.isEmpty() && !includeTables.contains(tableId)) {
                    continue;
                }
                datasets.add(describe(datasetId, tableId, table));
            }
        }
        return datasets.stream();
    }

    private List<String> listDatasets() {
        Map<String, Object> payload = get("/projects/" + project + "/datasets?maxResults=1000");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : asMapList(payload.get("datasets"))) {
            Map<String, Object> reference = asMap(item.get("datasetReference"));
            String id = str(reference.get("datasetId"));
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    private List<Map<String, Object>> listTables(String datasetId) {
        Map<String, Object> payload = get("/projects/" + project + "/datasets/" + encode(datasetId)
                + "/tables?maxResults=1000");
        return asMapList(payload.get("tables")).stream().map(BigQuerySource::normalizeReference).toList();
    }

    /** 列表接口只返回引用，schema 需要逐表读取（BigQuery 没有批量带 schema 的列表接口）。 */
    private static Map<String, Object> normalizeReference(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>(item);
        Map<String, Object> reference = asMap(item.get("tableReference"));
        if (reference.get("tableId") != null) {
            out.put("tableId", reference.get("tableId"));
        }
        return out;
    }

    private RawModels.RawDataset describe(String datasetId, String tableId, Map<String, Object> listItem) {
        Map<String, Object> table = listItem;
        if (listItem.get("schema") == null) {
            table = get("/projects/" + project + "/datasets/" + encode(datasetId)
                    + "/tables/" + encode(tableId));
        }
        Map<String, Object> schema = asMap(table.get("schema"));
        List<RawModels.RawColumn> columns = new ArrayList<>();
        int ordinal = 1;
        for (Map<String, Object> field : asMapList(schema.get("fields"))) {
            String mode = field.get("mode") == null ? "NULLABLE" : String.valueOf(field.get("mode"));
            columns.add(new RawModels.RawColumn(
                    str(field.get("name")),
                    normalizeType(str(field.get("type")), asMapList(field.get("fields")).size()),
                    !"REQUIRED".equalsIgnoreCase(mode),
                    ordinal++,
                    blankToNull(str(field.get("description"))),
                    null));
        }
        String kind = table.get("type") == null ? "TABLE" : String.valueOf(table.get("type"));
        String comment = blankToNull(str(table.get("description")));
        long numRows = table.get("numRows") == null ? -1 : parseLong(table.get("numRows"));
        String note = "BigQuery " + kind + "；numRows≈" + numRows + "（元数据估算，非实算）";
        return new RawModels.RawDataset(
                platform(), project, datasetId, tableId, kind,
                comment == null ? note : comment + "（" + note + "）",
                columns, List.of(), List.of());
    }

    /** RECORD/STRUCT 带嵌套字段数，便于一眼看出结构的复杂度。 */
    private static String normalizeType(String type, int nestedFieldCount) {
        if (type == null) {
            return "UNKNOWN";
        }
        if (("RECORD".equals(type) || "STRUCT".equals(type)) && nestedFieldCount > 0) {
            return "STRUCT<" + nestedFieldCount + " fields>";
        }
        return type;
    }

    // ------------------------------------------------------------------ HTTP

    private Map<String, Object> get(String path) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        String token = accessToken();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("BigQuery 鉴权失败（HTTP " + response.statusCode()
                        + "）：请检查服务账号凭据与项目权限。响应：" + truncate(response.body()));
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("BigQuery 返回 HTTP " + response.statusCode() + "："
                        + truncate(response.body()));
            }
            return MAPPER.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (IOException e) {
            throw new IllegalStateException("连接 BigQuery 失败（" + endpoint + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 BigQuery 被中断", e);
        }
    }

    /** 取 access token：模拟器/无凭据模式下返回 null（不带 Authorization 头）。 */
    private String accessToken() {
        if (endpoint != DEFAULT_ENDPOINT) {
            return null; // 模拟器不需要鉴权
        }
        if (credentialsPath == null || credentialsPath.isBlank()) {
            throw new IllegalStateException(
                    "缺少 BigQuery 凭据：请用 bigquery://<project>?credentials=/path/sa.json 指定服务账号，"
                            + "或设置 GOOGLE_APPLICATION_CREDENTIALS（不会退化为匿名请求）");
        }
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        try {
            Map<String, Object> serviceAccount = MAPPER.readValue(
                    Files.readString(Path.of(credentialsPath), StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            String clientEmail = String.valueOf(serviceAccount.get("client_email"));
            String privateKeyPem = String.valueOf(serviceAccount.get("private_key"));
            String assertion = buildJwt(clientEmail, privateKeyPem);
            String body = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion=" + encode(assertion);
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("获取 BigQuery access token 失败（HTTP "
                        + response.statusCode() + "）：" + truncate(response.body()));
            }
            Map<String, Object> token = MAPPER.readValue(response.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            cachedToken = String.valueOf(token.get("access_token"));
            long expiresIn = token.get("expires_in") == null ? 3600 : parseLong(token.get("expires_in"));
            cachedTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
            return cachedToken;
        } catch (IOException e) {
            throw new IllegalStateException("读取 BigQuery 服务账号凭据失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取 BigQuery access token 被中断", e);
        }
    }

    /** 自签 RS256 JWT（BigQuery 的 JWT-bearer 流程）。 */
    private static String buildJwt(String clientEmail, String privateKeyPem) {
        try {
            long now = Instant.now().getEpochSecond();
            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String claims = MAPPER.writeValueAsString(Map.of(
                    "iss", clientEmail,
                    "scope", "https://www.googleapis.com/auth/bigquery.readonly",
                    "aud", TOKEN_ENDPOINT,
                    "iat", now,
                    "exp", now + 3600));
            String payload = base64Url(claims.getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;

            String pem = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64Url(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("构造 BigQuery JWT 失败：" + e.getMessage(), e);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, val) -> out.put(String.valueOf(key), val));
            return out;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(asMap(map));
            }
        }
        return out;
    }

    private static long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String value) {
        return value == null ? "" : value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
