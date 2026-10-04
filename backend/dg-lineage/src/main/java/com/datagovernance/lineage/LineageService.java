package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import com.datagovernance.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 血缘服务（docs/09 §9.2）。
 *
 * <p>两类输入：
 * <ol>
 *   <li><b>OpenLineage 运行时事件</b>（高置信度 0.95）—— 作业真实读写；</li>
 *   <li><b>SQL 静态解析</b>（置信度 0.8）—— 由 Python/sqlglot 侧车产出，
 *       见 {@link SqlParseSidecarClient}。</li>
 * </ol>
 *
 * <p>⚠️ <b>方向反转</b>：OpenLineage 的 columnLineage facet 是「输出列 → 输入列」，
 * 而平台约定 from=上游、to=下游，接入时必须显式反转（docs/08 §7.1）。
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);
    public static final String SOURCE_OPENLINEAGE = "openlineage";
    public static final String SOURCE_SQL_PARSE = "sql_parse";

    private final JdbcTemplate jdbc;
    private final MetadataService metadata;
    private final ModelRegistry registry;

    public LineageService(JdbcTemplate jdbc, MetadataService metadata, ModelRegistry registry) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.registry = registry;
    }

    /**
     * 接收一条 OpenLineage RunEvent，写入数据集级与列级血缘。
     *
     * @param event 形如 {job:{namespace,name}, run:{runId}, inputs:[...], outputs:[...]}
     * @return 写入统计
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestOpenLineage(Map<String, Object> event, String namespace) {
        Map<String, Object> job = (Map<String, Object>) event.getOrDefault("job", Map.of());
        Map<String, Object> run = (Map<String, Object>) event.getOrDefault("run", Map.of());
        String jobUrn = UrnUtils.build("Pipeline", namespace,
                UrnUtils.sanitizeSegment(String.valueOf(job.getOrDefault("namespace", "default"))),
                UrnUtils.sanitizeSegment(String.valueOf(job.getOrDefault("name", "unknown"))));
        String runId = String.valueOf(run.getOrDefault("runId", "unknown"));

        metadata.ensureEntity(jobUrn, "Pipeline",
                String.valueOf(job.getOrDefault("name", "unknown")), runId);

        List<Map<String, Object>> inputs = list(event.get("inputs"));
        List<Map<String, Object>> outputs = list(event.get("outputs"));

        int tableEdges = 0;
        int columnEdges = 0;

        for (Map<String, Object> output : outputs) {
            String downstream = datasetUrn(output, namespace);
            metadata.ensureEntity(downstream, "Dataset", nameOf(output), runId);

            for (Map<String, Object> input : inputs) {
                String upstream = datasetUrn(input, namespace);
                metadata.ensureEntity(upstream, "Dataset", nameOf(input), runId);
                metadata.upsertEdge(upstream, downstream, "derivesFrom", SOURCE_OPENLINEAGE,
                        0.95, null, null, null, "VALUE", "exact", jobUrn, runId);
                tableEdges++;
            }

            // 列级：facet 方向是「输出列 → 输入列」，这里反转为 上游 → 下游
            Map<String, Object> facet = columnLineageFacet(inputs.isEmpty() ? output : inputs.get(0));
            columnEdges += ingestColumnLineage(facet, downstream, namespace, jobUrn, runId);
        }
        return Map.of("jobUrn", jobUrn, "tableEdges", tableEdges, "columnEdges", columnEdges,
                "inputs", inputs.size(), "outputs", outputs.size());
    }

    @SuppressWarnings("unchecked")
    private int ingestColumnLineage(Map<String, Object> facet, String downstreamDataset,
                                    String namespace, String jobUrn, String runId) {
        Object fieldsObj = facet.get("fields");
        if (!(fieldsObj instanceof Map<?, ?> fields)) {
            return 0;
        }
        int edges = 0;
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String outputColumn = String.valueOf(entry.getKey());
            Object specObj = entry.getValue();
            if (!(specObj instanceof Map<?, ?> spec)) {
                continue;
            }
            Object inputFields = spec.get("inputFields");
            if (!(inputFields instanceof List<?> inputs)) {
                continue;
            }
            for (Object item : inputs) {
                if (!(item instanceof Map<?, ?> input)) {
                    continue;
                }
                String upstreamName = str(input, "name", "");
                String upstreamColumn = str(input, "field", "");
                if (upstreamName.isBlank() || upstreamColumn.isBlank()) {
                    continue;
                }
                String upstreamDataset = upstreamName.startsWith("urn:dg:")
                        ? upstreamName
                        : buildOpenLineageDatasetUrn(namespace, upstreamName);

                String transform = transformOf(input);
                metadata.ensureEntity(upstreamDataset, "Dataset", upstreamName, runId);
                metadata.upsertEdge(
                        UrnUtils.column(upstreamDataset, upstreamColumn),
                        UrnUtils.column(downstreamDataset, outputColumn),
                        "derivesFrom", SOURCE_OPENLINEAGE, 0.95,
                        transform, null, "ONE_TO_ONE", "VALUE", "exact", jobUrn, runId);
                edges++;
            }
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private static String transformOf(Map<?, ?> input) {
        Object transformations = input.get("transformations");
        if (transformations instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object subtype = first.get("subtype");
            if (subtype != null && String.valueOf(subtype).toLowerCase().startsWith("mask")) {
                return "MASKED";
            }
            Object type = first.get("type");
            return type != null && "DIRECT".equalsIgnoreCase(String.valueOf(type)) ? "DIRECT" : "INDIRECT";
        }
        return "DIRECT";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> columnLineageFacet(Map<String, Object> dataset) {
        Object facets = dataset.get("facets");
        if (facets instanceof Map<?, ?> map) {
            Object value = ((Map<String, Object>) map).get("columnLineage");
            if (value instanceof Map<?, ?> facet) {
                return (Map<String, Object>) facet;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> items
                ? items.stream().filter(Map.class::isInstance).map(i -> (Map<String, Object>) i).toList()
                : List.of();
    }

    private static String nameOf(Map<String, Object> dataset) {
        return String.valueOf(dataset.getOrDefault("name", "unknown"));
    }

    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 把 OpenLineage 的数据集名（如 {@code db.schema.table}）转成平台 Dataset URN。 */
    private static String buildOpenLineageDatasetUrn(String namespace, String name) {
        String[] parts = name.split("\\.");
        List<String> segments = new ArrayList<>();
        segments.add("openlineage");
        java.util.Arrays.stream(parts).map(UrnUtils::sanitizeSegment).forEach(segments::add);
        while (segments.size() < 5) {
            segments.add("default");
        }
        List<String> withNamespace = new ArrayList<>();
        withNamespace.add(namespace);
        withNamespace.addAll(segments);
        return UrnUtils.build("Dataset", withNamespace.toArray(String[]::new));
    }

    private static String datasetUrn(Map<String, Object> dataset, String namespace) {
        String name = nameOf(dataset);
        if (name.startsWith("urn:dg:")) {
            return name;
        }
        String platform = String.valueOf(dataset.getOrDefault("namespace", "unknown"));
        String[] parts = name.split("\\.");
        List<String> segments = new ArrayList<>();
        segments.add(UrnUtils.sanitizeSegment(platform));
        java.util.Arrays.stream(parts).map(UrnUtils::sanitizeSegment).forEach(segments::add);
        while (segments.size() < 5) {
            segments.add("default");
        }
        List<String> withNamespace = new ArrayList<>();
        withNamespace.add(namespace);
        withNamespace.addAll(segments);
        return UrnUtils.build("Dataset", withNamespace.toArray(String[]::new));
    }

    // -------------------------------------------------------------- 查询

    /** 血缘质量报告：回答「覆盖率为什么低」（没采集 vs 解析不出来）。 */
    public Map<String, Object> quality() {
        List<Map<String, Object>> bySource = jdbc.queryForList("""
                SELECT source, dependency_kind, count(*) AS edges FROM edge
                 WHERE edge_type = 'derivesFrom' GROUP BY source, dependency_kind ORDER BY edges DESC
                """);
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM edge WHERE edge_type='derivesFrom' AND state='ACTIVE'", Integer.class);
        Integer columnEdges = jdbc.queryForObject("""
                SELECT count(*) FROM edge
                 WHERE edge_type='derivesFrom' AND state='ACTIVE' AND from_urn LIKE '%:Column:%'
                """, Integer.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("edgeSources", bySource);
        out.put("activeLineageEdges", total == null ? 0 : total);
        out.put("activeColumnEdges", columnEdges == null ? 0 : columnEdges);
        out.put("parseSamples", jdbc.queryForList("""
                SELECT dialect, parse_level, sum(occurrences) AS total, count(*) AS distinct_sql
                  FROM lineage_parse_sample GROUP BY dialect, parse_level ORDER BY total DESC LIMIT 20
                """));
        out.put("sqlglotSidecarReady", false);
        out.put("sidecarNote", "SQL 静态解析由 Python(sqlglot) 侧车提供，当前未部署（见 docs/21）");
        return out;
    }
}
