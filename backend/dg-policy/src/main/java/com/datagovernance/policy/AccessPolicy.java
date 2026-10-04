package com.datagovernance.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权判定的<b>唯一入口</b>（docs/09 §9.7、§9.3 的安全底线）。
 *
 * <p>搜索、资产详情、写入、导出、AI 上下文裁剪<b>必须全部调用这里</b>。
 * 任何一处用另一套判定就是越权漏洞 —— 这类漏洞不会报错，只会静默泄露。
 *
 * <p>两级判定：
 * <ul>
 *   <li><b>RBAC</b> 权限点：{@code asset:read} / {@code asset:write} / {@code collect:run} …</li>
 *   <li><b>ABAC</b> 分级可见性：L1–L4，用于资产过滤与访问拒绝</li>
 * </ul>
 *
 * <p>无状态、纯函数，便于单测覆盖 —— 授权逻辑是唯一不能靠"跑一遍看看"来验证的部分。
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    // ------------------------------------------------------------------ RBAC

    public static Set<String> permissions(Subject subject) {
        return Roles.permissionsOf(subject.roles());
    }

    public static boolean can(Subject subject, String permission) {
        Set<String> permissions = permissions(subject);
        return permissions.contains("*") || permissions.contains(permission);
    }

    /** 权限点不足时抛 {@link AccessDenied}。 */
    public static void authorize(Subject subject, String permission) {
        if (!can(subject, permission)) {
            throw new AccessDenied("缺少权限 " + permission
                    + "（当前角色：" + (subject.roles().isEmpty() ? "无" : String.join(",", subject.roles())) + "）");
        }
    }

    public static boolean hasRole(Subject subject, String role) {
        return subject.roles().contains(Roles.ADMIN) || subject.roles().contains(role);
    }

    // ------------------------------------------------------------------ ABAC

    /** 该主体可见的最高分级；无角色 → L1（只能看公开级）。 */
    public static String maxVisibleLevel(Subject subject) {
        String top = Roles.maxClassificationOf(subject.roles());
        return top == null ? "L1" : top;
    }

    /**
     * 可见分级列表（含默认级）。
     *
     * <p>搜索结果过滤与详情访问判定<b>都以此为准</b> —— 保证两者永不冲突
     * （"搜得到但打不开"或"搜不到但知道 URN 就能看"都是同一类 bug）。
     */
    public static List<String> visibleLevels(Subject subject) {
        String top = maxVisibleLevel(subject);
        List<String> allowed = new ArrayList<>(
                Roles.CLASSIFICATION_ORDER.subList(0, Roles.CLASSIFICATION_ORDER.indexOf(top) + 1));
        if (!allowed.contains(Roles.DEFAULT_CLASSIFICATION)) {
            allowed.add(Roles.DEFAULT_CLASSIFICATION);
        }
        return List.copyOf(allowed);
    }

    public static boolean canSeeClassification(Subject subject, String classification) {
        String level = classification == null || classification.isBlank()
                ? Roles.DEFAULT_CLASSIFICATION
                : classification.trim().toUpperCase(java.util.Locale.ROOT);
        return visibleLevels(subject).contains(level);
    }

    /** 资产级可见性判定；不可见时抛 {@link AccessDenied}。 */
    public static void ensureVisible(Subject subject, String classification) {
        if (!canSeeClassification(subject, classification)) {
            String level = classification == null || classification.isBlank()
                    ? Roles.DEFAULT_CLASSIFICATION : classification;
            throw new AccessDenied("资产分级 " + level + " 超出你的可见范围（最高可见 "
                    + maxVisibleLevel(subject) + "）");
        }
    }

    /**
     * 搜索可见性过滤条件 —— 用于<b>前置</b>注入 SQL。
     *
     * <p>为什么必须前置（docs/09 §9.3）：结果后过滤会泄露总数与分面统计，
     * 用户能看到"共 132 条结果，其中 47 条你无权查看" —— 这本身就泄露了资产的存在性与规模。
     *
     * <p>返回值故意做成「占位符 + 参数」而不是拼字符串：级别集合来自服务端常量，
     * 但仍不把任何值拼进 SQL，避免以后有人把用户输入接进来。
     *
     * @param classificationColumn 分级列名（默认 {@code classification}）
     */
    public static VisibilityFilter visibilityFilter(Subject subject, String classificationColumn) {
        List<String> levels = visibleLevels(subject);
        String column = classificationColumn == null ? "classification" : classificationColumn;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < levels.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String clause = "COALESCE(" + column + ", '" + Roles.DEFAULT_CLASSIFICATION + "') IN ("
                + placeholders + ")";
        return new VisibilityFilter(clause, levels, maxVisibleLevel(subject));
    }

    public static VisibilityFilter visibilityFilter(Subject subject) {
        return visibilityFilter(subject, "classification");
    }

    /** 可直接放入 WHERE 的可见性条件。 */
    public record VisibilityFilter(String clause, List<String> levels, String maxVisibleLevel) {
        public Object[] params() {
            return levels.toArray();
        }
    }

    /** 排障输出：让调用者看清自己"能做什么、能看到什么"。 */
    public static Map<String, Object> describe(Subject subject) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", subject.id());
        payload.put("name", subject.name());
        payload.put("roles", subject.roles());
        payload.put("permissions", new java.util.TreeSet<>(permissions(subject)));
        payload.put("maxVisibleLevel", maxVisibleLevel(subject));
        payload.put("visibleLevels", visibleLevels(subject));
        payload.put("policyEntryPoint", "com.datagovernance.policy.AccessPolicy（搜索/详情/写入共用）");
        return payload;
    }
}
