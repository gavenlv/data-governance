package com.datagovernance.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.search.SearchService;
import com.datagovernance.lineage.ImpactService;
import com.datagovernance.lineage.LineageSubgraphService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import com.datagovernance.quality.RuleExecutionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * MCP / Agent 工具集（docs/13 §5）。
 *
 * <p>为什么值得做：外部 Agent（IDE、BI 助手、运维机器人）需要**安全地**访问治理能力。
 * 如果没有这一层，人们会直接给 Agent 数据库凭据 —— 那是治理的反面。
 * 因此本类提供 MCP 风格的 JSON-RPC 接口，并做两件关键的事：
 * <ol>
 *   <li><b>权限裁剪</b>：每个工具声明所需权限点，调用时按调用者角色判定（复用 AccessPolicy 唯一入口）；</li>
 *   <li><b>审计</b>：每次工具调用都写 access_event，可回答"Agent 到底做了什么"。</li>
 * </ol>
 *
 * <p>实现方式是 MCP 的**核心子集**（JSON-RPC 2.0 的 {@code initialize} / {@code tools/list} /
 * {@code tools/call}），通过 HTTP 暴露；stdio 传输与资源/提示（resources/prompts）未实现。
 */
@Service
public class McpService {

    private final JdbcTemplate jdbc;
    private final SearchService search;
    private final ImpactService impact;
    private final LineageSubgraphService subgraph;
    private final RuleExecutionService rules;
    private final SuggestionService suggestions;
    private final HybridSearchService hybrid;

    public McpService(JdbcTemplate jdbc, SearchService search, ImpactService impact,
                      LineageSubgraphService subgraph, RuleExecutionService rules,
                      SuggestionService suggestions, HybridSearchService hybrid) {
        this.jdbc = jdbc;
        this.search = search;
        this.impact = impact;
        this.subgraph = subgraph;
        this.rules = rules;
        this.suggestions = suggestions;
        this.hybrid = hybrid;
    }

    /** 工具定义：名称、说明、所需权限、输入 schema。 */
    private record Tool(String name, String description, String requiredPermission,
                        Map<String, Object> inputSchema) {
    }

    private List<Tool> tools() {
        return List.of(
                new Tool("search_assets", "检索资产（词法 + 术语同义词融合）", "asset:read",
                        schema(Map.of("query", "string", "type", "string?"), List.of("query"))),
                new Tool("get_asset", "读取资产详情（含 aspects）", "asset:read",
                        schema(Map.of("urn", "string"), List.of("urn"))),
                new Tool("lineage", "查询血缘子图（上游/下游）", "lineage:read",
                        schema(Map.of("urn", "string", "direction", "upstream|downstream",
                                "depth", "number?"), List.of("urn"))),
                new Tool("impact_analysis", "影响分析：改这个资产会影响谁（按关键性排序）", "lineage:read",
                        schema(Map.of("urn", "string", "depth", "number?"), List.of("urn"))),
                new Tool("list_quality_rules", "列出质量规则与最近状态", "asset:read",
                        schema(Map.of(), List.of())),
                new Tool("quality_overview", "质量概览：规则状态分布与最近失败", "asset:read",
                        schema(Map.of(), List.of())),
                new Tool("list_contracts", "列出数据契约与其违约数", "asset:read",
                        schema(Map.of(), List.of())),
                new Tool("policy_coverage", "策略覆盖率与已知盲区", "asset:read",
                        schema(Map.of(), List.of())),
                new Tool("list_suggestions", "列出待审 AI 建议（带依据与置信度）", "asset:read",
                        schema(Map.of("status", "string?"), List.of())),
                new Tool("access_events", "查询访问事件（审计）", "asset:read",
                        schema(Map.of("limit", "number?"), List.of())),
                // Agent 唯一的写路径：提建议。**不存在直写 aspect 的工具** ——
                // 让外部 Agent 直接改元数据，等于把治理纪律交给一个不受控的进程
                new Tool("propose_aspect", "提交一条元数据建议（走人工审批，不直接改写元数据）",
                        "governance:write",
                        schema(Map.of("urn", "string", "aspectType", "string",
                                "field", "string", "value", "string", "rationale", "string"),
                                List.of("urn", "aspectType", "field", "value", "rationale"))));
    }

