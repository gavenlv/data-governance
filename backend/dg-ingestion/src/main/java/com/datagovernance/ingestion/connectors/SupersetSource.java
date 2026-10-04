package com.datagovernance.ingestion.connectors;

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
import java.util.stream.Stream;

import com.datagovernance.core.UrnUtils;
import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Apache Superset 连接器（BI 资产：仪表板 + 图表）。
 *
 * <p>Superset 不是"数据源"，而是<b>消费端</b>：它告诉平台"哪些报表在被看、它们读的是哪些表"。
 * 因此本连接器产出的是 {@link RawModels.RawDashboard}，并通过
 * {@code Dashboard --readsFrom--> Dataset} 边把 BI 与表连起来 ——
 * 这样"这张表要改"，影响分析就能直接把受影响的报表列出来。
 *
 * <p>认证：Superset 的 REST API 用 {@code /api/v1/security/login} 换 Bearer token
 * （provider=db）。token 在本连接器生命周期内复用。
 *
 * <p>数据集映射：Superset 的 dataset 有 {@code table_name} 与 {@code schema}
 * （物理数据集）或 {@code sql}（虚拟数据集）。映射规则：
 * <ol>
 *   <li>物理数据集 → 按 {@code schema.table_name} 在库内查找同名 Dataset URN（**唯一才采用**）；</li>
 *   <li>虚拟数据集（有 sql）→ 不猜：SQL 解析属于血缘子系统的职责（sqlglot 侧车），
 *       连接器只记录 dataset 引用，不假装知道它读了哪张表。</li>
 * </ol>
 * 这条边界很重要：连接器猜出来的血缘，会让人在"报表为什么挂了"的排查里走向错误方向。
 */
public class SupersetSource implements Source {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String username;
    private final String password;
    private final String namespace;
    private final SupersetDatasetLookup datasetLookup;
    private final List<String> includeDashboards;

    private String token;

    /** 把 Superset 数据集映射为平台内的 Dataset URN（由采集服务提供，避免连接器直接依赖元数据库）。 */
    public interface SupersetDatasetLookup {
        /**
         * @return 命中的 Dataset URN；查不到或存在歧义时返回 null（**不猜**）
         */
        String resolve(String schema, String tableName);
    }

