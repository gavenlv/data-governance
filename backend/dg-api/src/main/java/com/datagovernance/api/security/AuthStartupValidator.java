package com.datagovernance.api.security;

import jakarta.annotation.PostConstruct;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 认证配置的启动校验（fail closed）。
 *
 * <p>为什么要在启动时抛异常，而不是运行期打一条 WARN：
 * 一个"配置错了但能启动"的认证，最可能的结局是**用开发令牌跑在生产里**。
 * 那种状态下系统看起来一切正常（能登录、能操作），直到有人发现 `dev-admin-token`
 * 也能登录生产。这类问题无法靠巡检发现，只能靠"起不来"强制暴露。
 *
 * <p>被拒绝的三种生产配置：
 * <ol>
 *   <li>{@code mode=disabled}：完全不认证；</li>
 *   <li>{@code mode=static} 且使用内置开发令牌（或令牌长度过短）：静态令牌本身不绑定
 *       真实身份、不会过期，只适合内网试用；生产至少要换成企业 IdP；</li>
 *   <li>{@code mode=jwt} 但缺少签发方 / 公钥来源 / 受众（生产），或白名单里混入对称算法。</li>
 * </ol>
 *
 * <p>非生产（dev / test）环境只告警不阻断：开发机本来就没 IdP，
 * 把开发环境也卡死只会让人去找绕过校验的办法 —— 那比放宽更危险。
 */
