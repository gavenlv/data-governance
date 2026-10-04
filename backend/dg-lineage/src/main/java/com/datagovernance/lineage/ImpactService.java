package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 影响分析 / 爆炸半径评分（docs/09 §9.2）。
 *
 * <p>要解决的问题：改动一个列或一张表之前，"会影响到谁"必须能一眼看清，
 * 而不是靠人肉翻血缘图。因此输出不是一串 URN，而是<b>按关键性排序</b>的受影响清单。
 *
 * <p>评分公式（与文档一致）：{@code score(v) = w(v) · α^depth(v)}，α = 0.7。
 * {@code w(v)} 由四项合成：分级（越高越关键）、关键性标签、是否有 Owner、下游资产数。
 *
 * <p><b>诚实边界</b>：文档里的 {@code w(v)} 还包含"使用热度"，而热度需要接入
 * 查询日志 / BI 审计日志（docs/11 §1.1 的路径 (a)）。当前没有查询日志接入，
 * 因此热度项<b>不计入</b>，并在响应里显式说明 —— 而不是悄悄用 0 顶替。
 */
@Service
public class ImpactService {

    private static final double DECAY = 0.7;
    private static final Set<String> CRITICAL_TAGS = Set.of(
            "critical", "tier1", "tier-1", "p0", "core", "关键", "核心");

    private final JdbcTemplate jdbc;
    private final com.datagovernance.model.ModelRegistry registry;

