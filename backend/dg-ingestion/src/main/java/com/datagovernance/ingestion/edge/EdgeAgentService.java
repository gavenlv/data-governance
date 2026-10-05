package com.datagovernance.ingestion.edge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.UrnUtils;
import com.datagovernance.core.MetadataService;
import com.datagovernance.ingestion.CollectionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edge Agent 的**控制面侧**（ADR-012 的推模式）。
 *
 * <p>为什么必须有它：企业环境的硬约束是**数据不出域**（私有子网、专有云），
 * 主动拉取（pull）在这些环境里根本连不上；只能由数据侧的 Agent 把元数据推上来。
 * 因此在"实现 Go Agent 二进制"之前，控制面必须先能**接住**这种推送。
 *
 * <p>本类实现：Agent 注册（发凭据）、心跳、元数据上报（数据集 + 列）、
 * 上报的护栏与审计留痕。
 *
 * <p><b>诚实边界</b>：ADR-012 选型里的 **Go 单二进制 Agent 本体未实现** ——
 * 它需要独立的构建与发布流水线，不在本仓库范围内。
 * 但协议与接入点已经就绪：任何能发 HTTP 的采集器（脚本、cron、k8s Job）都能用。
 */
@Service
public class EdgeAgentService {

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final CollectionService collection;
    private final SecureRandom random = new SecureRandom();

