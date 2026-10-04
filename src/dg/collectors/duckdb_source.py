"""DuckDB 连接器（第三个源）。

选它作为第三个连接器的理由：
  - 单文件、零服务依赖 → **可完整测试**（造真实 .duckdb 文件）
  - 真实的现代分析场景（本地分析、数据科学、嵌入式 BI）
  - 元数据走标准 `information_schema`，比 SQLite 更规范
  - 额外能力：**能用 DuckDB 读 Parquet/CSV 并推断 schema**，
    这把"数据湖裸文件"这类没有 catalog 的资产也纳入了治理范围

与前两个源的差异（正是框架要兼容的点）：
  - 有 schema 概念（默认 main），URN 层级与 PostgreSQL 一致
  - 支持文件级虚拟表（read_parquet/read_csv），其"表名"是文件路径
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable, Iterator

from dg.collectors.base import RawColumn, RawDataset, Source

DEFAULT_EXCLUDED_SCHEMAS = ("information_schema", "pg_catalog", "main.temp")

_FILE_KINDS = {
    ".parquet": ("read_parquet", "PARQUET"),
    ".csv": ("read_csv_auto", "CSV"),
    ".json": ("read_json_auto", "JSON"),
    ".ndjson": ("read_json_auto", "JSON"),
}


class DuckDbSource(Source):
    name = "duckdb"
    platform = "duckdb"

    def __init__(
        self,
        path: str,
        *,
        include_schemas: list[str] | None = None,
        include_tables: list[str] | None = None,
        files: Iterable[str] | None = None,
    ) -> None:
        self.path = path
        self.include_schemas = include_schemas
        self.include_tables = include_tables
        # 额外的裸文件（Parquet/CSV），用 DuckDB 的能力推断 schema
        self.files = list(files or [])

    # ------------------------------------------------------------------ 读取

    def extract(self, database: str | None = None) -> Iterator[RawDataset]:
        import duckdb

        db_path = Path(self.path)
        if str(db_path) != ":memory:" and not db_path.exists():
            raise FileNotFoundError(f"DuckDB 文件不存在：{self.path}")

        conn = duckdb.connect(str(db_path), read_only=True)
        try:
            db_name = database or ("memory" if str(db_path) == ":memory:" else db_path.stem)
            for schema, table, kind in self._load_objects(conn):
                columns = self._load_columns(conn, schema, table)
                if not columns:
                    continue
                yield RawDataset(
                    platform=self.platform,
                    database=db_name,
                    schema=schema,
                    table=table,
                    kind=kind,
                    comment=None,  # DuckDB 无表注释
                    columns=columns,
                    primary_key=self._load_primary_key(conn, schema, table),
                )
            for dataset in self._load_files(conn, db_name):
                yield dataset
        finally:
            conn.close()

    # ---------------------------------------------------------------- 子查询

    def _load_objects(self, conn) -> list[tuple[str, str, str]]:
        where = ["table_schema NOT IN ('information_schema', 'pg_catalog')"]
        params: list = []
        if self.include_schemas:
            where.append("table_schema = ANY(?)")
            params.append(self.include_schemas)
        if self.include_tables:
            where.append("table_name = ANY(?)")
            params.append(self.include_tables)

        sql = f"""
            SELECT table_schema, table_name, table_type
              FROM information_schema.tables
             WHERE {' AND '.join(where)}
             ORDER BY table_schema, table_name
        """
        rows = conn.execute(sql, params).fetchall() if params else conn.execute(sql).fetchall()
        out: list[tuple[str, str, str]] = []
        for schema, table, table_type in rows:
            kind = "VIEW" if "VIEW" in (table_type or "").upper() else "TABLE"
            out.append((schema, table, kind))
        return out

    def _load_columns(self, conn, schema: str, table: str) -> list[RawColumn]:
        rows = conn.execute(
            """
            SELECT column_name, data_type, is_nullable, ordinal_position, column_default
              FROM information_schema.columns
             WHERE table_schema = ? AND table_name = ?
             ORDER BY ordinal_position
            """,
            [schema, table],
        ).fetchall()
        return [
            RawColumn(
                name=name,
                data_type=(dtype or "UNKNOWN").upper(),
                nullable=(nullable or "YES").upper() == "YES",
                ordinal=int(position),
                comment=None,
                default=default,
            )
            for name, dtype, nullable, position, default in rows
        ]

    def _load_primary_key(self, conn, schema: str, table: str) -> list[str]:
        try:
            rows = conn.execute(
                """
                SELECT constraint_column_names
                  FROM duckdb_constraints()
                 WHERE schema_name = ? AND table_name = ?
                   AND constraint_type = 'PRIMARY KEY'
                """,
                [schema, table],
            ).fetchall()
        except Exception:
            return []
        if not rows:
            return []
        # constraint_column_names 是 DuckDB 的 LIST(VARCHAR)
        return [str(c) for c in (rows[0][0] or [])]

    def _load_files(self, conn, db_name: str) -> Iterator[RawDataset]:
        """用 DuckDB 读裸文件并推断 schema（数据湖场景：没有 catalog 的资产）。"""
        for raw_path in self.files:
            path = Path(raw_path)
            if not path.exists():
                raise FileNotFoundError(f"数据文件不存在：{raw_path}")
            reader_entry = _FILE_KINDS.get(path.suffix.lower())
            if reader_entry is None:
                continue
            reader, file_kind = reader_entry

            # DESCRIBE 不读取数据，只推断 schema（对超大文件也安全）
            rows = conn.execute(
                f"DESCRIBE SELECT * FROM {reader}(?)", [str(path)]
            ).fetchall()
            columns = [
                RawColumn(
                    name=r[0],
                    data_type=(r[1] or "UNKNOWN").upper(),
                    nullable=(str(r[2]).upper() != "NO") if len(r) > 2 else True,
                    ordinal=idx + 1,
                )
                for idx, r in enumerate(rows)
            ]
            if not columns:
                continue
            yield RawDataset(
                platform=self.platform,
                database=db_name,
                schema="files",
                table=path.name,
                kind=file_kind,
                comment=f"外部文件（{file_kind}），schema 由 DuckDB 推断",
                columns=columns,
            )


__all__ = ["DuckDbSource"]
