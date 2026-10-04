package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.datagovernance.api.security.SecurityConfig;
import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import com.datagovernance.model.CapabilityStatus;
import com.datagovernance.model.ModelRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力清单、模型摘要与身份信息。
 *
 * <p>{@code /api/v1/capabilities} 是整个项目「未实现必须显式标注」的对外出口：
 * 界面据此渲染状态徽标与设计说明，使用者不必靠试错发现哪些是占位。
 */
@RestController
public class CapabilityController {

    private final List<CapabilityProvider> providers;
    private final ModelRegistry registry;
    private final JdbcTemplate jdbc;

    public CapabilityController(List<CapabilityProvider> providers, ModelRegistry registry, JdbcTemplate jdbc) {
        this.providers = providers;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** 公开健康检查（探活用，不含库内细节）。 */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Map.of("status", "ok", "controlPlane", "java-spring-boot", "version", "0.1.0");
        } catch (RuntimeException e) {
            return Map.of("status", "degraded", "error", String.valueOf(e.getMessage()));
        }
    }

    /** 能力清单：按域分组，含实现状态与缺口说明。 */
    @GetMapping("/api/v1/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, List<CapabilityDescriptor>> byDomain = new TreeMap<>();
        int implemented = 0;
        int partial = 0;
        int missing = 0;

        for (CapabilityProvider provider : providers) {
            for (CapabilityDescriptor descriptor : provider.capabilities()) {
                byDomain.computeIfAbsent(descriptor.domain(), k -> new java.util.ArrayList<>()).add(descriptor);
                switch (descriptor.status()) {
                    case IMPLEMENTED -> implemented++;
                    case PARTIAL -> partial++;
                    case NOT_IMPLEMENTED -> missing++;
                }
            }
        }
        byDomain.values().forEach(list -> list.sort(java.util.Comparator.comparing(CapabilityDescriptor::id)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("controlPlane", Map.of(
                "language", "Java 21 + Spring Boot 3",
                "note", "控制面已按 docs/10 §1 重建为 Java；Python 实现保留为参考实现（docs/21 §2）"));
        payload.put("summary", Map.of(
                "implemented", implemented,
                "partial", partial,
                "notImplemented", missing,
                "total", implemented + partial + missing));
        payload.put("domains", byDomain);
        payload.put("statusLegend", Map.of(
                CapabilityStatus.IMPLEMENTED.name(), "已实现并有验证",
                CapabilityStatus.PARTIAL.name(), "部分实现，缺口见 notes",
                CapabilityStatus.NOT_IMPLEMENTED.name(), "未实现，仅设计与接口占位"));
        return payload;
    }

    /** 模型摘要（唯一事实源）。 */
    @GetMapping("/api/v1/model")
    public Map<String, Object> model() {
        Map<String, Object> payload = new LinkedHashMap<>(registry.summary());
        payload.put("entityTypes", registry.entityTypeNames());
        payload.put("aspectTypes", registry.aspectTypeNames());
        payload.put("relationshipTypes", registry.relationshipTypeNames());
        payload.put("lineageRelationships", registry.lineageRelationshipNames());
        payload.put("entityAspects", registry.entityTypeNames().stream().collect(
                java.util.LinkedHashMap::new,
                (m, name) -> m.put(name, registry.entityType(name).aspects()),
                Map::putAll));
        return payload;
    }

    /** 当前身份（排障：让用户看清自己能被允许做什么、能看到什么分级）。 */
    @GetMapping("/api/v1/me")
    public Map<String, Object> me() {
        com.datagovernance.policy.Subject subject =
                com.datagovernance.api.security.Subjects.current();
        if (subject == null) {
            return Map.of("authenticated", false);
        }
        Map<String, Object> payload = new LinkedHashMap<>(
                com.datagovernance.policy.AccessPolicy.describe(subject));
        payload.put("authenticated", true);
        payload.put("authMethod", "static-token");
        payload.put("authGap", "生产须迁到 OIDC/JWKS（Java 侧对齐未实现，见 policy.* 能力说明）");
        return payload;
    }
}
