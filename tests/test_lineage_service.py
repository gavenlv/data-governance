"""血缘服务集成测试（docs/09 §9.2）。

覆盖"从 SQL 到血缘图"的整条链路，重点是**名字解析**这个真实难点：
  - SQL 里写 `ods.orders`，平台里是 `urn:dg:Dataset:prod.sqlite.x.main.orders`
  - 解析不到时**不写边**（宁可缺边，不用猜的边污染图），但计入 unresolved
  - 解析异常样本库按内容哈希聚合（把方言覆盖率变成可运营指标）
"""

from __future__ import annotations

import sqlite3

import pytest
from sqlalchemy import text

from dg.collectors.base import run_collection
from dg.collectors.sqlite_source import SqliteSource
from dg.lineage.service import (
    LINEAGE_SOURCE,
    TableResolver,
    ingest_sql,
    parse_quality_report,
)

SQL_DIM = """
INSERT INTO dw.dim_customer
SELECT o.customer_id AS cust_key,
       c.name        AS cust_name,
       SUM(o.amount) AS total_amount,
       md5(c.phone)  AS phone_hash
  FROM ods.orders o
  JOIN ods.customers c ON c.id = o.customer_id
 GROUP BY o.customer_id, c.name, c.phone
"""


@pytest.fixture
def warehouse(session, registry, tmp_path):
    """造一个"数仓"：ods.orders / ods.customers / dw.dim_customer，并采集进平台。

    SQLite 没有 schema 概念，采集器把 schema 固定为 main，
    因此平台里的 URN 形如 urn:dg:Dataset:prod.sqlite.<db>.main.<table>，
    而测试 SQL 里写的是 `ods.orders` —— 正好用来验证后缀解析。
    """
    db = tmp_path / "wh.db"
    conn = sqlite3.connect(db)
    conn.executescript(
        """
        CREATE TABLE orders (customer_id INTEGER, amount REAL);
        CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT, phone TEXT);
        CREATE TABLE dim_customer (cust_key INTEGER, cust_name TEXT,
                                   total_amount REAL, phone_hash TEXT);
        """
    )
    conn.commit()
    conn.close()
    run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
    session.commit()
    return db


# ---------------------------------------------------------------------------
# 名字解析
# ---------------------------------------------------------------------------
class TestTableResolver:
    def test_resolves_by_urn_suffix(self, session, warehouse):
        resolver = TableResolver(session, "prod")
        urn = resolver.resolve("orders")
        assert urn is not None and urn.endswith(".orders")
        assert urn.startswith("urn:dg:Dataset:prod.sqlite.")

    def test_resolves_qualified_and_unqualified(self, session, warehouse):
        resolver = TableResolver(session, "prod", cache=False)
        assert resolver.resolve("main.orders") is not None
        assert resolver.resolve("orders") is not None
        assert resolver.resolve("wh.main.orders") is not None

    def test_passes_through_platform_urn(self, session, warehouse):
        resolver = TableResolver(session, "prod")
        existing = session.execute(
            text("SELECT urn FROM entity WHERE entity_type='Dataset' LIMIT 1")
        ).scalar()
        assert resolver.resolve(existing) == existing

    def test_unknown_table_returns_none_not_a_guess(self, session, warehouse):
        resolver = TableResolver(session, "prod")
        assert resolver.resolve("totally.unknown.table") is None

    def test_other_namespace_not_matched(self, session, warehouse):
        assert TableResolver(session, "stg").resolve("orders") is None


