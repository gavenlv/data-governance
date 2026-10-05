package com.datagovernance.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

/**
 * 语义层 / 指标层接入（docs/13 §6、docs/09 §9.3 的"指标口径可追溯到物理列"）。
 *
 * <p>解决的问题：业务问"GMV 到底怎么算的"，答案散在 SQL、wiki 和某个人脑子里。
 * 本类把**指标定义**接进治理平台，并建立 {@code 指标 → 物理列} 的血缘，
 * 于是"改这一列会影响哪些指标"可以被直接回答（复用血缘影响分析）。
 *
 * <p>支持两种来源格式：
 * <ul>
 *   <li>{@code dbt}：dbt Semantic Layer 的 semantic models（{@code metrics:} + {@code measures:}）</li>
 *   <li>{@code cube}：Cube 的 {@code cubes:} + {@code measures:} / {@code dimensions:}</li>
 * </ul>
 * 解析是**宽容但显式**的：认不出的字段会被列进 {@code unrecognized}，而不是静默丢弃
 * （静默丢弃会让人以为"指标定义已经很完整了"）。
 */
@Service
public class SemanticLayerService {

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;

    public SemanticLayerService(JdbcTemplate jdbc, MetadataService metadata) {
        this.jdbc = jdbc;
        this.metadata = metadata;
    }

