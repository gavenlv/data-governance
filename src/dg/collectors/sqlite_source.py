"""SQLite 连接器（第二个源，用于验证采集框架的多源扩展能力）。

为什么选它作为"首批连接器"的第二个：
  - 零外部依赖与零服务依赖 → **端到端可完整测试**（可造真实库文件）
  - 真实存在：本地分析库、嵌入式应用库、离线数据交付
  - 它检验的是**框架扩展性**：只新增一个 `Source` 实现，框架与 Sink 一行不改

与其他源的差异（正是要验证的点）：
  - SQLite 是动态类型，`PRAGMA table_info` 给出的 `type` 是**声明亲和性**，可能为空
    → 空类型必须显式标注为 `UNKNOWN`，不得猜测（docs/09 §9.2「失败必须显式」的精神）
  - 无 information_schema，元数据来自 `sqlite_master` + `PRAGMA`
"""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Iterator

from dg.collectors.base import RawColumn, RawDataset, Source


class SqliteSource(Source):
    name = "sqlite"
    platform = "sqlite"

    def __init__(self, path: str, *, include_views: bool = True) -> None:
        self.path = path
        self.include_views = include_views

    # ------------------------------------------------------------------ 读取

    def extract(self, database: str | None = None) -> Iterator[RawDataset]:
        db_path = Path(self.path)
        if not db_path.exists():
            raise FileNotFoundError(f"SQLite 文件不存在：{self.path}")

        # 只读打开，避免采集动作改动源库（含 -wal/-shm 的库也能安全读）
        conn = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
        try:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            db_name = database or db_path.stem
            for name, kind in self._load_objects(cur):
                columns = self._load_columns(cur, name)
                if not columns:
                    continue  # 无列的虚拟表/异常对象，跳过而不是产出空壳
                yield RawDataset(
                    platform=self.platform,
                    database=db_name,
                    schema="main",  # SQLite 只有一个 main schema（附加库另计）
                    table=name,
                    kind=kind,
                    comment=None,  # SQLite 无原生注释
                    columns=columns,
                    primary_key=self._load_primary_key(cur, name),
                )
        finally:
            conn.close()

    # ---------------------------------------------------------------- 子查询

    def _load_objects(self, cur: sqlite3.Cursor) -> list[tuple[str, str]]:
        types = ["table", "view"] if self.include_views else ["table"]
        placeholders = ",".join("?" for _ in types)
        cur.execute(
            f"""
            SELECT name, type FROM sqlite_master
             WHERE type IN ({placeholders})
               AND name NOT LIKE 'sqlite_%'
             ORDER BY type, name
            """,
            types,
        )
        out: list[tuple[str, str]] = []
        for row in cur.fetchall():
            out.append((row["name"], "VIEW" if row["type"] == "view" else "TABLE"))
        return out

    def _load_columns(self, cur: sqlite3.Cursor, table: str) -> list[RawColumn]:
        # 表名不能参数化，用双引号转义（SQLite 标识符规则）
        safe = table.replace('"', '""')
        cur.execute(f'PRAGMA table_info("{safe}")')
        columns: list[RawColumn] = []
        for row in cur.fetchall():
            declared = (row["type"] or "").strip()
            columns.append(
                RawColumn(
                    name=row["name"],
                    # 声明类型可能为空 —— 显式标注而非猜测
                    data_type=declared or "UNKNOWN",
                    nullable=not bool(row["notnull"]),
                    ordinal=int(row["cid"]) + 1,
                    comment=None,
                    default=row["dflt_value"],
                )
            )
        return columns

    def _load_primary_key(self, cur: sqlite3.Cursor, table: str) -> list[str]:
        safe = table.replace('"', '""')
        cur.execute(f'PRAGMA table_info("{safe}")')
        rows = [r for r in cur.fetchall() if r["pk"]]
        rows.sort(key=lambda r: r["pk"])
        return [r["name"] for r in rows]


__all__ = ["SqliteSource"]
