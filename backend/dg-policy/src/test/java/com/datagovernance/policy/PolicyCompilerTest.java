package com.datagovernance.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略编译器的快照测试（docs/09 §9.7 的硬性要求）。
 *
 * <p>设计文档的原话：<b>编译器必须可测试（给定策略 → 期望产物快照测试），
 * 否则错误策略直接造成数据泄露或大面积不可用。</b>
 * 因此这里对每个目标的核心产物做逐字断言 —— 这类测试的价值不在于"跑通"，
 * 而在于产物被人改动时立刻可见。
 */
class PolicyCompilerTest {

    private static PolicyCompiler.PolicySpec rowFilter() {
        return new PolicyCompiler.PolicySpec("region_filter", 3, "trino", "ROW_FILTER",
                Map.of("prefixes", List.of("urn:dg:Dataset:prod.postgresql.dw.")),
                Map.of("roles", List.of("ANALYST_CN")),
                Map.of("column", "region", "operator", "=", "value", "CN"),
                10);
    }

    @Test
    void trinoRowFilterProducesPredicateWithProvenanceHeader() {
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(rowFilter());
        assertEquals("trino", compiled.target());
        assertEquals("ROW_FILTER", compiled.effect());
        assertEquals("region = 'CN'", compiled.metadata().get("rowFilter"));
        // 产物必须带出处：下发的谓词要能被人审计
        assertTrue(compiled.artifact().contains("策略：region_filter（v3）"), compiled.artifact());
        assertTrue(compiled.artifact().contains("region = 'CN'"), compiled.artifact());
    }

    @Test
    void trinoRowFilterSupportsTheDocumentedOperators() {
        assertEquals("amount > 100", PolicyCompiler.rowPredicate(Map.of("column", "amount", "operator", ">", "value", 100)));
        assertEquals("status IN ('PAID', 'REFUNDED')",
                PolicyCompiler.rowPredicate(Map.of("column", "status", "operator", "IN",
                        "value", List.of("PAID", "REFUNDED"))));
        assertEquals("deleted_at IS NULL",
                PolicyCompiler.rowPredicate(Map.of("column", "deleted_at", "operator", "IS_NULL")));
        assertEquals("owner = CURRENT_USER",
                PolicyCompiler.rowPredicate(Map.of("column", "owner", "operator", "CURRENT_USER")));
    }

    @Test
    void stringLiteralsAreEscapedInPredicates() {
        assertEquals("name = 'it''s'",
                PolicyCompiler.rowPredicate(Map.of("column", "name", "operator", "=", "value", "it's")));
    }

    /** 不支持的自定义表达式必须被拒绝：那是代码执行入口，不是"灵活性"。 */
    @Test
    void unknownOperatorIsRejected() {
        AccessPolicyException error = assertThrows(AccessPolicyException.class,
                () -> PolicyCompiler.rowPredicate(Map.of("column", "a", "operator", "LIKE_ANY", "value", "x")));
        assertTrue(error.getMessage().contains("不支持的行过滤运算符"));
    }

    @Test
    void trinoColumnMaskProducesExpression() {
        PolicyCompiler.PolicySpec spec = new PolicyCompiler.PolicySpec("mask_id_card", 1, "trino",
                "COLUMN_MASK", Map.of("prefixes", List.of("urn:dg:Dataset:prod.")),
                Map.of("roles", List.of("SUPPORT")),
                Map.of("column", "id_card", "mask", "partial"), 20);
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(spec);
        assertEquals("COLUMN_MASK", compiled.effect());
        assertTrue(String.valueOf(compiled.metadata().get("columnMask")).contains("substr"), compiled.artifact());
        assertTrue(compiled.artifact().contains("id_card"), compiled.artifact());
    }

    @Test
    void maskKindsAreClosed() {
        assertEquals("CAST(NULL AS VARCHAR)",
                PolicyCompiler.maskExpression(Map.of("column", "c", "mask", "null")));
        assertTrue(PolicyCompiler.maskExpression(Map.of("column", "c", "mask", "hash")).contains("sha256"));
        assertEquals("'***'", PolicyCompiler.maskExpression(Map.of("column", "c", "mask", "constant", "value", "***")));
        assertThrows(AccessPolicyException.class,
                () -> PolicyCompiler.maskExpression(Map.of("column", "c", "mask", "base64_and_reverse")));
    }

