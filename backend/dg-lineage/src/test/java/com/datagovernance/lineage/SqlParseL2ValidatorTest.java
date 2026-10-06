package com.datagovernance.lineage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 校验层的纯逻辑测试。
 *
 * <p>重点是**判定纪律**，而不是"能跑"：
 * <ul>
 *   <li>{@code SELECT *} 能展开（用平台 schema）、缺 schema 时**记账而不是静默**；</li>
 *   <li>无表限定的列只在**唯一一个**源表里存在时才消歧；两个源表都有 → **不猜**；</li>
 *   <li>推出来的边置信度必须低于 exact，且解析级别为 derived（使用者有权区分）；</li>
 *   <li>"缺 schema" 与 "解析失败" 必须分开记 —— 前者能靠采集补上，后者不能。</li>
 * </ul>
 */
class SqlParseL2ValidatorTest {

    private static final String TARGET = "urn:dg:Dataset:prod.pg.public.dw_summary";
    private static final String EVENTS = "urn:dg:Dataset:prod.pg.public.event_log";
    private static final String USERS = "urn:dg:Dataset:prod.pg.public.users";

    /** 假 schema：event_log 有 3 列，users 的 schema 故意不采集。 */
    private static SqlParseL2Validator validatorWithKnownSchema() {
        Map<String, Set<String>> schemas = Map.of(
                EVENTS, Set.of("seq", "event_type", "created_at"),
                USERS, Set.of("id", "name"));
        return new SqlParseL2Validator(urn -> schemas.getOrDefault(urn, Set.of()));
    }

    private static final List<String> STAR_WARNING =
            List.of("存在 SELECT *，未展开列级血缘（缺 schema）", "未能产出任何列级边，降级为表级");

    @Test
    void expandsSelectStarUsingPlatformSchema() {
        SqlParseL2Validator.Result result = validatorWithKnownSchema()
                .validate(TARGET, List.of(EVENTS), STAR_WARNING, "table_level_only", false);

        assertEquals(3, result.edges().size(), "应按上游 3 列展开出 3 条边");
        assertTrue(result.edges().stream().allMatch(edge ->
                        edge.fromUrn().equals(EVENTS) && edge.toUrn().equals(TARGET)
                                && edge.fromColumn().equals(edge.toColumn())),
                "SELECT * 的语义是同名透传");
        assertTrue(result.edges().stream().allMatch(edge -> "derived".equals(edge.parseLevel())),
                "推出来的边必须是 derived，不能冒充 exact");
        assertTrue(result.edges().stream().allMatch(edge -> edge.confidence() < 0.7),
                "推断的置信度必须低于 exact（0.8）");
        assertTrue(result.findings().stream().anyMatch(f -> "select_star_expanded".equals(f.checkType())),
                "补出来的边也要有记录：" + result.findings());
    }

    @Test
    void missingSchemaIsRecordedNotSilentlySkipped() {
        SqlParseL2Validator validator = new SqlParseL2Validator(urn -> Set.of());   // 什么都没采集
        SqlParseL2Validator.Result result = validator
                .validate(TARGET, List.of(EVENTS), STAR_WARNING, "table_level_only", false);

        assertTrue(result.edges().isEmpty(), "没有 schema 就不该凭空造边");
        SqlParseL2Validator.Finding finding = result.findings().stream()
                .filter(item -> "select_star_unresolved".equals(item.checkType()))
                .findFirst().orElseThrow();
        assertTrue(finding.message().contains("先采集"), "要告诉使用者**怎么办**：" + finding.message());
        assertEquals(EVENTS, finding.evidence().get("source"));
    }

    @Test
    void unqualifiedColumnIsDisambiguatedOnlyWhenUnique() {
        SqlParseL2Validator validator = validatorWithKnownSchema();
        // name 只在 users 里存在 → 可以确定来源
        SqlParseL2Validator.Result unique = validator.validate(TARGET, List.of(EVENTS, USERS),
                List.of("列 name 的来源 'name' 无法定位到表（无表限定且存在多个源表）"),
                "table_level_only", false);
        assertTrue(unique.edges().stream().anyMatch(edge ->
                        edge.fromUrn().equals(USERS) && "name".equals(edge.fromColumn())),
                "唯一命中才消歧：" + unique.edges());
        assertTrue(unique.findings().stream()
                        .anyMatch(f -> "ambiguous_column_resolved".equals(f.checkType())),
                unique.findings().toString());
    }

    @Test
    void ambiguousColumnAcrossTwoTablesIsNotGuessed() {
        Map<String, Set<String>> schemas = Map.of(
                EVENTS, Set.of("id", "event_type"),
                USERS, Set.of("id", "name"));
        SqlParseL2Validator validator = new SqlParseL2Validator(urn -> schemas.getOrDefault(urn, Set.of()));

        SqlParseL2Validator.Result result = validator.validate(TARGET, List.of(EVENTS, USERS),
                List.of("列 id 的来源 'id' 无法定位到表（无表限定且存在多个源表）"),
                "table_level_only", false);

        assertTrue(result.edges().isEmpty(), "两个源表都有 id → 绝不挑一个（错边比缺边更难发现）");
        SqlParseL2Validator.Finding finding = result.findings().stream()
                .filter(item -> "ambiguous_column_unresolved".equals(item.checkType()))
                .findFirst().orElseThrow();
        assertEquals(2, ((List<?>) finding.evidence().get("candidates")).size(),
                "候选要列全，便于人判断：" + finding.evidence());
    }

    @Test
    void parseFailureIsFiledSeparatelyFromMissingSchema() {
        SqlParseL2Validator.Result result = validatorWithKnownSchema()
                .validate(TARGET, List.of(EVENTS), List.of("sqlglot 解析异常：Expecting )"),
                        "failed", true);
        assertEquals(1, result.findings().size());
        assertEquals("parse_failed", result.findings().get(0).checkType());
        assertTrue(result.findings().get(0).message().contains("与 schema 缺失不同"),
                "两类失败必须能分开：前者采集能补，后者不能");
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void unresolvedTargetIsRecordedInsteadOfCrashing() {
        SqlParseL2Validator.Result result = validatorWithKnownSchema()
                .validate(null, List.of(EVENTS), STAR_WARNING, "table_level_only", false);
        assertEquals(1, result.findings().size());
        assertEquals("target_unresolved", result.findings().get(0).checkType());
    }

    @Test
    void warningParsingExtractsColumnNames() {
        Set<String> columns = SqlParseL2Validator.columnsMentionedInWarnings(List.of(
                "列 id 的来源 'id' 无法定位到表（无表限定且存在多个源表）",
                "列 Event_Type 的来源 'Event_Type' 无法定位到表（无表限定且存在多个源表）",
                "无关的警告"));
        assertEquals(Set.of("id", "event_type"), columns, "列名统一小写，与 datasetSchema 的比对口径一致");
    }

    @Test
    void noWarningsMeansNoWork() {
        SqlParseL2Validator.Result result = validatorWithKnownSchema()
                .validate(TARGET, List.of(EVENTS), List.of(), "exact", false);
        assertTrue(result.edges().isEmpty());
        assertFalse(!result.findings().isEmpty(), "exact 的语句没有信息缺口，不该产生发现");
    }
}
