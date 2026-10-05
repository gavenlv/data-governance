package com.datagovernance.ingestion.connectors;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.datagovernance.ingestion.RawModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbt manifest 连接器的纯逻辑测试（不连数据库）。
 *
 * <p>这里验证的是**建模决定**而不是"能解析 JSON"：
 * <ul>
 *   <li>model/seed/snapshot 是**独立**的 dbt 资产（不去覆盖物理表的 schema）；</li>
 *   <li>source 优先解析到已采集的物理资产，解析不到才建 dbt 侧资产；</li>
 *   <li>血缘只写**解析得到**的边，解析不到的必须**记账**（skipNotes）；</li>
 *   <li>manifest 不存在时给出可操作的错误（"先跑 dbt compile"），而不是返回空结果。</li>
 * </ul>
 */
class DbtManifestSourceTest {

    private static final Path FIXTURE = locateFixture();

    /**
     * 定位夹具：从当前模块目录向上找 {@code tools/fixtures/dbt/manifest.json}。
     *
     * <p>不写死相对层级的原因很实际：Maven 从模块目录跑测试（{@code backend/dg-ingestion}），
     * 而 IDE 可能从仓库根跑 —— 写死 {@code ../} 或 {@code ../../} 总有一边找不到，
     * 而"找不到夹具"的错误看起来很像"连接器坏了"。
     */
    private static Path locateFixture() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path fixture = candidate.resolve("tools").resolve("fixtures").resolve("dbt").resolve("manifest.json");
            if (java.nio.file.Files.exists(fixture)) {
                return fixture;
            }
        }
        throw new IllegalStateException("找不到 dbt manifest 夹具（tools/fixtures/dbt/manifest.json）");
    }

    /** 物理解析器：只认 platform 已知的两个表（对应真实采集到的资产）。 */
    private static String physical(String database, String schema, String table) {
        Map<String, String> known = Map.of(
                "event_log", "urn:dg:Dataset:java_e2e.postgresql.dg.public.event_log",
                "alert_event", "urn:dg:Dataset:java_e2e.postgresql.dg.public.alert_event",
                "collector_state", "urn:dg:Dataset:java_e2e.postgresql.dg.public.collector_state");
        return known.get(table);
    }

    @Test
    void modelsBecomeIndependentDbtAssets() {
        DbtManifestSource source = new DbtManifestSource(FIXTURE, "java_e2e",
                DbtManifestSourceTest::physical, List.of());
        List<RawModels.RawDataset> datasets;
        try (Stream<RawModels.RawDataset> stream = source.extract()) {
            datasets = stream.toList();
        }
        List<String> tables = datasets.stream().map(RawModels.RawDataset::table).sorted().toList();
        // 2 个 model + 1 个 dbt 侧 source（unknown_table 解析不到物理表，才建 dbt 资产）
        assertEquals(List.of("collector_rollup", "stg_event_log", "unknown_table"), tables);
        assertTrue(datasets.stream().allMatch(item -> "dbt".equals(item.platform())),
                "dbt 资产必须自成平台，不能混进物理表的 platform");
        assertTrue(datasets.stream().allMatch(item -> "dg_demo".equals(item.database())),
                "URN 里要带项目名，多项目共存时不会撞车");

        RawModels.RawDataset staging = datasets.stream()
                .filter(item -> "stg_event_log".equals(item.table())).findFirst().orElseThrow();
        assertEquals("MODEL", staging.kind());
        assertEquals(2, staging.columns().size());
        assertTrue(staging.comment().contains("清洗层"));
        // alias 与 schema 写进 properties，用于"模型 → 物理表"的边与人工核对
        String detail = String.valueOf(staging.partitionKeys());
        assertTrue(detail.contains("alert_event"), detail);
    }

    @Test
    void sourceResolvesToPhysicalAssetInsteadOfDuplicating() {
        DbtManifestSource source = new DbtManifestSource(FIXTURE, "java_e2e",
                DbtManifestSourceTest::physical, List.of());
        try (Stream<RawModels.RawDataset> stream = source.extract()) {
            stream.toList();
        }
        List<RawModels.RawEdge> edges;
        try (Stream<RawModels.RawEdge> stream = source.extractEdges()) {
            edges = stream.toList();
        }
        // event_log（物理表）→ stg_event_log：source 解析到了物理资产，因此边直接连到它
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.fromUrn().equals("urn:dg:Dataset:java_e2e.postgresql.dg.public.event_log")
                                && edge.toUrn().contains("stg_event_log")),
                "source 应解析到已采集的物理资产：" + edges);
        // unknown_table 没有物理对应 → 不应出现指向它的边
        assertFalse(edges.stream().anyMatch(edge -> edge.toUrn().contains("unknown_table")
                        || edge.fromUrn().contains("unknown_table")),
                "解析不到的上游不能凭空造边");
    }

    @Test
    void writesModelToPhysicalTableEdgeAndSkipsUnresolvableUpstream() {
        DbtManifestSource source = new DbtManifestSource(FIXTURE, "java_e2e",
                DbtManifestSourceTest::physical, List.of());
        List<RawModels.RawEdge> edges;
        try (Stream<RawModels.RawEdge> stream = source.extractEdges()) {
            edges = stream.toList();
        }
        // 模型 → 物化的物理表
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.fromUrn().contains("stg_event_log")
                                && edge.toUrn().equals("urn:dg:Dataset:java_e2e.postgresql.dg.public.alert_event")
                                && "table_level_only".equals(edge.parseLevel())),
                "应存在「模型 → 物理表」的边：" + edges);
        // 模型之间的依赖
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.fromUrn().contains("stg_event_log") && edge.toUrn().contains("collector_rollup")),
                "模型间应存在 depends_on 边：" + edges);
        // 解析不到的 missing_model 必须记账，而不是静默丢弃
        assertEquals(1, source.edgeSkipNotes().size(), String.valueOf(source.edgeSkipNotes()));
        assertTrue(source.edgeSkipNotes().get(0).contains("未解析"), source.edgeSkipNotes().get(0));
    }

    @Test
    void missingManifestExplainsWhatToDo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DbtManifestSource.fromDsn("dbt:///no/such/dbt/project", "prod",
                        (a, b, c) -> null, List.of()));
        assertTrue(error.getMessage().contains("dbt compile"), error.getMessage());
    }

    /**
     * DSN 解析：{@code dbt:///abs/path} 在 Windows 上会变成 {@code /D:/path}，
     * 直接喂给 {@code Path.of} 会报 "Illegal char <:>" —— 使用者根本看不出是自己写错了 DSN。
     */
    @Test
    void dsnAcceptsUrlStylePathsOnWindows() {
        String manifest = FIXTURE.toAbsolutePath().toString();
        String uri = "dbt:///" + manifest.replace("\\", "/");
        DbtManifestSource source = DbtManifestSource.fromDsn(uri, "java_e2e",
                DbtManifestSourceTest::physical, List.of());
        try (Stream<RawModels.RawDataset> stream = source.extract()) {
            assertEquals(3, stream.count(), "URL 形态的 DSN 应当定位到同一个 manifest");
        }
        // 也接受直接指向文件的普通路径
        DbtManifestSource direct = DbtManifestSource.fromDsn(manifest, "java_e2e",
                DbtManifestSourceTest::physical, List.of());
        try (Stream<RawModels.RawDataset> stream = direct.extract()) {
            assertEquals(3, stream.count());
        }
    }

    @Test
    void filtersNarrowTheAssetSet() {
        List<String> collected = new ArrayList<>();
        DbtManifestSource source = new DbtManifestSource(FIXTURE, "java_e2e",
                DbtManifestSourceTest::physical, List.of("collector_rollup"));
        try (Stream<RawModels.RawDataset> stream = source.extract()) {
            stream.forEach(item -> collected.add(item.table()));
        }
        assertEquals(List.of("collector_rollup"), collected);
    }
}
