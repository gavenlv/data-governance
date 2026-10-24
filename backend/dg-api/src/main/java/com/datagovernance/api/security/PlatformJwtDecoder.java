package com.datagovernance.api.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 构造 JWT 解码器（签名校验 + 声明校验）。
 *
 * <p>这里的每一行都对应一类真实攻击，注释写清"为什么"，因为下一个维护者很可能想"简化"它们：
 *
 * <ol>
 *   <li><b>算法由配置决定，不由令牌决定。</b>{@link JWSVerificationKeySelector} 只接受
 *       {@code dg.auth.jwt.algorithms} 里的算法；令牌头里写 {@code alg=none} 或
 *       换成别的算法都会被拒绝。信任令牌头里的 {@code alg} 是历史上大量 JWT 漏洞的根因。</li>
 *   <li><b>密钥类型与算法族绑定。</b>{@code JWKSource} 里全是公钥，因此 HS256 无法通过 ——
 *       想用"公钥当 HMAC 密钥"伪造令牌会失败。配置里写 HS* 直接在启动时失败（见
 *       {@link AuthStartupValidator}），而不是运行期才拒。</li>
 *   <li><b>时间校验带时钟偏移</b>（默认 60s）：签发方与本机时钟不可能完全一致，
 *       偏移为 0 会让"刚签发的令牌偶发 401"，这种间歇性故障极难排查。</li>
 *   <li><b>签发方必须校验</b>（{@link JwtIssuerValidator}）：不校验 iss 意味着
 *       任何能签发 JWT 的系统（包括攻击者自建的）都能登录本平台。</li>
 *   <li><b>受众按需校验</b>：配了 audience 就必须命中。多应用共用一个 IdP 时，
 *       没有这一条，A 系统的令牌可以直接拿去调 B 系统（令牌重放）。</li>
 * </ol>
 */
public final class PlatformJwtDecoder {

    /** 明确支持的非对称签名算法。 */
    private static final java.util.Set<String> SUPPORTED_ALGORITHMS = java.util.Set.of(
            "RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512", "EDDSA");

    private PlatformJwtDecoder() {
    }

    public static NimbusJwtDecoder create(AuthProperties.Jwt config) {
        Set<JWSAlgorithm> algorithms = parseAlgorithms(config.getAlgorithms());
        JWKSource<SecurityContext> keys = JwkSources.create(config);

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(config));
        return decoder;
    }

    /**
     * 解析并校验算法白名单。
     *
     * <p>解析失败与不安全算法都**在启动时**报错：一个写错的算法名不该等到用户登录才发现。
     */
    static Set<JWSAlgorithm> parseAlgorithms(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("dg.auth.jwt.algorithms 不能为空：不配置算法等于没有算法白名单");
        }
        Set<JWSAlgorithm> algorithms = new LinkedHashSet<>();
        for (String name : configured) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("HS")) {
                throw new IllegalStateException(
                        "dg.auth.jwt.algorithms 不允许对称算法 " + normalized + "：JWKS 是公开信息，"
                                + "允许 HMAC 会让任何拿到公钥的人伪造令牌（算法混淆攻击）");
            }
            if (normalized.equals("NONE")) {
                throw new IllegalStateException("dg.auth.jwt.algorithms 不允许 none：那等于「不验签」");
            }
            // 必须**按白名单**判断，而不是交给 JWSAlgorithm.parse：Nimbus 对未知名字
            // 会构造一个自定义 JWSAlgorithm 而不是报错，于是 "RS999" 会安静地进入白名单。
            // 这不是理论问题 —— 本用例就是先写错了才发现（见 docs/21 §22）。
            if (!SUPPORTED_ALGORITHMS.contains(normalized)) {
                throw new IllegalStateException(
                        "dg.auth.jwt.algorithms 里的算法不受支持：" + normalized
                                + "（支持 " + String.join(", ", SUPPORTED_ALGORITHMS) + "）");
            }
            algorithms.add(JWSAlgorithm.parse(normalized));
        }
        if (algorithms.isEmpty()) {
            throw new IllegalStateException("dg.auth.jwt.algorithms 解析后为空");
        }
        return algorithms;
    }

    static OAuth2TokenValidator<Jwt> validator(AuthProperties.Jwt config) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        long skew = Math.max(0, config.getClockSkewSeconds());
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(skew)));
        if (hasText(config.getIssuer())) {
            validators.add(new JwtIssuerValidator(config.getIssuer().trim()));
        }
        if (hasText(config.getAudience())) {
            String expected = config.getAudience().trim();
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audiences -> audiences != null && audiences.contains(expected)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
