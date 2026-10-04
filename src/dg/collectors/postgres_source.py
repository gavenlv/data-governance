"""PostgreSQL 连接器（首个连接器）。

采集路径说明（docs/09 §9.1「实现陷阱」）：
  - 用 information_schema + pg_catalog 的 obj_description/col_description 读注释
  - ⚠️ 已知风险：**权限不足时 information_schema 会静默裁剪**（表现为"元数据稀疏"而非报错）。
    因此采集端只负责如实上报；"实体数骤降"护栏在框架层（docs/09 §9.1）。
  - 只读：仅执行 SELECT
"""

from __future__ import annotations

from typing import Iterator

import psycopg2

from dg.collectors.base import RawColumn, RawDataset, Source

DEFAULT_EXCLUDED_SCHEMAS = ("pg_catalog", "information_schema", "pg_toast")


class PostgresSource(Source):
    name = "postgres"
    platform = "postgresql"

    def __init__(
        self,
        dsn: str,
        *,
        include_schemas: list[str] | None = None,
        exclude_schemas: list[str] | None = None,
        include_tables: list[str] | None = None,
        statement_timeout_ms: int = 30_000,
    ) -> None:
        self.dsn = dsn
        self.include_schemas = include_schemas
        self.exclude_schemas = list(exclude_schemas or DEFAULT_EXCLUDED_SCHEMAS)
        self.include_tables = include_tables
        self.statement_timeout_ms = statement_timeout_ms

    # ------------------------------------------------------------------ 读取

    def extract(self, database: str | None = None) -> Iterator[RawDataset]:
        conn = psycopg2.connect(self.dsn)
        try:
            conn.set_session(readonly=True, autocommit=False)
            with conn.cursor() as cur:
                cur.execute(f"SET statement_timeout = {int(self.statement_timeout_ms)}")
                cur.execute("SELECT current_database()")
                dbname = database or cur.fetchone()[0]

                tables = self._load_tables(cur)
                columns = self._load_columns(cur)
                pks = self._load_primary_keys(cur)

            for key in tables:
                schema, table = key
                raw_cols = columns.get(key, [])
                yield RawDataset(
                    platform=self.platform,
                    database=dbname,
                    schema=schema,
                    table=table,
                    kind=tables[key],
                    comment=self._table_comment_cache.get(key),
                    columns=raw_cols,
                    primary_key=pks.get(key, []),
                )
        finally:
            conn.close()

    # ---------------------------------------------------------------- 子查询

    def _schema_filter(self, alias: str = "") -> tuple[str, list]:
        col = f"{alias}.table_schema" if alias else "table_schema"
        clauses = [f"{col} <> ALL(%s)"]
        params: list = [self.exclude_schemas]
        if self.include_schemas:
            clauses.append(f"{col} = ANY(%s)")
            params.append(self.include_schemas)
        if self.include_tables:
            tcol = f"{alias}.table_name" if alias else "table_name"
            clauses.append(f"{tcol} = ANY(%s)")
            params.append(self.include_tables)
        return " AND ".join(clauses), params

    def _load_tables(self, cur) -> dict[tuple[str, str], str]:
        self._table_comment_cache: dict[tuple[str, str], str | None] = {}
        where, params = self._schema_filter()
        cur.execute(
            f"""
            SELECT t.table_schema,
                   t.table_name,
                   CASE t.table_type WHEN 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS kind,
                   obj_description(
                       format('%%I.%%I', t.table_schema, t.table_name)::regclass, 'pg_class'
                   ) AS comment
              FROM information_schema.tables t
             WHERE {where}
             ORDER BY t.table_schema, t.table_name
            """,
            params,
        )
        result: dict[tuple[str, str], str] = {}
        for schema, table, kind, comment in cur.fetchall():
            result[(schema, table)] = kind
            self._table_comment_cache[(schema, table)] = comment
        return result

    def _load_columns(self, cur) -> dict[tuple[str, str], list[RawColumn]]:
        where, params = self._schema_filter()
        cur.execute(
            f"""
            SELECT c.table_schema,
                   c.table_name,
                   c.column_name,
                   COALESCE(
                       CASE
                         WHEN c.data_type = 'character varying' AND c.character_maximum_length IS NOT NULL
                             THEN 'varchar(' || c.character_maximum_length || ')'
                         WHEN c.data_type = 'numeric' AND c.numeric_precision IS NOT NULL
                             THEN 'numeric(' || c.numeric_precision || ',' || COALESCE(c.numeric_scale, 0) || ')'
                         ELSE c.data_type
                       END, c.data_type) AS type,
                   (c.is_nullable = 'YES') AS nullable,
                   c.ordinal_position,
                   c.column_default,
                   col_description(
                       format('%%I.%%I', c.table_schema, c.table_name)::regclass, c.ordinal_position
                   ) AS comment
              FROM information_schema.columns c
             WHERE {where}
             ORDER BY c.table_schema, c.table_name, c.ordinal_position
            """,
            params,
        )
        out: dict[tuple[str, str], list[RawColumn]] = {}
        for schema, table, name, dtype, nullable, ordinal, default, comment in cur.fetchall():
            out.setdefault((schema, table), []).append(
                RawColumn(
                    name=name,
                    data_type=dtype,
                    nullable=bool(nullable),
                    ordinal=int(ordinal),
                    comment=comment,
                    default=default,
                )
            )
        return out

    def _load_primary_keys(self, cur) -> dict[tuple[str, str], list[str]]:
        where, params = self._schema_filter("tc")
        cur.execute(
            f"""
            SELECT tc.table_schema, tc.table_name, kcu.column_name, kcu.ordinal_position
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.table_schema = kcu.table_schema
               AND tc.table_name = kcu.table_name
             WHERE tc.constraint_type = 'PRIMARY KEY'
               AND {where}
             ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
            """,
            params,
        )
        out: dict[tuple[str, str], list[str]] = {}
        for schema, table, column, _ord in cur.fetchall():
            out.setdefault((schema, table), []).append(column)
        return out


__all__ = ["PostgresSource"]
