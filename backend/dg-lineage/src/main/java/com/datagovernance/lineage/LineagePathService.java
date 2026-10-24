package com.datagovernance.lineage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 列级端到端路径追踪（docs/14 §3.3 的「A.col1 → B.col2 → C.metric」）。
 *
 * <p>为什么这是血缘里最有用的一个视图：影响分析回答"改这张表会坏什么"，
 * 而使用者真正问的是**"这个指标是从哪个字段算出来的"** —— 后者必须能给出**一条连续链路**，
 * 而不是一张图让人自己找。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>只走 ACTIVE 边</b>：REJECTED 是被人工判定错误的边，拿它做路径等于把人已经否掉的推断
 *       又用了一次；STALE 的边只提示、不参与最短路径。</li>
 *   <li><b>深度硬上限</b>：路径搜索是指数风险，默认 6、上限 12；超限显式返回"没找到（可能更深）"，
 *       绝不静默返回空 —— "没路径"与"没找够"必须可区分。</li>
 *   <li><b>方向固定为上游 → 下游</b>：与全局约定一致；反向追踪用 from/to 互换。</li>
 * </ol>
 */
@Service
public class LineagePathService {

    private static final int MAX_DEPTH = 12;

    private final JdbcTemplate jdbc;

    public LineagePathService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条路径边（同时也是 BFS 的邻接元素）。 */
    public record Hop(String fromUrn, String toUrn, long edgeId, String source,
                      double confidence, String parseLevel, String transform) {
    }

    public Map<String, Object> path(String fromUrn, String toUrn, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth, MAX_DEPTH));
        List<Hop> edges = loadEdges(fromUrn, depth);
        List<Hop> found = findPath(edges, fromUrn, toUrn);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", fromUrn);
        payload.put("to", toUrn);
        payload.put("maxDepth", depth);
        payload.put("searchedEdges", edges.size());
        if (found == null) {
            payload.put("found", false);
            payload.put("path", List.of());
            payload.put("note", edges.isEmpty()
                    ? "该起点在深度 " + depth + " 内没有任何 ACTIVE 下游边：可能确实没有血缘，或尚未采集/解析"
                    : "深度 " + depth + " 内未找到通向目标的路径 —— 可能是链路更深，也可能中间表未被采集。"
                            + "提高 depth 再试，或先看血缘质量报告");
            return payload;
        }

        List<Map<String, Object>> hops = new ArrayList<>();
        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(fromUrn);
        double minConfidence = 1.0;
        for (Hop hop : found) {
            nodes.add(hop.toUrn());
            minConfidence = Math.min(minConfidence, hop.confidence());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("edgeId", hop.edgeId());
            item.put("from", hop.fromUrn());
            item.put("to", hop.toUrn());
            item.put("source", hop.source());
            item.put("confidence", hop.confidence());
            item.put("parseLevel", hop.parseLevel());
            item.put("transform", hop.transform());
            hops.add(item);
        }
        payload.put("found", true);
        payload.put("hops", found.size());
        // 整条链路的可信度取**最弱的一环**：一条链不会比它最不确定的那段更可信
        payload.put("minConfidence", minConfidence);
        payload.put("nodes", List.copyOf(nodes));
        payload.put("path", hops);
        return payload;
    }

    /**
     * 广度优先最短路径（纯函数，可单测）。
     *
     * @return 从 from 到 to 的边序列；不可达返回 {@code null}（与"空列表"区分：后者没有意义）
     */
    public static List<Hop> findPath(List<Hop> edges, String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        Map<String, List<Hop>> adjacency = new HashMap<>();
        for (Hop hop : edges) {
            adjacency.computeIfAbsent(hop.fromUrn(), key -> new ArrayList<>()).add(hop);
        }
        Map<String, Hop> parent = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(to)) {
                break;
            }
            for (Hop hop : adjacency.getOrDefault(current, List.of())) {
                if (seen.add(hop.toUrn())) {
                    parent.put(hop.toUrn(), hop);
                    queue.add(hop.toUrn());
                }
            }
        }
        if (!seen.contains(to)) {
            return null;
        }
        List<Hop> reversed = new ArrayList<>();
        String cursor = to;
        while (!cursor.equals(from)) {
            Hop hop = parent.get(cursor);
            if (hop == null) {
                return null;
            }
            reversed.add(hop);
            cursor = hop.fromUrn();
        }
        List<Hop> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    /** 只取起点深度内的 ACTIVE 边：先把闭包收窄，再做 BFS，避免全表扫描。 */
    private List<Hop> loadEdges(String fromUrn, int depth) {
        return jdbc.query("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT ?, 0
                    UNION
                    SELECT e.to_urn, w.depth + 1
                    FROM edge e JOIN walk w ON e.from_urn = w.urn
                    WHERE e.state = 'ACTIVE' AND w.depth < ?
                )
                SELECT DISTINCT e.id, e.from_urn, e.to_urn, e.source, e.confidence,
                       e.parse_level, e.transform
                FROM edge e JOIN walk w ON e.from_urn = w.urn
                WHERE e.state = 'ACTIVE' AND w.depth < ?
                """, (rs, index) -> new Hop(
                rs.getString("from_urn"), rs.getString("to_urn"), rs.getLong("id"),
                rs.getString("source"), rs.getDouble("confidence"),
                rs.getString("parse_level"), rs.getString("transform")),
                fromUrn, depth, depth);
    }
}
