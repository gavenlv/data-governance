"""采集告警测试（docs/09 §9.1）。

要验证的核心属性 —— **告警疲劳治理**（可观测性产品"死于噪音"的根源）：
  1. 分级：不同规则给不同严重级别，通道可按级别过滤
  2. 去重：同一问题反复失败**只累计计数，不重复打扰**
  3. 冷却/提醒：仍在告警中的问题按 remind_interval 才再提醒
  4. 恢复：条件消失自动 RESOLVED 并发恢复通知
  5. 防抖动：刚恢复的问题在冷却期内不立刻重新告警
  6. 投递真实可用（webhook 打到本地 HTTP 服务）且失败不影响状态机
"""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from sqlalchemy import text

from dg.alerting import (
    DEFAULT_RULES,
    AlertMessage,
    AlertRule,
    LogChannel,
    RecordingChannel,
    WebhookChannel,
    check_and_notify,
    evaluate_alerts,
    list_alerts,
    load_channels,
    reconcile_alerts,
    upsert_channel,
)
from dg.collectors.base import run_collection
from dg.collectors.sqlite_source import SqliteSource

NS = "alerttest"


# ---------------------------------------------------------------------------
# 夹具：制造真实的采集状态
# ---------------------------------------------------------------------------
@pytest.fixture
def failing_source(tmp_path):
    """源文件不存在 → 每次采集都 FAILED（用于制造连续失败）。"""
    return SqliteSource(str(tmp_path / "missing.db"))


@pytest.fixture
def healthy_source(tmp_path):
    path = tmp_path / "ok.db"
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE t1 (id INTEGER PRIMARY KEY, v TEXT)")
    conn.commit()
    conn.close()
    return SqliteSource(str(path))


def _fail_n_times(session, registry, source, n: int, namespace: str = NS):
    run = None
    for _ in range(n):
        run = run_collection(session, registry, source, namespace=namespace)
        session.commit()
    return run


def _insert_state(
    session,
    *,
    source="sqlite",
    namespace=NS,
    scope="*",
    status="SUCCEEDED",
    failures=0,
    last_success=None,
    entity_count=10,
):
    session.execute(
        text(
            """
            INSERT INTO collector_state
                (source, namespace, scope, last_snapshot, entity_count,
                 last_status, consecutive_failures, last_success_at, updated_at)
            VALUES (:s, :n, :sc, '[]'::jsonb, :cnt, :st, :f, :ls, now())
            ON CONFLICT (source, namespace, scope) DO UPDATE
                SET last_status = EXCLUDED.last_status,
                    consecutive_failures = EXCLUDED.consecutive_failures,
                    last_success_at = EXCLUDED.last_success_at,
                    entity_count = EXCLUDED.entity_count,
                    updated_at = now()
            """
        ),
        {"s": source, "n": namespace, "sc": scope, "cnt": entity_count,
         "st": status, "f": failures, "ls": last_success},
    )


