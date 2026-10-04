"""采集调度的定义、加载与执行（docs/09 §9.1）。

关键设计（都是 09 §9.1「实现陷阱」的落地）：
  1. **互斥用 PG advisory lock，不用 Redlock**：Redlock 缺 fencing token，
     在 GC 停顿下无法保证互斥；`pg_try_advisory_lock` 随连接释放、崩溃不残留。
  2. **显式设置 APScheduler 参数**（max_instances/coalesce/misfire_grace_time），
     不依赖库默认 —— 默认值在生产语义下未必正确。
  3. **凭证不落明文**：DSN 支持 `env:VAR_NAME` 引用环境变量。
  4. 每次执行都写 `collect_run`，因此调度是否真的在跑**可被观测**
     （避免"目录悄悄停止更新"这一隐性失效）。
"""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field
from typing import Any

import yaml
from sqlalchemy import text
from sqlalchemy.orm import Session

from dg.collectors.base import run_collection
from dg.collectors.guard import GuardConfig
from dg.model import ModelRegistry

DEFAULT_CRON = "0 3 * * *"


class ScheduleError(Exception):
    """调度定义非法。"""


@dataclass
class Schedule:
    name: str
    source: str
    dsn: str
    namespace: str = "prod"
    database: str | None = None
    schemas: list[str] | None = None
    tables: list[str] | None = None
    cron: str = DEFAULT_CRON
    enabled: bool = True
    guard: dict[str, Any] = field(default_factory=dict)
    timezone: str = "Asia/Shanghai"

    # ---------------------------------------------------------------- 校验

    def validate(self) -> None:
        if not self.name:
            raise ScheduleError("调度缺少 name")
        if self.source not in ("postgres", "sqlite", "duckdb"):
            raise ScheduleError(f"调度 {self.name}: 不支持的 source={self.source!r}")
        if not self.dsn:
            raise ScheduleError(f"调度 {self.name}: 缺少 dsn")
        try:
            from croniter import croniter

            if not croniter.is_valid(self.cron):
                raise ScheduleError(f"调度 {self.name}: cron 表达式非法 {self.cron!r}")
        except ImportError:  # pragma: no cover
            pass

    # ------------------------------------------------------------ DSN 解析

    def resolved_dsn(self) -> str:
        """解析 DSN：支持 `env:VAR` 与 `secret:path`（后者仅提示，未接 Vault）。"""
        if self.dsn.startswith("env:"):
            var = self.dsn[4:].strip()
            value = os.environ.get(var)
            if not value:
                raise ScheduleError(
                    f"调度 {self.name}: 环境变量 {var} 未设置（DSN 以 env: 引用）"
                )
            return value
        if self.dsn.startswith("secret:"):
            raise ScheduleError(
                f"调度 {self.name}: secret: 引用需要接入 Vault/KMS（v1 未实现）；"
                f"请改用 env:VAR 形式"
            )
        return self.dsn

    def guard_config(self) -> GuardConfig:
        cfg = self.guard or {}
        return GuardConfig(
            max_deletions=int(cfg.get("maxDeletions", 200)),
            max_delete_ratio=float(cfg.get("maxDeleteRatio", 0.30)),
            min_retention_ratio=float(cfg.get("minRetentionRatio", 0.70)),
        )

    @staticmethod
    def from_dict(raw: dict) -> "Schedule":
        sched = Schedule(
            name=raw.get("name", ""),
            source=raw.get("source", ""),
            dsn=raw.get("dsn", ""),
            namespace=raw.get("namespace", "prod"),
            database=raw.get("database"),
            schemas=raw.get("schemas"),
            tables=raw.get("tables"),
            cron=raw.get("cron", DEFAULT_CRON),
            enabled=bool(raw.get("enabled", True)),
            guard=raw.get("guard") or {},
            timezone=raw.get("timezone", "Asia/Shanghai"),
        )
        sched.validate()
        return sched

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "source": self.source,
            "dsn": _redact(self.dsn),
            "namespace": self.namespace,
            "database": self.database,
            "schemas": self.schemas,
            "tables": self.tables,
            "cron": self.cron,
            "enabled": self.enabled,
            "guard": self.guard,
            "timezone": self.timezone,
        }


def _redact(dsn: str) -> str:
    """输出时遮蔽 DSN 中的口令。"""
    if "://" not in dsn or "@" not in dsn:
        return dsn
    scheme, rest = dsn.split("://", 1)
    creds, host = rest.split("@", 1)
    if ":" in creds:
        user = creds.split(":", 1)[0]
        return f"{scheme}://{user}:***@{host}"
    return dsn


def load_schedules_from_yaml(path: str) -> list[Schedule]:
    raw = yaml.safe_load(open(path, encoding="utf-8")) or {}
    entries = raw.get("schedules") or []
    if not isinstance(entries, list):
        raise ScheduleError("schedules 必须是数组")
    return [Schedule.from_dict(e) for e in entries]


def parse_schedules(document: dict) -> list[Schedule]:
    entries = document.get("schedules") or []
    return [Schedule.from_dict(e) for e in entries]


