"""内置调度器（APScheduler 3.x）。

刻意显式设置三个参数，不依赖库默认值（docs/09 §9.1「库默认值在生产场景失效」）：
  max_instances=1       同一调度不并发执行（默认就是 1，但显式写出以免被默认值变更影响）
  coalesce=True         停机恢复后错过的触发合并为一次，避免"补偿风暴"打爆源端
  misfire_grace_time    错过触发后仍允许执行的宽限时间（默认仅 1s，采集场景太短）

分布式互斥在 execute_schedule 内用 PG advisory lock 完成，因此**多副本同时
启动调度器也不会重复采集**。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from sqlalchemy import text

from dg.config import settings
from dg.db import session_scope
from dg.model import ModelRegistry
from dg.scheduler.core import Schedule, execute_schedule, schedule_from_row

log = logging.getLogger("dg.scheduler")

MISFIRE_GRACE_SECONDS = 300


@dataclass
class SchedulerStatus:
    running: bool
    jobs: list[dict[str, Any]]


class CollectionScheduler:
    """把数据库里的调度定义装载进 APScheduler。

    session_factory 可注入（默认走全局 session_scope）——
    这样测试可以指向独立测试库，而不必改动生产代码路径。
    """

    def __init__(
        self,
        registry: ModelRegistry,
        *,
        timezone: str = "UTC",
        session_factory=None,
    ) -> None:
        self.registry = registry
        self.timezone = timezone
        self._session_factory = session_factory or session_scope
        self._scheduler = BackgroundScheduler(timezone=timezone)
        self._started = False

    # ------------------------------------------------------------------ 生命周期

    def start(self) -> int:
        """启动调度器并装载全部启用的调度。返回装载数量。"""
        count = self.reload()
        self._schedule_health_check()
        if not self._started:
            self._scheduler.start()
            self._started = True
        return count

    def _schedule_health_check(self) -> None:
        """周期巡检：评估采集健康度并触发告警（docs/09 §9.1）。

        没有这一步，健康度就只是一个"需要有人主动去看"的看板 ——
        而目录停止更新恰恰是没人会主动去看的那种故障。
        """
        interval = settings.alert_check_interval
        if interval <= 0:
            log.info("告警巡检已关闭（DG_ALERT_CHECK_INTERVAL_SECONDS=0）")
            return
        self._scheduler.add_job(
            func=self._run_health_check,
            trigger="interval",
            seconds=interval,
            id="dg-health-check",
            name="采集健康巡检与告警",
            max_instances=1,
            coalesce=True,
            misfire_grace_time=MISFIRE_GRACE_SECONDS,
            replace_existing=True,
        )
        log.info("已装载健康巡检（每 %s 秒）", interval)

    def _run_health_check(self) -> None:
        from dg.alerting import check_and_notify

        try:
            with self._session_factory() as session:
                result = check_and_notify(
                    session,
                    remind_interval_seconds=settings.alert_remind_interval,
                    reopen_cooldown_seconds=settings.alert_reopen_cooldown,
                )
            if result["fired"] or result["resolved"] or result["reminded"]:
                log.warning(
                    "告警巡检：新增 %s / 提醒 %s / 恢复 %s",
                    len(result["fired"]), len(result["reminded"]), len(result["resolved"]),
                )
        except Exception:  # pragma: no cover - 巡检线程必须吞异常
            log.exception("告警巡检失败")

    def shutdown(self, wait: bool = False) -> None:
        if self._started:
            self._scheduler.shutdown(wait=wait)
            self._started = False

    @property
    def running(self) -> bool:
        return self._started

    # ------------------------------------------------------------------ 装载

    def reload(self) -> int:
        """从数据库重新装载调度（幂等，可随时调用以应用变更）。"""
        with self._session_factory() as session:
            rows = session.execute(
                text(
                    """
                    SELECT name, source, dsn, namespace, database_name, schemas, tables,
                           cron, enabled, guard_config, timezone
                      FROM collect_schedule
                     WHERE enabled = TRUE
                     ORDER BY name
                    """
                )
            ).mappings().all()

        desired = {r["name"] for r in rows}
        for job in self._scheduler.get_jobs():
            if job.id not in desired:
                self._scheduler.remove_job(job.id)

        for row in rows:
            sched = schedule_from_row(dict(row))
            self._add_job(sched)
        return len(rows)

    def _add_job(self, sched: Schedule) -> None:
        try:
            trigger = CronTrigger.from_crontab(sched.cron, timezone=sched.timezone)
        except Exception as exc:
            log.error("调度 %s 的 cron 无法解析（%s）：%s", sched.name, sched.cron, exc)
            return
        self._scheduler.add_job(
            func=self._run_job,
            trigger=trigger,
            args=[sched.name],
            id=sched.name,
            name=sched.name,
            max_instances=1,
            coalesce=True,
            misfire_grace_time=MISFIRE_GRACE_SECONDS,
            replace_existing=True,
        )
        log.info("已装载调度 %s（cron=%s, tz=%s）", sched.name, sched.cron, sched.timezone)

    # ------------------------------------------------------------------ 执行

    def _run_job(self, name: str) -> None:
        """APScheduler 线程池中的入口：自建 session，异常不冒泡到调度器。"""
        try:
            result = self.run_now(name, actor="scheduler")
            if result.get("skipped"):
                log.info("调度 %s 被跳过：%s", name, result.get("reason"))
            else:
                run = result.get("run") or {}
                log.info(
                    "调度 %s 完成：status=%s seen=%s deleted=%s",
                    name, run.get("status"), run.get("datasetsSeen"), run.get("deleted"),
                )
        except Exception:  # pragma: no cover - 调度线程内必须吞掉异常
            log.exception("调度 %s 执行失败", name)

    def run_now(self, name: str, *, actor: str = "cli", accept_deletions: bool = False) -> dict:
        """立即执行一次（供 CLI/API 触发，也用于排障）。"""
        with self._session_factory() as session:
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
                raise KeyError(f"调度不存在：{name}")
            sched = schedule_from_row(dict(row))
            return execute_schedule(
                session, self.registry, sched, actor=actor, accept_deletions=accept_deletions
            )

    # ------------------------------------------------------------------ 状态

    def status(self) -> SchedulerStatus:
        jobs = []
        for job in self._scheduler.get_jobs():
            # 未 start 时 job 处于 pending 状态，没有 next_run_time（getattr 兜底）
            next_run = getattr(job, "next_run_time", None)
            jobs.append(
                {
                    "id": job.id,
                    "nextRunAt": next_run.isoformat() if next_run else None,
                    "trigger": str(job.trigger),
                }
            )
        return SchedulerStatus(running=self._started, jobs=jobs)


__all__ = ["CollectionScheduler", "SchedulerStatus"]
