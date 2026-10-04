package com.datagovernance.quality;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import com.datagovernance.ingestion.schedule.CronUtil;
import com.datagovernance.ingestion.schedule.JdbcTarget;
import com.datagovernance.ingestion.schedule.ScheduleDefinition;
import com.datagovernance.ingestion.schedule.ScheduleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 质量规则的注册与执行（docs/09 §9.4）。
 *
 * <p>几个刻意的选择：
 * <ol>
 *   <li><b>规则定义是实体 + {@code ruleSpec} aspect</b>：于是天然拥有 Owner、标签、版本历史，
 *       并能进入检索与血缘。调度状态（下次执行时间等）放在 {@code quality_rule_state}，
 *       不把运行态混进元数据真相源。</li>
 *   <li><b>规则推到源系统执行</b>：平台不搬数据，编译出的 SQL 直接在源库上跑。</li>
 *   <li><b>"没跑成"必须是独立状态</b>：窗口内没有基线（如行数波动的首次执行）记为
 *       {@code SKIPPED}。若把它算成 PASS，规则会在悄悄失效的同时让面板保持绿色。</li>
 *   <li><b>编译产物入档</b>：{@code rule_run.compiled_sql} 存下来，
 *       否则"规则失败了"无法复盘到底是数据变了还是规则写错了。</li>
 * </ol>
 */
