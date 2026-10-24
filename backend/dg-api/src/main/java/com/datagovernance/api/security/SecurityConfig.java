package com.datagovernance.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.datagovernance.policy.Roles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证与授权装配（docs/09 §9.7 的第一层：平台功能权限）。
 *
 * <p>本层只回答一个问题：**请求是谁**。至于"他能做什么"，统一由
 * {@code com.datagovernance.policy.AccessPolicy} 判定（唯一入口）。
 * 两层分开的价值在于：换认证方式（静态令牌 → OIDC/JWKS）不改变任何一条授权规则。
 *
 * <p>放行的路径与理由：
 * <ul>
 *   <li>{@code /healthz}、{@code /actuator/health}：探活，必须能在没有凭据时被负载均衡器调用；</li>
 *   <li>Edge Agent 的三个上报端点：走 **Agent 自己的凭据**（{@code dgagent_...}，库里只存哈希、
 *       可吊销），由 {@code EdgeAgentService} 在服务层校验。这里只放行这三个**字面路径**
 *       （不用通配符），避免顺手把管理面 {@code /api/v1/edge/agents} 也放开；</li>
 *   <li>{@code /}、{@code /static/**}、SPA 路由：界面静态资源本身不含数据；数据一律经
 *       {@code /api/**} 且需认证。界面没有专用后门（docs/09 §9.3）。</li>
 * </ul>
 *
 * <p>其余 {@code /actuator/**} 一律需要认证：指标里含连接池、JVM、请求量等内部信息，
 * 公开出去等于给攻击者做侦察。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthProperties.class, SecurityProperties.class})
public class SecurityConfig {

    /** Swagger UI 依赖内联脚本，CSP 对它豁免；生产下 springdoc 默认关闭，因此不影响生产面。 */
    private static final RequestMatcher API_DOCS = request -> {
        String path = request.getRequestURI();
        return path.startsWith("/docs") || path.startsWith("/api-docs") || path.startsWith("/swagger-ui");
    };

    /**
     * 按配置选择令牌认证实现。
     *
     * <p>构造期就建好解码器/JWKS 源：配置错误在**启动**时暴露，而不是等第一个用户登录。
     */
    @Bean
    public TokenAuthenticator tokenAuthenticator(AuthProperties properties) {
        return switch (properties.normalizedMode()) {
            case "jwt" -> new JwtTokenAuthenticator(
                    PlatformJwtDecoder.create(properties.getJwt()), properties.getJwt());
            case "static" -> new StaticTokenAuthenticator(properties);
            case "disabled" -> token -> Optional.empty();
            default -> throw new IllegalStateException("dg.auth.mode 无效：" + properties.getMode());
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, TokenAuthFilter tokenFilter,
                                           AuthProperties properties, SecurityProperties security,
                                           ObjectMapper json) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // 纯 Bearer 认证、无 Cookie 会话，因此 CSRF 不适用；一旦引入 Cookie 认证必须改回开启
                .cors(cors -> cors.configure(http))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/edge/agent/heartbeat",
                                "/api/v1/edge/agent/report",
                                "/api/v1/edge/agent/whoami").permitAll()
                        .requestMatchers("/healthz", "/actuator/health", "/actuator/health/**").permitAll()
                        // 嵌入卡：iframe 无法携带 Bearer，因此改由**短期签名令牌**在服务层校验
                        // （令牌绑定单个 URN、只读；签发端点仍需平台登录，见 EmbedController）。
                        // 只放行这一个字面前缀，不放行 /api/v1/embed/token。
                        .requestMatchers("/api/v1/embed/assets/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint(properties, json))
                        .accessDeniedHandler(accessDeniedHandler(json)))
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.deny());
                    headers.referrerPolicy(referrer -> referrer
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                    headers.contentTypeOptions(Customizer.withDefaults());
                    if (security.getHstsMaxAgeSeconds() > 0) {
                        headers.httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(security.getHstsMaxAgeSeconds())
                                .includeSubDomains(security.isHstsIncludeSubDomains())
                                .preload(false)
                                // Spring 默认只在"请求本身是 HTTPS"时发送 HSTS（secure-only）。
                                // 但生产的常见形态是**在 TLS 终结代理之后**：到达本进程的请求是 HTTP，
                                // request.isSecure() 恒为 false，于是这个头会**永远不发送**，
                                // 而配置看起来完全正确 —— 这是最容易被漏掉的一类失效。
                                // 因此显式对所有请求发送：浏览器按 RFC 6797 会忽略经 HTTP 收到的
                                // Strict-Transport-Security，所以对纯 HTTP 部署没有副作用。
                                .requestMatcher(AnyRequestMatcher.INSTANCE));
                    }
                    String csp = security.getContentSecurityPolicy();
                    if (csp != null && !csp.isBlank()) {
                        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(API_DOCS),
                                new StaticHeadersWriter("Content-Security-Policy", csp)));
                    }
                });
        return http.build();
    }

    /**
     * 401 的响应体。
     *
     * <p>刻意返回 JSON 而不是默认的空响应：前端需要能区分"没登录"与"接口挂了"，
     * 而 401 + 空响应体会被开发者在控制台里误读成网络错误。
     */
    private static AuthenticationEntryPoint authenticationEntryPoint(AuthProperties properties, ObjectMapper json) {
        return (request, response, exception) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", "unauthenticated");
            payload.put("message", "缺少或无效的凭证：请在 Authorization 头提供 Bearer 令牌");
            payload.put("hint", properties.jwtMode()
                    ? "JWT 模式：令牌必须由 " + properties.getJwt().getIssuer()
                            + " 签发、在有效期内，且受众匹配 dg.auth.jwt.audience"
                    : "静态令牌模式：请填入 dg.auth.tokens 中配置的令牌");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"dg-platform\"");
            writeJson(response, HttpStatus.UNAUTHORIZED, payload, json);
        };
    }

    /** 403 的响应体（当前授权在控制器层抛出，这里兜住安全层可能新增的拒绝）。 */
    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper json) {
        return (request, response, exception) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", "forbidden");
            payload.put("message", "无权执行该操作");
            payload.put("hint", "授权判定唯一入口：com.datagovernance.policy.AccessPolicy（docs/09 §9.7）");
            writeJson(response, HttpStatus.FORBIDDEN, payload, json);
        };
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status,
                                  Map<String, Object> payload, ObjectMapper json) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), payload);
    }

    /** 把 Bearer 令牌解成主体；失败时**什么都不设置**，由安全层给出 401。 */
    @Component
    public static class TokenAuthFilter extends OncePerRequestFilter {

        private final AuthProperties properties;
        private final TokenAuthenticator authenticator;

        public TokenAuthFilter(AuthProperties properties, TokenAuthenticator authenticator) {
            this.properties = properties;
            this.authenticator = authenticator;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (properties.disabled()) {
                setPrincipal(new Principal("dev@local", "开发模式（未认证）", java.util.Set.of(Roles.ADMIN)));
                chain.doFilter(request, response);
                return;
            }

            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = header.substring(7).trim();
                if (!token.isEmpty()) {
                    authenticator.authenticate(token).ifPresent(this::setPrincipal);
                }
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
