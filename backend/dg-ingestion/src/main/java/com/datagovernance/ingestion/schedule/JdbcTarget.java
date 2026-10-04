package com.datagovernance.ingestion.schedule;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把调度里的 DSN 解析为 JDBC 连接参数。
 *
 * <p>同时接受两种写法，因为在真实环境里两种都会遇到：
 * <ul>
 *   <li>{@code postgresql://user:pass@host:port/db}（psycopg2 / 文档里的常见形式）</li>
 *   <li>{@code jdbc:postgresql://host:port/db?user=u&password=p}（JDBC 原生形式）</li>
 * </ul>
 *
 * <p>口令从查询串里取出后<b>会从 URL 中删除</b>：否则口令会随异常信息、连接池指标、
 * 日志一起被打印出来 —— 这是实际发生过的泄露路径。
 */
public record JdbcTarget(String jdbcUrl, String username, String password) {

    public static JdbcTarget parse(String dsn) {
        if (dsn == null || dsn.isBlank()) {
            throw new ScheduleException("DSN 为空，无法建立连接");
        }
        String value = dsn.trim();
        if (value.startsWith("jdbc:")) {
            return parseJdbc(value);
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            throw new ScheduleException("DSN 格式无法识别（期望 postgresql:// 或 jdbc:postgresql://）："
                    + ScheduleDefinition.redact(value));
        }
        String scheme = value.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
        if (!scheme.startsWith("postgres")) {
            throw new ScheduleException("DSN 协议不支持：" + scheme + "（当前仅支持 postgresql）");
        }
        String rest = value.substring(schemeEnd + 3);
        String credentials = null;
        int at = rest.indexOf('@');
        if (at >= 0) {
            credentials = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }
        String username = null;
        String password = null;
        if (credentials != null && !credentials.isBlank()) {
            int colon = credentials.indexOf(':');
            if (colon >= 0) {
                username = credentials.substring(0, colon);
                password = credentials.substring(colon + 1);
            } else {
                username = credentials;
            }
        }
        return new JdbcTarget("jdbc:postgresql://" + rest,
                blankToNull(username), blankToNull(password));
    }

    private static JdbcTarget parseJdbc(String value) {
        int queryStart = value.indexOf('?');
        if (queryStart < 0) {
            return new JdbcTarget(value, null, null);
        }
        String base = value.substring(0, queryStart);
        String query = value.substring(queryStart + 1);
        Map<String, String> kept = new LinkedHashMap<>();
        String username = null;
        String password = null;
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String raw = eq < 0 ? "" : pair.substring(eq + 1);
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            if ("user".equals(key)) {
                username = decoded;
            } else if ("password".equals(key)) {
                password = decoded;
            } else {
                kept.put(key, raw);
            }
        }
        if (kept.isEmpty()) {
            return new JdbcTarget(base, blankToNull(username), blankToNull(password));
        }
        StringBuilder rebuilt = new StringBuilder(base).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : kept.entrySet()) {
            if (!first) {
                rebuilt.append('&');
            }
            rebuilt.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return new JdbcTarget(rebuilt.toString(), blankToNull(username), blankToNull(password));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