@Service
public class RuleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutionService.class);

    private static final String STATUS_PASS = "PASS";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;

    public RuleExecutionService(MetadataService metadata, JdbcTemplate jdbc) {
        this.metadata = metadata;
        this.jdbc = jdbc;
    }

    /** 按 ruleId 生成规则 URN（可读、稳定）。 */
    public static String ruleUrn(String namespace, String ruleId) {
        return UrnUtils.build("QualityRule", namespace, UrnUtils.sanitizeSegment(ruleId));
    }

    // ------------------------------------------------------------- 注册规则

    /**
     * 注册（或更新）一批规则：写入实体、ruleSpec aspect、appliesTo 边，并登记调度状态。
     *
     * @param connection DSN（支持 {@code env:VAR}）；单条规则可以用 {@code engineHints.connection} 覆盖
     */
    public Map<String, Object> register(List<QualityRule> rules, String namespace, String dsn,
                                        String cron, String timezone, String actor) {
        List<Map<String, Object>> registered = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();

        for (QualityRule rule : rules) {
            try {
                List<String> errors = rule.validate();
                if (!errors.isEmpty()) {
                    throw new QualityException("规则不合法：" + String.join("；", errors));
                }
                String urn = ruleUrn(namespace, rule.ruleId());
                // 注册即编译一次：编译不过的规则不该落库（否则会在调度时才炸）
                RuleCompiler.compile(rule, RuleCompiler.qualifiedTable(rule.datasetUrn()));

                metadata.ensureEntity(urn, "QualityRule", rule.ruleId(), null);
                metadata.upsertAspect(urn, "ruleSpec", rule.toAspect(), "MANUAL", null, null);
                metadata.upsertEdge(urn, rule.datasetUrn(), "appliesTo", "manual", 1.0,
                        null, null, null, "VALUE", null, null, null);

                String ruleDsn = dsn;
                Object connectionHint = rule.engineHints().get("connection");
                if (connectionHint instanceof Map<?, ?> hint && hint.get("dsn") != null) {
                    ruleDsn = String.valueOf(hint.get("dsn"));
                }
                if (ruleDsn == null || ruleDsn.isBlank()) {
                    throw new QualityException("规则缺少执行目标连接（dsn）");
                }
                String ruleCron = cron;
                Object scheduleHint = rule.schedule().get("cron");
                if (scheduleHint != null) {
                    ruleCron = String.valueOf(scheduleHint);
                }
                String ruleTimezone = rule.schedule().get("timezone") == null
                        ? timezone : String.valueOf(rule.schedule().get("timezone"));
                upsertState(urn, ruleDsn, ruleCron == null ? "0 3 * * *" : ruleCron,
                        ruleTimezone == null ? "Asia/Shanghai" : ruleTimezone);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("urn", urn);
                item.put("ruleId", rule.ruleId());
                item.put("dataset", rule.datasetUrn());
                item.put("metric", rule.metric());
                item.put("operator", rule.operator());
                item.put("expected", RuleCompiler.describeExpected(rule));
                item.put("severity", rule.severity());
                item.put("sourceFrontend", rule.sourceFrontend());
                item.put("dsn", ScheduleDefinition.redact(ruleDsn));
                registered.add(item);
            } catch (QualityException e) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("ruleId", rule.ruleId());
                failure.put("error", e.getMessage());
                rejected.add(failure);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registered", registered);
        payload.put("rejected", rejected);
        payload.put("note", "被拒绝的规则不会落库：注册时即编译一次，编译不过的规则不该等到调度时才暴露");
        return payload;
    }

    private void upsertState(String urn, String dsn, String cron, String timezone) {
        CronUtil.validate(cron);
        Instant next = CronUtil.next(cron, timezone, Instant.now());
        jdbc.update("""
                INSERT INTO quality_rule_state (rule_urn, dsn, cron, timezone, enabled, next_run_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, ?, now())
                ON CONFLICT (rule_urn) DO UPDATE
                    SET dsn = EXCLUDED.dsn, cron = EXCLUDED.cron, timezone = EXCLUDED.timezone,
                        next_run_at = EXCLUDED.next_run_at, updated_at = now()
                """, urn, dsn, cron, timezone,
                next == null ? null : java.sql.Timestamp.from(next));
    }

    // --------------------------------------------------------------- 查询

    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.rule_urn, s.dsn, s.cron, s.timezone, s.enabled, s.next_run_at,
                       s.last_run_at, s.last_status, s.last_run_id,
                       a.data AS spec, e.display_name
                  FROM quality_rule_state s
                  LEFT JOIN aspect a ON a.urn = s.rule_urn AND a.aspect_type = 'ruleSpec'
                  LEFT JOIN entity e ON e.urn = s.rule_urn
                 ORDER BY s.rule_urn
                """);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> spec = parseJson(str(row.get("spec")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("urn", row.get("rule_urn"));
            item.put("ruleId", spec.get("ruleId"));
            item.put("metric", spec.get("metric"));
            item.put("operator", spec.get("operator"));
            item.put("threshold", spec.get("threshold"));
            item.put("column", spec.get("column"));
            item.put("severity", spec.get("severity"));
            item.put("dimension", spec.get("dimension"));
            item.put("onFail", spec.get("onFail"));
            item.put("sourceFrontend", spec.get("sourceFrontend"));
            item.put("engineHints", spec.get("engineHints"));
            item.put("dsn", ScheduleDefinition.redact(str(row.get("dsn"))));
            item.put("cron", row.get("cron"));
            item.put("timezone", row.get("timezone"));
            item.put("enabled", row.get("enabled"));
            item.put("nextRunAt", row.get("next_run_at"));
            item.put("lastRunAt", row.get("last_run_at"));
            item.put("lastStatus", row.get("last_status"));
            item.put("lastRunId", row.get("last_run_id"));
            item.put("dataset", datasetOf(str(row.get("rule_urn"))));
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> listRuns(String ruleUrn, int limit) {
        if (ruleUrn == null || ruleUrn.isBlank()) {
            return jdbc.queryForList("""
                    SELECT run_id, rule_urn, status, observed, expected, metric, severity, duration_ms,
                           error, started_at, executed_by
                      FROM rule_run ORDER BY started_at DESC LIMIT ?
                    """, Math.min(Math.max(limit, 1), 200));
        }
        return jdbc.queryForList("""
                SELECT run_id, rule_urn, status, observed, expected, metric, severity, duration_ms,
                       error, started_at, executed_by
                  FROM rule_run WHERE rule_urn = ? ORDER BY started_at DESC LIMIT ?
                """, ruleUrn, Math.min(Math.max(limit, 1), 200));
    }

    /** 规则概览（给界面用）：按状态计数 + 最近失败。 */
    public Map<String, Object> overview() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rules", jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_rule_state", Integer.class));
        payload.put("enabledRules", jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_rule_state WHERE enabled", Integer.class));
        payload.put("byLastStatus", jdbc.queryForList("""
                SELECT COALESCE(last_status, 'NEVER_RUN') AS status, COUNT(*) AS count
                  FROM quality_rule_state GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("recentRuns", jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM rule_run
                 WHERE started_at > now() - interval '7 days' GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("recentFailures", jdbc.queryForList("""
                SELECT run_id, rule_urn, observed, expected, error, started_at
                  FROM rule_run WHERE status IN ('FAIL', 'ERROR') ORDER BY started_at DESC LIMIT 10
                """));
        payload.put("note", "NEVER_RUN 是独立状态：从未执行过的规则不应被误读为健康");
        return payload;
    }

    // --------------------------------------------------------------- 执行

    /** 立即执行一条规则（手动触发）。 */
    public Map<String, Object> runNow(String ruleUrn, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dsn, cron, timezone FROM quality_rule_state WHERE rule_urn = ?", ruleUrn);
        if (rows.isEmpty()) {
            throw new QualityException("规则不存在或未注册调度状态：" + ruleUrn);
        }
        Map<String, Object> state = rows.get(0);
        Map<String, Object> result = execute(ruleUrn, str(state.get("dsn")), actor);
        Instant next = CronUtil.next(str(state.get("cron")), str(state.get("timezone")), Instant.now());
        recordState(ruleUrn, str(result.get("status")), str(result.get("runId")), next);
        return result;
    }

    /** 执行一条规则（不改变调度状态；调度器会另行记录）。 */
    public Map<String, Object> execute(String ruleUrn, String dsn, String actor) {
        Map<String, Object> spec = metadata.getAspect(ruleUrn, "ruleSpec")
                .orElseThrow(() -> new QualityException("规则缺少 ruleSpec aspect：" + ruleUrn));
        String datasetUrn = datasetOf(ruleUrn);
        QualityRule rule = QualityRule.fromAspect(datasetUrn, spec);

        String runId = "rule-" + UUID.randomUUID().toString().substring(0, 12);
        long started = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO rule_run (run_id, rule_urn, dataset_urn, status, metric, severity, dimension,
                                      window_start, executed_by, started_at)
                VALUES (?, ?, ?, 'ERROR', ?, ?, ?, date_trunc('day', now()), ?, now())
                """, runId, ruleUrn, datasetUrn, rule.metric(), rule.severity(), rule.dimension(), actor);

        String resolvedDsn;
        try {
            resolvedDsn = resolveDsn(dsn);
        } catch (ScheduleException e) {
            return finish(runId, ruleUrn, STATUS_ERROR, null, null, null, e.getMessage(), started, null);
        }

        RuleCompiler.CompiledQuery compiled;
        try {
            compiled = RuleCompiler.compile(rule, RuleCompiler.qualifiedTable(datasetUrn));
        } catch (QualityException e) {
            return finish(runId, ruleUrn, STATUS_ERROR, null, null, null,
                    "编译失败：" + e.getMessage(), started, null);
        }

        JdbcTarget target;
        try {
            target = JdbcTarget.parse(resolvedDsn);
        } catch (ScheduleException e) {
            return finish(runId, ruleUrn, STATUS_ERROR, null, null, compiled.sql(),
                    "连接串非法：" + e.getMessage(), started, null);
        }

        Double baseline = null;
        Double expected = rule.threshold();
        if (compiled.needsBaseline()) {
            baseline = baselineRowCount(ruleUrn, rule);
            expected = RuleCompiler.expectedFromBaseline(rule, baseline);
            if (expected == null) {
                // 没有基线 → SKIPPED（既不是通过也不是失败）。
                // 记成 PASS 会让"规则从未真正生效"这件事完全不可见
                return finish(runId, ruleUrn, STATUS_SKIPPED, null,
                        "窗口内没有基线（首次执行或历史不足）", compiled.sql(),
                        "窗口 " + rule.engineHints().get("baselineWindow") + " 内没有可用的 row_count 基线，"
                                + "无法判定相对变化", started, baseline);
            }
        }

        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.username(), target.password())) {
            connection.setReadOnly(true);
            double observed = queryScalar(connection, compiled.sql(), rule);
            boolean passed = RuleCompiler.evaluate(observed, withThreshold(rule, expected));
            String status = passed ? STATUS_PASS : STATUS_FAIL;
            return finish(runId, ruleUrn, status, observed, compiled.expected(), compiled.sql(),
                    passed ? null : "观察值 " + observed + " 不满足期望 " + compiled.expected(),
                    started, baseline);
        } catch (SQLException e) {
            return finish(runId, ruleUrn, STATUS_ERROR, null, compiled.expected(), compiled.sql(),
                    "执行失败：" + e.getMessage(), started, baseline);
        }
    }

    /** baseline 存在时的期望值由基线决定，因此判定要用临时替换 threshold 的规则副本。 */
    private static QualityRule withThreshold(QualityRule rule, Double expected) {
        return expected == null || expected.equals(rule.threshold()) ? rule
                : new QualityRule(rule.ruleId(), rule.datasetUrn(), rule.metric(), rule.operator(),
                        expected, rule.thresholdMax(), rule.column(), rule.columns(), rule.window(),
                        rule.percentile(), rule.pattern(), rule.acceptedValues(), rule.customSql(),
                        rule.expected(), rule.severity(), rule.dimension(), rule.onFail(),
                        rule.schedule(), rule.engineHints(), rule.sourceFrontend());
    }

    /** 窗口基线：取最近一次成功执行的 row_count 观察值（没有就返回 null）。 */
    private Double baselineRowCount(String ruleUrn, QualityRule rule) {
        Object window = rule.engineHints().get("baselineWindow");
        String windowLiteral = window == null ? "7d" : String.valueOf(window);
        int days = 7;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)d$").matcher(windowLiteral);
        if (matcher.matches()) {
            days = Integer.parseInt(matcher.group(1));
        }
        List<Double> rows = jdbc.query("""
                SELECT observed FROM rule_run
                 WHERE rule_urn = ? AND status = 'PASS' AND observed IS NOT NULL
                   AND started_at > now() - (? || ' days')::interval
                 ORDER BY started_at DESC LIMIT 1
                """, (rs, index) -> rs.getDouble(1), ruleUrn, String.valueOf(days));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> finish(String runId, String ruleUrn, String status, Double observed,
                                       String expected, String sql, String error, long started,
                                       Double baseline) {
        jdbc.update("""
                UPDATE rule_run
                   SET status = ?, observed = ?, expected = ?, compiled_sql = ?, error = ?,
                       duration_ms = ?, finished_at = now()
                 WHERE run_id = ?
                """, status, observed, expected, sql, error,
                (int) (System.currentTimeMillis() - started), runId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("rule", ruleUrn);
        payload.put("status", status);
        payload.put("observed", observed);
        payload.put("expected", expected);
        payload.put("compiledSql", sql);
        payload.put("error", error);
        payload.put("baseline", baseline);
        payload.put("durationMs", System.currentTimeMillis() - started);
        if (STATUS_SKIPPED.equals(status)) {
            payload.put("note", "SKIPPED：没能判定（不是通过）。这种情况必须显式暴露，"
                    + "否则规则会悄悄失效而面板保持绿色");
        }
        return payload;
    }

    private void recordState(String ruleUrn, String status, String runId, Instant next) {
        jdbc.update("""
                UPDATE quality_rule_state
                   SET last_run_at = now(), last_status = ?, last_run_id = ?,
                       next_run_at = COALESCE(?, next_run_at), updated_at = now()
                 WHERE rule_urn = ?
                """, status, runId, next == null ? null : java.sql.Timestamp.from(next), ruleUrn);
    }

    /** 调度器调用：执行到期的规则。 */
    public Map<String, Object> runDue(String actor) {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT rule_urn, dsn, cron, timezone FROM quality_rule_state
                 WHERE enabled = TRUE AND (next_run_at IS NULL OR next_run_at <= now())
                 ORDER BY rule_urn
                """);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : due) {
            String ruleUrn = str(row.get("rule_urn"));
            Map<String, Object> result;
            try {
                result = execute(ruleUrn, str(row.get("dsn")), actor);
            } catch (RuntimeException e) {
                // 单条规则失败不影响其它规则（否则一条坏规则会让质量体系整体停摆）
                log.warn("规则 {} 执行异常：{}", ruleUrn, e.getMessage());
                result = Map.of("rule", ruleUrn, "status", STATUS_ERROR, "error", e.getMessage());
            }
            Instant next = CronUtil.next(str(row.get("cron")), str(row.get("timezone")), Instant.now());
            recordState(ruleUrn, str(result.get("status")), str(result.get("runId")), next);
            results.add(result);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("due", due.size());
        payload.put("results", results);
        return payload;
    }

    /** 初始化没有 next_run_at 的规则。 */
    public int initializeMissingNextRun() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT rule_urn, cron, timezone FROM quality_rule_state
                 WHERE enabled = TRUE AND next_run_at IS NULL
                """);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Instant next = CronUtil.next(str(row.get("cron")), str(row.get("timezone")), Instant.now());
            if (next != null) {
                jdbc.update("UPDATE quality_rule_state SET next_run_at = ? WHERE rule_urn = ?",
                        java.sql.Timestamp.from(next), row.get("rule_urn"));
                updated++;
            }
        }
        return updated;
    }

    // --------------------------------------------------------------- 工具

    /** 解析 DSN（支持 env: 引用；fail loud）。 */
    public static String resolveDsn(String dsn) {
        if (dsn == null || dsn.isBlank()) {
            throw new ScheduleException("规则执行目标连接为空");
        }
        if (dsn.startsWith("env:")) {
            String variable = dsn.substring(4).trim();
            String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                throw new ScheduleException("环境变量 " + variable + " 未设置（DSN 以 env: 引用）");
            }
            return value;
        }
        if (dsn.startsWith("secret:")) {
            throw new ScheduleException("secret: 引用需要接入 Vault/KMS（未实现）；请改用 env:VAR");
        }
        return dsn;
    }

    private double queryScalar(Connection connection, String sql, QualityRule rule) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new QualityException("规则 SQL 没有返回行（应返回单个数值）：" + sql);
                }
                java.math.BigDecimal value = rs.getBigDecimal(1);
                return value == null ? 0.0 : value.doubleValue();
            }
        } catch (SQLException e) {
            throw new QualityException("规则 SQL 执行失败：" + e.getMessage() + "（SQL: " + sql + "）", e);
        }
    }

    private String datasetOf(String ruleUrn) {
        List<String> rows = jdbc.queryForList("""
                SELECT to_urn FROM edge WHERE from_urn = ? AND edge_type = 'appliesTo' LIMIT 1
                """, String.class, ruleUrn);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