    /** 数仓产物必须同时给出回收路径：只发 GRANT 不发 REVOKE 就是"永久权限"。 */
    @Test
    void warehouseGrantIncludesRevokePath() {
        PolicyCompiler.PolicySpec spec = new PolicyCompiler.PolicySpec("grant_dw_orders", 2, "warehouse", "GRANT",
                Map.of("table", "dw.orders"), Map.of("roles", List.of("ROLE_ANALYST"), "permissions", List.of("SELECT")),
                Map.of(), 30);
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(spec);
        assertTrue(compiled.artifact().contains("GRANT SELECT ON TABLE dw.orders TO ROLE ROLE_ANALYST;"),
                compiled.artifact());
        assertTrue(compiled.artifact().contains("REVOKE SELECT ON TABLE dw.orders FROM ROLE ROLE_ANALYST;"),
                compiled.artifact());
        assertTrue(compiled.artifact().contains("到期回收由平台驱动"), compiled.artifact());
    }

    /** URN 前缀不能用来猜表名 —— 猜错表名就是给错表授权。 */
    @Test
    void warehouseRequiresExplicitTable() {
        PolicyCompiler.PolicySpec spec = new PolicyCompiler.PolicySpec("bad", 1, "warehouse", "GRANT",
                Map.of("prefixes", List.of("urn:dg:Dataset:prod.")),
                Map.of("roles", List.of("R"), "permissions", List.of("SELECT")), Map.of(), 1);
        AccessPolicyException error = assertThrows(AccessPolicyException.class, () -> PolicyCompiler.compile(spec));
        assertTrue(error.getMessage().contains("不允许从 URN 前缀猜表名"), error.getMessage());
    }

    @Test
    void warehouseRowFilterProducesRowAccessPolicy() {
        PolicyCompiler.PolicySpec spec = new PolicyCompiler.PolicySpec("rap_region", 1, "warehouse", "ROW_FILTER",
                Map.of("table", "dw.orders"), Map.of("roles", List.of("ROLE_CN")),
                Map.of("column", "region", "operator", "=", "value", "CN"), 40);
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(spec);
        assertTrue(compiled.artifact().contains("ROW ACCESS POLICY"), compiled.artifact());
        assertTrue(compiled.artifact().contains("ALTER TABLE dw.orders ADD ROW ACCESS POLICY"), compiled.artifact());
    }

    /** BI 与 SDK 产物必须写明"不是安全边界"，否则会被当成防线。 */
    @Test
    void biAndSdkArtifactsDeclareTheyAreNotSecurityBoundaries() {
        PolicyCompiler.PolicySpec bi = new PolicyCompiler.PolicySpec("bi_hide", 1, "bi", "COLUMN_MASK",
                Map.of("prefixes", List.of("urn:dg:Dashboard:prod.")), Map.of("roles", List.of("VIEWER")),
                Map.of("column", "customer_name"), 50);
        PolicyCompiler.CompiledPolicy compiledBi = PolicyCompiler.compile(bi);
        assertTrue(compiledBi.artifact().contains("不是安全边界"), compiledBi.artifact());
        assertEquals(false, compiledBi.metadata().get("isSecurityBoundary"));
        assertTrue(compiledBi.artifact().contains("hidden_columns: [customer_name]"), compiledBi.artifact());

        PolicyCompiler.PolicySpec sdk = new PolicyCompiler.PolicySpec("sdk_scope", 1, "sdk", "GRANT",
                Map.of("prefixes", List.of("urn:dg:Dataset:prod."), "classification", List.of("L1", "L2")),
                Map.of("roles", List.of("APP"), "permissions", List.of("READ")), Map.of(), 60);
        PolicyCompiler.CompiledPolicy compiledSdk = PolicyCompiler.compile(sdk);
        assertTrue(compiledSdk.artifact().contains("不可作为唯一防线"), compiledSdk.artifact());
        assertTrue(compiledSdk.artifact().contains("dg_access"), compiledSdk.artifact());
    }

