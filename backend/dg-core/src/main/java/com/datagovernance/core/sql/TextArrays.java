package com.datagovernance.core.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

/**
 * PostgreSQL 数组参数绑定。
 *
 * <p><b>为什么需要这个类</b>：{@code JdbcTemplate} 对 {@code text[]} 列接受 {@code String[]}
 * 是**假接受** —— 它在 {@code setObject} 时才抛
 * {@code SQLFeatureNotSupportedException}（pgjdbc 未实现 {@code setObjectArray}）。
 * 这个缺陷在本项目里出现过 5 次（调度 DSN、访问申请的 permissions/approvers、
 * 质量规则、Edge Agent 的 capabilities、引擎审计的 columns），每次都要跑起来才暴露。
 *
 * <p>因此把它收敛成一个显式入口：**要绑数组就调 {@link #of}**，
 * 并配一条回归测试（{@code PgArrayBindingGuardTest}）禁止在 {@code jdbc.*} 调用里直接传
 * {@code String[]}。约定 + 门禁，比"记住这件事"可靠。
 */
public final class TextArrays {

    private TextArrays() {
    }

    /** 绑定 {@code text[]} 参数（必须传入当前连接的 {@link Connection}）。 */
    public static java.sql.Array of(Connection connection, Collection<String> values) {
        try {
            return connection.createArrayOf("text", values == null ? new Object[0] : values.toArray());
        } catch (SQLException e) {
            throw new IllegalStateException("绑定 text[] 参数失败", e);
        }
    }

    /** 绑定 {@code text[]} 参数（变参形式）。 */
    public static java.sql.Array of(Connection connection, String... values) {
        try {
            return connection.createArrayOf("text", values == null ? new Object[0] : values);
        } catch (SQLException e) {
            throw new IllegalStateException("绑定 text[] 参数失败", e);
        }
    }
}
