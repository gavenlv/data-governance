package com.datagovernance.ingestion.connectors;

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

import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ClickHouse 连接器（docs/09 §9.1、docs/11 §1.1 的白名单核心源）。
 *
 * <p>实现选择：走 <b>HTTP 接口</b>（默认 8123）而不是 JDBC 驱动。
 * 理由：ClickHouse 的 HTTP 接口是一等公民（`clickhouse-client` 与官方文档都基于它），
 * 而 JDBC 驱动会额外引入一份不小的依赖；对"只读元数据"这件事，
 * {@code system.tables} + {@code system.columns} 两次查询就够了。
 *
 * <p>元数据来源：
 * <ul>
 *   <li>{@code system.tables}：引擎、注释、分区键、排序键、**行数估算**；</li>
 *   <li>{@code system.columns}：列名、类型、默认表达式、注释。</li>
 * </ul>
 *
 * <p><b>行数与精度</b>：{@code system.tables.total_rows} 是**估算值**（可能为 NULL 或明显偏离），
 * 因此它不会被当成实算行数使用 —— 与 docs/09 §9.4 的"元数据级统计必须标注精度"一致：
 * 这里把它作为列注释之外的辅助信息保留在 properties，而不写进任何"精确行数"字段。
 */
public class ClickHouseSource implements Source {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String user;
    private final String password;
    private final List<String> includeDatabases;
    private final List<String> includeTables;

