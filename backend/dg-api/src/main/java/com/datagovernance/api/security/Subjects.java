package com.datagovernance.api.security;

import java.util.Set;

import com.datagovernance.policy.AccessDenied;
import com.datagovernance.policy.Subject;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把安全层的身份映射为策略层的 {@link Subject}（docs/09 §9.7）。
 *
 * <p>这层薄适配是刻意保留的：认证方式（静态令牌 → OIDC/JWKS）会变，
 * 但授权判定只认 {@link Subject}，因此换认证不需要改授权逻辑。
 */
public final class Subjects {

    private Subjects() {
    }

    /** 当前主体；未认证时返回 null（由 {@link #require()} 决定是否拒绝）。 */
    public static Subject current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SecurityConfig.Principal principal)) {
            return null;
        }
        Set<String> roles = principal.roles() == null ? Set.of() : principal.roles();
        return Subject.of(principal.id(), principal.name(), roles);
    }

    /**
     * 当前主体，未认证则抛 {@link AccessDenied}。
     *
     * <p>注意：{@code /api/**} 在安全层已要求认证，所以正常路径下这里不会触发；
     * 保留它是为了即使有人放宽了安全层匹配，授权也仍然是否决式的（fail closed）。
     */
    public static Subject require() {
        Subject subject = current();
        if (subject == null) {
            throw new AccessDenied("未认证：无法判定权限（授权默认拒绝）");
        }
        return subject;
    }
}
