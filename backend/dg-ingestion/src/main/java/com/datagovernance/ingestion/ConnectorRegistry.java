package com.datagovernance.ingestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.ingestion.connectors.BigQuerySource;
import com.datagovernance.ingestion.connectors.ClickHouseSource;
import com.datagovernance.ingestion.connectors.MongoSource;
import com.datagovernance.ingestion.connectors.PostgresSource;
import com.datagovernance.ingestion.connectors.SupersetSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 连接器注册表：把「请求里的参数」翻译成具体的 {@link Source}（docs/09 §9.1）。
 *
 * <p>存在的意义：采集入口（API / 调度）只认识"source 名 + DSN + 过滤条件"，
 * 不认识任何具体连接器。新增一个源系统只需要在这里注册一行，
 * 采集编排、护栏、快照、运行记录、事件流全部自动复用。
 *
 * <p>同时它是**诚实信息的唯一出口**：{@code /api/v1/collect/sources} 从这里取
 * "哪些连接器已实现、哪些没有、实现到什么程度（是否对真实系统验证过）"。
 */
@Service
public class ConnectorRegistry {

    private final JdbcTemplate jdbc;

    public ConnectorRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次采集请求的连接信息。 */
    public record ConnectorRequest(
            String source,
            String dsn,
            String namespace,
            List<String> databases,
            List<String> schemas,
            List<String> tables,
            Integer sampleSize) {
    }

    /** 已实现的连接器（含"是否对真实系统验证过"的诚实标注）。 */
    public List<Map<String, Object>> implemented() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(info("postgres", "PostgreSQL", "dataset", true,
                "information_schema + obj_description/col_description，含主键与注释",
                "postgresql://user:pass@host:5432/db 或 jdbc:postgresql://..."));
        out.add(info("clickhouse", "ClickHouse", "dataset", true,
                "system.tables + system.columns（HTTP 接口 8123）；引擎/分区键/排序键写入注释；"
                        + "total_rows 标注为估算值",
                "clickhouse://user:pass@host:8123"));
        out.add(info("mongodb", "MongoDB", "dataset", true,
                "无 schema → 采样推断：并集多个文档、嵌套对象下钻为点号路径、"
                        + "类型不稳定记为 mixed(...)、稀疏字段写入覆盖度",
                "mongodb://user:pass@host:27017/?authSource=admin"));
        out.add(info("bigquery", "BigQuery", "dataset", false,
                "BigQuery REST v2（自签 RS256 JWT 换 token，不引入 google-cloud SDK）；"
                        + "**尚未对真实 BigQuery 项目验证**（无凭据），模拟器可验证",
                "bigquery://project?credentials=/path/sa.json"));
        out.add(info("superset", "Apache Superset", "dashboard", true,
                "REST /api/v1/dashboard + /api/v1/dataset；产出仪表板实体与 "
                        + "Dashboard→Dataset 的 readsFrom 边；虚拟数据集不猜血缘（交给 sqlglot）",
                "superset://user:pass@host:8088"));
        return out;
    }

    /** 未实现的连接器（按 docs/11 §1.1 的白名单，按需共建）。 */
    public Map<String, Object> notImplemented() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mysql", Map.of("note", "计划中：与 PostgreSQL 连接器结构相同，信息模式查询可复用"));
        out.put("trino", Map.of("note", "计划中：可复用 trino 的 information_schema + 系统表"));
        out.put("hive-hms", Map.of("note", "计划中：需要 HMS Thrift 客户端"));
        out.put("dbt", Map.of("note", "计划中：读 manifest.json（编译期血缘最准），与契约/测试天然同源"));
        out.put("airflow", Map.of("note", "计划中：REST API 读 DAG 与任务依赖"));
        out.put("sqlite", Map.of("note", "计划中：Python 参考实现已有（sqlite_master + PRAGMA）"));
        out.put("duckdb", Map.of("note", "计划中：Python 参考实现已有（含 Parquet/CSV 裸文件 schema 推断）"));
        out.put("tableau", Map.of("note", "计划中：需要 Tableau REST + 个人访问令牌"));
        return out;
    }

    private static Map<String, Object> info(String id, String displayName, String assetKind,
                                            boolean verifiedAgainstRealSystem, String note,
                                            String dsnExample) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("displayName", displayName);
        out.put("assetKind", assetKind);
        out.put("verifiedAgainstRealSystem", verifiedAgainstRealSystem);
        out.put("note", note);
        out.put("dsnExample", dsnExample);
        return out;
    }

    /** 按请求构造连接器。 */
    public Source create(ConnectorRequest request) {
        String source = request.source() == null ? "" : request.source().trim().toLowerCase(java.util.Locale.ROOT);
        String dsn = request.dsn();
        if (dsn == null || dsn.isBlank()) {
            throw new IllegalArgumentException("缺少 dsn（连接串）");
        }
        List<String> databases = request.databases() == null ? List.of() : request.databases();
        List<String> schemas = request.schemas() == null ? List.of() : request.schemas();
        List<String> tables = request.tables() == null ? List.of() : request.tables();

        return switch (source) {
            case "postgres", "postgresql", "jdbc" -> {
                com.datagovernance.ingestion.schedule.JdbcTarget target =
                        com.datagovernance.ingestion.schedule.JdbcTarget.parse(dsn);
                yield new PostgresSource(target.jdbcUrl(), target.username(), target.password(),
                        schemas.isEmpty() ? databases : schemas, tables);
            }
            case "clickhouse" -> ClickHouseSource.fromDsn(dsn,
                    databases.isEmpty() ? schemas : databases, tables);
            case "mongodb", "mongo" -> MongoSource.fromDsn(dsn, databases, tables, request.sampleSize());
            case "bigquery", "bq" -> BigQuerySource.fromDsn(dsn, databases, tables);
            case "superset" -> SupersetSource.fromDsn(dsn, request.namespace(),
                    (schema, table) -> resolveDatasetUrn(schema, table, request.namespace()), tables);
            default -> throw new IllegalArgumentException("不支持的 source：" + source
                    + "（已实现：" + implemented().stream().map(item -> item.get("id")).toList() + "）");
        };
    }

    /**
     * 把 Superset 的 {@code schema.table} 解析为平台内的 Dataset URN。
     *
     * <p>解析顺序（**只在唯一命中时返回**，否则返回 null —— 报表血缘宁可缺边也不猜错）：
     * <ol>
     *   <li>先在本命名空间内找：同一个表被采到多个命名空间是常见情况（例如同一套 Superset 元数据库
     *       被两个域各采一次），此时"全库唯一"会直接退化成歧义；</li>
     *   <li>本命名空间内没有，再退回全库唯一匹配。</li>
     * </ol>
     */
    private String resolveDatasetUrn(String schema, String tableName, String namespace) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        String suffix = (schema == null || schema.isBlank() ? "" : schema + ".") + tableName;
        if (namespace != null && !namespace.isBlank()) {
            List<String> scoped = jdbc.queryForList("""
                    SELECT urn FROM entity
                     WHERE entity_type = 'Dataset' AND deleted_at IS NULL
                       AND namespace = ? AND urn LIKE ?
                    """, String.class, namespace, "%." + suffix);
            if (scoped.size() == 1) {
                return scoped.get(0);
            }
            if (scoped.size() > 1) {
                // 同一命名空间内也不唯一：不猜
                return null;
            }
        }
        List<String> rows = jdbc.queryForList("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Dataset' AND deleted_at IS NULL AND urn LIKE ?
                """, String.class, "%." + suffix);
        return rows.size() == 1 ? rows.get(0) : null;
    }
}
