package com.datagovernance.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * JWT/JWKS 认证（生产形态，docs/09 §9.7）。
 *
 * <p>职责边界很窄：**把已经验过签的令牌声明映射成平台主体**。签名、时间、签发方、
 * 受众的校验在 {@link PlatformJwtDecoder} 里完成；这里只做声明 → {@link Principal} 的翻译。
 * 分开的原因是两者的测试方式完全不同：前者靠构造恶意令牌，后者靠构造各种声明形状。
 *
 * <p>声明映射的三条纪律：
 * <ol>
 *   <li><b>未知角色丢弃</b>（{@code Roles.normalize}）：IdP 里的组名不能直接当平台角色用，
 *       否则"给用户加一个拼错的组"会变成静默的权限变化。</li>
 *   <li><b>外部组名必须显式映射</b>（{@code role-aliases}）：企业 IdP 的组名通常不是
 *       ADMIN/EDITOR/STEWARD/READER。映射表的值仍然要过 normalize，因此这张表不能
 *       变成"把任意组名变成 ADMIN"的后门。</li>
 *   <li><b>身份取 {@code sub}</b>（回退 email），而不是 name：姓名可以改、可以重名，
 *       审计与授权都必须绑定不可变的身份标识。</li>
 * </ol>
 */
public final class JwtTokenAuthenticator implements TokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenAuthenticator.class);

    private final JwtDecoder decoder;
    private final AuthProperties.Jwt config;
    /** 小写外部组名 → 平台角色；配置时统一小写，避免大小写差异造成的"映射不生效"。 */
    private final Map<String, String> roleAliases;

    public JwtTokenAuthenticator(JwtDecoder decoder, AuthProperties.Jwt config) {
        this.decoder = decoder;
        this.config = config;
        Map<String, String> aliases = new LinkedHashMap<>();
        config.getRoleAliases().forEach((external, internal) -> {
            if (external != null && !external.isBlank() && internal != null && !internal.isBlank()) {
                aliases.put(external.trim().toLowerCase(Locale.ROOT), internal.trim());
            }
        });
        this.roleAliases = Map.copyOf(aliases);
    }

    @Override
    public Optional<Principal> authenticate(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 只记原因，不记令牌：日志本身也是一种数据泄漏面
            log.debug("JWT 认证失败：{}", e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new Principal(subjectOf(jwt), nameOf(jwt), new TreeSet<>(rolesOf(jwt))));
    }

    private static String subjectOf(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        return "unknown";
    }

    private String nameOf(Jwt jwt) {
        String configured = jwt.getClaimAsString(config.getNameClaim());
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred == null ? "" : preferred;
    }

    /**
     * 角色来源：配置的 claim，回退 {@code role}（单数）。
     *
     * <p>回退 {@code role} 是因为不少 IdP 用单数形式；但**不**回退 {@code scope}
     * 或 {@code groups}：把 OAuth scope（如 {@code read:assets}）当角色会导致
     * "任何拿到该 scope 的客户端都能当 EDITOR"，这是权限模型上的混淆。
     */
    private List<String> rolesOf(Jwt jwt) {
        Object raw = jwt.getClaims().get(config.getRolesClaim());
        if (raw == null) {
            raw = jwt.getClaims().get("role");
        }
        List<String> values = flatten(raw);
        List<String> mapped = new ArrayList<>(values.size());
        for (String value : values) {
            String alias = roleAliases.get(value.trim().toLowerCase(Locale.ROOT));
            mapped.add(alias == null ? value : alias);
        }
        return mapped;
    }

    /** 数组、集合、单值、逗号/空格分隔的字符串都接受 —— IdP 的声明形状确实五花八门。 */
    private static List<String> flatten(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (raw instanceof String text) {
            List<String> values = new ArrayList<>();
            for (String part : text.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
            return values;
        }
        return List.of(String.valueOf(raw));
    }
}
