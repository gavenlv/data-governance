"""采集调度测试（docs/09 §9.1）。

覆盖：
  1. 定义校验与 DSN 解析（env: 引用、secret: 拒绝、输出遮蔽）
  2. YAML 加载与持久化（apply / list）
  3. 真实执行一次调度（写入 collect_run 并回填 last_run_*）
  4. **分布式互斥**：advisory lock 生效时第二个执行者必须跳过
  5. APScheduler 装载与状态（显式参数不依赖默认值）
  6. 库默认值陷阱的回归：misfire/coalesce/max_instances 必须被显式设置
"""

from __future__ import annotations

import sqlite3
import textwrap

import pytest
from sqlalchemy import text

from dg.scheduler import (
    CollectionScheduler,
    Schedule,
    ScheduleError,
    execute_schedule,
    list_schedules,
    load_schedules_from_yaml,
    lock_key_for,
    upsert_schedule,
)
from dg.scheduler.runner import MISFIRE_GRACE_SECONDS
from tests.conftest import session_scope_test


def _db(path, tables: int = 5):
    conn = sqlite3.connect(path)
    for i in range(tables):
        conn.execute(f"CREATE TABLE s{i} (id INTEGER PRIMARY KEY, v TEXT)")
    conn.commit()
    conn.close()
    return path


# ---------------------------------------------------------------------------
# 1. 定义与 DSN
# ---------------------------------------------------------------------------
class TestScheduleDefinition:
    def test_rejects_unknown_source(self):
        with pytest.raises(ScheduleError):
            Schedule(name="x", source="oracle", dsn="d").validate()

    def test_rejects_invalid_cron(self):
        with pytest.raises(ScheduleError):
            Schedule(name="x", source="sqlite", dsn="d", cron="not a cron").validate()

    def test_rejects_missing_name_and_dsn(self):
        with pytest.raises(ScheduleError):
            Schedule(name="", source="sqlite", dsn="d").validate()
        with pytest.raises(ScheduleError):
            Schedule(name="x", source="sqlite", dsn="").validate()

    def test_env_dsn_is_resolved(self, monkeypatch):
        monkeypatch.setenv("DG_TEST_SRC", "/tmp/x.db")
        sched = Schedule(name="x", source="sqlite", dsn="env:DG_TEST_SRC")
        assert sched.resolved_dsn() == "/tmp/x.db"

    def test_env_dsn_missing_variable_raises(self, monkeypatch):
        monkeypatch.delenv("DG_MISSING_VAR", raising=False)
        sched = Schedule(name="x", source="sqlite", dsn="env:DG_MISSING_VAR")
        with pytest.raises(ScheduleError):
            sched.resolved_dsn()

    def test_secret_ref_is_rejected_with_clear_message(self):
        """v1 未接 Vault：必须显式报错，不能静默当作普通 DSN。"""
        sched = Schedule(name="x", source="postgres", dsn="secret:vault/db")
        with pytest.raises(ScheduleError) as exc:
            sched.resolved_dsn()
        assert "Vault" in str(exc.value)

    def test_password_is_redacted_in_output(self):
        sched = Schedule(
            name="x", source="postgres", dsn="postgresql://alice:supersecret@host:5432/db"
        )
        assert "supersecret" not in str(sched.to_dict())
        assert "alice:***@host" in sched.to_dict()["dsn"]

    def test_guard_config_from_dict(self):
        sched = Schedule(
            name="x", source="sqlite", dsn="d",
            guard={"maxDeletions": 10, "maxDeleteRatio": 0.5, "minRetentionRatio": 0.9},
        )
        cfg = sched.guard_config()
        assert cfg.max_deletions == 10
        assert cfg.max_delete_ratio == 0.5
        assert cfg.min_retention_ratio == 0.9


