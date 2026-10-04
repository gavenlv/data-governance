package com.datagovernance.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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
        registry.addResourceHandler("/assets/**").addResourceLocations("file:" + webDist + "assets/");
        registry.addResourceHandler("/favicon.ico").addResourceLocations("file:" + webDist);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA 路由：非 /api 前缀的路径统一回退到 index.html，由前端路由接管
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|actuator|docs|api-docs|swagger-ui|assets|healthz)[^\\.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|actuator|docs|api-docs|swagger-ui|assets|healthz)[^\\.]*}/**")
                .setViewName("forward:/index.html");
    }
}
