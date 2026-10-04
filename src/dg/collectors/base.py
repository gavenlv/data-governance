"""采集框架（docs/09 §9.1）。

流水线：Source（读源系统）→ Normalizer（统一模型）→ Sink（写真相源 + 事件）
并叠加三层保障：
  - **护栏**（guard.py）：实体数骤降/删除比例异常 → BLOCKED，本轮不删
  - **状态快照**（collector_state）：删除检测的基线，按 (source, namespace, scope) 隔离
  - **运行记录**（collect_run）：每次采集留痕，支撑采集健康度

关键工程要求（09 §9.1）：
  - **增量 + 幂等**：内容指纹未变则不产生新事件
  - **绝不覆盖人工内容**：以 source=AUTO_COLLECTED 写入，字段级来源保护生效（ADR-005）
  - **可回滚**：同一次采集共用 run_id，可用 rollback_run 整体回退
  - **采集自身的可观测性**：新增/更新/跳过/失败/删除 与耗时全部留痕
"""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Iterable, Iterator

from sqlalchemy import text
from sqlalchemy.orm import Session

from dg.collectors.guard import GuardConfig, GuardDecision, evaluate_guard
from dg.core.service import MetadataService
from dg.core.urn import build_urn
from dg.model import ModelRegistry

COLLECT_SOURCE = "AUTO_COLLECTED"


# ---------------------------------------------------------------------------
# 统一中间模型（Source 产出的"原始元数据"）
# ---------------------------------------------------------------------------
@dataclass
class RawColumn:
    name: str
    data_type: str
    nullable: bool
    ordinal: int
    comment: str | None = None
    default: str | None = None


@dataclass
class RawDataset:
    platform: str
    database: str
    schema: str
    table: str
    kind: str = "TABLE"          # TABLE | VIEW | MATERIALIZED_VIEW
    comment: str | None = None
    columns: list[RawColumn] = field(default_factory=list)
    primary_key: list[str] = field(default_factory=list)


def sanitize_segment(name: str) -> str:
    """把任意名字规范化为合法的 URN 路径段。

    URN 的路径段不允许含 `.` / `:` / 空格（它们分别是层级分隔符与 URN 分隔符），
    但**真实世界的对象名经常含这些字符** —— 典型例子是数据湖里的文件名
    `orders_2026.parquet`。这里做规范化，同时把原始名保留为 display_name，
    保证"URN 稳定合法"与"展示可读"两不耽误。
    """
    out = (name or "").strip()
    for ch in (".", ":", " ", "/", "\\", "#", "?"):
        out = out.replace(ch, "_")
    return out or "_"


def schema_hash(columns: Iterable[RawColumn], kind: str) -> str:
    """结构指纹：用于契约兼容性、变更检测与"无变化不产生事件"。"""
    payload = {
        "kind": kind,
        "columns": [
            {"name": c.name, "type": c.data_type, "nullable": c.nullable, "ordinal": c.ordinal}
            for c in sorted(columns, key=lambda x: x.ordinal)
        ],
    }
    blob = json.dumps(payload, sort_keys=True, ensure_ascii=False)
    return "sha256:" + hashlib.sha256(blob.encode("utf-8")).hexdigest()[:32]


# ---------------------------------------------------------------------------
# Source 抽象
# ---------------------------------------------------------------------------
class Source(ABC):
    """连接器基类。"""

    name: str = "unknown"
    platform: str = "unknown"

    @abstractmethod
    def extract(self, database: str | None = None) -> Iterator[RawDataset]:
        """产出原始元数据。实现方只负责"读 + 翻译"，不负责写平台。"""


# ---------------------------------------------------------------------------
# 采集运行
# ---------------------------------------------------------------------------
@dataclass
class CollectionRun:
    run_id: str
    source: str
    platform_urn: str
    namespace: str = ""
    scope: str = ""
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    finished_at: datetime | None = None
    status: str = "RUNNING"                # RUNNING | SUCCEEDED | BLOCKED | FAILED
    block_reason: str | None = None
    datasets_seen: int = 0
    datasets_created: int = 0
    schemas_written: int = 0
    schemas_unchanged: int = 0
    descriptions_written: int = 0
    deleted_candidates: int = 0
    deleted: int = 0
    columns_seen: int = 0
    errors: list[str] = field(default_factory=list)
    guard: GuardDecision | None = None

    @property
    def duration_ms(self) -> int:
        if not self.finished_at:
            return 0
        return int((self.finished_at - self.started_at).total_seconds() * 1000)

    @property
    def ok(self) -> bool:
        return self.status == "SUCCEEDED" and not self.errors

    def as_dict(self) -> dict:
        out = {
            "runId": self.run_id,
            "source": self.source,
            "platformUrn": self.platform_urn,
            "namespace": self.namespace,
            "scope": self.scope,
            "status": self.status,
            "datasetsSeen": self.datasets_seen,
            "datasetsCreated": self.datasets_created,
            "schemasWritten": self.schemas_written,
            "schemasUnchanged": self.schemas_unchanged,
            "descriptionsWritten": self.descriptions_written,
            "deletedCandidates": self.deleted_candidates,
            "deleted": self.deleted,
            "columnsSeen": self.columns_seen,
            "errors": self.errors,
            "durationMs": self.duration_ms,
            "startedAt": self.started_at.isoformat(),
            "finishedAt": self.finished_at.isoformat() if self.finished_at else None,
        }
        if self.block_reason:
            out["blockReason"] = self.block_reason
        if self.guard:
            out["guard"] = self.guard.as_dict()
        return out