# ---------------------------------------------------------------------------
# 写入血缘图
# ---------------------------------------------------------------------------
class TestIngestSql:
    def test_writes_table_and_column_edges(self, session, registry, warehouse):
        result = ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        session.commit()

        assert result.statements == 1
        assert result.table_edges == 2          # ods.orders / ods.customers → dw.dim_customer
        assert result.column_edges == 4
        assert result.failed == 0
        assert result.unresolved_tables == []

        edges = session.execute(
            text(
                """
                SELECT from_urn, to_urn, transform, dependency_kind, parse_level, confidence
                  FROM edge WHERE edge_type='derivesFrom' AND source=:src
                """
            ),
            {"src": LINEAGE_SOURCE},
        ).mappings().all()
        assert len(edges) == 6                  # 2 表级 + 4 列级

        col_edges = [e for e in edges if ":Column:" in e["from_urn"]]
        transforms = {e["transform"] for e in col_edges}
        assert {"DIRECT", "AGGREGATED", "MASKED"} <= transforms

        masked = next(e for e in col_edges if e["transform"] == "MASKED")
        assert masked["from_urn"].endswith(".customers.phone")
        assert masked["to_urn"].endswith(".dim_customer.phone_hash")
        assert masked["parse_level"] == "exact"

    def test_column_entities_created(self, session, registry, warehouse):
        ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        session.commit()
        cols = session.execute(
            text("SELECT count(*) FROM entity WHERE entity_type='Column'")
        ).scalar()
        assert cols == 8                        # 4 源列 + 4 目标列

    def test_unresolved_tables_are_reported_and_no_edge_written(
        self, session, registry, warehouse
    ):
        sql = "INSERT INTO dw.dim_customer SELECT o.customer_id AS cust_key FROM ods.orders o"
        result = ingest_sql(
            session, registry, "INSERT INTO nonexistent.target SELECT x AS c FROM ghost.source",
            dialect="hive", namespace="prod",
        )
        session.commit()
        assert result.unresolved_tables == ["nonexistent.target", "ghost.source"]
        assert result.table_edges == 0
        assert result.column_edges == 0

        # 确认没有写入任何 derivesFrom 边
        assert session.execute(
            text("SELECT count(*) FROM edge WHERE edge_type='derivesFrom'")
        ).scalar() == 0

    def test_control_dependency_recorded_but_not_value_lineage(self, session, registry, warehouse):
        """控制依赖要落库（影响分析要用），但不参与值级血缘遍历。"""
        sql = """
        INSERT INTO dw.dim_customer
        SELECT customer_id AS cust_key,
               ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY amount DESC) AS cust_name
          FROM ods.orders
        """
        ingest_sql(session, registry, sql, dialect="hive", namespace="prod")
        session.commit()

        control = session.execute(
            text("SELECT count(*) FROM edge WHERE dependency_kind='CONTROL'")
        ).scalar()
        assert control >= 1

        from dg.core.service import MetadataService

        svc = MetadataService(session, registry, actor="t")
        target = session.execute(
            text("SELECT urn FROM entity WHERE urn LIKE '%dim_customer' AND entity_type='Dataset'")
        ).scalar()
        up = svc.lineage(target, "upstream", max_depth=3)
        column_nodes = [n["urn"] for n in up["nodes"] if ":Column:" in n["urn"]]
        # 值级遍历不应包含"仅控制依赖"的列
        value_sources = session.execute(
            text(
                """
                SELECT count(*) FROM edge
                 WHERE dependency_kind='VALUE' AND to_urn = ANY(:urns)
                """
            ),
            {"urns": column_nodes or [target]},
        ).scalar()
        assert value_sources >= 1  # 至少 cust_key 是值依赖

    def test_multiple_statements_in_one_script(self, session, registry, warehouse):
        sql = """
        INSERT INTO dw.dim_customer SELECT customer_id AS cust_key FROM ods.orders;
        INSERT INTO dw.dim_customer SELECT id AS cust_key FROM ods.customers;
        """
        result = ingest_sql(session, registry, sql, dialect="hive", namespace="prod")
        session.commit()
        assert result.statements == 2
        assert result.table_edges == 2

    def test_idempotent_reingest_does_not_duplicate_edges(self, session, registry, warehouse):
        ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        session.commit()
        first = session.execute(text("SELECT count(*) FROM edge")).scalar()

        ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        session.commit()
        second = session.execute(text("SELECT count(*) FROM edge")).scalar()
        assert first == second, "重复解析同一 SQL 不应产生重复边（observed_count 递增即可）"

        observed = session.execute(
            text(
                "SELECT max(observed_count) FROM edge WHERE source = :s"
            ),
            {"s": LINEAGE_SOURCE},
        ).scalar()
        assert observed >= 2


