package com.datagovernance.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
import com.datagovernance.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异常检测（docs/09 §9.4 的 L1–L3 分层）。
 *
 * <p>分层实现，**够用即止**（文档原话：成本从低到高，够用即止）：
 * <ol>
 *   <li><b>L1 静态阈值</b>：由质量规则子系统承担（row_count &gt; 0 这类），本类不重复；</li>
 *   <li><b>L2 稳健统计</b>：MAD 稳健 Z 分数 {@code |x − median| / (1.4826 · MAD) > k} ——
 *       抗离群值优于 3σ，是默认统计层；</li>
 *   <li><b>L3 季节性基线</b>：按"同一个位置（如周内某天/同一小时）"分组取中位数与 MAD，
 *       对 remainder 做 L2 判定。<b>诚实标注</b>：这是 STL 分解的简化替代
 *       （完整 STL 需要 loess 迭代，本实现没有引入统计库），因此方法名记为
 *       {@code seasonal_mad} 而不是 {@code stl}。</li>
 * </ol>
 *
 * <p><b>上游抑制下游</b>（文档点名的、治理平台独有的优势）：某表异常时，
 * 其下游同批异常应被标为「传播」而不是独立告警 —— 因为平台同时掌握血缘。
 * 这一步真的调用血缘遍历（只沿 {@code lineage: true} 的边）。
 *
 * <p>不做的部分明说：**分布漂移（PSI/KS）未实现** —— 它需要直方图/分位数草图，
 * 而 profiling 目前只存统计量不存分布。
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    /** MAD 稳健 Z 的默认阈值（文档建议 3）。 */
    private static final double DEFAULT_Z = 3.0;
    /** 样本下限：少于这个数不判定（否则任何波动都会被当成异常，误报率爆炸）。 */
    private static final int MIN_SAMPLES = 7;

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final ModelRegistry registry;

    public AnomalyService(JdbcTemplate jdbc, MetadataService metadata, ModelRegistry registry) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.registry = registry;
    }

    /** 一次异常扫描的参数。 */
    public record ScanRequest(String datasetUrn, String metric, String method, Double zThreshold,
                              Integer lookbackDays, Integer seasonalPeriod) {
    }

    /**
     * 扫描指标时序并产出检测。
     *
     * @param method L2 = {@code mad}，L3 = {@code seasonal_mad}
     */
    @Transactional
    public Map<String, Object> scan(ScanRequest request, String actor) {
        String method = request.method() == null ? "mad" : request.method();
        if (!List.of("mad", "seasonal_mad").contains(method)) {
            throw new QualityException("method 必须是 mad 或 seasonal_mad（L1 静态阈值由质量规则承担）");
        }
        double z = request.zThreshold() == null ? DEFAULT_Z : request.zThreshold();
        int lookback = request.lookbackDays() == null ? 90 : request.lookbackDays();
        int period = request.seasonalPeriod() == null ? 7 : request.seasonalPeriod();

        List<Map<String, Object>> series = jdbc.queryForList("""
                SELECT dataset_urn, column_name, metric, window_start, value_num
                  FROM profile_metric
                 WHERE value_num IS NOT NULL
                   AND window_start > now() - (? || ' days')::interval
                   AND (CAST(? AS text) IS NULL OR dataset_urn = ?)
                   AND (CAST(? AS text) IS NULL OR metric = ?)
                 ORDER BY dataset_urn, column_name, metric, window_start
                """, String.valueOf(lookback), request.datasetUrn(), request.datasetUrn(),
                request.metric(), request.metric());

        // 按 (dataset, column, metric) 分组
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : series) {
            String key = row.get("dataset_urn") + "|" + (row.get("column_name") == null ? "" : row.get("column_name"))
                    + "|" + row.get("metric");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> detections = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> points = entry.getValue();
            if (points.size() < MIN_SAMPLES) {
                // 样本不足必须显式说明，而不是"看起来正常"
                skipped.add(Map.of("series", entry.getKey(), "samples", points.size(),
                        "reason", "样本不足 " + MIN_SAMPLES + " 个：不做统计判定（避免噪声被当成异常）"));
                continue;
            }
            Map<String, Object> latest = points.get(points.size() - 1);
            double observed = ((Number) latest.get("value_num")).doubleValue();
            List<Double> history = new ArrayList<>();
            for (int i = 0; i < points.size() - 1; i++) {
                history.add(((Number) points.get(i).get("value_num")).doubleValue());
            }

            // L3：按"位置"分组（同一周内第几天）取基线；L2：整体基线
            List<Double> baselineSample = history;
            String effectiveMethod = method;
            if ("seasonal_mad".equals(method)) {
                List<Double> seasonal = new ArrayList<>();
                for (int i = 0; i < points.size() - 1; i++) {
                    if (i % period == (points.size() - 1) % period) {
                        seasonal.add(((Number) points.get(i).get("value_num")).doubleValue());
                    }
                }
                if (seasonal.size() >= 4) {
                    baselineSample = seasonal;
                } else {
                    // 季节性样本不够时退回 L2，并如实记录降级（不假装做了季节分解）
                    effectiveMethod = "mad";
                }
            }

            RobustStats stats = RobustStats.of(baselineSample);
            if (stats == null) {
                skipped.add(Map.of("series", entry.getKey(), "samples", baselineSample.size(),
                        "reason", "基线样本不足：无法计算 MAD"));
                continue;
            }
            double score = stats.zScore(observed);
            if (Math.abs(score) <= z) {
                continue;
            }
            String datasetUrn = String.valueOf(latest.get("dataset_urn"));
            String column = latest.get("column_name") == null ? null : String.valueOf(latest.get("column_name"));
            String metricName = String.valueOf(latest.get("metric"));
            String severity = severityOf(score);
            Map<String, Object> detection = record(datasetUrn, column, metricName,
                    (java.sql.Timestamp) latest.get("window_start"), observed, stats.median(), score,
                    effectiveMethod, z, severity, baselineSample.size(),
                    Map.of("median", stats.median(), "mad", stats.mad(),
                            "seasonalPeriod", period,
                            "methodNote", "seasonal_mad".equals(effectiveMethod)
                                    ? "按位置（周期 " + period + "）取基线中位数与 MAD —— STL 分解的简化替代"
                                    : "整体中位数与 MAD（L2 稳健统计）"));
            detections.add(detection);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("zThreshold", z);
        payload.put("lookbackDays", lookback);
        payload.put("seriesScanned", groups.size());
        payload.put("detections", detections);
        payload.put("detectionCount", detections.size());
        payload.put("skipped", skipped);
        payload.put("actor", actor);
        payload.put("notImplemented", List.of(
                "分布漂移检测（PSI / KS / 卡方）：需要直方图或分位数草图，profiling 目前只存统计量",
                "变点检测（CUSUM / PELT）与多指标 ML（Isolation Forest）：属后续层，未实现"));
        return payload;
    }

    /** 记录一条检测，并做"上游抑制下游"的传播判定。 */
    private Map<String, Object> record(String datasetUrn, String column, String metric,
                                       java.sql.Timestamp windowStart, double observed, double baseline,
                                       double score, String method, double threshold, String severity,
                                       int samples, Map<String, Object> detail) {
        Propagation propagation = classifyPropagation(datasetUrn, windowStart, metric);
        Long id = jdbc.queryForObject("""
                INSERT INTO anomaly_detection (dataset_urn, column_name, metric, window_start, observed,
                                               baseline, score, method, threshold, severity, propagation,
                                               propagated_from, samples, suppressed, suppress_reason, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (dataset_urn, COALESCE(column_name, ''), metric, window_start, method) DO UPDATE
                    SET observed = EXCLUDED.observed, baseline = EXCLUDED.baseline, score = EXCLUDED.score,
                        threshold = EXCLUDED.threshold, severity = EXCLUDED.severity,
                        propagation = EXCLUDED.propagation, propagated_from = EXCLUDED.propagated_from,
                        samples = EXCLUDED.samples, suppressed = EXCLUDED.suppressed,
                        suppress_reason = EXCLUDED.suppress_reason, detail = EXCLUDED.detail,
                        detected_at = now()
                RETURNING id
                """, Long.class, datasetUrn, column, metric, windowStart, observed, baseline, score,
                method, threshold, severity, propagation.kind(), propagation.from(), samples,
                propagation.suppressed(), propagation.reason(), toJson(detail));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("dataset", datasetUrn);
        payload.put("column", column);
        payload.put("metric", metric);
        payload.put("observed", observed);
        payload.put("baseline", baseline);
        payload.put("score", Math.round(score * 100) / 100.0);
        payload.put("method", method);
        payload.put("severity", severity);
        payload.put("propagation", propagation.kind());
        payload.put("propagatedFrom", propagation.from());
        payload.put("samples", samples);
        payload.put("suppressed", propagation.suppressed());
        payload.put("suppressReason", propagation.reason());
        return payload;
    }

    /** 传播判定的结果。 */
    private record Propagation(String kind, String from, boolean suppressed, String reason) {
    }

    /**
     * 上游抑制下游：只在**同批次窗口**内查找上游是否也有异常。
     *
     * <p>这是治理平台相比纯可观测性产品的独有优势（docs/09 §9.4）：
     * 某张表异常时，它的下游往往"连锁异常"，把它们各报一条告警只会制造噪音。
     * 判定为 PROPAGATED 的检测仍然保留（可追溯），但被标记为抑制，不进入告警。
     */
    private Propagation classifyPropagation(String datasetUrn, java.sql.Timestamp windowStart, String metric) {
        List<String> upstreams = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT e.from_urn, w.depth + 1 FROM edge e JOIN walk w ON e.to_urn = w.urn
                     WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE'
                       AND e.edge_type = ANY(?) AND w.depth < 3
                )
                SELECT w.urn FROM walk w WHERE w.urn <> ?
                """, String.class, datasetUrn, metadata.lineageEdgeTypes(), datasetUrn);
        if (upstreams.isEmpty()) {
            return new Propagation("ISOLATED", null, false, null);
        }
        List<String> anomalous = jdbc.query(connection -> {
            // text[] 必须用 createArrayOf 绑（setObject(String[]) → SQLFeatureNotSupportedException）
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    SELECT DISTINCT dataset_urn FROM anomaly_detection
                     WHERE dataset_urn = ANY(?) AND metric = ?
                       AND window_start >= ?::timestamptz - interval '1 day'
                     LIMIT 1
                    """);
            ps.setArray(1, connection.createArrayOf("text", upstreams.toArray()));
            ps.setString(2, metric);
            ps.setTimestamp(3, windowStart);
            return ps;
        }, (rs, rowNum) -> rs.getString(1));
        if (anomalous.isEmpty()) {
            return new Propagation("SOURCE", null, false, null);
        }
        String from = anomalous.get(0);
        return new Propagation("PROPAGATED", from, true,
                "上游 " + from + " 在同一窗口已异常：判为传播而非独立故障（上游抑制下游）");
    }

    private static String severityOf(double score) {
        double abs = Math.abs(score);
        if (abs >= 8) {
            return "CRITICAL";
        }
        if (abs >= 6) {
            return "HIGH";
        }
        if (abs >= 4.5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    // ------------------------------------------------------------------ 查询

    public List<Map<String, Object>> list(boolean includeSuppressed, int limit) {
        return jdbc.queryForList("""
                SELECT id, dataset_urn, column_name, metric, window_start, observed, baseline, score,
                       method, threshold, severity, propagation, propagated_from, samples,
                       suppressed, suppress_reason, detail, detected_at
                  FROM anomaly_detection
                 WHERE (CAST(? AS boolean) OR suppressed = FALSE)
                 ORDER BY detected_at DESC LIMIT ?
                """, includeSuppressed, Math.min(Math.max(limit, 1), 500));
    }

    /** 概览：按方法/严重度/传播分类统计（误报治理需要这几个数）。 */
    public Map<String, Object> overview() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("byMethod", jdbc.queryForList("""
                SELECT method, COUNT(*) AS count FROM anomaly_detection
                 WHERE detected_at > now() - interval '30 days' GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("bySeverity", jdbc.queryForList("""
                SELECT severity, COUNT(*) AS count FROM anomaly_detection
                 WHERE detected_at > now() - interval '30 days' AND suppressed = FALSE
                 GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("propagation", jdbc.queryForList("""
                SELECT propagation, COUNT(*) AS count FROM anomaly_detection
                 WHERE detected_at > now() - interval '30 days' GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("topDatasets", jdbc.queryForList("""
                SELECT dataset_urn, COUNT(*) AS count FROM anomaly_detection
                 WHERE detected_at > now() - interval '30 days' AND suppressed = FALSE
                 GROUP BY 1 ORDER BY 2 DESC LIMIT 10
                """));
        payload.put("detectionModes", jdbc.queryForList("""
                SELECT COUNT(*) FILTER (WHERE method = 'mad') AS mad,
                       COUNT(*) FILTER (WHERE method = 'seasonal_mad') AS seasonal_mad,
                       COUNT(*) FILTER (WHERE method = 'static_threshold') AS static_threshold
                  FROM anomaly_detection
                """));
        payload.put("note", "误报率是这里的第一优先级指标：只看条数会忽略「全是噪音」这种情况。"
                + "PROPAGATED 的检测默认被抑制（上游抑制下游），但仍保留可追溯。");
        return payload;
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 稳健统计：中位数与 MAD（绝对中位差）。
     *
     * <p>为什么不用 3σ：标准差本身会被离群值拉大，一个极端值就能让 σ 变大、
     * 从而"掩盖"后续异常。MAD 对离群值不敏感，因此是默认统计层（docs/09 §9.4）。
     */
    public record RobustStats(double median, double mad) {

        public static RobustStats of(List<Double> values) {
            if (values == null || values.size() < 4) {
                return null;
            }
            List<Double> sorted = new ArrayList<>(values);
            java.util.Collections.sort(sorted);
            double median = percentile(sorted, 0.5);
            List<Double> deviations = new ArrayList<>(sorted.size());
            for (double value : sorted) {
                deviations.add(Math.abs(value - median));
            }
            java.util.Collections.sort(deviations);
            double mad = percentile(deviations, 0.5);
            return new RobustStats(median, mad);
        }

        /** 稳健 Z 分数；MAD 为 0 时用中位数的相对偏差兜底（否则会除零）。 */
        public double zScore(double observed) {
            if (mad > 1e-12) {
                return (observed - median) / (1.4826 * mad);
            }
            if (Math.abs(median) > 1e-12) {
                return (observed - median) / Math.abs(median) * 3.0;
            }
            return observed == 0 ? 0.0 : Math.signum(observed) * 9.9;
        }

        private static double percentile(List<Double> sorted, double fraction) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = (int) Math.round(fraction * (sorted.size() - 1));
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 供 SLO/事故子系统引用：最近的未抑制异常。 */
    public List<Map<String, Object>> recentUnsuppressed(int minutes) {
        return jdbc.queryForList("""
                SELECT id, dataset_urn, metric, severity, score, propagation, detected_at
                  FROM anomaly_detection
                 WHERE suppressed = FALSE AND detected_at > now() - (? || ' minutes')::interval
                 ORDER BY detected_at DESC
                """, String.valueOf(minutes));
    }

    /** 严重度排序（用于"取最严重的那条"）。 */
    public static int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }
}
