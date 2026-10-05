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
 * <p>实体建模的关键决定（与"把 dbt 模型合并进物理表"相反）：
 * <ul>
 *   <li><b>model / seed / snapshot 建为**独立的** dbt 资产</b>（platform=dbt，URN 里带项目名）。
 *       理由是两条硬事实：① manifest 里的 {@code columns} 通常只包含**被文档化过的列**，
 *       拿它去覆盖从数据库采集的真实 schema 是**降级**；② dbt 模型有自己的 owner、
 *       描述、物化方式与测试，合并进物理表会让这些信息无处安放；</li>
 *   <li><b>source 声明优先解析到已采集的物理资产</b>（这正是 source 的意义：它指向真实表）；
 *       解析不到才建 dbt 侧资产，避免血缘指向不存在的东西；</li>
 *   <li><b>血缘方向</b>：{@code 上游 → 下游}。于是一条 dbt 链路读起来就是真实链路：
 *       {@code 源表 → dbt 模型 → 物化的物理表 → 下一个 dbt 模型 → …}。</li>
 * </ul>
 *
 * <p>不做的事（诚实边界）：
 * <ul>
 *   <li><b>不执行 dbt</b>：只读已编译的 manifest，不跑 {@code dbt compile}（那属于 CI）；</li>
 *   <li><b>不做列级血缘</b>：manifest 的依赖只到表级。列级需要把 {@code compiled_code}
 *       交给 sqlglot 侧车（{@code POST /api/v1/lineage/parse}），平台已提供该接口；</li>
 *   <li><b>不采集 exposures / metrics</b>：exposures 的语义是"看板依赖模型"，
 *       与 BI 资产不同；指标走 {@code ai.semantic-layer} 的接入路径。</li>
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
        // URL 解码（路径里可能有空格等需要转义的字符）
        try {
            raw = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            // 解码失败就按原样处理：路径本身不合法时会走到下面的"找不到 manifest"分支并给出提示
        }
        // `dbt:///abs/path` 在解析后会留下一个前导斜杠；Windows 上 `/D:/path` 不是合法路径，
        // 必须去掉这个斜杠（否则报 "Illegal char <:>"，而使用者完全看不出是自己写错了 DSN）
        if (raw.length() > 2 && raw.charAt(0) == '/' && Character.isLetter(raw.charAt(1))
                && raw.charAt(2) == ':') {
            raw = raw.substring(1);
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("dbt DSN 需要给出路径：dbt:///path/to/project 或 dbt:///path/to/manifest.json");
        }
        String withoutQuery = raw.contains("?") ? raw.substring(0, raw.indexOf('?')) : raw;
        Path candidate = Path.of(withoutQuery).toAbsolutePath().normalize();
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
        String project = projectName(loaded);

        // 1) nodes：model / seed / snapshot 建为独立的 dbt 资产（test 不是数据资产，跳过）
        for (Map<String, Object> node : nodeList(loaded, "nodes")) {
            String resourceType = str(node.get("resource_type"));
            if (!List.of("model", "seed", "snapshot").contains(resourceType)) {
                continue;
            }
            RawModels.RawDataset dataset = toDataset(node, resourceType.toUpperCase(java.util.Locale.ROOT),
                    project);
            if (dataset != null) {
                out.add(dataset);
                recordUrn(node, dataset);
            }
        }

        // 2) sources：优先解析到已采集的物理资产（source 的意义就是指向真实表）；
        //    解析不到才建 dbt 侧资产，否则血缘会指向不存在的东西
        for (Map<String, Object> node : nodeList(loaded, "sources")) {
            String physical = resolvePhysical(node);
            if (physical != null) {
                String uniqueId = str(node.get("unique_id"));
                if (uniqueId != null) {
                    registerUrn(uniqueId, physical);
                }
                continue;
            }
            RawModels.RawDataset dataset = toDataset(node, "SOURCE", project);
            if (dataset != null) {
                out.add(dataset);
                recordUrn(node, dataset);
            }
        }

        return out.stream()
                .filter(item -> matches(filters, item.schema(), item.table()))
                .sorted(Comparator.comparing(RawModels.RawDataset::table));
    }

    /**
     * 血缘三条来源，都来自 manifest 的**编译期事实**：
     * <ol>
     *   <li>{@code depends_on} 的上游 → 本节点（模型引用模型 / 引用 source）；</li>
     *   <li>模型物化出的**物理表**：{@code 模型 → 物理表}，于是链路读起来就是真实链路；</li>
     *   <li>解析不到的上游：**跳过并记账**（宁可缺边也不猜）。</li>
     * </ol>
     */
    @Override
    public Stream<RawModels.RawEdge> extractEdges() {
        Map<String, Object> loaded = load();
        if (urnByUniqueId.isEmpty()) {
            try (Stream<RawModels.RawDataset> ignored = extract()) {
                ignored.toList();
            }
        }
        List<RawModels.RawEdge> edges = new ArrayList<>();
        int unresolved = 0;

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
                String fromUrn = urnByUniqueId.get(upstreamId);
                if (fromUrn == null) {
                    unresolved++;
                    continue;
                }
                if (fromUrn.equals(toUrn)) {
                    continue;
                }
                edges.add(new RawModels.RawEdge(fromUrn, toUrn, "derivesFrom", EDGE_SOURCE, 1.0,
                        "DIRECT", "exact", str(node.get("name")),
                        "dbt depends_on：" + upstreamId));
            }

            // 模型物化的物理表：只有解析到**唯一**的物理资产时才写边
            String physical = resolvePhysical(node);
            if (physical != null && !physical.equals(toUrn)) {
                edges.add(new RawModels.RawEdge(toUrn, physical, "derivesFrom", EDGE_SOURCE, 1.0,
                        "DIRECT", "table_level_only", str(node.get("name")),
                        "dbt 模型物化为该物理表（" + str(nested(node, "config", "materialized")) + "）"));
            }
        }
        if (unresolved > 0) {
            skipNotes.add("有 " + unresolved + " 条 dbt 依赖的上游未解析到平台资产（未采集/被过滤），"
                    + "这些边**没有写入**：宁可缺边也不猜");
        }
        return edges.stream();
    }

    private static final String EDGE_SOURCE = "dbt_manifest";

    /** 解析 dbt 节点的物理表（source 用 identifier，model 用 alias）。 */
    private String resolvePhysical(Map<String, Object> node) {
        String schema = str(node.get("schema"));
        String table = str(node.get("identifier"));
        if (table == null || table.isBlank()) {
            table = str(node.get("alias"));
        }
        if (table == null || table.isBlank()) {
            return null;
        }
        return resolver.resolve(str(node.get("database")), schema, table);
    }

    // ------------------------------------------------------------------ 转换

    private RawModels.RawDataset toDataset(Map<String, Object> node, String kind, String project) {
        // dbt 资产的 URN 形状：platform=dbt、database=项目名、schema=dbt schema、table=节点名
        // （刻意用**节点名**而不是 alias：alias 会变，节点名是稳定标识；
        //  alias 与物化出来的物理表放在 properties 里，用于建"模型 → 物理表"的边）
        String schema = str(node.get("schema"));
        String table = str(node.get("name"));
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
        dbtInfo.put("resourceType", str(node.get("resource_type")));
        dbtInfo.put("materialized", nested(node, "config", "materialized"));
        dbtInfo.put("package", str(node.get("package_name")));
        dbtInfo.put("tags", node.get("tags"));
        dbtInfo.put("path", str(node.get("original_file_path")));
        // alias / database / schema：物化出来的物理位置（用于"模型 → 物理表"的边，也让使用者能对上号）
        dbtInfo.put("alias", str(node.get("alias")));
        dbtInfo.put("database", str(node.get("database")));
        dbtInfo.put("schema", schema);
        properties.add(Map.of("kind", "dbt", "detail", dbtInfo));

        return new RawModels.RawDataset("dbt",
                project == null ? "dbt" : project,
                schema == null ? "" : schema,
                table, kind, description, columns, List.of(), properties);
    }

    private static String projectName(Map<String, Object> loaded) {
        if (loaded.get("metadata") instanceof Map<?, ?> meta) {
            Object project = meta.get("project_name");
            if (project != null && !String.valueOf(project).isBlank()) {
                return String.valueOf(project);
            }
        }
        return "dbt";
    }

    /**
     * 记录 dbt 节点对应的平台 URN。
     *
     * <p>注意这里**不走物理表解析器**：dbt 节点是它自己的资产（见类注释的三条建模决定），
     * URN 由"平台=dbt + 项目 + schema + 节点名"直接构造，与 {@code CollectionService} 的
     * URN 规则完全一致 —— 两边必须算出同一个字符串，否则血缘会指向一个不存在的实体。
     */
    private void recordUrn(Map<String, Object> node, RawModels.RawDataset dataset) {
        String uniqueId = str(node.get("unique_id"));
        if (uniqueId == null) {
            return;
        }
        try {
            String urn = com.datagovernance.core.UrnUtils.build("Dataset", namespace,
                    com.datagovernance.core.UrnUtils.sanitizeSegment(dataset.platform()),
                    com.datagovernance.core.UrnUtils.sanitizeSegment(dataset.database()),
                    com.datagovernance.core.UrnUtils.sanitizeSegment(dataset.schema()),
                    com.datagovernance.core.UrnUtils.sanitizeSegment(dataset.table()));
            registerUrn(uniqueId, urn);
        } catch (RuntimeException e) {
            skipNotes.add("节点 " + uniqueId + " 的 URN 构造失败：" + e.getMessage());
        }
    }

    /**
     * 登记 unique_id → URN。
     *
     * <p>这里必须同时登记**两种写法**：manifest 里 {@code sources} 的键与 {@code unique_id} 用点号
     * （{@code source.dg_demo.raw.event_log}），而 {@code depends_on.nodes} 里的引用可能用冒号
     * （{@code source:dg_demo.raw.event_log}）。只登记一种的话，**source 的上游边会全部解析不到**
     * 而变成"跳过" —— 看起来像"没有血缘"，实际是键形式对不上。
     */
    private void registerUrn(String uniqueId, String urn) {
        urnByUniqueId.put(uniqueId, urn);
        if (uniqueId.startsWith("source.")) {
            urnByUniqueId.put("source:" + uniqueId.substring("source.".length()), urn);
        }
        if (uniqueId.startsWith("source:")) {
            urnByUniqueId.put("source." + uniqueId.substring("source:".length()), urn);
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