# ---------------------------------------------------------------------------
# 2. YAML 与持久化
# ---------------------------------------------------------------------------
class TestSchedulePersistence:
    def test_load_from_yaml(self, tmp_path, monkeypatch):
        monkeypatch.setenv("DG_SRC_DSN", "postgresql://x")
        path = tmp_path / "s.yaml"
        path.write_text(
            textwrap.dedent(
                """
                schedules:
                  - name: a
                    source: postgres
                    dsn: env:DG_SRC_DSN
                    namespace: prod
                    schemas: [public]
                    cron: "0 3 * * *"
                  - name: b
                    source: sqlite
                    dsn: /tmp/b.db
                    cron: "*/15 * * * *"
                    enabled: false
                """
            ),
            encoding="utf-8",
        )
        schedules = load_schedules_from_yaml(str(path))
        assert [s.name for s in schedules] == ["a", "b"]
        assert schedules[0].schemas == ["public"]
        assert schedules[0].namespace == "prod"
        assert schedules[1].enabled is False
        assert schedules[1].namespace == "prod"  # 默认值

    def test_upsert_and_list(self, session, tmp_path):
        sched = Schedule(name="up-1", source="sqlite", dsn=str(tmp_path / "x.db"), cron="0 * * * *")
        upsert_schedule(session, sched)
        session.commit()

        rows = list_schedules(session)
        assert len(rows) == 1 and rows[0]["name"] == "up-1"

        # 幂等更新
        sched.cron = "30 * * * *"
        upsert_schedule(session, sched)
        session.commit()
        rows = list_schedules(session)
        assert len(rows) == 1 and rows[0]["cron"] == "30 * * * *"

    def test_invalid_yaml_content_rejected(self, session):
        from dg.scheduler import parse_schedules

        with pytest.raises(ScheduleError):
            parse_schedules({"schedules": [{"name": "bad", "source": "nope", "dsn": "d"}]})


# ---------------------------------------------------------------------------
# 3. 执行
# ---------------------------------------------------------------------------
class TestScheduleExecution:
    def test_execute_runs_collection_and_records(self, session, registry, tmp_path):
        db = _db(tmp_path / "src.db", tables=5)
        sched = Schedule(name="exec-1", source="sqlite", dsn=str(db), namespace="prod")
        upsert_schedule(session, sched)
        session.commit()

        result = execute_schedule(session, registry, sched, actor="test")
        session.commit()

        assert result["skipped"] is False
        run = result["run"]
        assert run["status"] == "SUCCEEDED"
        assert run["datasetsSeen"] == 5

        row = session.execute(
            text("SELECT last_run_id, last_status, last_run_at FROM collect_schedule WHERE name='exec-1'")
        ).mappings().one()
        assert row["last_run_id"] == run["runId"]
        assert row["last_status"] == "SUCCEEDED"
        assert row["last_run_at"] is not None

    def test_guard_blocks_via_schedule(self, session, registry, tmp_path):
        db = _db(tmp_path / "src.db", tables=30)
        sched = Schedule(name="exec-2", source="sqlite", dsn=str(db), namespace="prod")
        upsert_schedule(session, sched)
        execute_schedule(session, registry, sched, actor="test")
        session.commit()

        conn = sqlite3.connect(db)
        for i in range(5, 30):
            conn.execute(f"DROP TABLE s{i}")
        conn.commit()
        conn.close()

        result = execute_schedule(session, registry, sched, actor="test")
        session.commit()
        assert result["run"]["status"] == "BLOCKED"

        alive = session.execute(
            text("SELECT count(*) FROM entity WHERE entity_type='Dataset' AND deleted_at IS NULL")
        ).scalar()
        assert alive == 30

    def test_missing_schedule_raises(self, session, registry):
        with pytest.raises(KeyError):
            CollectionScheduler(registry, session_factory=session_scope_test).run_now("does-not-exist")


