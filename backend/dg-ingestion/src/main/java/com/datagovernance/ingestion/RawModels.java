package com.datagovernance.ingestion;

import java.util.List;
import java.util.Map;

/**
 * 采集中间模型（Source 产出的「原始元数据」）。
 *
 * <p>边界：连接器只负责「读源系统 + 翻译成统一模型」，不负责写平台 ——
 * 落库、护栏、快照都由 {@link CollectionService} 统一处理。
 */
public final class RawModels {

    private RawModels() {
    }

    public record RawColumn(
            String name,
            String dataType,
            boolean nullable,
            int ordinal,
            String comment,
            String defaultValue) {
    }

    public record RawDataset(
            String platform,
            String database,
            String schema,
            String table,
            String kind,
            String comment,
            List<RawColumn> columns,
            List<String> primaryKey,
            /** 分区键 / 排序键等结构化信息（模型中 datasetSchema.partitionKeys 就是为此预留的）。 */
            List<Map<String, Object>> partitionKeys) {

        public RawDataset {
            columns = List.copyOf(columns == null ? List.of() : columns);
            primaryKey = List.copyOf(primaryKey == null ? List.of() : primaryKey);
            partitionKeys = List.copyOf(partitionKeys == null ? List.of() : partitionKeys);
            if (kind == null) {
                kind = "TABLE";
            }
        }

        public static RawDataset of(String platform, String database, String schema, String table,
                                    List<RawColumn> columns) {
            return new RawDataset(platform, database, schema, table, "TABLE", null, columns,
                    List.of(), List.of());
        }
    }

    /**
     * 一个 BI 资产（仪表板 / 看板）。
     *
     * <p>为什么 BI 资产要进采集框架而不是另起一套：它同样需要 URN、Owner、标签、分级、
     * 版本历史与**血缘**（"这张报表挂了，它读的是哪张表"）—— 这些能力元数据侧已经全有了，
     * 重造一套只会得到两个互相不知道对方存在的世界。
     *
     * @param chartCount   图表数量（Superset 的 charts 字段）
     * @param datasetUrns  该资产直接引用的数据集 URN（用于建立 readsFrom 边；解析不到就是空）
     */
    public record RawDashboard(
            String platform,
            String externalId,
            String name,
            String title,
            String description,
            String url,
            boolean published,
            List<Map<String, Object>> charts,
            List<String> owners,
            List<String> datasetUrns,
            String lastRefreshedAt) {

        public RawDashboard {
            charts = List.copyOf(charts == null ? List.of() : charts);
            owners = List.copyOf(owners == null ? List.of() : owners);
            datasetUrns = List.copyOf(datasetUrns == null ? List.of() : datasetUrns);
            if (name == null || name.isBlank()) {
                name = externalId;
            }
        }
    }
}
