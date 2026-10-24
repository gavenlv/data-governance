package com.datagovernance.api.security;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT/JWKS 认证的**攻击面测试**。
 *
 * <p>这些用例不是"把正常路径跑通"，而是逐条证明某类攻击在本平台失败。
 * 每一类都对应一个真实世界的 CVE 家族：
 * <ul>
 *   <li>{@code alg=none} / 对称算法混淆 → 2015 年以来大量 JWT 库的默认漏洞；</li>
 *   <li>不校验 iss → 任意能签 JWT 的系统都能登录；</li>
 *   <li>不校验 aud → 兄弟应用的令牌可以重放过来；</li>
 *   <li>不校验 exp/nbf → 泄漏的令牌永久有效；</li>
 *   <li>信任令牌头里的 kid 去"找"密钥 → 密钥注入。</li>
 * </ul>
 */
class JwtTokenAuthenticatorTest {

    @Test
    void acceptsValidRs256TokenAndMapsClaims() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-1");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        Optional<Principal> principal = authenticator.authenticate(
                JwtTestSupport.sign(key, "alice@corp", List.of("ADMIN", "READER")));

        assertTrue(principal.isPresent(), "合法令牌必须认证成功");
        assertEquals("alice@corp", principal.get().id(), "身份必须取 sub，而不是姓名");
        assertEquals("测试用户", principal.get().name());
        assertEquals(java.util.Set.of("ADMIN", "READER"), principal.get().roles());
        assertTrue(principal.get().hasRole("STEWARD"), "ADMIN 应隐含所有角色");
    }

    @Test
    void acceptsValidEs256Token() throws JOSEException {
        ECKey key = JwtTestSupport.ecKey("ec-1");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        Optional<Principal> principal = authenticator.authenticate(
                JwtTestSupport.sign(key, "bob@corp", List.of("STEWARD")));

        assertTrue(principal.isPresent(), "ES256 是默认白名单里的算法，必须可用");
        assertEquals(java.util.Set.of("STEWARD"), principal.get().roles());
    }

    @Test
    void dropsUnknownRolesInsteadOfGrantingThem() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-2");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        Optional<Principal> principal = authenticator.authenticate(
                JwtTestSupport.sign(key, "carol@corp", List.of("ADMIN", "SUPERUSER", " admin ")));

        assertTrue(principal.isPresent());
        assertEquals(java.util.Set.of("ADMIN"), principal.get().roles(),
                "未知角色必须丢弃；带空格的角色名应被归一化成 ADMIN 而不是当成新角色");
    }

    @Test
    void mapsExternalGroupNamesThroughAliases() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-3");
        AuthProperties.Jwt config = JwtTestSupport.config(JwtTestSupport.publicJwks(key));
        config.setRolesClaim("groups");
        config.setRoleAliases(java.util.Map.of("dg-admins", "ADMIN", "DG-Readers", "READER"));
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(config);

        JWTClaimsSet claims = JwtTestSupport.claims("dave@corp", List.of()).claim("groups", List.of("dg-admins")).build();
        Optional<Principal> principal = authenticator.authenticate(JwtTestSupport.sign(key, claims));

        assertTrue(principal.isPresent());
        assertEquals(java.util.Set.of("ADMIN"), principal.get().roles(),
                "外部组名必须经 role-aliases 映射成平台角色");
    }

    @Test
    void acceptsRolesClaimAsDelimitedString() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-4");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet claims = JwtTestSupport.claims("erin@corp", List.of())
                .claim("roles", "READER, STEWARD").build();

        Optional<Principal> principal = authenticator.authenticate(JwtTestSupport.sign(key, claims));

        assertTrue(principal.isPresent());
        assertEquals(java.util.Set.of("READER", "STEWARD"), principal.get().roles(),
                "IdP 用逗号分隔字符串表达角色是常见形态，必须支持");
    }

    @Test
    void rejectsExpiredTokenBeyondClockSkew() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-5");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet claims = JwtTestSupport.claims("frank@corp", List.of("READER"))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(1200)))
                .issueTime(Date.from(Instant.now().minusSeconds(1200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                .build();

        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, claims)).isEmpty(),
                "过期 10 分钟的令牌必须被拒绝（时钟偏移只有 60s）");
    }

    @Test
    void acceptsExpiryWithinClockSkew() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-6");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        // nbf/iat 必须早于 exp，否则令牌本身就是矛盾的（Nimbus 会直接判无效），
        // 那样测的就不是"时钟偏移"而是"令牌字段矛盾"
        JWTClaimsSet claims = JwtTestSupport.claims("grace@corp", List.of("READER"))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(600)))
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(10)))
                .build();

        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, claims)).isPresent(),
                "刚过期 10s 的令牌应落在 60s 偏移内，否则会出现间歇性 401");
    }

    @Test
    void rejectsNotYetValidToken() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-7");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet claims = JwtTestSupport.claims("henry@corp", List.of("READER"))
                .notBeforeTime(Date.from(Instant.now().plusSeconds(300)))
                .build();

        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, claims)).isEmpty(),
                "nbf 在未来 5 分钟的令牌不得生效");
    }

    @Test
    void rejectsWrongIssuer() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-8");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet claims = JwtTestSupport.claims("ivan@corp", List.of("ADMIN"))
                .issuer("https://evil.example.test").build();

        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, claims)).isEmpty(),
                "签发方不匹配必须拒绝：否则任何能签 JWT 的系统都能登录");
    }

    @Test
    void rejectsWrongAudience() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-9");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet claims = JwtTestSupport.claims("judy@corp", List.of("ADMIN"))
                .audience(List.of("another-app")).build();

        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, claims)).isEmpty(),
                "受众不匹配必须拒绝：否则兄弟系统的令牌可以重放");
    }

    @Test
    void rejectsAlgorithmNone() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-10");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        String token = JwtTestSupport.unsignedNone(
                JwtTestSupport.claims("kid@corp", List.of("ADMIN")).build());

        assertTrue(authenticator.authenticate(token).isEmpty(), "alg=none 必须被拒绝");
    }

    @Test
    void rejectsHmacTokenSignedWithPublicKeyMaterial() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-11");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        String forged = JwtTestSupport.hmacWithPublicKeyMaterial(key,
                JwtTestSupport.claims("mallory@corp", List.of("ADMIN")).build());

        assertTrue(authenticator.authenticate(forged).isEmpty(),
                "用公钥当 HMAC 密钥伪造的令牌必须被拒绝（算法混淆攻击）");
    }

    @Test
    void rejectsTamperedPayload() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-12");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        String valid = JwtTestSupport.sign(key, "kate@corp", List.of("READER"));

        assertTrue(authenticator.authenticate(JwtTestSupport.tampered(valid)).isEmpty(),
                "改了载荷但签名没变必须被拒绝");
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws JOSEException {
        RSAKey trusted = JwtTestSupport.rsaKey("trusted");
        RSAKey attacker = JwtTestSupport.rsaKey("trusted");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(trusted)));

        String forged = JwtTestSupport.sign(attacker, "mallory@corp", List.of("ADMIN"));

        assertTrue(authenticator.authenticate(forged).isEmpty(),
                "kid 相同但密钥不同：必须按密钥验签，不能按 kid 找到就用");
    }

    @Test
    void rejectsUnknownKid() throws JOSEException {
        RSAKey trusted = JwtTestSupport.rsaKey("trusted");
        RSAKey unknown = JwtTestSupport.rsaKey("unknown-kid");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(trusted)));

        assertTrue(authenticator.authenticate(
                JwtTestSupport.sign(unknown, "nina@corp", List.of("ADMIN"))).isEmpty(),
                "JWKS 里没有的 kid 必须拒绝");
    }

    @Test
    void rejectsGarbageAndEmptyTokens() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-13");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        assertTrue(authenticator.authenticate("").isEmpty());
        assertTrue(authenticator.authenticate("not-a-jwt").isEmpty());
        assertTrue(authenticator.authenticate("a.b.c").isEmpty());
    }

    @Test
    void identityFallsBackToEmailThenUnknown() throws JOSEException {
        RSAKey key = JwtTestSupport.rsaKey("rsa-14");
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(
                JwtTestSupport.config(JwtTestSupport.publicJwks(key)));

        JWTClaimsSet emailOnly = new JWTClaimsSet.Builder()
                .issuer(JwtTestSupport.ISSUER)
                .audience(JwtTestSupport.AUDIENCE)
                .claim("email", "leo@corp")
                .claim("roles", List.of("READER"))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();

        Optional<Principal> principal = authenticator.authenticate(JwtTestSupport.sign(key, emailOnly));
        assertTrue(principal.isPresent());
        assertEquals("leo@corp", principal.get().id(), "没有 sub 时回退 email");
        assertEquals("leo@corp", principal.get().displayName(),
                "没有姓名时展示名回退到身份标识，界面不应出现空白主体");
    }
}
