"""SQL 解析侧车（dg.lineage.sidecar）的接口测试。

侧车是 Java 控制面血缘能力的**外部依赖**，因此它自己必须有测试：
侧车"看起来在跑但解析结果静默变空"是这套架构里最危险的失效模式 ——
它会表现为"血缘覆盖率低"，而不是任何一条报错。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from dg.lineage.sidecar import SERVICE_NAME, app

client = TestClient(app)


def test_healthz_exposes_service_and_dialects() -> None:
    response = client.get("/healthz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == SERVICE_NAME
    assert body["sqlglotVersion"]
    assert len(body["dialects"]) >= 15


def test_parse_returns_column_edges() -> None:
    sql = "INSERT INTO dwd_orders SELECT o.order_id, o.amount FROM ods.orders o"
    response = client.post("/api/v1/lineage/parse",
                           json={"sql": sql, "dialect": "hive", "namespace": "prod"})
    assert response.status_code == 200
    body = response.json()
    assert body["statements"] == 1
    assert body["failed"] == 0
    result = body["results"][0]
    assert result["targetTable"] == "dwd_orders"
    assert "ods.orders" in result["sourceTables"]
    assert result["parseLevel"] == "exact"
    assert result["columnEdges"], "列级血缘不能为空"
    edge = result["columnEdges"][0]
    # 方向约定：from = 上游（docs/09 §9.2）
    assert edge["fromTable"] == "ods.orders"
    assert edge["toTable"] == "dwd_orders"
    assert edge["dependencyKind"] in ("VALUE", "CONTROL")


def test_parse_is_pure_and_never_writes_metadata() -> None:
    """侧车不连数据库：它只解析。写入血缘是 Java 控制面的职责。"""
    sql = "INSERT INTO a SELECT x FROM b"
    first = client.post("/api/v1/lineage/parse", json={"sql": sql, "dialect": "hive"}).json()
    second = client.post("/api/v1/lineage/parse", json={"sql": sql, "dialect": "hive"}).json()
    assert first == second, "同一输入必须得到同一结果（无隐藏状态）"


def test_unknown_dialect_is_rejected_not_silently_downgraded() -> None:
    """未知方言必须报错：静默换方言会产生'看起来解析成功但血缘是错的'最难查的问题。"""
    response = client.post("/api/v1/lineage/parse",
                           json={"sql": "SELECT 1", "dialect": "klingon"})
    assert response.status_code >= 400


def test_broken_sql_is_reported_as_failed_with_sample_material() -> None:
    response = client.post("/api/v1/lineage/parse",
                           json={"sql": "INSERT INTO a SELEC broken FROM", "dialect": "hive"})
    assert response.status_code == 200
    body = response.json()
    assert body["failed"] >= 1
    failed = next(item for item in body["results"] if item["parseLevel"] == "failed")
    assert failed["error"], "失败必须带原因（要落样本库做方言覆盖率运营）"


def test_multi_statement_script_is_split() -> None:
    sql = ("INSERT INTO t1 SELECT a FROM s1;\n"
           "INSERT INTO t2 SELECT b FROM s2;")
    body = client.post("/api/v1/lineage/parse",
                       json={"sql": sql, "dialect": "hive"}).json()
    assert body["statements"] == 2
    targets = {item["targetTable"] for item in body["results"]}
    assert targets == {"t1", "t2"}


def test_non_lineage_statements_are_skipped_without_error() -> None:
    """DDL/会话控制语句没有血缘价值，跳过且不报错。"""
    body = client.post("/api/v1/lineage/parse",
                       json={"sql": "SET hive.exec.dynamic.partition=true;",
                             "dialect": "hive"}).json()
    assert body["statements"] == 0
    assert body["failed"] == 0


@pytest.mark.parametrize("dialect", ["hive", "spark", "postgres", "trino", "bigquery"])
def test_dialects_are_accepted(dialect: str) -> None:
    body = client.post("/api/v1/lineage/parse",
                       json={"sql": "INSERT INTO t SELECT c FROM s", "dialect": dialect}).json()
    assert body["statements"] >= 1
