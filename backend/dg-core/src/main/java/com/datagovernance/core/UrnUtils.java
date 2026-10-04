package com.datagovernance.core;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URN 构造与解析（docs/08 §2）。
 *
 * <p>格式：{@code urn:dg:<entityType>:<part1>.<part2>...}
 *
 * <p>设计约束：
 * <ul>
 *   <li>URN 一旦分配永不复用（删除进墓碑）；</li>
 *   <li>列作为 dataset 的子资源（点号续接），保证列级血缘指向稳定标识；</li>
 *   <li><b>路径段不允许含点</b> —— 真实世界的对象名经常含点（如数据湖文件名
 *       {@code orders_2026.parquet}），因此必须规范化段名，同时把原名保留为展示名。</li>
 * </ul>
 */
public final class UrnUtils {

    public static final String PREFIX = "urn:dg:";
    private static final Pattern URN_PATTERN = Pattern.compile("^urn:dg:([A-Za-z][A-Za-z0-9]*):(.+)$");

    private UrnUtils() {
    }

    public record Parsed(String entityType, String path) {

        public List<String> parts() {
            return Arrays.asList(path.split("\\."));
        }

        public String lastPart() {
            List<String> parts = parts();
            return parts.get(parts.size() - 1);
        }
    }

    public static String build(String entityType, String... parts) {
        if (entityType == null || entityType.isEmpty() || !Character.isLetter(entityType.charAt(0))) {
            throw new IllegalArgumentException("entityType 非法：" + entityType);
        }
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("URN 至少需要一个路径段");
        }
        for (String part : parts) {
            if (part == null || part.isEmpty() || part.contains(".") || part.contains(":") || part.contains(" ")) {
                throw new IllegalArgumentException("路径段非法（不可含 . : 空格）：" + part);
            }
        }
        return PREFIX + entityType + ":" + String.join(".", parts);
    }

    public static Parsed parse(String urn) {
        Matcher matcher = URN_PATTERN.matcher(urn == null ? "" : urn);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("URN 格式非法：" + urn + "（期望 urn:dg:<EntityType>:<a.b.c>）");
        }
        return new Parsed(matcher.group(1), matcher.group(2));
    }

    public static String dataset(String namespace, String platform, String database, String schema, String table) {
        return build("Dataset", namespace, platform, database, schema, table);
    }

    public static String column(String datasetUrn, String columnName) {
        List<String> parts = new java.util.ArrayList<>(parse(datasetUrn).parts());
        parts.add(columnName);
        return build("Column", parts.toArray(String[]::new));
    }

    public static String platform(String namespace, String platform) {
        return build("Platform", namespace, platform);
    }

    public static String container(String namespace, String platform, String database, String schema) {
        return build("Container", namespace, platform, database, schema);
    }

    /**
     * 把任意名字规范化为合法的 URN 路径段（点/冒号/空格 → 下划线）。
     */
    public static String sanitizeSegment(String name) {
        String value = name == null ? "" : name.trim();
        for (char ch : new char[] {'.', ':', ' ', '/', '\\', '#', '?'}) {
            value = value.replace(ch, '_');
        }
        return value.isEmpty() ? "_" : value;
    }

    public static boolean isUrn(String value) {
        return value != null && URN_PATTERN.matcher(value).matches();
    }
}
