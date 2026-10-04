package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.UrnUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把 SQL 里的表名解析为平台内的 Dataset URN（docs/09 §9.2）。
 *
 * <p>解析顺序（命中即返回）：
 * <ol>
 *   <li>已经是平台 URN → 直接用</li>
 *   <li>完整路径 {@code ns.platform.db.schema.table}（5 段）→ 直接构造</li>
 *   <li>URN 后缀匹配 {@code %.db.schema.table}（要求唯一）</li>
 *   <li>按 display_name 精确匹配（要求唯一）</li>
 * </ol>
 *
 * <p><b>解析不到就返回 null —— 绝不猜</b>。跨 schema 同名表存在歧义时宁可缺边：
 * 一条错的血缘会让人在排查故障时走向错误的方向，代价远高于缺一条边。
 * 缺边会被计入 {@code unresolvedTables} 并在解析质量报告里可见。
 */
@Component
public class TableResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new LinkedHashMap<>();

    public TableResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单次解析（带缓存，用在一次解析会话内）。 */
    public String resolve(String raw, String namespace) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = namespace + "|" + raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String urn = resolveUncached(raw.trim(), namespace);
        cache.put(key, urn);
        return urn;
    }

    private String resolveUncached(String raw, String namespace) {
        if (raw.startsWith(UrnUtils.PREFIX)) {
            try {
                UrnUtils.parse(raw);
                return raw;
            } catch (RuntimeException e) {
                return null;
            }
        }

        List<String> parts = new ArrayList<>();
        for (String piece : raw.split("\\.")) {
            String cleaned = piece.trim().replace("`", "").replace("\"", "");
            if (!cleaned.isEmpty()) {
                parts.add(cleaned);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }

        if (parts.size() == 5) {
            try {
                return UrnUtils.build("Dataset", sanitize(parts));
            } catch (RuntimeException e) {
                return null;
            }
        }

        String suffix = String.join(".",
                parts.subList(Math.max(0, parts.size() - Math.min(parts.size(), 5)), parts.size()));
        String bySuffix = unique("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Dataset' AND deleted_at IS NULL
                   AND namespace = ? AND urn LIKE ?
                """, namespace, "%" + suffix);
        if (bySuffix != null) {
            return bySuffix;
        }

        return unique("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Dataset' AND deleted_at IS NULL
                   AND namespace = ? AND display_name = ?
                """, namespace, parts.get(parts.size() - 1));
    }

    /** 只在结果唯一时返回；0 条或多条都返回 null。 */
    private String unique(String sql, Object... params) {
        List<String> rows = jdbc.queryForList(sql, String.class, params);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public static String[] sanitize(List<String> parts) {
        return parts.stream().map(UrnUtils::sanitizeSegment).toArray(String[]::new);
    }

    /** 列 URN（dataset 的子资源）。 */
    public static String columnUrn(String datasetUrn, String column) {
        return UrnUtils.column(datasetUrn, UrnUtils.sanitizeSegment(column));
    }

    /** 每个解析会话一份缓存（避免长生命周期缓存读到旧元数据）。 */
    public void clearCache() {
        cache.clear();
    }
}
