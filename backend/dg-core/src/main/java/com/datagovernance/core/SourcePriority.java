package com.datagovernance.core;

import java.util.Map;

/**
 * 字段级来源优先级（ADR-005 / docs/08 §5）。
 *
 * <p>核心承诺：<b>采集绝不覆盖人工内容</b> —— 这是企业客户投诉最多的问题，
 * 也是「目录失去信任」最常见的起点。
 */
public enum SourcePriority {

    MANUAL(40),
    IMPORTED(30),
    AI_GENERATED(20),
    AUTO_COLLECTED(10);

    private final int weight;

    SourcePriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean outranks(SourcePriority other) {
        return other == null || this.weight > other.weight;
    }

    public static SourcePriority of(String name) {
        if (name == null) {
            return AUTO_COLLECTED;
        }
        for (SourcePriority value : values()) {
            if (value.name().equalsIgnoreCase(name.trim())) {
                return value;
            }
        }
        throw new IllegalArgumentException(
                "未知来源 " + name + "，允许值：" + java.util.Arrays.toString(values()));
    }

    public static Map<String, Integer> weights() {
        return java.util.Arrays.stream(values())
                .collect(java.util.LinkedHashMap::new, (m, v) -> m.put(v.name(), v.weight), Map::putAll);
    }
}
