package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 血缘的**健康度**与**未确认队列**（docs/14 §2 的「血缘健康 / 未确认血缘队列」）。
 *
 * <p>解决的问题很具体：平台能画出图、能算影响，但没有任何地方回答
 * **"这张图里有多少条边是没人确认过的推断"**。没有这个回答，
 * 血缘就只是"看起来有"，而且越用越像真的 —— 一条错误血缘会让人做出错误的变更决策。
 *
 * <p>三个口径（刻意分开，不合成一个"健康分"）：
 * <ol>
 *   <li><b>结构性</b>：ACTIVE / STALE / PENDING_REVIEW / REJECTED 各多少 —— 是否存在"过期了还在用"；</li>
 *   <li><b>可信度</b>：按来源与解析级别分布 —— `derived`（拿平台 schema 推出来的）与 `exact` 不是一回事；</li>
 *   <li><b>未确认</b>：需要人判断的边，以及最老的一条等了多久 —— 队列没人看就等于没有队列。</li>
 * </ol>
 *
 * <p>为什么不做"健康分"：分数会把三个不同性质的信号揉成一个数字，
 * 使用者无法据此决定"该采集、该解析、还是该找人确认"。分开给，比合成一个更好用。
 */
@Service
public class LineageReviewService {

    /** 低置信度阈值：低于它的非人工边值得人看一眼（与 subgraph 的默认过滤阈值一致）。 */
    private static final double LOW_CONFIDENCE = 0.7;

    private final JdbcTemplate jdbc;