def new_run_id(source_name: str) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"{source_name}-{stamp}-{uuid.uuid4().hex[:6]}"


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def run_collection(
    session: Session,
    registry: ModelRegistry,
    source: Source,
    *,
    namespace: str = "prod",
    database: str | None = None,
    actor: str = "collector",
    guard_config: GuardConfig | None = None,
    guard_enabled: bool = True,
    accept_deletions: bool = False,
    scope: str | None = None,
) -> CollectionRun:
    """执行一次采集：Source → Normalizer → Sink → 护栏 → 快照。

    accept_deletions=True 用于"人工确认后接受删除"（护栏会放行，并更新基线）。
    """
    cfg = guard_config or GuardConfig()
    run_id = new_run_id(source.name)
    run_scope = scope if scope is not None else (database or "*")
    platform_urn = build_urn("Platform", namespace, source.platform)
    svc = MetadataService(session, registry, actor=actor, namespace=namespace)

    run = CollectionRun(
        run_id=run_id,
        source=source.name,
        platform_urn=platform_urn,
        namespace=namespace,
        scope=run_scope,
    )

    previous = _load_snapshot(session, source.name, namespace, run_scope)
    _begin_run_record(session, run, namespace)
    session.flush()

    current: set[str] = set()
    try:
        svc.ensure_entity(platform_urn, "Platform", display_name=source.name, run_id=run_id)

        for raw in source.extract(database):
            try:
                urn = _sink_dataset(svc, run, raw, namespace, platform_urn)
                current.add(urn)
            except Exception as exc:  # 单表失败不阻断整批（隔离原则）
                run.errors.append(f"{raw.schema}.{raw.table}: {exc}")
            run.datasets_seen += 1
            run.columns_seen += len(raw.columns)

        decision = evaluate_guard(previous, current, cfg)
        if not guard_enabled:
            decision.action = "PROCEED"
            decision.reasons = ["护栏已关闭（--no-guard）", *decision.reasons]
        elif accept_deletions and decision.blocked:
            # 人工确认接受删除：保留删除清单，但放行
            decision.action = "PROCEED"
            decision.reasons = [
                "人工确认接受删除（accept_deletions=true）",
                *decision.reasons,
            ]
        run.guard = decision
        run.deleted_candidates = len(decision.deletions)

        if decision.blocked:
            run.status = "BLOCKED"
            run.block_reason = "; ".join(decision.reasons)
            # 关键：被拦截时**不更新基线**，保证修好配置后仍能正确比较
        else:
            if decision.deletions:
                deleted = svc.mark_deleted_at_source(
                    decision.deletions, run_id=run_id, reason=f"collect:{run_id}"
                )
                run.deleted = len(deleted)
            run.status = "SUCCEEDED"
            _save_snapshot(session, source.name, namespace, run_scope, run, current, previous)

    except Exception as exc:  # 源整体不可用等致命错误
        run.errors.append(f"fatal: {exc}")
        run.status = "FAILED"

    run.finished_at = datetime.now(timezone.utc)
    _finish_run_record(session, run, namespace)
    session.flush()
    return run


# ---------------------------------------------------------------------------
# Sink
# ---------------------------------------------------------------------------
def _sink_dataset(
    svc: MetadataService,
    run: CollectionRun,
    raw: RawDataset,
    namespace: str,
    platform_urn: str,
) -> str:
    """把一个源表写入平台。返回其 URN（供护栏做集合比较）。"""
    dataset_urn = build_urn(
        "Dataset",
        namespace,
        sanitize_segment(raw.platform),
        sanitize_segment(raw.database),
        sanitize_segment(raw.schema),
        sanitize_segment(raw.table),
    )
    container_urn = build_urn(
        "Container",
        namespace,
        sanitize_segment(raw.platform),
        sanitize_segment(raw.database),
        sanitize_segment(raw.schema),
    )

    svc.ensure_entity(container_urn, "Container", run_id=run.run_id)
    _, created = svc.ensure_entity(
        dataset_urn, "Dataset", display_name=raw.table, run_id=run.run_id
    )
    if created:
        run.datasets_created += 1

    svc.upsert_edge(container_urn, dataset_urn, "contains", source="sql_parse",
                    confidence=1.0, run_id=run.run_id)
    svc.upsert_edge(platform_urn, container_urn, "contains", source="sql_parse",
                    confidence=1.0, run_id=run.run_id)

    digest = schema_hash(raw.columns, raw.kind)
    result = svc.upsert_aspect(
        dataset_urn,
        "datasetSchema",
        {
            "fields": [
                {
                    "name": c.name,
                    "type": c.data_type,
                    "nativeType": c.data_type,
                    "nullable": c.nullable,
                    "ordinal": c.ordinal,
                    "description": c.comment,
                }
                for c in raw.columns
            ],
            "primaryKey": raw.primary_key,
            "schemaHash": digest,
            "rawTypeSystem": raw.platform,
        },
        source=COLLECT_SOURCE,
        run_id=run.run_id,
    )
    if result.noop:
        run.schemas_unchanged += 1
    else:
        run.schemas_written += 1

    if raw.comment:
        desc = svc.upsert_aspect(
            dataset_urn,
            "descriptions",
            {"text": raw.comment, "language": "zh", "source": "AUTO_COLLECTED"},
            source=COLLECT_SOURCE,
            run_id=run.run_id,
        )
        if desc.changed_fields:
            run.descriptions_written += 1

    return dataset_urn


