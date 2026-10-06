package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 血缘 L2 检查发现的查询与统计（{@code lineage_check_finding}）。
 *
 * <p>存在的意义是把"血缘覆盖率为什么低"从一句感慨变成一个**可分的答案**：
 * <ul>
 *   <li>{@code select_star_unresolved} / {@code missing_schema}：**输入不足** ——
 *       采集那几张表就能自动补上；</li>
 *   <li>{@code ambiguous_column_unresolved}：SQL 本身有歧义 ——
 *       需要改 SQL 或补列限定；</li>
 *   <li>{@code parse_failed}：解析器能力边界 —— 只能靠换解析器或人工登记；</li>
 *   <li>{@code select_star_expanded} / {@code ambiguous_column_resolved}：**已经补上了**
 *       （但边的置信度低于 exact，因此单独可见）。</li>
 * </ul>
 */
@Service
public class LineageCheckService {

    private final JdbcTemplate jdbc;

    public LineageCheckService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 最近的血缘检查发现。 */
    public List<Map<String, Object>> findings(String checkType, String targetUrn, Integer days, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, check_type, severity, statement_hash, target_urn, resource, message,
                       evidence, source, actor, occurred_at
                  FROM lineage_check_finding WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (checkType != null && !checkType.isBlank()) {
            sql.append(" AND check_type = ?");
            params.add(checkType);
        }
        if (targetUrn != null && !targetUrn.isBlank()) {
            sql.append(" AND target_urn = ?");
            params.add(targetUrn);
        }
        if (days != null && days > 0) {
            sql.append(" AND occurred_at > now() - (? || ' days')::interval");
            params.add(String.valueOf(days));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 1000));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 按类型统计 + 结论说明。
     *
     * <p>刻意把"能补的"和"补不了的"分开列：只有前者值得排期做采集，
     * 后者要么改 SQL、要么接受缺口。混在一起就只是一堆数字。
     */
    public Map<String, Object> summary(Integer days) {
        int window = days == null || days <= 0 ? 30 : days;
        List<Map<String, Object>> byType = jdbc.queryForList("""
                SELECT check_type, severity, COUNT(*) AS count,
                       COUNT(DISTINCT target_urn) AS affected_assets,
                       MAX(occurred_at) AS last_seen
                  FROM lineage_check_finding
                 WHERE occurred_at > now() - (? || ' days')::interval
                 GROUP BY 1, 2 ORDER BY count DESC
                """, String.valueOf(window));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowDays", window);
        payload.put("byType", byType);
        payload.put("legend", Map.of(
                "select_star_expanded", "SELECT * 已用平台 schema 展开（已补上，置信度低于 exact）",
                "ambiguous_column_resolved", "无表限定列已按平台 schema 消歧（已补上）",
                "select_star_unresolved", "SELECT * 无法展开：**上游 schema 未采集** → 采集即可自动补上",
                "ambiguous_column_unresolved", "列在多个上游表中都存在：SQL 有歧义 → 需补列限定或改 SQL",
                "missing_schema", "上游 schema 缺失",
                "parse_failed", "SQL 解析失败：解析器能力边界（与 schema 缺失不同）",
                "target_unresolved", "目标表未采集：先采集目标表"));
        payload.put("actionable", jdbc.queryForList("""
                SELECT target_urn, resource, COUNT(*) AS count
                  FROM lineage_check_finding
                 WHERE occurred_at > now() - (? || ' days')::interval
                   AND check_type IN ('select_star_unresolved', 'missing_schema', 'target_unresolved')
                 GROUP BY 1, 2 ORDER BY count DESC LIMIT 20
                """, String.valueOf(window)));
        payload.put("needsSqlFix", jdbc.queryForList("""
                SELECT target_urn, resource, COUNT(*) AS count
                  FROM lineage_check_finding
                 WHERE occurred_at > now() - (? || ' days')::interval
                   AND check_type = 'ambiguous_column_unresolved'
                 GROUP BY 1, 2 ORDER BY count DESC LIMIT 20
                """, String.valueOf(window)));
        payload.put("note", "「能补的」= 采集上游表就会自动消失的发现（actionable）；"
                + "「需改 SQL 的」= 列在多个上游表里都存在，平台**不猜**，"
                + "因为猜错的那条边比缺失的边更难发现");
        return payload;
    }
}
