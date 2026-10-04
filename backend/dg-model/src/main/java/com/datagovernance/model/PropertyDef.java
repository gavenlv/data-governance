package com.datagovernance.model;

import java.util.List;
import java.util.Map;

/**
 * 属性定义（docs/08 §6）。
 *
 * @param name        属性名
 * @param type        string | integer | number | boolean | object | array | enum
 * @param required    是否必填
 * @param defaultValue 默认值（可空）
 * @param enumValues  枚举取值（type=enum 时）
 * @param itemsType   数组元素类型（type=array 时）
 */
public record PropertyDef(
        String name,
        String type,
        boolean required,
        Object defaultValue,
        List<String> enumValues,
        String itemsType) {

    public static PropertyDef fromMap(Map<String, Object> raw) {
        Object name = raw.get("name");
        Object type = raw.get("type");
        if (name == null || type == null) {
            throw ModelException.of("property 定义缺少 name/type: %s", raw);
        }
        Object values = raw.get("values");
        return new PropertyDef(
                String.valueOf(name),
                String.valueOf(type),
                Boolean.TRUE.equals(raw.get("required")),
                raw.get("default"),
                values instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
                raw.get("itemsType") == null ? null : String.valueOf(raw.get("itemsType")));
    }
}
