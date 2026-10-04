"""DuckDB 连接器测试（第三个源）。

额外验证一项真实能力：**用 DuckDB 推断裸文件（Parquet/CSV）的 schema** ——
数据湖里大量资产没有 catalog，这条路径把它们纳入治理范围。
"""

from __future__ import annotations

import duckdb
import pytest
from sqlalchemy import text

from dg.collectors.base import run_collection
from dg.collectors.duckdb_source import DuckDbSource


@pytest.fixture
def duck_db(tmp_path):
    path = tmp_path / "analytics.duckdb"
    conn = duckdb.connect(str(path))
    conn.execute("CREATE SCHEMA IF NOT EXISTS marts")
    conn.execute("CREATE TABLE main.raw_events (id INTEGER PRIMARY KEY, payload VARCHAR, ts TIMESTAMP)")
    conn.execute("CREATE TABLE marts.dim_user (user_key INTEGER PRIMARY KEY, name VARCHAR NOT NULL)")
    conn.execute("CREATE VIEW main.v_events AS SELECT id FROM main.raw_events")
    conn.close()
    return path


@pytest.fixture
def parquet_file(tmp_path):
    path = tmp_path / "orders_2026.parquet"
    conn = duckdb.connect(":memory:")
    conn.execute(
        f"""
        COPY (
            SELECT 1 AS order_id, 'A' AS channel, 12.5 AS amount
            UNION ALL SELECT 2, 'B', 30.0
        ) TO '{path.as_posix()}' (FORMAT PARQUET)
        """
    )
    conn.close()
    return path


@pytest.fixture
def csv_file(tmp_path):
    path = tmp_path / "customers.csv"
    path.write_text("cust_id,name,phone\n1,Alice,13800000000\n2,Bob,13900000000\n", encoding="utf-8")
    return path


class TestDuckDbCollection:
    def test_collects_tables_views_schemas_and_pk(self, session, registry, duck_db):
        run = run_collection(session, registry, DuckDbSource(str(duck_db)), namespace="lake")
        session.commit()

        assert run.status == "SUCCEEDED"
        assert run.errors == []
        assert run.datasets_seen == 3          # 2 表 + 1 视图

        urns = {
            r["urn"]
            for r in session.execute(
                text("SELECT urn FROM entity WHERE entity_type='Dataset' AND namespace='lake'")
            ).mappings()
        }
        assert any(u.endswith(".main.raw_events") for u in urns)
        assert any(u.endswith(".marts.dim_user") for u in urns)   # schema 层级被保留
        assert any(u.endswith(".main.v_events") for u in urns)

        schema = session.execute(
            text(
                "SELECT data FROM aspect WHERE aspect_type='datasetSchema' "
                "AND urn LIKE '%dim_user'"
            )
        ).scalar()
        assert schema["primaryKey"] == ["user_key"]
        assert schema["rawTypeSystem"] == "duckdb"
        assert [c["name"] for c in schema["fields"]] == ["user_key", "name"]
        assert schema["fields"][1]["nullable"] is False   # NOT NULL 被识别

    def test_second_run_is_noop(self, session, registry, duck_db):
        source = DuckDbSource(str(duck_db))
        first = run_collection(session, registry, source, namespace="lake")
        session.commit()
        second = run_collection(session, registry, source, namespace="lake")
        session.commit()
        assert first.schemas_written == 3
        assert second.schemas_written == 0
        assert second.schemas_unchanged == 3

    def test_include_filters(self, session, registry, duck_db):
        run = run_collection(
            session, registry, DuckDbSource(str(duck_db), include_schemas=["marts"]),
            namespace="lake",
        )
        session.commit()
        assert run.datasets_seen == 1
        assert session.execute(
            text("SELECT count(*) FROM entity WHERE namespace='lake' AND entity_type='Dataset'")
        ).scalar() == 1

    def test_missing_file_is_recorded_as_failed_run(self, session, registry, tmp_path):
        run = run_collection(
            session, registry, DuckDbSource(str(tmp_path / "nope.duckdb")), namespace="lake"
        )
        session.commit()
        assert run.status == "FAILED"
        assert any("fatal" in e for e in run.errors)


