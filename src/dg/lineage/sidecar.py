"""SQL 静态解析侧车（docs/10 §2）。

**为什么是一个独立进程而不是库调用**：
设计选型把「SQL 解析」放在 Python 侧（sqlglot 的方言覆盖与内置列级血缘远强于 JVM 侧现成方案），
Java 控制面通过内部 HTTP 调用。侧车只做**纯解析**：不连数据库、不写元数据。
解析结果由 Java 控制面负责解析成 URN 并写入血缘图 —— 这样"谁是真相源"没有歧义。

**为什么侧车不可用必须报错而不是返回空**：
空血缘与"解析没跑"在界面上长得一模一样。侧车 /healthz 不可达时 Java 侧会返回 502 并标注原因
（docs/09 §9.2 点名要避免的静默失败）。

启动：

    python -m dg.cli sidecar                 # 默认 127.0.0.1:8099
    python -m dg.cli sidecar --port 8099

Java 侧配置：

    dg.lineage.sidecar-url=http://127.0.0.1:8099
"""

from __future__ import annotations

from dataclasses import asdict
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from dg.lineage.parser import (
    SUPPORTED_DIALECTS,
    parse_sql,
    sqlglot_version,
)

SERVICE_NAME = "dg-sql-parse-sidecar"
SERVICE_VERSION = "0.1.0"


class ParseRequest(BaseModel):
    sql: str
    dialect: str = "hive"
    namespace: str = "prod"
    defaultSchema: str | None = None


class ParseResponse(BaseModel):
    service: str = SERVICE_NAME
    version: str = SERVICE_VERSION
    dialect: str
    sqlglotVersion: str = Field(default_factory=sqlglot_version)
    statements: int
    failed: int
    downgraded: int
    results: list[dict[str, Any]]


app = FastAPI(
    title="Data Governance · SQL parse sidecar",
    description="为 Java 控制面提供 sqlglot 列级血缘解析（纯解析，不落库）",
    version=SERVICE_VERSION,
)


@app.get("/healthz")
def healthz() -> dict[str, Any]:
    """探活（Java 侧据此判断侧车是否可用）。"""
    return {
        "status": "ok",
        "service": SERVICE_NAME,
        "version": SERVICE_VERSION,
        "sqlglotVersion": sqlglot_version(),
        "dialects": sorted(SUPPORTED_DIALECTS),
    }


@app.post("/api/v1/lineage/parse", response_model=ParseResponse)
def parse(request: ParseRequest) -> ParseResponse:
    """解析 SQL（多语句）并返回每条语句的结果。

    不写任何元数据：入 URN 解析、写边、登记失败样本都由 Java 控制面完成，
    保证「只有一个写入者、只有一个真相源」。
    """
    dialect = (request.dialect or "hive").lower()
    if dialect not in SUPPORTED_DIALECTS:
        # 显式拒绝未知方言，而不是静默退回默认方言 ——
        # 静默换方言会产生"看起来解析成功了但血缘是错的"这种最难查的问题。
        # 用 400 而不是 500：这是调用方的问题（可与"侧车不可用"区分开）
        raise HTTPException(
            status_code=400,
            detail=(
                f"不支持的方言 {dialect!r}；支持：{', '.join(sorted(SUPPORTED_DIALECTS))}"
            ),
        )

    results = parse_sql(request.sql, dialect=dialect, default_schema=request.defaultSchema)
    payloads = [_as_payload(r) for r in results]
    return ParseResponse(
        dialect=dialect,
        statements=len(payloads),
        failed=sum(1 for p in payloads if p["parseLevel"] == "failed"),
        downgraded=sum(1 for p in payloads if p["parseLevel"] not in ("exact", "failed")),
        results=payloads,
    )


def _as_payload(result) -> dict[str, Any]:
    """把 ParseResult 转成 JSON（列级边用 camelCase，与 Java/TS 侧一致）。"""
    payload = {
        "sql": result.sql,
        "dialect": result.dialect,
        "statementType": result.statement_type,
        "targetTable": result.target_table,
        "targetColumns": result.target_columns,
        "sourceTables": result.source_tables,
        "parseLevel": result.parse_level,
        "error": result.error,
        "warnings": result.warnings,
        "columnEdges": [asdict(edge) for edge in result.column_edges],
    }
    # dataclass 字段是 snake_case，转成 camelCase 后再返回
    payload["columnEdges"] = [
        {
            "fromTable": edge.from_table,
            "fromColumn": edge.from_column,
            "toTable": edge.to_table,
            "toColumn": edge.to_column,
            "transform": edge.transform,
            "expression": edge.expression,
            "dependencyKind": edge.dependency_kind,
            "parseLevel": edge.parse_level,
            "confidence": edge.confidence,
            "cardinality": edge.cardinality,
        }
        for edge in result.column_edges
    ]
    return payload
