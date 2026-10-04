package com.datagovernance.quality;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 契约的运行时校验：把"实际结构"与"承诺的结构"对比，产出违约事件（docs/09 §9.5）。
 *
 * <p>为什么违约要做成事件而不是报错：
 * 数据源的漂移是**持续状态**而不是瞬时错误。做成事件才能回答"这条违约持续了多久、
 * 影响了哪些下游、谁在处理"，而不是每次采集都刷一条日志。
 *
 * <p>豁免规则（刻意设计）：<b>豁免必须带到期时间</b>。
 * 允许永久豁免的治理系统里，所有违约最终都会被永久豁免 —— 于是契约就名存实亡。
 * 到期后自动回到 OPEN。
 */
@Service
public class ContractViolationService {

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;
    private final ContractService contracts;

    public ContractViolationService(MetadataService metadata, JdbcTemplate jdbc, ContractService contracts) {
        this.metadata = metadata;
        this.jdbc = jdbc;
        this.contracts = contracts;
    }

    /** 校验一份契约：对比契约 schema 与数据集当前结构。 */
    public Map<String, Object> validate(String contractUrn, String actor) {
        Map<String, Object> contract = contracts.get(contractUrn);
        Map<String, Object> spec = asMap(contract.get("spec"));
        String datasetUrn = contract.get("dataset") == null ? null : String.valueOf(contract.get("dataset"));
        if (datasetUrn == null) {
            throw new QualityException("契约没有关联数据集（appliesTo 边缺失），无法校验：" + contractUrn);
        }

        Map<String, Map<String, Object>> promised = ContractDiff.fieldsOf(spec);
        Map<String, Object> actualSchema = metadata.getAspect(datasetUrn, "datasetSchema")
                .orElseThrow(() -> new QualityException("数据集没有 datasetSchema，无法校验（先采集）：" + datasetUrn));
        Map<String, Map<String, Object>> actual = new LinkedHashMap<>();
        for (Map<String, Object> field : asMapList(actualSchema.get("fields"))) {
            actual.put(String.valueOf(field.get("name")), field);
        }

        List<Map<String, Object>> violations = new ArrayList<>();
        String severityOnMissing = severityOf(spec, "missing_column", "BLOCK");

        for (Map.Entry<String, Map<String, Object>> entry : promised.entrySet()) {
            String column = entry.getKey();
            Map<String, Object> promise = entry.getValue();
            Map<String, Object> real = actual.get(column);
            if (real == null) {
                violations.add(record(contractUrn, datasetUrn, "missing_column", severityOnMissing,
                        Map.of("column", column, "promisedType", String.valueOf(promise.get("type"))),
                        actor));
                continue;
            }
            String promisedType = str(promise.get("type"));
            String actualType = str(real.get("type"));
            ContractDiff.TypeAssessment assessment = ContractDiff.assessTypeChange(promisedType, actualType);
            if (!assessment.compatible()) {
                violations.add(record(contractUrn, datasetUrn, "type_mismatch", severityOnMissing,
                        Map.of("column", column, "promised", promisedType, "actual", actualType,
                                "reason", assessment.reason()),
                        actor));
            }
            boolean promisedRequired = Boolean.parseBoolean(String.valueOf(promise.getOrDefault("required", false)));
            boolean actualNullable = Boolean.parseBoolean(String.valueOf(real.getOrDefault("nullable", true)));
            if (promisedRequired && actualNullable) {
                violations.add(record(contractUrn, datasetUrn, "nullability", "ALERT",
                        Map.of("column", column, "promised", "required", "actual", "nullable"),
                        actor));
            }
        }

        for (String column : actual.keySet()) {
            if (!promised.containsKey(column)) {
                violations.add(record(contractUrn, datasetUrn, "unexpected_column", "RECORD",
                        Map.of("column", column, "note", "实际存在但契约未声明的列（加法变更通常无害，但必须可见）"),
                        actor));
            }
        }

        // SLA 校验：新鲜度取最近一次剖析结果（没有剖析结果就不判 —— 不假装通过）
        Object sla = spec.get("sla");
        Double freshnessBreach = null;
        if (sla instanceof Map<?, ?> slaMap && slaMap.get("freshness") != null) {
            long maxLag = RuleCompiler.parseDurationSeconds(String.valueOf(slaMap.get("freshness")));
            Double observed = latestMetric(datasetUrn, null, "freshness_seconds");
            if (observed != null && observed > maxLag) {
                freshnessBreach = observed;
                violations.add(record(contractUrn, datasetUrn, "sla_breach", "ALERT",
                        Map.of("metric", "freshness_seconds", "promised", maxLag, "observed", observed,
                                "note", "新鲜度超出契约承诺；数据来源可能已停止更新"),
                        actor));
            }
        }

        reopenExpiredExemptions();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contract", contractUrn);
        payload.put("dataset", datasetUrn);
        payload.put("promisedFields", promised.size());
        payload.put("actualFields", actual.size());
        payload.put("violations", violations);
        payload.put("violationCount", violations.size());
        payload.put("freshnessBreach", freshnessBreach);
        if (violations.isEmpty()) {
            payload.put("note", "没有违约：实际结构与契约一致");
        }
        return payload;
    }

