package com.datagovernance.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.datagovernance.model.ModelRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 元数据核心服务：实体 / Aspect / 边的读写与事件发布（docs/07 §3.1）。
 *
 * <p>架构约束：
 * <ul>
 *   <li><b>ADR-001</b> PostgreSQL 是唯一真相源；</li>
 *   <li><b>ADR-002</b> 业务写入与 {@code event_log}(outbox) 在<b>同一事务</b>内提交，
 *       派生视图由消费者构建、可重放重建；</li>
 *   <li><b>ADR-005</b> aspect 字段级来源优先级：MANUAL &gt; IMPORTED &gt; AI_GENERATED &gt;
 *       AUTO_COLLECTED，采集不得覆盖人工内容，且每次采集(run)可整体回滚。</li>
 * </ul>
 */
@Service
public class MetadataService {

    /** 每次写入事件都要带上的来源，供消费者与审计使用。 */
    public static final String EVENT_ENTITY_CREATED = "ENTITY_CREATED";
    public static final String EVENT_ENTITY_DELETED = "ENTITY_DELETED";
    public static final String EVENT_ENTITY_RESURRECTED = "ENTITY_RESURRECTED";
    public static final String EVENT_ASPECT_UPSERTED = "ASPECT_UPSERTED";
    public static final String EVENT_EDGE_UPSERTED = "EDGE_UPSERTED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ModelRegistry registry;

