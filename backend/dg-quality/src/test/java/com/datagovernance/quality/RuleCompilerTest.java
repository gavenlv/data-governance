package com.datagovernance.quality;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则编译器测试（三种前端 → IR → SQL）。
 *
 * <p>规则引擎的风险不是"跑不起来"，而是"跑起来了但判错了"：
 * 一条恒真的规则会让所有人以为质量被守住了。因此这里逐条断言编译产物与判定语义。
 */
class RuleCompilerTest {

    private static final String DATASET = "urn:dg:Dataset:prod.postgresql.dg.public.orders";

    @Test
    void yamlChecksCompileToIr() {
        List<QualityRule> rules = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "orders_quality",
                "dataset", DATASET,
                "checks", List.of(
                        Map.of("type", "notNull", "column", "order_id"),
                        Map.of("type", "uniqueness", "columns", List.of("order_id"), "threshold", 0.999),
                        Map.of("type", "freshness", "column", "updated_at", "maxLag", "PT6H"),
                        Map.of("type", "rowCount", "threshold", 1000))), DATASET);

        assertEquals(4, rules.size());
        assertEquals("null_count", rules.get(0).metric());
        assertEquals(0.0, rules.get(0).threshold());
        // uniqueness 的 0.999 是**唯一率**，必须编译成重复率上限 0.001，而不是当成计数
        assertEquals("duplicate_ratio", rules.get(1).metric());
        assertEquals("<=", rules.get(1).operator());
        assertEquals(0.001, rules.get(1).threshold(), 1e-9);
        assertEquals("freshness_seconds", rules.get(2).metric());
        assertEquals(6 * 3600.0, rules.get(2).threshold());
        assertEquals("row_count", rules.get(3).metric());
        assertTrue(rules.stream().allMatch(r -> r.validate().isEmpty()));
    }

    @Test
    void unknownCheckTypeIsRejectedWithTemplateList() {
        QualityException error = assertThrows(QualityException.class, () ->
                RuleCompiler.fromYamlDocument(Map.of(
                        "rule", "x", "checks", List.of(Map.of("type", "vibes", "column", "a"))), DATASET));
        assertTrue(error.getMessage().contains("不支持的检查类型"));
        assertTrue(error.getMessage().contains("uniqueness"), "错误信息里要给出可用模板");
    }

    @Test
    void missingChecksIsRejected() {
        assertThrows(QualityException.class, () ->
                RuleCompiler.fromYamlDocument(Map.of("rule", "x"), DATASET));
    }

    @Test
    void sqlAssertionFrontendDerivesCustomSqlRule() {
        QualityRule rule = RuleCompiler.fromSqlAssertion("no_negative_amount", DATASET,
                "SELECT count(*) FROM orders WHERE amount < 0", 0.0, "HIGH", "validity");
        assertEquals("custom_sql", rule.metric());
        assertEquals("sql_assertion", rule.sourceFrontend());
        RuleCompiler.CompiledQuery compiled = RuleCompiler.compile(rule, "\"public\".\"orders\"");
        assertEquals("SELECT count(*) FROM orders WHERE amount < 0", compiled.sql());
        assertTrue(RuleCompiler.evaluate(0, rule));
        assertFalse(RuleCompiler.evaluate(3, rule));
    }

    @Test
    void nonSelectSqlAssertionIsRejected() {
        assertThrows(QualityException.class, () ->
                RuleCompiler.fromSqlAssertion("bad", DATASET, "DELETE FROM orders", 0.0, null, null));
    }

    @Test
    void dbtTestsFrontendMapsFourBuiltinTests() {
        List<QualityRule> rules = RuleCompiler.fromDbtTests("orders", DATASET, List.of(
                Map.of("unique", "order_id"),
                Map.of("not_null", "customer_id"),
                Map.of("accepted_values", Map.of("field", "status", "values", List.of("PAID", "REFUNDED"))),
                Map.of("relationships", Map.of("field", "customer_id", "to", "public.customers"))));

        assertEquals(4, rules.size());
        assertEquals("duplicate_count", rules.get(0).metric());
        assertEquals("null_count", rules.get(1).metric());
        assertEquals("custom_sql", rules.get(2).metric());
        assertEquals("dbt_test", rules.get(3).sourceFrontend());

        String acceptedSql = RuleCompiler.compile(rules.get(2), "\"public\".\"orders\"").sql();
        assertTrue(acceptedSql.contains("NOT IN ('PAID', 'REFUNDED')"), acceptedSql);

        String relationshipsSql = RuleCompiler.compile(rules.get(3), "\"public\".\"orders\"").sql();
        assertTrue(relationshipsSql.contains("LEFT JOIN \"public\".\"customers\""), relationshipsSql);
        assertTrue(relationshipsSql.contains("parent.\"customer_id\" IS NULL"), relationshipsSql);
    }

    @Test
    void unsupportedDbtTestIsRejected() {
        assertThrows(QualityException.class, () ->
                RuleCompiler.fromDbtTests("orders", DATASET, List.of(Map.of("custom_thing", Map.of()))));
    }

    /** 标识符必须白名单校验：拒绝而不是转义（转义给了"看起来支持"的错觉）。 */
    @Test
    void identifierInjectionIsRejected() {
        QualityException error = assertThrows(QualityException.class,
                () -> RuleCompiler.requireIdentifier("a; DROP TABLE x", "column"));
        assertTrue(error.getMessage().contains("非法的"));
        assertThrows(QualityException.class, () -> RuleCompiler.requireIdentifier("col--", "column"));
    }

    @Test
    void qualifiedTableUsesLastTwoUrnSegments() {
        assertEquals("\"public\".\"orders\"", RuleCompiler.qualifiedTable(DATASET));
    }

    @Test
    void uniquenessCompilesToRatioFormAndDbtUniqueToCountForm() {
        // YAML uniqueness 的 threshold 是唯一率 → 编译为比率型 SQL
        QualityRule ratioRule = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "u", "checks", List.of(
                        Map.of("type", "uniqueness", "columns", List.of("order_id"), "threshold", 1.0))),
                DATASET).get(0);
        String ratioSql = RuleCompiler.compile(ratioRule, "\"public\".\"orders\"").sql();
        assertTrue(ratioSql.contains("COUNT(DISTINCT \"order_id\")"), ratioSql);

        // dbt unique 是"不许重复"→ 编译为重复行计数（应为 0），语义与比率型不同
        QualityRule countRule = RuleCompiler.fromDbtTests("orders", DATASET,
                List.of(Map.of("unique", "order_id"))).get(0);
        String countSql = RuleCompiler.compile(countRule, "\"public\".\"orders\"").sql();
        assertTrue(countSql.contains("GROUP BY \"order_id\" HAVING COUNT(*) > 1"), countSql);
    }

    @Test
    void percentileAndPatternRulesCompileWithLiterals() {
        QualityRule percentile = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "p", "checks", List.of(
                        Map.of("type", "customSql", "sql", "SELECT 0"))), DATASET).get(0);
        assertNotNull(percentile.customSql());

        QualityRule pattern = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "r", "checks", List.of(
                        Map.of("type", "regex", "column", "email", "pattern", "^[^@]+@[^@]+$",
                                "threshold", 0.99))), DATASET).get(0);
        String sql = RuleCompiler.compile(pattern, "\"public\".\"orders\"").sql();
        assertTrue(sql.contains("~ '^[^@]+@[^@]+$'"), sql);
        assertEquals("pattern_match_rate", pattern.metric());
    }

    /** 单引号必须被转义：否则模式里带引号就会拼出坏 SQL。 */
    @Test
    void stringLiteralsAreEscaped() {
        QualityRule rule = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "e", "checks", List.of(
                        Map.of("type", "acceptedValues", "column", "note",
                                "acceptedValues", List.of("it's fine")))), DATASET).get(0);
        String sql = RuleCompiler.compile(rule, "\"public\".\"orders\"").sql();
        assertTrue(sql.contains("'it''s fine'"), sql);
    }

    @Test
    void betweenOperatorNeedsUpperBound() {
        QualityRule bad = new QualityRule("x", DATASET, "row_count", "between", 1.0, null,
                null, List.of(), null, null, null, List.of(), null, null, null, null, null,
                Map.of(), Map.of(), "yaml");
        assertTrue(bad.validate().stream().anyMatch(e -> e.contains("thresholdMax")));
    }

    @Test
    void durationParsingHandlesIsoForms() {
        assertEquals(6 * 3600L, RuleCompiler.parseDurationSeconds("PT6H"));
        assertEquals(86400L, RuleCompiler.parseDurationSeconds("P1D"));
        assertEquals(5400L, RuleCompiler.parseDurationSeconds("PT1H30M"));
        assertThrows(QualityException.class, () -> RuleCompiler.parseDurationSeconds("6 hours"));
        assertThrows(QualityException.class, () -> RuleCompiler.parseDurationSeconds("PT0S"));
    }

    /** 行数波动这类相对判定没有基线时必须"无法判定"，而不是默认通过。 */
    @Test
    void relativeRuleWithoutBaselineHasNoExpectedValue() {
        QualityRule rule = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "rowchange", "checks", List.of(
                        Map.of("type", "rowCountChange", "maxDropPct", 30, "window", "7d"))), DATASET).get(0);
        assertEquals("rowCountChange", rule.engineHints().get("relative"));
        assertEquals(null, RuleCompiler.expectedFromBaseline(rule, null));
        assertEquals(700.0, RuleCompiler.expectedFromBaseline(rule, 1000.0));
    }

    /** severity（严重级别）与 onFail（失败动作）是两个概念，不能混用。 */
    @Test
    void severityAndOnFailAreSeparateConcepts() {
        QualityRule rule = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "s", "checks", List.of(Map.of("type", "notNull", "column", "id")),
                "severity", Map.of("onFail", "CRITICAL")), DATASET).get(0);
        assertEquals("CRITICAL", rule.severity());
        assertEquals("BLOCK", rule.onFail(), "CRITICAL 默认动作是阻断");
        assertTrue(rule.validate().isEmpty());

        QualityRule alertOnly = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "a", "checks", List.of(Map.of("type", "notNull", "column", "id")),
                "severity", Map.of("onFail", "LOW", "onFailAction", "RECORD")), DATASET).get(0);
        assertEquals("LOW", alertOnly.severity());
        assertEquals("RECORD", alertOnly.onFail());
    }

    /** 把 HIGH 这类"级别"当成"动作"传下去必须报错，而不是产生一条模型校验不过的规则。 */
    @Test
    void invalidSeverityIsRejectedWithGuidance() {
        QualityException error = assertThrows(QualityException.class, () ->
                RuleCompiler.fromYamlDocument(Map.of(
                        "rule", "bad", "checks", List.of(Map.of("type", "notNull", "column", "id")),
                        "severity", Map.of("onFail", "BLOCK")), DATASET));
        assertTrue(error.getMessage().contains("severity 必须是"), error.getMessage());
        assertTrue(error.getMessage().contains("onFail"), "错误信息要指出可能的混淆点");
    }

    @Test
    void aspectRoundTripKeepsSemantics() {
        QualityRule original = RuleCompiler.fromYamlDocument(Map.of(
                "rule", "rt", "dataset", DATASET,
                "checks", List.of(Map.of("type", "uniqueness", "columns", List.of("id"), "threshold", 0.99))),
                DATASET).get(0);
        QualityRule restored = QualityRule.fromAspect(DATASET, original.toAspect());
        assertEquals(original.metric(), restored.metric());
        assertEquals(original.threshold(), restored.threshold());
        assertEquals(original.operator(), restored.operator());
        assertEquals(original.severity(), restored.severity());
        assertTrue(restored.validate().isEmpty());
    }
}
