package com.datagovernance.api.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 测试用的密钥与令牌工厂。
 *
 * <p>刻意**不**依赖任何外部 IdP 或网络：认证是最需要测试覆盖的地方，也是最不该
 * 依赖"测试环境恰好有个 IdP"的地方。这里用真实 RSA/EC 密钥签名，因此测的是真的
 * 加密校验路径，而不是把校验关掉之后的映射逻辑。
 */
final class JwtTestSupport {

    static final String ISSUER = "https://idp.example.test";
    static final String AUDIENCE = "dg-platform";

    private JwtTestSupport() {
    }

    static RSAKey rsaKey(String kid) throws JOSEException {
        return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
    }

    static ECKey ecKey(String kid) throws JOSEException {
        return new ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
    }

    /** 公钥集 JSON（只含公钥材料）。 */
    static String publicJwks(JWK... keys) {
        return new JWKSet(Arrays.asList(keys)).toPublicJWKSet().toString();
    }

    static AuthProperties.Jwt config(String jwksJson) {
        AuthProperties.Jwt config = new AuthProperties.Jwt();
        config.setIssuer(ISSUER);
        config.setAudience(AUDIENCE);
        config.setJwksJson(jwksJson);
        return config;
    }

    static JwtTokenAuthenticator authenticator(AuthProperties.Jwt config) {
        return new JwtTokenAuthenticator(PlatformJwtDecoder.create(config), config);
    }

    /** 正常签名的令牌。 */
    static String sign(JWK signingKey, String subject, List<String> roles) throws JOSEException {
        return sign(signingKey, claims(subject, roles).build());
    }

    static String sign(JWK signingKey, JWTClaimsSet claims) throws JOSEException {
        JWSAlgorithm algorithm = signingKey instanceof ECKey ? JWSAlgorithm.ES256 : JWSAlgorithm.RS256;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm)
                .keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(signerFor(signingKey));
        return jwt.serialize();
    }

    static JWTClaimsSet.Builder claims(String subject, List<String> roles) {
        long now = Instant.now().getEpochSecond();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject)
                .claim("name", "测试用户")
                .claim("roles", roles)
                .issueTime(Date.from(Instant.ofEpochSecond(now)))
                .notBeforeTime(Date.from(Instant.ofEpochSecond(now)))
                .expirationTime(Date.from(Instant.ofEpochSecond(now + 600)));
    }

    /**
     * 算法混淆令牌：用**公钥的序列化文本**当 HMAC 密钥签 HS256。
     *
     * <p>这正是攻击者会做的事 —— 如果服务端允许 HS*，公开的 JWKS 就变成了签名密钥。
     */
    static String hmacWithPublicKeyMaterial(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        byte[] secret = key.toPublicJWK().toString().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            secret = Arrays.copyOf(secret, 32);
        }
        JWSSigner signer = new MACSigner(new SecretKeySpec(secret, "HmacSHA256"));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID(key.getKeyID()).build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    /** {@code alg=none} 的无签名令牌。 */
    static String unsignedNone(JWTClaimsSet claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"k1\"}";
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8)) + ".";
    }

    /** 篡改载荷（签名保持不变）。 */
    static String tampered(String token) {
        String[] parts = token.split("\\.");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("测试用户", "被篡改的用户");
        return parts[0] + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    private static JWSSigner signerFor(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsaKey) {
            return new RSASSASigner(rsaKey);
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSASigner(ecKey);
        }
        throw new IllegalArgumentException("不支持的密钥类型：" + key.getKeyType());
    }
}
