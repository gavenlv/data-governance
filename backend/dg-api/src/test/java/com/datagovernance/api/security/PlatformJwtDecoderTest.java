package com.datagovernance.api.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解码器配置本身的失败路径。
 *
 * <p>为什么这些也算"功能测试"：一个能被错误配置成"验签形同虚设"的认证组件，
 * 它的正常路径测试通过与否没有意义。这里逐条证明**危险配置会启动失败**，
 * 而不是安静地降级。
 */
class PlatformJwtDecoderTest {

    @Test
    void rejectsSymmetricAlgorithms() {
        AuthProperties.Jwt config = new AuthProperties.Jwt();
        config.setAlgorithms(List.of("RS256", "HS256"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.parseAlgorithms(config.getAlgorithms()));
        assertTrue(error.getMessage().contains("算法混淆"),
                "报错必须说明为什么对称算法危险，否则运维会把它当成「校验太严」而绕过");
    }

    @Test
    void rejectsNoneAlgorithm() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.parseAlgorithms(List.of("none")));
        assertTrue(error.getMessage().contains("不验签"));
    }

    @Test
    void rejectsUnknownAlgorithmName() {
        assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.parseAlgorithms(List.of("RS999")));
    }

    @Test
    void rejectsEmptyAlgorithmList() {
        assertThrows(IllegalStateException.class, () -> PlatformJwtDecoder.parseAlgorithms(List.of()));
        assertThrows(IllegalStateException.class, () -> PlatformJwtDecoder.parseAlgorithms(null));
    }

    @Test
    void rejectsJwksContainingPrivateKey() throws JOSEException {
        RSAKey key = new RSAKeyGenerator(2048).keyID("leaky").generate();
        // 关键：显式输出**含私钥**的完整 JWK（JWKSet.toString() 只输出公钥，
        // 用它来测这条校验会得到"永远不会失败"的假绿）
        String jwks = "{\"keys\":[" + key.toJSONString() + "]}";
        AuthProperties.Jwt config = JwtTestSupport.config(jwks);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.create(config));
        assertTrue(error.getMessage().contains("私钥"),
                "公钥集里出现私钥必须启动失败：这通常意味着 IdP 私钥被打进了部署物");
        assertTrue(error.getMessage().contains("轮换"), "报错要给出下一步动作，而不只是拒绝");
    }

    @Test
    void rejectsEmptyJwks() {
        AuthProperties.Jwt config = JwtTestSupport.config("{\"keys\":[]}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.create(config));
        assertTrue(error.getMessage().contains("没有可用于验签的公钥"));
    }

    @Test
    void rejectsMalformedJwks() {
        AuthProperties.Jwt config = JwtTestSupport.config("{not json}");
        assertThrows(IllegalStateException.class, () -> PlatformJwtDecoder.create(config));
    }

    @Test
    void skipsEncryptionKeysButKeepsSignatureKeys() throws JOSEException {
        RSAKey signing = new RSAKeyGenerator(2048).keyID("sign").keyUse(KeyUse.SIGNATURE).generate();
        RSAKey encryption = new RSAKeyGenerator(2048).keyID("enc").keyUse(KeyUse.ENCRYPTION).generate();

        AuthProperties.Jwt config = JwtTestSupport.config(JwtTestSupport.publicJwks(signing, encryption));
        assertNotNull(PlatformJwtDecoder.create(config), "混合用途的 JWKS 应可用（加密键被跳过）");

        AuthProperties.Jwt onlyEncryption = JwtTestSupport.config(JwtTestSupport.publicJwks(encryption));
        assertThrows(IllegalStateException.class, () -> PlatformJwtDecoder.create(onlyEncryption),
                "只有加密键时没有任何验签能力，必须启动失败");
    }

    @Test
    void requiresExactlyOneJwksSource(@TempDir Path dir) throws Exception {
        AuthProperties.Jwt both = JwtTestSupport.config("{\"keys\":[]}");
        both.setJwksUri("https://idp.example.test/jwks.json");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.create(both));
        assertTrue(error.getMessage().contains("只保留一个"));

        AuthProperties.Jwt none = new AuthProperties.Jwt();
        none.setIssuer(JwtTestSupport.ISSUER);
        assertThrows(IllegalStateException.class, () -> PlatformJwtDecoder.create(none),
                "没有任何公钥来源时必须启动失败");
    }

    @Test
    void readsJwksFromFileForAirGappedDeployments(@TempDir Path dir) throws Exception {
        RSAKey key = JwtTestSupport.rsaKey("file-key");
        Path jwks = dir.resolve("jwks.json");
        Files.writeString(jwks, JwtTestSupport.publicJwks(key), StandardCharsets.UTF_8);

        AuthProperties.Jwt config = new AuthProperties.Jwt();
        config.setIssuer(JwtTestSupport.ISSUER);
        config.setAudience(JwtTestSupport.AUDIENCE);
        config.setJwksFile(jwks.toString());

        assertNotNull(PlatformJwtDecoder.create(config));
        JwtTokenAuthenticator authenticator = JwtTestSupport.authenticator(config);
        assertTrue(authenticator.authenticate(JwtTestSupport.sign(key, "file@corp", List.of("READER")))
                .isPresent(), "文件来源的 JWKS 必须能真正验签");
    }

    @Test
    void reportsMissingJwksFileClearly(@TempDir Path dir) {
        AuthProperties.Jwt config = new AuthProperties.Jwt();
        config.setIssuer(JwtTestSupport.ISSUER);
        config.setJwksFile(dir.resolve("missing.json").toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PlatformJwtDecoder.create(config));
        assertTrue(error.getMessage().contains("读取 JWKS 文件失败"));
        assertFalse(error.getMessage().isBlank());
    }
}
