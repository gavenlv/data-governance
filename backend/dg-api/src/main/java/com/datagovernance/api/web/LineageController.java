package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.core.MetadataService;
import com.datagovernance.lineage.LineageService;
import com.datagovernance.lineage.SqlParseSidecarClient;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 血缘：OpenLineage 接收、图查询、质量报告、SQL 解析（侧车）。 */
@RestController
@RequestMapping("/api/v1/lineage")
public class LineageController {

    private final MetadataService metadata;
    private final LineageService lineage;
    private final SqlParseSidecarClient sidecar;
    private final com.datagovernance.lineage.ImpactService impactService;
    private final com.datagovernance.lineage.SqlParseService parseService;
    private final com.datagovernance.lineage.LineageSubgraphService subgraphService;
    private final com.datagovernance.lineage.LineageCheckService checkService;

    public LineageController(MetadataService metadata, LineageService lineage,
                             SqlParseSidecarClient sidecar,
                             com.datagovernance.lineage.ImpactService impactService,
                             com.datagovernance.lineage.SqlParseService parseService,
                             com.datagovernance.lineage.LineageSubgraphService subgraphService,
                             com.datagovernance.lineage.LineageCheckService checkService) {
        this.metadata = metadata;
        this.lineage = lineage;
        this.sidecar = sidecar;
        this.impactService = impactService;
        this.parseService = parseService;
        this.subgraphService = subgraphService;
        this.checkService = checkService;
    }

    /**
     * 血缘 L2 检查发现（"覆盖率为什么低"的可分答案）。
     *
     * <p>把"能补的"（缺 schema → 采集即可）与"需改 SQL 的"（列有歧义）分开，
     * 而不是让它们一起消失在解析警告里。
     */
    @GetMapping("/checks")
    public Map<String, Object> checks(@RequestParam(required = false) String checkType,
                                      @RequestParam(required = false) String targetUrn,
                                      @RequestParam(defaultValue = "30") Integer days,
                                      @RequestParam(defaultValue = "200") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "lineage:read");
        List<Map<String, Object>> rows = checkService.findings(checkType, targetUrn, days, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("findings", rows);
        payload.put("summary", checkService.summary(days));
        return payload;
    }