    /** 按调用者权限裁剪后的工具清单（没有权限的工具对调用者不可见，而不是"看得见但调用被拒"）。 */
    private List<Tool> visibleTools(Subject subject) {
        return tools().stream().filter(tool -> AccessPolicy.can(subject, tool.requiredPermission()))
                .toList();
    }

    private static Map<String, Object> schema(Map<String, String> properties, List<String> required) {
        Map<String, Object> props = new LinkedHashMap<>();
        properties.forEach((name, type) -> props.put(name, Map.of("type", type.replace("?", ""),
                "description", type.endsWith("?") ? "可选" : "必填")));
        return Map.of("type", "object", "properties", props, "required", required);
    }

    /** MCP JSON-RPC 入口。 */
    public Map<String, Object> handle(Map<String, Object> request, Subject subject) {
        Object id = request.get("id");
        String method = String.valueOf(request.getOrDefault("method", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        return switch (method) {
            case "initialize" -> result(id, Map.of(
                    "protocolVersion", "2024-11-05",
                    "serverInfo", Map.of("name", "data-governance-platform", "version", "0.1.0"),
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "instructions", "这是数据治理平台的工具集。工具调用按调用者权限裁剪并记入审计。"
                            + "**未实现**：resources / prompts / stdio 传输。"));
            case "tools/list" -> result(id, Map.of("tools", visibleTools(subject).stream().map(tool -> Map.of(
                    "name", tool.name(),
                    "description", tool.description() + "（所需权限：" + tool.requiredPermission() + "）",
                    "inputSchema", tool.inputSchema())).toList()));
            case "tools/call" -> callTool(id, params, subject);
            case "ping" -> result(id, Map.of());
            default -> error(id, -32601, "未实现的方法：" + method
                    + "（本平台实现 MCP 核心子集：initialize / tools/list / tools/call / ping）");
        };
    }

    private Map<String, Object> callTool(Object id, Map<String, Object> params, Subject subject) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        Tool tool = tools().stream().filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new AiException("未知工具：" + name));
        // 权限裁剪：Agent 也走同一套判定，不给"内部调用"开后门
        if (!AccessPolicy.can(subject, tool.requiredPermission())) {
            record(subject, "MCP_TOOL_DENIED", name, "DENY",
                    "缺少权限 " + tool.requiredPermission());
            return result(id, Map.of("isError", true,
                    "content", List.of(Map.of("type", "text",
                            "text", "无权调用该工具：缺少权限 " + tool.requiredPermission()
                                    + "（当前角色 " + subject.roles() + "）"))));
        }
        try {
            Map<String, Object> output = execute(name, arguments, subject);
            record(subject, "MCP_TOOL_CALLED", name, "ALLOW", null);
            return result(id, Map.of("content", List.of(Map.of("type", "text", "text", toJson(output))),
                    "structuredContent", output));
        } catch (RuntimeException e) {
            record(subject, "MCP_TOOL_FAILED", name, "DENY", e.getMessage());
            return result(id, Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "工具执行失败：" + e.getMessage()))));
        }
    }

    private Map<String, Object> execute(String name, Map<String, Object> arguments, Subject subject) {
        return switch (name) {
            case "search_assets" -> hybrid.hybrid(str(arguments.get("query")),
                    str(arguments.get("type")), 10, AccessPolicy.visibleLevels(subject));
            case "get_asset" -> {
                String urn = require(arguments, "urn");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("urn", urn);
                payload.put("aspects", jdbc.queryForList(
                        "SELECT aspect_type, version, data FROM aspect WHERE urn = ?", urn));
                yield payload;
            }
            case "lineage" -> subgraph.subgraph(require(arguments, "urn"),
                    str(arguments.getOrDefault("direction", "downstream")),
                    intOf(arguments.get("depth"), 3), 0.0, false, false, 200);
            case "impact_analysis" -> impact.analyze(require(arguments, "urn"), "downstream",
                    intOf(arguments.get("depth"), 3), 0.0, false);
            case "list_quality_rules" -> Map.of("rules", rules.listRules());
            case "quality_overview" -> rules.overview();
            case "list_contracts" -> Map.of("contracts", jdbc.queryForList("""
                    SELECT e.urn, e.display_name, a.data->>'contractVersion' AS version,
                           (SELECT COUNT(*) FROM contract_violation v
                             WHERE v.contract_urn = e.urn AND v.status = 'OPEN') AS open_violations
                      FROM entity e LEFT JOIN aspect a ON a.urn = e.urn AND a.aspect_type = 'contractSpec'
                     WHERE e.entity_type = 'DataContract' AND e.deleted_at IS NULL LIMIT 50
                    """));
            case "policy_coverage" -> Map.of("history", jdbc.queryForList("""
                    SELECT measured_at, total_assets, covered_assets, uncovered_high_class
                      FROM policy_coverage ORDER BY measured_at DESC LIMIT 5
                    """));
            case "list_suggestions" -> Map.of("suggestions",
                    suggestions.inbox(str(arguments.getOrDefault("status", "PENDING")), 0.0, 50));
            case "access_events" -> Map.of("events", jdbc.queryForList("""
                    SELECT subject, action, resource_urn, decision, occurred_at
                      FROM access_event ORDER BY occurred_at DESC LIMIT ?
                    """, Math.min(Math.max(intOf(arguments.get("limit"), 50), 1), 200)));
            case "propose_aspect" -> suggestions.propose(
                    require(arguments, "urn"), require(arguments, "aspectType"),
                    str(arguments.get("field")), require(arguments, "value"),
                    require(arguments, "rationale"), "mcp:" + subject.id());
            default -> throw new AiException("未实现的工具：" + name);
        };
    }

    private void record(Subject subject, String action, String tool, String decision, String reason) {
        jdbc.update("""
                INSERT INTO access_event (subject, action, resource_urn, decision, reason, detail, source)
                VALUES (?, ?, NULL, ?, ?, CAST(? AS jsonb), 'mcp')
                """, subject.id(), action, decision, reason, toJson(Map.of("tool", tool)));
    }

    /**
     * 工具清单（供界面展示"Agent 能用什么"）。
     *
     * <p>按 {@code subject} 裁剪：没有权限的工具**不出现在清单里**。
     * 只展示调用者真能用的工具，比"列出来再拒绝"更省事，也避免把权限模型泄露给无权者。
     */
    public Map<String, Object> describe(Subject subject) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Tool tool : visibleTools(subject)) {
            items.add(Map.of("name", tool.name(), "description", tool.description(),
                    "requiredPermission", tool.requiredPermission(),
                    "inputSchema", tool.inputSchema()));
        }
        int hidden = tools().size() - items.size();
        return Map.of("protocolVersion", "2024-11-05",
                "transport", "HTTP POST /api/v1/ai/mcp（JSON-RPC 2.0）",
                "tools", items,
                "hiddenByPermission", hidden,
                "permissionModel", "工具清单与每次 tools/call 都按调用者角色判定所需权限点；"
                        + "拒绝与失败都写入 access_event(source=mcp)；写操作**只有提建议**（propose_aspect），"
                        + "不存在直写元数据的工具",
                "notImplemented", List.of("stdio 传输", "resources / prompts（MCP 的资源与提示）",
                        "工具结果流式返回", "sampling / notifications"));
    }

    private static Map<String, Object> result(Object id, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("result", result);
        return payload;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("error", Map.of("code", code, "message", message));
        return payload;
    }

    private static String require(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new AiException("缺少参数 " + key);
        }
        return String.valueOf(value);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
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
