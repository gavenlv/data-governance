package com.datagovernance.ingestion.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import org.yaml.snakeyaml.Yaml;

/**
 * dbt 连接器：读 {@code target/manifest.json}，产出模型 / 源表 / 种子 / 快照，
 * 以及**编译期确定的血缘**（docs/09 §9.1、docs/11 §1.1）。
 *
 * <p>为什么 dbt 值得单独做（而不是"再多一个 JDBC 连接器"）：
 * <ol>
 *   <li><b>血缘是现成的、且最准</b>：{@code manifest.json} 里 {@code depends_on.nodes}
 *       是 dbt 编译期解析出来的依赖。<b>不需要解析 SQL</b>，因此不受方言、宏、动态 SQL 影响 ——
 *       而 SQL 静态解析恰恰是血缘覆盖率的最大瓶颈；</li>
 *   <li><b>描述与语义都在里面</b>：模型的 {@code description}、列的 {@code description}、
 *       {@code meta}、{@code tags} 是数据团队已经写好的东西，直接进目录就有人用
 *       （这比让平台"生成"描述靠谱得多）；</li>
 *   <li><b>dbt 项目本身就是治理的入口</b>：把 dbt 的模型作为一等资产，
 *       才谈得上"改了模型影响哪些下游"。</li>
 * </ol>
 *
 * <p>不做的事（诚实边界）：
 * <ul>
 *   <li><b>不执行 dbt</b>：只读已编译的 manifest，不跑 {@code dbt compile}（那属于 CI）；</li>
 *   <li><b>不做列级血缘</b>：manifest 的依赖只到表级。列级需要把 {@code compiled_code}
 *       交给 sqlglot 侧车（{@code POST /api/v1/lineage/parse}），平台已提供该接口；</li>
 *   <li><b>不解析 exposures / metrics</b>：exposures 是"看板依赖模型"，
 *       语义与 BI 资产不同（且已在 {@code ai.semantic-layer} 里有指标接入路径）。</li>
 * </ul>
 *
 * <p>DSN 形态：{@code dbt:///abs/path/to/project}（自动找 {@code target/manifest.json}）
 * 或直接指向文件：{@code dbt:///abs/path/to/manifest.json}。
 */
public class DbtManifestSource implements Source {

    /** 把 dbt 的 database.schema.alias 解析成平台 URN（由注册表注入，与其它连接器共用同一规则）。 */
    public interface DatasetResolver {
        String resolve(String database, String schema, String table);
    }

    private final Path manifestPath;
    private final String namespace;
    private final DatasetResolver resolver;
    private final List<String> filters;
    private final List<String> skipNotes = new ArrayList<>();

    private Map<String, Object> manifest;
    /** unique_id → 解析出的 URN（用于建血缘；解析不到的不进 map）。 */
    private final Map<String, String> urnByUniqueId = new LinkedHashMap<>();

    public DbtManifestSource(Path manifestPath, String namespace, DatasetResolver resolver,
                             List<String> filters) {
        this.manifestPath = manifestPath;
        this.namespace = namespace;
        this.resolver = resolver;
        this.filters = filters == null ? List.of() : filters;
    }