    /** 非法策略必须被拒绝，且错误信息要能指导修正。 */
    @Test
    void invalidPoliciesAreRejectedWithActionableMessages() {
        PolicyCompiler.PolicySpec noScope = new PolicyCompiler.PolicySpec("x", 1, "trino", "ROW_FILTER",
                Map.of(), Map.of("roles", List.of("R")), Map.of("column", "c"), 1);
        assertTrue(noScope.validate().stream().anyMatch(e -> e.contains("资源范围不能为空")));

        PolicyCompiler.PolicySpec noSubject = new PolicyCompiler.PolicySpec("x", 1, "trino", "ROW_FILTER",
                Map.of("prefix", "urn:dg:"), Map.of(), Map.of("column", "c"), 1);
        assertTrue(noSubject.validate().stream().anyMatch(e -> e.contains("主体范围不能为空")));

        PolicyCompiler.PolicySpec badTarget = new PolicyCompiler.PolicySpec("x", 1, "hive", "ROW_FILTER",
                Map.of("prefix", "urn:dg:"), Map.of("roles", List.of("R")), Map.of("column", "c"), 1);
        assertTrue(badTarget.validate().stream().anyMatch(e -> e.contains("不支持的目标引擎")));
    }

    @Test
    void identifiersAreWhitelistedNotEscaped() {
        assertThrows(AccessPolicyException.class, () -> PolicyCompiler.rowPredicate(
                Map.of("column", "a; DROP TABLE x", "operator", "=", "value", 1)));
    }

    /** 覆盖率判定与编译共用同一套资源范围语义（否则"声称覆盖"与"实际覆盖"会分叉）。 */
    @Test
    void coverageMatchesResourceScope() {
        PolicyCompiler.PolicySpec spec = new PolicyCompiler.PolicySpec("cover", 1, "trino", "ROW_FILTER",
                Map.of("prefixes", List.of("urn:dg:Dataset:prod.postgresql.dw."),
                        "classification", List.of("L3", "L4")),
                Map.of("roles", List.of("R")), Map.of("column", "region", "operator", "=", "value", "CN"), 1);
        assertTrue(PolicyCompiler.coversDataset(spec, "urn:dg:Dataset:prod.postgresql.dw.orders", "L3", null));
        assertFalse(PolicyCompiler.coversDataset(spec, "urn:dg:Dataset:prod.postgresql.dw.orders", "L1", null),
                "分级不匹配不算覆盖");
        assertFalse(PolicyCompiler.coversDataset(spec, "urn:dg:Dataset:dev.sqlite.x.orders", "L3", null),
                "前缀不匹配不算覆盖");
    }

    /**
     * 没有资源选择器的策略不能声称覆盖任何数据集。
     *
     * <p>这是实测发现过的缺陷：只有 {@code table} 的策略（数仓目标）会跳过所有前缀判定，
     * 于是"覆盖率 100%"——而它其实只覆盖了一张物理表。假的好数字比没有数字更危险。
     */
    @Test
    void scopesWithoutDatasetSelectorDoNotClaimCoverage() {
        PolicyCompiler.PolicySpec tableOnly = new PolicyCompiler.PolicySpec("t", 1, "warehouse", "GRANT",
                Map.of("table", "dw.orders"), Map.of("roles", List.of("R"), "permissions", List.of("SELECT")),
                Map.of(), 1);
        assertFalse(PolicyCompiler.coversDataset(tableOnly, "urn:dg:Dataset:prod.postgresql.dw.orders", "L2", null),
                "只有 table 的策略不参与目录覆盖率（同名表可能存在于多个命名空间）");

        // 加上前缀选择器后，同一张表才算被覆盖
        PolicyCompiler.PolicySpec scoped = new PolicyCompiler.PolicySpec("t2", 1, "warehouse", "GRANT",
                Map.of("table", "dw.orders", "prefixes", List.of("urn:dg:Dataset:prod.postgresql.")),
                Map.of("roles", List.of("R"), "permissions", List.of("SELECT")), Map.of(), 1);
        assertTrue(PolicyCompiler.coversDataset(scoped, "urn:dg:Dataset:prod.postgresql.dw.orders", "L2", null));
        assertFalse(PolicyCompiler.coversDataset(scoped, "urn:dg:Dataset:prod.postgresql.dw.customers", "L2", null));
        assertFalse(PolicyCompiler.coversDataset(scoped, "urn:dg:Dataset:dev.sqlite.dw.orders", "L2", null),
                "前缀不匹配时即使表名相同也不算覆盖");

        PolicyCompiler.PolicySpec noSelector = new PolicyCompiler.PolicySpec("n", 1, "sdk", "GRANT",
                Map.of("note", "只有说明没有选择器"), Map.of("roles", List.of("R"), "permissions", List.of("READ")),
                Map.of(), 1);
        assertFalse(PolicyCompiler.coversDataset(noSelector, "urn:dg:Dataset:any", "L2", null),
                "没有任何资源选择器时不能声称覆盖");
    }
}
