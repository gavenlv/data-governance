package com.datagovernance.ingestion.datasource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.datagovernance.core.MetadataException;
import com.datagovernance.ingestion.CollectionRun;
import com.datagovernance.ingestion.CollectionService;
import com.datagovernance.ingestion.ConnectorRegistry;
import com.datagovernance.ingestion.GuardConfig;
import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据源管理（docs/09 §9.1 的连接层）。
 *
 * <p>要解决的问题很具体：{@code POST /api/v1/collect/run} 每次都要重新提供完整 DSN
 * （其中含口令）。于是"每天扫一次生产库"变成"每天去找一次口令"，
 * 结果是口令被抄进脚本、记事本、聊天记录 —— 比不加密更糟。
 * 本服务把连接变成**一次录入、反复复用**的实体。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>凭据只以密文落库</b>（{@link SecretCipher}），密钥不在库里；
 *       未配置密钥时 {@code save} 直接失败，不退化成明文。</li>
 *   <li><b>接口永不回显凭据</b>：{@code list}/{@code get} 只返回 {@code hasCredentials}
 *       与<b>已脱敏</b>的 endpoint，密文与明文都不出网。</li>
 *   <li><b>扫描复用既有采集链路</b>：{@link ConnectorRegistry} + {@link CollectionService}
 *       + {@link GuardConfig}，护栏、快照、运行记录、事件流全部照旧生效 ——
 *       数据源管理只是"连接从哪里来"的变化，不是第二条采集路径。</li>
 * </ol>
 */
