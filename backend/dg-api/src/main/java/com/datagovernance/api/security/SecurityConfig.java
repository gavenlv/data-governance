package com.datagovernance.api.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证与授权装配（docs/09 §9.7 的第一层：平台功能权限）。
 *
 * <p>本轮实现 **静态令牌**（开发/内网形态）。生产形态应换 OIDC/JWKS，
 * 该路径在 Python 参考实现中已验证（本地 JWKS + RSA 密钥 + 算法混淆防护），
 * Java 侧对齐尚未实现 —— 见 {@code /api/v1/capabilities} 中 {@code policy.*} 的说明。
 *
 * <p>放宽的路径：{@code /healthz}、{@code /actuator/health}（探活）、
 * {@code /docs} 与 {@code /api-docs}（接口文档）、{@code /}（前端静态资源）。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, TokenAuthFilter tokenFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configure(http))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 界面静态资源与 SPA 路由公开；数据一律经 /api/** 且需认证
                        // （界面不做专用后门：界面上看不到的，接口同样拿不到，docs/09 §9.3）
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendError(HttpStatus.UNAUTHORIZED.value(), "unauthenticated")));
        return http.build();
    }

    /** 当前主体（从 SecurityContext 取）。 */
    public record Principal(String id, String name, Set<String> roles) {
        public boolean hasRole(String role) {
            return roles.contains("ADMIN") || roles.contains(role);
        }
    }

    @Component
    public static class TokenAuthFilter extends OncePerRequestFilter {

        private final AuthProperties properties;

        public TokenAuthFilter(AuthProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (properties.disabled()) {
                setPrincipal(new Principal("dev@local", "开发模式（未认证）", Set.of("ADMIN")));
                chain.doFilter(request, response);
                return;
            }

            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = header.substring(7).trim();
                properties.getTokens().stream()
                        .filter(entry -> token.equals(entry.getToken()))
                        .findFirst()
                        .ifPresent(entry -> setPrincipal(new Principal(
                                entry.getId(),
                                entry.getName() == null ? entry.getId() : entry.getName(),
                                new TreeSet<>(entry.getRoles() == null ? List.of() : entry.getRoles()))));
            }
            chain.doFilter(request, response);
        }

        private void setPrincipal(Principal principal) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null,
                            principal.roles().stream().map(SimpleGrantedAuthority::new).toList()));
        }
    }
}
