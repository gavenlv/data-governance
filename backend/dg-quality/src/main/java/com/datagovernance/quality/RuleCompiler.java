package com.datagovernance.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.datagovernance.core.UrnUtils;

/**
 * 规则编译器：三种前端语法 → 统一 IR → 引擎 SQL（docs/09 §9.4）。
 *
 * <p>支持的前端：
 * <ol>
 *   <li><b>YAML 声明式</b>（内置模板：非空、唯一、值域、枚举、正则、行数波动、新鲜度、自定义 SQL）</li>
 *   <li><b>SQL 断言</b>：{@code SELECT count(*) FROM t WHERE <违约条件>}，期望为 0</li>
 *   <li><b>dbt tests</b>：unique / not_null / accepted_values / relationships</li>
 * </ol>
 *
 * <p><b>关于内联值与标识符</b>：编译产物需要可归档（存进 {@code rule_run.compiled_sql} 用于复盘），
 * 因此标识符与字面量被内联进 SQL。安全边界靠两件事：
 * <ul>
 *   <li>标识符必须匹配 {@code [A-Za-z_][A-Za-z0-9_$]*} 白名单，否则**拒绝编译**（不是转义，是拒绝）；</li>
 *   <li>字面量做类型校验（数值）或单引号转义（字符串）。</li>
 * </ul>
 * 规则定义来自受治理的配置（YAML 进 Git 或经审批写入），不是终端用户输入；
 * 但边界仍然显式实现，不依赖"输入可信"这个假设。
 */
public final class RuleCompiler {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private RuleCompiler() {
    }

    // ============================================================ 前端 1：YAML