    public EdgeAgentService(JdbcTemplate jdbc, MetadataService metadata, CollectionService collection) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.collection = collection;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 注册 Agent，返回**一次性**下发的凭据。
     *
     * <p>数据库只存凭据的哈希：与调度 DSN 的凭证纪律一致 —— 凭据不落明文。
     */
    @Transactional
    public Map<String, Object> register(String agentId, String displayName, String namespace,
                                        List<String> capabilities, String version, String actor) {
        if (agentId == null || agentId.isBlank()) {
            throw new EdgeException("缺少 agentId");
        }        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = "dgagent_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String resolvedNamespace = namespace == null || namespace.isBlank() ? "prod" : namespace;
        List<String> capabilityList = capabilities == null ? List.of() : capabilities;
        // text[] 必须用 createArrayOf 绑定：setObject(String[]) 会抛 SQLFeatureNotSupportedException
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO edge_agent (agent_id, display_name, namespace, token_hash, capabilities, version, registered_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (agent_id) DO UPDATE
                        SET display_name = EXCLUDED.display_name, namespace = EXCLUDED.namespace,
                            token_hash = EXCLUDED.token_hash, capabilities = EXCLUDED.capabilities,
                            version = EXCLUDED.version, status = 'ACTIVE', registered_by = EXCLUDED.registered_by
                    """);
            ps.setString(1, agentId);
            ps.setString(2, displayName == null ? agentId : displayName);
            ps.setString(3, resolvedNamespace);
            ps.setString(4, sha256(token));
            ps.setArray(5, connection.createArrayOf("text", capabilityList.toArray()));
            ps.setString(6, version);
            ps.setString(7, actor);
            return ps;
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("token", token);
        payload.put("namespace", resolvedNamespace);
        payload.put("note", "凭据**只在这里返回一次**（库里只存哈希）：请让 Agent 从环境变量读取，不要写进配置文件");
        payload.put("agentBinary", "ADR-012 选型的 Go 单二进制 Agent **未实现**；"
                + "本接口与 /api/v1/edge/report 构成了推模式接入点，任何能发 HTTP 的采集器都可用");
        return payload;
    }

    public List<Map<String, Object>> listAgents() {
        return jdbc.queryForList("""
                SELECT agent_id, display_name, namespace, capabilities, version, status,
                       last_heartbeat_at, registered_at, registered_by,
                       EXTRACT(EPOCH FROM (now() - last_heartbeat_at))::int AS seconds_since_heartbeat
                  FROM edge_agent ORDER BY agent_id
                """);
    }

    /**
     * 最近的上报记录。
     *
     * <p>存在的理由：Agent 说"我推了"、平台说"我没收到"是最常见的扯皮。
     * 有这张表就能回答"哪一次推送、推了多少、被接受多少、为什么被拒"。
     */
    public List<Map<String, Object>> recentReports(int limit) {
        return jdbc.queryForList("""
                SELECT r.id, r.agent_id, a.display_name, r.report_type, r.namespace,
                       r.entity_count, r.accepted, r.reject_reason, r.received_at
                  FROM edge_report r
                  LEFT JOIN edge_agent a ON a.agent_id = r.agent_id
                 ORDER BY r.received_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    /** 校验 Agent 凭据（返回 agentId 或 null）。 */
    public Map<String, Object> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT agent_id, namespace, status, capabilities FROM edge_agent
                 WHERE token_hash = ? AND status = 'ACTIVE'
                """, sha256(token));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 吊销 Agent 凭据。
     *
     * <p>凭据生命周期没有吊销口就是安全漏洞：Agent 部署在客户内网，一旦主机失陷或人员离职，
     * 平台侧必须能<b>立即</b>切断上报能力。吊销只改状态，**保留历史上报记录**用于追溯
     * （"这条元数据是谁在什么时候推上来的"必须还能回答）。
     */
    @Transactional
    public Map<String, Object> revoke(String agentId, String actor, String reason) {
        int updated = jdbc.update("""
                UPDATE edge_agent SET status = 'REVOKED' WHERE agent_id = ? AND status <> 'REVOKED'
                """, agentId);
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM edge_agent WHERE agent_id = ?", Integer.class, agentId);
            if (exists == null || exists == 0) {
                throw EdgeException.notFound("Agent 不存在：" + agentId);
            }
        }        jdbc.update("""
                INSERT INTO edge_report (agent_id, report_type, namespace, accepted, reject_reason)
                SELECT agent_id, 'heartbeat', namespace, FALSE, ?
                  FROM edge_agent WHERE agent_id = ?
                """, "凭据已吊销（操作者 " + actor + "）：" + (reason == null ? "未说明理由" : reason), agentId);
        return Map.of("agentId", agentId, "status", "REVOKED",
                "note", "吊销后该 Agent 的下一次心跳/上报会因凭据无效被拒；历史上报记录保留以便追溯");
    }

    // ------------------------------------------------------------------ 上报

    /** 心跳。 */
    @Transactional
    public Map<String, Object> heartbeat(String token, Map<String, Object> body) {
        Map<String, Object> agent = requireAgent(token);
        String agentId = String.valueOf(agent.get("agent_id"));
        jdbc.update("UPDATE edge_agent SET last_heartbeat_at = now() WHERE agent_id = ?", agentId);
        if (body != null && body.get("version") != null) {
            jdbc.update("UPDATE edge_agent SET version = ? WHERE agent_id = ?",
                    String.valueOf(body.get("version")), agentId);
        }
        jdbc.update("""
                INSERT INTO edge_report (agent_id, report_type, namespace) VALUES (?, 'heartbeat', ?)
                """, agentId, agent.get("namespace"));
        return Map.of("agentId", agentId, "status", "OK",
                "note", "心跳时间用于判断 Agent 是否存活；超过阈值未心跳的 Agent 会在 /agents 里显示滞后秒数");
    }

    /**
     * 元数据上报（数据集 + 列）。
     *
     * <p>上报的数据走**同一套**真相源与来源保护（{@code AUTO_COLLECTED}），
     * 因此私有子网采上来的元数据不会覆盖人工编辑（ADR-005），
     * 也会自动进入检索索引与血缘图 —— 推模式不是"另一条侧路"。
     *
     * <p>护栏：单次上报的实体数有上限；超过上限直接**拒绝并说明原因**，
     * 而不是截断后写入（截断会让人以为"上报成功了"）。
     */
    @Transactional
    public Map<String, Object> reportDatasets(String token, Map<String, Object> body) {
        Map<String, Object> agent = requireAgent(token);
        String agentId = String.valueOf(agent.get("agent_id"));
        String namespace = body.get("namespace") == null
                ? String.valueOf(agent.get("namespace")) : String.valueOf(body.get("namespace"));
        List<Map<String, Object>> datasets = asMapList(body.get("datasets"));
        int maxEntities = 5000;
        if (datasets.size() > maxEntities) {
            jdbc.update("""
                    INSERT INTO edge_report (agent_id, report_type, namespace, entity_count, accepted, reject_reason)
                    VALUES (?, 'datasets', ?, ?, FALSE, ?)
                    """, agentId, namespace, datasets.size(),
                    "单次上报实体数超过上限 " + maxEntities);
            throw new EdgeException("单次上报 " + datasets.size() + " 个实体，超过上限 " + maxEntities
                    + "：请分批上报。**不会截断后写入** —— 截断会让人以为上报成功了");
        }

        int accepted = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> raw : datasets) {
            try {
                ingestDataset(namespace, raw);
                accepted++;
            } catch (RuntimeException e) {
                errors.add(String.valueOf(raw.get("database")) + "." + raw.get("schema") + "."
                        + raw.get("table") + ": " + e.getMessage());
            }
        }
        jdbc.update("""
                INSERT INTO edge_report (agent_id, report_type, namespace, entity_count, accepted, reject_reason)
                VALUES (?, 'datasets', ?, ?, ?, ?)
                """, agentId, namespace, datasets.size(), errors.isEmpty(),
                errors.isEmpty() ? null : String.join("; ", errors.subList(0, Math.min(3, errors.size()))));
        jdbc.update("UPDATE edge_agent SET last_heartbeat_at = now() WHERE agent_id = ?", agentId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("namespace", namespace);
        payload.put("received", datasets.size());
        payload.put("accepted", accepted);
        payload.put("errors", errors);
        payload.put("note", "上报的元数据同样以 AUTO_COLLECTED 来源写入："
                + "**不会覆盖人工编辑**（ADR-005），并自动进入检索索引与血缘图");
        return payload;
    }

    private void ingestDataset(String namespace, Map<String, Object> raw) {
        String platform = raw.get("platform") == null ? "edge" : String.valueOf(raw.get("platform"));
        String database = String.valueOf(raw.get("database"));
        String schema = String.valueOf(raw.get("schema"));
        String table = String.valueOf(raw.get("table"));
        String datasetUrn = UrnUtils.build("Dataset", namespace,
                UrnUtils.sanitizeSegment(platform), UrnUtils.sanitizeSegment(database),
                UrnUtils.sanitizeSegment(schema), UrnUtils.sanitizeSegment(table));
        String containerUrn = UrnUtils.build("Container", namespace,
                UrnUtils.sanitizeSegment(platform), UrnUtils.sanitizeSegment(database),
                UrnUtils.sanitizeSegment(schema));
        // 与主动拉取（CollectionService）保持**同一套 URN 形状**：否则同一个库被
        // 拉取和推送各采一次，会变成图里两个互不相连的资产
        String platformUrn = UrnUtils.build("Platform", namespace, UrnUtils.sanitizeSegment(platform));

        metadata.ensureEntity(platformUrn, "Platform", platform, null);
        metadata.ensureEntity(containerUrn, "Container", null, null);
        metadata.ensureEntity(datasetUrn, "Dataset", table, null);
        metadata.upsertEdge(platformUrn, containerUrn, "contains", "sql_parse", 1.0,
                null, null, null, "VALUE", null, null, null);
        metadata.upsertEdge(containerUrn, datasetUrn, "contains", "sql_parse", 1.0,
                null, null, null, "VALUE", null, null, null);

        List<Map<String, Object>> fields = new ArrayList<>();
        int ordinal = 1;
        for (Map<String, Object> column : asMapList(raw.get("columns"))) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", String.valueOf(column.get("name")));
            field.put("type", column.get("type") == null ? "UNKNOWN" : String.valueOf(column.get("type")));
            field.put("nativeType", field.get("type"));
            field.put("nullable", column.get("nullable") == null || Boolean.parseBoolean(
                    String.valueOf(column.get("nullable"))));
            field.put("ordinal", ordinal++);
            if (column.get("description") != null) {
                field.put("description", String.valueOf(column.get("description")));
            }
            fields.add(field);
        }
        Map<String, Object> schemaData = new LinkedHashMap<>();
        schemaData.put("fields", fields);
        schemaData.put("primaryKey", asStringList(raw.get("primaryKey")));
        schemaData.put("schemaHash", MetadataService.fingerprint(Map.of("kind", "TABLE", "fields", fields)));
        schemaData.put("rawTypeSystem", platform + "（edge 上报）");
        metadata.upsertAspect(datasetUrn, "datasetSchema", schemaData, "AUTO_COLLECTED", null, null);
        if (raw.get("description") != null && !String.valueOf(raw.get("description")).isBlank()) {
            metadata.upsertAspect(datasetUrn, "descriptions", Map.of(
                    "text", String.valueOf(raw.get("description")), "language", "zh",
                    "source", "AUTO_COLLECTED"), "AUTO_COLLECTED", null, null);
        }
    }

    private Map<String, Object> requireAgent(String token) {
        Map<String, Object> agent = authenticate(token);
        if (agent == null) {
            throw EdgeException.unauthorized(
                    "Agent 凭据无效或已停用（Authorization: Bearer <agent token>）");
        }
        return agent;
    }

    // ------------------------------------------------------------------ 工具

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    map.forEach((key, val) -> entry.put(String.valueOf(key), val));
                    out.add(entry);
                }
            }
            return out;
        }
        return List.of();
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * Edge Agent 相关错误。
     *
     * <p>带 {@code kind} 而不是靠消息文本判类型：HTTP 层要据此区分 401（凭据问题）
     * 与 422（上报内容/护栏拒绝），用字符串匹配消息迟早会判错。
     */
    public static class EdgeException extends RuntimeException {

        /** 凭据无效/已吊销 → HTTP 401。 */
        public static final String KIND_UNAUTHORIZED = "UNAUTHORIZED";
        /** 上报内容被拒（超限、参数错误）或管理操作不合法 → HTTP 422。 */
        public static final String KIND_REJECTED = "REJECTED";
        /** 目标对象不存在 → HTTP 404。 */
        public static final String KIND_NOT_FOUND = "NOT_FOUND";

        private final String kind;

        public EdgeException(String message) {
            this(KIND_REJECTED, message);
        }

        public EdgeException(String kind, String message) {
            super(message);
            this.kind = kind;
        }

        public static EdgeException unauthorized(String message) {
            return new EdgeException(KIND_UNAUTHORIZED, message);
        }

        public static EdgeException notFound(String message) {
            return new EdgeException(KIND_NOT_FOUND, message);
        }

        public String kind() {
            return kind;
        }

        public boolean isUnauthorized() {
            return KIND_UNAUTHORIZED.equals(kind);
        }

        public boolean isNotFound() {
            return KIND_NOT_FOUND.equals(kind);
        }
    }
}
