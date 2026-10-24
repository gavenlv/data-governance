package com.datagovernance.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 响应安全头配置（生产基线的一部分）。
 *
 * <p>默认值的选择原则：**默认安全，显式放宽**。CSP 默认开启，只有明确写成空串才关闭；
 * HSTS 默认关闭，因为它在纯 HTTP 的开发环境里没有意义，而在 HTTPS 之后再开才会生效
 * （在 HTTP 上发 HSTS 会被浏览器忽略，但如果同一个域名以后用 HTTP 访问，反而会留下隐患）。
 *
 * <p>CSP 里 {@code style-src} 允许 {@code 'unsafe-inline'} 是**有意的让步**：
 * Ant Design 在运行时把样式注入 {@code <style>} 标签，禁用内联样式会让整个界面失去样式。
 * {@code script-src} 则是严格 {@code 'self'} —— 界面没有内联脚本（{@code web/index.html} 已核对），
 * 这条限制真正挡住的正是 XSS 最常用的执行路径。
 */
@ConfigurationProperties(prefix = "dg.security")
public class SecurityProperties {

    /** 界面（Vite 构建产物）可用的 CSP；与 Swagger UI 的内联脚本冲突，因此 /docs 路径豁免。 */
    public static final String DEFAULT_CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    /** 设为空串即关闭 CSP（不建议）。 */
    private String contentSecurityPolicy = DEFAULT_CSP;

    /** 0 = 不发送 HSTS 头（开发默认）；生产建议 31536000（一年）。 */
    private long hstsMaxAgeSeconds = 0;

    private boolean hstsIncludeSubDomains = true;

    public String getContentSecurityPolicy() {
        return contentSecurityPolicy;
    }

    public void setContentSecurityPolicy(String contentSecurityPolicy) {
        this.contentSecurityPolicy = contentSecurityPolicy;
    }

    public long getHstsMaxAgeSeconds() {
        return hstsMaxAgeSeconds;
    }

    public void setHstsMaxAgeSeconds(long hstsMaxAgeSeconds) {
        this.hstsMaxAgeSeconds = hstsMaxAgeSeconds;
    }

    public boolean isHstsIncludeSubDomains() {
        return hstsIncludeSubDomains;
    }

    public void setHstsIncludeSubDomains(boolean hstsIncludeSubDomains) {
        this.hstsIncludeSubDomains = hstsIncludeSubDomains;
    }
}