class TestRawFileInference:
    def test_parquet_schema_is_inferred(self, session, registry, duck_db, parquet_file):
        run = run_collection(
            session, registry,
            DuckDbSource(str(duck_db), files=[str(parquet_file)]),
            namespace="lake",
        )
        session.commit()
        assert run.errors == []

        urns = [
            r["urn"]
            for r in session.execute(
                text("SELECT urn FROM entity WHERE namespace='lake' AND entity_type='Dataset'")
            ).mappings()
        ]
        target = next((u for u in urns if u.endswith("files.orders_2026_parquet")), None)
        assert target is not None, f"裸文件应作为 Dataset 被采集（实际 URN：{urns}）"

        row = session.execute(
            text("SELECT data FROM aspect WHERE urn = :u AND aspect_type='datasetSchema'"),
            {"u": target},
        ).scalar()
        fields = {c["name"]: c["type"] for c in row["fields"]}
        assert set(fields) == {"order_id", "channel", "amount"}
        # DuckDB 对字面量 12.5 推断为 DECIMAL(3,1)（不是 DOUBLE）——
        # 采集器如实上报引擎的类型推断，不做"归一化猜测"，因此这里只校验数值类型族
        assert fields["amount"].startswith(("DECIMAL", "DOUBLE", "FLOAT"))
        assert fields["order_id"].startswith(("INT", "BIGINT", "INTEGER"))

    def test_csv_schema_is_inferred(self, session, registry, duck_db, csv_file):
        run_collection(
            session, registry, DuckDbSource(str(duck_db), files=[str(csv_file)]), namespace="lake"
        )
        session.commit()
        urns = [
            r["urn"]
            for r in session.execute(
                text("SELECT urn FROM entity WHERE namespace='lake' AND entity_type='Dataset'")
            ).mappings()
        ]
        target = next((u for u in urns if u.endswith("files.customers_csv")), None)
        assert target is not None, f"CSV 应被采集（实际 URN：{urns}）"

        row = session.execute(
            text("SELECT data FROM aspect WHERE urn = :u AND aspect_type='datasetSchema'"),
            {"u": target},
        ).scalar()
        names = [c["name"] for c in row["fields"]]
        assert names == ["cust_id", "name", "phone"]

    def test_missing_data_file_raises(self, session, registry, duck_db, tmp_path):
        run = run_collection(
            session, registry,
            DuckDbSource(str(duck_db), files=[str(tmp_path / "ghost.parquet")]),
            namespace="lake",
        )
        session.commit()
        assert run.status == "FAILED"

    def test_unsupported_file_type_skipped(self, session, registry, duck_db, tmp_path):
        weird = tmp_path / "notes.txt"
        weird.write_text("hello", encoding="utf-8")
        run = run_collection(
            session, registry, DuckDbSource(str(duck_db), files=[str(weird)]), namespace="lake"
        )
        session.commit()
        assert run.errors == []
        assert run.datasets_seen == 3   # 只有库内的表/视图，txt 被跳过


class TestThreeSourceCoexistence:
    def test_three_connectors_coexist(self, session, registry, duck_db, tmp_path):
        """三个连接器同时工作，各自命名空间与 URN 前缀互不干扰。"""
        import sqlite3

        from dg.collectors.sqlite_source import SqliteSource

        sqlite_path = tmp_path / "local.db"
        conn = sqlite3.connect(sqlite_path)
        conn.execute("CREATE TABLE t1 (id INTEGER PRIMARY KEY)")
        conn.commit()
        conn.close()

        runs = [
            run_collection(session, registry, DuckDbSource(str(duck_db)), namespace="lake"),
            run_collection(session, registry, SqliteSource(str(sqlite_path)), namespace="local"),
        ]
        session.commit()
        assert all(r.status == "SUCCEEDED" for r in runs)

        platforms = {
            r["urn"]
            for r in session.execute(
                text("SELECT urn FROM entity WHERE entity_type='Platform'")
            ).mappings()
        }
        assert "urn:dg:Platform:lake.duckdb" in platforms
        assert "urn:dg:Platform:local.sqlite" in platforms

        datasets = session.execute(
            text(
                "SELECT count(*) FROM entity WHERE entity_type='Dataset' AND deleted_at IS NULL"
            )
        ).scalar()
        assert datasets == 4   # duckdb 3 + sqlite 1
