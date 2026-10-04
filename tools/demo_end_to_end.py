"""一键端到端演示：三个连接器 → 血缘 → 索引 → 影响分析。

用法（需 PostgreSQL 可用，默认用 DG_DATABASE_URL）：
    $env:PYTHONPATH='src'
    python tools/demo_end_to_end.py

它会：
  1. 在临时目录造三个源：SQLite 库、DuckDB 库（含 marts schema）、Parquet 裸文件
  2. 分别采集（每个源独立命名空间）
  3. 解析一段真实数仓 SQL，写入列级血缘
  4. 重建派生索引
  5. 打印：实体统计 / 列级血缘 / 影响分析 / 采集健康度 / 解析质量

每一步都断言结果，跑完即是一份可复现的验收记录。
"""

from __future__ import annotations

import sqlite3
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, "src")

from sqlalchemy import text  # noqa: E402

from dg.collectors.base import run_collection  # noqa: E402
from dg.collectors.duckdb_source import DuckDbSource  # noqa: E402
from dg.collectors.guard import summarize_history  # noqa: E402
from dg.collectors.sqlite_source import SqliteSource  # noqa: E402
from dg.consumers.search_index import index_lag, rebuild_search_index  # noqa: E402
from dg.core.service import MetadataService  # noqa: E402
from dg.db import SessionLocal  # noqa: E402
from dg.lineage.service import ingest_sql, parse_quality_report  # noqa: E402
from dg.model import ModelRegistry  # noqa: E402

NS_SQLITE = "demo_oltp"
NS_DUCK = "demo_lake"

DSQL = """
INSERT INTO main.dim_customer
SELECT o.customer_id AS cust_key,
       c.name         AS cust_name,
       SUM(o.amount)  AS total_amount,
       md5(c.phone)   AS phone_hash
  FROM main.orders o
  JOIN main.customers c ON c.id = o.customer_id
 GROUP BY o.customer_id, c.name, c.phone
"""


