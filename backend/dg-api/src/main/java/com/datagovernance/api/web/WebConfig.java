package com.datagovernance.api.web;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把前端构建产物（{@code web/dist}）作为静态资源托管，并支持 SPA 路由回退。
 *
 * <p>这样生产形态只有一个端口：{@code http://host:8081/} 既是 API 也是界面。
 * 开发形态仍可用 Vite dev server（{@code pnpm dev}，带 /api 代理）以获得热更新。
 *
 * <p>路径通过 {@code dg.web-dist} 配置，默认相对仓库根的 {@code web/dist}。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String webDist;

    public WebConfig(@Value("${dg.web-dist:../web/dist}") String webDist) {
        this.webDist = webDist.endsWith("/") ? webDist : webDist + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态产物目录是 static/（见 web/vite.config.ts 的 assetsDir）。
        // 不能用 assets/：SPA 有一条 /assets 路由（资产页），撞名会让该页面直接打不开。
        //
        // 缓存策略：Vite 构建产物文件名带内容哈希，因此可以长期公开缓存（immutable）。
        // 显式设置 Cache-Control 还有一层作用：Spring Security 默认给响应加 no-store，
        // 只有响应里已经有 Cache-Control 时它才会跳过 —— 不设这一行，带哈希的静态资源
        // 每次刷新都要重新下载。index.html 不在此列（它必须每次回源，否则升级后仍加载旧入口）。
        registry.addResourceHandler("/static/**")
                .addResourceLocations("file:" + webDist + "static/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/favicon.ico").addResourceLocations("file:" + webDist);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA 路由：非 /api 前缀的路径统一回退到 index.html，由前端路由接管
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|actuator|docs|api-docs|swagger-ui|static|healthz)[^\\.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|actuator|docs|api-docs|swagger-ui|static|healthz)[^\\.]*}/**")
                .setViewName("forward:/index.html");
    }
}
