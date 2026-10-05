package com.datagovernance.core.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 检索（docs/09 §9.3）。
 *
 * <p><b>本实现只做 BM25 侧的两项能力</b>：标识符切分（{@code _} / 驼峰）与**前置**授权过滤。
 * 向量检索、RRF 融合排序、cross-encoder 重排属 {@code ai.semantic-search}（Batch 5），
 * 不在本类里假装实现。
 *
 * <p>关于授权过滤的两个硬约束：
 * <ol>
 *   <li><b>必须前置</b>：条件注入 SQL 后再执行。结果后过滤会泄露总数与分面统计 ——
 *       "共 132 条，其中 47 条你无权查看"本身就是泄露。</li>
 *   <li><b>分面统计也必须前置过滤</b>：所以 facets 与结果用同一套 WHERE，而不是先全量聚合再筛。</li>
 * </ol>
 *
 * <p>{@code visibleLevels} 由 {@code com.datagovernance.policy.AccessPolicy} 计算并强制传入
 * （dg-core 不依赖 dg-policy，避免反向依赖）。参数<b>不可为 null</b>：
 * 让"忘记传可见范围"变成编译期/运行期的显式失败，而不是静默返回全部资产。
 */
@Service
public class SearchService {

    private final JdbcTemplate jdbc;
    private final SearchIndexConsumer consumer;

    public SearchService(JdbcTemplate jdbc, SearchIndexConsumer consumer) {
        this.jdbc = jdbc;
        this.consumer = consumer;
    }

    /** 检索。 */
    public SearchResult search(String query, String entityType, String platform, int limit,
                               List<String> visibleLevels) {
        if (visibleLevels == null || visibleLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "visibleLevels 不能为空：检索必须前置注入可见范围（docs/09 §9.3）");
        }
        int capped = Math.max(1, Math.min(limit, 200));

        StringBuilder where = new StringBuilder(" WHERE COALESCE(classification, 'L2') IN ("
                + placeholders(visibleLevels.size()) + ")");
        List<Object> whereParams = new ArrayList<>(visibleLevels);

