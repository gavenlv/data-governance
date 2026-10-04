package com.datagovernance.policy;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 角色、权限点与分级上限（docs/09 §9.7 的三层权限模型，v1 落地前两层）。
 *
 * <pre>
 *   ┌ 平台功能权限   RBAC    谁能调哪个 API / 执行哪个动作
 *   ├ 资产可见性     ABAC    按分类分级（L1–L4）过滤可见资产
 *   └ 数据行/列访问  ——      v1 不做（属 Phase 3 的策略下发，见 ADR-008）
 * </pre>
 *
 * <p>本类的映射表与 Python 参考实现（{@code src/dg/auth/principal.py}）保持一致，
 * 仅新增两个本批新增接口所需的权限点（{@code schedule:write} / {@code index:rebuild}），
 * 差异在此显式记录而不是悄悄扩大授权面。
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String EDITOR = "EDITOR";
    public static final String STEWARD = "STEWARD";
    public static final String READER = "READER";

    /** 全部已知角色。未知角色一律丢弃（拒绝静默提权）。 */
    public static final Set<String> ALL = Set.of(ADMIN, EDITOR, STEWARD, READER);

    /** 分级由低到高（docs/18 §2）。 */
    public static final List<String> CLASSIFICATION_ORDER = List.of("L1", "L2", "L3", "L4");

    /** 未分级资产按 L2 处理（保守默认，docs/18 §2）。 */
    public static final String DEFAULT_CLASSIFICATION = "L2";

    private static final Set<String> READ_BASIC = Set.of("asset:read", "lineage:read", "model:read");

    private static final java.util.Map<String, Set<String>> ROLE_PERMISSIONS = java.util.Map.of(
            ADMIN, Set.of("*"),
            EDITOR, Set.of("asset:read", "asset:write", "lineage:read", "lineage:write",
                    "model:read", "collect:run", "index:consume"),
            STEWARD, Set.of("asset:read", "asset:write", "lineage:read", "lineage:write",
                    "model:read", "governance:write", "index:consume",
                    "schedule:write", "index:rebuild"),
            READER, READ_BASIC);

    private static final java.util.Map<String, String> ROLE_MAX_CLASSIFICATION = java.util.Map.of(
            ADMIN, "L4",
            STEWARD, "L4",
            EDITOR, "L3",
            READER, "L3");

    private Roles() {
    }

    /**
     * 规范化角色集合：转大写、去空白、**丢弃未知角色**。
     *
     * <p>丢弃而不是保留是关键：保留未知角色会让 "ADMIN "（带空格）或拼错的角色名
     * 在后续判定里变成难以察觉的静默降权/提权来源。
     */
    public static Set<String> normalize(java.util.Collection<String> raw) {
        Set<String> normalized = new TreeSet<>();
        if (raw == null) {
            return normalized;
        }
        for (String role : raw) {
            if (role == null) {
                continue;
            }
            String value = role.trim().toUpperCase(Locale.ROOT);
            if (ALL.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    public static Set<String> permissionsOf(Set<String> roles) {
        Set<String> permissions = new TreeSet<>();
        for (String role : roles) {
            permissions.addAll(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return permissions;
    }

    public static String maxClassificationOf(Set<String> roles) {
        String top = null;
        for (String role : roles) {
            String level = ROLE_MAX_CLASSIFICATION.get(role);
            if (level == null) {
                continue;
            }
            if (top == null || CLASSIFICATION_ORDER.indexOf(level) > CLASSIFICATION_ORDER.indexOf(top)) {
                top = level;
            }
        }
        return top;
    }

    /** 权限点声明（用于 /api/v1/capabilities 与排障输出）。 */
    public static java.util.Map<String, Set<String>> rolePermissions() {
        return ROLE_PERMISSIONS;
    }

    public static java.util.Map<String, String> roleMaxClassification() {
        return ROLE_MAX_CLASSIFICATION;
    }
}
