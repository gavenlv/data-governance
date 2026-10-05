package com.datagovernance.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集编排：Source → Normalizer → Sink → 护栏 → 状态快照 → 运行记录（docs/09 §9.1）。
 *
 * <p>三层保障：
 * <ul>
 *   <li><b>护栏</b>：实体数骤降/删除比例异常 → BLOCKED，本轮不删且不更新基线；</li>
 *   <li><b>状态快照</b> {@code collector_state}：按 (source, namespace, scope) 隔离，
 *       天然实现「命名空间隔离防误删」；</li>
 *   <li><b>运行记录</b> {@code collect_run}：每次采集留痕，支撑健康度与告警。</li>
 * </ul>
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private static final String COLLECT_SOURCE = "AUTO_COLLECTED";
    /** 仪表板的快照 scope：与数据集分开，护栏互不牵连。 */
    private static final String DASHBOARD_SCOPE = "dashboards";

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;

    public CollectionService(JdbcTemplate jdbc, MetadataService metadata) {
        this.jdbc = jdbc;
        this.metadata = metadata;
    }

    public CollectionRun collect(Source source, String namespace) {
        return collect(source, namespace, GuardConfig.defaults(), true, false);
    }

    @Transactional
    public CollectionRun collect(
            Source source,
            String namespace,
            GuardConfig guardConfig,
            boolean guardEnabled,
            boolean acceptDeletions) {
        return collect(source, namespace, guardConfig, guardEnabled, acceptDeletions, false);
    }

    /**
     * @param reconcileOrphans 是否清理"孤儿实体"：数据库中属于本源、但本轮采集不再出现的实体
     *                         （例如连接器升级后 URN 规则变化留下的旧实体）。
     *                         默认关闭：**自动删除没见过的实体太危险**，应由使用者显式要求，
     *                         且仍受护栏约束（超出阈值就拦截）。
     */
    @Transactional
    public CollectionRun collect(
            Source source,
            String namespace,
            GuardConfig guardConfig,
            boolean guardEnabled,
            boolean acceptDeletions,
            boolean reconcileOrphans) {

        Instant startedAt = Instant.now();
        String runId = newRunId(source.name());
        String scope = "*";
        String platformUrn = UrnUtils.platform(namespace, source.platform());

        Set<String> previous = loadSnapshot(source.name(), namespace, scope);
        insertRunRecord(runId, source.name(), namespace, scope, startedAt);

        Set<String> current = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int[] counters = new int[5]; // seen, created, written, unchanged, descriptions
        int[] columns = new int[1];
        String status = CollectionRun.SUCCEEDED;
        String blockReason = null;
        GuardConfig.GuardDecision decision = null;
        int deleted = 0;
        int deletedCandidates = 0;
        int[] dashboardsSunk = new int[3]; // 已处理、新建、删除
        boolean[] dashboardGuard = new boolean[1];
        String[] dashboardGuardReason = new String[1];
        /** 连接器自带血缘写入的边数；skipNotes 记录"为什么有些边没有写"。 */
        int edgesWritten = 0;
        List<String> skipNotes = new ArrayList<>();

        try {
            metadata.ensureEntity(platformUrn, "Platform", source.name(), runId);

            List<RawModels.RawDataset> datasets;
            try (Stream<RawModels.RawDataset> stream = source.extract()) {
                datasets = stream.toList();
            }

            for (RawModels.RawDataset raw : datasets) {
                try {
                    current.add(sink(raw, namespace, platformUrn, runId, counters, columns));
                } catch (RuntimeException e) {
                    errors.add("%s.%s: %s".formatted(raw.schema(), raw.table(), e.getMessage()));
                }
                counters[0]++;
            }

            // BI 资产（仪表板）单独处理：它们不参与数据集护栏与快照 ——
            // "仪表板数量骤降"与"表被删了"是完全不同的语义，混在一起会让护栏产生误报
            if (source.supportsDashboards()) {
                Set<String> previousDashboards = loadSnapshot(source.name(), namespace, DASHBOARD_SCOPE);
                Set<String> currentDashboards = new LinkedHashSet<>();
                List<RawModels.RawDashboard> dashboards;
                try (Stream<RawModels.RawDashboard> stream = source.extractDashboards()) {
                    dashboards = stream.toList();
                }
                for (RawModels.RawDashboard raw : dashboards) {
                    try {
                        currentDashboards.add(sinkDashboard(raw, namespace, platformUrn, runId, dashboardsSunk));
                    } catch (RuntimeException e) {
                        errors.add("dashboard %s: %s".formatted(raw.externalId(), e.getMessage()));
                    }
                }
                // 仪表板用**独立**的护栏与快照 scope：BI 资产 churn 率高，
                // 删几个报表不应该把数据采集整体拦停（反之亦然）
                GuardConfig.GuardDecision dashboardDecision = guardConfig.evaluate(previousDashboards, currentDashboards);
                if (!guardEnabled) {
                    dashboardGuardReason[0] = "护栏已关闭（--no-guard）";
                } else if (acceptDeletions && dashboardDecision.blocked()) {
                    dashboardGuardReason[0] = "人工确认接受删除（accept-deletions）";
                } else if (dashboardDecision.blocked()) {
                    dashboardGuard[0] = true;
                    dashboardGuardReason[0] = dashboardDecision.blockReason();
                }
                if (dashboardGuard[0]) {
                    // 与数据集护栏一致：被拦截时**不更新基线、不删除**
                    log.warn("仪表板护栏拦截 run={} source={}：{}", runId, source.name(), dashboardGuardReason[0]);
                } else {
                    if (!dashboardDecision.deletions().isEmpty()) {
                        dashboardsSunk[2] += metadata.markDeletedAtSource(dashboardDecision.deletions(), runId,
                                "collect:" + runId).size();
                    }
                    if (reconcileOrphans) {
                        // 清理孤儿：数据库里有、本轮没采到的（例如连接器升级后 URN 规则变了）。
                        // 只处理本源 + 本命名空间 + 同一平台前缀，避免误伤其它源的资产
                        List<String> orphans = findOrphanDashboards(source.platform(), namespace, currentDashboards);
                        if (!orphans.isEmpty()) {
                            dashboardsSunk[2] += metadata.markDeletedAtSource(orphans, runId,
                                    "reconcile-orphans:" + runId).size();
                            log.info("清理 {} 个孤儿仪表板实体（本源未再出现）：{}", orphans.size(), orphans);
                        }
                    }
                    saveSnapshot(source.name(), namespace, DASHBOARD_SCOPE, runId, currentDashboards,
                            CollectionRun.SUCCEEDED);
                }
            }

            decision = guardConfig.evaluate(previous, current);
            if (!guardEnabled) {
                decision = new GuardConfig.GuardDecision(false,
                        prepend("护栏已关闭（--no-guard）", decision.reasons()),
                        decision.deletions(), decision.previousCount(), decision.currentCount());
            } else if (acceptDeletions && decision.blocked()) {
                decision = new GuardConfig.GuardDecision(false,
                        prepend("人工确认接受删除（accept-deletions）", decision.reasons()),
                        decision.deletions(), decision.previousCount(), decision.currentCount());
            }

            deletedCandidates = decision.deletions().size();

            if (decision.blocked()) {
                status = CollectionRun.BLOCKED;
                blockReason = decision.blockReason();
                // 关键：被拦截时**不更新基线**，保证修好配置后仍能检出同样的异常
                bumpFailures(source.name(), namespace, scope, status);
            } else {
                if (!decision.deletions().isEmpty()) {
                    deleted = metadata.markDeletedAtSource(decision.deletions(), runId,
                            "collect:" + runId).size();
                }
                saveSnapshot(source.name(), namespace, scope, runId, current, status);

                // 连接器自带的血缘（dbt manifest 这类"编译期已知"的依赖）。
                // 放在护栏通过之后写：被拦截的这一轮不应该往图里加边 ——
                // 否则"目录没更新、血缘却变了"，两边对不上。
                for (RawModels.RawEdge edge : source.extractEdges().toList()) {
                    try {
                        metadata.upsertEdge(edge.fromUrn(), edge.toUrn(), edge.edgeType(), edge.source(),
                                edge.confidence(), edge.transform(), null, null, "VALUE",
                                edge.parseLevel(), edge.viaJob(), runId);
                        edgesWritten++;
                    } catch (RuntimeException e) {
                        errors.add("edge %s→%s: %s".formatted(edge.fromUrn(), edge.toUrn(), e.getMessage()));
                    }
                }
                skipNotes.addAll(source.edgeSkipNotes());
            }
        } catch (RuntimeException e) {
            status = CollectionRun.FAILED;
            errors.add("fatal: " + e.getMessage());
            bumpFailures(source.name(), namespace, scope, status);
            log.warn("采集失败 run={} source={}：{}", runId, source.name(), e.getMessage());
        }

        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        finishRunRecord(runId, status, blockReason, counters, deletedCandidates, deleted,
                columns[0], dashboardsSunk, dashboardGuard[0], dashboardGuardReason[0],
                errors, finishedAt, durationMs);

        return new CollectionRun(runId, source.name(), namespace, scope, status, blockReason,
                counters[0], counters[1], counters[2], counters[3], counters[4],
                deletedCandidates, deleted, columns[0],
                dashboardsSunk[0], dashboardsSunk[1], dashboardsSunk[2],
                dashboardGuard[0], dashboardGuardReason[0],
                errors, durationMs, startedAt, finishedAt, decision);
    }

    // ------------------------------------------------------------------ Sink

    /**
     * BI 资产入库：仪表板是**一等实体**，因此天然获得 Owner、标签、生命周期、版本历史与血缘。
     *
     * <p>最关键的一条边：{@code Dashboard --readsFrom--> Dataset}。
     * 有了它才能回答"这张表要改了，哪些报表会受影响"（Batch 1 的影响分析直接复用），
     * 而这正是数据治理平台相对 BI 工具自带的"血缘"最有价值的增量。
     */
    private String sinkDashboard(RawModels.RawDashboard raw, String namespace, String platformUrn,
                                 String runId, int[] dashboardsSunk) {
        String dashboardUrn = UrnUtils.build("Dashboard", namespace,
                UrnUtils.sanitizeSegment(raw.platform()),
                UrnUtils.sanitizeSegment(raw.externalId()));

        boolean existed = metadata.entityExists(dashboardUrn);
        metadata.ensureEntity(dashboardUrn, "Dashboard", raw.title() == null ? raw.name() : raw.title(), runId);
        if (!existed) {
            dashboardsSunk[1]++;
        }
        dashboardsSunk[0]++;

        metadata.upsertEdge(platformUrn, dashboardUrn, "contains", "sql_parse", 1.0,
                null, null, null, "VALUE", null, null, runId);

        Map<String, Object> spec = new HashMap<>();
        spec.put("externalId", raw.externalId());
        spec.put("specSource", raw.platform());
        if (raw.url() != null) {
            spec.put("url", raw.url());
        }
        spec.put("chartCount", raw.charts().size());
        if (!raw.charts().isEmpty()) {
            spec.put("charts", raw.charts());
        }
        if (!raw.datasetUrns().isEmpty()) {
            spec.put("datasetUrns", raw.datasetUrns());
        }
        spec.put("published", raw.published());
        if (raw.lastRefreshedAt() != null) {
            spec.put("lastRefreshedAt", raw.lastRefreshedAt());
        }
        metadata.upsertAspect(dashboardUrn, "dashboardSpec", spec, COLLECT_SOURCE, null, runId);

        if (raw.description() != null && !raw.description().isBlank()) {
            metadata.upsertAspect(dashboardUrn, "descriptions",
                    Map.of("text", raw.description(), "language", "zh", "source", COLLECT_SOURCE),
                    COLLECT_SOURCE, null, runId);
        }

        if (!raw.owners().isEmpty()) {
            List<Map<String, Object>> owners = new ArrayList<>();
            for (String owner : raw.owners()) {
                String ownerUrn = UrnUtils.build("User", namespace, UrnUtils.sanitizeSegment(owner));
                metadata.ensureEntity(ownerUrn, "User", owner, runId);
                owners.add(Map.of("urn", ownerUrn, "name", owner));
                metadata.upsertEdge(dashboardUrn, ownerUrn, "ownedBy", "sql_parse", 1.0,
                        null, null, null, "VALUE", null, null, runId);
            }
            metadata.upsertAspect(dashboardUrn, "ownership", Map.of("owners", owners),
                    COLLECT_SOURCE, null, runId);
        }

        for (String datasetUrn : raw.datasetUrns()) {
            if (metadata.entityExists(datasetUrn)) {
                // 血缘边方向必须 from=上游、to=下游：数据集 → 报表。
                // 这样才能直接复用血缘遍历与影响分析（"这张表要改，哪些看板受影响"）。
                metadata.upsertEdge(datasetUrn, dashboardUrn, "consumedBy", "sql_parse", 0.9,
                        null, null, null, "VALUE", "table_level_only", null, runId);
            }
        }
        return dashboardUrn;
    }

    private String sink(RawModels.RawDataset raw, String namespace, String platformUrn,
                        String runId, int[] counters, int[] columns) {

        String datasetUrn = UrnUtils.build("Dataset", namespace,
                UrnUtils.sanitizeSegment(raw.platform()),
                UrnUtils.sanitizeSegment(raw.database()),
                UrnUtils.sanitizeSegment(raw.schema()),
                UrnUtils.sanitizeSegment(raw.table()));
        String containerUrn = UrnUtils.build("Container", namespace,
                UrnUtils.sanitizeSegment(raw.platform()),
                UrnUtils.sanitizeSegment(raw.database()),
                UrnUtils.sanitizeSegment(raw.schema()));

        metadata.ensureEntity(containerUrn, "Container", null, runId);
        boolean existed = metadata.entityExists(datasetUrn);
        metadata.ensureEntity(datasetUrn, "Dataset", raw.table(), runId);
        if (!existed) {
            counters[1]++;
        }
        columns[0] += raw.columns().size();

        metadata.upsertEdge(containerUrn, datasetUrn, "contains", "sql_parse", 1.0,
                null, null, null, "VALUE", null, null, runId);
        metadata.upsertEdge(platformUrn, containerUrn, "contains", "sql_parse", 1.0,
                null, null, null, "VALUE", null, null, runId);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (RawModels.RawColumn column : raw.columns()) {
            Map<String, Object> field = new HashMap<>();
            field.put("name", column.name());
            field.put("type", column.dataType());
            field.put("nativeType", column.dataType());
            field.put("nullable", column.nullable());
            field.put("ordinal", column.ordinal());
            if (column.comment() != null) {
                field.put("description", column.comment());
            }
            fields.add(field);
        }
        Map<String, Object> schema = new HashMap<>();
        schema.put("fields", fields);
        schema.put("primaryKey", raw.primaryKey());
        if (!raw.partitionKeys().isEmpty()) {
            schema.put("partitionKeys", raw.partitionKeys());
        }
        schema.put("schemaHash", MetadataService.fingerprint(Map.of(
                "kind", raw.kind(), "fields", fields)));
        schema.put("rawTypeSystem", raw.platform());

        var result = metadata.upsertAspect(datasetUrn, "datasetSchema", schema,
                COLLECT_SOURCE, null, runId);
        if (result.noop()) {
            counters[3]++;
        } else {
            counters[2]++;
        }

        if (raw.comment() != null && !raw.comment().isBlank()) {
            var desc = metadata.upsertAspect(datasetUrn, "descriptions",
                    Map.of("text", raw.comment(), "language", "zh", "source", COLLECT_SOURCE),
                    COLLECT_SOURCE, null, runId);
            if (!desc.changedFields().isEmpty()) {
                counters[4]++;
            }
        }
        return datasetUrn;
    }

    /**
     * 找出"数据库里有、本轮没采到"的仪表板实体（孤儿）。
     *
     * <p>为什么需要它：连接器的 URN 规则一旦变化（例如无 slug 的仪表板从 `id` 改成 `uuid`），
     * 旧实体就再也不会出现在采集结果里 —— 快照式护栏只能发现"上次见过、这次不见了"，
     * 对"从未进入过快照"的实体无能为力。孤儿会一直留在目录里，让人看到一个已经不存在的报表。
     */
    private List<String> findOrphanDashboards(String platform, String namespace, Set<String> current) {
        List<String> candidates = jdbc.queryForList("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Dashboard' AND namespace = ? AND deleted_at IS NULL
                   AND urn LIKE ?
                """, String.class, namespace, "urn:dg:Dashboard:" + namespace + "."
                + UrnUtils.sanitizeSegment(platform) + ".%");
        List<String> orphans = new ArrayList<>();
        for (String urn : candidates) {
            if (!current.contains(urn)) {
                orphans.add(urn);
            }
        }
        return orphans;
    }

    private static List<String> prepend(String prefix, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(prefix);
        out.addAll(rest);
        return out;
    }

    static String newRunId(String sourceName) {
        return "%s-%s-%s".formatted(sourceName,
                Instant.now().toString().replace(":", "").replace("-", "").substring(0, 15),
                UUID.randomUUID().toString().substring(0, 6));
    }

    // ------------------------------------------------------- 快照与运行记录

    private Set<String> loadSnapshot(String source, String namespace, String scope) {
        List<String> rows = jdbc.query("""
                SELECT jsonb_array_elements_text(last_snapshot) FROM collector_state
                 WHERE source = ? AND namespace = ? AND scope = ?
                """, (rs, i) -> rs.getString(1), source, namespace, scope);
        return new HashSet<>(rows);
    }

    private void saveSnapshot(String source, String namespace, String scope, String runId,
                              Set<String> current, String status) {
        List<String> sorted = current.stream().sorted().toList();
        jdbc.update("""
                INSERT INTO collector_state (source, namespace, scope, last_run_id, last_snapshot,
                                             entity_count, last_success_at, last_status,
                                             consecutive_failures, updated_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, now(), ?, 0, now())
                ON CONFLICT (source, namespace, scope) DO UPDATE
                    SET last_run_id = EXCLUDED.last_run_id,
                        last_snapshot = EXCLUDED.last_snapshot,
                        entity_count = EXCLUDED.entity_count,
                        last_success_at = now(),
                        last_status = EXCLUDED.last_status,
                        consecutive_failures = 0,
                        updated_at = now()
                """, source, namespace, scope, runId, toJson(sorted), sorted.size(), status);
    }

    private void bumpFailures(String source, String namespace, String scope, String status) {
        jdbc.update("""
                INSERT INTO collector_state (source, namespace, scope, last_snapshot, entity_count,
                                             last_status, consecutive_failures, updated_at)
                VALUES (?, ?, ?, '[]'::jsonb, 0, ?, 1, now())
                ON CONFLICT (source, namespace, scope) DO UPDATE
                    SET last_status = EXCLUDED.last_status,
                        consecutive_failures = collector_state.consecutive_failures + 1,
                        updated_at = now()
                """, source, namespace, scope, status);
    }

    private void insertRunRecord(String runId, String source, String namespace, String scope, Instant startedAt) {
        jdbc.update("""
                INSERT INTO collect_run (run_id, source, namespace, scope, status, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?)
                """, runId, source, namespace, scope, java.sql.Timestamp.from(startedAt));
    }

    private void finishRunRecord(String runId, String status, String blockReason, int[] counters,
                                 int deletedCandidates, int deleted, int columnsSeen,
                                 int[] dashboards, boolean dashboardGuardBlocked,
                                 String dashboardGuardReason,
                                 List<String> errors, Instant finishedAt, long durationMs) {
        jdbc.update("""
                UPDATE collect_run
                   SET status = ?, block_reason = ?, datasets_seen = ?, datasets_created = ?,
                       schemas_written = ?, schemas_unchanged = ?, deleted_candidates = ?,
                       deleted = ?, columns_seen = ?, dashboards_seen = ?, dashboards_created = ?,
                       dashboards_deleted = ?, dashboard_guard_blocked = ?, dashboard_guard_reason = ?,
                       errors = CAST(? AS jsonb),
                       finished_at = ?, duration_ms = ?
                 WHERE run_id = ?
                """, status, blockReason, counters[0], counters[1], counters[2], counters[3],
                deletedCandidates, deleted, columnsSeen, dashboards[0], dashboards[1], dashboards[2],
                dashboardGuardBlocked, dashboardGuardReason,
                toJson(errors),
                java.sql.Timestamp.from(finishedAt), (int) durationMs, runId);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new MetadataException("JSON 序列化失败：" + e.getMessage());
        }
    }

    /** 采集运行历史（供健康度与界面）。 */
    public List<Map<String, Object>> recentRuns(int limit) {
        return jdbc.queryForList("""
                SELECT run_id, source, namespace, scope, status, block_reason, datasets_seen,
                       datasets_created, schemas_written, schemas_unchanged, deleted_candidates,
                       deleted, columns_seen, dashboards_seen, dashboards_created, dashboards_deleted,
                       dashboard_guard_blocked, dashboard_guard_reason,
                       errors, duration_ms, started_at, finished_at
                  FROM collect_run ORDER BY started_at DESC LIMIT ?
                """, limit);
    }

    /** 采集健康度（供健康视图与告警）。 */
    public Map<String, Object> health() {
        List<Map<String, Object>> state = jdbc.queryForList("""
                SELECT source, namespace, scope, entity_count, last_status,
                       consecutive_failures, last_success_at, updated_at
                  FROM collector_state ORDER BY source, namespace, scope
                """);
        long unhealthy = state.stream()
                .filter(row -> number(row.get("consecutive_failures")) >= 3).count();
        long degraded = state.stream()
                .filter(row -> number(row.get("consecutive_failures")) >= 1).count();
        String health = unhealthy > 0 ? "UNHEALTHY" : (degraded > 0 ? "DEGRADED" : "HEALTHY");
        return Map.of("health", health, "sources", state);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
