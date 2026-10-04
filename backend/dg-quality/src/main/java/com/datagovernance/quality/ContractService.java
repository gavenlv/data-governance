package com.datagovernance.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据契约的注册与版本管理（docs/09 §9.5）。
 *
 * <p>关键立场：
 * <ul>
 *   <li><b>契约是一等实体</b>：因此天生有 Owner、标签、生命周期与**版本历史**
 *       （版本历史直接用 {@code aspect_history}，不另造一套）。</li>
 *   <li><b>apiVersion 与 version 分开治理</b>：前者是 ODCS 规范版本，后者是契约自身的语义化版本。
 *       把两者混在一起，规范升级时所有契约的"版本"都会失去意义。</li>
 *   <li><b>破坏性变更默认拒绝注册</b>：需要显式 {@code allowBreaking} 才能写入。
 *       契约的价值来自"生产者不能随便改"，如果平台自己允许静默破坏，契约就只是文档。</li>
 * </ul>
 */
@Service
public class ContractService {

    public static final String SOURCE = "contract";
    private static final String ODCS_DEFAULT_API_VERSION = "v3.0.2";

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;
    private final RuleExecutionService rules;

    public ContractService(MetadataService metadata, JdbcTemplate jdbc, RuleExecutionService rules) {
        this.metadata = metadata;
        this.jdbc = jdbc;
        this.rules = rules;
    }

    public static String contractUrn(String namespace, String id) {
        return UrnUtils.build("DataContract", namespace, UrnUtils.sanitizeSegment(id));
    }

    /** 注册（或发布新版本）。 */
    public Map<String, Object> register(Map<String, Object> document, String namespace, String actor,
                                        boolean allowBreaking, String breakingJustification,
                                        boolean dryRun) {
        ContractDocument parsed = parse(document, namespace);

        Map<String, Object> currentSpec = metadata.getAspect(parsed.urn(), "contractSpec").orElse(null);
        String currentVersion = currentSpec == null ? null : String.valueOf(currentSpec.get("contractVersion"));

        ContractDiff.Result diff = ContractDiff.compare(currentSpec, parsed.spec(),
                currentVersion, parsed.version());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contract", parsed.urn());
        payload.put("version", parsed.version());
        payload.put("previousVersion", currentVersion);
        payload.put("diff", diff.asMap());
        payload.put("dataset", parsed.datasetUrn());

        if (diff.breaking() && !allowBreaking) {
            payload.put("registered", false);
            payload.put("rejected", "存在破坏性变更，默认拒绝注册（契约的价值来自「生产者不能随便改」）；"
                    + "确认要发布请显式传 allowBreaking=true 并附 breakingJustification");
            return payload;
        }
        if (diff.breaking() && (breakingJustification == null || breakingJustification.isBlank())) {
            payload.put("registered", false);
            payload.put("rejected", "破坏性变更必须附 breakingJustification（谁来承担下游改造成本必须留痕）");
            return payload;
        }
        if (dryRun) {
            payload.put("registered", false);
            payload.put("dryRun", true);
            return payload;
        }

        metadata.ensureEntity(parsed.urn(), "DataContract", parsed.id(), null);
        metadata.upsertAspect(parsed.urn(), "contractSpec", parsed.spec(), "MANUAL", null, null);
        metadata.upsertEdge(parsed.urn(), parsed.datasetUrn(), "appliesTo", "manual", 1.0,
                null, null, null, "VALUE", null, null, null);

        // 契约的 quality 段 → 质量规则（"契约声明 → 生成质量规则"这一步真的接通）
        List<QualityRule> generated = rulesFromContract(parsed);
        payload.put("generatedRules", generated.size());
        if (!generated.isEmpty() && parsed.dsn() != null) {
            payload.put("ruleRegistration", rules.register(generated, namespace, parsed.dsn(),
                    parsed.cron(), "Asia/Shanghai", actor));
        } else if (!generated.isEmpty()) {
            payload.put("ruleRegistration", Map.of(
                    "note", "契约里声明了质量约束，但注册时未提供 dsn，因此规则未落库（不会假装已生效）",
                    "pendingRules", generated.stream().map(QualityRule::ruleId).toList()));
        }

        payload.put("registered", true);
        if (diff.breaking()) {
            payload.put("breakingJustification", breakingJustification);
            payload.put("warning", "本次为破坏性变更：下游消费者应当被定向通知（消费者清单见 /consumers）");
        }
        return payload;
    }

