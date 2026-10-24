package com.datagovernance.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.ai.embedding.EmbeddingProvider;
import com.datagovernance.ai.embedding.EmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 向量索引与向量召回（docs/13 §1.1「语义检索」）。
 *
 * <p>结构上分三步，每一步的边界都写在方法注释里：
 * <ol>
 *   <li>{@link #rebuild}：把检索文档向量化并落库（按内容哈希增量，模型换了必须重建）；</li>
 *   <li>{@link #search}：**先做授权前置过滤**（SQL 里按可见分级），再算余弦排序；</li>
 *   <li>{@link #status}：让"有没有索引、用哪个模型、覆盖率多少"可观测。</li>
 * </ol>
 *
 * <p><b>诚实的规模边界</b>：默认部署没有 pgvector，因此召回是"取候选后在 Java 里算余弦"，
 * 候选数量有上限（{@code dg.ai.embedding.max-scan}，默认 2 万）。超过上限时**显式回报
 * truncated=true**，而不是悄悄只算一部分。装了 pgvector 后把这里换成带 ANN 索引的 SQL 即可，
 * 表结构与接口不变。
 */
@Service
public class VectorSearchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider provider;
    private final EmbeddingProperties properties;

    public VectorSearchService(JdbcTemplate jdbc, EmbeddingProvider provider, EmbeddingProperties properties) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.properties = properties;
    }

    public record Outcome(List<Map<String, Object>> hits, int scanned, boolean truncated) {
    }

    /** 索引状态：provider / 是否可用 / 已向量化数量 / 与当前模型不匹配的历史向量。 */
    public Map<String, Object> status() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider.id());
        payload.put("available", provider.available());
        payload.put("dimension", provider.dimension());
        payload.put("embedded", jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_vector WHERE provider = ?", Integer.class, provider.id()));
        payload.put("indexedDocs", jdbc.queryForObject("SELECT COUNT(*) FROM search_doc", Integer.class));
        payload.put("staleVectors", jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_vector WHERE provider <> ? OR dimension <> ?",
                Integer.class, provider.id(), provider.dimension()));
        List<Map<String, Object>> byProvider = jdbc.query("""
                SELECT provider, dimension, COUNT(*) AS count FROM search_vector
                GROUP BY provider, dimension ORDER BY count DESC
                """, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", rs.getString("provider"));
            row.put("dimension", rs.getInt("dimension"));
            row.put("count", rs.getInt("count"));
            return row;
        });
        payload.put("byProvider", byProvider);
        payload.put("note", provider.available()
                ? "向量召回已启用；无 pgvector 时为有上限的全量余弦扫描（超出上限会显式回报 truncated）"
                : "向量召回**未启用**：未配置 embedding 服务，混合检索只有词法与术语扩展两路");
        return payload;
    }

    /**
     * 重建向量索引（增量）。
     *
     * <p>按内容哈希跳过未变化的文档：重建 5 千文档时，这能把"每天重建一次"的成本降到可接受；
     * 同时**模型变更必须全量重建** —— 不同 provider 的向量不可比较，旧向量会被 (provider, dimension)
     * 过滤掉，status 里也会显示 staleVectors。
     */
    public Map<String, Object> rebuild(int limit) {
        if (!provider.available()) {
            throw new EmbeddingProvider.EmbeddingUnavailable(
                    "向量索引未启用：未配置 embedding 服务（DG_AI_EMBEDDING_PROVIDER）");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 100_000));
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT urn, entity_type, display_name, namespace, description
                FROM search_doc ORDER BY urn LIMIT ?
                """, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("urn", rs.getString("urn"));
            row.put("entityType", rs.getString("entity_type"));
            row.put("displayName", rs.getString("display_name"));
            row.put("namespace", rs.getString("namespace"));
            row.put("description", rs.getString("description"));
            return row;
        }, effectiveLimit);

        int embedded = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String urn = String.valueOf(row.get("urn"));
            String text = embedText(row);
            String hash = sha256(text);
            String existing = jdbc.query("""
                    SELECT doc_hash FROM search_vector WHERE urn = ? AND provider = ? AND dimension = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, urn, provider.id(), provider.dimension());
            if (hash.equals(existing)) {
                skipped++;
                continue;
            }
            try {
                float[] vector = provider.embed(text);
                jdbc.update("""
                        INSERT INTO search_vector (urn, provider, dimension, vector, doc_hash, updated_at)
                        VALUES (?, ?, ?, cast(? AS jsonb), ?, now())
                        ON CONFLICT (urn) DO UPDATE
                        SET provider = EXCLUDED.provider, dimension = EXCLUDED.dimension,
                            vector = EXCLUDED.vector, doc_hash = EXCLUDED.doc_hash, updated_at = now()
                        """, urn, provider.id(), provider.dimension(), toJson(vector), hash);
                embedded++;
            } catch (RuntimeException e) {
                // 单条失败不中断整体：重建是批处理，失败项要能被看到而不是让整次重建回滚
                failures.add(urn + " -> " + e.getMessage());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider.id());
        payload.put("scanned", rows.size());
        payload.put("embedded", embedded);
        payload.put("skippedUnchanged", skipped);
        payload.put("failed", failures.size());
        if (!failures.isEmpty()) {
            payload.put("failures", failures.subList(0, Math.min(10, failures.size())));
        }
        return payload;
    }

    /**
     * 向量召回。
     *
     * <p><b>授权前置过滤</b>在这一步之前完成（SQL 里按 {@code classification} 过滤）：
     * 先取回再过滤会泄漏"存在但无权看"的资产数量与时延差异，这是 docs/09 §9.3 明确禁止的做法。
     */
    public Outcome search(String query, String entityType, List<String> visibleLevels, int topK) {
        if (!provider.available() || query == null || query.isBlank()) {
            return new Outcome(List.of(), 0, false);
        }
        float[] queryVector = provider.embed(query);
        int maxScan = Math.max(100, properties.getMaxScan());
        List<Object> args = new ArrayList<>();
        args.add(provider.id());
        args.add(provider.dimension());
        StringBuilder where = new StringBuilder("""
                WHERE v.provider = ? AND v.dimension = ?
                  AND COALESCE(d.classification, 'L2') IN (
                """);
        where.append(String.join(",", java.util.Collections.nCopies(visibleLevels.size(), "?")));
        where.append(")");
        args.addAll(visibleLevels);
        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND d.entity_type = ?");
            args.add(entityType.trim());
        }
        args.add(maxScan + 1);

        List<Map<String, Object>> candidates = jdbc.query("""
                SELECT v.urn, v.vector::text AS vector, d.entity_type, d.display_name,
                       d.namespace, d.platform, d.classification
                FROM search_vector v JOIN search_doc d ON d.urn = v.urn
                """ + where + " LIMIT ?", (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("urn", rs.getString("urn"));
            row.put("vector", rs.getString("vector"));
            row.put("entityType", rs.getString("entity_type"));
            row.put("displayName", rs.getString("display_name"));
            row.put("namespace", rs.getString("namespace"));
            row.put("platform", rs.getString("platform"));
            row.put("classification", rs.getString("classification"));
            return row;
        }, args.toArray());

        boolean truncated = candidates.size() > maxScan;
        List<Map<String, Object>> scored = new ArrayList<>();
        int scanned = 0;
        for (Map<String, Object> candidate : candidates) {
            if (scanned >= maxScan) {
                break;
            }
            scanned++;
            float[] vector = parseVector(String.valueOf(candidate.get("vector")));
            double score = cosine(queryVector, vector);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("urn", candidate.get("urn"));
            document.put("entityType", candidate.get("entityType"));
            document.put("displayName", candidate.get("displayName"));
            document.put("namespace", candidate.get("namespace"));
            document.put("platform", candidate.get("platform"));
            document.put("classification", candidate.get("classification"));
            document.put("vectorScore", Math.round(score * 10000) / 10000.0);
            document.put("retriever", "vector");
            scored.add(document);
        }
        scored.sort((a, b) -> Double.compare(
                ((Number) b.get("vectorScore")).doubleValue(), ((Number) a.get("vectorScore")).doubleValue()));
        return new Outcome(scored.subList(0, Math.min(Math.max(1, topK), scored.size())), scanned, truncated);
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int index = 0; index < a.length; index++) {
            dot += a[index] * b[index];
        }
        return dot;
    }

    private static String embedText(Map<String, Object> row) {
        StringBuilder text = new StringBuilder();
        text.append(row.getOrDefault("displayName", ""));
        if (row.get("namespace") != null) {
            text.append(' ').append(row.get("namespace"));
        }
        if (row.get("description") != null) {
            text.append(' ').append(row.get("description"));
        }
        return text.toString().trim();
    }

    private static String toJson(float[] vector) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(Float.toString(vector[index]));
        }
        return json.append(']').toString();
    }

    private static float[] parseVector(String json) {
        try {
            var node = MAPPER.readTree(json);
            float[] vector = new float[node.size()];
            for (int index = 0; index < node.size(); index++) {
                vector[index] = (float) node.get(index).asDouble();
            }
            return vector;
        } catch (Exception e) {
            throw new IllegalStateException("向量内容不可解析：" + e.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("JRE 缺少 SHA-256", e);
        }
    }
}
