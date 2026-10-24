package com.datagovernance.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 静态令牌认证（开发 / 内网试用形态）。
 *
 * <p>存在的理由只有一个：让本机开发与内网试用不需要先架一个 IdP。
 * **它不是生产形态** —— 令牌不会过期、没有签名、身份与凭据绑定在配置文件里，
 * 因此生产启动时会拒绝使用内置的开发令牌（见 {@link AuthStartupValidator}）。
 *
 * <p>比较方式：先各自做 SHA-256 再比摘要。Map 直接比字符串会泄漏长度与前缀信息，
 * 而且令牌表很小，常数时间扫描的成本可以忽略。这不是"防住所有侧信道"，
 * 而是不给一个本可以避免的差异。
 */
public final class StaticTokenAuthenticator implements TokenAuthenticator {

    private final Map<String, Principal> table;

    public StaticTokenAuthenticator(AuthProperties properties) {
        Map<String, Principal> built = new LinkedHashMap<>();
        for (AuthProperties.TokenEntry entry : properties.getTokens()) {
            String token = entry.getToken();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "dg.auth.tokens 里存在空令牌：空串会让任何缺少 Authorization 头的请求被当成已认证");
            }
            if (built.containsKey(token)) {
                throw new IllegalStateException(
                        "dg.auth.tokens 里存在重复令牌：同一个凭据对应两个身份，授权结果将取决于顺序");
            }
            String id = entry.getId() == null || entry.getId().isBlank()
                    ? "token:" + fingerprint(token)
                    : entry.getId();
            List<String> roles = entry.getRoles();
            built.put(token, new Principal(id,
                    entry.getName() == null ? "" : entry.getName(),
                    new java.util.TreeSet<>(roles == null ? List.of() : roles)));
        }
        this.table = Map.copyOf(built);
    }

    @Override
    public Optional<Principal> authenticate(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        byte[] presented = sha256(token);
        for (Map.Entry<String, Principal> entry : table.entrySet()) {
            if (MessageDigest.isEqual(presented, sha256(entry.getKey()))) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** 令牌指纹：用来给没有 id 的令牌生成一个可读但不泄密的主体标识。 */
    private static String fingerprint(String token) {
        byte[] digest = sha256(token);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            builder.append(String.format(Locale.ROOT, "%02x", digest[index]));
        }
        return builder.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 缺少 SHA-256", e);
        }
    }
}
