package com.datagovernance.ingestion.connectors;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 连接器的 DSN 解析测试（纯逻辑，不需要真实服务）。
 *
 * <p>为什么值得测：DSN 是用户唯一要写对的东西，而解析错误的表现是
 * "连到了一个看起来能连、但不是你想的库" —— 这类错误最难发现。
 */
class ConnectorDsnTest {

    @Test
    void clickHouseDsnParsesCredentialsAndScheme() {
        ClickHouseSource source = ClickHouseSource.fromDsn(
                "clickhouse://analyst:secret@ch.internal:8123", List.of("dwd"), List.of());
        assertEquals("clickhouse", source.platform());
        assertNotNull(source);
    }

    @Test
    void clickHouseDsnAcceptsHttpScheme() {
        ClickHouseSource source = ClickHouseSource.fromDsn(
                "http://ch.internal:8123", List.of(), List.of());
        assertEquals("clickhouse", source.name());
    }

    @Test
    void mongoDsnAcceptsShortForm() {
        MongoSource source = MongoSource.fromDsn("127.0.0.1:27018", List.of("shop"), List.of(), 100);
        assertEquals("mongodb", source.platform());
    }

    @Test
    void mongoDsnRejectsBlank() {
        assertThrows(IllegalStateException.class,
                () -> MongoSource.fromDsn("  ", List.of(), List.of(), null));
    }

    @Test
    void bigQueryDsnParsesProjectAndOptions() {
        BigQuerySource source = BigQuerySource.fromDsn(
                "bigquery://my-project?credentials=/tmp/sa.json", List.of("dwd"), List.of());
        assertEquals("bigquery", source.platform());

        BigQuerySource emulator = BigQuerySource.fromDsn(
                "bigquery://my-project?emulator=http://127.0.0.1:9050", List.of(), List.of());
        assertEquals("bigquery", emulator.name());
    }

    @Test
    void bigQueryDsnRejectsMissingProject() {
        assertThrows(IllegalStateException.class,
                () -> BigQuerySource.fromDsn("bigquery://", List.of(), List.of()));
    }

    @Test
    void supersetDsnParsesCredentials() {
        SupersetSource source = SupersetSource.fromDsn(
                "superset://admin:admin@bi.internal:8088", "bi", (schema, table) -> null, List.of());
        assertEquals("superset", source.platform());
    }

    /** Superset 是消费端：它不产出数据集实体，这一点必须在接口层面表达清楚。 */
    @Test
    void supersetProducesDashboardsNotDatasets() {
        SupersetSource source = new SupersetSource(
                "http://bi.internal:8088", "admin", "admin", "bi", (schema, table) -> null, List.of());
        assertEquals(0, source.extract().count(), "Superset 不产数据集");
        org.junit.jupiter.api.Assertions.assertTrue(source.supportsDashboards());
    }

    @Test
    void datasetUrnIsBuiltWithTheSameRulesAsCollection() {
        // URN 形状必须是 ns.platform.database.schema.table —— 与采集服务写出的完全一致，
        // 否则 Superset 侧算出来的 URN 会指向一个不存在的实体（血缘直接断掉）
        assertEquals("urn:dg:Dataset:bi.postgresql.superset.public.birth_names",
                SupersetSource.datasetUrn("bi", "postgresql", "superset", "public", "birth_names"));
    }

    @Test
    void unsupportedSchemesAreRejectedInsteadOfBeingRepurposed() {
        assertThrows(IllegalStateException.class,
                () -> BigQuerySource.fromDsn("mysql://host/db", List.of(), List.of()));
        assertThrows(IllegalStateException.class,
                () -> MongoSource.fromDsn("mysql://host/db", List.of(), List.of(), null));
    }
}