    /**
     * 接入一个语义层定义文件。
     *
     * @param yamlText    文件内容（dbt semantic models 或 Cube cubes）
     * @param namespace   命名空间（用于构造 Metric 实体 URN 与列 URN 解析）
     * @param sourceFormat dg | dbt | cube
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingest(String yamlText, String namespace, String sourceFormat, String actor) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new AiException("语义层定义不能为空");
        }
        String format = sourceFormat == null || sourceFormat.isBlank()
                ? "dg" : sourceFormat.trim().toLowerCase(java.util.Locale.ROOT);
        // 明确校验而不是让它掉到模型校验里：模型校验的报错是"metricSpec.sourceFormat 期望 enum"，
        // 对调用者来说看不出是自己传错了 sourceFormat
        if (!List.of("dg", "dbt", "cube").contains(format)) {
            throw new AiException("不支持的 sourceFormat：" + format + "（dg / dbt / cube）");
        }
        Object parsed = new Yaml().load(yamlText);
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new AiException("语义层定义的顶层应为映射（含 models / cubes / metrics 之一）");
        }
        List<Map<String, Object>> rawMetrics = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();

        if (document.get("models") instanceof List<?> models) {
            // dbt：models[].name + models[].columns[].name + models[].metrics[].{name, type, expr}
            for (Object item : models) {
                if (!(item instanceof Map<?, ?> model)) {
                    continue;
                }
                String modelName = str(model.get("name"));
                List<String> columns = new ArrayList<>();
                if (model.get("columns") instanceof List<?> cols) {
                    for (Object col : cols) {
                        if (col instanceof Map<?, ?> map && map.get("name") != null) {
                            columns.add(String.valueOf(map.get("name")));
                        }
                    }
                }
                if (model.get("metrics") instanceof List<?> metrics) {
                    for (Object metric : metrics) {
                        if (!(metric instanceof Map<?, ?> map)) {
                            continue;
                        }
                        rawMetrics.add(new LinkedHashMap<>(Map.of(
                                "name", String.valueOf(map.get("name")),
                                "description", map.get("description") == null ? "" : String.valueOf(map.get("description")),
                                "expression", map.get("expr") == null ? "" : String.valueOf(map.get("expr")),
                                "type", map.get("type") == null ? "SIMPLE" : String.valueOf(map.get("type")),
                                "model", modelName == null ? "" : modelName,
                                "columns", columns,
                                "dimensions", model.get("dimensions") == null ? List.of() : model.get("dimensions"))));
                    }
                }
            }
            for (Object key : document.keySet()) {
                String name = String.valueOf(key);
                if (!List.of("version", "models").contains(name)) {
                    unrecognized.add("顶层字段 " + name + "（本实现未处理，未静默忽略）");
                }
            }
        } else if (document.get("cubes") instanceof List<?> cubes) {
            for (Object item : cubes) {
                if (!(item instanceof Map<?, ?> cube)) {
                    continue;
                }
                String cubeName = str(cube.get("name"));
                String sqlTable = str(cube.get("sql_table"));
                String[] parts = sqlTable == null ? new String[0] : sqlTable.split("\\.");
                String modelName = parts.length > 0 ? parts[parts.length - 1] : cubeName;
                if (cube.get("measures") instanceof List<?> measures) {
                    for (Object measure : measures) {
                        if (!(measure instanceof Map<?, ?> map)) {
                            continue;
                        }
                        rawMetrics.add(new LinkedHashMap<>(Map.of(
                                "name", String.valueOf(map.get("name")),
                                "description", map.get("description") == null ? "" : String.valueOf(map.get("description")),
                                "expression", map.get("sql") == null ? "" : String.valueOf(map.get("sql")),
                                "type", map.get("type") == null ? "SIMPLE" : String.valueOf(map.get("type")),
                                "model", modelName == null ? "" : modelName,
                                "columns", List.of(),
                                "dimensions", cube.get("dimensions") == null ? List.of() : cube.get("dimensions"))));
                    }
                }
            }
        } else if (document.get("metrics") instanceof List<?> metrics) {
            // 平台自有格式：直接给指标（可选 physicalColumns）
            for (Object metric : metrics) {
                if (metric instanceof Map<?, ?> map) {
                    // 显式逐键拷贝：`new LinkedHashMap<>(map)` 对 Map<?,?> 无法推断出
                    // Map<String,Object>（通配符捕获），会直接编译失败
                    Map<String, Object> entry = new LinkedHashMap<>();
                    map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    rawMetrics.add(entry);
                }
            }
        } else {
            throw new AiException("未识别的语义层格式：期望含 models（dbt）/ cubes（Cube）/ metrics（平台格式）");
        }

        List<Map<String, Object>> ingested = new ArrayList<>();
        List<String> unresolvedColumns = new ArrayList<>();
        for (Map<String, Object> metric : rawMetrics) {
            String name = str(metric.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            // 平台自有格式用 physicalColumns（与 metricSpec 字段同名），dbt/Cube 格式用模型里的 columns；
            // 两者都接受，避免"同一个概念在接入层与模型层各叫一个名字"
            List<String> columns = stringList(metric.get("physicalColumns"));
            if (columns.isEmpty()) {
                columns = stringList(metric.get("columns"));
            }
            List<String> columnUrns = new ArrayList<>();
            String modelName = str(metric.get("model"));

            // 列级血缘：指标依赖的列必须能解析到平台 URN，解析不到就明确报告（不猜）
            for (String column : columns) {
                String urn = resolveColumnUrn(namespace, modelName, column);
                if (urn == null) {
                    unresolvedColumns.add(modelName + "." + column);
                } else {
                    columnUrns.add(urn);
                }
            }

            // text[] 不能用 setObject(String[]) 绑：PG JDBC 会抛 SQLFeatureNotSupportedException，
            // 必须走 createArrayOf（这是本项目里第三次踩同一个坑，故此处显式写清楚）
            final String metricName = name;
            final String metricDescription = str(metric.get("description"));
            final String metricType = normalizeType(str(metric.get("type")));
            final String metricExpression = str(metric.get("expression"));
            final String dimensionsJson = toJson(
                    metric.get("dimensions") == null ? List.of() : metric.get("dimensions"));
            final String sourceRef = modelName;
            jdbc.update(connection -> {
                java.sql.PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO metric_definition (name, display_name, description, metric_type, expression,
                                                       physical_columns, dimensions, source_format, source_ref, owner)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                        ON CONFLICT (name) DO UPDATE
                            SET display_name = EXCLUDED.display_name, description = EXCLUDED.description,
                                metric_type = EXCLUDED.metric_type, expression = EXCLUDED.expression,
                                physical_columns = EXCLUDED.physical_columns, dimensions = EXCLUDED.dimensions,
                                source_format = EXCLUDED.source_format, source_ref = EXCLUDED.source_ref,
                                updated_at = now()
                        """);
                ps.setString(1, metricName);
                ps.setString(2, metricName);
                ps.setString(3, metricDescription);
                ps.setString(4, metricType);
                ps.setString(5, metricExpression);
                ps.setArray(6, connection.createArrayOf("text", columnUrns.toArray()));
                ps.setString(7, dimensionsJson);
                ps.setString(8, format);
                ps.setString(9, sourceRef);
                ps.setString(10, actor);
                return ps;
            });

            // 指标作为一等实体：可被检索、可被血缘引用
            String metricUrn = metricUrn(namespace, name);
            metadata.ensureEntity(metricUrn, "Metric", name, null);
            metadata.upsertAspect(metricUrn, "metricSpec", Map.of(
                    "metricName", name,
                    "metricType", normalizeType(str(metric.get("type"))),
                    "expression", str(metric.get("expression")) == null ? "" : str(metric.get("expression")),
                    "sourceFormat", format,
                    "physicalColumns", columnUrns), "MANUAL", null, null);
            for (String columnUrn : columnUrns) {
                // 血缘方向：from=上游（列）→ to=下游（指标），与全局约定一致
                metadata.upsertEdge(columnUrn, metricUrn, "consumedBy", "manual", 1.0,
                        null, null, null, "VALUE", "exact", null, null);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("urn", metricUrn);
            item.put("metricType", normalizeType(str(metric.get("type"))));
            item.put("physicalColumns", columnUrns);
            ingested.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceFormat", format);
        payload.put("ingested", ingested.size());
        payload.put("metrics", ingested);
        payload.put("unresolvedColumns", unresolvedColumns.stream().distinct().toList());
        payload.put("unresolvedNote", unresolvedColumns.isEmpty() ? null
                : "这些列没解析到平台 URN，因此没有建立 指标→列 血缘（宁可缺边也不猜）。"
                        + "常见原因：对应数据集还没采集，或语义层模型名与平台表名不一致");
        payload.put("unrecognized", unrecognized);
        payload.put("howToTrace", "问「这个指标怎么算的」→ GET /api/v1/ai/semantic-layer/metrics/{name}；"
                + "问「改这列影响哪些指标」→ GET /api/v1/lineage/impact/{columnUrn}");
        return payload;
    }

    /** 指标清单（含依赖列，可追溯口径）。 */
    public List<Map<String, Object>> listMetrics() {
        return jdbc.queryForList("""
                SELECT name, display_name, description, metric_type, expression, physical_columns,
                       dimensions, source_format, source_ref, owner, status, updated_at
                  FROM metric_definition WHERE status <> 'RETIRED' ORDER BY name
                """);
    }

