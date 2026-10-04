package com.datagovernance.policy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权判定测试。
 *
 * <p>这是唯一不能靠"跑一遍看看"验证的部分：授权出错不会报错，只会静默放行。
 * 因此对每条规则都写成断言。
 */
class AccessPolicyTest {

    private static final Subject ADMIN = Subject.of("a@local", "管理员", List.of("ADMIN"));
    private static final Subject STEWARD = Subject.of("s@local", "数据管家", List.of("STEWARD"));
    private static final Subject READER = Subject.of("r@local", "只读", List.of("READER"));
    private static final Subject NOBODY = Subject.anonymous();

    @Test
    void adminHasWildcard() {
        assertTrue(AccessPolicy.can(ADMIN, "asset:write"));
        assertTrue(AccessPolicy.can(ADMIN, "index:rebuild"));
        assertTrue(AccessPolicy.can(ADMIN, "任何未来新增的权限点"));
    }

    @Test
    void readerCanOnlyRead() {
        assertTrue(AccessPolicy.can(READER, "asset:read"));
        assertTrue(AccessPolicy.can(READER, "lineage:read"));
        assertFalse(AccessPolicy.can(READER, "asset:write"));
        assertFalse(AccessPolicy.can(READER, "index:rebuild"));
    }

    @Test
    void stewardCanWriteGovernanceButEditorCannotGovernance() {
        assertTrue(AccessPolicy.can(STEWARD, "governance:write"));
        assertTrue(AccessPolicy.can(STEWARD, "schedule:write"));
        assertEquals(false, AccessPolicy.can(Subject.of("e@local", "编辑", List.of("EDITOR")), "governance:write"));
    }

    /** 未知角色必须被丢弃：否则拼错的角色名会成为难以察觉的提权/降权来源。 */
    @Test
    void unknownRolesAreDropped() {
        Subject typo = Subject.of("x@local", "拼错角色", List.of("SUPERUSER", "ROOT", "admn"));
        assertTrue(typo.roles().isEmpty(), "未知角色必须全部丢弃");
        assertTrue(AccessPolicy.permissions(typo).isEmpty());
        assertFalse(AccessPolicy.can(typo, "asset:read"), "无有效角色 = 无权限（默认拒绝）");
    }

    /** 大小写与空白应被规范化，而不是变成"另一个未知角色"。 */
    @Test
    void roleNamesAreNormalized() {
        Subject subject = Subject.of("x@local", "大小写", List.of(" admin ", "steward"));
        assertEquals(Set.of("ADMIN", "STEWARD"), subject.roles());
    }

    @Test
    void authorizeThrowsAccessDeniedWithReadableMessage() {
        AccessDenied error = assertThrows(AccessDenied.class,
                () -> AccessPolicy.authorize(READER, "asset:write"));
        assertTrue(error.reason().contains("asset:write"));
        assertTrue(error.reason().contains("READER"));
    }

    @Test
    void visibleLevelsFollowRoleCeiling() {
        assertEquals(List.of("L1", "L2", "L3", "L4"), AccessPolicy.visibleLevels(ADMIN));
        assertEquals(List.of("L1", "L2", "L3"), AccessPolicy.visibleLevels(READER));
        // 无角色 → 只能看公开级，但默认级 L2 必须在内（未分级资产按 L2 处理）
        assertEquals(List.of("L1", "L2"), AccessPolicy.visibleLevels(NOBODY));
    }

    @Test
    void classificationVisibilityIsDenyByDefault() {
        assertTrue(AccessPolicy.canSeeClassification(READER, "L3"));
        assertFalse(AccessPolicy.canSeeClassification(READER, "L4"));
        // 未分级资产按 L2 处理（保守默认）
        assertTrue(AccessPolicy.canSeeClassification(NOBODY, null));
        assertThrows(AccessDenied.class, () -> AccessPolicy.ensureVisible(READER, "L4"));
    }

    /** 可见性条件必须是"占位符 + 参数"，且级别集合来自服务端常量而非用户输入。 */
    @Test
    void visibilityFilterIsParameterized() {
        AccessPolicy.VisibilityFilter filter = AccessPolicy.visibilityFilter(READER);
        assertEquals("COALESCE(classification, 'L2') IN (?, ?, ?)", filter.clause());
        assertEquals(List.of("L1", "L2", "L3"), filter.levels());
        assertEquals(3, filter.params().length);
        assertEquals("L3", filter.maxVisibleLevel());
    }

    @Test
    void describeExposesWhatSubjectCanDo() {
        var described = AccessPolicy.describe(READER);
        assertEquals("r@local", described.get("id"));
        assertTrue(described.get("permissions").toString().contains("asset:read"));
        assertEquals("L3", described.get("maxVisibleLevel"));
    }

    @Test
    void rolesWithNoMatchHaveNoPermissions() {
        assertTrue(AccessPolicy.permissions(NOBODY).isEmpty());
        assertFalse(AccessPolicy.can(NOBODY, "asset:read"));
    }

    @Test
    void adminCheckHelpersIgnoreOtherRoles() {
        assertTrue(AccessPolicy.hasRole(ADMIN, "STEWARD"));
        assertTrue(AccessPolicy.hasRole(STEWARD, "STEWARD"));
        assertFalse(AccessPolicy.hasRole(READER, "STEWARD"));
    }

    @Test
    void roleTablesAreExposedForDiagnostics() {
        assertEquals(Set.of("ADMIN", "EDITOR", "STEWARD", "READER"), Roles.ALL);
        assertTrue(Roles.rolePermissions().get("ADMIN").contains("*"));
    }
}
