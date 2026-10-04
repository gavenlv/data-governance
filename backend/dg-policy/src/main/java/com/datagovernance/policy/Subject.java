package com.datagovernance.policy;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 调用主体（用户或服务账号）。
 *
 * <p>与 {@code dg-api} 的安全层解耦：dg-api 从令牌/OIDC 解析出身份后构造本对象，
 * 策略判定只依赖它 —— 这样换认证方式（静态令牌 → OIDC/JWKS）不需要改授权逻辑。
 */
public record Subject(String id, String name, Set<String> roles) {

    public Subject {
        roles = java.util.Collections.unmodifiableSet(new TreeSet<>(Roles.normalize(roles)));
        id = id == null || id.isBlank() ? "anonymous" : id;
        name = name == null || name.isBlank() ? id : name;
    }

    public static Subject of(String id, String name, java.util.Collection<String> roles) {
        Set<String> raw = roles == null ? Set.of() : new LinkedHashSet<>(roles);
        return new Subject(id, name, raw);
    }

    /** 匿名主体（无角色，只能看公开级）。 */
    public static Subject anonymous() {
        return new Subject("anonymous", "匿名", Set.of());
    }

    public boolean isAnonymous() {
        return "anonymous".equals(id);
    }

    public String display() {
        return name;
    }
}
