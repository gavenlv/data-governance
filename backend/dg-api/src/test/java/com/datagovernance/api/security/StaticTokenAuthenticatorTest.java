package com.datagovernance.api.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 静态令牌模式（开发形态）的回归测试：换认证方式不能把开发体验弄坏。 */
class StaticTokenAuthenticatorTest {

    private static AuthProperties properties(AuthProperties.TokenEntry... entries) {
        AuthProperties properties = new AuthProperties();
        properties.setMode("static");
        properties.setTokens(List.of(entries));
        return properties;
    }

    private static AuthProperties.TokenEntry entry(String token, String id, List<String> roles) {
        AuthProperties.TokenEntry entry = new AuthProperties.TokenEntry();
        entry.setToken(token);
        entry.setId(id);
        entry.setName("测试主体");
        entry.setRoles(roles);
        return entry;
    }

    @Test
    void authenticatesConfiguredToken() {
        StaticTokenAuthenticator authenticator = new StaticTokenAuthenticator(properties(
                entry("dev-admin-token", "admin@local", List.of("ADMIN"))));

        Principal principal = authenticator.authenticate("dev-admin-token").orElseThrow();
        assertEquals("admin@local", principal.id());
        assertEquals("测试主体", principal.name());
        assertEquals(java.util.Set.of("ADMIN"), principal.roles());
    }

    @Test
    void rejectsUnknownToken() {
        StaticTokenAuthenticator authenticator = new StaticTokenAuthenticator(properties(
                entry("dev-admin-token", "admin@local", List.of("ADMIN"))));

        assertTrue(authenticator.authenticate("dev-admin-toke").isEmpty());
        assertTrue(authenticator.authenticate("").isEmpty());
        assertTrue(authenticator.authenticate(null).isEmpty());
    }

    @Test
    void dropsUnknownRoles() {
        StaticTokenAuthenticator authenticator = new StaticTokenAuthenticator(properties(
                entry("token-value", "alice@corp", List.of("ADMIN", "NOT_A_ROLE"))));

        Principal principal = authenticator.authenticate("token-value").orElseThrow();
        assertEquals(java.util.Set.of("ADMIN"), principal.roles(),
                "未知道具角色必须丢弃（拒绝静默提权）");
    }

    @Test
    void rejectsDuplicateTokens() {
        AuthProperties properties = properties(
                entry("same-token", "alice@corp", List.of("ADMIN")),
                entry("same-token", "bob@corp", List.of("READER")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new StaticTokenAuthenticator(properties));
        assertTrue(error.getMessage().contains("重复令牌"));
    }

    @Test
    void rejectsBlankToken() {
        AuthProperties properties = properties(entry("  ", "alice@corp", List.of("ADMIN")));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new StaticTokenAuthenticator(properties));
        assertTrue(error.getMessage().contains("空令牌"));
    }

    @Test
    void generatesStableIdentifierWhenIdMissing() {
        StaticTokenAuthenticator authenticator = new StaticTokenAuthenticator(properties(
                entry("token-without-id", "  ", List.of("READER"))));

        String first = authenticator.authenticate("token-without-id").orElseThrow().id();
        String second = authenticator.authenticate("token-without-id").orElseThrow().id();
        assertTrue(first.startsWith("token:"), "没有配置 id 时要生成可读标识：" + first);
        assertEquals(first, second, "同一令牌每次生成的标识必须稳定，否则审计无法关联");
    }
}
