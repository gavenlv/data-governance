package com.datagovernance.ingestion.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源对外展示的脱敏测试。
 *
 * <p>这是最容易漏的一处泄露：endpoint 落在**非密列**（列表页不解密就能看到），
 * 若直接把 DSN 存进去，等于把口令明文写进了一个"非密列"。
 */
class DataSourceServiceTest {

    @Test
    void 去掉连接串里的账号密码() {
        assertThat(DataSourceService.sanitizeForDisplay(
                "postgresql://postgres:root@localhost:25011/dg", null))
                .isEqualTo("postgresql://localhost:25011/dg");
    }

    @Test
    void 屏蔽敏感查询参数但保留主机库名() {
        assertThat(DataSourceService.sanitizeForDisplay(
                "jdbc:postgresql://localhost:25011/dg?user=postgres&password=root", null))
                .isEqualTo("jdbc:postgresql://localhost:25011/dg?user=postgres&password=****");
    }

    @Test
    void 没有dsn时回退到jdbcUrl() {
        assertThat(DataSourceService.sanitizeForDisplay(null, "clickhouse://admin:s3cret@host:8123"))
                .isEqualTo("clickhouse://host:8123");
    }

    @Test
    void 无凭据的路径型连接串原样保留() {
        assertThat(DataSourceService.sanitizeForDisplay("dbt:///repo/analytics", null))
                .isEqualTo("dbt:///repo/analytics");
    }

    @Test
    void 空输入返回空() {
        assertThat(DataSourceService.sanitizeForDisplay(null, null)).isNull();
        assertThat(DataSourceService.sanitizeForDisplay("  ", "")).isNull();
    }
}