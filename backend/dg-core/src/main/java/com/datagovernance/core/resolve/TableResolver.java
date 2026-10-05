package com.datagovernance.core.resolve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.UrnUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把外部系统给的"裸表名"解析成平台 URN。
 *
 * <p>为什么这件事必须是**一个**实现：同一张表在 SQL 解析（血缘）与引擎审计（真实访问）里
 * 若用两套解析规则，就会出现"血缘认出来了、审计没认出来"的不一致 ——
 * 而依据这些解析结果做判断的正是**复核与回收**。因此本类放在 dg-core，由两个模块共用。
 *
 * <p>解析策略（保守优先，宁可返回 null 也不猜）：
 * <ol>
 *   <li>已经是平台 URN → 直接返回；</li>
 *   <li>恰好 5 段（platform.database.schema.table 的变体）→ 直接按 URN 规则构造；</li>
 *   <li>否则按**后缀**在命名空间内唯一匹配（`catalog.schema.table` / `schema.table`）；</li>
 *   <li>再退一步按 display_name 唯一匹配；</li>
 *   <li>0 条或多条命中都返回 null（多义即不解析）。</li>
 * </ol>
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
