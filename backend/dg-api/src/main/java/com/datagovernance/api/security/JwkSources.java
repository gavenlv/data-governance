package com.datagovernance.api.security;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.DefaultJWKSetCache;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSetCache;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;

/**
 * 按配置构造 {@link JWKSource}（公钥来源）。
 *
 * <p>三种来源对应三类真实部署：
 * <ul>
 *   <li>{@code jwks-uri}：常态。IdP 的 JWKS 端点，密钥轮换由 IdP 与缓存寿命决定；</li>
 *   <li>{@code jwks-file}：内网 / air-gapped。IdP 元数据不可达时，把公钥集随部署一起下发；</li>
 *   <li>{@code jwks-json}：单机与测试。让"认证行为"在没有 IdP 的环境里也能被端到端验证。</li>
 * </ul>
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>**公钥集里出现私钥直接失败**。把含 {@code d} 的 JWK 放进公钥集通常是"把 IdP 的签名私钥
 *       一起打包了"，这已经不是配置疏忽而是密钥泄漏；启动时报错比默默能跑更有价值。</li>
 *   <li>只接受 {@code use=sig}（或未标注 use）的键：加密公钥不应被用来验签。</li>
 *   <li>过滤后必须至少剩一个键，否则任何一个令牌都验不过 —— 让它启动失败，而不是等到第一个用户登录。</li>
 * </ol>
 */
final class JwkSources {

    private JwkSources() {
    }

    static JWKSource<SecurityContext> create(AuthProperties.Jwt config) {
        String json = trimToNull(config.getJwksJson());
        String file = trimToNull(config.getJwksFile());
        String uri = trimToNull(config.getJwksUri());

        int configured = (json == null ? 0 : 1) + (file == null ? 0 : 1) + (uri == null ? 0 : 1);
        if (configured == 0) {
            throw new IllegalStateException(
                    "dg.auth.mode=jwt 需要公钥来源：配置 dg.auth.jwt.jwks-uri（OIDC 常态）、"
                            + "jwks-file（内网/air-gapped）或 jwks-json（单机/测试）之一");
        }
        if (configured > 1) {
            throw new IllegalStateException(
                    "dg.auth.jwt 同时配置了多个公钥来源：请只保留一个，否则「用哪个验签」取决于代码顺序，"
                            + "排查时无法从配置看出实际生效的是哪一个");
        }

        if (json != null) {
            return immutable(json, "dg.auth.jwt.jwks-json");
        }
        if (file != null) {
            return immutable(readFile(Path.of(file), config.getMaxJwksBytes()), "dg.auth.jwt.jwks-file=" + file);
        }
        return remote(uri, config);
    }

    private static JWKSource<SecurityContext> immutable(String json, String origin) {
        List<JWK> usable = new ArrayList<>();
        JWKSet set;
        try {
            set = JWKSet.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("JWKS 不是合法的 JWK Set（" + origin + "）：" + e.getMessage(), e);
        }
        for (JWK key : set.getKeys()) {
            String kid = key.getKeyID() == null ? "(无 kid)" : key.getKeyID();
            if (key.isPrivate()) {
                throw new IllegalStateException(
                        "JWKS 里出现私钥（kid=" + kid + "，来源 " + origin + "）：这里只应放公钥。"
                                + "私钥必须留在 IdP，出现在部署物里等同于密钥泄漏 —— 请撤销该密钥并轮换，"
                                + "不要通过删掉这条校验来绕过去");
            }
            if (key.getKeyUse() != null && !"sig".equals(key.getKeyUse().identifier())) {
                // 加密用途的公钥不参与验签；跳过而不是失败：一个 JWKS 里同时放 sig/enc 是常见做法
                continue;
            }
            usable.add(key);
        }
        if (usable.isEmpty()) {
            throw new IllegalStateException(
                    "JWKS 里没有可用于验签的公钥（来源 " + origin + "）：空公钥集会让所有令牌都验不过，"
                            + "这类问题应当启动即暴露");
        }
        return new ImmutableJWKSet<>(new JWKSet(usable));
    }

    private static JWKSource<SecurityContext> remote(String uri, AuthProperties.Jwt config) {
        URL url;
        try {
            url = URI.create(uri).toURL();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("dg.auth.jwt.jwks-uri 不是合法 URL：" + uri, e);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalStateException("dg.auth.jwt.jwks-uri 只支持 http/https：" + uri);
        }
        ResourceRetriever retriever = new DefaultResourceRetriever(
                Math.max(1, config.getConnectTimeoutSeconds()) * 1000,
                Math.max(1, config.getReadTimeoutSeconds()) * 1000,
                Math.max(1024, config.getMaxJwksBytes()));
        long lifespan = Math.max(30, config.getJwksCacheSeconds());
        // 提前一半寿命刷新：密钥轮换后新 kid 的引入延迟从"最长一个完整寿命"降到"半个寿命"，
        // 同时又不会被高频刷新变成对 IdP 的压测。
        long refresh = Math.max(15, lifespan / 2);
        JWKSetCache cache = new DefaultJWKSetCache(lifespan, refresh, TimeUnit.SECONDS);
        return new RemoteJWKSet<>(url, retriever, cache);
    }

    private static String readFile(Path path, int maxBytes) {
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                throw new IllegalStateException("JWKS 文件过大（" + size + " 字节）:" + path);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JWKS 文件失败：" + path + "（" + e.getMessage() + "）", e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
