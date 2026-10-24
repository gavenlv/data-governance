package com.datagovernance.api.embed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 嵌入卡片的短期令牌（docs/14 §5）。
 *
 * <p>为什么需要单独的令牌，而不是让 iframe 直接带平台的 Bearer：iframe 的 URL 会出现在
 * 浏览器历史、代理日志、截图里，把长期平台凭据放进去等于把它公开。因此这里签发
 * **短寿命、只对一个 URN 有效、只用于读**的令牌（默认 5 分钟）。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>令牌绑定到具体 URN</b>：一个资产的嵌入令牌不能拿去读另一个资产 ——
 *       否则拿到一次令牌就等于拿到整个目录的读权限；</li>
 *   <li><b>签名密钥从 {@code DG_SECRET_KEY} 派生</b>（带 context 前缀，不与数据源加密共用同一把密钥）。</li>
 *   <li><b>未配置密钥时显式失败</b>（502），不降级成"不签名"或"用固定密钥"——
 *       那会让嵌入变成任何人都能伪造的读入口。</li>
 * </ol>
 */
@Service
public class EmbedTokenService {

    private static final String CONTEXT = "dg-embed-token-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_TTL_SECONDS = 3600;

    private final byte[] signingKey;
    private final long defaultTtlSeconds;
    private final Clock clock;

    @Autowired
    public EmbedTokenService(@Value("${dg.secret-key:}") String secretKey,
                             @Value("${dg.embed.token-ttl-seconds:300}") long tokenTtlSeconds) {
        this(deriveKey(secretKey), Math.max(30, Math.min(tokenTtlSeconds, MAX_TTL_SECONDS)), Clock.systemUTC());
    }

    /** 测试用：显式密钥与时钟。 */
    public EmbedTokenService(byte[] signingKey, long defaultTtlSeconds, Clock clock) {
        this.signingKey = signingKey;
        this.defaultTtlSeconds = Math.max(1, Math.min(defaultTtlSeconds, MAX_TTL_SECONDS));
        this.clock = clock;
    }

    public record MintedToken(String token, Instant expiresAt, long ttlSeconds) {
    }

    /** 签发：调用方必须已经通过平台认证与 {@code asset:read} 授权。 */
    public MintedToken mint(String urn, String subjectId, Long requestedTtlSeconds) {
        requireConfigured();
        long ttl = requestedTtlSeconds == null ? defaultTtlSeconds
                : Math.max(30, Math.min(requestedTtlSeconds, MAX_TTL_SECONDS));
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(ttl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("u", urn);
        payload.put("s", subjectId);
        payload.put("i", now.getEpochSecond());
        payload.put("e", expiresAt.getEpochSecond());
        try {
            String body = MAPPER.writeValueAsString(payload);
            String encoded = base64(body.getBytes(StandardCharsets.UTF_8));
            return new MintedToken(encoded + "." + base64(sign(encoded)), expiresAt, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("无法签发嵌入令牌", e);
        }
    }

    /**
     * 校验并返回令牌声明的 URN。
     *
     * @throws InvalidToken 任何签名/有效期/格式问题（统一一个原因：不向调用方泄漏细节）
     */
    public String verify(String token, String expectedUrn) {
        requireConfigured();
        if (token == null || token.isBlank()) {
            throw new InvalidToken("缺少嵌入令牌");
        }
        int split = token.lastIndexOf('.');
        if (split <= 0) {
            throw new InvalidToken("令牌格式不正确");
        }
        String encoded = token.substring(0, split);
        byte[] presented;
        try {
            presented = decode(token.substring(split + 1));
        } catch (IllegalArgumentException e) {
            // Base64 解码失败也属于"令牌无效"，而不是 500：调用方看到的原因应当统一
            throw new InvalidToken("令牌签名不可解析");
        }
        if (!MessageDigest.isEqual(presented, sign(encoded))) {
            throw new InvalidToken("令牌签名不正确");
        }
        JsonNode payload = parse(encoded);
        long expiresAt = payload.path("e").asLong(0);
        if (expiresAt <= clock.instant().getEpochSecond()) {
            throw new InvalidToken("令牌已过期");
        }
        String urn = payload.path("u").asText("");
        if (!urn.equals(expectedUrn)) {
            throw new InvalidToken("令牌与目标资产不匹配");
        }
        return urn;
    }

    private void requireConfigured() {
        if (signingKey == null || signingKey.length == 0) {
            throw new NotConfigured("未配置 DG_SECRET_KEY：无法签发或校验嵌入令牌。"
                    + "嵌入令牌必须签名，否则任何能构造 URL 的人都能读任意资产");
        }
    }

    private byte[] sign(String encoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("嵌入令牌签名失败", e);
        }
    }

    private static JsonNode parse(String encoded) {
        try {
            return MAPPER.readTree(decode(encoded));
        } catch (Exception e) {
            throw new InvalidToken("令牌内容不可解析");
        }
    }

    private static byte[] deriveKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return new byte[0];
        }
        try {
            byte[] raw = Base64.getDecoder().decode(secretKey.trim());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(raw, "HmacSHA256"));
            return mac.doFinal(CONTEXT.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 密钥格式不合法与"未配置"同样处理：宁可 502，也不要静默用一个错密钥签名
            return new byte[0];
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** 令牌无效（401）。 */
    public static class InvalidToken extends RuntimeException {
        public InvalidToken(String message) {
            super(message);
        }
    }

    /** 未配置签名密钥（502）。 */
    public static class NotConfigured extends RuntimeException {
        public NotConfigured(String message) {
            super(message);
        }
    }
}
