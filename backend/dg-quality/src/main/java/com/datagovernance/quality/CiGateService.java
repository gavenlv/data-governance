package com.datagovernance.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 契约与治理 CI 门禁（docs/09 §9.5、docs/11 Phase 2）。
 *
 * <p>PR 阶段要能回答三个问题，缺一不可：
 * <ol>
 *   <li><b>这个改动破坏兼容性吗</b>（schema diff + 版本号是否诚实）</li>
 *   <li><b>会影响到谁</b>（血缘影响面：下游数量、关键资产、未登记消费者）</li>
 *   <li><b>治理属性齐了吗</b>（Owner / 描述 / 分级 —— 没有这些，资产进了目录也没人能负责）</li>
 * </ol>
 *
 * <p>判定原则：<b>破坏性变更默认 BLOCK</b>；治理属性缺失默认 WARN（可通过 policy 提升为 BLOCK）。
 * 把"没填 Owner"直接 block 掉流水线会导致团队绕过门禁，所以默认只警告 ——
 * 但它是显性的警告，而不是静默通过。
 *
 * <p>另：CI 调用方拿到的响应带 {@code exitCode} 建议值，脚本据此决定退出码；
 * 门禁 API 不可用时调用方应"不阻断 + 记录"（见 tools/ci/contract_gate.py），
 * 绝不把流水线挂死在网络问题上。
 */
@Service
public class CiGateService {

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;
    private final ContractService contracts;
    private final ContractViolationService violations;

    public CiGateService(MetadataService metadata, JdbcTemplate jdbc, ContractService contracts,
                         ContractViolationService violations) {
        this.metadata = metadata;
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.violations = violations;
    }

    /** 门禁策略（可由调用方覆盖，默认值偏向"拦住真正的破坏性变更"）。 */
    public record GatePolicy(boolean requireOwner, boolean requireDescription, boolean requireClassification,
                             boolean requireNoUndetectedConsumers, int maxDownstreamForBreaking,
                             boolean allowBreaking) {

        public static GatePolicy defaults() {
            return new GatePolicy(false, false, false, false, 0, false);
        }
    }