# ---------------------------------------------------------------------------
# 1. 规则评估
# ---------------------------------------------------------------------------
class TestRuleEvaluation:
    def test_healthy_state_produces_no_alerts(self, session):
        _insert_state(session, last_success=datetime.now(timezone.utc))
        session.commit()
        assert evaluate_alerts(session) == []

    def test_consecutive_failures_is_critical(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        alerts = evaluate_alerts(session)
        assert len(alerts) == 1
        a = alerts[0]
        assert a.rule == "collect.consecutive_failures"
        assert a.severity == "CRITICAL"
        assert a.dedup_key == f"collect.consecutive_failures:sqlite@{NS}:*"

    def test_below_threshold_does_not_alert(self, session):
        _insert_state(session, status="FAILED", failures=2)
        session.commit()
        assert evaluate_alerts(session) == []

    def test_blocked_run_raises_warning(self, session):
        _insert_state(session, status="BLOCKED", failures=1,
                      last_success=datetime.now(timezone.utc))
        session.commit()
        alerts = evaluate_alerts(session)
        assert [a.rule for a in alerts] == ["collect.blocked"]
        assert alerts[0].severity == "WARNING"
        assert "护栏" in alerts[0].title

    def test_stale_collection_raises_warning(self, session):
        old = datetime.now(timezone.utc) - timedelta(hours=72)
        _insert_state(session, last_success=old)
        session.commit()
        alerts = evaluate_alerts(session)
        assert [a.rule for a in alerts] == ["collect.stale"]
        assert alerts[0].detail["陈旧小时"] > 48

    def test_fresh_collection_is_not_stale(self, session):
        _insert_state(session, last_success=datetime.now(timezone.utc) - timedelta(hours=1))
        session.commit()
        assert evaluate_alerts(session) == []

    def test_schedule_never_succeeded(self, session):
        session.execute(
            text(
                """
                INSERT INTO collect_schedule (name, source, dsn, namespace, cron, enabled)
                VALUES ('never', 'sqlite', '/tmp/x.db', :ns, '0 * * * *', TRUE)
                """
            ),
            {"ns": NS},
        )
        session.commit()
        alerts = evaluate_alerts(session)
        assert [a.rule for a in alerts] == ["collect.never_succeeded"]
        assert alerts[0].subject == "schedule:never"

    def test_disabled_rule_is_skipped(self, session):
        _insert_state(session, status="FAILED", failures=5,
                      last_success=datetime.now(timezone.utc))
        session.commit()
        only_stale = [r for r in DEFAULT_RULES if r.name != "collect.consecutive_failures"]
        assert evaluate_alerts(session, rules=only_stale) == []

    def test_real_failing_collection_triggers_critical(self, session, registry, failing_source):
        """真实跑 3 次失败采集 → 连续失败计数与告警都应成立。"""
        _fail_n_times(session, registry, failing_source, 3)
        alerts = evaluate_alerts(session)
        rules = {a.rule for a in alerts}
        assert "collect.consecutive_failures" in rules
        critical = next(a for a in alerts if a.rule == "collect.consecutive_failures")
        assert critical.detail["连续失败"] == 3


# ---------------------------------------------------------------------------
# 2~5. 状态机：去重 / 提醒 / 恢复 / 防抖
# ---------------------------------------------------------------------------
class TestAlertStateMachine:
    def test_fires_once_and_dedups(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()

        first = reconcile_alerts(session)
        session.commit()
        assert len(first["fired"]) == 1
        assert first["fired"][0].action == "FIRED"

        # 再次巡检：同一问题不应重复告警，只累计计数
        second = reconcile_alerts(session)
        session.commit()
        assert second["fired"] == []
        assert second["resolved"] == []
        assert len(second["updated"]) == 1

        row = session.execute(
            text("SELECT fire_count, state FROM alert_event")
        ).mappings().one()
        assert row["fire_count"] == 2
        assert row["state"] == "FIRING"

        # 库里只有一个活跃告警
        assert session.execute(
            text("SELECT count(*) FROM alert_event WHERE state='FIRING'")
        ).scalar() == 1

    def test_reminder_after_interval(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        now = datetime.now(timezone.utc)

        reconcile_alerts(session, now=now, remind_interval_seconds=3600, reopen_cooldown_seconds=0)
        session.commit()
        # 手动把上次通知时间往前推，模拟已过去很久
        session.execute(
            text("UPDATE alert_event SET notified_at = :t"), {"t": now - timedelta(hours=2)}
        )
        session.commit()

        later = reconcile_alerts(
            session, now=now + timedelta(hours=2), remind_interval_seconds=3600,
            reopen_cooldown_seconds=0,
        )
        session.commit()
        assert len(later["reminded"]) == 1
        assert later["reminded"][0].message.fire_count == 2

    def test_resolves_when_condition_clears(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        reconcile_alerts(session)
        session.commit()

        # 条件消失（采集恢复正常）
        _insert_state(session, status="SUCCEEDED", failures=0,
                      last_success=datetime.now(timezone.utc))
        session.commit()

        result = reconcile_alerts(session)
        session.commit()
        assert len(result["resolved"]) == 1
        msg = result["resolved"][0].message
        assert msg.recovered is True
        assert "已恢复" in msg.text()

        row = session.execute(
            text("SELECT state, resolved_at FROM alert_event")
        ).mappings().one()
        assert row["state"] == "RESOLVED" and row["resolved_at"] is not None

    def test_reopen_cooldown_suppresses_flapping(self, session):
        """刚恢复又坏 → 冷却期内不重新告警（防抖动刷屏）。"""
        now = datetime.now(timezone.utc)
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        reconcile_alerts(session, now=now)
        session.commit()

        _insert_state(session, status="SUCCEEDED", failures=0, last_success=now)
        session.commit()
        reconcile_alerts(session, now=now + timedelta(seconds=10))
        session.commit()

        # 又坏了（冷却期内）
        _insert_state(session, status="FAILED", failures=3, last_success=now)
        session.commit()
        again = reconcile_alerts(
            session, now=now + timedelta(seconds=20), reopen_cooldown_seconds=300
        )
        session.commit()
        assert again["fired"] == []
        assert len(again["updated"]) == 1

        # 冷却期过后应重新告警
        reopened = reconcile_alerts(
            session, now=now + timedelta(seconds=400), reopen_cooldown_seconds=300
        )
        session.commit()
        assert len(reopened["fired"]) == 1

    def test_can_acknowledge(self, session):
        from dg.alerting import acknowledge_alert

        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        result = reconcile_alerts(session)
        session.commit()
        alert_id = result["fired"][0].alert_id

        row = acknowledge_alert(session, alert_id, by="alice@corp")
        session.commit()
        assert row["acknowledged_by"] == "alice@corp"

    def test_multiple_independent_problems_coexist(self, session):
        _insert_state(session, namespace="a", status="FAILED", failures=3)
        _insert_state(session, namespace="b", status="BLOCKED", failures=1,
                      last_success=datetime.now(timezone.utc))
        session.commit()
        result = reconcile_alerts(session)
        session.commit()
        assert len(result["fired"]) == 2
        subjects = {a.message.subject for a in result["fired"]}
        assert subjects == {"sqlite@a", "sqlite@b"}


# ---------------------------------------------------------------------------
# 6. 通道与投递
# ---------------------------------------------------------------------------
class TestChannels:
    def test_log_channel_always_available(self, session):
        channels = load_channels(session)
        assert any(isinstance(c, LogChannel) for c in channels)

    def test_severity_filter_skips_low(self):
        channel = RecordingChannel(min_severity="CRITICAL")
        msg = AlertMessage(dedup_key="k", rule="r", severity="WARNING",
                           subject="s", title="t")
        result = channel.send(msg)
        assert result.ok and channel.sent == []

    def test_channel_upsert_and_load(self, session):
        upsert_channel(
            session, name="ops", kind="webhook", format="slack",
            config={"url": "http://127.0.0.1:9/hook"}, min_severity="WARNING",
        )
        session.commit()
        channels = load_channels(session)
        webhooks = [c for c in channels if isinstance(c, WebhookChannel)]
        assert len(webhooks) == 1
        assert webhooks[0].format == "slack"
        assert webhooks[0].min_severity == "WARNING"

    def test_invalid_webhook_config_is_skipped_not_crashing(self, session):
        upsert_channel(session, name="broken", kind="webhook", config={})
        session.commit()
        assert all(c.name != "broken" for c in load_channels(session))

    def test_webhook_payload_formats(self):
        msg = AlertMessage(dedup_key="k", rule="r", severity="CRITICAL",
                           subject="sqlite@prod", title="采集连续失败",
                           detail={"连续失败": 3})
        for fmt, check in [
            ("generic", lambda p: p["severity"] == "CRITICAL" and "text" in p),
            ("slack", lambda p: set(p) == {"text"}),
            ("feishu", lambda p: p["msg_type"] == "text"),
            ("dingtalk", lambda p: p["msgtype"] == "text"),
            ("teams", lambda p: "@type" in p),
        ]:
            payload = WebhookChannel(name="w", url="http://x", format=fmt).build_payload(msg)
            assert check(payload), f"{fmt} payload 不符合预期：{payload}"
            assert "采集连续失败" in json.dumps(payload, ensure_ascii=False)

    def test_webhook_delivers_to_real_http_endpoint(self, session):
        """真实投递：起一个本地 HTTP 服务接收告警，验证收到的内容。"""
        received: list[dict] = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                received.append(json.loads(body.decode("utf-8")))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/hook"
            channel = WebhookChannel(name="local", url=url, min_severity="INFO")
            msg = AlertMessage(dedup_key="collect.stale:sqlite@prod:*",
                               rule="collect.stale", severity="WARNING",
                               subject="sqlite@prod", title="采集已 72 小时未成功",
                               detail={"陈旧小时": 72.0})
            result = channel.send(msg)
            assert result.ok and result.status_code == 200
            assert len(received) == 1
            assert received[0]["rule"] == "collect.stale"
            assert received[0]["severity"] == "WARNING"
            assert "72" in received[0]["text"]
        finally:
            server.shutdown()
            server.server_close()

    def test_webhook_failure_is_recorded_and_does_not_break_state(self, session):
        """通道投递失败不应影响告警状态机（告警已在库里，换通道是运维动作）。"""
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        failing = WebhookChannel(
            name="dead", url="http://127.0.0.1:9/dead", min_severity="INFO", timeout=1
        )
        result = check_and_notify(session, channels=[failing])
        session.commit()

        assert len(result["fired"]) == 1
        assert result["notifications"][0]["channels"][0]["ok"] is False

        # 告警仍然存在
        assert session.execute(
            text("SELECT count(*) FROM alert_event WHERE state='FIRING'")
        ).scalar() == 1
        # 失败被记录，可排查"为什么没收到告警"
        log_row = session.execute(
            text("SELECT ok, error FROM alert_dispatch_log ORDER BY id DESC LIMIT 1")
        ).mappings().one()
        assert log_row["ok"] is False and log_row["error"]

    def test_dispatch_writes_log_and_marks_notified(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        recorder = RecordingChannel(name="rec", min_severity="INFO")
        result = check_and_notify(session, channels=[recorder])
        session.commit()

        assert len(recorder.sent) == 1
        assert recorder.sent[0].severity == "CRITICAL"
        row = session.execute(
            text("SELECT notified_at, notify_count FROM alert_event")
        ).mappings().one()
        assert row["notified_at"] is not None and row["notify_count"] == 1
        assert session.execute(text("SELECT count(*) FROM alert_dispatch_log")).scalar() == 1

    def test_dry_run_does_not_dispatch_or_mark(self, session):
        _insert_state(session, status="FAILED", failures=3)
        session.commit()
        recorder = RecordingChannel(name="rec", min_severity="INFO")
        result = check_and_notify(session, channels=[recorder], dry_run=True)
        session.commit()

        assert recorder.sent == []
        assert result["notifications"][0]["dryRun"] is True
        assert session.execute(
            text("SELECT notified_at FROM alert_event")
        ).scalar() is None


# ---------------------------------------------------------------------------
# 7. 端到端：真实采集故障 → 告警 → 修复 → 恢复
# ---------------------------------------------------------------------------
class TestEndToEnd:
    def test_full_lifecycle_from_real_collection_failure(
        self, session, registry, failing_source, healthy_source
    ):
        recorder = RecordingChannel(name="rec", min_severity="INFO")

        # 1) 连续失败 3 次
        _fail_n_times(session, registry, failing_source, 3)
        first = check_and_notify(session, channels=[recorder])
        session.commit()
        assert len(first["fired"]) == 1
        assert first["fired"][0]["severity"] == "CRITICAL"
        assert len(recorder.sent) == 1

        # 2) 再失败一次：不重复打扰，只累计
        run_collection(session, registry, failing_source, namespace=NS)
        session.commit()
        second = check_and_notify(session, channels=[recorder])
        session.commit()
        assert second["fired"] == [] and second["reminded"] == []
        assert len(recorder.sent) == 1        # 仍然只发过一次

        # 第 2 次巡检：同一问题只累计计数，不重复打扰
        fire_count = session.execute(text("SELECT fire_count FROM alert_event")).scalar()
        assert fire_count == 2, "两次巡检应累计为 2 次（首次 FIRED + 一次 UPDATED）"

        # 3) 换成可用的源 → 采集成功 → 告警自动恢复
        run_collection(session, registry, healthy_source, namespace=NS)
        session.commit()
        third = check_and_notify(session, channels=[recorder])
        session.commit()
        assert len(third["resolved"]) == 1
        assert len(recorder.sent) == 2        # 一条恢复通知
        assert recorder.sent[-1].recovered is True

        # 4) 全部恢复后再次巡检：静默
        fourth = check_and_notify(session, channels=[recorder])
        session.commit()
        assert fourth["fired"] == [] and fourth["resolved"] == []
        assert len(recorder.sent) == 2

    def test_list_alerts_orders_by_severity(self, session):
        _insert_state(session, namespace="crit", status="FAILED", failures=3)
        _insert_state(session, namespace="warn", status="BLOCKED", failures=1,
                      last_success=datetime.now(timezone.utc))
        session.commit()
        check_and_notify(session, channels=[RecordingChannel(name="rec")])
        session.commit()

        rows = list_alerts(session, state="FIRING")
        assert [r["severity"] for r in rows] == ["CRITICAL", "WARNING"]
        assert rows[0]["subject"] == "sqlite@crit"
