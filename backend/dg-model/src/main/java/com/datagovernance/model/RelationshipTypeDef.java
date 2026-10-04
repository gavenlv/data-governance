package com.datagovernance.model;

import java.util.List;
import java.util.Map;

/**
 * 关系类型定义（docs/08 §4.6）。
 *
 * <p>category 决定删除/级联语义（借鉴 Atlas relationshipCategory）：
 * <ul>
 *   <li>COMPOSITION 子对象不能脱离父对象 → 删除父对象时级联软删</li>
 *   <li>AGGREGATION 子对象可独立存在 → 仅解除关系</li>
 *   <li>ASSOCIATION 纯引用 → 只删边</li>
 * </ul>
 */
public record RelationshipTypeDef(
        String name,
        String displayName,
        String category,
        List<String> fromTypes,
        List<String> toTypes,
        boolean lineage,
        String description) {

    public static final List<String> CATEGORIES = List.of("COMPOSITION", "AGGREGATION", "ASSOCIATION");

    public static RelationshipTypeDef fromMap(Map<String, Object> raw) {
        Object name = raw.get("name");
        if (name == null) {
            throw ModelException.of("关系定义缺少 name: %s", raw);
        }
        String category = String.valueOf(raw.getOrDefault("category", "ASSOCIATION"));
        if (!CATEGORIES.contains(category)) {
            throw ModelException.of("关系 %s 的 category=%s 非法，必须是 %s", name, category, CATEGORIES);
        }
        return new RelationshipTypeDef(
                String.valueOf(name),
                String.valueOf(raw.getOrDefault("displayName", name)),
                category,
                EntityTypeDef.stringList(raw.get("from")),
                EntityTypeDef.stringList(raw.get("to")),
                Boolean.TRUE.equals(raw.get("lineage")),
                String.valueOf(raw.getOrDefault("description", "")));
    }
}
