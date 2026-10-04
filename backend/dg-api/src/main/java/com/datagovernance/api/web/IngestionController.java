package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.ingestion.CollectionRun;
import com.datagovernance.ingestion.CollectionService;
import com.datagovernance.ingestion.ConnectorRegistry;
import com.datagovernance.ingestion.GuardConfig;
import com.datagovernance.ingestion.Source;
import com.datagovernance.ingestion.connectors.PostgresSource;
import com.datagovernance.core.MetadataService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 采集触发、运行历史与健康度。 */
@RestController
@RequestMapping("/api/v1/collect")
public class IngestionController {

    private final CollectionService collection;
    private final ConnectorRegistry connectors;
    private final MetadataService metadata;

    public IngestionController(CollectionService collection, ConnectorRegistry connectors,
                               MetadataService metadata) {
        this.collection = collection;
        this.connectors = connectors;
        this.metadata = metadata;
    }

    /**
     * 通用采集入口：连接器从 {@link ConnectorRegistry} 取，采集编排完全复用。
     *
     * <p>新增一个源系统不需要动这里 —— 只在注册表里加一行。
     */
    @PostMapping("/run")
    public Map<String, Object> collect(@RequestBody CollectRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "collect:run");

        Source source = connectors.create(new ConnectorRegistry.ConnectorRequest(
                request.source(), request.dsn(), request.namespace(),
                request.databases(), request.schemas(), request.tables(), request.sampleSize()));

        GuardConfig guard = guardOf(request);
        CollectionRun run = collection.collect(
                source,
                request.namespace() == null || request.namespace().isBlank() ? "prod" : request.namespace(),
                guard,
                !Boolean.FALSE.equals(request.guardEnabled()),
                Boolean.TRUE.equals(request.acceptDeletions()),
                Boolean.TRUE.equals(request.reconcileOrphans()));
        return run.asMap();
    }

    /** 兼容入口：PostgreSQL（保留原有调用方式）。 */
    @PostMapping("/postgres")
    public Map<String, Object> collectPostgres(@RequestBody CollectRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "collect:run");

        Source source = new PostgresSource(
                request.jdbcUrl(), request.username(), request.password(),
                request.schemas(), request.tables());

        CollectionRun run = collection.collect(
                source,
                request.namespace() == null ? "prod" : request.namespace(),
                guardOf(request),
                !Boolean.FALSE.equals(request.guardEnabled()),
                Boolean.TRUE.equals(request.acceptDeletions()));
        return run.asMap();
    }

    private static GuardConfig guardOf(CollectRequest request) {
        return new GuardConfig(
                request.maxDeletions() == null ? 200 : request.maxDeletions(),
                request.maxDeleteRatio() == null ? 0.30 : request.maxDeleteRatio(),
                request.minRetentionRatio() == null ? 0.70 : request.minRetentionRatio(),
                10);
    }

    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiExceptionHandler.list("runs", collection.recentRuns(limit));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return collection.health();
    }

    /** 按采集批次回滚（ADR-005）。 */
    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestParam String runId) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "collect:run");
        return metadata.rollbackRun(runId);
    }

    /**
     * 采集调度已实现，见 {@link ScheduleController}（{@code /api/v1/schedules}）。
     *
     * <p>此处原有的 501 占位已删除：占位被真实实现取代后必须删掉，
     * 否则同一语义存在两套路径（一处真、一处假）。
     */

    /** 未实现项：告警（Python 参考实现已有采集类四条规则）。 */
    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        throw new UnsupportedOperationException(
                "告警尚未在 Java 控制面实现。设计见 docs/09 §9.1：分级/去重/冷却/恢复 + 多通道 webhook；"
                        + "alert_event / alert_channel 表已就绪（sql/005_alerting.sql），"
                        + "Python 参考实现可作为行为基准（docs/21 §10.7）。"
                        + "当前可用替代：GET /api/v1/collect/health 已暴露连续失败与陈旧度。");
    }

    /**
     * 连接器清单（供界面选择数据源）。
     *
     * <p>状态来自 {@link ConnectorRegistry} 这一处：界面不维护自己的"支持哪些源"清单，
     * 也不会出现"界面上有、实际不支持"。{@code verifiedAgainstRealSystem} 明确区分
     * 「实现了」与「对真实系统验证过」—— 这两件事在连接器上差别很大。
     */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("implemented", connectors.implemented());
        payload.put("notImplemented", connectors.notImplemented());
        payload.put("note", "verifiedAgainstRealSystem=true 表示该连接器已对真实系统跑通端到端采集（见 tools/java_e2e_verify.py）");
        return payload;
    }

    public record CollectRequest(
            String source,
            String dsn,
            String jdbcUrl,
            String username,
            String password,
            String namespace,
            List<String> databases,
            List<String> schemas,
            List<String> tables,
            Integer sampleSize,
            Integer maxDeletions,
            Double maxDeleteRatio,
            Double minRetentionRatio,
            Boolean guardEnabled,
            Boolean acceptDeletions,
            /** 清理孤儿实体（数据库里有、本轮没采到）。默认关闭：自动删除没见过的实体太危险。 */
            Boolean reconcileOrphans) {
    }
}
