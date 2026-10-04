package com.datagovernance.api.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import com.datagovernance.quality.ProfilingService;
import com.datagovernance.quality.QualityException;
import com.datagovernance.quality.QualityRule;
import com.datagovernance.quality.RuleCompiler;
import com.datagovernance.quality.RuleExecutionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量：剖析、规则、执行结果（docs/09 §9.4）。
 *
 * <p>授权：读取需 {@code asset:read}；注册规则/触发剖析需 {@code governance:write}
 * （与 Python 参考实现一致的角色划分：数据管家能治理，只读用户只能看）。
 */
@RestController
@RequestMapping("/api/v1/quality")
public class QualityController {

    private final ProfilingService profiling;
    private final RuleExecutionService rules;
    private final JdbcTemplate jdbc;

    public QualityController(ProfilingService profiling, RuleExecutionService rules, JdbcTemplate jdbc) {
        this.profiling = profiling;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 剖析

    /** 触发一次剖析（把统计 SQL 推到源库执行，平台不搬数据）。 */
    @PostMapping("/profile")
    public Map<String, Object> profile(@RequestBody ProfileRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return profiling.profile(new ProfilingService.ProfileRequest(
                request.jdbcUrl(), request.username(), request.password(),
                request.datasetUrns(), request.samplePercent(), request.includeTopK(),
                subject.id()));
    }

    /** 某数据集的最新剖析结果（按列 + 精度标注）。 */
    @GetMapping("/profiles/{urn}")
    public Map<String, Object> profiles(@PathVariable String urn,
                                        @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");

        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT DISTINCT ON (column_name, metric)
                       column_name, metric, value_num, value_json, precision, precision_source,
                       sampling_method, sample_ratio, window_start
                  FROM profile_metric
                 WHERE dataset_urn = ?
                 ORDER BY column_name, metric, window_start DESC
                """, urn);

        Map<String, List<Map<String, Object>>> byColumn = new LinkedHashMap<>();
        for (Map<String, Object> row : latest) {
            String column = row.get("column_name") == null ? "(table)" : String.valueOf(row.get("column_name"));
            byColumn.computeIfAbsent(column, key -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> trend = jdbc.queryForList("""
                SELECT metric, column_name, window_start, value_num, precision
                  FROM profile_metric
                 WHERE dataset_urn = ? AND metric IN ('row_count', 'null_ratio')
                 ORDER BY window_start DESC LIMIT ?
                """, urn, Math.min(Math.max(limit, 1), 200));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", urn);
        payload.put("columns", byColumn);
        payload.put("columnCount", byColumn.size());
        payload.put("trend", trend);
        payload.put("precisionLegend", Map.of(
                "EXACT", "profiling 实算",
                "ESTIMATED", "来自源系统估算或采样（不应作为告警阈值依据）"));
        return payload;
    }

    // ------------------------------------------------------------------ 规则

    /** 注册规则：支持三种前端语法（YAML / SQL 断言 / dbt tests）。 */
    @PostMapping("/rules")
    public Map<String, Object> registerRules(@RequestBody RuleDocument document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");

        List<QualityRule> compiled = compile(document);
        return rules.register(compiled, document.namespace() == null ? "prod" : document.namespace(),
                document.dsn(), document.cron(), document.timezone(), subject.id());
    }

    /** 只编译不落库：让使用者先看规则会被编译成什么 SQL。 */
    @PostMapping("/rules/compile")
    public Map<String, Object> compileOnly(@RequestBody RuleDocument document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");

        List<QualityRule> compiled = compile(document);
        List<Map<String, Object>> previews = new ArrayList<>();
        for (QualityRule rule : compiled) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", rule.ruleId());
            item.put("dataset", rule.datasetUrn());
            item.put("metric", rule.metric());
            item.put("operator", rule.operator());
            item.put("severity", rule.severity());
            item.put("dimension", rule.dimension());
            item.put("expected", RuleCompiler.describeExpected(rule));
            item.put("sourceFrontend", rule.sourceFrontend());
            item.put("engineHints", rule.engineHints());
            try {
                RuleCompiler.CompiledQuery query = RuleCompiler.compile(rule,
                        RuleCompiler.qualifiedTable(rule.datasetUrn()));
                item.put("sql", query.sql());
                item.put("needsBaseline", query.needsBaseline());
            } catch (RuntimeException e) {
                item.put("compileError", e.getMessage());
            }
            previews.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rules", previews);
        payload.put("count", previews.size());
        payload.put("note", "编译预览：平台把规则推到源系统执行，因此这里的 SQL 就是实际会跑的语句");
        return payload;
    }

    @GetMapping("/rules")
    public Map<String, Object> listRules() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = rules.listRules();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("rules", rows);
        return payload;
    }

    /** 立即执行一条规则（手动验证，不改变调度时间）。 */
    @PostMapping("/rules/{urn}/run")
    public Map<String, Object> runRule(@PathVariable String urn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return rules.runNow(urn, subject.id());
    }

    /** 质量概览：规则状态分布 + 最近失败（供界面顶部看板）。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return rules.overview();
    }

    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(required = false) String ruleUrn,
                                    @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = rules.listRuns(ruleUrn, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("runs", rows);
        return payload;
    }

    /** 手动触发一次到期规则检查（排障用；常驻调度由 RuleScheduler 负责）。 */
    @PostMapping("/rules/run-due")
    public Map<String, Object> runDue() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return rules.runDue(subject.id());
    }

    // ------------------------------------------------------------ 前端语法转换

    private List<QualityRule> compile(RuleDocument document) {
        String frontend = document.frontend() == null ? "yaml" : document.frontend();
        switch (frontend) {
            case "yaml" -> {
                if (document.document() == null) {
                    throw new QualityException("frontend=yaml 时必须提供 document（rule/checks 结构）");
                }
                return RuleCompiler.fromYamlDocument(document.document(), document.datasetUrn());
            }
            case "sql_assertion" -> {
                if (document.sql() == null) {
                    throw new QualityException("frontend=sql_assertion 时必须提供 sql");
                }
                if (document.ruleId() == null || document.datasetUrn() == null) {
                    throw new QualityException("SQL 断言需要 ruleId 与 datasetUrn");
                }
                return List.of(RuleCompiler.fromSqlAssertion(document.ruleId(), document.datasetUrn(),
                        document.sql(), document.expect(), document.severity(), document.dimension()));
            }
            case "dbt_test" -> {
                if (document.modelName() == null || document.tests() == null || document.datasetUrn() == null) {
                    throw new QualityException("frontend=dbt_test 需要 modelName / datasetUrn / tests");
                }
                return RuleCompiler.fromDbtTests(document.modelName(), document.datasetUrn(), document.tests());
            }
            default -> throw new QualityException("不支持的 frontend：" + frontend
                    + "（支持 yaml / sql_assertion / dbt_test）");
        }
    }

    /** 规则注册请求（三种前端共用）。 */
    public record RuleDocument(
            String frontend,
            Map<String, Object> document,
            String sql,
            Double expect,
            String modelName,
            List<Map<String, Object>> tests,
            String ruleId,
            String datasetUrn,
            String namespace,
            String dsn,
            String cron,
            String timezone,
            String severity,
            String dimension) {
    }

    /** 剖析请求。 */
    public record ProfileRequest(
            String jdbcUrl,
            String username,
            String password,
            List<String> datasetUrns,
            Integer samplePercent,
            Boolean includeTopK,
            String namespace) {
    }
}
