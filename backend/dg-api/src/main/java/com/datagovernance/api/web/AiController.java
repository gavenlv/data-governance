package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.ai.HybridSearchService;
import com.datagovernance.ai.McpService;
import com.datagovernance.ai.SemanticLayerService;
import com.datagovernance.ai.SuggestionService;
import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 原生能力：建议收件箱、混合检索、MCP 工具集、语义层指标（docs/13）。
 *
 * <p>两条贯穿全批的纪律在接口层就体现出来：
 * <ol>
 *   <li><b>AI 建议不自动落库</b>：采纳必须由人触发（{@code POST /suggestions/{id}/accept}）；</li>
 *   <li><b>未配置的能力必须可见</b>：{@code GET /ai/status} 会说明哪些 AI 能力真的可用。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final SuggestionService suggestions;
    private final HybridSearchService hybrid;
    private final McpService mcp;
    private final SemanticLayerService semanticLayer;

    public AiController(SuggestionService suggestions, HybridSearchService hybrid, McpService mcp,
                        SemanticLayerService semanticLayer) {
        this.suggestions = suggestions;
        this.hybrid = hybrid;
        this.mcp = mcp;
        this.semanticLayer = semanticLayer;
    }

    /** AI 能力状态（**诚实的出口**：哪些真能用、哪些没配置）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suggestionDeterministic", Map.of(
                "available", true,
                "note", "规则型建议生成器立即可用（无主资产 / 缺描述 / 敏感列未分级）"));
        payload.put("suggestionLlm", suggestions.llmStatus());
        payload.put("semanticSearch", Map.of(
                "available", true,
                "lexical", true,
                "glossaryExpansion", true,
                "vector", false,
                "note", "**向量检索未实现**：需要 embedding 服务（未配置），不假装有语义检索"));
        payload.put("mcp", Map.of("available", true, "note", "MCP 核心子集（initialize/tools/list/tools/call）"));
        payload.put("semanticLayer", Map.of("available", true, "note", "dbt / Cube 语义层指标接入"));
        return payload;
    }

    // ------------------------------------------------------------------ 建议

    @PostMapping("/suggestions/generate")
    public Map<String, Object> generate(@RequestParam(required = false) String namespace,
                                        @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return suggestions.generate(namespace, limit);
    }

    @GetMapping("/suggestions")
    public Map<String, Object> inbox(@RequestParam(defaultValue = "PENDING") String status,
                                     @RequestParam(defaultValue = "0") double minConfidence,
                                     @RequestParam(defaultValue = "100") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = suggestions.inbox(status, minConfidence, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("suggestions", rows);
        return payload;
    }

    /**
     * 采纳建议（可由人编辑后再采纳）。
     *
     * <p>{@code value} 非空时按人工编辑内容写入；否则用建议的原始内容。
     * 写入来源固定为 {@code AI_GENERATED}，因此不会被采集覆盖，但人工仍可再改（ADR-005）。
     */
    @PostMapping("/suggestions/{id}/accept")
    public Map<String, Object> accept(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> value = body == null ? Map.of() : body;
        return suggestions.accept(id, subject.id(), value);
    }

    @PostMapping("/suggestions/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        // 必须用 null 安全的 str()：String.valueOf(null) 会变成字符串 "null"，
        // 于是"没写理由"会被当成写了理由（驳回理由就成了摆设）
        return suggestions.reject(id, subject.id(), str(body == null ? null : body.get("reason")));
    }

    /** 采纳率统计（AI 有没有用，只有这个数说了算）。 */
    @GetMapping("/suggestions/metrics")
    public Map<String, Object> metrics() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return suggestions.metrics();
    }

    /** 用大模型生成建议 —— 未配置时**明确报错**，不退回模板。 */
    @PostMapping("/suggestions/llm")
    public Map<String, Object> generateWithLlm(@RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return suggestions.generateWithLlm(body == null ? null : String.valueOf(body.get("urn")));
    }

    // -------------------------------------------------------------- 混合检索

    @GetMapping("/search")
    public Map<String, Object> hybridSearch(@RequestParam(defaultValue = "") String q,
                                            @RequestParam(name = "type", required = false) String entityType,
                                            @RequestParam(defaultValue = "20") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return hybrid.hybrid(q, entityType, limit, AccessPolicy.visibleLevels(subject));
    }

    @GetMapping("/search/signal")
    public Map<String, Object> signal() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return hybrid.signal();
    }

    // -------------------------------------------------------------------- MCP

    /** MCP 工具清单（供界面展示"Agent 能用什么、需要什么权限"）—— 按调用者权限裁剪。 */
    @GetMapping("/mcp/tools")
    public Map<String, Object> mcpTools() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return mcp.describe(subject);
    }

    /**
     * MCP JSON-RPC 入口。
     *
     * <p>权限按调用者角色裁剪（Agent 不走后门），每次调用写入 {@code access_event(source=mcp)}。
     */
    @PostMapping("/mcp")
    public Map<String, Object> mcp(@RequestBody Map<String, Object> request) {
        Subject subject = Subjects.require();
        return mcp.handle(request, subject);
    }

    // ---------------------------------------------------------------- 语义层

    /** 接入语义层定义（dbt semantic models / Cube cubes / 平台格式）。 */
    @PostMapping("/semantic-layer/ingest")
    public Map<String, Object> ingestSemanticLayer(@RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        // 注意：这里**不能**用 String.valueOf(input.get("sourceFormat"))：
        // 键缺失时它返回字符串 "null"，会被当成一种合法来源格式传下去（早期版本正是这么错的）
        return semanticLayer.ingest(str(input.get("yaml")),
                str(input.get("namespace")) == null ? "prod" : str(input.get("namespace")),
                str(input.get("sourceFormat")),
                subject.id());
    }

    @GetMapping("/semantic-layer/metrics")
    public Map<String, Object> metrics2() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = semanticLayer.listMetrics();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("metrics", rows);
        return payload;
    }

    /** 指标口径追溯：指标 → 依赖列 → 上游数据集。 */
    @GetMapping("/semantic-layer/metrics/{name}")
    public Map<String, Object> metric(@PathVariable String name) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return semanticLayer.metric(name);
    }

    /** null 安全的取字符串：键缺失时返回 null，而不是字符串 "null"。 */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