    public SupersetSource(String baseUrl, String username, String password, String namespace,
                          SupersetDatasetLookup datasetLookup, List<String> includeDashboards) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.namespace = namespace == null || namespace.isBlank() ? "prod" : namespace;
        this.datasetLookup = datasetLookup;
        this.includeDashboards = includeDashboards == null ? List.of() : includeDashboards;
    }

    /** 从 DSN 构造：{@code superset://user:password@host:port}。 */
    public static SupersetSource fromDsn(String dsn, String namespace, SupersetDatasetLookup lookup,
                                         List<String> includeDashboards) {
        String value = dsn == null ? "" : dsn.trim();
        String url = value;
        String user = null;
        String pass = null;
        if (value.contains("://")) {
            String scheme = value.substring(0, value.indexOf("://")).toLowerCase(java.util.Locale.ROOT);
            String rest = value.substring(value.indexOf("://") + 3);
            int at = rest.indexOf('@');
            if (at >= 0) {
                String credentials = rest.substring(0, at);
                rest = rest.substring(at + 1);
                int colon = credentials.indexOf(':');
                if (colon >= 0) {
                    user = credentials.substring(0, colon);
                    pass = credentials.substring(colon + 1);
                } else {
                    user = credentials;
                }
            }
            String protocol = scheme.startsWith("https") ? "https://" : "http://";
            url = protocol + rest;
        }
        return new SupersetSource(url, user, pass, namespace, lookup, includeDashboards);
    }

    @Override
    public String name() {
        return "superset";
    }

    @Override
    public String platform() {
        return "superset";
    }

    /** Superset 不产数据集实体（它是消费端）：这里刻意返回空，而不是把虚拟数据集当成表。 */
    @Override
    public Stream<RawModels.RawDataset> extract() {
        return Stream.empty();
    }

    @Override
    public boolean supportsDashboards() {
        return true;
    }

    @Override
    public Stream<RawModels.RawDashboard> extractDashboards() {
        Map<String, Object> payload = getMap("/api/v1/dashboard/?q=" + encode("(page_size:200)"));
        List<Map<String, Object>> dashboards = asMapList(payload.get("result"));
        Map<String, Map<String, Object>> chartsByName = chartIndex();

        List<RawModels.RawDashboard> out = new ArrayList<>();
        for (Map<String, Object> dashboard : dashboards) {
            String id = str(dashboard.get("id"));
            String title = blankToNull(str(dashboard.get("dashboard_title")));
            String slug = blankToNull(str(dashboard.get("slug")));
            String uuid = blankToNull(str(dashboard.get("uuid")));
            if (!includeDashboards.isEmpty() && (slug == null || !includeDashboards.contains(slug))) {
                continue;
            }

            // 详情接口才有 charts（而且是**图表名字符串列表**，不是对象）；
            // 图表的名字 → 数据源映射来自 /api/v1/chart 一次批量取回
            Map<String, Object> detail = asMap(getMap("/api/v1/dashboard/" + id).get("result"));
            Object rawCharts = detail.get("charts");
            List<Object> chartRefs = rawCharts instanceof List<?> list ? List.copyOf(list) : List.of();
            String description = blankToNull(str(detail.get("description")));

            List<Map<String, Object>> charts = new ArrayList<>();
            List<String> datasetUrns = new ArrayList<>();
            for (Object ref : chartRefs) {
                Map<String, Object> chart = chartsByName.get(String.valueOf(ref));
                Map<String, Object> item = new LinkedHashMap<>();
                if (chart == null) {
                    // 名字对不上（被改名/无权限）时如实记录，而不是把图表数算成 0
                    item.put("name", String.valueOf(ref));
                    item.put("resolved", false);
                    charts.add(item);
                    continue;
                }
                item.put("id", chart.get("id"));
                item.put("name", chart.get("slice_name"));
                item.put("vizType", chart.get("viz_type"));
                item.put("datasource", chart.get("datasource_name_text"));
                item.put("datasourceType", chart.get("datasource_type"));

                // datasource_type=table 才是**物理**数据源；query 是 SQL 定义的虚拟数据集。
                // 虚拟数据集的名字看起来也像 "schema.table"，靠名字去猜血缘会猜错 ——
                // 它读的是哪张表取决于 SQL，交给血缘子系统（sqlglot 侧车）解析，这里明确不猜。
                boolean physical = "table".equalsIgnoreCase(str(chart.get("datasource_type")));
                if (physical) {
                    String urn = resolveFromDatasourceName(str(chart.get("datasource_name_text")));
                    if (urn != null && !datasetUrns.contains(urn)) {
                        datasetUrns.add(urn);
                    }
                } else {
                    item.put("lineageNote", "虚拟数据集（SQL 定义）：不猜血缘，交由 sqlglot 解析其 SQL");
                }
                charts.add(item);
            }

            List<String> owners = new ArrayList<>();
            for (Map<String, Object> owner : asMapList(detail.get("owners"))) {
                String email = blankToNull(str(owner.get("email")));
                String name = java.util.stream.Stream.of(str(owner.get("first_name")), str(owner.get("last_name")))
                        .filter(part -> part != null && !"null".equals(part) && !part.isBlank())
                        .collect(java.util.stream.Collectors.joining(" "));
                owners.add(email != null ? email : (name.isBlank() ? "owner-" + owner.get("id") : name));
            }

            String externalId = slug != null ? slug : (uuid != null ? uuid : "dashboard-" + id);
            out.add(new RawModels.RawDashboard(
                    platform(),
                    externalId,
                    externalId,
                    title,
                    description,
                    baseUrl + "/superset/dashboard/" + (slug != null ? slug : id) + "/",
                    isPublished(detail, dashboard),
                    charts,
                    owners,
                    datasetUrns,
                    blankToNull(str(detail.get("changed_on_utc")))));
        }
        return out.stream();
    }

    /**
     * Superset 的 published 字段在不同版本/接口位置不同（列表里是 {@code published} 布尔，
     * 详情里可能是 {@code status} 字符串）。两者都读，避免因版本差异把已发布的报表标成未发布。
     */
    private static boolean isPublished(Map<String, Object> detail, Map<String, Object> listItem) {
        Object published = detail.get("published") != null ? detail.get("published") : listItem.get("published");
        if (published instanceof Boolean value) {
            return value;
        }
        String status = str(detail.get("status") != null ? detail.get("status") : listItem.get("status"));
        return "published".equalsIgnoreCase(status);
    }

    /** 图表索引：名字 → 图表（一次批量取回，避免逐仪表板为每个图表发一次请求）。 */
    private Map<String, Map<String, Object>> chartIndex() {
        Map<String, Object> payload = getMap("/api/v1/chart/?q=" + encode("(page_size:500)"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> chart : asMapList(payload.get("result"))) {
            String name = blankToNull(str(chart.get("slice_name")));
            if (name != null) {
                out.put(name, chart);
            }
        }
        return out;
    }

    /**
     * 从 Superset 的数据源名（{@code schema.table} 或 {@code catalog.schema.table}）解析 Dataset URN。
     *
     * <p>只有物理数据源会走到这里；虚拟数据源（SQL 定义的）由调用方过滤掉 ——
     * 它们读的是哪张表取决于 SQL，属于血缘子系统（sqlglot 侧车）的职责。
     */
    private String resolveFromDatasourceName(String datasourceName) {
        if (datasourceName == null || datasourceName.isBlank() || datasetLookup == null) {
            return null;
        }
        String value = datasourceName.trim();
        int lastDot = value.lastIndexOf('.');
        if (lastDot < 0) {
            return datasetLookup.resolve(null, value);
        }
        int previousDot = value.lastIndexOf('.', lastDot - 1);
        String schema = previousDot < 0 ? value.substring(0, lastDot) : value.substring(previousDot + 1, lastDot);
        String table = value.substring(lastDot + 1);
        return datasetLookup.resolve(schema, table);
    }

    // ------------------------------------------------------------------ HTTP

    private String authToken() {
        if (token != null) {
            return token;
        }
        if (username == null || password == null) {
            throw new IllegalStateException("Superset DSN 缺少凭据：期望 superset://user:password@host:port");
        }
        Map<String, Object> body = Map.of(
                "username", username, "password", password, "provider", "db", "refresh", true);
        String response = post("/api/v1/security/login", body);
        try {
            Map<String, Object> payload = MAPPER.readValue(response,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            token = String.valueOf(payload.get("access_token"));
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("解析 Superset 登录响应失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> getMap(String path) {
        return asMap(parse(send(path, "GET", null)));
    }

    private String post(String path, Map<String, Object> body) {
        return send(path, "POST", body);
    }

    private String send(String path, String method, Map<String, Object> body) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT);
        if (body != null) {
            try {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            } catch (IOException e) {
                throw new IllegalStateException("序列化 Superset 请求失败：" + e.getMessage(), e);
            }
        } else {
            builder.header("Accept", "application/json").method(method, HttpRequest.BodyPublishers.noBody());
            if (!path.contains("/security/login")) {
                builder.header("Authorization", "Bearer " + authToken());
            }
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("Superset 鉴权失败（HTTP " + response.statusCode()
                        + "）：请检查用户名 / 口令。响应：" + truncate(response.body()));
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Superset 返回 HTTP " + response.statusCode() + "："
                        + truncate(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("连接 Superset 失败（" + baseUrl + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 Superset 被中断", e);
        }
    }

    private static Object parse(String body) {
        try {
            return MAPPER.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (IOException e) {
            throw new IllegalStateException("解析 Superset 响应失败：" + e.getMessage(), e);
        }
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

    private static int parseInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
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
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    /** 数据集 URN 构造（与采集服务保持同一套规则）。 */
    public static String datasetUrn(String namespace, String platform, String database,
                                    String schema, String table) {
        return UrnUtils.build("Dataset", namespace,
                UrnUtils.sanitizeSegment(platform),
                UrnUtils.sanitizeSegment(database),
                UrnUtils.sanitizeSegment(schema),
                UrnUtils.sanitizeSegment(table));
    }
}