    /** 把契约的 quality 段编译成质量规则（契约与质量子系统的交汇点）。 */
    public List<QualityRule> rulesFromContract(ContractDocument document) {
        Object quality = document.spec().get("quality");
        if (!(quality instanceof List<?> checks) || checks.isEmpty()) {
            return List.of();
        }
        List<QualityRule> out = new ArrayList<>();
        int index = 0;
        for (Object item : checks) {
            if (!(item instanceof Map<?, ?> check)) {
                continue;
            }
            index++;
            Map<String, Object> entry = new LinkedHashMap<>();
            check.forEach((key, value) -> entry.put(String.valueOf(key), value));
            String contractId = document.id() + "@" + document.version();
            List<QualityRule> compiled = RuleCompiler.fromYamlDocument(Map.of(
                    "rule", "contract:" + contractId,
                    "dataset", document.datasetUrn(),
                    "checks", List.of(entry),
                    "severity", Map.of("onFail", entry.getOrDefault("severity", "HIGH"))),
                    document.datasetUrn());
            for (QualityRule rule : compiled) {
                out.add(new QualityRule(
                        rule.ruleId() + "-" + index, rule.datasetUrn(), rule.metric(), rule.operator(),
                        rule.threshold(), rule.thresholdMax(), rule.column(), rule.columns(),
                        rule.window(), rule.percentile(), rule.pattern(), rule.acceptedValues(),
                        rule.customSql(), rule.expected(), rule.severity(), rule.dimension(),
                        rule.onFail(), rule.schedule(),
                        mergeHints(rule.engineHints(), document.urn()), rule.sourceFrontend()));
            }
        }
        return out;
    }

    private static Map<String, Object> mergeHints(Map<String, Object> hints, String contractUrn) {
        Map<String, Object> merged = new LinkedHashMap<>(hints);
        merged.put("generatedFromContract", contractUrn);
        return merged;
    }