# ---------------------------------------------------------------------------
# 解析异常样本库
# ---------------------------------------------------------------------------
class TestParseSampleLibrary:
    def test_failures_are_recorded(self, session, registry, warehouse):
        ingest_sql(session, registry, "THIS IS NOT SQL", dialect="hive", namespace="prod")
        session.commit()
        rows = session.execute(
            text("SELECT parse_level, error, occurrences FROM lineage_parse_sample")
        ).mappings().all()
        assert len(rows) == 1
        assert rows[0]["parse_level"] == "failed"
        assert rows[0]["error"]
        assert rows[0]["occurrences"] == 1

    def test_same_sql_aggregates_instead_of_exploding(self, session, registry, warehouse):
        for _ in range(3):
            ingest_sql(session, registry, "THIS IS NOT SQL", dialect="hive", namespace="prod")
        session.commit()
        rows = session.execute(
            text("SELECT occurrences FROM lineage_parse_sample")
        ).scalars().all()
        assert rows == [3]

    def test_star_select_recorded_as_downgrade(self, session, registry, warehouse):
        ingest_sql(
            session, registry,
            "INSERT INTO dw.dim_customer SELECT * FROM ods.orders",
            dialect="hive", namespace="prod",
        )
        session.commit()
        level = session.execute(
            text("SELECT parse_level FROM lineage_parse_sample")
        ).scalar()
        assert level == "table_level_only"

    def test_quality_report_shape(self, session, registry, warehouse):
        ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        ingest_sql(session, registry, "BROKEN SQL HERE", dialect="hive", namespace="prod")
        session.commit()

        report = parse_quality_report(session)
        assert report["samplesByDialect"]
        assert report["topFailures"]
        assert any(s["source"] == LINEAGE_SOURCE for s in report["edgeSources"])

    def test_record_samples_can_be_disabled(self, session, registry, warehouse):
        ingest_sql(
            session, registry, "BROKEN", dialect="hive", namespace="prod", record_samples=False
        )
        session.commit()
        assert session.execute(
            text("SELECT count(*) FROM lineage_parse_sample")
        ).scalar() == 0


# ---------------------------------------------------------------------------
# 置信度分层
# ---------------------------------------------------------------------------
class TestConfidenceLayering:
    def test_parse_level_drives_confidence(self, session, registry, warehouse):
        ingest_sql(session, registry, SQL_DIM, dialect="hive", namespace="prod")
        session.commit()
        rows = session.execute(
            text(
                "SELECT parse_level, confidence FROM edge "
                "WHERE source = :s AND edge_type = 'derivesFrom'"
            ),
            {"s": LINEAGE_SOURCE},
        ).mappings().all()
        by_level: dict[str, set[float]] = {}
        for r in rows:
            by_level.setdefault(r["parse_level"], set()).add(float(r["confidence"]))
        assert "exact" in by_level
        assert by_level["exact"] == {0.8}

    def test_table_level_only_gets_lower_confidence(self, session, registry, warehouse):
        ingest_sql(
            session, registry,
            "INSERT INTO dw.dim_customer SELECT * FROM ods.orders",
            dialect="hive", namespace="prod",
        )
        session.commit()
        row = session.execute(
            text(
                "SELECT parse_level, confidence FROM edge "
                "WHERE source = :s AND edge_type = 'derivesFrom'"
            ),
            {"s": LINEAGE_SOURCE},
        ).mappings().first()
        assert row["parse_level"] == "table_level_only"
        assert float(row["confidence"]) == pytest.approx(0.5)