@Component
public class AuthStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(AuthStartupValidator.class);

    /** {@code application.yml} 里内置的开发令牌：出现在生产配置里即视为未完成迁移。 */
    public static final Set<String> BUILTIN_DEV_TOKENS =
            Set.of("dev-admin-token", "dev-steward-token", "dev-reader-token");

    /** 生产静态令牌的最短长度（字符）。低于它等于把"密钥"写成可猜的词。 */
    private static final int MIN_PROD_TOKEN_LENGTH = 16;

    private final AuthProperties properties;
    private final Environment environment;

    public AuthStartupValidator(AuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validateAtStartup() {
        validate(properties, environment);
    }

    /** 可单测的校验入口（不依赖 Spring 生命周期）。 */
    public static void validate(AuthProperties properties, Environment environment) {
        boolean prod = isProd(environment);
        String mode = properties.normalizedMode();
        switch (mode) {
            case "jwt" -> validateJwt(properties, prod);
            case "static" -> validateStatic(properties, prod);
            case "disabled" -> {
                if (prod) {
                    throw new IllegalStateException(
                            "dg.auth.mode=disabled 在生产环境被拒绝：该模式不做任何认证，"
                                    + "任何能访问端口的人都是 ADMIN。请改用 dg.auth.mode=jwt 对接企业 OIDC/SSO");
                }
                log.warn("认证已关闭（dg.auth.mode=disabled）：所有请求都按 ADMIN 处理，仅限本机开发");
            }
            default -> throw new IllegalStateException(
                    "dg.auth.mode 无效：" + properties.getMode() + "（可选 static | jwt | disabled）");
        }
    }

    private static void validateJwt(AuthProperties properties, boolean prod) {
        AuthProperties.Jwt jwt = properties.getJwt();
        if (isBlank(jwt.getIssuer())) {
            throw new IllegalStateException(
                    "dg.auth.mode=jwt 必须配置 dg.auth.jwt.issuer：不校验签发方的 JWT 等于"
                            + "任何能签发 JWT 的系统都能登录本平台");
        }
        if (isBlank(jwt.getJwksUri()) && isBlank(jwt.getJwksFile()) && isBlank(jwt.getJwksJson())) {
            throw new IllegalStateException(
                    "dg.auth.mode=jwt 必须配置公钥来源：dg.auth.jwt.jwks-uri（OIDC 常态）/"
                            + "jwks-file（内网）/ jwks-json（单机）之一");
        }
        if (isBlank(jwt.getAudience())) {
            if (prod) {
                throw new IllegalStateException(
                        "dg.auth.mode=jwt 在生产环境必须配置 dg.auth.jwt.audience："
                                + "多个应用共用一个 IdP 时，不校验受众意味着别的系统的令牌可以直接拿来调用本平台");
            }
            log.warn("dg.auth.jwt.audience 未配置：不校验受众，仅适合单应用或开发环境");
        }
        if (isBlank(jwt.getRolesClaim())) {
            throw new IllegalStateException("dg.auth.jwt.roles-claim 不能为空");
        }
        // 构造一次解码器：让非法的 JWKS（坏 JSON、只有私钥、空公钥集）与非法算法名在启动时暴露
        PlatformJwtDecoder.create(jwt);

        String source = !isBlank(jwt.getJwksUri()) ? "jwks-uri=" + jwt.getJwksUri()
                : !isBlank(jwt.getJwksFile()) ? "jwks-file=" + jwt.getJwksFile()
                : "jwks-json（内联）";
        log.info("认证模式 JWT/JWKS 已启用：issuer={} audience={} 公钥来源={} 算法={}",
                jwt.getIssuer(), isBlank(jwt.getAudience()) ? "(未校验)" : jwt.getAudience(),
                source, String.join(",", jwt.getAlgorithms()));
    }

    private static void validateStatic(AuthProperties properties, boolean prod) {
        if (properties.getTokens().isEmpty()) {
            throw new IllegalStateException(
                    "dg.auth.mode=static 但 dg.auth.tokens 为空：没有任何令牌能通过认证（全部 401）");
        }
        if (!prod) {
            log.warn("认证模式为静态令牌（共 {} 个）：仅适合开发/内网试用，生产请配置 dg.auth.mode=jwt",
                    properties.getTokens().size());
            return;
        }
        for (AuthProperties.TokenEntry entry : properties.getTokens()) {
            String token = entry.getToken() == null ? "" : entry.getToken().trim();
            if (BUILTIN_DEV_TOKENS.contains(token)) {
                throw new IllegalStateException(
                        "生产环境检测到内置开发令牌（" + token + "）：这些令牌公开在文档与测试里，"
                                + "等于没有认证。请改用 dg.auth.mode=jwt 对接企业 OIDC/SSO；"
                                + "若确需静态令牌，请换成随机长令牌（openssl rand -base64 32）并配置真实 id");
            }
            if (token.length() < MIN_PROD_TOKEN_LENGTH) {
                throw new IllegalStateException(
                        "生产环境的静态令牌过短（长度 " + token.length() + "，要求至少 "
                                + MIN_PROD_TOKEN_LENGTH + "）：短令牌可被穷举，"
                                + "且会被当成「看起来有认证」的假安全");
            }
            if (isBlank(entry.getId())) {
                throw new IllegalStateException(
                        "生产环境的静态令牌必须配置 id（真实身份，如 alice@corp）："
                                + "没有 id 的令牌让审计无法回答「是谁做的」");
            }
        }
        log.warn("生产环境仍在使用静态令牌（{} 个）：它能通过校验，但不会过期、不绑定 SSO 生命周期，"
                + "建议尽快迁移到 dg.auth.mode=jwt", properties.getTokens().size());
    }

    static boolean isProd(Environment environment) {
        return environment != null && environment.matchesProfiles("prod");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 供排障接口展示的认证方式摘要（不包含任何令牌内容）。 */
    public static Map<String, Object> describe(AuthProperties properties) {
        String mode = properties.normalizedMode();
        return switch (mode) {
            case "jwt" -> Map.of(
                    "mode", "jwt",
                    "issuer", properties.getJwt().getIssuer() == null ? "" : properties.getJwt().getIssuer(),
                    "audience", properties.getJwt().getAudience() == null ? "" : properties.getJwt().getAudience(),
                    "algorithms", properties.getJwt().getAlgorithms());
            case "static" -> Map.of("mode", "static", "tokens", properties.getTokens().size());
            default -> Map.of("mode", mode);
        };
    }
}