    public MetadataService(JdbcTemplate jdbc, ModelRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public ModelRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- entity

    /**
     * 确保实体存在（幂等）。
     *
     * <p>复活语义：墓碑（TOMBSTONE / DELETED_AT_SOURCE）实体再次被采集到时自动复活，
     * 并发出 {@code ENTITY_RESURRECTED} 事件 —— 否则消费者会以为它仍处于删除状态。
     * 人工成果（aspect）从未物理删除，因此复活后即刻恢复。
     */
    @Transactional
    public EntityRow ensureEntity(String urn, String entityType, String displayName, String runId) {
        registry.entityType(entityType);
        UrnUtils.Parsed parsed = UrnUtils.parse(urn);
        if (!parsed.entityType().equals(entityType)) {
            throw new MetadataException.ValidationFailed(List.of(
                    "URN 中的类型 %s 与参数 entityType=%s 不一致".formatted(parsed.entityType(), entityType)));
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT deleted_at, lifecycle FROM entity WHERE urn = ?", urn);
        boolean created = existing.isEmpty();
        boolean resurrected = !existing.isEmpty() && existing.get(0).get("deleted_at") != null;

        jdbc.update("""
                INSERT INTO entity (urn, entity_type, namespace, tenant, display_name, run_id)
                VALUES (?, ?, ?, 'default', ?, ?)
                ON CONFLICT (urn) DO UPDATE
                    SET deleted_at = NULL,
                        lifecycle = CASE WHEN entity.lifecycle IN ('TOMBSTONE','DELETED_AT_SOURCE')
                                         THEN 'ACTIVE' ELSE entity.lifecycle END,
                        updated_at = now(),
                        display_name = COALESCE(entity.display_name, EXCLUDED.display_name),
                        run_id = COALESCE(EXCLUDED.run_id, entity.run_id)
                """, urn, entityType, namespaceOf(urn), displayName, runId);

        if (created) {
            emitEvent(EVENT_ENTITY_CREATED, urn, null, null,
                    Map.of("entityType", entityType, "displayName", displayName == null ? "" : displayName), runId);
        } else if (resurrected) {
            emitEvent(EVENT_ENTITY_RESURRECTED, urn, null, null,
                    Map.of("entityType", entityType, "previousLifecycle",
                            String.valueOf(existing.get(0).get("lifecycle"))), runId);
        }
        return getEntity(urn);
    }

    public EntityRow getEntity(String urn) {
        List<EntityRow> rows = jdbc.query("""
                SELECT urn, entity_type, namespace, display_name, lifecycle, created_at, updated_at
                  FROM entity WHERE urn = ? AND deleted_at IS NULL
                """, (rs, i) -> new EntityRow(
                        rs.getString("urn"),
                        rs.getString("entity_type"),
                        rs.getString("namespace"),
                        rs.getString("display_name"),
                        rs.getString("lifecycle"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), urn);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("实体不存在：" + urn);
        }
        return rows.get(0);
    }

    public boolean entityExists(String urn) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM entity WHERE urn = ? AND deleted_at IS NULL", Integer.class, urn);
        return count != null && count > 0;
    }

    /** 采集发现源端消失的实体 → 进墓碑（软删；aspect 与版本历史保留，可复活）。 */
    @Transactional
    public List<String> markDeletedAtSource(List<String> urns, String runId, String reason) {
        if (urns == null || urns.isEmpty()) {
            return List.of();
        }
        List<String> deleted = new ArrayList<>();
        for (String urn : urns) {
            int updated = jdbc.update("""
                    UPDATE entity SET deleted_at = now(), lifecycle = 'DELETED_AT_SOURCE', updated_at = now()
                     WHERE urn = ? AND deleted_at IS NULL
                    """, urn);
            if (updated > 0) {
                deleted.add(urn);
                emitEvent(EVENT_ENTITY_DELETED, urn, null, null,
                        Map.of("cause", "DELETED_AT_SOURCE", "reason", reason == null ? "" : reason), runId);
            }
        }
        return deleted;
    }

    // ---------------------------------------------------------------- aspect

    /**
     * 写入/更新一个 aspect（变更的最小单位）。
     *
     * @param expectedVersion 乐观锁期望版本（null 表示不校验）
     */
    @Transactional
    public UpsertResult upsertAspect(
            String urn,
            String aspectType,
            Map<String, Object> data,
            String source,
            Long expectedVersion,
            String runId) {

        SourcePriority priority = SourcePriority.of(source);
        EntityRow entity = getEntity(urn);

        if (!registry.isAspectAllowed(entity.entityType(), aspectType)) {
            throw new MetadataException.ValidationFailed(List.of(
                    "实体类型 %s 不允许 aspect %s（允许：%s）".formatted(
                            entity.entityType(), aspectType, registry.entityType(entity.entityType()).aspects())));
        }

        List<String> errors = registry.validateAspectData(aspectType, data);
        if (!errors.isEmpty()) {
            throw new MetadataException.ValidationFailed(errors);
        }

        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT version, data, field_sources FROM aspect WHERE urn = ? AND aspect_type = ? FOR UPDATE",
                urn, aspectType);

        long version;
        boolean created;
        Map<String, Object> merged;
        Map<String, Object> sources;
        Map<String, Object> protectedFields;
        List<String> changed;

        if (current.isEmpty()) {
            if (expectedVersion != null && expectedVersion != 0L) {
                throw new MetadataException.Conflict("aspect 不存在，但期望版本为 " + expectedVersion);
            }
            MergeResult merge = merge(new LinkedHashMap<>(), new LinkedHashMap<>(), data, priority);
            merged = merge.data();
            sources = merge.sources();
            protectedFields = merge.protectedFields();
            changed = merge.changed();

            jdbc.update("""
                    INSERT INTO aspect (urn, aspect_type, version, data, field_sources, updated_by, run_id)
                    VALUES (?, ?, 1, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                    """, urn, aspectType, writeJson(merged), writeJson(sources), source, runId);
            version = 1L;
            created = true;
        } else {
            Map<String, Object> row = current.get(0);
            long currentVersion = ((Number) row.get("version")).longValue();
            if (expectedVersion != null && expectedVersion != currentVersion) {
                throw new MetadataException.Conflict(
                        "乐观锁冲突：%s#%s 当前版本 %d，期望 %d".formatted(urn, aspectType, currentVersion, expectedVersion));
            }

            Map<String, Object> oldData = readJson(row.get("data"));
            Map<String, Object> oldSources = readJson(row.get("field_sources"));
            MergeResult merge = merge(oldData, oldSources, data, priority);

            if (merge.changed().isEmpty()) {
                // 幂等：无实际变化则不产生新版本与事件（内容指纹增量的具体体现）
                return new UpsertResult(urn, aspectType, currentVersion, -1L, List.of(),
                        merge.protectedFields(), false);
            }

            jdbc.update("""
                    INSERT INTO aspect_history (urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id)
                    SELECT urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id
                      FROM aspect WHERE urn = ? AND aspect_type = ?
                    """, urn, aspectType);

            jdbc.update("""
                    UPDATE aspect SET data = CAST(? AS jsonb), field_sources = CAST(? AS jsonb),
                                      version = version + 1, updated_by = ?, updated_at = now(), run_id = ?
                     WHERE urn = ? AND aspect_type = ?
                    """, writeJson(merge.data()), writeJson(merge.sources()), source, runId, urn, aspectType);

            merged = merge.data();
            sources = merge.sources();
            protectedFields = merge.protectedFields();
            changed = merge.changed();
            version = currentVersion + 1;
            created = false;
        }

        long eventSeq = emitEvent(EVENT_ASPECT_UPSERTED, urn, aspectType, version,
                Map.of("data", merged, "fieldSources", sources, "source", source), runId);

        return new UpsertResult(urn, aspectType, version, eventSeq, changed, protectedFields, created);
    }

