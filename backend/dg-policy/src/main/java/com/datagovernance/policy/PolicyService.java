package com.datagovernance.policy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
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
 * 策略的建模、编译、版本与下发（docs/09 §9.7、ADR-008、docs/20 §8）。
 *
 * <p>定位（docs/20 §8 的原话）：<b>执行层是商品，生命周期层才是产品。</b>
 * 因此本类<b>不实现执行引擎</b> —— 它做的是：
 * <ol>
 *   <li><b>业务可读的建模</b>：策略用 YAML/JSON 描述（谁能看哪些行/列），而不是写 Trino 插件；</li>
 *   <li><b>编译</b>：把策略编译成各目标引擎的产物（{@link PolicyCompiler}，纯函数 + 快照测试）；</li>
 *   <li><b>版本与产物存档</b>：每次编译的产物落库（内容哈希），事后能回答"当时下发的是什么"；</li>
 *   <li><b>下发与回滚</b>：产物打包成 bundle（默认写到本地目录，可对接 GitOps），记录部署记录；</li>
 *   <li><b>覆盖率度量</b>：哪些资产被策略覆盖、哪些高分级资产完全没有策略 ——
 *       并且**显式记录"直连绕过"这一已知缺口**（真实检测需要引擎审计日志，本平台目前拿不到）。</li>
 * </ol>
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final Path bundleDir;

    public PolicyService(JdbcTemplate jdbc, MetadataService metadata,
                         @Value("${dg.policy.bundle-dir:./policy-bundles}") String bundleDir) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.bundleDir = Path.of(bundleDir);
    }

    // ------------------------------------------------------------------ 建模

    /** 新建 / 更新策略定义（业务可读的建模）。 */
    @Transactional
    public Map<String, Object> upsertDefinition(Map<String, Object> document, String actor) {
        PolicyCompiler.PolicySpec spec = toSpec(document);
        List<String> errors = spec.validate();
        if (!errors.isEmpty()) {
            throw new AccessPolicyException("策略不合法：" + String.join("；", errors));
        }
        Integer existingVersion = currentVersion(spec.name());
        int version = existingVersion == null ? 1 : existingVersion + 1;

        jdbc.update("""
                INSERT INTO policy_definition (name, description, target, effect, resource_scope,
                                               subject_scope, condition, priority, status, version, created_by)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE
                    SET description = EXCLUDED.description,
                        target = EXCLUDED.target,
                        effect = EXCLUDED.effect,
                        resource_scope = EXCLUDED.resource_scope,
                        subject_scope = EXCLUDED.subject_scope,
                        condition = EXCLUDED.condition,
                        priority = EXCLUDED.priority,
                        status = EXCLUDED.status,
                        version = EXCLUDED.version,
                        updated_at = now()
                """, spec.name(), str(document.get("description")), spec.target(), spec.effect(),
                toJson(spec.resourceScope()), toJson(spec.subjectScope()), toJson(spec.condition()),
                spec.priority(), document.get("status") == null ? "ACTIVE" : String.valueOf(document.get("status")),
                version, actor);

        // 建模后立即编译一次：**编译不过的策略不该留在库里**（否则下发时才发现）
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(spec);
        storeArtifact(spec, compiled);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", spec.name());
        payload.put("version", version);
        payload.put("target", spec.target());
        payload.put("effect", spec.effect());
        payload.put("compiledPreview", compiled.artifact());
        payload.put("note", "建模即编译：编译不过的策略不会入库（避免下发时才发现问题）");
        return payload;
    }

    public List<Map<String, Object>> listDefinitions() {
        return jdbc.queryForList("""
                SELECT name, description, target, effect, resource_scope, subject_scope, condition,
                       priority, status, version, updated_at
                  FROM policy_definition ORDER BY target, priority, name
                """);
    }

    // ------------------------------------------------------------------ 编译

    /** 编译全部 ACTIVE 策略（可指定目标），产物落库。 */
    public Map<String, Object> compileAll(String target) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM policy_definition
                 WHERE status = 'ACTIVE' AND (CAST(? AS text) IS NULL OR target = ?)
                 ORDER BY target, priority, name
                """, target, target);
        List<Map<String, Object>> compiled = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            try {
                PolicyCompiler.PolicySpec spec = fromRow(row);
                PolicyCompiler.CompiledPolicy artifact = PolicyCompiler.compile(spec);
                storeArtifact(spec, artifact);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", spec.name());
                item.put("version", spec.version());
                item.put("target", spec.target());
                item.put("effect", spec.effect());
                item.put("artifactHash", sha256(artifact.artifact()));
                compiled.add(item);
            } catch (RuntimeException e) {
                failed.add(Map.of("name", String.valueOf(row.get("name")), "error", e.getMessage()));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("compiled", compiled);
        payload.put("failed", failed);
        payload.put("count", compiled.size());
        payload.put("note", "编译产物按 (策略, 版本, 目标, 内容哈希) 去重存档："
                + "事后必须能回答「当时下发的是什么」");
        return payload;
    }

    private void storeArtifact(PolicyCompiler.PolicySpec spec, PolicyCompiler.CompiledPolicy compiled) {
        jdbc.update("""
                INSERT INTO policy_artifact (policy_name, policy_version, target, content_hash, artifact)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (policy_name, policy_version, target, content_hash) DO NOTHING
                """, spec.name(), spec.version(), compiled.target(), sha256(compiled.artifact()),
                compiled.artifact());
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 下发：把某个目标的全部产物打成一个 bundle 并写出到磁盘。
     *
     * <p>为什么默认是"产物 bundle + 落盘"而不是"直连引擎推送"：
     * 执行层是现成件（Trino AccessControl 插件、Ranger、数仓原生策略），
     * 平台的职责是**产出可信、可审计、可回滚的产物**；直接推送则由客户的发布流程决定
     * （GitOps / CI / 运维脚本）。本接口产出 bundle 并记录部署记录，
     * 使"下发"这件事在治理侧可追溯、可回滚。
     */
    @Transactional
    public Map<String, Object> deploy(String target, String actor) {
        List<Map<String, Object>> artifacts = jdbc.queryForList("""
                SELECT a.policy_name, a.policy_version, a.target, a.content_hash, a.artifact
                  FROM policy_artifact a
                  JOIN policy_definition d ON d.name = a.policy_name AND d.version = a.policy_version
                 WHERE a.target = ? AND d.status = 'ACTIVE'
                 ORDER BY d.priority, a.policy_name
                """, target);
        if (artifacts.isEmpty()) {
            throw new AccessPolicyException("目标 " + target + " 没有可下发的产物（先 compile 或检查策略状态）");
        }
        StringBuilder bundle = new StringBuilder();
        bundle.append("-- 策略产物 bundle\n");
        bundle.append("-- target: ").append(target).append('\n');
        bundle.append("-- generatedAt: ").append(Instant.now()).append('\n');
        bundle.append("-- 执行层说明：本 bundle 由治理平台编译产出，实际执行件为现成的\n");
        bundle.append("--   Trino SystemAccessControl / Ranger / 数仓原生策略（平台不自研执行引擎）\n\n");
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Map<String, Object> artifact : artifacts) {
            bundle.append("-- === ").append(artifact.get("policy_name"))
                    .append(" v").append(artifact.get("policy_version")).append(" ===\n");
            bundle.append(artifact.get("artifact")).append('\n');
            detail.add(Map.of("policy", artifact.get("policy_name"),
                    "version", artifact.get("policy_version"),
                    "hash", artifact.get("content_hash")));
        }
        String bundleHash = sha256(bundle.toString());
        String fileName = "policy-bundle-" + target + "-" + bundleHash.substring(0, 12) + ".txt";
        String written = null;
        try {
            Files.createDirectories(bundleDir);
            Path file = bundleDir.resolve(fileName);
            Files.writeString(file, bundle.toString(), StandardCharsets.UTF_8);
            written = file.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            // 落盘失败必须显式暴露：否则"已下发"是假的
            log.warn("策略 bundle 落盘失败：{}", e.getMessage());
            throw new AccessPolicyException("策略 bundle 落盘失败：" + e.getMessage());
        }

        Long deploymentId = jdbc.queryForObject("""
                INSERT INTO policy_deployment (target, bundle_hash, status, dispatch_mode,
                                               artifact_count, detail, deployed_by, applied_at)
                VALUES (?, ?, 'APPLIED', 'artifact_bundle', ?, CAST(? AS jsonb), ?, now())
                RETURNING id
                """, Long.class, target, bundleHash, artifacts.size(),
                toJson(Map.of("artifacts", detail, "bundleFile", written)), actor);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deploymentId", deploymentId);
        payload.put("target", target);
        payload.put("bundleHash", bundleHash);
        payload.put("artifactCount", artifacts.size());
        payload.put("bundleFile", written);
        payload.put("status", "APPLIED");
        payload.put("note", "产物已写出并记录部署；**执行层的实际生效**取决于客户把它接入 Trino/Ranger/"
                + "数仓的发布流程（平台不自研执行引擎，见 docs/20 §8）");
        return payload;
    }

    /** 回滚到某个历史 bundle（重新记录一条 APPLIED 部署，指向旧产物集合）。 */
    @Transactional
    public Map<String, Object> rollback(long deploymentId, String actor, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM policy_deployment WHERE id = ?", deploymentId);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("部署记录不存在：" + deploymentId);
        }
        Map<String, Object> previous = rows.get(0);
        jdbc.update("""
                UPDATE policy_deployment SET status = 'ROLLED_BACK', rolled_back_at = now()
                 WHERE id = ?
                """, deploymentId);
        Long newId = jdbc.queryForObject("""
                INSERT INTO policy_deployment (target, bundle_hash, status, dispatch_mode, artifact_count,
                                               detail, deployed_by, applied_at)
                VALUES (?, ?, 'APPLIED', ?, ?, CAST(? AS jsonb), ?, now())
                RETURNING id
                """, Long.class, previous.get("target"), previous.get("bundle_hash"),
                "rollback_to:" + deploymentId, previous.get("artifact_count"),
                toJson(Map.of("rollbackOf", deploymentId, "reason", reason == null ? "" : reason)),
                actor);
        return Map.of("deploymentId", newId, "rolledBack", deploymentId,
                "bundleHash", previous.get("bundle_hash"),
                "reason", reason == null ? "" : reason,
                "note", "回滚是「记录一次指向旧产物集合的新部署」：审计上必须能看到"
                        + "「什么时候、谁、因为什么回滚到哪个版本」");
    }

    public List<Map<String, Object>> listDeployments(String target, int limit) {
        return jdbc.queryForList("""
                SELECT id, target, bundle_hash, status, dispatch_mode, artifact_count, deployed_by,
                       created_at, applied_at, rolled_back_at, detail
                  FROM policy_deployment
                 WHERE (CAST(? AS text) IS NULL OR target = ?)
                 ORDER BY created_at DESC LIMIT ?
                """, target, target, Math.min(Math.max(limit, 1), 200));
    }

    // -------------------------------------------------------------- 覆盖率度量

    /**
     * 覆盖率度量。
     *
     * <p>docs/09 §9.7 的原话：<b>宣称"策略已下发"而不度量覆盖率，是最危险的产品表述。</b>
     * 因此这里给出三个必须被看见的数：总资产、被策略覆盖的资产、**没有任何策略覆盖的高分级资产**，
     * 并显式声明"直连绕过"这一缺口（真实检测需要引擎审计日志，未接入）。
     */
    @Transactional
    public Map<String, Object> measureCoverage(String actor) {
        List<Map<String, Object>> datasets = jdbc.queryForList("""
                SELECT e.urn, e.display_name, e.namespace,
                       COALESCE(a.data->>'level', 'L2') AS classification
                  FROM entity e
                  LEFT JOIN aspect a ON a.urn = e.urn AND a.aspect_type = 'classification'
                 WHERE e.entity_type = 'Dataset' AND e.deleted_at IS NULL
                """);
        List<PolicyCompiler.PolicySpec> specs = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM policy_definition WHERE status = 'ACTIVE'")) {
            specs.add(fromRow(row));
        }

        List<Map<String, Object>> uncoveredHigh = new ArrayList<>();
        int covered = 0;
        for (Map<String, Object> dataset : datasets) {
            String urn = String.valueOf(dataset.get("urn"));
            String classification = String.valueOf(dataset.get("classification"));
            boolean isCovered = specs.stream().anyMatch(spec ->
                    PolicyCompiler.coversDataset(spec, urn, classification, null));
            if (isCovered) {
                covered++;
            } else if ("L3".equals(classification) || "L4".equals(classification)) {
                uncoveredHigh.add(Map.of("urn", urn,
                        "displayName", String.valueOf(dataset.get("display_name")),
                        "classification", classification,
                        "risk", "高分级资产没有任何策略覆盖：属于最该优先补齐的缺口"));
            }
        }

        double ratio = datasets.isEmpty() ? 0.0 : (double) covered / datasets.size();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("activePolicies", specs.size());
        detail.put("uncoveredHighClassification", uncoveredHigh);
        detail.put("knownBlindSpots", List.of(
                "**直连绕过**：用户直接连数仓 JDBC 会完全绕过 Trino/数仓策略执行点。"
                        + "真实检测需要引擎审计日志或网络层数据，本平台尚未接入，因此覆盖率数字**不包含**这一风险",
                "BI 层可见性只是消费端配置，不是安全边界（用户可绕过 BI 直连数仓）",
                "应用层令牌声明仅是辅助提示，客户端可伪造/忽略"));

        jdbc.update("""
                INSERT INTO policy_coverage (scope, total_assets, covered_assets, uncovered_high_class, detail)
                VALUES ('all-datasets', ?, ?, ?, CAST(? AS jsonb))
                """, datasets.size(), covered, uncoveredHigh.size(), toJson(detail));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "all-datasets");
        payload.put("totalAssets", datasets.size());
        payload.put("coveredAssets", covered);
        payload.put("coverageRatio", Math.round(ratio * 1000) / 1000.0);
        payload.put("uncoveredHighClassification", uncoveredHigh);
        payload.put("knownBlindSpots", detail.get("knownBlindSpots"));
        payload.put("note", "覆盖率是「策略生命周期」可信的前提；"
                + "把直连绕过这一缺口显式写出来，比报一个漂亮的覆盖率数字更重要");
        return payload;
    }

    /** 覆盖率历史（趋势：治理是否在收敛）。 */
    public List<Map<String, Object>> coverageHistory(int limit) {
        return jdbc.queryForList("""
                SELECT measured_at, total_assets, covered_assets, uncovered_high_class
                  FROM policy_coverage ORDER BY measured_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 100));
    }

    // ------------------------------------------------------------------ 工具

    private Integer currentVersion(String name) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT version FROM policy_definition WHERE name = ?", Integer.class, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 把业务可读的策略文档翻译成编译器的 IR。 */
    @SuppressWarnings("unchecked")
    public static PolicyCompiler.PolicySpec toSpec(Map<String, Object> document) {
        Map<String, Object> resourceScope = document.get("resourceScope") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> subjectScope = document.get("subjectScope") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> condition = document.get("condition") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return new PolicyCompiler.PolicySpec(
                str(document.get("name")),
                document.get("version") == null ? 1 : ((Number) document.get("version")).intValue(),
                str(document.get("target")),
                str(document.get("effect")),
                resourceScope, subjectScope, condition,
                document.get("priority") == null ? 100 : ((Number) document.get("priority")).intValue());
    }

    @SuppressWarnings("unchecked")
    private static PolicyCompiler.PolicySpec fromRow(Map<String, Object> row) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", row.get("name"));
        document.put("version", row.get("version"));
        document.put("target", row.get("target"));
        document.put("effect", row.get("effect"));
        document.put("priority", row.get("priority"));
        document.put("resourceScope", parseJson(str(row.get("resource_scope"))));
        document.put("subjectScope", parseJson(str(row.get("subject_scope"))));
        document.put("condition", parseJson(str(row.get("condition"))));
        return toSpec(document);
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

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
