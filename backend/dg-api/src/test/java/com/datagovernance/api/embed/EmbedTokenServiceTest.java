package com.datagovernance.api.embed;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.datagovernance.api.embed.EmbedTokenService.InvalidToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 嵌入令牌的攻击面测试。
 *
 * <p>嵌入卡是唯一"不需要平台登录"的数据端点，因此它的令牌必须逐条证明：
 * 不能跨资产使用、不能改内容、不能过期后用、不能用未签名格式。
 */
class EmbedTokenServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private static EmbedTokenService service(long ttl) {
        return new EmbedTokenService(KEY, ttl, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mintsTokenBoundToUrnAndSubject() {
        EmbedTokenService service = service(300);
        EmbedTokenService.MintedToken minted = service.mint("urn:dg:Dataset:t.a.b", "alice@corp", null);

        assertEquals("urn:dg:Dataset:t.a.b", service.verify(minted.token(), "urn:dg:Dataset:t.a.b"));
        assertEquals(NOW.plusSeconds(300), minted.expiresAt());
        assertTrue(minted.token().contains("."), "令牌必须是 payload.signature 形态");
    }

    @Test
    void rejectsTokenForAnotherAsset() {
        EmbedTokenService service = service(300);
        String token = service.mint("urn:dg:Dataset:t.a.b", "alice@corp", null).token();

        InvalidToken error = assertThrows(InvalidToken.class,
                () -> service.verify(token, "urn:dg:Dataset:t.x.y"));
        assertTrue(error.getMessage().contains("不匹配"));
    }

    @Test
    void rejectsTamperedPayload() {
        EmbedTokenService service = service(300);
        String token = service.mint("urn:dg:Dataset:t.a.b", "alice@corp", null).token();
        String[] parts = token.split("\\.");
        // 换掉载荷（指向另一个资产），签名保持原样
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"u\":\"urn:dg:Dataset:t.x.y\",\"s\":\"alice@corp\",\"e\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(InvalidToken.class, () -> service.verify(forgedPayload + "." + parts[1],
                "urn:dg:Dataset:t.x.y"), "签名不匹配必须拒绝（否则任何人都能改 URN）");
    }

    @Test
    void rejectsExpiredToken() {
        EmbedTokenService service = service(300);
        EmbedTokenService.MintedToken minted = service.mint("urn:dg:Dataset:t.a.b", "alice@corp", null);
        // 用"未来 10 分钟"的时钟模拟过期
        EmbedTokenService later = new EmbedTokenService(KEY, 300,
                Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC));

        assertThrows(InvalidToken.class, () -> later.verify(minted.token(), "urn:dg:Dataset:t.a.b"));
    }

    @Test
    void clampsTtlToSafeRange() {
        EmbedTokenService service = service(300);
        assertEquals(3600L, service.mint("u", "s", 999_999L).ttlSeconds());
        assertEquals(30L, service.mint("u", "s", 1L).ttlSeconds());
    }

    @Test
    void rejectsGarbageAndMissingTokens() {
        EmbedTokenService service = service(300);
        assertThrows(InvalidToken.class, () -> service.verify(null, "u"));
        assertThrows(InvalidToken.class, () -> service.verify("", "u"));
        assertThrows(InvalidToken.class, () -> service.verify("no-dot", "u"));
        assertThrows(InvalidToken.class, () -> service.verify("a.b.c", "u"));
    }

    @Test
    void refusesToSignWithoutConfiguredKey() {
        EmbedTokenService unconfigured = new EmbedTokenService(new byte[0], 300, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(EmbedTokenService.NotConfigured.class,
                () -> unconfigured.mint("u", "s", null),
                "没有密钥时必须 502，绝不能降级成不签名");
    }
}