    /** 记录/累加一条违约（按内容哈希去重，occurrences 计数）。 */
    private Map<String, Object> record(String contractUrn, String datasetUrn, String kind,
                                       String severity, Map<String, Object> detail, String actor) {
        String json = toJson(detail);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, status, occurrences FROM contract_violation
                 WHERE contract_urn = ? AND kind = ? AND COALESCE(dataset_urn, '') = COALESCE(?, '')
                   AND md5(detail::text) = md5(CAST(? AS jsonb)::text)
                """, contractUrn, kind, datasetUrn, json);

        if (!existing.isEmpty()) {
            Long id = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("""
                    UPDATE contract_violation SET occurrences = occurrences + 1, last_seen = now()
                     WHERE id = ?
                    """, id);
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            payload.put("kind", kind);
            payload.put("severity", severity);
            payload.put("status", existing.get(0).get("status"));
            payload.put("occurrences", ((Number) existing.get(0).get("occurrences")).intValue() + 1);
            payload.put("repeated", true);
            return payload;
        }

        jdbc.update("""
                INSERT INTO contract_violation (contract_urn, dataset_urn, kind, severity, detail, detected_by)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, contractUrn, datasetUrn, kind, severity, json, actor);
        Map<String, Object> payload = new LinkedHashMap<>(detail);
        payload.put("kind", kind);
        payload.put("severity", severity);
        payload.put("status", "OPEN");
        payload.put("occurrences", 1);
        payload.put("repeated", false);
        return payload;
    }

    public List<Map<String, Object>> list(String status, String severity, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, contract_urn, dataset_urn, kind, severity, status, detail,
                       first_seen, last_seen, occurrences, exempted_until, exempt_reason
                  FROM contract_violation WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            params.add(severity);
        }
        sql.append(" ORDER BY CASE severity WHEN 'BLOCK' THEN 1 WHEN 'ALERT' THEN 2 ELSE 3 END, last_seen DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> acknowledge(long id, String actor) {
        int updated = jdbc.update("""
                UPDATE contract_violation
                   SET status = CASE WHEN status = 'OPEN' THEN 'ACKNOWLEDGED' ELSE status END,
                       detected_by = COALESCE(detected_by, ?)
                 WHERE id = ?
                """, actor, id);
        if (updated == 0) {
            throw new com.datagovernance.core.MetadataException.NotFound("违约事件不存在：" + id);
        }
        return Map.of("id", id, "acknowledged", true,
                "note", "确认不等于修复：状态仍是 ACKNOWLEDGED，重新校验时若仍违约会继续累计次数");
    }

    /**
     * 豁免（必须有到期时间）。
     *
     * @param until 到期时间；为空或不在未来一律拒绝 —— 不允许永久豁免
     */
    public Map<String, Object> exempt(long id, Instant until, String reason, String actor) {
        if (until == null) {
            throw new QualityException("豁免必须提供到期时间：不允许永久豁免"
                    + "（允许永久豁免的系统里，所有违约最终都会被永久豁免）");
        }
        if (until.isBefore(Instant.now())) {
            throw new QualityException("豁免到期时间必须晚于当前时间：" + until);
        }
        if (reason == null || reason.isBlank()) {
            throw new QualityException("豁免必须说明原因（谁会去看一条没有原因的豁免？）");
        }
        int updated = jdbc.update("""
                UPDATE contract_violation
                   SET status = 'EXEMPTED', exempted_until = ?, exempted_by = ?, exempt_reason = ?
                 WHERE id = ?
                """, java.sql.Timestamp.from(until), actor, reason, id);
        if (updated == 0) {
            throw new com.datagovernance.core.MetadataException.NotFound("违约事件不存在：" + id);
        }
        return Map.of("id", id, "status", "EXEMPTED", "exemptedUntil", until,
                "note", "到期后自动回到 OPEN");
    }

    /** 到期的豁免自动回到 OPEN（否则"临时豁免"会变成事实上的永久关闭）。 */
    public int reopenExpiredExemptions() {
        return jdbc.update("""
                UPDATE contract_violation SET status = 'OPEN'
                 WHERE status = 'EXEMPTED' AND exempted_until IS NOT NULL AND exempted_until < now()
                """);
    }

    /** 违约概览（治理页用）。 */
    public Map<String, Object> overview() {
        reopenExpiredExemptions();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bySeverity", jdbc.queryForList("""
                SELECT severity, COUNT(*) AS count FROM contract_violation
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED') GROUP BY 1 ORDER BY 1
                """));
        payload.put("byKind", jdbc.queryForList("""
                SELECT kind, COUNT(*) AS count FROM contract_violation
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED') GROUP BY 1 ORDER BY 2 DESC
                """));
        payload.put("contracts", jdbc.queryForObject("""
                SELECT COUNT(DISTINCT contract_urn) FROM contract_violation
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED')
                """, Integer.class));
        payload.put("oldestOpen", jdbc.queryForList("""
                SELECT id, contract_urn, kind, first_seen, occurrences FROM contract_violation
                 WHERE status = 'OPEN' ORDER BY first_seen LIMIT 5
                """));
        payload.put("exemptionPolicy", "豁免必须带到期时间，到期自动回到 OPEN（拒绝永久豁免）");
        return payload;
    }

    /** 默认豁免时长（供界面提示，不自动应用）。 */
    public static Instant defaultExemptionUntil() {
        return Instant.now().plus(14, ChronoUnit.DAYS);
    }

    // ------------------------------------------------------------------ 工具

    private Double latestMetric(String datasetUrn, String column, String metric) {
        List<Double> rows = jdbc.query("""
                SELECT value_num FROM profile_metric
                 WHERE dataset_urn = ? AND metric = ? AND COALESCE(column_name, '') = COALESCE(?, '')
                   AND value_num IS NOT NULL
                 ORDER BY window_start DESC LIMIT 1
                """, (rs, index) -> rs.getDouble(1), datasetUrn, metric, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String severityOf(Map<String, Object> spec, String kind, String fallback) {
        Object quality = spec.get("quality");
        if (quality instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> check && kind.equals(String.valueOf(check.get("onViolation")))) {
                    return String.valueOf(check.get("severity"));
                }
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, val) -> out.put(String.valueOf(key), val));
            return out;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((key, val) -> entry.put(String.valueOf(key), val));
                out.add(entry);
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