    public ImpactService(JdbcTemplate jdbc, com.datagovernance.model.ModelRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /**
     * 血缘关系类型（模型里 {@code lineage: true} 的关系）。
     *
     * <p>只沿血缘类型遍历：结构包含边（{@code contains}）表达的是"表属于这个库"，
     * 把它算进血缘会让每张表都冒出一堆假上游（实测：不过滤时某表"上游"多达 41 个）。
     * "哪些边算血缘"必须有唯一判定，因此统一取自模型。
     */
    private java.sql.Array lineageEdgeTypes() {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<java.sql.Array>) connection ->
                connection.createArrayOf("text", registry.lineageRelationshipNames().toArray()));
    }

    public Map<String, Object> analyze(String urn, String direction, int maxDepth,
                                       double minConfidence, boolean includeColumns) {
        String dir = "upstream".equals(direction) ? "upstream" : "downstream";
        int depth = Math.max(1, Math.min(maxDepth, 10));

        // 1) 可达闭包（带深度）。用 UNION（去重）而非 UNION ALL：真实血缘有环，
        //    UNION ALL 在 DAG 多路径下会路径数指数膨胀（docs/09 §9.2）
        String joinClause = "downstream".equals(dir) ? "e.from_urn = w.urn" : "e.to_urn = w.urn";
        String nextCol = "downstream".equals(dir) ? "e.to_urn" : "e.from_urn";

        List<Map<String, Object>> closure = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT %s, w.depth + 1
                      FROM edge e JOIN walk w ON %s
                     WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE' AND e.edge_type = ANY(?)
                       AND e.confidence >= ? AND w.depth < ?
                )
                SELECT w.urn, MIN(w.depth) AS depth FROM walk w
                 WHERE w.urn <> ? GROUP BY w.urn
                """.formatted(nextCol, joinClause), urn, lineageEdgeTypes(), minConfidence, depth, urn);

        Map<String, Integer> depthOf = new LinkedHashMap<>();
        for (Map<String, Object> row : closure) {
            depthOf.put(String.valueOf(row.get("urn")), ((Number) row.get("depth")).intValue());
        }

        if (!includeColumns) {
            depthOf.keySet().removeIf(u -> u.startsWith("urn:dg:Column:"));
        }

        if (depthOf.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("urn", urn);
            empty.put("direction", dir);
            empty.put("affectedCount", 0);
            empty.put("nodes", List.of());
            empty.put("caveat", "闭包为空：可能确实没有下游，也可能是血缘尚未被采集/解析。"
                    + "用 GET /api/v1/lineage/quality 看解析覆盖率，用 GET /api/v1/lineage/graph 确认边是否存在 —— "
                    + "空结果不等于没有影响。");
            return empty;
        }

        // 2) 闭包内的边（一次性取回，避免逐节点递归造成 N+1 查询）
        List<Map<String, Object>> edges = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT %s, w.depth + 1
                      FROM edge e JOIN walk w ON %s
                     WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE' AND e.edge_type = ANY(?)
                       AND e.confidence >= ? AND w.depth < ?
                )
                SELECT e.from_urn, e.to_urn, e.source, e.confidence, e.transform, e.parse_level
                  FROM edge e
                 WHERE e.state = 'ACTIVE'
                   AND (e.from_urn IN (SELECT urn FROM walk) OR e.to_urn IN (SELECT urn FROM walk))
                """.formatted(nextCol, joinClause), urn, lineageEdgeTypes(), minConfidence, depth);

        // 3) 闭包内每个节点的下游数（用于 w(v) 的关键性权重）
        Map<String, Integer> outDegree = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            outDegree.merge(String.valueOf(edge.get("from_urn")), 1, Integer::sum);
        }

        // 4) 逐节点的画像（分级 / 标签 / Owner / 展示名 / 类型）
        Map<String, NodeProfile> profiles = loadProfiles(depthOf.keySet());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : depthOf.entrySet()) {
            String nodeUrn = entry.getKey();
            int nodeDepth = entry.getValue();
            NodeProfile profile = profiles.getOrDefault(nodeUrn, NodeProfile.empty(nodeUrn));
            int downstreamCount = outDegree.getOrDefault(nodeUrn, 0);

            double weight = weight(profile, downstreamCount);
            double score = weight * Math.pow(DECAY, nodeDepth);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("urn", nodeUrn);
            node.put("displayName", profile.displayName());
            node.put("entityType", profile.entityType());
            node.put("depth", nodeDepth);
            node.put("score", Math.round(score * 1000.0) / 1000.0);
            node.put("weight", Math.round(weight * 1000.0) / 1000.0);
            node.put("classification", profile.classification());
            node.put("owners", profile.owners());
            node.put("tags", profile.tags());
            node.put("downstreamCount", downstreamCount);
            node.put("reasons", reasons(profile, downstreamCount));
            nodes.add(node);
        }
        nodes.sort(Comparator.comparingDouble((Map<String, Object> n) -> -((Number) n.get("score")).doubleValue())
                .thenComparing(n -> (String) n.get("urn")));

        // 5) 截断诚实性：达到深度上限但仍有下游的节点必须显式标注，
        //    否则"3 跳内就这些"会被误读成"总共就这些"
        List<String> boundary = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : depthOf.entrySet()) {
            if (entry.getValue() == depth) {
                Integer out = outDegree.get(entry.getKey());
                if (out != null && out > 0) {
                    boundary.add(entry.getKey());
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", urn);
        payload.put("direction", dir);
        payload.put("maxDepth", depth);
        payload.put("minConfidence", minConfidence);
        payload.put("includeColumns", includeColumns);
        payload.put("affectedCount", nodes.size());
        payload.put("criticalCount", nodes.stream().filter(ImpactService::isCritical).count());
        payload.put("byEntityType", countBy(nodes, "entityType"));
        payload.put("reachedMaxDepth", boundary.size() > 0);
        payload.put("boundaryNodes", boundary);
        payload.put("truncationNote", boundary.isEmpty() ? null
                : "有 " + boundary.size() + " 个节点在深度上限处仍有下游，真实影响面更大；"
                        + "提高 depth 或按前缀继续展开");
        payload.put("edgesInClosure", edges.size());
        payload.put("nodes", nodes);
        payload.put("scoring", Map.of(
                "formula", "score(v) = w(v) · " + DECAY + "^depth(v)",
                "wComponents", List.of(
                        "分级 L1–L4（越高越关键）",
                        "关键性标签（critical / tier1 / p0 / core / 核心）",
                        "是否有 Owner（无主资产加权，因为没人接的改动最容易出事）",
                        "下游资产数（对数缩放）"),
                "excluded", "使用热度 —— 需要接入查询日志 / BI 审计日志（docs/11 §1.1 路径 a），"
                        + "当前无该数据源，因此**未计入**评分（不静默按 0 处理）"));
        return payload;
    }

    /** 一个受影响节点是否"关键"：分级 L4 或带关键性标签。 */
    private static boolean isCritical(Map<String, Object> node) {
        Object classification = node.get("classification");
        if (classification != null
                && "L4".equalsIgnoreCase(String.valueOf(classification))) {
            return true;
        }
        Object tags = node.get("tags");
        if (tags instanceof List<?> list) {
            return list.stream().anyMatch(tag -> CRITICAL_TAGS.contains(
                    String.valueOf(tag).toLowerCase(java.util.Locale.ROOT)));
        }
        return false;
    }

    /** 单个节点的画像。 */
    private record NodeProfile(String urn, String entityType, String displayName,
                               String classification, List<String> owners, List<String> tags) {

        static NodeProfile empty(String urn) {
            return new NodeProfile(urn, "Unknown", null, null, List.of(), List.of());
        }
    }

    private Map<String, NodeProfile> loadProfiles(Set<String> urns) {
        Map<String, NodeProfile> profiles = new LinkedHashMap<>();
        if (urns.isEmpty()) {
            return profiles;
        }
        List<String> list = new ArrayList<>(urns);
        String placeholders = String.join(", ", java.util.Collections.nCopies(list.size(), "?"));

        Map<String, Map<String, Object>> basics = new LinkedHashMap<>();
        jdbc.query("""
                SELECT urn, entity_type, display_name FROM entity
                 WHERE deleted_at IS NULL AND urn IN (""" + placeholders + ")",
                rs -> {
                    basics.put(rs.getString("urn"), Map.of(
                            "entityType", rs.getString("entity_type"),
                            "displayName", rs.getString("display_name") == null
                                    ? "" : rs.getString("display_name")));
                }, list.toArray());

        Map<String, String> classification = new LinkedHashMap<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        Map<String, List<String>> tags = new LinkedHashMap<>();

        jdbc.query("""
                SELECT urn, aspect_type, data FROM aspect
                 WHERE aspect_type IN ('classification', 'ownership', 'tags')
                   AND urn IN (""" + placeholders + ")", rs -> {
                    String nodeUrn = rs.getString("urn");
                    String aspectType = rs.getString("aspect_type");
                    Map<String, Object> data = parseJson(rs.getString("data"));
                    switch (aspectType) {
                        case "classification" -> classification.put(nodeUrn, str(data.get("level")));
                        case "ownership" -> owners.put(nodeUrn, ownerList(data));
                        case "tags" -> tags.put(nodeUrn, tagList(data));
                        default -> { }
                    }
                }, list.toArray());

        for (String nodeUrn : list) {
            Map<String, Object> basic = basics.getOrDefault(nodeUrn, Map.of());
            profiles.put(nodeUrn, new NodeProfile(
                    nodeUrn,
                    String.valueOf(basic.getOrDefault("entityType", "Unknown")),
                    str(basic.get("displayName")),
                    classification.get(nodeUrn),
                    owners.getOrDefault(nodeUrn, List.of()),
                    tags.getOrDefault(nodeUrn, List.of())));
        }
        return profiles;
    }

    private static double weight(NodeProfile profile, int downstreamCount) {
        double classificationWeight = switch (profile.classification() == null
                ? "L2" : profile.classification().toUpperCase(java.util.Locale.ROOT)) {
            case "L4" -> 1.0;
            case "L3" -> 0.8;
            case "L1" -> 0.3;
            default -> 0.5;
        };
        boolean critical = profile.tags().stream()
                .anyMatch(tag -> CRITICAL_TAGS.contains(tag.toLowerCase(java.util.Locale.ROOT)));
        double criticalWeight = critical ? 1.0 : 0.4;
        double ownershipWeight = profile.owners().isEmpty() ? 0.9 : 1.0;
        double downstreamWeight = Math.min(1.0,
                Math.log10(1 + downstreamCount) / 2.0);

        return 0.40 * classificationWeight + 0.25 * criticalWeight
                + 0.15 * ownershipWeight + 0.20 * downstreamWeight;
    }

    private static List<String> reasons(NodeProfile profile, int downstreamCount) {
        List<String> reasons = new ArrayList<>();
        if (profile.classification() != null) {
            reasons.add("分级 " + profile.classification());
        }
        boolean critical = profile.tags().stream()
                .anyMatch(tag -> CRITICAL_TAGS.contains(tag.toLowerCase(java.util.Locale.ROOT)));
        if (critical) {
            reasons.add("带关键性标签");
        }
        if (profile.owners().isEmpty()) {
            reasons.add("无 Owner（无主资产）");
        }
        if (downstreamCount > 0) {
            reasons.add("闭包内下游 " + downstreamCount + " 条");
        }
        return reasons;
    }

    private static Map<String, Integer> countBy(List<Map<String, Object>> nodes, String key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            counts.merge(String.valueOf(node.get(key)), 1, Integer::sum);
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    private static List<String> ownerList(Map<String, Object> data) {
        Object raw = data.get("owners");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object value = map.get("urn") != null ? map.get("urn") : map.get("name");
                if (value != null) {
                    out.add(String.valueOf(value));
                }
            } else if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> tagList(Map<String, Object> data) {
        Object raw = data.get("tags");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
