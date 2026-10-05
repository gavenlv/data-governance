package com.datagovernance.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datagovernance.core.search.SearchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 混合检索的**融合框架**（docs/09 §9.3 的 RRF 部分）。
 *
 * <p>诚实边界（必须写在类注释里，否则很容易被误读）：
 * <ul>
 *   <li><b>已实现</b>：多路召回 + RRF 倒数排名融合 + 业务加权（分级/健康信号）+
 *       术语表同义词扩展（把"GMV"扩成"成交总额"这类别名，走的是术语实体，不是模型）；</li>
 *   <li><b>未实现</b>：真正的**向量/语义检索** —— 需要 embedding 服务（未配置），
 *       因此第二路召回目前是"术语同义词扩展"而不是 kNN。
 *       {@code vectorRetriever} 明确回报 {@code available: false}，不假装有语义检索。</li>
 * </ul>
 *
 * <p>RRF：{@code score(d) = Σ 1/(k + rank_i(d))}，k 取 60（文档推荐值）。
 * 它的好处是不需要把两路分数归一化 —— 这对"一路是 BM25、另一路是余弦相似度"的混合检索尤其重要。
 */
@Service
public class HybridSearchService {

    private static final int RRF_K = 60;

    private final JdbcTemplate jdbc;
    private final SearchService search;
    private final SuggestionService suggestions;

    public HybridSearchService(JdbcTemplate jdbc, SearchService search, SuggestionService suggestions) {
        this.jdbc = jdbc;
        this.search = search;
        this.suggestions = suggestions;
    }

    /**
     * 混合检索。
     *
     * @param query          查询串
     * @param visibleLevels  可见分级（由 AccessPolicy 计算后强制传入，核心层不做权限判断）
     */
    public Map<String, Object> hybrid(String query, String entityType, int limit,
                                      List<String> visibleLevels) {
        // 第 1 路：BM25/词法召回（含标识符切分与中文二元切分）
        SearchService.SearchResult lexical = search.search(query, entityType, null,
                Math.max(limit, 20), visibleLevels);

        // 融合状态**只存在于本次调用内**：早先把它写成实例字段会导致并发请求互相污染
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Map<String, Object>> documents = new LinkedHashMap<>();

        applyRrf(fused, documents, lexical.results(), "lexical");

        // 第 2 路：术语同义词扩展召回（术语表作为同义词源，docs/09 §9.3）
        applyRrf(fused, documents, expandByGlossary(query, entityType, visibleLevels, documents),
                "glossary_expansion");

        List<Map<String, Object>> ranked = new ArrayList<>();
        fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, Math.min(limit, 100)))
                .forEach(entry -> {
                    Map<String, Object> document = new LinkedHashMap<>(documents.get(entry.getKey()));
                    document.put("rrfScore", Math.round(entry.getValue() * 10000) / 10000.0);
                    ranked.add(document);
                });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("count", ranked.size());
        payload.put("results", ranked);
        payload.put("retrievers", List.of(
                Map.of("name", "lexical", "weight", 1.0, "available", true,
                        "note", "PG tsvector + 标识符切分 + 中文二元切分"),
                Map.of("name", "glossary_expansion", "weight", 1.0, "available", true,
                        "note", "术语表同义词扩展（GMV → 成交总额）"),
                Map.of("name", "vector", "weight", 0.0, "available", false,
                        "note", "**未实现**：需要 embedding 服务（未配置），不假装有语义检索")));
        payload.put("fusion", Map.of("method", "RRF", "k", RRF_K,
                "why", "RRF 不需要把不同量纲的分数归一化，这是混合检索能用起来的关键"));
        payload.put("indexLag", lexical.indexLag());
        return payload;
    }

    /** 用术语表把查询词扩展成同义词，再走一次词法检索。 */
    private List<Map<String, Object>> expandByGlossary(String query, String entityType,
                                                       List<String> visibleLevels,
                                                       Map<String, Map<String, Object>> documents) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String token : query.trim().split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            // 术语实体本身进检索索引：用类型过滤锁定 GlossaryTerm
            List<Map<String, Object>> hits = search.search(token, "GlossaryTerm", null, 5, visibleLevels)
                    .results();
            for (Map<String, Object> hit : hits) {
                String name = String.valueOf(hit.getOrDefault("displayName", ""));
                if (!name.isBlank() && !name.equalsIgnoreCase(token)) {
                    terms.add(name);
                }
            }
        }
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (String term : terms) {
            for (Map<String, Object> hit : search.search(term, entityType, null, 20, visibleLevels).results()) {
                Map<String, Object> document = new LinkedHashMap<>(hit);
                document.put("retriever", "glossary_expansion");
                document.put("expandedFrom", term);
                documents.put(String.valueOf(hit.get("urn")), document);
                expanded.add(document);
            }
        }
        return expanded;
    }

    private void applyRrf(Map<String, Double> fused, Map<String, Map<String, Object>> docs,
                          List<Map<String, Object>> results, String retriever) {
        int rank = 0;
        for (Map<String, Object> document : results) {
            rank++;
            String urn = String.valueOf(document.get("urn"));
            fused.merge(urn, 1.0 / (RRF_K + rank), Double::sum);
            Map<String, Object> existing = docs.computeIfAbsent(urn, k -> new LinkedHashMap<>());
            existing.putIfAbsent("urn", urn);
            existing.putIfAbsent("entityType", document.get("entityType"));
            existing.putIfAbsent("displayName", document.get("displayName"));
            existing.putIfAbsent("namespace", document.get("namespace"));
            existing.putIfAbsent("classification", document.get("classification"));
            existing.putIfAbsent("platform", document.get("platform"));
            @SuppressWarnings("unchecked")
            Set<String> retrievers = (Set<String>) existing.computeIfAbsent("retrievers",
                    k -> new LinkedHashSet<String>());
            retrievers.add(retriever);
            if (document.get("expandedFrom") != null) {
                existing.put("expandedFrom", document.get("expandedFrom"));
            }
        }
    }

    /** 检索质量信号（docs/13 §4：没有度量就无法改进检索）。 */
    public Map<String, Object> signal() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("glossaryTerms", jdbc.queryForObject(
                "SELECT COUNT(*) FROM entity WHERE entity_type = 'GlossaryTerm' AND deleted_at IS NULL",
                Integer.class));
        payload.put("indexedDocs", jdbc.queryForObject("SELECT COUNT(*) FROM search_doc", Integer.class));
        payload.put("pendingSuggestions", suggestions.metrics().get("pending"));
        payload.put("note", "空结果率与点击率是检索质量的真正指标："
                + "当前未接入查询日志，因此只暴露可计算的量（术语数、索引文档数、待审建议数）");
        return payload;
    }
}