    /** 单个指标的完整追溯信息：定义 + 依赖列 + 下游影响面（直接复用血缘影响分析）。 */
    public Map<String, Object> metric(String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM metric_definition WHERE name = ?", name);
        if (rows.isEmpty()) {
            throw new MetadataException.NotFound("指标不存在：" + name);
        }
        Map<String, Object> payload = new LinkedHashMap<>(rows.get(0));
        payload.put("columns", jdbc.queryForList("""
                SELECT e.urn, e.display_name, en.display_name AS dataset_name
                  FROM metric_definition m
                  CROSS JOIN LATERAL unnest(m.physical_columns) AS u(urn)
                  LEFT JOIN entity e ON e.urn = u.urn
                  LEFT JOIN entity en ON en.urn = split_part(u.urn, '.' || e.display_name, 1)
                 WHERE m.name = ?
                """, name));
        payload.put("note", "指标口径 = 表达式 + 依赖列；依赖列可继续查它的上游数据集，"
                + "因此「指标数字不对」可以沿血缘回溯到具体表和列");
        return payload;
    }

    // ------------------------------------------------------------------ 工具

    private static String metricUrn(String namespace, String name) {
        return UrnUtils.build("Metric", namespace == null || namespace.isBlank() ? "prod" : namespace,
                UrnUtils.sanitizeSegment(name));
    }

    /**
     * 把 {@code model.column} 解析为平台的列 URN。
     *
     * <p>与血缘/契约里的解析保持一致：**只在唯一命中时返回**，否则返回 null（不猜）。
     */
    private String resolveColumnUrn(String namespace, String modelName, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        String suffix = modelName == null || modelName.isBlank()
                ? "." + column
                : "." + modelName + "." + column;
        List<String> rows = jdbc.queryForList("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Column' AND deleted_at IS NULL AND urn LIKE ?
                   AND (CAST(? AS text) IS NULL OR namespace = ?)
                """, String.class, "%" + suffix, namespace, namespace);
        if (rows.size() == 1) {
            return rows.get(0);
        }
        if (!rows.isEmpty()) {
            return null;  // 歧义：不猜
        }
        // 退一步：只按列名匹配（语义层模型名与平台表名不一致时的常见情形），仍要求唯一
        List<String> byName = jdbc.queryForList("""
                SELECT urn FROM entity
                 WHERE entity_type = 'Column' AND deleted_at IS NULL AND display_name = ?
                   AND (CAST(? AS text) IS NULL OR namespace = ?)
                """, String.class, column, namespace, namespace);
        return byName.size() == 1 ? byName.get(0) : null;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "SIMPLE";
        }
        String upper = type.toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "SIMPLE", "RATIO", "DERIVED", "CUMULATIVE" -> upper;
            case "SUM", "COUNT", "AVG", "MIN", "MAX", "COUNT_DISTINCT" -> "SIMPLE";
            case "NUMBER" -> "SIMPLE";
            default -> "SIMPLE";
        };
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