# ---------------------------------------------------------------------------
# 持久化
# ---------------------------------------------------------------------------
def upsert_schedule(session: Session, sched: Schedule) -> dict:
    sched.validate()
    row = session.execute(
        text(
            """
            INSERT INTO collect_schedule
                (name, source, dsn, namespace, database_name, schemas, tables,
                 cron, enabled, guard_config, timezone, updated_at)
            VALUES (:name, :source, :dsn, :ns, :db, :schemas, :tables,
                    :cron, :enabled, CAST(:guard AS jsonb), :tz, now())
            ON CONFLICT (name) DO UPDATE
                SET source = EXCLUDED.source,
                    dsn = EXCLUDED.dsn,
                    namespace = EXCLUDED.namespace,
                    database_name = EXCLUDED.database_name,
                    schemas = EXCLUDED.schemas,
                    tables = EXCLUDED.tables,
                    cron = EXCLUDED.cron,
                    enabled = EXCLUDED.enabled,
                    guard_config = EXCLUDED.guard_config,
                    timezone = EXCLUDED.timezone,
                    updated_at = now()
            RETURNING name, enabled, cron
            """
        ),
        {
            "name": sched.name,
            "source": sched.source,
            "dsn": sched.dsn,
            "ns": sched.namespace,
            "db": sched.database,
            "schemas": sched.schemas,
            "tables": sched.tables,
            "cron": sched.cron,
            "enabled": sched.enabled,
            "guard": json.dumps(sched.guard or {}),
            "tz": sched.timezone,
        },
    ).mappings().one()
    return dict(row)


def list_schedules(session: Session, *, enabled_only: bool = False) -> list[dict]:
    where = "WHERE enabled = TRUE" if enabled_only else ""
    rows = session.execute(
        text(
            f"""
            SELECT name, source, dsn, namespace, database_name, schemas, tables,
                   cron, enabled, guard_config, timezone,
                   last_run_id, last_run_at, last_status, next_run_at
              FROM collect_schedule {where}
             ORDER BY name
            """
        )
    ).mappings().all()
    out = []
    for r in rows:
        d = dict(r)
        d["dsn"] = _redact(d["dsn"])
        out.append(d)
    return out


def record_schedule_run(
    session: Session, name: str, run_id: str, status: str, next_run_at=None
) -> None:
    session.execute(
        text(
            """
            UPDATE collect_schedule
               SET last_run_id = :rid,
                   last_run_at = now(),
                   last_status = :status,
                   next_run_at = COALESCE(:next, next_run_at),
                   updated_at = now()
             WHERE name = :name
            """
        ),
        {"name": name, "rid": run_id, "status": status, "next": next_run_at},
    )


# ---------------------------------------------------------------------------
# 执行（含分布式互斥）
# ---------------------------------------------------------------------------
def lock_key_for(name: str) -> int:
    digest = hashlib.sha256(f"dg-collect:{name}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big", signed=True)


def execute_schedule(
    session: Session,
    registry: ModelRegistry,
    sched: Schedule,
    *,
    actor: str = "scheduler",
    accept_deletions: bool = False,
) -> dict:
    """执行一次调度。

    用 `pg_try_advisory_lock` 做互斥：多个 API 副本同时调度时只有一个真正跑，
    且锁随连接释放（进程崩溃不会留下死锁）。
    """
    key = lock_key_for(sched.name)
    acquired = session.execute(
        text("SELECT pg_try_advisory_lock(:k)"), {"k": key}
    ).scalar()
    if not acquired:
        return {
            "schedule": sched.name,
            "skipped": True,
            "reason": "另一个实例正在执行同一调度（advisory lock 未获取到）",
        }

    try:
        source = _build_source(sched)
        run = run_collection(
            session,
            registry,
            source,
            namespace=sched.namespace,
            database=sched.database,
            actor=actor,
            guard_config=sched.guard_config(),
            accept_deletions=accept_deletions,
        )
        record_schedule_run(session, sched.name, run.run_id, run.status)
        session.flush()
        return {"schedule": sched.name, "skipped": False, "run": run.as_dict()}
    finally:
        session.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": key})


def _build_source(sched: Schedule):
    if sched.source == "postgres":
        from dg.collectors.postgres_source import PostgresSource

        return PostgresSource(
            sched.resolved_dsn(),
            include_schemas=sched.schemas,
            include_tables=sched.tables,
        )
    if sched.source == "sqlite":
        from dg.collectors.sqlite_source import SqliteSource

        return SqliteSource(sched.resolved_dsn())
    if sched.source == "duckdb":
        from dg.collectors.duckdb_source import DuckDbSource

        return DuckDbSource(
            sched.resolved_dsn(),
            include_schemas=sched.schemas,
            include_tables=sched.tables,
        )
    raise ScheduleError(f"不支持的 source：{sched.source}")


def schedule_from_row(row: dict) -> Schedule:
    return Schedule(
        name=row["name"],
        source=row["source"],
        dsn=row["dsn"],
        namespace=row["namespace"],
        database=row.get("database_name"),
        schemas=list(row["schemas"]) if row.get("schemas") else None,
        tables=list(row["tables"]) if row.get("tables") else None,
        cron=row["cron"],
        enabled=bool(row["enabled"]),
        guard=dict(row.get("guard_config") or {}),
        timezone=row.get("timezone") or "Asia/Shanghai",
    )


__all__ = [
    "DEFAULT_CRON",
    "Schedule",
    "ScheduleError",
    "execute_schedule",
    "list_schedules",
    "load_schedules_from_yaml",
    "lock_key_for",
    "parse_schedules",
    "record_schedule_run",
    "schedule_from_row",
    "upsert_schedule",
]
