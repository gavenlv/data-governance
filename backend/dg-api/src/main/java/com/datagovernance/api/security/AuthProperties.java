package com.datagovernance.api.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 静态令牌配置（docs/09 §9.7 的开发形态）。
 *
 * <p>生产应改为 OIDC/JWKS：Python 参考实现已用本地 JWKS + 真实 RSA 密钥验证过该路径
 * （含算法混淆与 alg:none 防护），Java 侧的对齐在本轮未实现，见 /api/v1/capabilities。
 */
@ConfigurationProperties(prefix = "dg.auth")
public class AuthProperties {

    /** static | disabled */
    private String mode = "static";
    private List<TokenEntry> tokens = List.of();

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
        this.tokens = tokens;
    }

    public boolean disabled() {
        return "disabled".equalsIgnoreCase(mode);
    }

    /** 一个令牌对应的主体。 */
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
            this.roles = roles;
        }
    }
}
