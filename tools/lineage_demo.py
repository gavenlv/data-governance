"""列级血缘解析演示与自检（docs/09 §9.2）。

用法：
    python tools/lineage_demo.py            # 跑内置样例
    python tools/lineage_demo.py file.sql hive   # 解析一个 SQL 文件

自检要点：
  - 别名 → 表名还原正确
  - 聚合/掩码/计算 的转换类型识别正确
  - 窗口函数 PARTITION BY / ORDER BY 被标记为 **CONTROL** 而非 VALUE
  - SELECT * / 未知方言 / 语法错误 均**显式降级**并给出原因
"""

from __future__ import annotations

import sys

from dg.lineage.parser import parse_statement, sqlglot_version

SAMPLES: list[tuple[str, str]] = [
    (
        "hive",
        """
        INSERT INTO dw.dim_customer
        SELECT o.customer_id AS cust_key,
               c.name        AS cust_name,
               SUM(o.amount) AS total_amount,
               md5(c.phone)  AS phone_hash
          FROM ods.orders o
          JOIN ods.customers c ON c.id = o.customer_id
         GROUP BY o.customer_id, c.name, c.phone
        """,
    ),
    (
        "hive",
        """
        INSERT INTO dw.ranked
        SELECT id, dept,
               ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn
          FROM ods.emp
        """,
    ),
    (
        "hive",
        """
        INSERT INTO dw.wide
        SELECT * FROM ods.staging
        """,
    ),
    (
        "postgres",
        "INSERT INTO dw.t SELECT a.x AS ax, b.y AS by FROM s1 a JOIN s2 b ON a.id = b.id",
    ),
    (
        "bigquery",
        "INSERT INTO `p.d.t` SELECT x AS a FROM `p.d.s`",
    ),
    (
        "trino",
        "INSERT INTO dw.t SELECT substr(name, 1, 3) AS n, amount * 1.1 AS gross FROM src",
    ),
    (
        "hive",
        "INSERT INTO dw.exploded SELECT id, item FROM src LATERAL VIEW explode(items) t AS item",
    ),
    ("hive", "THIS IS NOT SQL AT ALL"),
    ("klingon", "SELECT 1"),
    ("hive", "SET hive.exec.dynamic.partition=true"),
]


def run(sql: str, dialect: str) -> None:
    result = parse_statement(sql, dialect=dialect)
    head = " ".join(sql.split())[:72]
    print(f"\n▸ [{dialect}] {head}…")
    print(
        f"  语句={result.statement_type}  目标={result.target_table}  "
        f"级别={result.parse_level}"
    )
    if result.source_tables:
        print(f"  源表={result.source_tables}")
    if result.error:
        print(f"  ❌ error={result.error}")
    for warning in result.warnings:
        print(f"  ⚠ {warning}")
    if not result.column_edges:
        print("  （无列级边）")
    for edge in result.column_edges:
        print(
            f"  {edge.from_table}.{edge.from_column}"
            f"  →  {edge.to_table}.{edge.to_column}"
            f"   [{edge.transform}/{edge.dependency_kind}/{edge.cardinality}]"
            f" conf={edge.confidence}"
        )


def main() -> int:
    print(f"sqlglot 版本：{sqlglot_version()}")
    if len(sys.argv) > 1:
        path = sys.argv[1]
        dialect = sys.argv[2] if len(sys.argv) > 2 else "hive"
        sql = open(path, encoding="utf-8").read()
        run(sql, dialect)
        return 0

    for dialect, sql in SAMPLES:
        if sql.strip().upper().startswith("SET "):
            print(f"\n▸ [{dialect}] {sql.strip()}  → L0 预筛跳过（无血缘价值）")
            continue
        run(sql, dialect)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
