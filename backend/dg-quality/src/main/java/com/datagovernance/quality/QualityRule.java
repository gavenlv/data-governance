package com.datagovernance.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量规则的<b>统一中间表示（IR）</b>（docs/09 §9.4）。
 *
 * <p>设计取向：<b>不发明第 N 种 DSL</b>，而是把三种前端语法（YAML 声明式 / SQL 断言 /
 * dbt tests）都编译到这一个 IR，再由编译器生成引擎 SQL。
 * 因此新增一种前端语法无需改动执行与存储。
 *
 * <p>{@code engineHints} 是刻意保留的逃生口：GX 的 {@code mostly}、Soda 的百分比、
 * Deequ 的 {@code CheckLevel} 语义并不一一对应，强行统一会退化成"最小公分母"。
 * 引擎特有语义放这里，而<b>默认执行路径不依赖任何外部 DSL</b>。
 *
 * @param ruleId      规则标识（全局唯一，用于生成 URN）
 * @param datasetUrn  目标数据集
 * @param metric      IR 指标（见 model/core/aspects.yaml 的 ruleSpec.metric 枚举）
 * @param operator    比较运算符
 * @param threshold   阈值（between 时为下界）
 * @param thresholdMax 上界（仅 between 使用）
 * @param column      单列指标的目标列
 * @param columns     多列指标（唯一性等）的目标列
 * @param window      窗口（如 7d），用于行数波动这类相对判定
 * @param percentile  分位数（metric=percentile 时必填）
 * @param pattern     正则（metric=pattern_match_rate 时必填）
 * @param acceptedValues 枚举校验的允许值（引擎侧编译为 NOT IN 计数）
 * @param customSql   自定义 SQL（断言式：返回一个数值）
 * @param expected    期望的人类可读描述（如 "0 条违约"）
 * @param severity    INFO/LOW/MEDIUM/HIGH/CRITICAL
 * @param dimension   DAMA 六维
 * @param onFail      BLOCK/ALERT/RECORD
 * @param schedule    调度（cron/timezone）
 * @param engineHints 引擎特有语义与相对判定的参数
 * @param sourceFrontend 该规则由哪种前端语法编译而来
 */
public record QualityRule(
        String ruleId,
        String datasetUrn,
        String metric,
        String operator,
        Double threshold,
        Double thresholdMax,
        String column,
        List<String> columns,
        String window,
        Double percentile,
        String pattern,
        List<String> acceptedValues,
        String customSql,
        String expected,
        String severity,
        String dimension,
        String onFail,
        Map<String, Object> schedule,
        Map<String, Object> engineHints,
        String sourceFrontend) {

    public QualityRule {
        columns = columns == null ? List.of() : List.copyOf(columns);
        acceptedValues = acceptedValues == null ? List.of() : List.copyOf(acceptedValues);
        schedule = schedule == null ? Map.of() : Map.copyOf(schedule);
        engineHints = engineHints == null ? Map.of() : Map.copyOf(engineHints);
        severity = severity == null || severity.isBlank() ? "MEDIUM" : severity;
        dimension = dimension == null || dimension.isBlank() ? "validity" : dimension;
        onFail = onFail == null || onFail.isBlank() ? "ALERT" : onFail;
        operator = operator == null || operator.isBlank() ? "=" : operator;
        sourceFrontend = sourceFrontend == null || sourceFrontend.isBlank() ? "yaml" : sourceFrontend;
    }

    // ------------------------------------------------------------------ 校验

    /** 结构校验：IR 不完整就不该进入执行阶段（错误在这里暴露，而不是在执行时）。 */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (ruleId == null || ruleId.isBlank()) {
            errors.add("缺少 ruleId");
        }
        if (datasetUrn == null || datasetUrn.isBlank()) {
            errors.add("缺少 datasetUrn");
        }
        if (metric == null || metric.isBlank()) {
            errors.add("缺少 metric");
        }
        if ("custom_sql".equals(metric)) {
            if (customSql == null || customSql.isBlank()) {
                errors.add("metric=custom_sql 时必须提供 customSql");
            }
        } else if ("percentile".equals(metric)) {
            if (percentile == null) {
                errors.add("metric=percentile 时必须提供 percentile");
            }
            if (column == null) {
                errors.add("metric=percentile 时必须提供 column");
            }
        } else if ("pattern_match_rate".equals(metric)) {
            if (pattern == null) {
                errors.add("metric=pattern_match_rate 时必须提供 pattern");
            }
            if (column == null) {
                errors.add("metric=pattern_match_rate 时必须提供 column");
            }
        } else if ("duplicate_count".equals(metric) || "duplicate_ratio".equals(metric)) {
            if (column == null && columns.isEmpty()) {
                errors.add("metric=" + metric + " 时必须提供 column 或 columns");
            }
        } else if (column == null && !"row_count".equals(metric)) {
            errors.add("metric=" + metric + " 时必须提供 column");
        }
        if ("between".equals(operator) && thresholdMax == null) {
            errors.add("operator=between 时必须提供 thresholdMax");
        }
        if (!"custom_sql".equals(metric) && !"row_count".equals(metric)
                && !"missing_count".equals(metric) && threshold == null) {
            errors.add("metric=" + metric + " 时必须提供 threshold");
        }
        return errors;
    }

    public String effectiveTargetColumn() {
        if (column != null && !column.isBlank()) {
            return column;
        }
        return columns.isEmpty() ? null : columns.get(0);
    }

    // -------------------------------------------------- 与 ruleSpec aspect 互转

    /** 转成 ruleSpec aspect 的数据（写入真相源 → 进检索、有版本历史与 Owner）。 */
    public Map<String, Object> toAspect() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", ruleId);
        data.put("metric", metric);
        data.put("operator", operator);
        if (threshold != null) {
            data.put("threshold", threshold);
        }
        if (thresholdMax != null) {
            data.put("thresholdMax", thresholdMax);
        }
        if (column != null) {
            data.put("column", column);
        }
        if (!columns.isEmpty()) {
            data.put("columns", columns);
        }
        if (window != null) {
            data.put("window", window);
        }
        if (percentile != null) {
            data.put("percentile", percentile);
        }
        if (pattern != null) {
            data.put("pattern", pattern);
        }
        if (!acceptedValues.isEmpty()) {
            data.put("acceptedValues", acceptedValues);
        }
        if (customSql != null) {
            data.put("customSql", customSql);
        }
        if (expected != null) {
            data.put("expected", expected);
        }
        data.put("severity", severity);
        data.put("dimension", dimension);
        data.put("onFail", onFail);
        if (!schedule.isEmpty()) {
            data.put("schedule", schedule);
        }
        if (!engineHints.isEmpty()) {
            data.put("engineHints", engineHints);
        }
        data.put("sourceFrontend", sourceFrontend);
        return data;
    }

    @SuppressWarnings("unchecked")
    public static QualityRule fromAspect(String datasetUrn, Map<String, Object> data) {
        return new QualityRule(
                str(data.get("ruleId")),
                datasetUrn,
                str(data.get("metric")),
                str(data.get("operator")),
                number(data.get("threshold")),
                number(data.get("thresholdMax")),
                str(data.get("column")),
                stringList(data.get("columns")),
                str(data.get("window")),
                number(data.get("percentile")),
                str(data.get("pattern")),
                stringList(data.get("acceptedValues")),
                str(data.get("customSql")),
                str(data.get("expected")),
                str(data.get("severity")),
                str(data.get("dimension")),
                str(data.get("onFail")),
                data.get("schedule") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                data.get("engineHints") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                str(data.get("sourceFrontend")));
    }

    // ------------------------------------------------------------- 小工具

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }
}
