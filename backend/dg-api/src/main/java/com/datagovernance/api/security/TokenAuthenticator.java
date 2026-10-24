package com.datagovernance.api.security;

import java.util.Optional;

/**
 * 令牌 → 主体 的唯一入口。
 *
 * <p>抽出接口是为了让 {@link SecurityConfig} 只关心"拿到一个主体或没有"，
 * 而静态令牌、JWT/JWKS 的差异封在各自实现里。这样换认证方式时，
 * 授权代码（{@code Subjects}、{@code AccessPolicy}）一行都不用改 —— 见 docs/09 §9.7。
 *
 * <p>约定：**认证失败返回 {@link Optional#empty()}，不抛异常**。
 * 抛异常会让"令牌过期"这类正常事件变成 500；而 401 必须由安全层的授权决策统一给出，
 * 否则不同实现会各自决定状态码，前端无法稳定处理。
 */
public interface TokenAuthenticator {

    Optional<Principal> authenticate(String token);
}
