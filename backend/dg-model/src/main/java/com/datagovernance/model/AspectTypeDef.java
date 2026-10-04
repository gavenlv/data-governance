package com.datagovernance.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aspect 类型定义（docs/08 §4）。
 *
 * <p>{@code sourceTracked=true} 的 aspect 参与「字段级来源优先级」判定（ADR-005）：
 * MANUAL &gt; IMPORTED &gt; AI_GENERATED &gt; AUTO_COLLECTED —— 采集不得覆盖人工内容。
 */
public record AspectTypeDef(
        String name,
        String displayName,
        List<PropertyDef> properties,
        boolean sourceTracked,
        boolean timeSeries) {

    public static AspectTypeDef fromMap(Map<String, Object> raw) {
        Object name = raw.get("name");
        if (name == null) {
            throw ModelException.of("aspect 定义缺少 name: %s", raw);
        }
        List<PropertyDef> props = ((List<?>) raw.getOrDefault("properties", List.of())).stream()
                .map(item -> PropertyDef.fromMap(castMap(item)))
                .toList();
        return new AspectTypeDef(
                String.valueOf(name),
                String.valueOf(raw.getOrDefault("displayName", name)),
                props,
                Boolean.TRUE.equals(raw.get("sourceTracked")),
                Boolean.TRUE.equals(raw.get("timeSeries")));
    }

    public Optional<PropertyDef> property(String propertyName) {
        return properties.stream().filter(p -> p.name().equals(propertyName)).findFirst();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw ModelException.of("期望对象但收到: %s", value);
    }
}
