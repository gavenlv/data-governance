package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 血缘子图查询（docs/09 §9.2「血缘图的查询能力」、docs/14 §3.3 血缘探索器）。
 *
 * <p>为什么不能让前端直接画全图：真实血缘图有几十万条边，把闭包原样丢给浏览器
 * 只会得到一张卡死的图。因此**裁剪在服务端做**，并且裁剪必须是**显式**的：
 * 响应里带 {@code truncated} / {@code boundaryNodes} / {@code nodeLimitReached}，
 * 界面据此提示"还有更多"，而不是让使用者以为"血缘就这么点"。
 *
 * <p>工程约束（来自 docs/09 §9.2 的实测要点）：
 * <ul>
 *   <li>递归用 {@code UNION}（去重）而非 {@code UNION ALL}：真实血缘有环，UNION ALL 会路径数指数膨胀；</li>
 *   <li>深度有界（交互式默认 3、上限 5）；</li>
 *   <li>边带来源/置信度/解析级别/时效，界面才能"线型 = 可信度"；</li>
 *   <li>默认排除 CONTROL 依赖（过滤/分区键产生的边会让图变得又大又难读）。</li>
 * </ul>
 */
@Service
public class LineageSubgraphService {

    /** 交互式探索的深度上限：再深就不该在一个界面里画了。 */
    private static final int MAX_INTERACTIVE_DEPTH = 5;
    private static final int DEFAULT_NODE_LIMIT = 600;

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;

    public LineageSubgraphService(JdbcTemplate jdbc, MetadataService metadata) {
        this.jdbc = jdbc;
        this.metadata = metadata;
    }