    /**
     * 从 DSN 建连接器。
     *
     * <p>路径可以是项目目录（自动找 {@code target/manifest.json}）或 manifest 文件本身；
     * 都找不到时给出**可操作的错误**（提示先跑 dbt compile），而不是空结果 ——
     * "没有模型"与"manifest 不存在"是完全不同的两件事。
     */
    public static DbtManifestSource fromDsn(String dsn, String namespace, DatasetResolver resolver,
                                            List<String> filters) {
        String raw = dsn == null ? "" : dsn.trim();
        if (raw.startsWith("dbt://")) {
            raw = raw.substring("dbt://".length());
        } else if (raw.startsWith("dbt:")) {
            raw = raw.substring("dbt:".length());
        }
        // dbt:///abs/path 会留下前导斜杠（三斜杠 = 空 host + 绝对路径）
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("dbt DSN 需要给出路径：dbt:///path/to/project 或 dbt:///path/to/manifest.json");
        }
        Path candidate = Path.of(raw).toAbsolutePath().normalize();
        Path manifest = candidate;
        if (Files.isDirectory(candidate)) {
            manifest = candidate.resolve("target").resolve("manifest.json");
        }
        if (!Files.exists(manifest)) {
            throw new IllegalArgumentException("找不到 dbt manifest：" + manifest
                    + "（先在该项目执行 dbt compile 生成 target/manifest.json，"
                    + "或把 DSN 直接指向 manifest.json 文件）");
        }
        return new DbtManifestSource(manifest, namespace, resolver, filters);
    }

    @Override
    public String name() {
        return "dbt";
    }

    @Override
    public String platform() {
        return "dbt";
    }

    @Override
    public List<String> edgeSkipNotes() {
        return List.copyOf(skipNotes);
    }

    // ------------------------------------------------------------------ 读取

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        if (manifest != null) {
            return manifest;
        }
        try {
            String text = Files.readString(manifestPath);
            Object parsed = new Yaml().load(text);   // manifest.json 也是合法 YAML（JSON 是 YAML 子集）
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("manifest 顶层应为对象：" + manifestPath);
            }
            manifest = (Map<String, Object>) map;
        } catch (IOException e) {
            throw new IllegalStateException("读取 dbt manifest 失败：" + manifestPath + " → " + e.getMessage(), e);
        }
        Object nodes = manifest.get("nodes");
        if (!(nodes instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("manifest 里没有 nodes：可能不是 dbt 的 manifest.json（"
                    + manifestPath + "）");
        }
        return manifest;
    }

    @Override
    public Stream<RawModels.RawDataset> extract() {
        Map<String, Object> loaded = load();
        List<RawModels.RawDataset> out = new ArrayList<>();

        // 1) nodes：model / seed / snapshot（test 不是数据资产，跳过）
        for (Map<String, Object> node : nodeList(loaded, "nodes")) {
            String resourceType = str(node.get("resource_type"));
            if (!List.of("model", "seed", "snapshot").contains(resourceType)) {
                continue;
            }
            RawModels.RawDataset dataset = toDataset(node, resourceType.toUpperCase(java.util.Locale.ROOT));
            if (dataset != null) {
                out.add(dataset);
                recordUrn(node, dataset);
            }
        }

        // 2) sources：外部源表的声明。它们同样是资产 —— 否则血缘会出现"指向不存在的东西"
        for (Map<String, Object> node : nodeList(loaded, "sources")) {
            RawModels.RawDataset dataset = toDataset(node, "SOURCE");
            if (dataset != null) {
                out.add(dataset);
                recordUrn(node, dataset);
            }
        }

        return out.stream()
                .filter(item -> matches(filters, item.schema(), item.table()))
                .sorted(Comparator.comparing(RawModels.RawDataset::table));
    }

    /** 血缘：{@code depends_on.nodes} 里的每个上游 → 本节点。 */
    @Override
    public Stream<RawModels.RawEdge> extractEdges() {
        Map<String, Object> loaded = load();
        if (urnByUniqueId.isEmpty()) {
            // extract() 还没跑过（或没有可产出的数据集）：先把 URN 映射建起来，
            // 否则血缘会全部变成"解析不到"
            try (Stream<RawModels.RawDataset> ignored = extract()) {
                ignored.toList();
            }
        }
        List<RawModels.RawEdge> edges = new ArrayList<>();
        int unresolved = 0;
        int sourceIsTest = 0;

        for (Map<String, Object> node : nodeList(loaded, "nodes")) {
            String resourceType = str(node.get("resource_type"));
            if (!List.of("model", "seed", "snapshot").contains(resourceType)) {
                continue;
            }
            String uniqueId = str(node.get("unique_id"));
            String toUrn = urnByUniqueId.get(uniqueId);
            if (toUrn == null) {
                continue;
            }
            for (String upstreamId : dependsOn(node)) {
                // 上游是 test 的情况不存在；解析不到的上游（例如被过滤掉/未采集的模型）必须计数
                String fromUrn = urnByUniqueId.get(upstreamId);
                if (fromUrn == null) {
                    unresolved++;
                    continue;
                }
                if (fromUrn.equals(toUrn)) {
                    continue;
                }
                edges.add(new RawModels.RawEdge(fromUrn, toUrn, "derivesFrom", "dbt_manifest", 1.0,
                        "DIRECT", "exact", str(node.get("name")),
                        "dbt depends_on：" + upstreamId));
            }
        }
        // sources 的 depends_on 为空（它们来自外部系统），所以血缘只有 nodes 一侧需要处理
        for (Map<String, Object> node : nodeList(loaded, "sources")) {
            String uniqueId = str(node.get("unique_id"));
            if (urnByUniqueId.get(uniqueId) == null) {
                sourceIsTest++;
            }
        }
        if (unresolved > 0) {
            skipNotes.add("有 " + unresolved + " 条 dbt 依赖的上游未解析到平台资产（未采集/被过滤），"
                    + "这些边**没有写入**：宁可缺边也不猜");
        }
        if (sourceIsTest > 0) {
            skipNotes.add("有 " + sourceIsTest + " 个 source 声明未产出资产（路径解析失败）");
        }
        return edges.stream();
    }

    // ------------------------------------------------------------------ 转换

    private RawModels.RawDataset toDataset(Map<String, Object> node, String kind) {
        String database = str(node.get("database"));
        String schema = str(node.get("schema"));
        String table = str(node.get("alias"));
        if (table == null || table.isBlank()) {
            table = str(node.get("identifier"));
        }
        if (table == null || table.isBlank()) {
            table = str(node.get("name"));
        }
        if (table == null || table.isBlank()) {
            return null;
        }
        List<RawModels.RawColumn> columns = new ArrayList<>();
        Object rawColumns = node.get("columns");
        int ordinal = 1;
        if (rawColumns instanceof Map<?, ?> map) {
            List<String> names = new ArrayList<>();
            map.forEach((key, value) -> names.add(String.valueOf(key)));
            names.sort(String::compareTo);       // manifest 的列是映射，顺序不稳定 → 排序保证可重复
            for (String name : names) {
                Map<String, Object> column = map.get(name) instanceof Map<?, ?> item
                        ? cast(item) : Map.of();
                String dataType = str(column.get("data_type"));
                columns.add(new RawModels.RawColumn(name,
                        dataType == null ? "unknown" : dataType.toLowerCase(java.util.Locale.ROOT),
                        true, ordinal++, str(column.get("description")), null));
            }
        }

        String description = str(node.get("description"));
        List<Map<String, Object>> properties = new ArrayList<>();
        Map<String, Object> dbtInfo = new LinkedHashMap<>();
        dbtInfo.put("uniqueId", str(node.get("unique_id")));
        dbtInfo.put("materialized", nested(node, "config", "materialized"));
        dbtInfo.put("package", str(node.get("package_name")));
        dbtInfo.put("tags", node.get("tags"));
        dbtInfo.put("path", str(node.get("original_file_path")));
        properties.add(Map.of("kind", "dbt", "detail", dbtInfo));

        return new RawModels.RawDataset("dbt",
                database == null ? "" : database,
                schema == null ? "" : schema,
                table, kind, description, columns, List.of(), properties);
    }

    private void recordUrn(Map<String, Object> node, RawModels.RawDataset dataset) {
        String uniqueId = str(node.get("unique_id"));
        if (uniqueId == null) {
            return;
        }
        String urn = resolver.resolve(dataset.database(), dataset.schema(), dataset.table());
        if (urn != null) {
            urnByUniqueId.put(uniqueId, urn);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodeList(Map<String, Object> loaded, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (loaded.get(key) instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof Map<?, ?> item) {
                    out.add(cast(item));
                }
            }
        }
        return out;
    }

    private static List<String> dependsOn(Map<String, Object> node) {
        List<String> out = new ArrayList<>();
        if (node.get("depends_on") instanceof Map<?, ?> depends
                && depends.get("nodes") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static Object nested(Map<String, Object> node, String outer, String inner) {
        if (node.get(outer) instanceof Map<?, ?> map) {
            return map.get(inner);
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean matches(List<String> filters, String schema, String table) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        String qualified = (schema == null ? "" : schema + ".") + table;
        for (String filter : filters) {
            String needle = filter.trim().toLowerCase(java.util.Locale.ROOT);
            if (needle.isEmpty()) {
                continue;
            }
            if (qualified.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || (table != null && table.toLowerCase(java.util.Locale.ROOT).equals(needle))) {
                return true;
            }
        }
        return false;
    }
}
