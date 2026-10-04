package com.datagovernance.ingestion.connectors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;

/**
 * PostgreSQL 连接器。
 *
 * <p>采集路径：{@code information_schema} + {@code pg_catalog} 的
 * {@code obj_description}/{@code col_description}（表注释与列注释）。
 *
 * <p>⚠️ 已知风险（docs/09 §9.1）：<b>权限不足时 information_schema 会静默裁剪</b>，
 * 表现为「元数据稀疏」而非报错 —— 因此采集端如实上报，护栏负责「实体数骤降」的兜底。
 */
public class PostgresSource implements Source {

    private static final String DEFAULT_EXCLUDED =
            "'pg_catalog','information_schema','pg_toast'";

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final List<String> includeSchemas;
    private final List<String> includeTables;

    public PostgresSource(String jdbcUrl, String user, String password,
                          List<String> includeSchemas, List<String> includeTables) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.includeSchemas = includeSchemas == null ? List.of() : includeSchemas;
        this.includeTables = includeTables == null ? List.of() : includeTables;
    }

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public String platform() {
        return "postgresql";
    }

    @Override
    public Stream<RawModels.RawDataset> extract() {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            connection.setReadOnly(true);
            try {
                String database = connection.getCatalog();
                Map<String, String> tables = loadTables(connection);
                Map<String, List<RawModels.RawColumn>> columns = loadColumns(connection);
                Map<String, List<String>> primaryKeys = loadPrimaryKeys(connection);

                List<RawModels.RawDataset> datasets = new ArrayList<>();
                for (Map.Entry<String, String> entry : tables.entrySet()) {
                    String[] parts = entry.getKey().split("\\.", 2);
                    List<RawModels.RawColumn> cols = columns.getOrDefault(entry.getKey(), List.of());
                    if (cols.isEmpty()) {
                        continue;
                    }
                    datasets.add(new RawModels.RawDataset(
                            platform(), database, parts[0], parts[1], entry.getValue(),
                            tableComment(connection, parts[0], parts[1]),
                            cols, primaryKeys.getOrDefault(entry.getKey(), List.of()), List.of()));
                }
                return datasets.stream();
            } finally {
                connection.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("连接 PostgreSQL 失败：" + e.getMessage(), e);
        }
    }

    private String schemaFilter(String alias) {
        StringBuilder sql = new StringBuilder(alias + ".table_schema NOT IN (" + DEFAULT_EXCLUDED + ")");
        if (!includeSchemas.isEmpty()) {
            sql.append(" AND ").append(alias).append(".table_schema = ANY(?)");
        }
        if (!includeTables.isEmpty()) {
            sql.append(" AND ").append(alias).append(".table_name = ANY(?)");
        }
        return sql.toString();
    }

    private void bindFilters(PreparedStatement statement) throws SQLException {
        int index = 1;
        if (!includeSchemas.isEmpty()) {
            statement.setArray(index++, statement.getConnection().createArrayOf("text", includeSchemas.toArray()));
        }
        if (!includeTables.isEmpty()) {
            statement.setArray(index, statement.getConnection().createArrayOf("text", includeTables.toArray()));
        }
    }

    private Map<String, String> loadTables(Connection connection) throws SQLException {
        String sql = """
                SELECT t.table_schema, t.table_name,
                       CASE t.table_type WHEN 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS kind
                  FROM information_schema.tables t
                 WHERE %s
                 ORDER BY t.table_schema, t.table_name
                """.formatted(schemaFilter("t"));
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilters(statement);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1) + "." + rs.getString(2), rs.getString(3));
                }
            }
        }
        return out;
    }

    private Map<String, List<RawModels.RawColumn>> loadColumns(Connection connection) throws SQLException {
        String sql = """
                SELECT c.table_schema, c.table_name, c.column_name,
                       COALESCE(c.data_type, 'UNKNOWN') AS data_type,
                       (c.is_nullable = 'YES') AS nullable,
                       c.ordinal_position,
                       col_description(
                           format('%%I.%%I', c.table_schema, c.table_name)::regclass, c.ordinal_position
                       ) AS comment,
                       c.column_default
                  FROM information_schema.columns c
                 WHERE %s
                 ORDER BY c.table_schema, c.table_name, c.ordinal_position
                """.formatted(schemaFilter("c"));
        Map<String, List<RawModels.RawColumn>> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilters(statement);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1) + "." + rs.getString(2);
                    out.computeIfAbsent(key, k -> new ArrayList<>()).add(new RawModels.RawColumn(
                            rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6),
                            rs.getString(7), rs.getString(8)));
                }
            }
        }
        return out;
    }

    private Map<String, List<String>> loadPrimaryKeys(Connection connection) throws SQLException {
        String sql = """
                SELECT tc.table_schema, tc.table_name, kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                   AND tc.table_schema = kcu.table_schema
                   AND tc.table_name = kcu.table_name
                 WHERE tc.constraint_type = 'PRIMARY KEY' AND %s
                 ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
                """.formatted(schemaFilter("tc"));
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilters(statement);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                            .add(rs.getString(3));
                }
            }
        }
        return out;
    }

    private String tableComment(Connection connection, String schema, String table) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT obj_description(format('%I.%I', ?, ?)::regclass, 'pg_class')")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null; // 注释读取失败不影响结构采集
        }
    }
}
