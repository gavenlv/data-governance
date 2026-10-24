package com.datagovernance.api.security;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置（docs/09 §9.7 的第一层）。
 *
 * <p>三种模式，**生产只允许 {@code jwt}**：
 * <table border="1">
 *   <caption>模式对照</caption>
 *   <tr><th>模式</th><th>用途</th><th>生产可用</th></tr>
 *   <tr><td>{@code static}</td><td>静态令牌表（本机开发 / 内网试用）</td><td>否 —— 令牌不会过期、无法绑定真实身份</td></tr>
 *   <tr><td>{@code jwt}</td><td>校验企业 OIDC/SSO 签发的 Bearer JWT（JWKS 取公钥）</td><td>是</td></tr>
 *   <tr><td>{@code disabled}</td><td>完全不做认证</td><td>否 —— 仅本机开发</td></tr>
 * </table>
 *
 * <p>JWT 模式的密钥来源三选一（{@link Jwt#getJwksUri()} / {@link Jwt#getJwksFile()} /
 * {@link Jwt#getJwksJson()}）：JWKS URL 是常态；文件与内联 JSON 是给**内网/air-gapped**
 * 部署用的（IdP 的元数据端点不可达时仍能部署），也让"认证行为"可以在没有 IdP 的
 * 环境里被端到端测试 —— 一个只能在客户机房验证的功能等于没验证（docs/21 §22）。
 *
 * <p>**刻意不做**的便利：不提供"默认公钥"或"跳过签名校验"的开关。
 * 认证失败一律 401，绝不降级成匿名身份 —— 降级是最容易被忽略的越权入口。
 */
@ConfigurationProperties(prefix = "dg.auth")
public class AuthProperties {

    /** 可选值见类注释：static | jwt | disabled。 */
    private String mode = "static";

    /** 静态令牌表（仅 static 模式使用）。 */
    private List<TokenEntry> tokens = List.of();

    /** JWT 模式配置。 */
    private Jwt jwt = new Jwt();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<TokenEntry> getTokens() {
        return tokens;
    }

    public void setTokens(List<TokenEntry> tokens) {
        this.tokens = tokens == null ? List.of() : tokens;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt == null ? new Jwt() : jwt;
    }

    /** 规范化后的模式（小写）；未知值原样返回，由启动校验拒绝。 */
    public String normalizedMode() {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public boolean disabled() {
        return "disabled".equals(normalizedMode());
    }

    public boolean jwtMode() {
        return "jwt".equals(normalizedMode());
    }

    /** JWT 模式的全部参数。 */
    public static class Jwt {

        /**
         * 期望的 {@code iss}。**必填**：不校验签发方的 JWT 等于"任何能发 JWT 的人都能登录"。
         */
        private String issuer;

        /**
         * 期望的 {@code aud}。生产建议必填；不填时只告警不阻断（部分 IdP 的 aud 是动态值，
         * 强行要求会把可用性问题伪装成安全问题）。校验逻辑见 {@code PlatformJwtDecoder}。
         */
        private String audience;

        /** JWKS 端点（如 {@code https://idp.example.com/.well-known/jwks.json}）。 */
        private String jwksUri;

        /** JWKS 文件（内网 / air-gapped；与 uri 二选一）。 */
        private String jwksFile;

        /** 内联 JWKS JSON（与 uri / file 三选一；主要用于测试与单机部署）。 */
        private String jwksJson;

        /**
         * 允许的签名算法。默认只含**非对称**算法。
         *
         * <p>刻意不支持 HS*：JWKS 是公开信息，若允许 HS256，任何拿到公钥的人都能伪造令牌
         * （这就是"算法混淆"攻击）。配置里写 HS256 会在启动时直接失败，而不是"安静地按配置执行"。
         */
        private List<String> algorithms = List.of(
                "RS256", "RS384", "RS512",
                "PS256", "PS384", "PS512",
                "ES256", "ES384", "ES512");

        /** 角色声明名。值可以是数组，也可以是逗号/空格分隔的字符串。 */
        private String rolesClaim = "roles";

        /**
         * 外部组名 → 平台角色的别名映射（例如 {@code dg-admins: ADMIN}）。
         *
         * <p>企业 IdP 里的组名通常不是平台角色名；没有这张表，就只能要求 IdP 改配置。
         * 映射后的值仍要走 {@code Roles.normalize}（未知角色丢弃），因此这张表
         * **不能**成为"把任意组名变成 ADMIN"的后门 —— 值必须是平台已知角色。
         */
        private Map<String, String> roleAliases = Map.of();

        /** 姓名声明名（依次回退 preferred_username、sub）。 */
        private String nameClaim = "name";

        /** 允许的时钟偏移（秒）。签发方与本机的时钟不可能完全一致。 */
        private long clockSkewSeconds = 60;

        /** JWKS 缓存寿命（秒）。密钥轮换时的刷新延迟由它决定。 */
        private long jwksCacheSeconds = 300;

        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 5;

        /** JWKS 响应大小上限，防止被投毒成超大响应把控制面拖垮。 */
        private int maxJwksBytes = 512 * 1024;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getJwksFile() {
            return jwksFile;
        }

        public void setJwksFile(String jwksFile) {
            this.jwksFile = jwksFile;
        }

        public String getJwksJson() {
            return jwksJson;
        }

        public void setJwksJson(String jwksJson) {
            this.jwksJson = jwksJson;
        }

        public List<String> getAlgorithms() {
            return algorithms;
        }

        public void setAlgorithms(List<String> algorithms) {
            this.algorithms = algorithms == null || algorithms.isEmpty() ? List.of() : algorithms;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim = rolesClaim;
        }

        public Map<String, String> getRoleAliases() {
            return roleAliases;
        }

        public void setRoleAliases(Map<String, String> roleAliases) {
            this.roleAliases = roleAliases == null ? Map.of() : roleAliases;
        }

        public String getNameClaim() {
            return nameClaim;
        }

        public void setNameClaim(String nameClaim) {
            this.nameClaim = nameClaim;
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }

        public long getJwksCacheSeconds() {
            return jwksCacheSeconds;
        }

        public void setJwksCacheSeconds(long jwksCacheSeconds) {
            this.jwksCacheSeconds = jwksCacheSeconds;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public int getMaxJwksBytes() {
            return maxJwksBytes;
        }

        public void setMaxJwksBytes(int maxJwksBytes) {
            this.maxJwksBytes = maxJwksBytes;
        }
    }

    /** 一个静态令牌对应的主体（仅 static 模式）。 */
    public static class TokenEntry {
        private String token;
        private String id;
        private String name;
        private List<String> roles = List.of();

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? List.of() : roles;
        }
    }
}