    public Map<String, Object> check(Map<String, Object> document, String namespace, GatePolicy policy,
                                     boolean dryRun, String source, String actor) {
        GatePolicy effective = policy == null ? GatePolicy.defaults() : policy;
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> passed = new ArrayList<>();

        ContractService.ContractDocument parsed = contracts.parse(document, namespace);
        Map<String, Object> currentSpec = metadata.getAspect(parsed.urn(), "contractSpec").orElse(null);
        String currentVersion = currentSpec == null ? null : String.valueOf(currentSpec.get("contractVersion"));

        ContractDiff.Result diff = ContractDiff.compare(currentSpec, parsed.spec(),
                currentVersion, parsed.version());
        if (diff.breaking()) {
            if (effective.allowBreaking()) {
                warnings.add("存在破坏性变更，但本次调用显式允许（allowBreaking=true）——请确认已在 PR 中留痕");
            } else {
                blocking.add("存在破坏性变更：" + diff.changes().stream()
                        .filter(ContractDiff.Change::breaking)
                        .map(ContractDiff.Change::message).limit(3).toList());
            }
        }
        if (!diff.versionBumpSufficient()) {
            blocking.add("版本号提升与变更性质不符（本次应为 " + diff.requiredVersionBump() + " 级），"
                    + "版本号在骗人时，消费者无法据此判断风险");
        } else if (!"INITIAL".equals(diff.verdict()) && !diff.changes().isEmpty()) {
            passed.add("版本号提升与变更性质一致（" + diff.requiredVersionBump() + "）");
        }

        // 血缘影响面
        Map<String, Object> impact = impactOf(parsed.datasetUrn());
        int downstream = ((Number) impact.get("downstreamCount")).intValue();
        if (diff.breaking() && downstream > 0) {
            warnings.add("该数据集有 " + downstream + " 个下游：破坏性变更的改造成本由它们承担");
        }
        if (effective.maxDownstreamForBreaking() > 0 && downstream > effective.maxDownstreamForBreaking()
                && diff.breaking()) {
            blocking.add("下游数量 " + downstream + " 超过策略上限 " + effective.maxDownstreamForBreaking()
                    + "，破坏性变更需走专项评审");
        } else if (downstream == 0) {
            passed.add("血缘上暂无下游消费者");
        }

        // 治理属性
        Map<String, Object> governance = governanceOf(parsed.datasetUrn());
        if (effective.requireOwner() && !Boolean.TRUE.equals(governance.get("hasOwner"))) {
            blocking.add("目标数据集没有 Owner（治理属性必填策略已开启）");
        } else if (!Boolean.TRUE.equals(governance.get("hasOwner"))) {
            warnings.add("目标数据集没有 Owner：进入目录后没人能对变更负责");
        } else {
            passed.add("目标数据集有 Owner");
        }
        if (effective.requireDescription() && !Boolean.TRUE.equals(governance.get("hasDescription"))) {
            blocking.add("目标数据集没有描述（治理属性必填策略已开启）");
        } else if (!Boolean.TRUE.equals(governance.get("hasDescription"))) {
            warnings.add("目标数据集没有描述");
        }
        if (effective.requireClassification() && !Boolean.TRUE.equals(governance.get("hasClassification"))) {
            blocking.add("目标数据集没有分类分级（治理属性必填策略已开启）");
        } else if (!Boolean.TRUE.equals(governance.get("hasClassification"))) {
            warnings.add("目标数据集没有分类分级：无法判断是否需要脱敏");
        }

        // 未登记消费者（契约落地的关键信息）
        Map<String, Object> consumers = null;
        if (currentSpec != null) {
            try {
                consumers = contracts.consumersReport(parsed.urn());
                List<?> undetected = (List<?>) consumers.get("undetected");
                if (undetected != null && !undetected.isEmpty()) {
                    String message = "有 " + undetected.size() + " 个未登记消费者（血缘上实际读取，但未登记契约消费者）";
                    if (effective.requireNoUndetectedConsumers()) {
                        blocking.add(message);
                    } else {
                        warnings.add(message);
                    }
                }
            } catch (RuntimeException e) {
                warnings.add("未登记消费者检查未能执行：" + e.getMessage());
            }
        }

        if (currentSpec == null) {
            passed.add("首次注册契约（无兼容性风险）");
        }
        if (!diff.changes().isEmpty()) {
            passed.add("变更清单：" + diff.changes().size() + " 项（" + diff.requiredVersionBump() + " 级）");
        }

        String verdict = !blocking.isEmpty() ? "BLOCK" : warnings.isEmpty() ? "PASS" : "WARN";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contract", parsed.urn());
        payload.put("dataset", parsed.datasetUrn());
        payload.put("fromVersion", currentVersion);
        payload.put("toVersion", parsed.version());
        payload.put("verdict", verdict);
        payload.put("exitCode", "BLOCK".equals(verdict) ? 1 : 0);
        payload.put("blocking", blocking);
        payload.put("warnings", warnings);
        payload.put("passed", passed);
        payload.put("diff", diff.asMap());
        payload.put("impact", impact);
        payload.put("governance", governance);
        if (consumers != null) {
            payload.put("consumers", Map.of(
                    "registered", consumers.get("registered"),
                    "undetected", consumers.get("undetected"),
                    "staleRegistrations", consumers.get("staleRegistrations")));
        }
        payload.put("policy", Map.of(
                "requireOwner", effective.requireOwner(),
                "requireDescription", effective.requireDescription(),
                "requireClassification", effective.requireClassification(),
                "requireNoUndetectedConsumers", effective.requireNoUndetectedConsumers(),
                "allowBreaking", effective.allowBreaking(),
                "note", "默认：破坏性变更 BLOCK，治理属性缺失 WARN（把'没填 Owner'直接 block 会让人绕过门禁）"));

        if (!dryRun) {
            jdbc.update("""
                    INSERT INTO contract_ci_check (contract_urn, requested_version, verdict, payload,
                                                   requested_by, source)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, parsed.urn(), parsed.version(), verdict, toJson(payload), actor, source);
        }
        return payload;
    }

    /** 门禁历史（回答"这个契约被拦过几次、为什么"）。 */
    public List<Map<String, Object>> history(String contractUrn, int limit) {
        return jdbc.queryForList("""
                SELECT id, contract_urn, requested_version, verdict, requested_by, source, created_at,
                       payload->'blocking' AS blocking
                  FROM contract_ci_check
                 -- 必须显式 CAST：可空参数直接参与 IS NULL 比较时，
                 -- PostgreSQL 无法推断类型（"could not determine data type of parameter"）
                 WHERE (CAST(? AS text) IS NULL OR contract_urn = ?)
                 ORDER BY created_at DESC LIMIT ?
                """, contractUrn, contractUrn, Math.min(Math.max(limit, 1), 200));
    }

    // ---------------------------------------------------------------- 影响面

    private Map<String, Object> impactOf(String datasetUrn) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT e.to_urn, w.depth + 1 FROM edge e JOIN walk w ON e.from_urn = w.urn
                     WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE' AND e.edge_type = ANY(?) AND w.depth < 3
                )
                SELECT w.urn, MIN(w.depth) AS depth, COALESCE(e.display_name, w.urn) AS display_name
                  FROM walk w LEFT JOIN entity e ON e.urn = w.urn
                 WHERE w.urn <> ? AND w.urn NOT LIKE 'urn:dg:Column:%'
                 GROUP BY w.urn, e.display_name
                 ORDER BY depth
                """, datasetUrn, metadata.lineageEdgeTypes(), datasetUrn);

        int critical = 0;
        List<String> details = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String urn = String.valueOf(row.get("urn"));
            String classification = metadata.getAspect(urn, "classification")
                    .map(data -> String.valueOf(data.get("level"))).orElse("L2");
            if ("L4".equals(classification)) {
                critical++;
            }
            if (details.size() < 20) {
                details.add(String.valueOf(row.get("display_name")) + " (跳数 " + row.get("depth")
                        + ", 分级 " + classification + ")");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("downstreamCount", rows.size());
        payload.put("criticalCount", critical);
        payload.put("maxDepth", 3);
        payload.put("downstream", details);
        return payload;
    }

    private Map<String, Object> governanceOf(String datasetUrn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Map<String, Object>> aspects = metadata.listAspects(datasetUrn);
        Map<String, Object> ownership = aspects.get("ownership");
        Map<String, Object> descriptions = aspects.get("descriptions");
        Map<String, Object> classification = aspects.get("classification");

        boolean hasOwner = ownership != null && ownership.get("owners") instanceof List<?> owners
                && !owners.isEmpty();
        boolean hasDescription = descriptions != null && descriptions.get("text") != null
                && !String.valueOf(descriptions.get("text")).isBlank();

        payload.put("hasOwner", hasOwner);
        payload.put("hasDescription", hasDescription);
        payload.put("hasClassification", classification != null);
        payload.put("classification", classification == null ? null : classification.get("level"));
        List<String> missing = new ArrayList<>();
        if (!hasOwner) {
            missing.add("ownership");
        }
        if (!hasDescription) {
            missing.add("descriptions");
        }
        if (classification == null) {
            missing.add("classification");
        }
        payload.put("missing", missing);
        return payload;
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
