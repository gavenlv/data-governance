"""采集护栏与采集健康度测试（docs/09 §9.1）。

这是"防止一次错误采集清空目录"的最后一道防线，必须有完整覆盖：
  1. 纯函数：四条护栏规则的触发与不触发边界
  2. 集成：真实采集 → 删表 → 再采集，必须 **BLOCKED 且实体仍在**
  3. 人工确认后可接受删除（accept_deletions）
  4. 复活：删掉后重新出现 → 实体自动复活（人工成果不丢）
  5. 基线在 BLOCKED 时不更新（否则下次无法再检出）
  6. 命名空间隔离：一个源的删除只影响自己 scope 内的实体
  7. 健康视图汇总
"""

from __future__ import annotations

import sqlite3

import pytest
from sqlalchemy import text

from dg.collectors.base import run_collection
from dg.collectors.guard import GuardConfig, evaluate_guard, summarize_history
from dg.collectors.sqlite_source import SqliteSource


def _make_db(path, tables: int = 3, extra_sql: str = ""):
    conn = sqlite3.connect(path)
    for i in range(tables):
        conn.execute(f"CREATE TABLE t{i} (id INTEGER PRIMARY KEY, v TEXT)")
    if extra_sql:
        conn.executescript(extra_sql)
    conn.commit()
    conn.close()
    return path


# ---------------------------------------------------------------------------
# 1. 纯函数：护栏规则
# ---------------------------------------------------------------------------
class TestGuardRules:
    def test_first_run_never_deletes(self):
        d = evaluate_guard(set(), {"urn:a", "urn:b"})
        assert d.action == "PROCEED"
        assert d.deletions == []
        assert "首次采集" in d.reasons[0]

    def test_no_deletions_proceeds(self):
        d = evaluate_guard({"a", "b"}, {"a", "b", "c"})
        assert d.action == "PROCEED" and d.deletions == []

    def test_small_deletion_within_threshold_proceeds(self):
        prev = {f"t{i}" for i in range(100)}
        curr = set(list(prev)[:95])          # 删除 5 个 = 5%
        d = evaluate_guard(prev, curr)
        assert d.action == "PROCEED"
        assert len(d.deletions) == 5

    def test_exceeding_max_deletions_blocks(self):
        prev = {f"t{i}" for i in range(1000)}
        curr = set(list(prev)[:700])         # 删除 300 > 上限 200
        d = evaluate_guard(prev, curr, GuardConfig(max_deletions=200))
        assert d.action == "BLOCK"
        assert any("超过上限" in r for r in d.reasons)

    def test_exceeding_delete_ratio_blocks(self):
        prev = {f"t{i}" for i in range(100)}
        curr = set(list(prev)[:60])          # 删除 40% > 30%
        d = evaluate_guard(prev, curr, GuardConfig(max_delete_ratio=0.30))
        assert d.action == "BLOCK"
        assert any("删除比例" in r for r in d.reasons)

    def test_entity_count_drop_blocks(self):
        prev = {f"t{i}" for i in range(100)}
        curr = set(list(prev)[:50])          # 保留率 50% < 70%
        d = evaluate_guard(prev, curr)
        assert d.action == "BLOCK"
        assert any("骤降" in r for r in d.reasons)

    def test_small_baseline_skips_ratio_rules(self):
        """小目录的比例噪音大（3 个里删 1 个就是 33%），不做比例判定。"""
        d = evaluate_guard({"a", "b", "c"}, {"a"}, GuardConfig(ratio_min_baseline=10))
        assert d.action == "PROCEED"

    def test_metrics_exposed(self):
        prev = {f"t{i}" for i in range(100)}
        curr = set(list(prev)[:90])
        d = evaluate_guard(prev, curr)
        assert d.previous_count == 100 and d.current_count == 90
        assert d.delete_ratio == pytest.approx(0.10)
        assert d.retention_ratio == pytest.approx(0.90)
        assert d.as_dict()["action"] == "PROCEED"