    public Optional<Map<String, Object>> getAspect(String urn, String aspectType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT data FROM aspect WHERE urn = ? AND aspect_type = ?", urn, aspectType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(readJson(rows.get(0).get("data")));
    }

    public Map<String, Map<String, Object>> listAspects(String urn) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.query("SELECT aspect_type, data FROM aspect WHERE urn = ?", rs -> {
            out.put(rs.getString("aspect_type"), readJsonQuietly(rs.getString("data")));
        }, urn);
        return out;
    }

    public List<Map<String, Object>> aspectHistory(String urn, String aspectType) {
        return jdbc.queryForList("""
                SELECT version, data, updated_by, updated_at, run_id FROM aspect_history
                 WHERE urn = ? AND aspect_type = ? ORDER BY version DESC
                """, urn, aspectType);
    }

    /**
     * 资产级版本时间线：该资产**全部 aspect** 的当前版本与历史版本，按时间倒序。
     *
     * <p>刻意<b>不带 data</b>：时间线是列表页整体加载的，若每个版本都带上全量 JSON，
     * 一次翻页就会变成几 MB 的响应。要看某个版本的内容，用
     * {@link #aspectVersion(String, String, long)}。
     */
    public List<Map<String, Object>> versionTimeline(String urn) {
        return jdbc.queryForList("""
                SELECT aspect_type, version, updated_by, updated_at, run_id, TRUE AS is_current
                  FROM aspect WHERE urn = ?
                UNION ALL
                SELECT aspect_type, version, updated_by, updated_at, run_id, FALSE
                  FROM aspect_history WHERE urn = ?
                ORDER BY updated_at DESC, aspect_type, version DESC
                """, urn, urn);
    }

    /** 读取**指定版本**的 aspect 内容（当前版本与历史版本都支持）。 */
    public Optional<Map<String, Object>> aspectVersion(String urn, String aspectType, long version) {
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT data FROM aspect WHERE urn = ? AND aspect_type = ? AND version = ?",
                urn, aspectType, version);
        if (!current.isEmpty()) {
            return Optional.of(readJson(current.get(0).get("data")));
        }
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT data FROM aspect_history WHERE urn = ? AND aspect_type = ? AND version = ?",
                urn, aspectType, version);
        return history.isEmpty()
                ? Optional.empty()
                : Optional.of(readJson(history.get(0).get("data")));
    }

    /**
     * 把某个 aspect 回滚到指定历史版本。
     *
     * <p>实现刻意走 SQL 而不是复用 {@code upsertAspect}：upsertAspect 会做<b>字段级来源合并</b>
     * （MANUAL 不被 AUTO_COLLECTED 覆盖），那在"回滚"场景里恰恰是错的 ——
     * 使用者明确要求"回到第 N 版"，就应该整段还原（data 与 field_sources 一起），
     * 否则回滚结果会是一份"半新半旧"的混合体，比不回滚更难理解。
     *
     * <p><b>历史不可篡改</b>：回滚不是删掉后续版本，而是<b>追加一个新版本</b>
     * （内容等于目标版本）。因此 aspect_history 始终是完整的审计轨迹，
     * "谁在什么时候回滚了什么"也能从版本链上直接读出来。
     */
    @Transactional
    public Map<String, Object> rollbackAspect(String urn, String aspectType, long targetVersion,
                                             String actor) {
        if (targetVersion < 1) {
            throw new MetadataException.ValidationFailed(
                    List.of("目标版本必须 >= 1，实际 " + targetVersion));
        }
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT version FROM aspect WHERE urn = ? AND aspect_type = ? FOR UPDATE",
                urn, aspectType);
        if (current.isEmpty()) {
            throw new MetadataException.NotFound("aspect 不存在：" + urn + "#" + aspectType);
        }
        long currentVersion = ((Number) current.get(0).get("version")).longValue();
        if (targetVersion == currentVersion) {
            throw new MetadataException.Conflict(
                    "目标版本 %d 就是当前版本，无需回滚".formatted(targetVersion));
        }
        List<Map<String, Object>> target = jdbc.queryForList("""
                SELECT data, field_sources FROM aspect_history
                 WHERE urn = ? AND aspect_type = ? AND version = ?
                """, urn, aspectType, targetVersion);
        if (target.isEmpty()) {
            throw new MetadataException.NotFound(
                    "历史版本不存在：%s#%s@%d（历史表只留有被覆盖过的版本，最新版在 aspect 表）"
                            .formatted(urn, aspectType, targetVersion));
        }
        Map<String, Object> restored = readJson(target.get(0).get("data"));

        // 1) 先把当前版本归档：保证版本链不断，回滚本身也成为历史的一部分
        jdbc.update("""
                INSERT INTO aspect_history (urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id)
                SELECT urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id
                  FROM aspect WHERE urn = ? AND aspect_type = ?
                """, urn, aspectType);
        // 2) 整体还原到目标版本，版本号 +1（回滚 = 新版本，而不是删历史）
        jdbc.update("""
                UPDATE aspect SET data = CAST(? AS jsonb), field_sources = CAST(? AS jsonb),
                                  version = version + 1, updated_by = ?, updated_at = now(), run_id = NULL
                 WHERE urn = ? AND aspect_type = ?
                """, writeJson(restored), target.get(0).get("field_sources"),
                actor == null || actor.isBlank() ? "rollback" : "rollback:" + actor,
                urn, aspectType);

        long newVersion = currentVersion + 1;
        long eventSeq = emitEvent(EVENT_ASPECT_UPSERTED, urn, aspectType, newVersion,
                Map.of("data", restored, "source", "ROLLBACK", "restoredFrom", targetVersion), null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", urn);
        payload.put("aspectType", aspectType);
        payload.put("restoredFrom", targetVersion);
        payload.put("previousVersion", currentVersion);
        payload.put("version", newVersion);
        payload.put("eventSeq", eventSeq);
        payload.put("note", "回滚是追加新版本：未删除任何历史版本，版本链完整保留");
        return payload;
    }

    /** 按采集批次整体回滚（ADR-005）。 */
    @Transactional
    public Map<String, Object> rollbackRun(String runId) {
        List<Map<String, Object>> updated = jdbc.queryForList("""
                UPDATE aspect a
                   SET data = h.data, field_sources = h.field_sources, version = a.version + 1,
                       updated_by = 'rollback', updated_at = now(), run_id = NULL
                  FROM aspect_history h
                 WHERE a.run_id = ?
                   AND h.urn = a.urn AND h.aspect_type = a.aspect_type AND h.version = a.version - 1
                RETURNING a.urn, a.aspect_type
                """, runId);

        List<Map<String, Object>> dropped = jdbc.queryForList("""
                DELETE FROM aspect a
                 WHERE a.run_id = ? AND a.version = 1
                   AND NOT EXISTS (SELECT 1 FROM aspect_history h
                                    WHERE h.urn = a.urn AND h.aspect_type = a.aspect_type)
                RETURNING a.urn, a.aspect_type
                """, runId);

        List<Map<String, Object>> tombstoned = jdbc.queryForList("""
                UPDATE entity e
                   SET deleted_at = now(), lifecycle = 'TOMBSTONE', updated_at = now()
                 WHERE e.run_id = ? AND e.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM aspect a WHERE a.urn = e.urn)
                RETURNING e.urn
                """, runId);

        return Map.of(
                "runId", runId,
                "restoredAspects", updated.stream().map(r -> r.get("urn") + "#" + r.get("aspect_type")).toList(),
                "droppedAspects", dropped.stream().map(r -> r.get("urn") + "#" + r.get("aspect_type")).toList(),
                "tombstonedEntities", tombstoned.stream().map(r -> r.get("urn")).toList());
    }

    // ------------------------------------------------------------------ edge

    /**
     * 写入/更新一条关系边。
     *
     * <p>方向约定：<b>from = 上游（数据来源），to = 下游（数据去向）</b>。
     * 注意 OpenLineage 的 columnLineage facet 方向相反（输出列→输入列），接入时必须反转。
     */
    @Transactional
    public long upsertEdge(
            String fromUrn,
            String toUrn,
            String edgeType,
            String source,
            double confidence,
            String transform,
            String transformExpression,
            String cardinality,
            String dependencyKind,
            String parseLevel,
            String viaJob,
            String runId) {

        registry.relationshipType(edgeType);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new MetadataException.ValidationFailed(List.of("confidence 必须在 [0,1]：" + confidence));
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO edge (from_urn, to_urn, edge_type, source, confidence, transform,
                                  transform_expression, cardinality, dependency_kind, parse_level,
                                  via_job, first_seen, last_seen, observed_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 1)
                ON CONFLICT (from_urn, to_urn, edge_type, source, dependency_kind) DO UPDATE
                    SET last_seen = now(),
                        observed_count = edge.observed_count + 1,
                        confidence = GREATEST(edge.confidence, EXCLUDED.confidence),
                        transform = COALESCE(EXCLUDED.transform, edge.transform),
                        transform_expression = COALESCE(EXCLUDED.transform_expression, edge.transform_expression),
                        cardinality = COALESCE(EXCLUDED.cardinality, edge.cardinality),
                        parse_level = COALESCE(EXCLUDED.parse_level, edge.parse_level),
                        state = 'ACTIVE'
                RETURNING id
                """, Long.class, fromUrn, toUrn, edgeType, source, confidence, transform,
                transformExpression, cardinality,
                dependencyKind == null ? "VALUE" : dependencyKind, parseLevel, viaJob);

        if (registry.relationshipType(edgeType).lineage()) {
            emitEvent(EVENT_EDGE_UPSERTED, fromUrn, null, null, Map.of(
                    "edgeId", id, "fromUrn", fromUrn, "toUrn", toUrn, "edgeType", edgeType,
                    "source", source, "confidence", confidence,
                    "dependencyKind", dependencyKind == null ? "VALUE" : dependencyKind), runId);
        }
        return id == null ? -1L : id;
    }

    /**
     * 血缘遍历（docs/09 §9.2）。
     *
     * <p>实现约束：用 {@code UNION}（去重）而非 {@code UNION ALL} —— 真实血缘图有环，
     * {@code UNION ALL} 在 DAG 多路径下会路径数指数膨胀；深度有界；排除 CONTROL 依赖。
     *
     * <p><b>只沿"血缘关系类型"遍历</b>（模型里 {@code lineage: true} 的那几种）：
     * 结构包含边（{@code contains}）虽然也是边，但它表达的是"表属于这个库"，
     * 不是"数据从这来"。把它算进血缘会让每张表的上游都冒出"它的容器和平台"，
     * 血缘图立刻失去意义（这是实测出来的：加过滤前发现某表有 41 个"上游"）。
     */
    public LineageResult lineage(String urn, String direction, int maxDepth, double minConfidence) {
        if (!"upstream".equals(direction) && !"downstream".equals(direction)) {
            throw new MetadataException.ValidationFailed(List.of("direction 必须是 upstream/downstream"));
        }
        if (maxDepth < 1 || maxDepth > 10) {
            throw new MetadataException.ValidationFailed(List.of("maxDepth 必须在 [1,10]：" + maxDepth));
        }
        String joinClause = "downstream".equals(direction) ? "e.from_urn = w.urn" : "e.to_urn = w.urn";
        String nextCol = "downstream".equals(direction) ? "e.to_urn" : "e.from_urn";

        List<Map<String, Object>> nodes = jdbc.queryForList("""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(? AS text), 0
                    UNION
                    SELECT %s, w.depth + 1
                      FROM edge e JOIN walk w ON %s
                     WHERE e.state = 'ACTIVE' AND e.dependency_kind = 'VALUE'
                       AND e.edge_type = ANY(?)
                       AND e.confidence >= ? AND w.depth < ?
                )
                SELECT w.urn, MIN(w.depth) AS depth FROM walk w
                 WHERE w.urn <> ? GROUP BY w.urn ORDER BY depth, urn
                """.formatted(nextCol, joinClause), urn, lineageEdgeTypes(), minConfidence, maxDepth, urn);

        return new LineageResult(urn, direction, maxDepth,
                nodes.stream().map(r -> new LineageNode(
                        String.valueOf(r.get("urn")), ((Number) r.get("depth")).intValue())).toList());
    }

    /**
     * 血缘关系类型（模型里 {@code lineage: true} 的关系）。
     *
     * <p>以 JDBC 数组形式返回，供各处的血缘遍历统一使用 ——
     * "哪些边算血缘"必须有**唯一判定**，否则不同接口会给出互相矛盾的血缘。
     */
    public java.sql.Array lineageEdgeTypes() {
        Set<String> types = registry.lineageRelationshipNames();
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<java.sql.Array>) connection ->
                connection.createArrayOf("text", types.toArray()));
    }

    // ---------------------------------------------------------- 血缘边的人工确认

    /**
     * 确认一条血缘边（docs/14 §3.3「确认此血缘」）。
     *
     * <p>确认的语义是"人工背书"：置信度提升到人工级（≥0.99）并记录确认人。
     * 它是**置信度模型的输入**，而不是简单地改个标记。
     */
    public Map<String, Object> confirmEdge(long edgeId, String actor) {
        int updated = jdbc.update("""
                UPDATE edge
                   SET confidence = GREATEST(confidence, 0.99),
                       state = 'ACTIVE',
                       properties = properties || CAST(? AS jsonb),
                       last_seen = now()
                 WHERE id = ?
                """, writeJson(Map.of("confirmedBy", actor, "confirmedAt", Instant.now().toString())), edgeId);
        if (updated == 0) {
            throw new MetadataException.NotFound("血缘边不存在：" + edgeId);
        }
        return Map.of("edgeId", edgeId, "state", "ACTIVE", "confirmedBy", actor,
                "note", "确认会提升置信度到人工级（≥0.99），并作为置信度模型的输入");
    }

    /**
     * 驳回一条血缘边（docs/14 §3.3「标记为错误」）。
     *
     * <p>驳回后边不再参与血缘遍历（状态 REJECTED）。<b>是标记而不是删除</b>：
     * 保留它才能回答"这条边曾经被谁、因为什么驳回过"，也避免解析器下一轮又把它写回来。
     */
    public Map<String, Object> rejectEdge(long edgeId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new MetadataException.ValidationFailed(List.of("驳回血缘必须说明原因"));
        }
        int updated = jdbc.update("""
                UPDATE edge
                   SET state = 'REJECTED',
                       properties = properties || CAST(? AS jsonb),
                       last_seen = now()
                 WHERE id = ?
                """, writeJson(Map.of("rejectedBy", actor, "rejectedAt", Instant.now().toString(),
                        "rejectReason", reason)), edgeId);
        if (updated == 0) {
            throw new MetadataException.NotFound("血缘边不存在：" + edgeId);
        }
        return Map.of("edgeId", edgeId, "state", "REJECTED", "reason", reason,
                "note", "驳回是标记而不是删除：保留原因才能解释这条边为什么消失，也不会被解析器下一轮写回来");
    }

    /**
     * 按 (edge_type, source) 批量退役边 —— 运维工具。
     *
     * <p>用途：连接器/解析器的**边语义变化**（例如方向修正后换了 edge_type）会留下旧边，
     * 而边不会因为"新版本不再写它"而消失。这类清理必须显式触发、明确范围、并回报影响条数。
     */
    public Map<String, Object> retireEdges(String edgeType, String source, String reason, String actor) {
        if (edgeType == null || edgeType.isBlank()) {
            throw new MetadataException.ValidationFailed(List.of("必须指定 edgeType（避免误伤所有边）"));
        }
        if (reason == null || reason.isBlank()) {
            throw new MetadataException.ValidationFailed(List.of("必须说明退役原因"));
        }
        StringBuilder sql = new StringBuilder("""
                UPDATE edge
                   SET state = 'REJECTED',
                       properties = properties || CAST(? AS jsonb)
                 WHERE edge_type = ? AND state <> 'REJECTED'
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(writeJson(Map.of("retiredBy", actor, "retiredAt", Instant.now().toString(),
                "retireReason", reason)));
        params.add(edgeType);
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            params.add(source);
        }
        int updated = jdbc.update(sql.toString(), params.toArray());
        return Map.of("edgeType", edgeType, "source", source == null ? "(全部)" : source,
                "retired", updated, "reason", reason,
                "note", "按 edge_type + source 退役；范围必须显式给出，避免误伤其它来源的边");
    }

    // ------------------------------------------------------------- 内部工具

    private record MergeResult(
            Map<String, Object> data,
            Map<String, Object> sources,
            Map<String, Object> protectedFields,
            List<String> changed) {
    }

    /** 按来源优先级合并 aspect 数据 —— 「采集绝不覆盖人工内容」的实现点。 */
    private static MergeResult merge(
            Map<String, Object> existingData,
            Map<String, Object> existingSources,
            Map<String, Object> newData,
            SourcePriority newPriority) {

        Map<String, Object> data = new LinkedHashMap<>(existingData);
        Map<String, Object> sources = new LinkedHashMap<>(existingSources);
        Map<String, Object> protectedFields = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();

        for (Map.Entry<String, Object> entry : newData.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object oldSourceName = sources.get(key);

            if (oldSourceName != null) {
                SourcePriority oldPriority = SourcePriority.of(String.valueOf(oldSourceName));
                if (oldPriority.outranks(newPriority)) {
                    if (!java.util.Objects.equals(data.get(key), value)) {
                        protectedFields.put(key, Map.of(
                                "kept", data.get(key) == null ? "" : data.get(key),
                                "keptSource", oldPriority.name(),
                                "rejected", value == null ? "" : value,
                                "rejectedSource", newPriority.name(),
                                "reason", "字段来源优先级更高，拒绝覆盖"));
                    }
                    continue;
                }
            }
            if (!java.util.Objects.equals(data.get(key), value) || !newPriority.name().equals(oldSourceName)) {
                changed.add(key);
            }
            data.put(key, value);
            sources.put(key, newPriority.name());
        }
        return new MergeResult(data, sources, protectedFields, changed);
    }

    private long emitEvent(String eventType, String urn, String aspectType, Long version,
                           Map<String, Object> payload, String runId) {
        Long seq = jdbc.queryForObject("""
                INSERT INTO event_log (event_type, urn, aspect_type, version, payload, actor, run_id)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'java-control-plane', ?)
                RETURNING seq
                """, Long.class, eventType, urn, aspectType, version, writeJson(payload), runId);
        return seq == null ? -1L : seq;
    }

    private static String namespaceOf(String urn) {
        List<String> parts = UrnUtils.parse(urn).parts();
        return parts.isEmpty() ? "default" : parts.get(0);
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new MetadataException("JSON 序列化失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> readJson(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(String.valueOf(value), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new MetadataException("JSON 解析失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> readJsonQuietly(String value) {
        try {
            return readJson(value);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** 供幂等与审计使用的稳定哈希。 */
    public static String fingerprint(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(writeJson(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            throw new MetadataException("哈希计算失败：" + e.getMessage());
        }
    }

    public record EntityRow(
            String urn,
            String entityType,
            String namespace,
            String displayName,
            String lifecycle,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }

    public record LineageNode(String urn, int depth) {
    }

    public record LineageResult(String urn, String direction, int maxDepth, List<LineageNode> nodes) {

        public LineageResult {
            nodes = List.copyOf(nodes);
        }

        public List<LineageNode> sorted() {
            return nodes.stream().sorted(Comparator.comparingInt(LineageNode::depth)).toList();
        }
    }
}