def banner(step: str, title: str) -> None:
    print(f"\n{'=' * 72}\n{step}  {title}\n{'=' * 72}")


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="dg_demo_"))
    registry = ModelRegistry.load("model")
    session = SessionLocal()

    # ---------------------------------------------------------------- 造数据
    banner("[1/6]", "构造三个源")

    sqlite_path = tmp / "oltp.db"
    conn = sqlite3.connect(sqlite_path)
    conn.executescript(
        """
        CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT, phone TEXT);
        CREATE TABLE orders (customer_id INTEGER, amount REAL);
        CREATE TABLE dim_customer (cust_key INTEGER, cust_name TEXT,
                                   total_amount REAL, phone_hash TEXT);
        """
    )
    conn.commit()
    conn.close()
    print(f"  SQLite : {sqlite_path.name}（3 表）")

    duck_path = tmp / "lake.duckdb"
    parquet_path = tmp / "events_2026.parquet"
    import duckdb

    duck = duckdb.connect(str(duck_path))
    duck.execute("CREATE SCHEMA marts")
    duck.execute("CREATE TABLE main.raw_events (id INTEGER PRIMARY KEY, payload VARCHAR)")
    duck.execute("CREATE TABLE marts.dim_user (user_key INTEGER PRIMARY KEY, name VARCHAR NOT NULL)")
    duck.execute("CREATE VIEW main.v_events AS SELECT id FROM main.raw_events")
    duck.execute(
        f"COPY (SELECT 1 AS event_id, 'click' AS kind) TO '{parquet_path.as_posix()}' (FORMAT PARQUET)"
    )
    duck.close()
    print(f"  DuckDB : {duck_path.name}（2 表 + 1 视图）+ 裸文件 {parquet_path.name}")

    # ------------------------------------------------------------------ 采集
    banner("[2/6]", "采集（三个源 / 独立命名空间）")
    runs = [
        run_collection(session, registry, SqliteSource(str(sqlite_path)), namespace=NS_SQLITE),
        run_collection(
            session, registry,
            DuckDbSource(str(duck_path), files=[str(parquet_path)]),
            namespace=NS_DUCK,
        ),
    ]
    session.commit()
    for run in runs:
        mark = "✓" if run.status == "SUCCEEDED" else "✗"
        print(
            f"  {mark} {run.source:9} ns={run.namespace:10} status={run.status:9} "
            f"seen={run.datasets_seen:2} created={run.datasets_created:2} "
            f"cols={run.columns_seen:2} {run.duration_ms}ms"
        )
        if run.errors:
            print(f"      errors: {run.errors}")
    assert all(r.status == "SUCCEEDED" for r in runs), "采集必须全部成功"

    # ------------------------------------------------------------------ 血缘
    banner("[3/6]", "解析 SQL → 列级血缘")
    result = ingest_sql(session, registry, DSQL, dialect="hive", namespace=NS_SQLITE, actor="demo")
    session.commit()
    print(f"  语句={result.statements} 表级边={result.table_edges} 列级边={result.column_edges} "
          f"失败={result.failed} 降级={result.downgraded}")
    print(f"  未解析的表：{result.unresolved_tables or '（无）'}")
    assert result.column_edges == 4, f"期望 4 条列级边，实际 {result.column_edges}"
    assert result.failed == 0

    rows = session.execute(
        text(
            """
            SELECT split_part(from_urn,'.',-1) AS src,
                   split_part(to_urn,'.',-1)   AS dst,
                   transform, dependency_kind
              FROM edge
             WHERE edge_type='derivesFrom' AND from_urn LIKE '%:Column:%'
               AND from_urn LIKE :ns
             ORDER BY dst, src
            """
        ),
        {"ns": f"%{NS_SQLITE}%"},
    ).mappings().all()
    print("  列级血缘：")
    for r in rows:
        print(f"    {r['src']:12} → {r['dst']:14} [{r['transform']}/{r['dependency_kind']}]")

    # -------------------------------------------------------------- 影响分析
    banner("[4/6]", "影响分析（orders.amount 变更的下游）")
    svc = MetadataService(session, registry, actor="demo")
    focus = session.execute(
        text(
            "SELECT urn FROM entity WHERE urn LIKE :p AND entity_type='Column'"
        ),
        {"p": f"%{NS_SQLITE}%.orders.amount"},
    ).scalar()
    impact = svc.lineage(focus, "downstream", max_depth=3)
    for node in impact["nodes"]:
        print(f"    depth={node['depth']}  {node['urn']}")
    assert impact["nodes"], "影响分析必须能查到下游"

    # ------------------------------------------------------------------ 索引
    banner("[5/6]", "派生索引（可丢弃重建）")
    stats = rebuild_search_index(session)
    lag = index_lag(session)
    print(f"  重放 {stats.processed} 条事件 → {stats.indexed} 个文档；lag={lag['lag']}")
    assert lag["lag"] == 0

    # ------------------------------------------------------------ 健康与质量
    banner("[6/6]", "采集健康度与解析质量")
    history = session.execute(
        text(
            """
            SELECT source, namespace, status, started_at, block_reason
              FROM collect_run ORDER BY started_at DESC LIMIT 50
            """
        )
    ).mappings().all()
    health = summarize_history([dict(r) for r in history])
    for src in health["sources"]:
        print(f"  {src['source']}@{src['namespace']}: last={src['lastStatus']} "
              f"failures={src['consecutiveFailures']} runs={src['runs']}")
    print(f"  总体健康度：{health['health']}")

    quality = parse_quality_report(session, limit=5)
    print(f"  血缘边来源：{quality['edgeSources'] or '（无）'}")
    print(f"  解析样本（仅记录不够好的结果）：{quality['samplesByDialect'] or '（无，全部 exact）'}")

    total = session.execute(
        text("SELECT count(*) FROM entity WHERE deleted_at IS NULL")
    ).scalar()
    datasets = session.execute(
        text("SELECT count(*) FROM entity WHERE entity_type='Dataset' AND deleted_at IS NULL")
    ).scalar()
    columns = session.execute(
        text("SELECT count(*) FROM entity WHERE entity_type='Column' AND deleted_at IS NULL")
    ).scalar()
    edges = session.execute(text("SELECT count(*) FROM edge")).scalar()

    print(f"\n{'=' * 72}")
    print(f"结果：实体 {total}（Dataset {datasets} / Column {columns}），边 {edges}")
    print(f"    工程目录：{tmp}")
    print("    全部断言通过 ✓")
    print("=" * 72)

    session.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