    /**
     * 取以 {@code urn} 为焦点的子图。
     *
     * @param includeColumns 是否包含列级节点（列级节点数量通常是表级的 10 倍以上）
     * @param includeControl 是否包含控制依赖（默认排除）
     */
    public Map<String, Object> subgraph(String urn, String direction, int depth, double minConfidence,
                                        boolean includeColumns, boolean includeControl, Integer nodeLimit) {
        String dir = "upstream".equals(direction) ? "upstream" : "downstream";
        int effectiveDepth = Math.max(1, Math.min(depth, MAX_INTERACTIVE_DEPTH));
        // 允许很小的上限：界面可能只想看"直接邻居"，测试也需要能触发截断分支
        int limit = nodeLimit == null ? DEFAULT_NODE_LIMIT : Math.max(1, Math.min(nodeLimit, 5000));

        String joinClause = "downstream".equals(dir) ? "e.from_urn = w.urn" : "e.to_urn = w.urn";
        String nextCol = "downstream".equals(dir) ? "e.to_urn" : "e.from_urn";
        String controlFilter = includeControl ? "" : " AND e.dependency_kind = 'VALUE'";

        List<Map<String, Object>> closure = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT %s, w.depth + 1
                      FROM edge e JOIN walk w ON %s
                     WHERE e.state = 'ACTIVE' AND e.edge_type = ANY(?)%s
                       AND e.confidence >= ? AND w.depth < ?
                )
                SELECT w.urn, MIN(w.depth) AS depth FROM walk w
                 WHERE w.urn <> ? GROUP BY w.urn ORDER BY depth, urn
                """.formatted(nextCol, joinClause, controlFilter),
                urn, metadata.lineageEdgeTypes(), minConfidence, effectiveDepth, urn);

        Map<String, Integer> depthOf = new LinkedHashMap<>();
        for (Map<String, Object> row : closure) {
            depthOf.put(String.valueOf(row.get("urn")), ((Number) row.get("depth")).intValue());
        }
        if (!includeColumns) {
            depthOf.keySet().removeIf(node -> node.startsWith("urn:dg:Column:"));
        }

        // 节点上限：**先按跳数排序**再截断，保证画出来的是"离焦点最近的部分"，
        // 而不是随机一半；被截断的节点数要显式回报
        // 注意：reachable 必须**含焦点自身**，否则 "可达 2 / 展示 3" 这种自相矛盾的计数
        // 会让人以为裁剪逻辑坏了（实测踩到）
        int totalReachable = depthOf.size() + 1;
        boolean nodeLimitReached = totalReachable > limit;
        List<String> selected = new ArrayList<>(depthOf.keySet());
        if (nodeLimitReached) {
            selected = selected.subList(0, Math.max(0, limit - 1));
        }
        Set<String> selectedSet = new LinkedHashSet<>(selected);
        selectedSet.add(urn);

        List<Map<String, Object>> edges = fetchEdges(urn, dir, effectiveDepth, minConfidence,
                includeControl, selectedSet);

        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Map<String, Object>> profiles = loadProfiles(selectedSet);
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (String nodeUrn : selectedSet) {
            Map<String, Object> profile = profiles.getOrDefault(nodeUrn, Map.of());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("urn", nodeUrn);
            node.put("entityType", profile.getOrDefault("entityType", UrnUtils.parse(nodeUrn).entityType()));
            node.put("displayName", profile.get("displayName"));
            node.put("namespace", profile.get("namespace"));
            node.put("platform", profile.get("platform"));
            node.put("container", profile.get("container"));
            node.put("lifecycle", profile.get("lifecycle"));
            node.put("classification", profile.get("classification"));
            node.put("owners", profile.get("owners"));
            node.put("depth", depthOf.getOrDefault(nodeUrn, 0));
            node.put("focus", nodeUrn.equals(urn));
            nodes.add(node);
            byType.merge(String.valueOf(node.get("entityType")), 1, Integer::sum);
        }
        nodes.sort(java.util.Comparator
                .comparingInt((Map<String, Object> node) -> (Integer) node.get("depth"))
                .thenComparing(node -> String.valueOf(node.get("urn"))));

        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            bySource.merge(String.valueOf(edge.get("source")), 1, Integer::sum);
        }

        // 深度边界上仍有下游的节点：说明"真实影响面更大"
        List<String> boundary = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : depthOf.entrySet()) {
            if (entry.getValue() == effectiveDepth && selectedSet.contains(entry.getKey())) {
                boundary.add(entry.getKey());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", urn);
        payload.put("direction", dir);
        payload.put("maxDepth", effectiveDepth);
        payload.put("minConfidence", minConfidence);
        payload.put("includeColumns", includeColumns);
        payload.put("includeControl", includeControl);
        payload.put("nodes", nodes);
        payload.put("edges", edges);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("nodes", nodes.size());
        counts.put("edges", edges.size());
        counts.put("nodesByType", byType);
        counts.put("edgesBySource", bySource);
        counts.put("reachableBeforeLimit", totalReachable);
        payload.put("counts", counts);
        payload.put("nodeLimit", limit);
        payload.put("nodeLimitReached", nodeLimitReached);
        payload.put("truncated", !boundary.isEmpty() || nodeLimitReached);
        payload.put("boundaryNodes", boundary.stream().limit(50).toList());
        payload.put("notes", buildNotes(dir, effectiveDepth, boundary, nodeLimitReached,
                totalReachable, limit, includeColumns, includeControl));
        return payload;
    }

    /** 闭包内的边（一次性取回，避免逐节点查询）。 */
    private List<Map<String, Object>> fetchEdges(String urn, String dir, int depth, double minConfidence,
                                                 boolean includeControl, Set<String> selected) {
        if (selected.size() <= 1) {
            return List.of();
        }
        String joinClause = "downstream".equals(dir) ? "e.from_urn = w.urn" : "e.to_urn = w.urn";
        String nextCol = "downstream".equals(dir) ? "e.to_urn" : "e.from_urn";
        String controlFilter = includeControl ? "" : " AND e.dependency_kind = 'VALUE'";

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT %s, w.depth + 1
                      FROM edge e JOIN walk w ON %s
                     WHERE e.state = 'ACTIVE' AND e.edge_type = ANY(?)%s
                       AND e.confidence >= ? AND w.depth < ?
                )
                SELECT e.id, e.from_urn, e.to_urn, e.edge_type, e.source, e.confidence, e.transform,
                       e.transform_expression, e.cardinality, e.dependency_kind, e.parse_level,
                       e.state, e.observed_count, e.properties,
                       to_char(e.last_seen, 'YYYY-MM-DD"T"HH24:MI:SSOF') AS last_seen
                  FROM edge e
                 WHERE e.state IN ('ACTIVE', 'STALE')
                   AND (e.from_urn IN (SELECT urn FROM walk) OR e.to_urn IN (SELECT urn FROM walk))
                 ORDER BY e.source, e.from_urn, e.to_urn
                """.formatted(nextCol, joinClause, controlFilter),
                urn, metadata.lineageEdgeTypes(), minConfidence, depth);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String from = String.valueOf(row.get("from_urn"));
            String to = String.valueOf(row.get("to_urn"));
            // 只保留两端都在可见集合里的边：否则前端会画出"悬空"的边
            if (!selected.contains(from) || !selected.contains(to)) {
                continue;
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("edgeId", row.get("id"));
            edge.put("id", from + "->" + to + "#" + row.get("source") + "#" + row.get("dependency_kind"));
            edge.put("from", from);
            edge.put("to", to);
            edge.put("edgeType", row.get("edge_type"));
            edge.put("source", row.get("source"));
            edge.put("confidence", row.get("confidence"));
            edge.put("transform", row.get("transform"));
            edge.put("transformExpression", row.get("transform_expression"));
            edge.put("cardinality", row.get("cardinality"));
            edge.put("dependencyKind", row.get("dependency_kind"));
            edge.put("parseLevel", row.get("parse_level"));
            edge.put("state", row.get("state"));
            edge.put("observedCount", row.get("observed_count"));
            edge.put("lastSeen", row.get("last_seen"));
            Map<String, Object> properties = parseProperties(str(row.get("properties")));
            if (properties.containsKey("confirmedBy")) {
                edge.put("confirmedBy", properties.get("confirmedBy"));
                edge.put("confirmedAt", properties.get("confirmedAt"));
            }
            edges.add(edge);
        }
        return edges;
    }

    /** 节点画像：类型、展示名、分级、Owner、平台/容器（用于着色与详情面板）。 */
    private Map<String, Map<String, Object>> loadProfiles(Set<String> urns) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (urns.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(urns);
        String placeholders = String.join(", ", java.util.Collections.nCopies(list.size(), "?"));

        Map<String, Map<String, Object>> basics = new LinkedHashMap<>();
        jdbc.query("""
                SELECT urn, entity_type, display_name, namespace, lifecycle
                  FROM entity WHERE deleted_at IS NULL AND urn IN (""" + placeholders + ")",
                rs -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("entityType", rs.getString("entity_type"));
                    item.put("displayName", rs.getString("display_name"));
                    item.put("namespace", rs.getString("namespace"));
                    item.put("lifecycle", rs.getString("lifecycle"));
                    basics.put(rs.getString("urn"), item);
                }, list.toArray());

        Map<String, String> classification = new LinkedHashMap<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        jdbc.query("""
                SELECT urn, aspect_type, data FROM aspect
                 WHERE aspect_type IN ('classification', 'ownership')
                   AND urn IN (""" + placeholders + ")", rs -> {
                    String urn = rs.getString("urn");
                    String type = rs.getString("aspect_type");
                    String raw = rs.getString("data");
                    if ("classification".equals(type)) {
                        classification.put(urn, extractJsonString(raw, "level"));
                    } else {
                        owners.put(urn, extractOwners(raw));
                    }
                }, list.toArray());

        for (String urn : list) {
            Map<String, Object> profile = new LinkedHashMap<>(basics.getOrDefault(urn, Map.of()));
            // 平台/容器从 URN 段推断：Dataset/Column 的 URN 形状是 ns.platform.db.schema.table[.column]
            try {
                List<String> parts = UrnUtils.parse(urn).parts();
                if (parts.size() > 2) {
                    profile.put("platform", parts.get(1));
                    profile.put("container", String.join(".", parts.subList(1, Math.max(2, parts.size() - 1))));
                }
            } catch (RuntimeException ignored) {
                // URN 形状异常不影响出图
            }
            profile.put("classification", classification.get(urn));
            profile.put("owners", owners.getOrDefault(urn, List.of()));
            out.put(urn, profile);
        }
        return out;
    }

    private List<String> buildNotes(String dir, int depth, List<String> boundary, boolean limitReached,
                                    int reachable, int limit, boolean includeColumns, boolean includeControl) {
        List<String> notes = new ArrayList<>();
        notes.add("方向：" + ("downstream".equals(dir) ? "下游（这张表影响了谁）" : "上游（这张表从哪来）")
                + "，深度 " + depth + "；线型 = 可信度（实线=运行时/人工，虚线=静态解析，点线=推断，灰线=过期）");
        if (!includeColumns) {
            notes.add("当前为表级视图：列级节点数量通常是表级的 10 倍以上，勾选「含列级」再展开");
        }
        if (!includeControl) {
            notes.add("已排除控制依赖（过滤/分区键产生的边会让图又大又难读），可在过滤器中开启");
        }
        if (limitReached) {
            notes.add("节点数达到上限（" + limit + "，可达 " + reachable + " 个）："
                    + "**图被裁剪过**，请降低深度、提高置信度阈值，或取消勾选列级");
        }
        if (!boundary.isEmpty()) {
            notes.add("有 " + boundary.size() + " 个节点在深度上限处仍有" + ("downstream".equals(dir) ? "下游" : "上游")
                    + "：真实链路更长，点节点可继续展开");
        }
        if (reachable == 0) {
            notes.add("闭包为空：可能确实没有血缘，也可能是尚未采集/解析（见血缘质量报告）");
        }
        return notes;
    }

    // --------------------------------------------------------------- 小工具

    private static Map<String, Object> parseProperties(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object value = data.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> extractOwners(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object raw = data.get("owners");
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object value = map.get("name") != null ? map.get("name") : map.get("urn");
                    if (value != null) {
                        out.add(String.valueOf(value));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
