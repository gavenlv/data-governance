package com.datagovernance.api.security;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import com.datagovernance.policy.Roles;

/**
 * 认证成功后的主体（认证层的输出、授权层的输入）。
 *
 * <p>角色在构造时就走 {@link Roles#normalize}：**未知角色一律丢弃**。
 * 这条规则来自 docs/09 §9.7 —— 保留来自 IdP 的任意角色名，等于让"给用户加一个
 * 拼错的组"变成静默的权限变化；丢弃后最坏是权限不足（403），不会是多出来的权限。
 *
 * <p>放在记录里而不是散落在各认证实现里，是因为静态令牌与 JWT 两条认证路径
 * 必须产出**同一种**主体；否则"换了认证方式，授权行为也跟着变"会非常难查。
 */
public record Principal(String id, String name, Set<String> roles) {

    public Principal {
        id = id == null || id.isBlank() ? "unknown" : id;
        name = name == null ? "" : name;
        roles = Collections.unmodifiableSet(new TreeSet<>(Roles.normalize(roles)));
    }

    /** ADMIN 隐含所有角色（与 Python 参考实现的 normalize_roles 语义一致）。 */
    public boolean hasRole(String role) {
        return roles.contains(Roles.ADMIN) || roles.contains(role);
    }

    /** 展示名：没有姓名时回退到身份标识，避免界面出现空白的主体。 */
    public String displayName() {
        return name.isBlank() ? id : name;
    }
}
