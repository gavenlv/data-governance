package com.datagovernance.ingestion.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.datagovernance.ingestion.CollectionRun;
import com.datagovernance.ingestion.CollectionService;
import com.datagovernance.ingestion.GuardConfig;
import com.datagovernance.ingestion.connectors.PostgresSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 采集调度的定义、加载与执行（docs/09 §9.1）。
 *
 * <p>三条关键设计，都是"实现陷阱"清单里的落地：
 * <ol>
 *   <li><b>互斥用 PG advisory lock，不用 Redis 分布式锁</b>：Redlock 缺 fencing token，
 *       在 GC 停顿 / 时钟漂移下无法真正保证互斥；而 {@code pg_try_advisory_lock}
 *       随连接释放，进程崩溃不会留下死锁。锁键与 Python 参考实现算法一致，
 *       因此两种实现同时跑也互斥。</li>
 *   <li><b>凭证不落明文</b>：支持 {@code env:VAR}；{@code secret:} 显式拒绝。</li>
 *   <li><b>每次执行都写 {@code collect_run}</b>：调度是否真的在跑**可被观测** ——
 *       避免"目录悄悄停止更新"这一隐性失效（`/api/v1/collect/health` 会暴露陈旧度）。</li>
 * </ol>
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CollectionService collection;

    public ScheduleService(JdbcTemplate jdbc, DataSource dataSource, CollectionService collection) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.collection = collection;
    }

    // ---------------------------------------------------------------- 定义

    /** 应用一批调度定义（来自 YAML 或接口）；返回每条的处理结果。 */
    public Map<String, Object> apply(List<Map<String, Object>> documents) {
        List<Map<String, Object>> applied = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();

        for (Map<String, Object> raw : documents) {
            try {
                ScheduleDefinition definition = ScheduleDefinition.fromMap(raw);
                applied.add(upsert(definition));
            } catch (ScheduleException e) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("name", raw.get("name"));
                failure.put("error", e.getMessage());
                rejected.add(failure);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applied", applied);
        payload.put("rejected", rejected);
        payload.put("note", "被拒绝的调度不会落库（宁可少调度，也不要一个静默跑错的调度）");
        return payload;
    }

    public Map<String, Object> upsert(ScheduleDefinition definition) {
        definition.validate();
        Instant now = Instant.now();
        Instant next = definition.enabled()
                ? CronUtil.next(definition.cron(), definition.timezone(), now) : null;
        final java.sql.Timestamp nextTimestamp = next == null ? null : java.sql.Timestamp.from(next);
        final String guardJson = toJson(definition.guard());

        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO collect_schedule
                        (name, source, dsn, namespace, database_name, schemas, tables,
                         cron, enabled, guard_config, timezone, next_run_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, now())
                    ON CONFLICT (name) DO UPDATE
                        SET source = EXCLUDED.source,
                            dsn = EXCLUDED.dsn,
                            namespace = EXCLUDED.namespace,
                            database_name = EXCLUDED.database_name,
                            schemas = EXCLUDED.schemas,
                            tables = EXCLUDED.tables,
                            cron = EXCLUDED.cron,
                            enabled = EXCLUDED.enabled,
                            guard_config = EXCLUDED.guard_config,
                            timezone = EXCLUDED.timezone,
                            next_run_at = EXCLUDED.next_run_at,
                            updated_at = now()
                    """);
            ps.setString(1, definition.name());
            ps.setString(2, definition.source());
            ps.setString(3, definition.dsn());
            ps.setString(4, definition.namespace());
            ps.setString(5, definition.database());
            ps.setArray(6, textArray(connection, definition.schemas()));
            ps.setArray(7, textArray(connection, definition.tables()));
            ps.setString(8, definition.cron());
            ps.setBoolean(9, definition.enabled());
            ps.setString(10, guardJson);
            ps.setString(11, definition.timezone());
            ps.setTimestamp(12, nextTimestamp);
            return ps;
        });

        Map<String, Object> payload = new LinkedHashMap<>(definition.toMap());
        payload.put("nextRunAt", next);
        payload.put("dsnStored", definition.dsn().startsWith("env:") ? "env-reference" : "inline");
        return payload;
    }

    /** 列出调度（DSN 遮蔽）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT name, source, dsn, namespace, database_name, schemas, tables,
                       cron, enabled, guard_config, timezone,
                       last_run_id, last_run_at, last_status, next_run_at
                  FROM collect_schedule ORDER BY name
                """);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("name"));
            item.put("source", row.get("source"));
            item.put("dsn", ScheduleDefinition.redact(str(row.get("dsn"))));
            item.put("namespace", row.get("namespace"));
            item.put("database", row.get("database_name"));
            item.put("schemas", toList(row.get("schemas")));
            item.put("tables", toList(row.get("tables")));
            item.put("cron", row.get("cron"));
            item.put("enabled", row.get("enabled"));
            item.put("guard", parseJson(str(row.get("guard_config"))));
            item.put("timezone", row.get("timezone"));
            item.put("lastRunId", row.get("last_run_id"));
            item.put("lastRunAt", row.get("last_run_at"));
            item.put("lastStatus", row.get("last_status"));
            item.put("nextRunAt", row.get("next_run_at"));
            out.add(item);
        }
        return out;
    }

    /** 读取一条调度定义（用于执行）。 */
    public ScheduleDefinition find(String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT name, source, dsn, namespace, database_name, schemas, tables,
                       cron, enabled, guard_config, timezone
                  FROM collect_schedule WHERE name = ?
                """, name);
        if (rows.isEmpty()) {
            throw new ScheduleException("调度不存在：" + name);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", row.get("name"));
        raw.put("source", row.get("source"));
        raw.put("dsn", row.get("dsn"));
        raw.put("namespace", row.get("namespace"));
        raw.put("database", row.get("database_name"));
        raw.put("schemas", toList(row.get("schemas")));
        raw.put("tables", toList(row.get("tables")));
        raw.put("cron", row.get("cron"));
        raw.put("enabled", row.get("enabled"));
        raw.put("guard", parseJson(str(row.get("guard_config"))));
        raw.put("timezone", row.get("timezone"));
        return ScheduleDefinition.fromMap(raw);
    }

    public boolean delete(String name) {
        return jdbc.update("DELETE FROM collect_schedule WHERE name = ?", name) > 0;
    }

    // ---------------------------------------------------------------- 执行

    /** 到期待执行的调度（含从未计算过 next_run_at 的）。 */
    public List<String> due(Instant now) {
        return jdbc.queryForList("""
                SELECT name FROM collect_schedule
                 WHERE enabled = TRUE AND (next_run_at IS NULL OR next_run_at <= ?)
                 ORDER BY name
                """, String.class, java.sql.Timestamp.from(now));
    }

    /**
     * 执行一次调度。
     *
     * <p>返回体里带 {@code skipped} 与原因，而不是静默返回成功：
     * "没跑"和"跑了但没变化"必须能区分（docs/09 §9.2 的反静默失败原则）。
     */
    public Map<String, Object> run(String name, boolean acceptDeletions, String actor) {
        ScheduleDefinition definition = find(name);
        long lockKey = lockKeyFor(name);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schedule", name);
        payload.put("cron", definition.cron());
        payload.put("namespace", definition.namespace());

        // 互斥：锁必须与解锁在同一连接上（PG advisory lock 是会话级的）
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection, lockKey)) {
                payload.put("skipped", true);
                payload.put("reason", "另一个实例正在执行同一调度（pg_try_advisory_lock 未获取到）");
                return payload;
            }
            try {
                return execute(definition, acceptDeletions, actor, payload);
            } finally {
                unlock(lockConnection, lockKey);
            }
        } catch (SQLException e) {
            throw new ScheduleException("获取调度互斥锁失败：" + e.getMessage());
        }
    }

    private Map<String, Object> execute(ScheduleDefinition definition, boolean acceptDeletions,
                                        String actor, Map<String, Object> payload) {
        Instant started = Instant.now();
        try {
            JdbcTarget target = JdbcTarget.parse(definition.resolvedDsn());
            PostgresSource source = new PostgresSource(target.jdbcUrl(), target.username(),
                    target.password(), definition.schemas(), definition.tables());

            GuardConfig guard = definition.guardConfig();
            CollectionRun run = collection.collect(source, definition.namespace(), guard, true,
                    acceptDeletions);

            Instant next = CronUtil.next(definition.cron(), definition.timezone(), Instant.now());
            recordRun(definition.name(), run.runId(), run.status(), next);

            payload.put("skipped", false);
            payload.put("run", run.asMap());
            payload.put("guarded", CollectionRun.BLOCKED.equals(run.status()));
            payload.put("nextRunAt", next);
            payload.put("durationMillis", java.time.Duration.between(started, Instant.now()).toMillis());
            return payload;
        } catch (RuntimeException e) {
            // 失败也要留痕：否则"调度配错了"会表现为"目录就是没更新"，无法定位
            Instant next = CronUtil.next(definition.cron(), definition.timezone(), Instant.now());
            recordRun(definition.name(), null, CollectionRun.FAILED, next);
            log.warn("调度 {} 执行失败：{}", definition.name(), e.getMessage());
            payload.put("skipped", false);
            payload.put("failed", true);
            payload.put("error", e.getMessage());
            payload.put("nextRunAt", next);
            return payload;
        }
    }

    public void recordRun(String name, String runId, String status, Instant nextRunAt) {
        jdbc.update("""
                UPDATE collect_schedule
                   SET last_run_id = ?, last_run_at = now(), last_status = ?,
                       next_run_at = COALESCE(?, next_run_at), updated_at = now()
                 WHERE name = ?
                """, runId, status,
                nextRunAt == null ? null : java.sql.Timestamp.from(nextRunAt), name);
    }

    /** 已启用但从未计算过下次执行时间的调度，补算一次。 */
    public int initializeMissingNextRun() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT name, cron, timezone FROM collect_schedule
                 WHERE enabled = TRUE AND next_run_at IS NULL
                """);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Instant next = CronUtil.next(str(row.get("cron")), str(row.get("timezone")), Instant.now());
            if (next != null) {
                jdbc.update("UPDATE collect_schedule SET next_run_at = ? WHERE name = ?",
                        java.sql.Timestamp.from(next), row.get("name"));
                updated++;
            }
        }
        return updated;
    }

    /** 与 Python 参考实现同算法的锁键：sha256("dg-collect:"+name) 前 8 字节（大端有符号）。 */
    public static long lockKeyFor(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("dg-collect:" + name).getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean tryLock(Connection connection, long key) throws SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, long key) throws SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    // ------------------------------------------------------------- 小工具

    private static java.sql.Array textArray(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> toList(Object value) {
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object array = sqlArray.getArray();
                if (array instanceof Object[] objects) {
                    List<String> out = new ArrayList<>(objects.length);
                    for (Object item : objects) {
                        out.add(item == null ? null : String.valueOf(item));
                    }
                    return out;
                }
            } catch (SQLException e) {
                return List.of();
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? null : String.valueOf(item)).toList();
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