# ---------------------------------------------------------------------------
# 4. 分布式互斥（advisory lock）
# ---------------------------------------------------------------------------
class TestAdvisoryLockMutex:
    def test_second_executor_skips_when_locked(self, session, registry, tmp_path):
        """多副本场景：同一调度同时被触发时，只有一个真正执行。"""
        from tests.conftest import _TestSession

        db = _db(tmp_path / "src.db", tables=3)
        sched = Schedule(name="lock-1", source="sqlite", dsn=str(db), namespace="prod")
        upsert_schedule(session, sched)
        session.commit()

        key = lock_key_for("lock-1")
        # 模拟"另一个副本"已持有锁
        session.execute(text("SELECT pg_try_advisory_lock(:k)"), {"k": key})

        other = _TestSession()
        try:
            result = execute_schedule(other, registry, sched, actor="other")
            assert result["skipped"] is True
            assert "advisory lock" in result["reason"]
        finally:
            other.close()
            session.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": key})

        # 释放后可正常执行
        result = execute_schedule(session, registry, sched, actor="test")
        assert result["skipped"] is False

    def test_lock_key_is_stable_and_distinct(self):
        assert lock_key_for("a") == lock_key_for("a")
        assert lock_key_for("a") != lock_key_for("b")


# ---------------------------------------------------------------------------
# 5~6. APScheduler 装载与显式参数
# ---------------------------------------------------------------------------
class TestSchedulerRunner:
    def test_reload_loads_enabled_schedules_only(self, session, registry, tmp_path):
        upsert_schedule(session, Schedule(name="on-1", source="sqlite", dsn=str(tmp_path / "a.db")))
        upsert_schedule(
            session,
            Schedule(name="off-1", source="sqlite", dsn=str(tmp_path / "b.db"), enabled=False),
        )
        session.commit()

        scheduler = CollectionScheduler(registry, session_factory=session_scope_test)
        count = scheduler.reload()
        assert count == 1
        jobs = [j["id"] for j in scheduler.status().jobs]
        assert jobs == ["on-1"]
        # 未 start 时不应标记为 running（避免误以为在跑）
        assert scheduler.status().running is False

    def test_start_is_idempotent_and_shutdown_works(self, session, registry, tmp_path):
        upsert_schedule(session, Schedule(name="start-1", source="sqlite", dsn=str(tmp_path / "a.db")))
        session.commit()
        scheduler = CollectionScheduler(registry, session_factory=session_scope_test)
        assert scheduler.start() == 1
        assert scheduler.running is True
        assert scheduler.start() == 1  # 重复 start 不应抛错
        scheduler.shutdown()
        assert scheduler.running is False

    def test_job_params_are_explicit_not_defaults(self, session, registry, tmp_path):
        """回归测试：09 §9.1 指出库默认值在生产语义下未必正确，必须显式设置。"""
        upsert_schedule(session, Schedule(name="p-1", source="sqlite", dsn=str(tmp_path / "a.db")))
        session.commit()
        scheduler = CollectionScheduler(registry, session_factory=session_scope_test)
        scheduler.start()
        try:
            job = scheduler._scheduler.get_job("p-1")
            assert job.max_instances == 1
            assert job.coalesce is True
            assert job.misfire_grace_time == MISFIRE_GRACE_SECONDS
            assert MISFIRE_GRACE_SECONDS > 1  # APScheduler 默认仅 1 秒，对采集太短
        finally:
            scheduler.shutdown()

    def test_invalid_cron_job_is_skipped_not_crashing(self, session, registry, tmp_path):
        """非法 cron 不能让调度器整体起不来。"""
        upsert_schedule(session, Schedule(name="bad-cron", source="sqlite", dsn=str(tmp_path / "a.db")))
        session.execute(
            text("UPDATE collect_schedule SET cron = 'invalid' WHERE name = 'bad-cron'")
        )
        upsert_schedule(session, Schedule(name="good", source="sqlite", dsn=str(tmp_path / "b.db")))
        session.commit()

        scheduler = CollectionScheduler(registry, session_factory=session_scope_test)
        count = scheduler.reload()
        assert count == 2                       # 两条都从库里读到了
        jobs = [j["id"] for j in scheduler.status().jobs]
        assert jobs == ["good"]                 # 但只有合法 cron 被装载
