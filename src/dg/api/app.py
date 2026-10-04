"""API 层（FastAPI）。

设计依据：
  - docs/09 §9.3 / §9.7：**授权判定必须唯一** —— 搜索、详情、写入、导出共用 Policy；
    搜索必须**前置过滤**（后过滤会泄露总数与分面统计）
  - docs/14 §5：UI 自身也走同一套 REST API，不做"UI 专用后门"

认证：Bearer 令牌（静态开发令牌或 JWT，见 dg.auth）。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from dg import __version__
from dg.auth import Forbidden, Policy, Principal
from dg.auth.deps import get_principal, require
from dg.config import settings
from dg.consumers.search_index import consume_search_index, index_lag, rebuild_search_index
from dg.core.service import Conflict, MetadataService, NotFound, ValidationFailed
from dg.db import SessionLocal, healthcheck
from dg.model import ModelError, ModelRegistry

STATIC_DIR = Path(__file__).parent / "static"

_registry: ModelRegistry | None = None


def get_registry() -> ModelRegistry:
    global _registry
    if _registry is None:
        _registry = ModelRegistry.load(settings.model_dir)
    return _registry


@asynccontextmanager
async def lifespan(app: FastAPI):
    get_registry()
    if settings.auth_disabled:
        print(
            "⚠️  DG_AUTH_MODE=disabled —— 认证已关闭，仅可用于本机开发，切勿用于任何共享环境。",
            flush=True,
        )
    yield


app = FastAPI(
    title="Data Governance Platform",
    version=__version__,
    description="通用数据治理平台 · Phase 0（元数据内核）",
    lifespan=lifespan,
)


@app.exception_handler(Forbidden)
async def _forbidden_handler(_request: Request, exc: Forbidden) -> JSONResponse:
    return JSONResponse(status_code=403, content={"detail": str(exc)})


def get_session():
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def get_service(session: Session = Depends(get_session)) -> MetadataService:
    return MetadataService(session, get_registry(), actor="api")


# ---------------------------------------------------------------------------
# 授权辅助：分级读取与可见性判定（唯一来源 = Policy）
# ---------------------------------------------------------------------------
def _classification_of(session: Session, urn: str) -> str | None:
    row = session.execute(
        text("SELECT data -> 'level' AS level FROM aspect WHERE urn = :u AND aspect_type = 'classification'"),
        {"u": urn},
    ).scalar()
    return row


def _asset_exists(session: Session, urn: str) -> bool:
    return bool(
        session.execute(
            text("SELECT 1 FROM entity WHERE urn = :u AND deleted_at IS NULL"), {"u": urn}
        ).scalar()
    )


def _ensure_visible(principal: Principal, session: Session, urn: str) -> None:
    """资产可见性判定。

    不可见时返回 **404**（而非 403）—— 不确认资产存在性，避免被用于枚举资产名。
    """
    if not _asset_exists(session, urn):
        raise HTTPException(status_code=404, detail=f"asset not found: {urn}")
    if not Policy.can_see_classification(principal, _classification_of(session, urn)):
        raise HTTPException(status_code=404, detail=f"asset not found: {urn}")


# ---------------------------------------------------------------------------
# 身份与运维
# ---------------------------------------------------------------------------
@app.get("/healthz", tags=["ops"])
def healthz() -> dict:
    """公开健康检查（不含库内细节）。"""
    try:
        healthcheck()
        return {"status": "ok", "version": __version__}
    except Exception as exc:  # pragma: no cover
        return JSONResponse(status_code=503, content={"status": "degraded", "error": str(exc)})  # type: ignore[return-value]


@app.get("/api/v1/admin/health", tags=["ops"])
def admin_health(principal: Principal = Depends(require("model:read"))) -> dict:
    return {"status": "ok", "version": __version__, "principal": principal.to_dict(), **healthcheck()}


@app.get("/api/v1/me", tags=["identity"])
def whoami(principal: Principal = Depends(get_principal)) -> dict:
    """当前身份与生效权限（排障必备：让用户自己看清能被允许做什么）。"""
    return {
        **principal.to_dict(),
        "permissions": sorted(Policy.permissions(principal)),
        "visibleLevels": Policy.visible_levels(principal),
        "maxVisibleLevel": Policy.max_visible_level(principal),
    }


@app.get("/api/v1/model", tags=["ops"])
def model_summary(principal: Principal = Depends(require("model:read"))) -> dict:
    registry = get_registry()
    return {
        **registry.summary(),
        "entityTypes": sorted(registry.entity_types),
        "lineageRelationships": sorted(registry.lineage_relationship_names()),
    }


@app.get("/api/v1/index/lag", tags=["ops"])
def api_index_lag(
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    return index_lag(session)


@app.get("/api/v1/collect/runs", tags=["ops"])
def collect_runs(
    limit: int = Query(20, ge=1, le=200),
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    """采集运行历史（排障用：某次采集为什么没更新/为什么被拦截）。"""
    rows = session.execute(
        text(
            """
            SELECT run_id, source, namespace, scope, status, block_reason,
                   datasets_seen, datasets_created, schemas_written, schemas_unchanged,
                   deleted_candidates, deleted, errors, duration_ms, started_at, finished_at
              FROM collect_run ORDER BY started_at DESC LIMIT :limit
            """
        ),
        {"limit": limit},
    ).mappings().all()
    return {"count": len(rows), "runs": [dict(r) for r in rows]}


@app.get("/api/v1/collect/health", tags=["ops"])
def collect_health(
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    """采集健康度总览。

    这是"目录悄悄停止更新"这一隐性失效的探测器（`09` §9.1）。
    """
    from dg.collectors.guard import summarize_history

    runs = session.execute(
        text(
            """
            SELECT source, namespace, status, started_at, block_reason
              FROM collect_run ORDER BY started_at DESC LIMIT 500
            """
        )
    ).mappings().all()
    state = session.execute(
        text(
            """
            SELECT source, namespace, scope, entity_count, last_status,
                   consecutive_failures, last_success_at, updated_at
              FROM collector_state ORDER BY source, namespace, scope
            """
        )
    ).mappings().all()

    summary = summarize_history([dict(r) for r in runs])
    return {**summary, "state": [dict(s) for s in state]}


@app.get("/api/v1/schedules", tags=["ops"])
def api_schedules(
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    """采集调度清单（含上次运行结果与下次计划时间）。"""
    from dg.scheduler import list_schedules

    rows = list_schedules(session)
    return {"count": len(rows), "schedules": rows}


@app.post("/api/v1/schedules/{name}/run", tags=["ops"])
def api_run_schedule(
    name: str,
    accept_deletions: bool = Query(False, description="人工确认后接受删除"),
    principal: Principal = Depends(require("collect:run")),
    session: Session = Depends(get_session),
) -> dict:
    """立即执行一个调度（异步受限：同步执行并返回结果，适合排障与手动补采）。"""
    from dg.scheduler import execute_schedule, schedule_from_row

    row = session.execute(
        text(
            """
            SELECT name, source, dsn, namespace, database_name, schemas, tables,
                   cron, enabled, guard_config, timezone
              FROM collect_schedule WHERE name = :n
            """
        ),
        {"n": name},
    ).mappings().first()
    if row is None:
        raise HTTPException(status_code=404, detail=f"schedule not found: {name}")

    sched = schedule_from_row(dict(row))
    try:
        return execute_schedule(
            session,
            get_registry(),
            sched,
            actor=principal.id,
            accept_deletions=accept_deletions,
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/v1/lineage/quality", tags=["lineage"])
def lineage_quality(
    limit: int = Query(20, ge=1, le=200),
    principal: Principal = Depends(require("lineage:read")),
    session: Session = Depends(get_session),
) -> dict:
    """血缘解析质量：样本库聚合 + 边来源分布。

    回答的关键问题：**"血缘覆盖率为什么低"** —— 是没采集，还是解析不出来？
    """
    from dg.lineage import parse_quality_report, sqlglot_version

    report = parse_quality_report(session, limit=limit)
    edge_total = session.execute(
        text("SELECT count(*) FROM edge WHERE edge_type='derivesFrom' AND state='ACTIVE'")
    ).scalar()
    column_edges = session.execute(
        text(
            "SELECT count(*) FROM edge WHERE edge_type='derivesFrom'"
            " AND state='ACTIVE' AND from_urn LIKE '%:Column:%'"
        )
    ).scalar()
    return {
        **report,
        "sqlglotVersion": sqlglot_version(),
        "activeLineageEdges": int(edge_total or 0),
        "activeColumnEdges": int(column_edges or 0),
    }


class SqlParseRequest(BaseModel):
    sql: str = Field(..., description="SQL 文本（可含多条语句）")
    dialect: str = Field("hive")
    namespace: str = Field("prod")
    recordSamples: bool = True


@app.post("/api/v1/lineage/parse", tags=["lineage"])
def lineage_parse(
    body: SqlParseRequest,
    principal: Principal = Depends(require("lineage:write")),
    session: Session = Depends(get_session),
) -> dict:
    """解析 SQL 并把列级血缘写入图（表名解析不上的边不写，但会报告）。"""
    from dg.lineage import ingest_sql

    try:
        result = ingest_sql(
            session,
            get_registry(),
            body.sql,
            dialect=body.dialect,
            namespace=body.namespace,
            actor=principal.id,
            record_samples=body.recordSamples,
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return result.as_dict()


@app.get("/api/v1/alerts", tags=["ops"])
def api_alerts(
    state: str = Query("FIRING", pattern="^(FIRING|RESOLVED)$"),
    limit: int = Query(50, ge=1, le=200),
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    """告警清单（默认只看活跃告警）。

    这是"目录悄悄停止更新"这一隐性失效的**主动探测器**（`09` §9.1）。
    """
    from dg.alerting import list_alerts

    rows = list_alerts(session, state=state, limit=limit)
    return {"count": len(rows), "state": state, "alerts": rows}


@app.post("/api/v1/alerts/check", tags=["ops"])
def api_alert_check(
    dry_run: bool = Query(False),
    principal: Principal = Depends(require("index:consume")),
    session: Session = Depends(get_session),
) -> dict:
    """立即执行一次告警巡检（对账 + 投递）。调度器也会周期执行。"""
    from dg.alerting import check_and_notify

    return check_and_notify(
        session,
        remind_interval_seconds=settings.alert_remind_interval,
        reopen_cooldown_seconds=settings.alert_reopen_cooldown,
        dry_run=dry_run,
    )


@app.post("/api/v1/alerts/{alert_id}/ack", tags=["ops"])
def api_alert_ack(
    alert_id: int,
    principal: Principal = Depends(require("index:consume")),
    session: Session = Depends(get_session),
) -> dict:
    """确认告警（记录确认人与时间；不影响状态机，条件恢复时仍会自动 RESOLVED）。"""
    from dg.alerting import acknowledge_alert

    try:
        return acknowledge_alert(session, alert_id, by=principal.id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get("/api/v1/audit", tags=["ops"])
def recent_audit(
    limit: int = Query(20, ge=1, le=200),
    principal: Principal = Depends(require("model:read")),
    session: Session = Depends(get_session),
) -> dict:
    """最近审计记录（管理员排障与合规取证的最小入口）。"""
    rows = session.execute(
        text(
            """
            SELECT seq, actor, action, urn, aspect_type, created_at
              FROM audit_log ORDER BY seq DESC LIMIT :limit
            """
        ),
        {"limit": limit},
    ).mappings().all()
    return {"count": len(rows), "entries": [dict(r) for r in rows]}


# ---------------------------------------------------------------------------
# 搜索（前置授权过滤）
# ---------------------------------------------------------------------------
@app.get("/api/v1/search", tags=["discovery"])
def search(
    q: str = Query("", description="关键词（标识符会按 _ / 驼峰切分）"),
    entity_type: str | None = Query(None, alias="type"),
    limit: int = Query(20, ge=1, le=200),
    principal: Principal = Depends(require("asset:read")),
    session: Session = Depends(get_session),
) -> dict:
    # 授权过滤必须**前置**注入查询（09 §9.3）
    visibility_clause, params = Policy.search_visibility_filter(principal)
    where = [visibility_clause]
    params["limit"] = limit

    if q.strip():
        where.append("tsv @@ plainto_tsquery('simple', :q)")
        params["q"] = q
    if entity_type:
        where.append("entity_type = :et")
        params["et"] = entity_type

    rows = session.execute(
        text(
            f"""
            SELECT urn, entity_type, display_name, description, tags, owners,
                   classification, platform, container, indexed_at
              FROM search_doc
             WHERE {' AND '.join(where)}
             ORDER BY indexed_at DESC, urn
             LIMIT :limit
            """
        ),
        params,
    ).mappings().all()

    lag = index_lag(session)
    return {
        "query": q,
        "count": len(rows),
        "results": [dict(r) for r in rows],
        "indexLag": lag["lag"],
        "visibleLevels": Policy.visible_levels(principal),
    }


# ---------------------------------------------------------------------------
# 资产读取
# ---------------------------------------------------------------------------
@app.get("/api/v1/assets/{urn}", tags=["assets"])
def get_asset(
    urn: str,
    principal: Principal = Depends(require("asset:read")),
    session: Session = Depends(get_session),
) -> dict:
    from dg.core.urn import parse_urn

    try:
        parsed_urn = parse_urn(urn)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    _ensure_visible(principal, session, urn)

    entity = session.execute(
        text(
            """
            SELECT urn, entity_type, namespace, tenant, display_name, lifecycle,
                   properties, created_at, updated_at
              FROM entity WHERE urn = :urn AND deleted_at IS NULL
            """
        ),
        {"urn": urn},
    ).mappings().first()

    aspects = {
        r["aspect_type"]: r["data"]
        for r in session.execute(
            text("SELECT aspect_type, data FROM aspect WHERE urn = :urn"), {"urn": urn}
        ).mappings()
    }

    return {
        "urn": urn,
        "entityType": parsed_urn.entity_type,
        "displayName": entity["display_name"],
        "namespace": entity["namespace"],
        "lifecycle": entity["lifecycle"],
        "createdAt": entity["created_at"],
        "updatedAt": entity["updated_at"],
        "aspects": aspects,
    }


@app.get("/api/v1/assets/{urn}/aspects/{aspect_type}", tags=["assets"])
def get_asset_aspect(
    urn: str,
    aspect_type: str,
    principal: Principal = Depends(require("asset:read")),
    session: Session = Depends(get_session),
) -> dict:
    _ensure_visible(principal, session, urn)
    row = session.execute(
        text(
            "SELECT version, data, field_sources, updated_at FROM aspect "
            "WHERE urn = :u AND aspect_type = :a"
        ),
        {"u": urn, "a": aspect_type},
    ).mappings().first()
    if row is None:
        raise HTTPException(status_code=404, detail=f"aspect not found: {urn}#{aspect_type}")
    return {"urn": urn, "aspectType": aspect_type, **dict(row)}


@app.get("/api/v1/assets/{urn}/lineage", tags=["lineage"])
def get_lineage(
    urn: str,
    direction: str = Query("downstream", pattern="^(upstream|downstream)$"),
    depth: int = Query(3, ge=1, le=10),
    min_confidence: float = Query(0.0, ge=0.0, le=1.0),
    principal: Principal = Depends(require("lineage:read")),
    session: Session = Depends(get_session),
) -> dict:
    _ensure_visible(principal, session, urn)
    service = MetadataService(session, get_registry(), actor="api")
    try:
        result = service.lineage(urn, direction, max_depth=depth, min_confidence=min_confidence)
    except ValidationFailed as exc:
        raise HTTPException(status_code=400, detail=exc.errors) from exc

    # 血缘节点也要做可见性裁剪：不能通过血缘把无权资产的名字带出来
    visible_nodes = []
    for node in result["nodes"]:
        if Policy.can_see_classification(
            principal, _classification_of(session, node["urn"])
        ):
            visible_nodes.append(node)
    hidden = len(result["nodes"]) - len(visible_nodes)
    result["nodes"] = visible_nodes
    result["hiddenNodes"] = hidden
    return result


# ---------------------------------------------------------------------------
# 资产写入
# ---------------------------------------------------------------------------
class AspectWrite(BaseModel):
    data: dict
    source: str = Field("MANUAL", description="MANUAL|IMPORTED|AI_GENERATED|AUTO_COLLECTED")
    expectedVersion: int | None = None
    runId: str | None = None


@app.post("/api/v1/assets/{urn}/aspects/{aspect_type}", tags=["assets"])
def write_aspect(
    urn: str,
    aspect_type: str,
    body: AspectWrite,
    create_entity_if_missing: bool = Query(True),
    entity_type: str = Query("Dataset"),
    principal: Principal = Depends(require("asset:write")),
    session: Session = Depends(get_session),
) -> dict:
    # 写入既有资产时同样要做可见性判定（避免通过写入探测资产内容）
    if _asset_exists(session, urn):
        _ensure_visible(principal, session, urn)

    # 分级字段需要治理权限（普通 EDITOR 不能自行把 L4 降级为 L1）
    if aspect_type == "classification" and not Policy.can(principal, "governance:write"):
        raise HTTPException(
            status_code=403,
            detail="修改分级需要 governance:write 权限（STEWARD/ADMIN）",
        )

    service = MetadataService(session, get_registry(), actor=principal.id)
    try:
        if create_entity_if_missing:
            service.ensure_entity(urn, entity_type)
        result = service.upsert_aspect(
            urn,
            aspect_type,
            body.data,
            source=body.source,
            expected_version=body.expectedVersion,
            run_id=body.runId,
        )
    except NotFound as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Conflict as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except (ValidationFailed, ModelError) as exc:
        detail = getattr(exc, "errors", None) or str(exc)
        raise HTTPException(status_code=422, detail=detail) from exc

    return {
        "urn": result.urn,
        "aspectType": result.aspect_type,
        "version": result.version,
        "eventSeq": result.event_seq,
        "changedFields": result.changed_fields,
        "protectedFields": result.protected_fields,
        "created": result.created,
    }


class EdgeWrite(BaseModel):
    fromUrn: str
    toUrn: str
    edgeType: str = "derivesFrom"
    source: str = "manual"
    confidence: float = 1.0
    transform: str | None = None
    transformExpression: str | None = None
    dependencyKind: str = "VALUE"
    parseLevel: str | None = None
    viaJob: str | None = None


@app.post("/api/v1/edges", tags=["lineage"])
def write_edge(
    body: EdgeWrite,
    principal: Principal = Depends(require("lineage:write")),
    session: Session = Depends(get_session),
) -> dict:
    service = MetadataService(session, get_registry(), actor=principal.id)
    try:
        edge_id = service.upsert_edge(
            body.fromUrn,
            body.toUrn,
            body.edgeType,
            source=body.source,
            confidence=body.confidence,
            transform=body.transform,
            transform_expression=body.transformExpression,
            dependency_kind=body.dependencyKind,
            parse_level=body.parseLevel,
            via_job=body.viaJob,
        )
    except ModelError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except ValidationFailed as exc:
        raise HTTPException(status_code=422, detail=exc.errors) from exc
    return {"edgeId": edge_id}


# ---------------------------------------------------------------------------
# 派生索引维护（运维动作，仅 ADMIN）
# ---------------------------------------------------------------------------
@app.post("/api/v1/index/rebuild", tags=["ops"])
def api_rebuild_index(
    principal: Principal = Depends(require("index:rebuild")),
    session: Session = Depends(get_session),
) -> dict:
    stats = rebuild_search_index(session)
    return {
        "processed": stats.processed,
        "indexed": stats.indexed,
        "deleted": stats.deleted,
        "fromSeq": stats.from_seq,
        "toSeq": stats.to_seq,
    }


@app.post("/api/v1/index/consume", tags=["ops"])
def api_consume_index(
    principal: Principal = Depends(require("index:consume")),
    session: Session = Depends(get_session),
) -> dict:
    stats = consume_search_index(session)
    return {"processed": stats.processed, "indexed": stats.indexed, "toSeq": stats.to_seq}


# ---------------------------------------------------------------------------
# 最小 UI（Phase 0 骨架；将替换为 React 应用，见 docs/14 与 docs/10 §1）
# ---------------------------------------------------------------------------
@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def ui_root() -> HTMLResponse:
    index = STATIC_DIR / "index.html"
    if not index.exists():
        return HTMLResponse("<h1>UI not built</h1>", status_code=404)
    return HTMLResponse(index.read_text(encoding="utf-8"))