    /** L2 检查汇总（按类型分布 + 可操作清单）。 */
    @GetMapping("/checks/summary")
    public Map<String, Object> checksSummary(@RequestParam(defaultValue = "30") Integer days) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "lineage:read");
        return checkService.summary(days);
    }

    /** OpenLineage 兼容端点（作业运行时上报）。 */
    @PostMapping("/openlineage")
    public Map<String, Object> openlineage(@RequestBody Map<String, Object> event,
                                           @RequestParam(defaultValue = "prod") String namespace) {
        return lineage.ingestOpenLineage(event, namespace);
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(
            @RequestParam String urn,
            @RequestParam(defaultValue = "downstream") String direction,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "0.0") double minConfidence) {
        MetadataService.LineageResult result = metadata.lineage(urn, direction, depth, minConfidence);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", result.urn());
        payload.put("direction", result.direction());
        payload.put("maxDepth", result.maxDepth());
        payload.put("nodes", result.sorted());
        return payload;
    }

    /** SQL 解析能力与覆盖率相关的质量报告（包含 L2 检查的可操作清单）。 */
    @GetMapping("/quality")
    public Map<String, Object> quality() {
        Map<String, Object> payload = new LinkedHashMap<>(lineage.quality());
        payload.put("sqlglotSidecarReady", sidecar.available());
        payload.put("sqlglotSidecarUrl", sidecar.baseUrl());
        return payload;
    }

    /**
     * 用 SQL 静态解析补全血缘（需 Python sqlglot 侧车）。
     *
     * <p>侧车不可用时返回 <b>502 + 启动方式</b>，不返回空血缘 —— 使用者必须能区分
     * 「真的没有血缘」与「解析没跑」（docs/09 §9.2）。
     *
     * <p>{@code dryRun=true} 只解析不写库：用于先看方言解析效果再决定是否入库。
     */
    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody ParseRequest request) {
        return parseService.ingest(request.sql(), request.dialect(), request.namespace(),
                actorOf(), !Boolean.FALSE.equals(request.recordSamples()),
                Boolean.TRUE.equals(request.dryRun()));
    }

    /** 侧车健康（sqlglot 版本、支持方言数）—— 让"解析能力"可观测。 */
    @GetMapping("/parse/sidecar")
    public Map<String, Object> sidecarHealth() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configured", !sidecar.baseUrl().isEmpty());
        payload.put("url", sidecar.baseUrl());
        payload.put("available", sidecar.available());
        if (sidecar.available()) {
            payload.put("health", sidecar.health());
        } else {
            payload.put("startCommand", "python -m dg.cli sidecar");
            payload.put("note", "侧车不可用：解析接口会返回 502 并标注原因，不会静默返回空血缘");
        }
        return payload;
    }

    private static String actorOf() {
        var subject = com.datagovernance.api.security.Subjects.current();
        return subject == null ? "lineage" : subject.id();
    }

    /**
     * 血缘子图（docs/14 §3.3 血缘探索器）。
     *
     * <p>与 {@code /graph} 的区别：这里返回**带有属性**的节点与边（来源/置信度/转换/解析级别/时效），
     * 界面才能做到"线型 = 可信度"。裁剪在服务端做，且裁剪是显式的
     * （响应里的 {@code truncated} / {@code boundaryNodes} / {@code nodeLimitReached}）。
     */
    @GetMapping("/subgraph")
    public Map<String, Object> subgraph(
            @RequestParam String urn,
            @RequestParam(defaultValue = "downstream") String direction,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "0.0") double minConfidence,
            @RequestParam(defaultValue = "false") boolean includeColumns,
            @RequestParam(defaultValue = "false") boolean includeControl,
            @RequestParam(required = false) Integer nodeLimit) {
        return subgraphService.subgraph(urn, direction, depth, minConfidence, includeColumns,
                includeControl, nodeLimit);
    }

    /**
     * 确认一条血缘边（docs/14 §3.3「确认此血缘」）。
     *
     * <p>确认是人工背书：置信度提升到人工级并记录确认人 —— 它是置信度模型的输入。
     */
    @PostMapping("/edges/{edgeId}/confirm")
    public Map<String, Object> confirmEdge(@PathVariable long edgeId) {
        return metadata.confirmEdge(edgeId, actorOf());
    }

    /** 驳回一条血缘边（标记而不是删除：保留原因才能解释它为什么消失）。 */
    @PostMapping("/edges/{edgeId}/reject")
    public Map<String, Object> rejectEdge(@PathVariable long edgeId,
                                          @RequestBody(required = false) RejectRequest request) {
        return metadata.rejectEdge(edgeId, request == null ? null : request.reason(), actorOf());
    }

    /**
     * 批量退役边（运维工具）：连接器/解析器**边语义变化**后清理遗留边。
     *
     * <p>必须显式给出 edgeType（避免误伤），并回报影响条数。
     */
    @PostMapping("/edges/retire")
    public Map<String, Object> retireEdges(@RequestParam String edgeType,
                                           @RequestParam(required = false) String source,
                                           @RequestParam String reason) {
        return metadata.retireEdges(edgeType, source, reason, actorOf());
    }

    public record RejectRequest(String reason) {
    }

    /**
     * 影响分析 / 爆炸半径（docs/09 §9.2）。
     *
     * <p>返回按关键性排序的受影响资产清单，而不是一串 URN —— 变更评审要的是"先看谁"。
     */
    @GetMapping("/impact/{urn}")
    public Map<String, Object> impact(
            @PathVariable String urn,
            @RequestParam(defaultValue = "downstream") String direction,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "0.0") double minConfidence,
            @RequestParam(defaultValue = "false") boolean includeColumns) {
        return impactService.analyze(urn, direction, depth, minConfidence, includeColumns);
    }

    public record ParseRequest(String sql, String dialect, String namespace,
                               Boolean recordSamples, Boolean dryRun) {
    }
}