        String trimmed = query == null ? "" : query.trim();
        // 查询侧必须做与写入侧**相同**的中文二元切分，否则写入切了、查询没切，中文检索依然是坏的
        String tokenizedQuery = IdentifierTokenizer.tokenizeQuery(trimmed);
        boolean hasQuery = !tokenizedQuery.isBlank();
        if (hasQuery) {
            where.append(" AND tsv @@ plainto_tsquery('simple', ?)");
            whereParams.add(tokenizedQuery);
        }
        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND entity_type = ?");
            whereParams.add(entityType);
        }
        if (platform != null && !platform.isBlank()) {
            where.append(" AND platform = ?");
            whereParams.add(platform);
        }

        String select = """
                SELECT urn, entity_type, display_name, namespace, platform, container,
                       description, tags, owners, classification, indexed_at, indexed_watermark
                  FROM search_doc
                """ + where;

        // 结果查询 = WHERE 参数 + 排序用的 rank 参数 + LIMIT
        List<Object> resultParams = new ArrayList<>(whereParams);
        String order;
        if (hasQuery) {
            order = " ORDER BY ts_rank(tsv, plainto_tsquery('simple', ?)) DESC, indexed_at DESC, urn";
            resultParams.add(tokenizedQuery);
        } else {
            order = " ORDER BY indexed_at DESC, urn";
        }
        resultParams.add(capped);

        List<Map<String, Object>> rows = jdbc.queryForList(select + order + " LIMIT ?",
                resultParams.toArray());
        List<Map<String, Object>> results = rows.stream().map(SearchService::toResult).toList();

        // 分面必须与结果用**同一套 WHERE 参数**：漏参数会让分面统计在异常时静默失效，
        // 而分面恰恰是最容易泄露"无权资产存在性"的地方（docs/09 §9.3）
        return new SearchResult(trimmed, results.size(), results,
                facets(where.toString(), whereParams), consumer.lag(), List.copyOf(visibleLevels));
    }

    /**
     * 分面统计（与结果同一套 WHERE，因此不会泄露无权资产的存在性）。
     *
     * <p>刻意不含 {@code total} 的全量口径：只返回"你可见范围内的分布"。
     */
    private Map<String, Object> facets(String where, List<Object> params) {
        List<Map<String, Object>> byType = jdbc.queryForList(
                "SELECT entity_type AS key, COUNT(*) AS count FROM search_doc" + where
                        + " GROUP BY entity_type ORDER BY count DESC, key", params.toArray());
        List<Map<String, Object>> byPlatform = jdbc.queryForList(
                "SELECT COALESCE(platform, '(无平台)') AS key, COUNT(*) AS count FROM search_doc" + where
                        + " GROUP BY platform ORDER BY count DESC, key", params.toArray());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", normalizeFacet(byType));
        payload.put("platform", normalizeFacet(byPlatform));
        return payload;
    }

    private static List<Map<String, Object>> normalizeFacet(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", row.get("key"));
            item.put("count", row.get("count"));
            out.add(item);
        }
        return out;
    }

    /** 检索建议：空结果时给出最接近的候选，而不是一句"没有结果"。 */
    public List<String> suggestions(String query, List<String> visibleLevels) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String prefix = query.trim().split("\\s+")[0];
        return jdbc.queryForList("""
                SELECT display_name FROM search_doc
                 WHERE COALESCE(classification, 'L2') IN (""" + placeholders(visibleLevels.size()) + """
                       )
                   AND display_name ILIKE ?
                 ORDER BY length(display_name), display_name
                 LIMIT 5
                """, String.class, concat(visibleLevels, "%" + prefix + "%"));
    }

    private static Object[] concat(List<String> levels, String extra) {
        Object[] params = new Object[levels.size() + 1];
        for (int i = 0; i < levels.size(); i++) {
            params[i] = levels.get(i);
        }
        params[levels.size()] = extra;
        return params;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static Map<String, Object> toResult(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("urn", row.get("urn"));
        item.put("entityType", row.get("entity_type"));
        item.put("displayName", row.get("display_name"));
        item.put("namespace", row.get("namespace"));
        item.put("platform", row.get("platform"));
        item.put("container", row.get("container"));
        item.put("description", row.get("description"));
        item.put("tags", toStringList(row.get("tags")));
        item.put("owners", toStringList(row.get("owners")));
        item.put("classification", row.get("classification"));
        item.put("indexedAt", row.get("indexed_at"));
        item.put("indexedWatermark", row.get("indexed_watermark"));
        return item;
    }

    /**
     * 把 JDBC 的 {@code PgArray} 转成普通 List。
     *
     * <p>必须显式转换：直接把 {@code PgArray} 交给 Jackson 会先抛
     * "No serializer found for ... V3ReplicationProtocol" —— 因为序列化器会顺着
     * {@code array.getResultSet().getStatement().getConnection()} 一路爬进 JDBC 内部对象。
     * 这既是一个 500，也是一个"把数据库连接对象暴露给序列化器"的隐患。
     */
    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object array = sqlArray.getArray();
                if (array instanceof Object[] objects) {
                    List<String> out = new ArrayList<>(objects.length);
                    for (Object item : objects) {
                        out.add(item == null ? null : String.valueOf(item));
                    }
                    return out;
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("读取数组列失败：" + e.getMessage(), e);
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? null : String.valueOf(item)).toList();
        }
        return List.of(String.valueOf(value));
    }

    /** 检索结果。 */
    public record SearchResult(String query, int count, List<Map<String, Object>> results,
                               Map<String, Object> facets, Map<String, Object> indexLag,
                               List<String> visibleLevels) {

        public Map<String, Object> asMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("count", count);
            payload.put("results", results);
            payload.put("facets", facets);
            payload.put("indexLag", indexLag.get("lag"));
            payload.put("index", indexLag);
            payload.put("visibleLevels", visibleLevels);
            return payload;
        }
    }
}