    /**
     * 编译 YAML 声明式规则文档。
     *
     * <pre>
     * rule: freshness_and_uniqueness
     * dataset: urn:dg:Dataset:...
     * checks:
     *   - {type: freshness, column: updated_at, maxLag: PT6H}
     *   - {type: uniqueness, columns: [cust_key], threshold: 0.999}
     *   - {type: rowCountChange, maxDropPct: 30, window: 7d}
     *   - {type: customSql, sql: "SELECT count(*) FROM t WHERE amount &lt; 0", expect: 0}
     * scheduling: {cron: "0 3 * * *", timezone: Asia/Shanghai}
     * severity: {onFail: HIGH}
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public static List<QualityRule> fromYamlDocument(Map<String, Object> document, String defaultDatasetUrn) {
        String dataset = document.get("dataset") == null
                ? defaultDatasetUrn : String.valueOf(document.get("dataset"));
        if (dataset == null || dataset.isBlank()) {
            throw new QualityException("规则文档缺少 dataset（且未传入默认数据集）");
        }
        String baseId = document.get("rule") == null ? "rule" : String.valueOf(document.get("rule"));
        Map<String, Object> scheduling = document.get("scheduling") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> severitySpec = document.get("severity") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        Object rawChecks = document.get("checks");
        if (!(rawChecks instanceof List<?> checks) || checks.isEmpty()) {
            throw new QualityException("规则文档缺少 checks（至少要有一条检查）");
        }

        List<QualityRule> rules = new ArrayList<>();
        int index = 0;
        for (Object item : checks) {
            if (!(item instanceof Map<?, ?> check)) {
                throw new QualityException("checks 的每一项必须是对象");
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            check.forEach((key, value) -> spec.put(String.valueOf(key), value));
            index++;
            String ruleId = baseId + "#" + index;
            rules.add(fromCheck(ruleId, dataset, spec, scheduling, severitySpec));
        }
        return rules;
    }

    private static final java.util.Set<String> SEVERITIES =
            java.util.Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    private static QualityRule fromCheck(String ruleId, String dataset, Map<String, Object> spec,
                                         Map<String, Object> scheduling, Map<String, Object> severitySpec) {
        String type = str(spec.get("type"));
        if (type == null) {
            throw new QualityException("check 缺少 type：" + spec);
        }
        // 注意区分两个容易混淆的概念（docs/09 §9.4 的 YAML 示例把它们写在一起了）：
        //   severity = 严重级别（INFO…CRITICAL）—— 决定这条规则失败有多要紧
        //   onFail   = 失败动作（BLOCK / ALERT / RECORD）—— 决定失败后做什么
        // 把 severity 当成 onFail 传下去会得到一条模型校验不过的规则（踩过）。
        String severity = severitySpec.get("onFail") != null
                ? str(severitySpec.get("onFail")) : str(spec.get("severity"));
        if (severity == null || severity.isBlank()) {
            severity = "MEDIUM";
        }
        severity = severity.toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new QualityException("severity 必须是 " + SEVERITIES + " 之一，收到 " + severity
                    + "（若想表达失败动作请用 onFail=BLOCK/ALERT/RECORD）");
        }
        String onFail = severitySpec.get("onFailAction") == null
                ? defaultOnFail(severity) : str(severitySpec.get("onFailAction"));

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("template", type);

        Double threshold;
        String operator;
        String metric;
        String column = str(spec.get("column"));
        List<String> columns = stringList(spec.get("columns"));
        String window = str(spec.get("window"));
        Double percentile = number(spec.get("percentile"));
        String pattern = str(spec.get("pattern"));
        List<String> acceptedValues = stringList(spec.get("acceptedValues"));
        String customSql = str(spec.get("sql"));
        String expected = str(spec.get("expect"));

        switch (type) {
            case "notNull", "not_null" -> {
                metric = "null_count";
                operator = "=";
                threshold = 0.0;
            }
            case "uniqueness", "unique" -> {
                // threshold 语义是"唯一率"（0.999），因此编译为**重复率** ≤ 1 - 唯一率。
                // 把比率当计数用会得到"看起来对、判定实际错"的规则（见 model 注释）
                Double uniqueness = number(spec.get("threshold"));
                if (uniqueness == null) {
                    throw new QualityException("uniqueness 检查必须提供 threshold（唯一率，如 0.999）");
                }
                metric = "duplicate_ratio";
                operator = "<=";
                threshold = 1.0 - uniqueness;
                hints.put("uniquenessThreshold", uniqueness);
            }
            case "nullRatio" -> {
                metric = "null_ratio";
                operator = "<=";
                threshold = number(spec.get("threshold"));
            }
            case "distinctness", "distinctCount" -> {
                metric = "distinct_count";
                operator = str(spec.get("operator")) == null ? ">=" : str(spec.get("operator"));
                threshold = number(spec.get("threshold"));
            }
            case "rowCount" -> {
                metric = "row_count";
                operator = str(spec.get("operator")) == null ? ">=" : str(spec.get("operator"));
                threshold = number(spec.get("threshold"));
            }
            case "rowCountChange" -> {
                // 相对判定：需要一个窗口基线，执行阶段从历史结果里取（取不到 → SKIPPED，不是 PASS）
                Double maxDrop = number(spec.get("maxDropPct"));
                if (maxDrop == null) {
                    throw new QualityException("rowCountChange 检查必须提供 maxDropPct");
                }
                metric = "row_count";
                operator = ">=";
                threshold = null;
                hints.put("relative", "rowCountChange");
                hints.put("maxDropPct", maxDrop);
                hints.put("baselineWindow", window == null ? "7d" : window);
                expected = "不低于窗口基线的 " + (100 - maxDrop) + "%";
            }
            case "freshness" -> {
                String maxLag = str(spec.get("maxLag"));
                if (maxLag == null) {
                    throw new QualityException("freshness 检查必须提供 maxLag（ISO-8601 时长，如 PT6H）");
                }
                metric = "freshness_seconds";
                operator = "<=";
                threshold = (double) parseDurationSeconds(maxLag);
                column = column == null ? "updated_at" : column;
            }
            case "acceptedValues", "enum" -> {
                if (acceptedValues.isEmpty()) {
                    throw new QualityException("acceptedValues 检查必须提供 acceptedValues 列表");
                }
                metric = "custom_sql";
                operator = "=";
                threshold = 0.0;
                customSql = null; // 由编译器用模板生成
            }
            case "regex", "pattern" -> {
                if (pattern == null) {
                    throw new QualityException("regex 检查必须提供 pattern");
                }
                metric = "pattern_match_rate";
                operator = ">=";
                threshold = number(spec.get("threshold"));
            }
            case "customSql", "sqlAssertion" -> {
                if (customSql == null) {
                    throw new QualityException("customSql 检查必须提供 sql");
                }
                metric = "custom_sql";
                operator = "=";
                threshold = expected == null ? 0.0 : number(expected);
            }
            default -> throw new QualityException("不支持的检查类型：" + type
                    + "（内置模板：notNull / uniqueness / nullRatio / distinctness / rowCount / "
                    + "rowCountChange / freshness / acceptedValues / regex / customSql）");
        }

        Map<String, Object> schedule = new LinkedHashMap<>(scheduling);
        return new QualityRule(ruleId, dataset, metric, operator, threshold, null, column, columns,
                window, percentile, pattern, acceptedValues, customSql, expected, severity, null,
                onFail, schedule, hints, "yaml");
    }

    // ======================================================= 前端 2：SQL 断言

    /**
     * SQL 断言前端：{@code SELECT count(*) FROM t WHERE &lt;违约条件&gt;}，期望为 0。
     *
     * <p>这是最直观的形式（分析同学会写的第一种规则），因此必须支持。
     */
    public static QualityRule fromSqlAssertion(String ruleId, String datasetUrn, String sql,
                                               Double expect, String severity, String dimension) {
        if (sql == null || sql.isBlank()) {
            throw new QualityException("SQL 断言不能为空");
        }
        String normalized = sql.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new QualityException("SQL 断言必须是 SELECT 语句（返回一个数值，通常为违约行数）");
        }
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("template", "sqlAssertion");
        return new QualityRule(ruleId, datasetUrn, "custom_sql", "=", expect == null ? 0.0 : expect,
                null, null, List.of(), null, null, null, List.of(), normalized,
                expect == null ? "0 条违约" : String.valueOf(expect), severity, dimension, null,
                Map.of(), hints, "sql_assertion");
    }

    // ======================================================== 前端 3：dbt tests

    /**
     * dbt tests 前端（复用已有的 dbt 测试，避免治理平台要求团队重写规则）。
     *
     * <p>支持 unique / not_null / accepted_values / relationships 四种内置测试。
     * dbt 1.8 起 {@code tests:} 更名为 {@code data_tests:}，两者都接受。
     */
    @SuppressWarnings("unchecked")
    public static List<QualityRule> fromDbtTests(String modelName, String datasetUrn,
                                                 List<Map<String, Object>> tests) {
        List<QualityRule> rules = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> test : tests) {
            index++;
            String ruleId = "dbt:" + modelName + "#" + index;
            Map<String, Object> hints = new LinkedHashMap<>();
            hints.put("template", "dbt");
            hints.put("dbtModel", modelName);

            if (test.containsKey("unique")) {
                rules.add(new QualityRule(ruleId, datasetUrn, "duplicate_count", "=", 0.0, null,
                        str(test.get("unique")), List.of(), null, null, null, List.of(), null,
                        "无重复", "HIGH", "uniqueness", null, Map.of(), hints, "dbt_test"));
            } else if (test.containsKey("not_null")) {
                rules.add(new QualityRule(ruleId, datasetUrn, "null_count", "=", 0.0, null,
                        str(test.get("not_null")), List.of(), null, null, null, List.of(), null,
                        "无空值", "HIGH", "completeness", null, Map.of(), hints, "dbt_test"));
            } else if (test.containsKey("accepted_values")) {
                Map<String, Object> spec = (Map<String, Object>) test.get("accepted_values");
                String field = str(spec.get("field"));
                List<String> values = stringList(spec.get("values"));
                if (field == null || values.isEmpty()) {
                    throw new QualityException("dbt accepted_values 测试必须提供 field 与 values");
                }
                rules.add(new QualityRule(ruleId, datasetUrn, "custom_sql", "=", 0.0, null,
                        field, List.of(), null, null, null, values, null, "全部在允许值内",
                        "HIGH", "validity", null, Map.of(), hints, "dbt_test"));
            } else if (test.containsKey("relationships")) {
                Map<String, Object> spec = (Map<String, Object>) test.get("relationships");
                String field = str(spec.get("field"));
                String to = str(spec.get("to"));
                String fieldTo = str(spec.get("field"));
                if (field == null || to == null) {
                    throw new QualityException("dbt relationships 测试必须提供 field 与 to");
                }
                hints.put("relationshipsTo", to);
                hints.put("relationshipsFieldTo", fieldTo == null ? field : fieldTo);
                rules.add(new QualityRule(ruleId, datasetUrn, "custom_sql", "=", 0.0, null,
                        field, List.of(), null, null, null, List.of(), null, "无孤儿引用",
                        "HIGH", "consistency", null, Map.of(), hints, "dbt_test"));
            } else {
                throw new QualityException("不支持的 dbt test：" + test.keySet()
                        + "（支持 unique / not_null / accepted_values / relationships）");
            }
        }
        return rules;
    }

    // ================================================================ 编译为 SQL

    /**
     * 把 IR 编译成引擎 SQL（返回单个数值）。
     *
     * @param qualifiedTable 已限定并加引号的表名，如 {@code "public"."orders"}
     * @return 编译结果（SQL + 期望的人类可读描述 + 是否需要外部基线）
     */
    public static CompiledQuery compile(QualityRule rule, String qualifiedTable) {
        String column = rule.effectiveTargetColumn();
        String columnRef = column == null ? null : requireIdentifier(column, "column");
        String sql = switch (rule.metric()) {
            case "null_count" -> "SELECT COUNT(*)::numeric FROM " + qualifiedTable
                    + " WHERE " + columnRef + " IS NULL";
            case "null_ratio" -> "SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE "
                    + "COUNT(*) FILTER (WHERE " + columnRef + " IS NULL)::numeric / COUNT(*) END FROM "
                    + qualifiedTable;
            case "missing_count" -> "SELECT COUNT(*)::numeric FROM " + qualifiedTable
                    + " WHERE " + columnRef + " IS NULL OR " + columnRef + "::text = ''";
            case "distinct_count" -> "SELECT COUNT(DISTINCT " + columnRef + ")::numeric FROM " + qualifiedTable;
            case "duplicate_count" -> "SELECT COALESCE(SUM(c - 1), 0)::numeric FROM (SELECT COUNT(*) AS c FROM "
                    + qualifiedTable + " GROUP BY " + columnRef + " HAVING COUNT(*) > 1) dup";
            case "duplicate_ratio" -> "SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE "
                    + "1 - (COUNT(DISTINCT " + columnRef + ")::numeric / COUNT(*)) END FROM " + qualifiedTable;
            case "row_count" -> "SELECT COUNT(*)::numeric FROM " + qualifiedTable;
            case "freshness_seconds" -> "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MAX(" + columnRef
                    + "))), -1)::numeric FROM " + qualifiedTable;
            case "percentile" -> "SELECT percentile_cont(" + literal(rule.percentile()) + ") "
                    + "WITHIN GROUP (ORDER BY " + columnRef + ")::numeric FROM " + qualifiedTable;
            case "pattern_match_rate" -> "SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE "
                    + "COUNT(*) FILTER (WHERE " + columnRef + "::text ~ " + literal(rule.pattern())
                    + ")::numeric / COUNT(*) END FROM " + qualifiedTable;
            case "custom_sql" -> compileCustom(rule, qualifiedTable, columnRef);
            default -> throw new QualityException("无法编译的指标：" + rule.metric());
        };

        boolean needsBaseline = "rowCountChange".equals(rule.engineHints().get("relative"));
        return new CompiledQuery(sql, describeExpected(rule), needsBaseline);
    }

    private static String compileCustom(QualityRule rule, String qualifiedTable, String columnRef) {
        if (rule.customSql() != null && !rule.customSql().isBlank()) {
            return rule.customSql();
        }
        Object template = rule.engineHints().get("template");
        if ("acceptedValues".equals(template) || "enum".equals(template)) {
            // 枚举违约 = 值不在允许集合内的行数（NULL 不计：空值由 notNull 规则单独负责）
            List<String> quoted = new ArrayList<>();
            for (String value : rule.acceptedValues()) {
                quoted.add(literal(value));
            }
            return "SELECT COUNT(*)::numeric FROM " + qualifiedTable
                    + " WHERE " + columnRef + " IS NOT NULL AND " + columnRef + "::text NOT IN ("
                    + String.join(", ", quoted) + ")";
        }
        if ("dbt".equals(template)) {
            String relationshipsTo = str(rule.engineHints().get("relationshipsTo"));
            if (relationshipsTo != null) {
                String qualifiedTarget = qualifying(relationshipsTo);
                String targetColumn = requireIdentifier(
                        str(rule.engineHints().get("relationshipsFieldTo")), "relationships.field");
                return "SELECT COUNT(*)::numeric FROM " + qualifiedTable + " child"
                        + " LEFT JOIN " + qualifiedTarget + " parent ON child." + columnRef
                        + " = parent." + targetColumn
                        + " WHERE child." + columnRef + " IS NOT NULL AND parent." + targetColumn + " IS NULL";
            }
            List<String> quoted = new ArrayList<>();
            for (String value : rule.acceptedValues()) {
                quoted.add(literal(value));
            }
            return "SELECT COUNT(*)::numeric FROM " + qualifiedTable
                    + " WHERE " + columnRef + " IS NOT NULL AND " + columnRef + "::text NOT IN ("
                    + String.join(", ", quoted) + ")";
        }
        throw new QualityException("无法编译自定义 SQL（既没有 customSql，也没有可识别的模板）："
                + rule.engineHints());
    }

    // ==================================================================== 判定

    /** 判定观察值是否满足规则。 */
    public static boolean evaluate(double observed, QualityRule rule) {
        Double threshold = rule.threshold();
        double max = rule.thresholdMax() == null ? 0.0 : rule.thresholdMax();
        return switch (rule.operator()) {
            case "=" -> threshold != null && close(observed, threshold);
            case "!=" -> threshold != null && !close(observed, threshold);
            case ">" -> threshold != null && observed > threshold;
            case ">=" -> threshold != null && observed >= threshold;
            case "<" -> threshold != null && observed < threshold;
            case "<=" -> threshold != null && observed <= threshold;
            case "between" -> threshold != null && observed >= threshold && observed <= max;
            case "in" -> rule.acceptedValues().stream()
                    .anyMatch(value -> close(observed, parseOrDefault(value, Double.NaN)));
            default -> throw new QualityException("不支持的运算符：" + rule.operator());
        };
    }

    /**
     * 行数波动这类相对判定的期望值：由窗口基线算出。
     *
     * <p>没有基线时**不能默认通过** —— 返回 null，由调用方标成 SKIPPED。
     */
    public static Double expectedFromBaseline(QualityRule rule, Double baseline) {
        if (!"rowCountChange".equals(rule.engineHints().get("relative"))) {
            return rule.threshold();
        }
        if (baseline == null) {
            return null;
        }
        double maxDrop = parseOrDefault(String.valueOf(rule.engineHints().get("maxDropPct")), 30.0);
        return baseline * (1.0 - maxDrop / 100.0);
    }

    public static String describeExpected(QualityRule rule) {
        if (rule.expected() != null && !rule.expected().isBlank()
                && !"rowCountChange".equals(rule.engineHints().get("relative"))) {
            return rule.expected();
        }
        String threshold = rule.threshold() == null ? "基线相对值" : trim(rule.threshold());
        return "between".equals(rule.operator())
                ? "介于 " + threshold + " 与 " + trim(rule.thresholdMax()) + " 之间"
                : rule.operator() + " " + threshold + (rule.column() == null ? "" : "（列 " + rule.column() + "）");
    }

    // ================================================================== 工具

    /** 标识符白名单校验：不匹配就拒绝编译，而不是尝试转义。 */
    public static String requireIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new QualityException("非法的 " + what + " 标识符：" + value
                    + "（只允许 [A-Za-z_][A-Za-z0-9_$]*；不支持表达式或带引号的写法）");
        }
        return "\"" + value + "\"";
    }

    /** URN → 已限定的表名（{@code "schema"."table"}）。 */
    public static String qualifiedTable(String datasetUrn) {
        List<String> parts = UrnUtils.parse(datasetUrn).parts();
        if (parts.size() < 2) {
            throw new QualityException("数据集 URN 段数不足，无法定位表：" + datasetUrn);
        }
        String table = parts.get(parts.size() - 1);
        String schema = parts.get(parts.size() - 2);
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(table, "table");
    }

    private static String qualifying(String raw) {
        String trimmed = raw.trim();
        int dot = trimmed.lastIndexOf('.');
        String schema = dot < 0 ? "public" : trimmed.substring(0, dot);
        String table = dot < 0 ? trimmed : trimmed.substring(dot + 1);
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(table, "table");
    }

    /** ISO-8601 时长 → 秒（支持 P#DT#H#M#S 与 PT#H#M#S）。 */
    public static long parseDurationSeconds(String value) {
        if (value == null || value.isBlank()) {
            throw new QualityException("时长不能为空");
        }
        java.util.regex.Matcher matcher = Pattern
                .compile("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
                .matcher(value.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new QualityException("无法解析的 ISO-8601 时长：" + value + "（示例：PT6H、P1DT12H）");
        }
        long days = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long hours = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long minutes = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        long seconds = matcher.group(4) == null ? 0 : Long.parseLong(matcher.group(4));
        long total = days * 86400 + hours * 3600 + minutes * 60 + seconds;
        if (total <= 0) {
            throw new QualityException("时长必须为正：" + value);
        }
        return total;
    }

    /** 默认失败动作：越严重越倾向于阻断；低级别只记录，避免告警疲劳。 */
    private static String defaultOnFail(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "BLOCK";
            case "HIGH", "MEDIUM" -> "ALERT";
            default -> "RECORD";
        };
    }

    private static String literal(Double value) {
        if (value == null) {
            throw new QualityException("缺少数值字面量");
        }
        return trim(value);
    }

    private static String literal(String value) {
        if (value == null) {
            throw new QualityException("缺少字符串字面量");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String trim(Double value) {
        if (value == null) {
            return "null";
        }
        if (value == Math.floor(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseOrDefault(String value, double fallback) {
        Double parsed = number(value);
        return parsed == null ? fallback : parsed;
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

    /** 编译结果。 */
    public record CompiledQuery(String sql, String expected, boolean needsBaseline) {
    }
}
