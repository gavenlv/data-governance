package com.datagovernance.model;

import java.util.List;
import java.util.Map;

/**
 * 实体类型定义（docs/08 §3）。
 *
 * @param name            类型名（URN 中出现的类型段）
 * @param displayName     展示名
 * @param description     说明
 * @param parentTypes     父类型（用于层级与权限继承）
 * @param aspects         允许挂载的 aspect
 * @param namespaceScoped 是否按命名空间隔离
 */
public record EntityTypeDef(
        String name,
        String displayName,
        String description,
        List<String> parentTypes,
        List<String> aspects,
        boolean namespaceScoped) {

    @SuppressWarnings("unchecked")
    public static EntityTypeDef fromMap(Map<String, Object> raw) {
        Object name = raw.get("name");
        if (name == null) {
            throw ModelException.of("实体定义缺少 name: %s", raw);
        }
        return new EntityTypeDef(
                String.valueOf(name),
                String.valueOf(raw.getOrDefault("displayName", name)),
                String.valueOf(raw.getOrDefault("description", "")),
                stringList(raw.get("parentTypes")),
                stringList(raw.get("aspects")),
                Boolean.TRUE.equals(raw.get("namespaceScoped")));
    }

    static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }
}