    public LineageReviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 未确认队列。
     *
     * <p>两类边进入队列，且**原因必须可区分**：
     * <ul>
     *   <li>{@code pending_review}：已显式标记为待复核（例如边被驳回后又重新出现）；</li>
     *   <li>{@code low_confidence_unconfirmed}：推断/解析得到、置信度低、且从未有人确认过。</li>
     * </ul>
     * 把两者混在一起会让"需要人工判断的量"失去意义 —— 前者是明确的待办，后者是筛查。
     */
    public Map<String, Object> queue(String entityType, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE (e.state = 'PENDING_REVIEW'
                       OR (e.state = 'ACTIVE'
                           AND e.confidence < ?
                           AND e.source <> 'manual'
                           AND COALESCE(e.properties->>'confirmedBy', '') = ''))
                """);
        args.add(LOW_CONFIDENCE);
        if (entityType != null && !entityType.isBlank()) {
            // 实体类型由 URN 第二段体现（urn:dg:<Type>:...）；用拆分避免为类型建索引列
            where.append(" AND split_part(e.from_urn, ':', 3) = ? ");
            args.add(entityType.trim());
        }
        args.add(effectiveLimit);

        List<Map<String, Object>> rows = jdbc.query("""
                SELECT e.id, e.from_urn, e.to_urn, e.edge_type, e.source, e.confidence,
                       e.parse_level, e.state, e.transform, e.first_seen, e.last_seen,
                       e.observed_count
                FROM edge e
                """ + where + """
                ORDER BY CASE WHEN e.state = 'PENDING_REVIEW' THEN 0 ELSE 1 END,
                         e.confidence ASC, e.first_seen ASC
                LIMIT ?
                """, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("edgeId", rs.getLong("id"));
            row.put("fromUrn", rs.getString("from_urn"));
            row.put("toUrn", rs.getString("to_urn"));
            row.put("edgeType", rs.getString("edge_type"));
            row.put("source", rs.getString("source"));
            row.put("confidence", rs.getDouble("confidence"));
            row.put("parseLevel", rs.getString("parse_level"));
            row.put("state", rs.getString("state"));
            row.put("transform", rs.getString("transform"));
            row.put("firstSeen", rs.getTimestamp("first_seen") == null ? null
                    : rs.getTimestamp("first_seen").toInstant().toString());
            row.put("lastSeen", rs.getTimestamp("last_seen") == null ? null
                    : rs.getTimestamp("last_seen").toInstant().toString());
            row.put("observedCount", rs.getInt("observed_count"));
            boolean pending = "PENDING_REVIEW".equals(row.get("state"));
            row.put("reason", pending ? "pending_review" : "low_confidence_unconfirmed");
            row.put("reasonText", pending
                    ? "已标记为待复核：请确认（置信度升到人工级）或驳回（保留记录但不参与遍历）"
                    : "低置信度且从未被人工确认：推断 / 静态解析得到的边，用之前值得先看一眼");
            return row;
        }, args.toArray());

        long pending = rows.stream().filter(row -> "pending_review".equals(row.get("reason"))).count();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("pendingReview", pending);
        payload.put("lowConfidenceUnconfirmed", rows.size() - pending);
        payload.put("lowConfidenceThreshold", LOW_CONFIDENCE);
        payload.put("edges", rows);
        payload.put("note", "确认 / 驳回走 POST /api/v1/lineage/edges/{id}/confirm 与 /reject；"
                + "驳回是标记而不是删除 —— 否则同一条错误边会被反复采集回来。");
        return payload;
    }

    /** 血缘健康度（三个口径分开给，不合成"健康分"）。 */
    public Map<String, Object> health(int days) {
        int window = Math.max(1, Math.min(days, 3650));
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("total", scalar("SELECT count(*) FROM edge"));
        payload.put("byState", grouped("SELECT state, count(*) AS c FROM edge GROUP BY state ORDER BY c DESC"));
        payload.put("bySource", grouped(
                "SELECT source, count(*) AS c FROM edge GROUP BY source ORDER BY c DESC"));
        payload.put("byParseLevel", grouped("""
                SELECT COALESCE(parse_level, '(unknown)') AS level, count(*) AS c
                FROM edge GROUP BY parse_level ORDER BY c DESC
                """));
        payload.put("byEntityPair", grouped("""
                SELECT split_part(from_urn, ':', 3) || ' → ' || split_part(to_urn, ':', 3) AS pair,
                       count(*) AS c
                FROM edge GROUP BY pair ORDER BY c DESC LIMIT 10
                """));

        Map<String, Object> unconfirmed = new LinkedHashMap<>();
        unconfirmed.put("pendingReview", scalar("SELECT count(*) FROM edge WHERE state = 'PENDING_REVIEW'"));
        unconfirmed.put("lowConfidenceUnconfirmed", scalar("""
                SELECT count(*) FROM edge
                WHERE state = 'ACTIVE' AND confidence < 0.7 AND source <> 'manual'
                  AND COALESCE(properties->>'confirmedBy', '') = ''
                """));
        // 队列里最老的一条等了多久：队列没人看，等于没有队列
        Double oldestDays = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (now() - min(first_seen))) / 86400.0
                FROM edge
                WHERE state = 'PENDING_REVIEW'
                   OR (state = 'ACTIVE' AND confidence < 0.7 AND source <> 'manual'
                       AND COALESCE(properties->>'confirmedBy', '') = '')
                """, Double.class);
        unconfirmed.put("oldestWaitingDays", oldestDays == null ? null : Math.round(oldestDays * 10) / 10.0);
        payload.put("unconfirmed", unconfirmed);

        payload.put("checksOpen", scalar(
                // 用 make_interval(days => ?) 而不是 (? || ' days')::interval：
                // 后者在 PostgreSQL 里报"无法确定参数类型"（JDBC 参数没有类型上下文）
                "SELECT count(*) FROM lineage_check_finding WHERE occurred_at >= now() - make_interval(days => ?)",
                window));
        payload.put("windowDays", window);

        List<String> notes = new ArrayList<>();
        notes.add("ACTIVE 之外的状态都有含义：STALE=长时间未再观测到、PENDING_REVIEW=待复核、REJECTED=已驳回（不参与遍历）");
        notes.add("derived 边是平台用 schema 推出来的（SELECT * 展开等），与 exact 边的可信度不同");
        if (unconfirmed.get("oldestWaitingDays") instanceof Number number && number.doubleValue() > 14) {
            notes.add("未确认队列里最老的一条已等待 " + number + " 天：队列长期无人处理时，"
                    + "上游变更的影响分析会建立在这些未核实的边上");
        }
        payload.put("notes", notes);
        return payload;
    }

    private Integer scalar(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> grouped(String sql) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", rs.getString(1));
            row.put("count", rs.getLong(2));
            return row;
        });
    }
}
