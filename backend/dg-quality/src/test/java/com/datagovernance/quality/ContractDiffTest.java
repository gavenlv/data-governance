package com.datagovernance.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约兼容性 diff 引擎测试。
 *
 * <p>兼容性判定错了的代价是双向的：把兼容变更判成破坏性 → 团队开始绕过门禁；
 * 把破坏性变更判成兼容 → 下游在生产上炸。因此每条规则都写成断言。
 */
class ContractDiffTest {

    private static Map<String, Object> spec(List<Map<String, Object>> fields) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("apiVersion", "v3.0.2");
        spec.put("contractVersion", "1.0.0");
        spec.put("schema", Map.of("fields", fields));
        return spec;
    }

    private static Map<String, Object> field(String name, String type, boolean required) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("required", required);
        return field;
    }

    @Test
    void initialRegistrationHasNoBreakingChange() {
        ContractDiff.Result result = ContractDiff.compare(null,
                spec(List.of(field("id", "bigint", true))), null, "1.0.0");
        assertEquals("INITIAL", result.verdict());
        assertFalse(result.breaking());
    }

    @Test
    void removingColumnIsBreakingAndRequiresMajorBump() {
        Map<String, Object> before = spec(List.of(field("id", "bigint", true), field("amount", "numeric", false)));
        Map<String, Object> after = spec(List.of(field("id", "bigint", true)));
        ContractDiff.Result result = ContractDiff.compare(before, after, "1.0.0", "1.1.0");
        assertTrue(result.breaking());
        assertEquals("MAJOR", result.requiredVersionBump());
        assertFalse(result.versionBumpSufficient(), "只升 minor 不足以承载删列");
        assertEquals("BLOCK", result.verdict());
    }

    @Test
    void narrowingTypeIsBreakingButWideningIsNot() {
        ContractDiff.Result narrowed = ContractDiff.compare(
                spec(List.of(field("id", "bigint", true))),
                spec(List.of(field("id", "int", true))), "1.0.0", "2.0.0");
        assertTrue(narrowed.breaking());

        ContractDiff.Result widened = ContractDiff.compare(
                spec(List.of(field("id", "int", true))),
                spec(List.of(field("id", "bigint", true))), "1.0.0", "1.1.0");
        assertFalse(widened.breaking());
        assertEquals("MINOR", widened.requiredVersionBump());
    }

    @Test
    void varcharPrecisionChangeIsNotBreaking() {
        assertTrue(ContractDiff.assessTypeChange("varchar(10)", "varchar(50)").compatible());
        assertTrue(ContractDiff.assessTypeChange("varchar(50)", "text").compatible());
    }

    @Test
    void optionalToRequiredIsBreaking() {
        ContractDiff.Result result = ContractDiff.compare(
                spec(List.of(field("id", "bigint", false))),
                spec(List.of(field("id", "bigint", true))), "1.0.0", "1.1.0");
        assertTrue(result.breaking());
        assertTrue(result.changes().stream().anyMatch(c -> "nullability_tightened".equals(c.kind())));
    }

    @Test
    void requiredToOptionalIsMinor() {
        ContractDiff.Result result = ContractDiff.compare(
                spec(List.of(field("id", "bigint", true))),
                spec(List.of(field("id", "bigint", false))), "1.0.0", "1.1.0");
        assertFalse(result.breaking());
        assertEquals("MINOR", result.requiredVersionBump());
        assertTrue(result.versionBumpSufficient());
    }

    @Test
    void addingRequiredColumnIsBreakingAddingOptionalIsNot() {
        assertTrue(ContractDiff.compare(spec(List.of(field("id", "bigint", true))),
                spec(List.of(field("id", "bigint", true), field("region", "text", true))),
                "1.0.0", "2.0.0").breaking());
        assertFalse(ContractDiff.compare(spec(List.of(field("id", "bigint", true))),
                spec(List.of(field("id", "bigint", true), field("region", "text", false))),
                "1.0.0", "1.1.0").breaking());
    }

    @Test
    void semanticChangeIsBreakingBecauseValuesStillLookValid() {
        Map<String, Object> before = field("amount", "numeric", true);
        before.put("semantic", "含税金额");
        Map<String, Object> after = field("amount", "numeric", true);
        after.put("semantic", "不含税金额");
        ContractDiff.Result result = ContractDiff.compare(spec(List.of(before)), spec(List.of(after)),
                "1.0.0", "1.1.0");
        assertTrue(result.breaking());
        assertTrue(result.changes().stream().anyMatch(c -> "semantic_changed".equals(c.kind())));
    }

    @Test
    void primaryKeyChangeIsBreaking() {
        Map<String, Object> before = spec(List.of(field("id", "bigint", true)));
        before.put("primaryKey", List.of("id"));
        Map<String, Object> after = spec(List.of(field("id", "bigint", true)));
        after.put("primaryKey", List.of("id", "region"));
        ContractDiff.Result result = ContractDiff.compare(before, after, "1.0.0", "2.0.0");
        assertTrue(result.breaking());
    }

    /** 悄悄降低质量承诺不是接口破坏，但必须显性记录，且版本号要如实反映（至少 minor）。 */
    @Test
    void weakeningQualityChecksIsRecordedAsMinor() {
        Map<String, Object> before = spec(List.of(field("id", "bigint", true)));
        before.put("quality", List.of(Map.of("type", "notNull", "column", "id"),
                Map.of("type", "uniqueness", "column", "id")));
        Map<String, Object> after = spec(List.of(field("id", "bigint", true)));
        after.put("quality", List.of(Map.of("type", "notNull", "column", "id")));

        ContractDiff.Result result = ContractDiff.compare(before, after, "1.0.0", "1.1.0");
        assertFalse(result.breaking());
        assertEquals("MINOR", result.requiredVersionBump());
        assertTrue(result.changes().stream().anyMatch(c -> "quality_weakened".equals(c.kind())));

        // patch 级别的版本号不足以承载"承诺被降低"：这种改动必须让消费者看得见
        ContractDiff.Result patchOnly = ContractDiff.compare(before, after, "1.0.0", "1.0.1");
        assertTrue(patchOnly.breaking(), "降低质量承诺却只升 patch，等于让消费者看不到变化");
        assertTrue(patchOnly.changes().stream()
                .anyMatch(c -> "version_bump_insufficient".equals(c.kind())));
    }

    @Test
    void identicalSpecHasNoChanges() {
        Map<String, Object> spec = spec(List.of(field("id", "bigint", true)));
        ContractDiff.Result result = ContractDiff.compare(spec, spec(List.of(field("id", "bigint", true))),
                "1.0.0", "1.0.0");
        assertTrue(result.changes().isEmpty());
        assertEquals("PASS", result.verdict());
    }

    @Test
    void typeFamilyAssessmentCoversCrossFamilyChanges() {
        assertFalse(ContractDiff.assessTypeChange("bigint", "text").compatible());
        assertFalse(ContractDiff.assessTypeChange("timestamp", "date").compatible());
        assertTrue(ContractDiff.assessTypeChange("date", "timestamp").compatible());
        assertTrue(ContractDiff.assessTypeChange("int", "numeric").compatible());
    }

    @Test
    void versionBumpRulesAreHonest() {
        assertTrue(ContractDiff.versionBumpSufficient("1.0.0", "2.0.0", "MAJOR"));
        assertFalse(ContractDiff.versionBumpSufficient("1.0.0", "1.9.9", "MAJOR"));
        assertTrue(ContractDiff.versionBumpSufficient("1.0.0", "1.1.0", "MINOR"));
        assertFalse(ContractDiff.versionBumpSufficient("1.0.0", "1.0.5", "MINOR"));
        assertTrue(ContractDiff.versionBumpSufficient("1.0.0", "1.0.1", "PATCH"));
        assertFalse(ContractDiff.versionBumpSufficient("1.0.0", "not-a-version", "MAJOR"));
    }

    @Test
    void schemaListFormIsAccepted() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("schema", List.of(field("id", "bigint", true)));
        assertEquals(1, ContractDiff.fieldsOf(spec).size());
    }

    @Test
    void nullableFlagIsTreatedAsRequiredWhenRequiredIsAbsent() {
        // nullable=false 等价于必填：改成 nullable=true 是**放宽**，不应判为破坏性
        Map<String, Object> strict = new LinkedHashMap<>();
        strict.put("schema", Map.of("fields", List.of(
                Map.of("name", "id", "type", "bigint", "nullable", false))));
        Map<String, Object> relaxed = new LinkedHashMap<>();
        relaxed.put("schema", Map.of("fields", List.of(
                Map.of("name", "id", "type", "bigint", "nullable", true))));

        ContractDiff.Result relaxedResult = ContractDiff.compare(strict, relaxed, "1.0.0", "1.1.0");
        assertFalse(relaxedResult.breaking(), "必填改可选是放宽约束");

        // 反向（可选 → 必填）才是破坏性
        ContractDiff.Result tightened = ContractDiff.compare(relaxed, strict, "1.0.0", "1.1.0");
        assertTrue(tightened.breaking(), "可选改必填会让已有数据违反新约束");
    }
}
