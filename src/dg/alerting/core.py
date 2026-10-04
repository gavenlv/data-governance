"""采集告警：规则评估、状态机（去重/冷却/恢复）与投递（docs/09 §9.1）。

要解决的真实问题：**目录悄悄停止更新**。
健康度看板必须被主动查看，而告警会主动找到人 —— 两者缺一不可。

告警疲劳治理（09 §9.1 明确要求，也是可观测性产品"死于噪音"的根源）：
  - **分级**：INFO / WARNING / CRITICAL，通道可设最低级别
  - **去重**：同一问题（dedup_key）只有一个活跃告警，反复失败不刷屏，只累计 fire_count
  - **冷却/提醒**：仍在告警中的问题，每 remind_interval 才再提醒一次
  - **恢复**：条件消失自动 RESOLVED 并发恢复通知
  - **防抖动**：刚恢复的问题在 reopen_cooldown 内不立刻重新告警
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Sequence

from sqlalchemy import text
from sqlalchemy.orm import Session

from dg.alerting.channels import AlertMessage, Channel, DispatchResult, LogChannel, channel_from_row

DEFAULT_REMIND_SECONDS = 12 * 3600
DEFAULT_REOPEN_COOLDOWN_SECONDS = 300
STALE_HOURS_THRESHOLD = 48.0
CONSECUTIVE_FAILURE_THRESHOLD = 3


# ---------------------------------------------------------------------------
# 规则
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class AlertRule:
    name: str
    severity: str
    description: str
    enabled: bool = True


DEFAULT_RULES: tuple[AlertRule, ...] = (
    AlertRule(
        name="collect.consecutive_failures",
        severity="CRITICAL",
        description=f"采集连续失败 ≥ {CONSECUTIVE_FAILURE_THRESHOLD} 次",
    ),
    AlertRule(
        name="collect.blocked",
        severity="WARNING",
        description="最近一次采集被护栏拦截（实体数骤降/删除比例异常），需人工确认",
    ),
    AlertRule(
        name="collect.stale",
        severity="WARNING",
        description=f"采集超过 {int(STALE_HOURS_THRESHOLD)} 小时没有成功",
    ),
    AlertRule(
        name="collect.never_succeeded",
        severity="WARNING",
        description="已启用的调度从未成功执行过（配置很可能有问题）",
    ),
)


@dataclass
class AlertCandidate:
    """当前条件下"应该有"的告警。"""

    rule: str
    severity: str
    subject: str
    title: str
    detail: dict[str, Any] = field(default_factory=dict)
    scope: str | None = None

    @property
    def dedup_key(self) -> str:
        return f"{self.rule}:{self.subject}:{self.scope or '*'}"


@dataclass
class AlertAction:
    """对账产生的一个动作。"""

    action: str  # FIRED | REMINDED | UPDATED | RESOLVED
    dedup_key: str
    alert_id: int | None = None
    message: AlertMessage | None = None

    def as_dict(self) -> dict:
        out = {"action": self.action, "dedupKey": self.dedup_key, "alertId": self.alert_id}
        if self.message:
            out.update(
                {
                    "rule": self.message.rule,
                    "severity": self.message.severity,
                    "subject": self.message.subject,
                    "title": self.message.title,
                }
            )
        return out


# ---------------------------------------------------------------------------
# 评估
# ---------------------------------------------------------------------------
def _utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def evaluate_alerts(
    session: Session,
    *,
    rules: Sequence[AlertRule] = DEFAULT_RULES,
    now: datetime | None = None,
) -> list[AlertCandidate]:
    """从采集状态与调度配置评估出当前应有的告警集合（纯读，不写库）。"""
    now = now or datetime.now(timezone.utc)
    enabled = {r.name for r in rules if r.enabled}
    candidates: list[AlertCandidate] = []

    state_rows = session.execute(
        text(
            """
            SELECT source, namespace, scope, entity_count, last_status,
                   consecutive_failures, last_success_at, last_run_id, updated_at
              FROM collector_state
             ORDER BY source, namespace, scope
            """
        )
    ).mappings().all()

    for row in state_rows:
        subject = f"{row['source']}@{row['namespace']}"
        scope = row["scope"]
        failures = int(row["consecutive_failures"] or 0)

        if "collect.consecutive_failures" in enabled and failures >= CONSECUTIVE_FAILURE_THRESHOLD:
            candidates.append(
                AlertCandidate(
                    rule="collect.consecutive_failures",
                    severity="CRITICAL",
                    subject=subject,
                    scope=scope,
                    title=f"采集连续失败 {failures} 次",
                    detail={
                        "连续失败": failures,
                        "最后状态": row["last_status"],
                        "最后成功": _iso(row["last_success_at"]),
                        "建议": "检查源可用性、凭证与采集配置",
                    },
                )
            )

        if "collect.blocked" in enabled and row["last_status"] == "BLOCKED":
            candidates.append(
                AlertCandidate(
                    rule="collect.blocked",
                    severity="WARNING",
                    subject=subject,
                    scope=scope,
                    title="上次采集被护栏拦截，需人工确认",
                    detail={
                        "原因": "实体数骤降或删除比例超阈值；已拒绝软删，目录保持原样",
                        "处理": "确认源端变化是否真实 → 真实则用 --accept-deletions 重跑",
                        "已知实体数": row["entity_count"],
                    },
                )
            )

        if "collect.stale" in enabled and row["last_success_at"] is not None:
            last_success = _utc(row["last_success_at"])
            age_hours = (now - last_success).total_seconds() / 3600 if last_success else 0
            if age_hours > STALE_HOURS_THRESHOLD:
                candidates.append(
                    AlertCandidate(
                        rule="collect.stale",
                        severity="WARNING",
                        subject=subject,
                        scope=scope,
                        title=f"采集已 {age_hours:.1f} 小时未成功",
                        detail={
                            "最后成功": _iso(row["last_success_at"]),
                            "陈旧小时": round(age_hours, 1),
                            "建议": "确认调度是否在运行、源是否可达",
                        },
                    )
                )

    if "collect.never_succeeded" in enabled:
        schedule_rows = session.execute(
            text(
                """
                SELECT name, source, namespace, last_status, last_run_at, enabled
                  FROM collect_schedule
                 WHERE enabled = TRUE AND last_status IS NULL
                 ORDER BY name
                """
            )
        ).mappings().all()
        for row in schedule_rows:
            candidates.append(
                AlertCandidate(
                    rule="collect.never_succeeded",
                    severity="WARNING",
                    subject=f"schedule:{row['name']}",
                    scope=row["namespace"],
                    title=f"调度 {row['name']} 从未成功执行",
                    detail={
                        "source": row["source"],
                        "建议": "用 dgctl schedule run 手动跑一次看报错",
                    },
                )
            )

    return candidates


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


# ---------------------------------------------------------------------------
# 对账（状态机）
# ---------------------------------------------------------------------------
def reconcile_alerts(
    session: Session,
    *,
    rules: Sequence[AlertRule] = DEFAULT_RULES,
    now: datetime | None = None,
    remind_interval_seconds: float = DEFAULT_REMIND_SECONDS,
    reopen_cooldown_seconds: float = DEFAULT_REOPEN_COOLDOWN_SECONDS,
) -> dict:
    """把"当前应有的告警"与库里的告警状态对账。

    返回 {"fired": [...], "reminded": [...], "updated": [...], "resolved": [...]}。
    只写库，不投递（投递由 dispatch_alerts 负责，便于 dry-run 与测试）。
    """
    now = now or datetime.now(timezone.utc)
    candidates = evaluate_alerts(session, rules=rules, now=now)
    by_key = {c.dedup_key: c for c in candidates}

    active_rows = session.execute(
        text("SELECT * FROM alert_event WHERE state = 'FIRING'")
    ).mappings().all()
    active = {r["dedup_key"]: dict(r) for r in active_rows}

    fired: list[AlertAction] = []
    reminded: list[AlertAction] = []
    updated: list[AlertAction] = []

    for key, cand in by_key.items():
        if key not in active:
            reopened = _recently_resolved(session, key, now, reopen_cooldown_seconds)
            if reopened is not None:
                # 刚恢复又坏：视为抖动，不新建告警（仅记录），避免刷屏
                updated.append(AlertAction("UPDATED", key, alert_id=reopened))
                continue
            alert_id = _insert_alert(session, cand, now)
            fired.append(
                AlertAction(
                    "FIRED",
                    key,
                    alert_id=alert_id,
                    message=AlertMessage(
                        dedup_key=key,
                        rule=cand.rule,
                        severity=cand.severity,
                        subject=cand.subject,
                        title=cand.title,
                        scope=cand.scope,
                        detail=cand.detail,
                        fire_count=1,
                    ),
                )
            )
            continue

        current = active[key]
        previous_notified = _utc(current["notified_at"]) or _utc(current["first_fired_at"])
        due = previous_notified is None or (now - previous_notified).total_seconds() >= remind_interval_seconds

        session.execute(
            text(
                """
                UPDATE alert_event
                   SET last_fired_at = :now,
                       fire_count = fire_count + 1,
                       detail = CAST(:detail AS jsonb)
                 WHERE id = :id
                """
            ),
            {"id": current["id"], "now": now, "detail": json.dumps(cand.detail, ensure_ascii=False, default=str)},
        )

        if due:
            reminded.append(
                AlertAction(
                    "REMINDED",
                    key,
                    alert_id=current["id"],
                    message=AlertMessage(
                        dedup_key=key,
                        rule=cand.rule,
                        severity=cand.severity,
                        subject=cand.subject,
                        title=cand.title,
                        scope=cand.scope,
                        detail=cand.detail,
                        fire_count=int(current["fire_count"]) + 1,
                    ),
                )
            )
        else:
            updated.append(AlertAction("UPDATED", key, alert_id=current["id"]))

    resolved: list[AlertAction] = []
    for key, current in active.items():
        if key in by_key:
            continue
        session.execute(
            text(
                """
                UPDATE alert_event
                   SET state = 'RESOLVED', resolved_at = :now
                 WHERE id = :id
                """
            ),
            {"id": current["id"], "now": now},
        )
        detail = current["detail"] if isinstance(current["detail"], dict) else json.loads(current["detail"] or "{}")
        resolved.append(
            AlertAction(
                "RESOLVED",
                key,
                alert_id=current["id"],
                message=AlertMessage(
                    dedup_key=key,
                    rule=current["rule"],
                    severity=current["severity"],
                    subject=current["subject"],
                    title=current["title"],
                    scope=current["scope"],
                    detail=detail,
                    recovered=True,
                    fire_count=int(current["fire_count"]),
                ),
            )
        )

    session.flush()
    return {
        "candidates": len(candidates),
        "fired": fired,
        "reminded": reminded,
        "updated": updated,
        "resolved": resolved,
    }


def _recently_resolved(
    session: Session, dedup_key: str, now: datetime, cooldown_seconds: float
) -> int | None:
    if cooldown_seconds <= 0:
        return None
    row = session.execute(
        text(
            """
            SELECT id, resolved_at FROM alert_event
             WHERE dedup_key = :key AND state = 'RESOLVED'
             ORDER BY resolved_at DESC LIMIT 1
            """
        ),
        {"key": dedup_key},
    ).mappings().first()
    if not row or row["resolved_at"] is None:
        return None
    resolved_at = _utc(row["resolved_at"])
    if resolved_at and (now - resolved_at).total_seconds() < cooldown_seconds:
        return int(row["id"])
    return None


def _insert_alert(session: Session, cand: AlertCandidate, now: datetime) -> int:
    row = session.execute(
        text(
            """
            INSERT INTO alert_event
                (dedup_key, rule, severity, state, subject, scope, title, detail,
                 first_fired_at, last_fired_at, fire_count)
            VALUES (:key, :rule, :severity, 'FIRING', :subject, :scope, :title,
                    CAST(:detail AS jsonb), :now, :now, 1)
            RETURNING id
            """
        ),
        {
            "key": cand.dedup_key,
            "rule": cand.rule,
            "severity": cand.severity,
            "subject": cand.subject,
            "scope": cand.scope,
            "title": cand.title,
            "detail": json.dumps(cand.detail, ensure_ascii=False, default=str),
            "now": now,
        },
    ).mappings().one()
    return int(row["id"])


# ---------------------------------------------------------------------------
# 投递
# ---------------------------------------------------------------------------
def load_channels(session: Session, *, include_log: bool = True) -> list[Channel]:
    """载入启用的通道；始终附带一个 log 兜底通道（09 §9.1）。"""
    rows = session.execute(
        text(
            """
            SELECT name, kind, format, config, enabled, min_severity
              FROM alert_channel WHERE enabled = TRUE ORDER BY name
            """
        )
    ).mappings().all()

    channels: list[Channel] = []
    for row in rows:
        channel = channel_from_row(dict(row))
        if channel is not None:
            channels.append(channel)
    if include_log and not any(isinstance(c, LogChannel) for c in channels):
        channels.append(LogChannel())
    return channels


def dispatch_alerts(
    session: Session,
    actions: Iterable[AlertAction],
    *,
    channels: Sequence[Channel] | None = None,
    dry_run: bool = False,
) -> list[dict]:
    """投递告警（FIRED / REMINDED / RESOLVED），写 dispatch log 并回填 notified_at。"""
    to_send = [a for a in actions if a.message is not None]
    if not to_send:
        return []

    channels = list(channels) if channels is not None else load_channels(session)
    results: list[dict] = []

    for action in to_send:
        message = action.message
        assert message is not None
        outcome = {
            "alertId": action.alert_id,
            "action": action.action,
            "dedupKey": action.dedup_key,
            "severity": message.severity,
            "channels": [],
        }
        if dry_run:
            outcome["dryRun"] = True
            results.append(outcome)
            continue

        for channel in channels:
            result: DispatchResult = channel.send(message)
            outcome["channels"].append(
                {
                    "name": result.channel,
                    "ok": result.ok,
                    "statusCode": result.status_code,
                    "error": result.error,
                }
            )
            session.execute(
                text(
                    """
                    INSERT INTO alert_dispatch_log (alert_id, channel, ok, status_code, error)
                    VALUES (:aid, :ch, :ok, :code, :err)
                    """
                ),
                {
                    "aid": action.alert_id,
                    "ch": result.channel,
                    "ok": result.ok,
                    "code": result.status_code,
                    "err": result.error,
                },
            )
        if action.alert_id is not None:
            session.execute(
                text(
                    """
                    UPDATE alert_event
                       SET notified_at = now(), notify_count = notify_count + 1
                     WHERE id = :id
                    """
                ),
                {"id": action.alert_id},
            )
        results.append(outcome)

    session.flush()
    return results


def check_and_notify(
    session: Session,
    *,
    channels: Sequence[Channel] | None = None,
    rules: Sequence[AlertRule] = DEFAULT_RULES,
    now: datetime | None = None,
    remind_interval_seconds: float = DEFAULT_REMIND_SECONDS,
    reopen_cooldown_seconds: float = DEFAULT_REOPEN_COOLDOWN_SECONDS,
    dry_run: bool = False,
) -> dict:
    """调度器与 CLI 的统一入口：对账 + 投递。"""
    reconciliation = reconcile_alerts(
        session,
        rules=rules,
        now=now,
        remind_interval_seconds=remind_interval_seconds,
        reopen_cooldown_seconds=reopen_cooldown_seconds,
    )
    notifications = dispatch_alerts(
        session,
        [*reconciliation["fired"], *reconciliation["reminded"], *reconciliation["resolved"]],
        channels=channels,
        dry_run=dry_run,
    )
    return {
        "candidates": reconciliation["candidates"],
        "fired": [a.as_dict() for a in reconciliation["fired"]],
        "reminded": [a.as_dict() for a in reconciliation["reminded"]],
        "updated": len(reconciliation["updated"]),
        "resolved": [a.as_dict() for a in reconciliation["resolved"]],
        "notifications": notifications,
        "dryRun": dry_run,
    }


# ---------------------------------------------------------------------------
# 查询
# ---------------------------------------------------------------------------
def list_alerts(
    session: Session, *, state: str = "FIRING", limit: int = 50, severity: str | None = None
) -> list[dict]:
    params: dict[str, Any] = {"limit": limit, "state": state}
    where = ["state = :state"]
    if severity:
        where.append("severity = :severity")
        params["severity"] = severity
    rows = session.execute(
        text(
            f"""
            SELECT id, dedup_key, rule, severity, state, subject, scope, title, detail,
                   first_fired_at, last_fired_at, fire_count, notify_count,
                   resolved_at, acknowledged_at, acknowledged_by
              FROM alert_event
             WHERE {' AND '.join(where)}
             ORDER BY
               CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
               last_fired_at DESC
             LIMIT :limit
            """
        ),
        params,
    ).mappings().all()
    return [dict(r) for r in rows]


def acknowledge_alert(session: Session, alert_id: int, *, by: str) -> dict:
    row = session.execute(
        text(
            """
            UPDATE alert_event
               SET acknowledged_at = now(), acknowledged_by = :by
             WHERE id = :id
            RETURNING id, acknowledged_by, acknowledged_at
            """
        ),
        {"id": alert_id, "by": by},
    ).mappings().first()
    if row is None:
        raise KeyError(f"告警不存在：{alert_id}")
    return dict(row)


def upsert_channel(session: Session, **fields: Any) -> dict:
    row = session.execute(
        text(
            """
            INSERT INTO alert_channel (name, kind, format, config, enabled, min_severity, updated_at)
            VALUES (:name, :kind, :format, CAST(:config AS jsonb), :enabled, :min_severity, now())
            ON CONFLICT (name) DO UPDATE
                SET kind = EXCLUDED.kind,
                    format = EXCLUDED.format,
                    config = EXCLUDED.config,
                    enabled = EXCLUDED.enabled,
                    min_severity = EXCLUDED.min_severity,
                    updated_at = now()
            RETURNING name, kind, format, enabled, min_severity
            """
        ),
        {
            "name": fields["name"],
            "kind": fields.get("kind", "webhook"),
            "format": fields.get("format", "generic"),
            "config": json.dumps(fields.get("config") or {}, ensure_ascii=False),
            "enabled": bool(fields.get("enabled", True)),
            "min_severity": fields.get("min_severity", "WARNING"),
        },
    ).mappings().one()
    return dict(row)


__all__ = [
    "AlertAction",
    "AlertCandidate",
    "AlertRule",
    "CONSECUTIVE_FAILURE_THRESHOLD",
    "DEFAULT_REMIND_SECONDS",
    "DEFAULT_REOPEN_COOLDOWN_SECONDS",
    "DEFAULT_RULES",
    "STALE_HOURS_THRESHOLD",
    "acknowledge_alert",
    "check_and_notify",
    "dispatch_alerts",
    "evaluate_alerts",
    "list_alerts",
    "load_channels",
    "reconcile_alerts",
    "upsert_channel",
]