# ---------------------------------------------------------------------------
# 状态快照与运行记录（持久化）
# ---------------------------------------------------------------------------
def _load_snapshot(session: Session, source: str, namespace: str, scope: str) -> set[str]:
    row = session.execute(
        text(
            """
            SELECT last_snapshot FROM collector_state
             WHERE source = :s AND namespace = :n AND scope = :sc
            """
        ),
        {"s": source, "n": namespace, "sc": scope},
    ).scalar()
    if not row:
        return set()
    if isinstance(row, str):  # 驱动差异兜底
        row = json.loads(row)
    return set(row)


def _save_snapshot(
    session: Session,
    source: str,
    namespace: str,
    scope: str,
    run: CollectionRun,
    current: set[str],
    previous: set[str],
) -> None:
    snapshot = sorted(current)
    session.execute(
        text(
            """
            INSERT INTO collector_state
                (source, namespace, scope, last_run_id, last_snapshot, entity_count,
                 last_success_at, last_status, consecutive_failures, updated_at)
            VALUES (:s, :n, :sc, :rid, CAST(:snap AS jsonb), :cnt, now(), :status, 0, now())
            ON CONFLICT (source, namespace, scope) DO UPDATE
                SET last_run_id = EXCLUDED.last_run_id,
                    last_snapshot = EXCLUDED.last_snapshot,
                    entity_count = EXCLUDED.entity_count,
                    last_success_at = now(),
                    last_status = EXCLUDED.last_status,
                    consecutive_failures = 0,
                    updated_at = now()
            """
        ),
        {
            "s": source,
            "n": namespace,
            "sc": scope,
            "rid": run.run_id,
            "snap": json.dumps(snapshot),
            "cnt": len(snapshot),
            "status": run.status,
        },
    )


def _bump_failures(session: Session, source: str, namespace: str, scope: str, status: str) -> None:
    session.execute(
        text(
            """
            INSERT INTO collector_state
                (source, namespace, scope, last_run_id, last_snapshot, entity_count,
                 last_status, consecutive_failures, updated_at)
            VALUES (:s, :n, :sc, NULL, '[]'::jsonb, 0, :status, 1, now())
            ON CONFLICT (source, namespace, scope) DO UPDATE
                SET last_status = EXCLUDED.last_status,
                    consecutive_failures = collector_state.consecutive_failures + 1,
                    updated_at = now()
            """
        ),
        {"s": source, "n": namespace, "sc": scope, "status": status},
    )


def _begin_run_record(session: Session, run: CollectionRun, namespace: str) -> None:
    session.execute(
        text(
            """
            INSERT INTO collect_run (run_id, source, namespace, scope, status, started_at)
            VALUES (:rid, :s, :n, :sc, 'RUNNING', :ts)
            """
        ),
        {
            "rid": run.run_id,
            "s": run.source,
            "n": namespace,
            "sc": run.scope,
            "ts": run.started_at,
        },
    )


def _finish_run_record(session: Session, run: CollectionRun, namespace: str) -> None:
    session.execute(
        text(
            """
            UPDATE collect_run
               SET status = :status,
                   block_reason = :reason,
                   datasets_seen = :seen,
                   datasets_created = :created,
                   schemas_written = :sw,
                   schemas_unchanged = :su,
                   deleted_candidates = :dc,
                   deleted = :deleted,
                   columns_seen = :cols,
                   errors = CAST(:errors AS jsonb),
                   finished_at = :finished,
                   duration_ms = :dur
             WHERE run_id = :rid
            """
        ),
        {
            "rid": run.run_id,
            "status": run.status,
            "reason": run.block_reason,
            "seen": run.datasets_seen,
            "created": run.datasets_created,
            "sw": run.schemas_written,
            "su": run.schemas_unchanged,
            "dc": run.deleted_candidates,
            "deleted": run.deleted,
            "cols": run.columns_seen,
            "errors": json.dumps(run.errors),
            "finished": run.finished_at,
            "dur": run.duration_ms,
        },
    )
    if run.status in ("BLOCKED", "FAILED"):
        _bump_failures(session, run.source, namespace, run.scope, run.status)


__all__ = [
    "COLLECT_SOURCE",
    "CollectionRun",
    "GuardConfig",
    "RawColumn",
    "RawDataset",
    "Source",
    "new_run_id",
    "run_collection",
    "schema_hash",
]