@Service
public class DataSourceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ConnectorRegistry connectors;
    private final CollectionService collection;
    private final SecretCipher cipher;

    public DataSourceService(JdbcTemplate jdbc, ConnectorRegistry connectors,
                             CollectionService collection, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.connectors = connectors;
        this.collection = collection;
        this.cipher = cipher;
    }

    /** 一次保存请求（创建与编辑共用）。 */
    public record SaveRequest(
            String name,
            String connector,
            String namespace,
            String dsn,
            String jdbcUrl,
            String username,
            String password,
            List<String> databases,
            List<String> schemas,
            List<String> tables,
            Integer sampleSize) {
    }

    /** 加密存储的**仅凭据**部分（非密字段一律走普通列，缩小明文面）。 */
    private record Secrets(String dsn, String jdbcUrl, String username, String password) {

        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsn", dsn);
            out.put("jdbcUrl", jdbcUrl);
            out.put("username", username);
            out.put("password", password);
            return out;
        }

        static Secrets fromJson(String json) {
            if (json == null || json.isBlank()) {
                return new Secrets(null, null, null, null);
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = MAPPER.readValue(json, Map.class);
                return new Secrets(text(raw.get("dsn")), text(raw.get("jdbcUrl")),
                        text(raw.get("username")), text(raw.get("password")));
            } catch (Exception e) {
                throw new IllegalStateException("凭据明文解析失败：" + e.getMessage(), e);
            }
        }

        private static String text(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }

    // ------------------------------------------------------------------ 读

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT id, name, connector, namespace, endpoint, databases, schemas, tables,
                       sample_size, created_by, created_at, updated_at,
                       last_scan_at, last_scan_run_id, last_scan_status
                  FROM data_source ORDER BY updated_at DESC, name
                """).stream().map(DataSourceService::toRow).toList();
    }

    public Map<String, Object> get(String id) {
        return toRow(require(id));
    }

    /** 密钥状态：界面据此解释"为什么保存按钮报错"，而不是让人去翻日志。 */
    public Map<String, Object> cipherStatus() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configured", cipher.configured());
        payload.put("algorithm", "AES-256-GCM");
        payload.put("keySource", "环境变量 DG_SECRET_KEY（密钥不在库内）");
        if (!cipher.configured()) {
            payload.put("reason", cipher.notConfiguredReason());
            payload.put("hint", "未配置密钥时保存连接会被拒绝（502），绝不明文落库");
        }
        return payload;
    }

    // ------------------------------------------------------------------ 写

    public Map<String, Object> create(SaveRequest request, String actor) {
        String name = requireName(request.name());
        String connector = requireConnector(request.connector());
        Secrets secrets = new Secrets(trim(request.dsn()), trim(request.jdbcUrl()),
                trim(request.username()), trim(request.password()));
        if (secrets.dsn() == null && secrets.jdbcUrl() == null) {
            throw new IllegalArgumentException("缺少连接信息：请提供 dsn（或 jdbcUrl + username/password）");
        }
        if (nameTaken(name, null)) {
            throw new MetadataException.Conflict("数据源名称已存在：" + name);
        }

        String id = "ds-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO data_source (id, name, connector, namespace, endpoint, databases,
                                         schemas, tables, sample_size, secret_enc, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, name, connector, trim(request.namespace()),
                sanitizeForDisplay(secrets.dsn(), secrets.jdbcUrl()),
                join(request.databases()), join(request.schemas()), join(request.tables()),
                request.sampleSize(), cipher.encrypt(json(secrets)), actor);
        return get(id);
    }

    /**
     * 编辑。**留空即保持原值** —— 这是"不必重新输入完整链接细节"的关键：
     * 改个命名空间或只轮换口令时，不需要重新抄一遍 DSN。
     */
    public Map<String, Object> update(String id, SaveRequest request, String actor) {
        Map<String, Object> existing = require(id);
        String name = request.name() == null || request.name().isBlank()
                ? String.valueOf(existing.get("name"))
                : requireName(request.name());
        if (nameTaken(name, id)) {
            throw new MetadataException.Conflict("数据源名称已存在：" + name);
        }
        Secrets old = Secrets.fromJson(cipher.decrypt(String.valueOf(existing.get("secret_enc"))));
        Secrets merged = new Secrets(
                coalesce(trim(request.dsn()), old.dsn()),
                coalesce(trim(request.jdbcUrl()), old.jdbcUrl()),
                coalesce(trim(request.username()), old.username()),
                coalesce(trim(request.password()), old.password()));
        if (merged.dsn() == null && merged.jdbcUrl() == null) {
            throw new IllegalArgumentException("缺少连接信息：更新后既没有 dsn 也没有 jdbcUrl");
        }

        jdbc.update("""
                UPDATE data_source
                   SET name = ?, connector = ?, namespace = ?, endpoint = ?, databases = ?,
                       schemas = ?, tables = ?, sample_size = ?, secret_enc = ?,
                       updated_at = now(), created_by = ?
                 WHERE id = ?
                """, name, requireConnector(request.connector()), trim(request.namespace()),
                sanitizeForDisplay(merged.dsn(), merged.jdbcUrl()),
                join(request.databases()), join(request.schemas()), join(request.tables()),
                request.sampleSize(), cipher.encrypt(json(merged)), actor, id);
        return get(id);
    }

    public Map<String, Object> delete(String id) {
        Map<String, Object> row = require(id);
        jdbc.update("DELETE FROM data_source WHERE id = ?", id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", row.get("name"));
        payload.put("deleted", true);
        payload.put("note", "只删除保存的连接本身；该连接采集过的资产与运行记录不受影响"
                + "（资产需要回滚请用采集批次回滚 POST /api/v1/collect/rollback）");
        return payload;
    }

    // ------------------------------------------------------------------ 测试与扫描

    /**
     * 连接测试：真的去读一次源系统（{@code extract()} 取首个数据集后立即关闭流）。
     *
     * <p>刻意<b>不写任何元数据</b>：测试连接的语义是"能不能连上"，
     * 不该顺带把半个目录写进真相源。
     *
     * <p>返回 200 + {@code ok:false} 表示"测试成功执行、结论是不通" ——
     * 这与"接口本身失败"是两件事，用 5xx 表达会让调用方无法区分
     * （前者要改连接信息，后者要重试）。
     */
    public Map<String, Object> test(String id) {
        Map<String, Object> row = require(id);
        String connector = String.valueOf(row.get("connector"));
        long startedAt = System.nanoTime();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", row.get("name"));
        payload.put("connector", connector);
        payload.put("endpoint", row.get("endpoint"));
        try {
            Source source = connectors.create(connectorRequest(row));
            String sample = "(源系统里没有发现任何数据集)";
            try (Stream<RawModels.RawDataset> stream = source.extract()) {
                Optional<RawModels.RawDataset> first = stream.findFirst();
                if (first.isPresent()) {
                    RawModels.RawDataset dataset = first.get();
                    sample = Stream.of(dataset.database(), dataset.schema(), dataset.table())
                            .filter(part -> part != null && !part.isBlank())
                            .reduce((a, b) -> a + "." + b).orElse(dataset.table());
                }
            }
            payload.put("ok", true);
            payload.put("platform", source.platform());
            payload.put("sampleDataset", sample);
            payload.put("note", "仅做只读探测，未写入任何元数据");
        } catch (RuntimeException e) {
            payload.put("ok", false);
            payload.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            payload.put("note", "连接失败不写元数据；修好连接信息后重试");
        }
        payload.put("durationMs", (System.nanoTime() - startedAt) / 1_000_000);
        return payload;
    }

    /**
     * 一键扫描：用保存的连接跑一次**完整采集**（护栏、快照、运行记录、事件流全部照旧）。
     *
     * <p>采集失败不会抛到接口层：{@link CollectionService} 会把失败写成一条 FAILED 运行记录
     * 并返回 —— 这样"扫描失败"与"扫描没跑"可区分（前者有 runId 可查），
     * 与血缘侧车"不静默返回空"是同一条纪律。
     */
    public Map<String, Object> scan(String id, String actor) {
        Map<String, Object> row = require(id);
        String namespace = blankTo(String.valueOf(row.get("namespace")), "prod");
        Source source = connectors.create(connectorRequest(row));
        CollectionRun run = collection.collect(source, namespace, GuardConfig.defaults(), true, false, false);
        jdbc.update("""
                UPDATE data_source SET last_scan_at = now(), last_scan_run_id = ?, last_scan_status = ?
                 WHERE id = ?
                """, run.runId(), run.status(), id);

        Map<String, Object> payload = new LinkedHashMap<>(run.asMap());
        payload.put("dataSourceId", id);
        payload.put("dataSourceName", row.get("name"));
        return payload;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把保存的连接翻译成采集请求。
     *
     * <p>这是本服务与采集框架的**唯一接缝**：解密在这里发生，且解密结果只活在这个方法里 ——
     * 不会被写回任何 Map、不会进日志。
     */
    private ConnectorRegistry.ConnectorRequest connectorRequest(Map<String, Object> row) {
        Secrets secrets = Secrets.fromJson(cipher.decrypt(String.valueOf(row.get("secret_enc"))));
        String dsn = secrets.dsn();
        if (dsn == null || dsn.isBlank()) {
            // 兼容入口：jdbcUrl + username/password 组装成 DSN（与 /collect/postgres 等价）
            dsn = buildJdbcDsn(secrets);
        }
        return new ConnectorRegistry.ConnectorRequest(
                String.valueOf(row.get("connector")),
                dsn,
                string(row.get("namespace")),
                split(string(row.get("databases"))),
                split(string(row.get("schemas"))),
                split(string(row.get("tables"))),
                row.get("sample_size") == null ? null : ((Number) row.get("sample_size")).intValue());
    }

    private static String buildJdbcDsn(Secrets secrets) {
        if (secrets.jdbcUrl() == null || secrets.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "该数据源既没有 dsn 也没有 jdbcUrl，无法扫描（请编辑数据源补全连接信息）");
        }
        StringBuilder out = new StringBuilder(secrets.jdbcUrl().trim());
        if (secrets.username() != null && !secrets.username().isBlank()) {
            out.append(out.indexOf("?") >= 0 ? '&' : '?').append("user=").append(secrets.username());
            if (secrets.password() != null && !secrets.password().isBlank()) {
                out.append("&password=").append(secrets.password());
            }
        }
        return out.toString();
    }

    private Map<String, Object> require(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM data_source WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("数据源不存在：" + id);
        }
        return rows.get(0);
    }

    private boolean nameTaken(String name, String exceptId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_source WHERE name = ? AND id <> COALESCE(?, '')",
                Long.class, name, exceptId);
        return count != null && count > 0;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少数据源名称 name");
        }
        return name.trim();
    }

    /** 连接器必须在 {@link ConnectorRegistry} 里真实存在 —— 否则保存下来的连接扫不了。 */
    private String requireConnector(String connector) {
        if (connector == null || connector.isBlank()) {
            throw new IllegalArgumentException("缺少连接器 connector");
        }
        String normalized = connector.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> implemented = connectors.implemented().stream()
                .map(item -> String.valueOf(item.get("id"))).toList();
        if (!implemented.contains(normalized)) {
            throw new IllegalArgumentException("不支持的连接器：" + connector
                    + "（已实现：" + implemented + "）");
        }
        return normalized;
    }

    /**
     * 生成**可对外展示**的连接描述：去掉 user:password@ 与 ?password= 一类参数。
     *
     * <p>这是"列表页不需要解密"的前提，也是最容易漏掉的一处泄露：
     * 直接把 DSN 当 endpoint 存下来，等于把密码明文写进了一个"非密列"。
     */
    static String sanitizeForDisplay(String dsn, String jdbcUrl) {
        String raw = (dsn != null && !dsn.isBlank()) ? dsn : jdbcUrl;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String out = raw.trim()
                // 去掉 //user:password@
                .replaceAll("(?i)(//)[^/@]*@", "$1")
                // 去掉 ?password=... / &token=... 等敏感查询参数
                .replaceAll("(?i)([?&](password|pwd|passwd|secret|token|apikey|api_key|key|credentials)=)[^&]*",
                        "$1****");
        return out;
    }

    private static String json(Secrets secrets) {
        try {
            return MAPPER.writeValueAsString(secrets.asMap());
        } catch (Exception e) {
            throw new IllegalStateException("凭据序列化失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> toRow(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", raw.get("id"));
        out.put("name", raw.get("name"));
        out.put("connector", raw.get("connector"));
        out.put("namespace", raw.get("namespace"));
        out.put("endpoint", raw.get("endpoint"));
        out.put("databases", split(string(raw.get("databases"))));
        out.put("schemas", split(string(raw.get("schemas"))));
        out.put("tables", split(string(raw.get("tables"))));
        out.put("sampleSize", raw.get("sample_size"));
        // 凭据是否已保存：只告知"有/无"，不回显任何密文或明文
        boolean hasSecret = raw.get("secret_enc") != null
                && !String.valueOf(raw.get("secret_enc")).isBlank();
        out.put("hasCredentials", hasSecret);
        out.put("createdBy", raw.get("created_by"));
        out.put("createdAt", instant(raw.get("created_at")));
        out.put("updatedAt", instant(raw.get("updated_at")));
        out.put("lastScanAt", instant(raw.get("last_scan_at")));
        out.put("lastScanRunId", raw.get("last_scan_run_id"));
        out.put("lastScanStatus", raw.get("last_scan_status"));
        return out;
    }

    private static Object instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String coalesce(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() || "null".equals(value) ? fallback : value;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).toList();
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Arrays.asList(value.split(",")));
        return out.stream().map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}