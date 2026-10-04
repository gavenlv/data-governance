package com.datagovernance.api.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 资产读取与治理属性写入。 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;

    public AssetController(MetadataService metadata, JdbcTemplate jdbc) {
        this.metadata = metadata;
        this.jdbc = jdbc;
    }

    @GetMapping("/assets/{urn}")
    public Map<String, Object> getAsset(@PathVariable String urn) {
        UrnUtils.parse(urn);
        // 详情与搜索必须共用同一套可见性判定（docs/09 §9.3）——
        // 否则会出现"搜不到但知道 URN 就能打开"这类越权
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        AccessPolicy.ensureVisible(subject, classificationOf(urn));

        MetadataService.EntityRow entity = metadata.getEntity(urn);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", entity.urn());
        payload.put("entityType", entity.entityType());
        payload.put("namespace", entity.namespace());
        payload.put("displayName", entity.displayName());
        payload.put("lifecycle", entity.lifecycle());
        payload.put("createdAt", entity.createdAt());
        payload.put("updatedAt", entity.updatedAt());
        payload.put("aspects", metadata.listAspects(urn));
        return payload;
    }

    /** 读取该资产的分类分级（无则返回 null，由 AccessPolicy 按 L2 保守默认处理）。 */
    private String classificationOf(String urn) {
        return metadata.getAspect(urn, "classification")
                .map(data -> data.get("level"))
                .map(String::valueOf)
                .orElse(null);
    }

    @GetMapping("/assets/{urn}/aspects/{aspectType}")
    public Map<String, Object> getAspect(@PathVariable String urn, @PathVariable String aspectType) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        AccessPolicy.ensureVisible(subject, classificationOf(urn));
        return metadata.getAspect(urn, aspectType)
                .map(data -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("urn", urn);
                    payload.put("aspectType", aspectType);
                    payload.put("data", data);
                    return payload;
                })
                .orElseThrow(() -> new com.datagovernance.core.MetadataException.NotFound(
                        "aspect 不存在：" + urn + "#" + aspectType));
    }

    /** 写入 aspect（治理动作）。来源优先级保护在核心层生效。 */
    @PostMapping("/assets/{urn}/aspects/{aspectType}")
    public Map<String, Object> writeAspect(
            @PathVariable String urn,
            @PathVariable String aspectType,
            @RequestBody AspectWriteRequest request,
            @RequestParam(defaultValue = "Dataset") String entityType,
            @RequestParam(defaultValue = "true") boolean createEntityIfMissing) {

        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:write");
        AccessPolicy.ensureVisible(subject, classificationOf(urn));

        if (createEntityIfMissing && !metadata.entityExists(urn)) {
            metadata.ensureEntity(urn, entityType, null, null);
        }
        var result = metadata.upsertAspect(urn, aspectType,
                request.data() == null ? Map.of() : request.data(),
                request.source() == null ? "MANUAL" : request.source(),
                request.expectedVersion(), request.runId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", result.urn());
        payload.put("aspectType", result.aspectType());
        payload.put("version", result.version());
        payload.put("eventSeq", result.eventSeq());
        payload.put("changedFields", result.changedFields());
        payload.put("protectedFields", result.protectedFields());
        payload.put("created", result.created());
        return payload;
    }

    /**
     * 按前缀列资产（供界面列表）。
     *
     * <p>列表同样**前置**注入可见性条件（不是取回再筛）：否则列表条数与内容会泄露
     * 无权资产的存在性 —— 与检索是同一类问题，必须用同一套判定（docs/09 §9.3）。
     * 完整检索见 {@code GET /api/v1/search}。
     */
    @GetMapping("/assets")
    public Map<String, Object> listAssets(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "50") int limit) {

        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<String> levels = AccessPolicy.visibleLevels(subject);

        List<Object> params = new ArrayList<>(levels);
        StringBuilder sql = new StringBuilder("""
                SELECT e.urn, e.entity_type, e.namespace, e.display_name, e.lifecycle, e.updated_at,
                       a.data->>'level' AS classification
                  FROM entity e
                  LEFT JOIN aspect a ON a.urn = e.urn AND a.aspect_type = 'classification'
                 WHERE e.deleted_at IS NULL
                   AND COALESCE(a.data->>'level', 'L2') IN ("""
                + String.join(", ", java.util.Collections.nCopies(levels.size(), "?")) + ")");
        if (prefix != null && !prefix.isBlank()) {
            sql.append(" AND e.urn LIKE ?");
            params.add(prefix + "%");
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND e.entity_type = ?");
            params.add(entityType);
        }
        sql.append(" ORDER BY e.updated_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        Map<String, Object> payload = ApiExceptionHandler.list("assets", rows);
        payload.put("visibleLevels", levels);
        return payload;
    }

    /** aspect 版本历史（时间旅行与回滚的依据）。 */
    @GetMapping("/assets/{urn}/aspects/{aspectType}/history")
    public Map<String, Object> aspectHistory(@PathVariable String urn, @PathVariable String aspectType) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        AccessPolicy.ensureVisible(subject, classificationOf(urn));
        return ApiExceptionHandler.list("history", metadata.aspectHistory(urn, aspectType));
    }

    /**
     * 全文检索已移至 {@link SearchController}（{@code GET /api/v1/search}）并接真实索引。
     *
     * <p>此处原有的 501 占位已删除 —— 占位被真实实现取代后必须删掉，
     * 否则同一路径存在两套语义（一处真、一处假），是更隐蔽的不诚实。
     */

    public record AspectWriteRequest(Map<String, Object> data, String source,
                                     Long expectedVersion, String runId) {
    }
}