    public ClickHouseSource(String baseUrl, String user, String password,
                            List<String> includeDatabases, List<String> includeTables) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.user = user == null || user.isBlank() ? "default" : user;
        this.password = password == null ? "" : password;
        this.includeDatabases = includeDatabases == null ? List.of() : includeDatabases;
        this.includeTables = includeTables == null ? List.of() : includeTables;
    }

    /** 从 DSN 构造：{@code clickhouse://user:password@host:8123}（也接受 http:// 前缀）。 */
    public static ClickHouseSource fromDsn(String dsn, List<String> databases, List<String> tables) {
        String value = dsn == null ? "" : dsn.trim();
        String user = null;
        String password = null;
        String host = value;
        if (value.contains("://")) {
            int schemeEnd = value.indexOf("://");
            String scheme = value.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
            host = value.substring(schemeEnd + 3);
            if (scheme.equals("clickhouse") || scheme.equals("https")) {
                host = "http://" + host;
            } else if (scheme.equals("clickhouses")) {
                host = "https://" + host;
            } else {
                host = value;
            }
        }
        String authority = host.contains("://") ? host.substring(host.indexOf("://") + 3) : host;
        int at = authority.indexOf('@');
        if (at >= 0) {
            String credentials = authority.substring(0, at);
            authority = authority.substring(at + 1);
            int colon = credentials.indexOf(':');
            if (colon >= 0) {
                user = credentials.substring(0, colon);
                password = credentials.substring(colon + 1);
            } else {
                user = credentials;
            }
            host = (host.contains("://") ? host.substring(0, host.indexOf("://") + 3) : "") + authority;
        }
        return new ClickHouseSource(host, user, password, databases, tables);
    }

    @Override
    public String name() {
        return "clickhouse";
    }

    @Override
    public String platform() {
        return "clickhouse";
    }

    @Override
    public Stream<RawModels.RawDataset> extract() {
        String databaseFilter = includeDatabases.isEmpty() ? "" : " AND database IN ("
                + quotedList(includeDatabases) + ")";
        String tableFilter = includeTables.isEmpty() ? "" : " AND name IN (" + quotedList(includeTables) + ")";

        List<Map<String, Object>> tables = query("""
                SELECT database, name, engine, comment, total_rows, partition_key, sorting_key
                  FROM system.tables
                 WHERE database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')
                """ + databaseFilter + tableFilter + " ORDER BY database, name");

        List<Map<String, Object>> columns = query("""
                SELECT database, table, name, type, comment, is_in_primary_key
                  FROM system.columns
                 WHERE database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')
                """ + databaseFilter + " ORDER BY database, table, position");

        // database.table → 列列表（一次读回，避免逐表查询）
        Map<String, List<RawModels.RawColumn>> columnsByTable = new LinkedHashMap<>();
        Map<String, List<String>> primaryKeys = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) {
            String key = str(column.get("database")) + "." + str(column.get("table"));
            int ordinal = columnsByTable.getOrDefault(key, List.of()).size() + 1;
            columnsByTable.computeIfAbsent(key, k -> new ArrayList<>()).add(new RawModels.RawColumn(
                    str(column.get("name")),
                    str(column.get("type")),
                    true, // ClickHouse 列默认可空（Nullable 包装），严格判定需要解析类型串
                    ordinal,
                    blankToNull(str(column.get("comment"))),
                    null));
            if (isTrue(column.get("is_in_primary_key"))) {
                primaryKeys.computeIfAbsent(key, k -> new ArrayList<>()).add(str(column.get("name")));
            }
        }

        List<RawModels.RawDataset> datasets = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String database = str(table.get("database"));
            String name = str(table.get("name"));
            String key = database + "." + name;
            List<RawModels.RawColumn> cols = columnsByTable.getOrDefault(key, List.of());
            if (cols.isEmpty()) {
                continue;
            }
            String comment = blankToNull(str(table.get("comment")));
            String engine = str(table.get("engine"));
            String note = buildNote(engine, table);
            // 分区键/排序键进 datasetSchema.partitionKeys（模型里本就为此预留的结构化字段），
            // 而不是混在描述文本里 —— 结构化信息要能被人和程序分别使用
            List<Map<String, Object>> partitionKeys = new ArrayList<>();
            if (blankToNull(str(table.get("partition_key"))) != null) {
                partitionKeys.add(Map.of("role", "PARTITION_BY", "expression", str(table.get("partition_key"))));
            }
            if (blankToNull(str(table.get("sorting_key"))) != null) {
                partitionKeys.add(Map.of("role", "ORDER_BY", "expression", str(table.get("sorting_key"))));
            }
            datasets.add(new RawModels.RawDataset(
                    platform(), database, database, name,
                    engine == null ? "TABLE" : engine.toUpperCase(java.util.Locale.ROOT),
                    comment == null ? note : comment + "（" + note + "）",
                    cols, primaryKeys.getOrDefault(key, List.of()), partitionKeys));
        }
        return datasets.stream();
    }

    /**
     * 引擎与键信息进注释。
     *
     * <p>刻意把 {@code total_rows} 写成"估算"字样：这是元数据级统计，
     * 与 profiling 的实算行数可能相差很远（docs/09 §9.4 补充 26 的同类问题在 ClickHouse 同样存在）。
     */
    private static String buildNote(String engine, Map<String, Object> table) {
        List<String> parts = new ArrayList<>();
        if (engine != null && !engine.isBlank()) {
            parts.add("engine=" + engine);
        }
        Object partitionKey = table.get("partition_key");
        if (partitionKey != null && !str(partitionKey).isBlank()) {
            parts.add("partitionBy=" + str(partitionKey));
        }
        Object sortingKey = table.get("sorting_key");
        if (sortingKey != null && !str(sortingKey).isBlank()) {
            parts.add("orderBy=" + str(sortingKey));
        }
        Object totalRows = table.get("total_rows");
        if (totalRows != null) {
            parts.add("total_rows≈" + str(totalRows) + "（估算）");
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ HTTP

    private List<Map<String, Object>> query(String sql) {
        String url = baseUrl + "/?default_format=JSON&user=" + encode(user)
                + (password.isEmpty() ? "" : "&password=" + encode(password));
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ClickHouse 返回 HTTP " + response.statusCode() + "："
                        + truncate(response.body()));
            }
            Map<String, Object> payload = MAPPER.readValue(response.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object data = payload.get("data");
            if (!(data instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    out.add(item);
                }
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("连接 ClickHouse 失败（" + baseUrl + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 ClickHouse 被中断", e);
        }
    }

    private static String quotedList(List<String> values) {
        return values.stream().map(value -> "'" + value.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
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

    private static boolean isTrue(Object value) {
        return value != null && (Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value))
                || "true".equalsIgnoreCase(String.valueOf(value)));
    }
}
