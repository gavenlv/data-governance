package com.datagovernance.api.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动校验：**危险配置必须让控制面起不来**。
 *
 * <p>这个测试集合的存在理由，见 {@link AuthStartupValidator} 的类注释：
 * 一个"配置错了但能启动"的认证，最终会以"用开发令牌跑生产"的形式出现在事故报告里。
 */
class AuthStartupValidatorTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return environment;
    }

    private static AuthProperties staticWith(String token, String id) {
        AuthProperties properties = new AuthProperties();
        properties.setMode("static");
        AuthProperties.TokenEntry entry = new AuthProperties.TokenEntry();
        entry.setToken(token);
        entry.setId(id);
        entry.setRoles(List.of("ADMIN"));
        properties.setTokens(List.of(entry));
        return properties;
    }

    @Test
    void rejectsUnknownMode() {
        AuthProperties properties = new AuthProperties();
        properties.setMode("oauth2");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env()));
        assertTrue(error.getMessage().contains("static | jwt | disabled"),
                "报错要列出合法取值，否则只能靠猜");
    }

    @Test
    void rejectsDisabledModeInProduction() {
        AuthProperties properties = new AuthProperties();
        properties.setMode("disabled");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env("prod")));
        assertTrue(error.getMessage().contains("disabled"));
        assertDoesNotThrow(() -> AuthStartupValidator.validate(properties, env()),
                "开发环境允许关闭认证（否则本机开发要先架 IdP）");
    }

    @Test
    void rejectsBuiltinDevTokensInProduction() {
        for (String token : AuthStartupValidator.BUILTIN_DEV_TOKENS) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> AuthStartupValidator.validate(staticWith(token, "admin@local"), env("prod")),
                    "内置开发令牌 " + token + " 不允许出现在生产");
            assertTrue(error.getMessage().contains("dev-") || error.getMessage().contains("内置开发令牌"));
        }
    }

    @Test
    void rejectsTooShortStaticTokenInProduction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(staticWith("short-token", "alice@corp"), env("prod")));
        assertTrue(error.getMessage().contains("过短"));
    }

    @Test
    void rejectsStaticTokenWithoutRealIdentityInProduction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(
                        staticWith("0123456789abcdef0123456789abcdef", "  "), env("prod")));
        assertTrue(error.getMessage().contains("id"),
                "生产静态令牌必须绑定真实身份，否则审计回答不了「是谁做的」");
    }

    @Test
    void acceptsLongStaticTokenWithIdentityInProduction() {
        assertDoesNotThrow(() -> AuthStartupValidator.validate(
                staticWith("s3cr3t-token-with-enough-entropy-32", "alice@corp"), env("prod")));
    }

    @Test
    void rejectsEmptyStaticTokenTable() {
        AuthProperties properties = new AuthProperties();
        properties.setMode("static");
        assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env()));
    }

    @Test
    void jwtRequiresIssuer() {
        AuthProperties properties = jwtProperties();
        properties.getJwt().setIssuer("  ");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env()));
        assertTrue(error.getMessage().contains("issuer"));
    }

    @Test
    void jwtRequiresJwksSource() {
        AuthProperties properties = jwtProperties();
        properties.getJwt().setJwksJson(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env()));
        assertTrue(error.getMessage().contains("公钥来源"));
    }

    @Test
    void jwtRequiresAudienceInProductionButNotInDevelopment() {
        AuthProperties properties = jwtProperties();
        properties.getJwt().setAudience("");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env("prod")));
        assertTrue(error.getMessage().contains("audience"));
        assertDoesNotThrow(() -> AuthStartupValidator.validate(properties, env()),
                "开发环境只告警：本机往往就是单应用");
    }

    @Test
    void rejectsSymmetricAlgorithmAtStartup() {
        AuthProperties properties = jwtProperties();
        properties.getJwt().setAlgorithms(List.of("HS256"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthStartupValidator.validate(properties, env()));
        assertTrue(error.getMessage().contains("对称算法"));
    }

    @Test
    void acceptsValidJwtConfiguration() {
        AuthProperties properties = jwtProperties();
        assertDoesNotThrow(() -> AuthStartupValidator.validate(properties, env("prod")));
        assertNotNull(AuthStartupValidator.describe(properties));
    }

    @Test
    void describeNeverLeaksSecrets() {
        AuthProperties properties = staticWith("super-secret-token-value", "alice@corp");
        String described = String.valueOf(AuthStartupValidator.describe(properties));
        assertTrue(!described.contains("super-secret-token-value"),
                "排障摘要里不能出现令牌内容：日志与诊断接口同样是泄漏面");
    }

    private static AuthProperties jwtProperties() {
        AuthProperties properties = new AuthProperties();
        properties.setMode("jwt");
        properties.getJwt().setIssuer(JwtTestSupport.ISSUER);
        properties.getJwt().setAudience(JwtTestSupport.AUDIENCE);
        try {
            properties.getJwt().setJwksJson(
                    JwtTestSupport.publicJwks(JwtTestSupport.rsaKey("startup-key")));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
