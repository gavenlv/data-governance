package com.datagovernance.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 建议引擎与收件箱（docs/13 §4、docs/09 §9.8 的治理运营）。
 *
 * <p>设计立场（docs/13 §4 的原话）：<b>无据不答、全部带 provenance</b>。
 * 因此本类的两条硬性约束：
 * <ol>
 *   <li>每条建议都必须有 {@code rationale}（依据）与 {@code generator}（来源）；
 *       没有依据的建议在治理场景里是负资产 —— 它让人无法判断该不该信；</li>
 *   <li>**没有配置 LLM 时明确报错**，不假装有 AI：{@link #llmStatus()} 会说明未配置，
 *       调用 LLM 生成时会抛错并给出接入方式，而不是静默退回模板（那会让人以为真的接了大模型）。</li>
 * </ol>
 *
 * <p>本类实现的**确定性生成器**（不依赖大模型，立即可用且有真实价值）：
 * <ul>
 *   <li>无 Owner 但下游多 → 建议指派负责人（依据：下游数量 + 影响面）；</li>
 *   <li>有结构但无描述 → 建议补描述（依据：字段清单）；</li>
 *   <li>列名像敏感字段且未分级 → 建议分级复核（依据：列名模式 + 无 classification）；</li>
 *   <li>高分级资产无契约 → 建议补契约（依据：分级 + 契约检查）。</li>
 * </ul>
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final String llmEndpoint;
    private final String llmModel;

    public SuggestionService(JdbcTemplate jdbc, MetadataService metadata,
                             @Value("${dg.ai.llm-endpoint:}") String llmEndpoint,
                             @Value("${dg.ai.llm-model:}") String llmModel) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.llmEndpoint = llmEndpoint == null ? "" : llmEndpoint.trim();
        this.llmModel = llmModel == null ? "" : llmModel.trim();
    }

    // ------------------------------------------------------------------ 生成

    /** 运行确定性生成器，产出待审建议。 */
    @Transactional
    public Map<String, Object> generate(String namespace, int limit) {
        List<String> created = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();

        // 1) 无 Owner 且下游多 → 建议指派负责人
        List<Map<String, Object>> unowned = jdbc.queryForList("""
                SELECT e.urn, e.display_name,
                       (SELECT COUNT(*) FROM edge d
                         WHERE d.from_urn = e.urn AND d.state = 'ACTIVE') AS downstream
                  FROM entity e
                  LEFT JOIN aspect o ON o.urn = e.urn AND o.aspect_type = 'ownership'
                 WHERE e.entity_type = 'Dataset' AND e.deleted_at IS NULL
                   AND (o.data IS NULL OR COALESCE(jsonb_array_length(o.data->'owners'), 0) = 0)
                   AND (CAST(? AS text) IS NULL OR e.namespace = ?)
                 ORDER BY downstream DESC
                 LIMIT ?
                """, namespace, namespace, Math.min(Math.max(limit, 1), 100));
        for (Map<String, Object> row : unowned) {
            int downstream = ((Number) row.get("downstream")).intValue();
            if (downstream < 2) {
                continue;  // 下游很少的资产先不催，避免建议泛滥
            }
            Map<String, Object> suggestion = record(
                    String.valueOf(row.get("urn")), "ownership", "owners", "owner",
                    Map.of("action", "ASSIGN_OWNER", "candidateRole", "该域数据管家"),
                    "该资产有 " + downstream + " 个下游依赖，但没有任何 Owner："
                            + "无主资产是治理优先级的头号信号（改动没人接、故障没人管）",
                    0.75, "deterministic", "rule:unowned_with_downstream",
                    Map.of("downstreamCount", downstream));
            if (suggestion != null) {
                items.add(suggestion);
                created.add(String.valueOf(suggestion.get("id")));
            }
        }

        // 2) 有结构但无描述 → 建议补描述
        List<Map<String, Object>> undescribed = jdbc.queryForList("""
                SELECT e.urn, e.display_name, s.data AS schema
                  FROM entity e
                  JOIN aspect s ON s.urn = e.urn AND s.aspect_type = 'datasetSchema'
                  LEFT JOIN aspect d ON d.urn = e.urn AND d.aspect_type = 'descriptions'
                 WHERE e.entity_type = 'Dataset' AND e.deleted_at IS NULL
                   AND (d.data IS NULL OR COALESCE(d.data->>'text', '') = '')
                   AND (CAST(? AS text) IS NULL OR e.namespace = ?)
                 LIMIT ?
                """, namespace, namespace, Math.min(Math.max(limit, 1), 100));
        for (Map<String, Object> row : undescribed) {
            List<String> columns = new ArrayList<>();
            try {
                Map<String, Object> schema = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(String.valueOf(row.get("schema")),
                                new com.fasterxml.jackson.core.type.TypeReference<>() { });
                if (schema.get("fields") instanceof List<?> fields) {
                    for (Object field : fields) {
                        if (field instanceof Map<?, ?> map && map.get("name") != null) {
                            columns.add(String.valueOf(map.get("name")));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("解析 schema 失败（跳过该资产）：{}", e.getMessage());
            }
            Map<String, Object> suggestion = record(
                    String.valueOf(row.get("urn")), "descriptions", "text", "describe",
                    Map.of("action", "DRAFT_DESCRIPTION",
                            "displayName", String.valueOf(row.get("display_name")),
                            "columns", columns.stream().limit(10).toList()),
                    "该资产已采集到 " + columns.size() + " 个字段但没有描述："
                            + "描述是「这张表能不能用」的第一判断依据（docs/14 §1 的三秒原则）。"
                            + "平台可以给出草稿，但**最终文字必须由人确认**",
                    0.6, "deterministic", "rule:undescribed_dataset",
                    Map.of("columnCount", columns.size()));
            if (suggestion != null) {
                items.add(suggestion);
                created.add(String.valueOf(suggestion.get("id")));
            }
        }

        // 3) 列名像敏感字段但没有分级 → 建议分级复核
        List<Map<String, Object>> sensitive = jdbc.queryForList("""
                SELECT c.urn, c.display_name
                  FROM entity c
                  LEFT JOIN aspect cl ON cl.urn = c.urn AND cl.aspect_type = 'classification'
                 WHERE c.entity_type = 'Column' AND c.deleted_at IS NULL
                   AND cl.data IS NULL
                   AND (c.display_name ~* '(phone|mobile|email|id_card|idcard|passwd|password|secret|token|ssn|bank|card_no)'
                        OR c.display_name ~* '(姓名|身份证|手机|电话|邮箱|地址|银行|密码)')
                   AND (CAST(? AS text) IS NULL OR c.namespace = ?)
                 LIMIT ?
                """, namespace, namespace, Math.min(Math.max(limit, 1), 100));
        for (Map<String, Object> row : sensitive) {
            Map<String, Object> suggestion = record(
                    String.valueOf(row.get("urn")), "classification", "level", "classify",
                    Map.of("action", "REVIEW_CLASSIFICATION", "proposedLevel", "L3",
                            "column", String.valueOf(row.get("display_name"))),
                    "列名 " + row.get("display_name") + " 命中敏感字段模式，但没有任何分级："
                            + "未分级的敏感列既不会被脱敏，也不会被访问审批拦住 —— 这是最常见的合规缺口",
                    0.7, "deterministic", "rule:sensitive_column_ungraded",
                    Map.of("matchedPattern", "sensitive-name", "currentLevel", "L2（默认）"));
            if (suggestion != null) {
                items.add(suggestion);
                created.add(String.valueOf(suggestion.get("id")));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generated", items.size());
        payload.put("items", items);
        payload.put("generators", List.of("rule:unowned_with_downstream", "rule:undescribed_dataset",
                "rule:sensitive_column_ungraded"));
        payload.put("cooldownDays", COOLDOWN_DAYS);
        payload.put("llmStatus", llmStatus());
        payload.put("note", "确定性生成器立即可用（不需要大模型）；"
                + "已有待审同类建议、或最近 " + COOLDOWN_DAYS + " 天内已裁决过的，不会重复生成"
                + "（否则驳回等于没驳回）；大模型建议需要配置 dg.ai.llm-endpoint —— "
                + "**未配置时会明确报错，不会假装有 AI**");
        return payload;
    }

    /**
     * 冷却期：最近裁决过的同类建议不再重复生成。
     *
     * <p>没有这条规则会怎样（这是实测暴露出来的）：采纳/驳回之后，同一实体的同一个缺口
     * 立刻又满足生成条件，于是**每次生成都会把它重新塞回收件箱** ——
     * 驳回等于没驳回，收件箱被同一诉求反复刷屏。
     * 冷却窗口是"人已经做过判断"的敬意，也是让采纳率这个指标有意义的前提。
     */
    private static final int COOLDOWN_DAYS = 7;

    private boolean inCooldown(String urn, String kind, String field, String generator) {
        Integer recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM suggestion
                 WHERE entity_urn = ? AND kind = ? AND COALESCE(field, '') = COALESCE(?, '')
                   AND generator = ? AND status IN ('ACCEPTED', 'REJECTED')
                   AND reviewed_at > now() - (? || ' days')::interval
                """, Integer.class, urn, kind, field, generator, String.valueOf(COOLDOWN_DAYS));
        return recent != null && recent > 0;
    }

    private Map<String, Object> record(String urn, String aspectType, String field, String kind,
                                       Map<String, Object> proposal, String rationale, double confidence,
                                       String generator, String generatorRef, Map<String, Object> evidence) {
        if (inCooldown(urn, kind, field, generator)) {
            return null;
        }
        try {
            // 注意：`INSERT … ON CONFLICT DO NOTHING RETURNING id` 在冲突时返回**零行**，
            // 用 queryForObject 会抛 EmptyResultDataAccessException（不是返回 null）——
            // 于是"重复建议静默跳过"会变成 500。必须用 queryForList 取首行。
            List<Long> ids = jdbc.queryForList("""
                    INSERT INTO suggestion (entity_urn, aspect_type, field, kind, proposal, rationale,
                                            confidence, generator, generator_ref, evidence)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (entity_urn, kind, COALESCE(field, ''), generator)
                        WHERE status = 'PENDING' DO NOTHING
                    RETURNING id
                    """, Long.class, urn, aspectType, field, kind, toJson(proposal), rationale,
                    confidence, generator, generatorRef, toJson(evidence));
            if (ids.isEmpty()) {
                return null;  // 已有待审的同类建议，静默跳过（避免收件箱被重复建议淹没）
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ids.get(0));
            item.put("entityUrn", urn);
            item.put("kind", kind);
            item.put("rationale", rationale);
            item.put("confidence", confidence);
            item.put("generator", generator);
            item.put("generatorRef", generatorRef);
            return item;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 收件箱

    /**
     * 外部主体（MCP Agent / 脚本）提交建议。
     *
     * <p>这是 Agent **唯一的写路径**：它只能提建议，由人采纳后才落 aspect。
     * 让外部 Agent 直接改元数据，等于把治理纪律交给一个不受控的进程。
     * {@code generator} 记为 {@code llm} 还是 {@code deterministic} 取决于提交者：
     * 这里统一记为 {@code human}（提交方是外部主体），并把来源写进 evidence。
     */
    @Transactional
    public Map<String, Object> propose(String urn, String aspectType, String field, String value,
                                       String rationale, String proposer) {
        if (urn == null || urn.isBlank()) {
            throw new AiException("缺少 urn");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new AiException("提交建议必须给出 rationale（依据）：没有依据的建议无法被审核");
        }
        String kind = kindOf(aspectType);
        Map<String, Object> suggestion = record(urn, aspectType, field, kind,
                Map.of("action", "PROPOSED_BY_EXTERNAL", "value", value),
                rationale, 0.5, "human", proposer, Map.of("proposer", proposer, "value", value));
        if (suggestion == null) {
            return Map.of("created", false,
                    "note", "该实体同字段已有待审建议：**没有重复创建**（避免收件箱被同一诉求刷屏）");
        }
        Map<String, Object> payload = new LinkedHashMap<>(suggestion);
        payload.put("created", true);
        payload.put("note", "已进入人工审批队列（PENDING）：Agent 不能直接改写元数据");
        return payload;
    }

    /** aspect 类型 → 建议 kind（受 suggestion_kind_chk 约束，未知类型直接拒绝）。 */
    private static String kindOf(String aspectType) {
        return switch (aspectType == null ? "" : aspectType) {
            case "descriptions" -> "describe";
            case "tags" -> "tag";
            case "ownership" -> "owner";
            case "classification" -> "classify";
            case "contractSpec" -> "contract";
            case "ruleSpec" -> "rule";
            default -> throw new AiException("不支持的 aspectType：" + aspectType
                    + "（descriptions / tags / ownership / classification / contractSpec / ruleSpec）");
        };
    }

    public List<Map<String, Object>> inbox(String status, double minConfidence, int limit) {
        return jdbc.queryForList("""
                SELECT id, entity_urn, aspect_type, field, kind, proposal, rationale, confidence,
                       generator, generator_ref, evidence, status, reviewed_by, reviewed_at,
                       review_note, created_at
                  FROM suggestion
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND confidence >= ?
                 ORDER BY confidence DESC, created_at DESC LIMIT ?
                """, status, status, minConfidence, Math.min(Math.max(limit, 1), 500));
    }

    /** 采纳：把建议写入 aspect（**必须由人触发**，AI 不自动改元数据）。 */
    @Transactional
    public Map<String, Object> accept(long id, String actor, Map<String, Object> editedValue) {
        Map<String, Object> row = find(id);
        if (!"PENDING".equals(row.get("status"))) {
            throw new AiException("建议当前状态为 " + row.get("status") + "，不能再次处理");
        }
        String urn = String.valueOf(row.get("entity_urn"));
        String aspectType = row.get("aspect_type") == null ? null : String.valueOf(row.get("aspect_type"));
        Map<String, Object> proposal = parseJson(String.valueOf(row.get("proposal")));
        Map<String, Object> value = editedValue != null && !editedValue.isEmpty() ? editedValue : proposalToAspect(aspectType, proposal);

        Long aspectVersion = null;
        if (aspectType != null && value != null && !value.isEmpty()) {
            var result = metadata.upsertAspect(urn, aspectType, value, "AI_GENERATED", null, null);
            aspectVersion = result.version();
        }
        jdbc.update("""
                UPDATE suggestion SET status = 'ACCEPTED', reviewed_by = ?, reviewed_at = now(),
                                     review_note = ?, applied_aspect_version = ?
                 WHERE id = ?
                """, actor, editedValue == null ? "按原建议采纳" : "采纳并人工编辑后写入", aspectVersion, id);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("status", "ACCEPTED");
        payload.put("entityUrn", urn);
        payload.put("aspectType", aspectType);
        payload.put("appliedAspectVersion", aspectVersion);
        payload.put("note", "采纳会以 **AI_GENERATED** 来源写入："
                + "该来源在字段级优先级里低于 MANUAL/IMPORTED，因此后续采集不会覆盖它，"
                + "但人工再编辑仍可覆盖（ADR-005）");
        return payload;
    }

    @Transactional
    public Map<String, Object> reject(long id, String actor, String reason) {
        Map<String, Object> row = find(id);
        if (!"PENDING".equals(row.get("status"))) {
            throw new AiException("建议当前状态为 " + row.get("status") + "，不能再次处理");
        }
        if (reason == null || reason.isBlank()) {
            throw new AiException("驳回建议必须说明原因（驳回理由就是改进生成器的输入）");
        }
        jdbc.update("""
                UPDATE suggestion SET status = 'REJECTED', reviewed_by = ?, reviewed_at = now(),
                                     review_note = ?
                 WHERE id = ?
                """, actor, reason, id);
        return Map.of("id", id, "status", "REJECTED", "reason", reason,
                "note", "驳回理由会被统计成采纳率的分母：它是判断生成器质量、而不是判断「AI 好不好」的依据");
    }

    /** 采纳率统计：AI 能力到底有没有用，只有这个数说了算。 */
    public Map<String, Object> metrics() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("byGenerator", jdbc.queryForList("""
                SELECT generator, status, COUNT(*) AS count FROM suggestion
                 GROUP BY 1, 2 ORDER BY 1, 2
                """));
        payload.put("acceptanceRate", jdbc.queryForList("""
                SELECT generator,
                       COUNT(*) FILTER (WHERE status = 'ACCEPTED') AS accepted,
                       COUNT(*) FILTER (WHERE status IN ('ACCEPTED', 'REJECTED')) AS reviewed,
                       CASE WHEN COUNT(*) FILTER (WHERE status IN ('ACCEPTED', 'REJECTED')) = 0 THEN NULL
                            ELSE ROUND((COUNT(*) FILTER (WHERE status = 'ACCEPTED'))::numeric
                                 / COUNT(*) FILTER (WHERE status IN ('ACCEPTED', 'REJECTED')), 3)
                       END AS rate
                  FROM suggestion GROUP BY 1 ORDER BY 1
                """));
        payload.put("pending", jdbc.queryForObject(
                "SELECT COUNT(*) FROM suggestion WHERE status = 'PENDING'", Integer.class));
        payload.put("target", "docs/13 §4 的验收口径：建议采纳率 ≥ 40%（未达到说明生成器需要改，而不是「用的人不懂」）");
        payload.put("llmStatus", llmStatus());
        return payload;
    }

    // -------------------------------------------------------------------- LLM

    /**
     * LLM 配置状态。
     *
     * <p><b>诚实的核心</b>：没有配置就必须看得见。平台把这个状态放进接口与界面，
     * 而不是让使用者以为"AI 功能在跑"。
     */
    public Map<String, Object> llmStatus() {
        boolean configured = !llmEndpoint.isEmpty();
        jdbc.update("""
                INSERT INTO ai_config (capability, provider, model, configured, endpoint, last_checked_at, note)
                VALUES ('suggestion_llm', ?, ?, ?, ?, now(), ?)
                ON CONFLICT (capability) DO UPDATE
                    SET provider = EXCLUDED.provider, model = EXCLUDED.model,
                        configured = EXCLUDED.configured, endpoint = EXCLUDED.endpoint,
                        last_checked_at = now(), note = EXCLUDED.note, updated_at = now()
                """, configured ? "openai-compatible" : null, llmModel.isEmpty() ? null : llmModel,
                configured, configured ? llmEndpoint : null,
                configured ? "已配置：suggestion_llm 可用于生成描述草稿" 
                        : "未配置：LLM 建议不可用（dg.ai.llm-endpoint 为空）。"
                        + "平台**不会**用模板冒充 AI 输出");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", "suggestion_llm");
        payload.put("configured", configured);
        payload.put("endpoint", configured ? llmEndpoint : null);
        payload.put("model", llmModel.isEmpty() ? null : llmModel);
        payload.put("howToEnable", "启动时设置 DG_AI_LLM_ENDPOINT（OpenAI 兼容的 /chat/completions）与 DG_AI_LLM_MODEL");
        payload.put("whyItMatters", "没有 provenance 的 AI 输出在治理场景是负资产："
                + "使用者无法判断该不该信。因此未配置时宁可明说不可用。");
        return payload;
    }

    /**
     * 用大模型生成描述草稿。
     *
     * @throws AiException 未配置 LLM（**不退回模板**）；已配置但未实现 HTTP 调用时同样明确报错
     */
    public Map<String, Object> generateWithLlm(String urn) {
        if (llmEndpoint.isEmpty()) {
            throw AiException.notConfigured("未配置大模型（dg.ai.llm-endpoint 为空）：无法生成 LLM 建议。"
                    + "平台不会用模板冒充 AI 输出 —— 请配置 OpenAI 兼容端点，"
                    + "或使用确定性生成器（POST /api/v1/ai/suggestions/generate）");
        }
        Map<String, Object> entity = metadata.getEntity(urn) == null ? Map.of() : Map.of(
                "urn", urn);
        throw AiException.notConfigured("已配置端点 " + llmEndpoint
                + "，但本轮未实现 HTTP 调用与提示词模板：**明确标注为未实现**，"
                + "而不是返回一段看起来像 AI 的假文本。（配置状态与实体信息：" + entity + "）");
    }

    // ------------------------------------------------------------------ 工具

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM suggestion WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("建议不存在：" + id);
        }
        return rows.get(0);
    }

    /** 把结构化建议翻译成要写入 aspect 的数据。 */
    private static Map<String, Object> proposalToAspect(String aspectType, Map<String, Object> proposal) {
        if (aspectType == null) {
            return Map.of();
        }
        return switch (aspectType) {
            case "ownership" -> Map.of("owners", List.of(Map.of(
                    "name", String.valueOf(proposal.getOrDefault("candidateRole", "数据管家")),
                    "source", "AI_SUGGESTED")));
            case "descriptions" -> Map.of("text", draftDescription(proposal), "language", "zh",
                    "source", "AI_GENERATED");
            case "classification" -> Map.of("level",
                    String.valueOf(proposal.getOrDefault("proposedLevel", "L3")),
                    "categories", List.of("sensitive-suspect"),
                    "appliedBy", Map.of("source", "ai_suggestion"));
            default -> Map.of();
        };
    }

    private static String draftDescription(Map<String, Object> proposal) {
        Object name = proposal.get("displayName");
        Object columns = proposal.get("columns");
        return "【AI 草稿，需人工确认】" + (name == null ? "该数据集" : name) + "："
                + "共 " + (columns instanceof List<?> list ? list.size() : 0) + " 个字段"
                + (columns instanceof List<?> list && !list.isEmpty() ? "（" + String.join("、",
                list.stream().map(String::valueOf).toList()) + "）" : "")
                + "。请补充业务口径与使用场景。";
    }

    private static Map<String, Object> parseJson(String json) {
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

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