    // ---------------------------------------------------------------- 查询

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.urn, e.display_name, a.data, a.version, a.updated_at,
                       (SELECT to_urn FROM edge WHERE from_urn = e.urn AND edge_type = 'appliesTo' LIMIT 1) AS dataset
                  FROM entity e
                  LEFT JOIN aspect a ON a.urn = e.urn AND a.aspect_type = 'contractSpec'
                 WHERE e.entity_type = 'DataContract' AND e.deleted_at IS NULL
                 ORDER BY e.urn
                """);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> spec = parseJson(str(row.get("data")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("urn", row.get("urn"));
            item.put("id", row.get("display_name"));
            item.put("dataset", row.get("dataset"));
            item.put("contractVersion", spec.get("contractVersion"));
            item.put("apiVersion", spec.get("apiVersion"));
            item.put("status", spec.get("status"));
            item.put("compatibility", spec.get("compatibility"));
            item.put("aspectVersion", row.get("version"));
            item.put("updatedAt", row.get("updated_at"));
            item.put("fieldCount", ContractDiff.fieldsOf(spec).size());
            item.put("checkCount", spec.get("quality") instanceof List<?> list ? list.size() : 0);
            item.put("openViolations", jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contract_violation WHERE contract_urn = ? AND status = 'OPEN'",
                    Integer.class, row.get("urn")));
            item.put("consumers", jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contract_consumer WHERE contract_urn = ?",
                    Integer.class, row.get("urn")));
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> get(String urn) {
        Map<String, Object> spec = metadata.getAspect(urn, "contractSpec")
                .orElseThrow(() -> new MetadataException.NotFound("契约不存在或没有 contractSpec：" + urn));
        MetadataService.EntityRow entity = metadata.getEntity(urn);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", urn);
        payload.put("id", entity.displayName());
        payload.put("spec", spec);
        payload.put("dataset", jdbc.queryForList(
                "SELECT to_urn FROM edge WHERE from_urn = ? AND edge_type = 'appliesTo' LIMIT 1",
                String.class, urn).stream().findFirst().orElse(null));
        payload.put("fieldCount", ContractDiff.fieldsOf(spec).size());
        return payload;
    }

    /**
     * 版本历史。
     *
     * <p>版本存储的语义容易搞错：{@code aspect_history} 保存的是**被替换掉的旧版本**，
     * 当前版本在 {@code aspect} 表里。因此只查 history 会漏掉最新版本 —— 契约页面上
     * "当前版本"消失会是非常费解的 bug（这里踩过）。所以两者取并集，并标出哪个是当前版本。
     */
    public List<Map<String, Object>> versions(String urn) {
        return jdbc.queryForList("""
                SELECT version, updated_by, updated_at, contract_version, status, field_count, is_current
                  FROM (
                        SELECT a.version, a.updated_by, a.updated_at,
                               a.data->>'contractVersion' AS contract_version,
                               a.data->>'status' AS status,
                               jsonb_array_length(COALESCE(a.data->'schema'->'fields', '[]'::jsonb)) AS field_count,
                               TRUE AS is_current
                          FROM aspect a WHERE a.urn = ? AND a.aspect_type = 'contractSpec'
                        UNION ALL
                        SELECT h.version, h.updated_by, h.updated_at,
                               h.data->>'contractVersion' AS contract_version,
                               h.data->>'status' AS status,
                               jsonb_array_length(COALESCE(h.data->'schema'->'fields', '[]'::jsonb)) AS field_count,
                               FALSE AS is_current
                          FROM aspect_history h WHERE h.urn = ? AND h.aspect_type = 'contractSpec'
                  ) versions
                 ORDER BY version DESC
                """, urn, urn);
    }

    /** 两个版本之间的兼容性 diff。 */
    public Map<String, Object> diffVersions(String urn, long fromVersion, long toVersion) {
        Map<String, Object> from = specAtVersion(urn, fromVersion);
        Map<String, Object> to = specAtVersion(urn, toVersion);
        ContractDiff.Result result = ContractDiff.compare(from, to,
                str(from.get("contractVersion")), str(to.get("contractVersion")));
        Map<String, Object> payload = new LinkedHashMap<>(result.asMap());
        payload.put("urn", urn);
        payload.put("fromVersion", fromVersion);
        payload.put("toVersion", toVersion);
        return payload;
    }

    /** 取指定版本的契约定义（当前版本在 aspect，历史版本在 aspect_history）。 */
    private Map<String, Object> specAtVersion(String urn, long version) {
        List<String> current = jdbc.queryForList("""
                SELECT data FROM aspect
                 WHERE urn = ? AND aspect_type = 'contractSpec' AND version = ?
                """, String.class, urn, version);
        if (!current.isEmpty()) {
            return parseJson(current.get(0));
        }
        List<String> rows = jdbc.queryForList("""
                SELECT data FROM aspect_history WHERE urn = ? AND aspect_type = 'contractSpec' AND version = ?
                """, String.class, urn, version);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("契约版本不存在：" + urn + " v" + version
                    + "（可用版本见 GET /api/v1/contracts/{urn}/versions）");
        }
        return parseJson(rows.get(0));
    }

    // ------------------------------------------------------------ 消费者关系

    public List<Map<String, Object>> consumers(String urn) {
        return jdbc.queryForList("""
                SELECT consumer_urn, registered_by, registered_at, note
                  FROM contract_consumer WHERE contract_urn = ? ORDER BY consumer_urn
                """, urn);
    }

    public Map<String, Object> addConsumer(String urn, String consumerUrn, String actor, String note) {
        if (consumerUrn == null || consumerUrn.isBlank()) {
            throw new QualityException("consumerUrn 不能为空");
        }
        jdbc.update("""
                INSERT INTO contract_consumer (contract_urn, consumer_urn, registered_by, note)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (contract_urn, consumer_urn) DO UPDATE
                    SET note = EXCLUDED.note, registered_by = EXCLUDED.registered_by
                """, urn, consumerUrn, actor, note);
        return Map.of("contract", urn, "consumer", consumerUrn, "registered", true);
    }

    public boolean removeConsumer(String urn, String consumerUrn) {
        return jdbc.update("DELETE FROM contract_consumer WHERE contract_urn = ? AND consumer_urn = ?",
                urn, consumerUrn) > 0;
    }

    /**
     * 未登记的消费者（docs/09 §9.5 点名的关键能力）：<b>让生产者看到真实消费者</b>。
     *
     * <p>注册的消费者是"声称在用我的人"；血缘里的下游是"实际在读我的人"。
     * 两者的差集才是契约落地的关键信息 —— 契约变更要通知的是后者。
     */
    public Map<String, Object> consumersReport(String urn) {
        String datasetUrn = get(urn).get("dataset") == null ? null : String.valueOf(get(urn).get("dataset"));
        List<String> registered = jdbc.queryForList(
                "SELECT consumer_urn FROM contract_consumer WHERE contract_urn = ?", String.class, urn);

        List<String> actual = new ArrayList<>();
        if (datasetUrn != null) {
            // 注意：用 addAll 而不是重新赋值 —— 后面的 lambda 需要 effectively final 的引用
            actual.addAll(jdbc.queryForList("""
                    WITH RECURSIVE walk(urn, depth) AS (
                        SELECT CAST(? AS text), 0
                        UNION
                        SELECT e.to_urn, w.depth + 1 FROM edge e JOIN walk w ON e.from_urn = w.urn
                         WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE' AND e.edge_type = ANY(?) AND w.depth < 3
                    )
                    SELECT DISTINCT w.urn FROM walk w
                     JOIN entity en ON en.urn = w.urn AND en.entity_type = 'Dataset' AND en.deleted_at IS NULL
                     WHERE w.urn <> ?
                    """, String.class, datasetUrn, metadata.lineageEdgeTypes(), datasetUrn));
        }

        List<String> undetected = actual.stream().filter(item -> !registered.contains(item)).toList();
        List<String> stale = registered.stream().filter(item -> !actual.contains(item)).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contract", urn);
        payload.put("dataset", datasetUrn);
        payload.put("registered", registered);
        payload.put("external", actual);
        payload.put("undetected", undetected);
        payload.put("staleRegistrations", stale);
        payload.put("note", undetected.isEmpty() ? "没有未登记的消费者"
                : "存在未登记消费者：" + undetected.size() + " 个下游在血缘上实际读取本数据集，"
                        + "但未登记为契约消费者 —— 契约变更必须通知它们");
        return payload;
    }

    // ------------------------------------------------------------ 文档解析

    /** 解析后的契约文档。 */
    public record ContractDocument(String urn, String id, String datasetUrn, String version,
                                   Map<String, Object> spec, String dsn, String cron) {
    }

    /** 解析 ODCS 兼容的契约文档。 */
    public ContractDocument parse(Map<String, Object> document, String namespace) {
        String apiVersion = str(document.get("apiVersion"));
        String id = str(document.get("id"));
        String version = str(document.get("version"));
        String dataset = str(document.get("dataset"));

        List<String> problems = new ArrayList<>();
        if (apiVersion == null || apiVersion.isBlank()) {
            problems.add("缺少 apiVersion（ODCS 规范版本，如 " + ODCS_DEFAULT_API_VERSION + "）");
        }
        if (id == null || id.isBlank()) {
            problems.add("缺少 id（契约标识）");
        }
        if (version == null || version.isBlank()) {
            problems.add("缺少 version（契约自身的语义化版本，如 1.2.0）");
        } else if (ContractDiff.parseVersion(version) == null) {
            problems.add("version 必须是语义化版本（major.minor.patch）：" + version);
        }
        if (dataset == null || dataset.isBlank()) {
            problems.add("缺少 dataset（目标数据集 URN）");
        } else if (!UrnUtils.isUrn(dataset)) {
            problems.add("dataset 必须是平台 URN：" + dataset);
        }
        if (!problems.isEmpty()) {
            throw new QualityException("契约文档不合法：" + String.join("；", problems));
        }

        String status = str(document.get("status"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("apiVersion", apiVersion);
        spec.put("kind", document.getOrDefault("kind", "DataContract"));
        spec.put("contractVersion", version);
        spec.put("status", status == null ? "ACTIVE" : status);
        spec.put("compatibility", document.getOrDefault("compatibility", "BACKWARD"));
        spec.put("schema", normalizeSchema(document.get("schema")));
        if (document.get("primaryKey") != null) {
            spec.put("primaryKey", document.get("primaryKey"));
        }
        if (document.get("quality") instanceof List<?> quality) {
            spec.put("quality", quality);
        }
        if (document.get("sla") instanceof Map<?, ?> sla) {
            spec.put("sla", sla);
        }
        if (document.get("semantic") instanceof Map<?, ?> semantic) {
            spec.put("semantic", semantic);
        }
        if (document.get("access") instanceof Map<?, ?> access) {
            spec.put("access", access);
        }
        if (document.get("examples") instanceof List<?> examples) {
            spec.put("examples", examples);
        }
        if (document.get("domain") != null) {
            spec.put("domain", document.get("domain"));
        }

        Object connection = document.get("connection");
        String dsn = null;
        if (connection instanceof Map<?, ?> map && map.get("dsn") != null) {
            dsn = String.valueOf(map.get("dsn"));
        }
        String cron = document.get("schedule") instanceof Map<?, ?> schedule
                && schedule.get("cron") != null ? String.valueOf(schedule.get("cron")) : null;

        return new ContractDocument(contractUrn(namespace, id), id, dataset, version, spec, dsn, cron);
    }

    /** 把 schema 段规范化为 {@code {fields:[...]}}（列表与对象两种写法都接受）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSchema(Object schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema instanceof List<?> list) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> field) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    field.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    fields.add(entry);
                }
            }
            out.put("fields", fields);
            return out;
        }
        if (schema instanceof Map<?, ?> map) {
            if (map.get("fields") instanceof List<?> fields) {
                List<Map<String, Object>> normalized = new ArrayList<>();
                for (Object item : fields) {
                    if (item instanceof Map<?, ?> field) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        field.forEach((key, value) -> entry.put(String.valueOf(key), value));
                        normalized.add(entry);
                    }
                }
                out.put("fields", normalized);
            } else {
                out.putAll((Map<String, Object>) map);
            }
            return out;
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
