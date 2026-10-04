package com.datagovernance.ingestion.schedule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.ingestion.GuardConfig;

/**
 * 一条采集调度定义（docs/09 §9.1）。
 *
 * <p>凭证纪律（来自设计文档的硬要求）：<b>DSN 不落明文</b>。
 * 支持 {@code env:VAR_NAME} 引用环境变量；{@code secret:} 形式**显式拒绝**并提示改用
 * {@code env:} 或接 Vault/KMS —— 因为"看起来支持 Vault，实际上没读"比直接报错更危险。
 */
public record ScheduleDefinition(
        String name,
        String source,
        String dsn,
        String namespace,
        String database,
        List<String> schemas,
        List<String> tables,
        String cron,
        boolean enabled,
        Map<String, Object> guard,
        String timezone) {

    public static final String DEFAULT_CRON = "0 3 * * *";
    public static final List<String> SUPPORTED_SOURCES = List.of("postgres");

    public ScheduleDefinition {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        tables = tables == null ? List.of() : List.copyOf(tables);
        guard = guard == null ? Map.of() : Map.copyOf(guard);
    }

    @SuppressWarnings("unchecked")
    public static ScheduleDefinition fromMap(Map<String, Object> raw) {
        ScheduleDefinition definition = new ScheduleDefinition(
                str(raw.get("name")),
                str(raw.get("source")),
                str(raw.get("dsn")),
                raw.get("namespace") == null ? "prod" : str(raw.get("namespace")),
                str(raw.get("database")),
                stringList(raw.get("schemas")),
                stringList(raw.get("tables")),
                raw.get("cron") == null ? DEFAULT_CRON : str(raw.get("cron")),
                !Boolean.FALSE.equals(raw.get("enabled")),
                raw.get("guard") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                raw.get("timezone") == null ? "Asia/Shanghai" : str(raw.get("timezone")));
        definition.validate();
        return definition;
    }

    public void validate() {
        if (name == null || name.isBlank()) {
            throw new ScheduleException("调度缺少 name");
        }
        if (source == null || !SUPPORTED_SOURCES.contains(source)) {
            throw new ScheduleException("调度 " + name + "：不支持的 source=" + source
                    + "（当前 Java 控制面已实现的连接器：" + SUPPORTED_SOURCES + "）");
        }
        if (dsn == null || dsn.isBlank()) {
            throw new ScheduleException("调度 " + name + "：缺少 dsn");
        }
        if (dsn.startsWith("secret:")) {
            throw new ScheduleException("调度 " + name + "：secret: 引用需要接入 Vault/KMS（未实现）；"
                    + "请改用 env:VAR_NAME 形式");
        }
        CronUtil.validate(cron);
    }

    /**
     * 解析 DSN：支持 {@code env:VAR} 间接引用。
     *
     * @throws ScheduleException 引用的环境变量未设置（**不**降级为默认值 —— 静默用错库比报错严重）
     */
    public String resolvedDsn() {
        if (dsn == null) {
            throw new ScheduleException("调度 " + name + "：缺少 dsn");
        }
        if (dsn.startsWith("env:")) {
            String variable = dsn.substring(4).trim();
            String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                throw new ScheduleException("调度 " + name + "：环境变量 " + variable
                        + " 未设置（DSN 以 env: 引用）");
            }
            return value;
        }
        return dsn;
    }

    public GuardConfig guardConfig() {
        return new GuardConfig(
                intOf(guard.get("maxDeletions"), 200),
                doubleOf(guard.get("maxDeleteRatio"), 0.30),
                doubleOf(guard.get("minRetentionRatio"), 0.70),
                intOf(guard.get("ratioMinBaseline"), 10));
    }

    /** 对外输出（DSN 遮蔽口令）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("source", source);
        payload.put("dsn", redact(dsn));
        payload.put("namespace", namespace);
        payload.put("database", database);
        payload.put("schemas", schemas);
        payload.put("tables", tables);
        payload.put("cron", cron);
        payload.put("cronNormalized", CronUtil.normalize(cron));
        payload.put("enabled", enabled);
        payload.put("guard", guard);
        payload.put("timezone", timezone);
        return payload;
    }

    /** 遮蔽 DSN 中的口令，避免它出现在接口响应与日志里。 */
    public static String redact(String dsn) {
        if (dsn == null) {
            return null;
        }
        if (dsn.startsWith("env:") || dsn.startsWith("secret:")) {
            return dsn; // 本来就是引用，不含明文
        }
        int schemeEnd = dsn.indexOf("://");
        if (schemeEnd < 0) {
            return dsn;
        }
        String scheme = dsn.substring(0, schemeEnd);
        String rest = dsn.substring(schemeEnd + 3);
        int at = rest.indexOf('@');
        if (at < 0) {
            return dsn;
        }
        String credentials = rest.substring(0, at);
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            return dsn;
        }
        return scheme + "://" + credentials.substring(0, colon) + ":***@" + rest.substring(at + 1);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