# ---------------------------------------------------------------------------
# 2~6. 集成
# ---------------------------------------------------------------------------
class TestGuardIntegration:
    def test_mass_deletion_is_blocked_and_entities_survive(self, session, registry, tmp_path):
        """核心安全属性：源端大面积消失时，目录必须保持不变。"""
        db = _make_db(tmp_path / "src.db", tables=20)
        first = run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()
        assert first.status == "SUCCEEDED" and first.datasets_seen == 20

        # 模拟"源端只剩 3 张表"（配置改错 / 账号权限变化的典型症状）
        conn = sqlite3.connect(db)
        for i in range(3, 20):
            conn.execute(f"DROP TABLE t{i}")
        conn.commit()
        conn.close()

        second = run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        assert second.status == "BLOCKED"
        assert second.deleted == 0
        assert second.deleted_candidates == 17
        assert second.block_reason and "骤降" in second.block_reason

        alive = session.execute(
            text("SELECT count(*) FROM entity WHERE entity_type='Dataset' AND deleted_at IS NULL")
        ).scalar()
        assert alive == 20, "被拦截时不得删除任何实体"

    def test_baseline_not_updated_when_blocked(self, session, registry, tmp_path):
        """被拦截时不更新基线，修好配置后仍能正确比较。"""
        db = _make_db(tmp_path / "src.db", tables=20)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        conn = sqlite3.connect(db)
        for i in range(3, 20):
            conn.execute(f"DROP TABLE t{i}")
        conn.commit()
        conn.close()

        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        state = session.execute(
            text("SELECT entity_count, last_status, consecutive_failures FROM collector_state")
        ).mappings().one()
        assert state["entity_count"] == 20, "BLOCKED 时基线必须保持上一轮的值"
        assert state["last_status"] == "BLOCKED"
        assert state["consecutive_failures"] == 1

        # 再次采集仍应 BLOCKED（说明基线没被污染）
        third = run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()
        assert third.status == "BLOCKED"
        assert session.execute(
            text("SELECT consecutive_failures FROM collector_state")
        ).scalar() == 2

    def test_accept_deletions_applies_after_human_confirmation(self, session, registry, tmp_path):
        db = _make_db(tmp_path / "src.db", tables=20)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        conn = sqlite3.connect(db)
        for i in range(3, 20):
            conn.execute(f"DROP TABLE t{i}")
        conn.commit()
        conn.close()

        run = run_collection(
            session, registry, SqliteSource(str(db)), namespace="prod", accept_deletions=True
        )
        session.commit()

        assert run.status == "SUCCEEDED"
        assert run.deleted == 17
        assert any("人工确认" in r for r in run.guard.reasons)

        alive = session.execute(
            text("SELECT count(*) FROM entity WHERE entity_type='Dataset' AND deleted_at IS NULL")
        ).scalar()
        assert alive == 3

        # 被删除的实体是软删：aspect 仍在（人工成果不丢）
        orphans = session.execute(
            text(
                """
                SELECT count(*) FROM aspect a
                  JOIN entity e ON e.urn = a.urn
                 WHERE e.deleted_at IS NOT NULL AND a.aspect_type = 'datasetSchema'
                """
            )
        ).scalar()
        assert orphans == 17

    def test_resurrection_after_table_returns(self, session, registry, tmp_path):
        """源端表回来时实体自动复活（此前是隐蔽崩溃点）。"""
        db = _make_db(tmp_path / "src.db", tables=4)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        # 先取定 t3 的 URN（而不是"第一个"），后续用它验证墓碑与复活
        target = session.execute(
            text("SELECT urn FROM entity WHERE entity_type='Dataset' AND urn LIKE '%t3'")
        ).scalar()
        assert target is not None

        conn = sqlite3.connect(db)
        conn.execute("DROP TABLE t3")
        conn.commit()
        conn.close()
        blocked = run_collection(
            session, registry, SqliteSource(str(db)), namespace="prod", accept_deletions=True
        )
        session.commit()
        assert blocked.deleted == 1

        tomb = session.execute(
            text("SELECT deleted_at, lifecycle FROM entity WHERE urn = :u"), {"u": target}
        ).mappings().one()
        assert tomb["deleted_at"] is not None

        # 表回来 → 再采集 → 复活
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE t3 (id INTEGER PRIMARY KEY, v TEXT)")
        conn.commit()
        conn.close()

        run = run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()
        assert run.status == "SUCCEEDED"

        revived = session.execute(
            text("SELECT deleted_at, lifecycle FROM entity WHERE urn = :u"), {"u": target}
        ).mappings().one()
        assert revived["deleted_at"] is None
        assert revived["lifecycle"] == "ACTIVE"

        # 复活事件已发出（消费者据此重建索引文档）
        assert session.execute(
            text("SELECT count(*) FROM event_log WHERE event_type='ENTITY_RESURRECTED'")
        ).scalar() >= 1

    def test_namespace_isolation(self, session, registry, tmp_path):
        """不同 namespace 的采集互不影响（命名空间隔离防误删）。"""
        db = _make_db(tmp_path / "src.db", tables=6)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        run_collection(session, registry, SqliteSource(str(db)), namespace="stg")
        session.commit()

        conn = sqlite3.connect(db)
        for i in range(2, 6):
            conn.execute(f"DROP TABLE t{i}")
        conn.commit()
        conn.close()

        run = run_collection(
            session, registry, SqliteSource(str(db)), namespace="prod", accept_deletions=True
        )
        session.commit()
        assert run.deleted == 4

        stg_alive = session.execute(
            text(
                "SELECT count(*) FROM entity WHERE namespace='stg' AND deleted_at IS NULL"
                " AND entity_type='Dataset'"
            )
        ).scalar()
        assert stg_alive == 6, "stg 命名空间的资产不应被 prod 的采集删除"

    def test_run_record_persisted(self, session, registry, tmp_path):
        db = _make_db(tmp_path / "src.db", tables=3)
        run = run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        row = session.execute(
            text("SELECT * FROM collect_run WHERE run_id = :r"), {"r": run.run_id}
        ).mappings().one()
        assert row["status"] == "SUCCEEDED"
        assert row["datasets_seen"] == 3
        assert row["duration_ms"] >= 0
        assert row["finished_at"] is not None

    def test_snapshot_updated_on_success(self, session, registry, tmp_path):
        db = _make_db(tmp_path / "src.db", tables=3)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()
        state = session.execute(
            text("SELECT entity_count, last_status, last_run_id FROM collector_state")
        ).mappings().one()
        assert state["entity_count"] == 3
        assert state["last_status"] == "SUCCEEDED"
        assert state["last_run_id"]


# ---------------------------------------------------------------------------
# 7. 健康视图
# ---------------------------------------------------------------------------
class TestHealthSummary:
    def test_source_health_transitions(self, session, registry, tmp_path):
        db = _make_db(tmp_path / "src.db", tables=20)
        run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
        session.commit()

        conn = sqlite3.connect(db)
        for i in range(3, 20):
            conn.execute(f"DROP TABLE t{i}")
        conn.commit()
        conn.close()

        for _ in range(3):
            run_collection(session, registry, SqliteSource(str(db)), namespace="prod")
            session.commit()

        rows = session.execute(
            text(
                """
                SELECT source, namespace, status, started_at
                  FROM collect_run ORDER BY started_at DESC
                """
            )
        ).mappings().all()
        summary = summarize_history([dict(r) for r in rows])
        assert summary["total"] >= 4
        assert summary["health"] == "UNHEALTHY"
        src = next(s for s in summary["sources"] if s["source"] == "sqlite")
        assert src["consecutiveFailures"] >= 3
        assert src["blocked"] >= 3
        assert src["stalenessHours"] is not None

    def test_empty_history(self):
        assert summarize_history([])["health"] == "UNKNOWN"
