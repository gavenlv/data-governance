"""告警端到端演示：真实故障 → 告警投递 → 去重 → 恢复。

用法：
    $env:PYTHONPATH='src'
    python tools/alert_demo.py

它会起一个**本地 HTTP 服务**当 webhook 接收端，然后：
  1. 注册告警通道（指向本地服务）
  2. 制造连续 3 次采集失败（源文件不存在）
  3. 巡检 → 应发出 CRITICAL 告警，接收端收到真实 HTTP POST
  4. 再失败 + 再巡检 → **不重复打扰**，只累计 fire_count
  5. 换成可用的源 → 巡检 → 自动 RESOLVED 并发恢复通知
  6. 打印告警清单与投递日志

每一步都断言，跑完即是一份可复现的验收记录。
"""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, "src")

from sqlalchemy import text  # noqa: E402

from dg.alerting import check_and_notify, list_alerts, upsert_channel  # noqa: E402
from dg.collectors.base import run_collection  # noqa: E402
from dg.collectors.sqlite_source import SqliteSource  # noqa: E402
from dg.db import SessionLocal  # noqa: E402
from dg.model import ModelRegistry  # noqa: E402

NS = "alertdemo"
RECEIVED: list[dict] = []


class HookHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            RECEIVED.append(json.loads(raw.decode("utf-8")))
        except Exception:
            RECEIVED.append({"raw": raw.decode("utf-8", "replace")})
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


def banner(step: str, title: str) -> None:
    print(f"\n{'=' * 72}\n{step}  {title}\n{'=' * 72}")


def show_received(tag: str, since: int = 0) -> None:
    """打印自 since 之后新收到的通知（避免重复展示历史）。"""
    for item in RECEIVED[since:]:
        sev = item.get("severity", "?")
        state = item.get("state", "?")
        print(f"  ← [{tag}] {sev}/{state}  {item.get('title')}")
        for line in (item.get("text") or "").splitlines()[1:]:
            print(f"       {line}")


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="dg_alert_"))
    registry = ModelRegistry.load("model")
    session = SessionLocal()

    # 让演示可重复：清理本命名空间上次遗留的采集状态与告警
    session.execute(
        text(
            "DELETE FROM alert_dispatch_log WHERE alert_id IN "
            "(SELECT id FROM alert_event WHERE subject LIKE :s)"
        ),
        {"s": f"%{NS}%"},
    )
    session.execute(text("DELETE FROM alert_event WHERE subject LIKE :s"), {"s": f"%{NS}%"})
    session.execute(text("DELETE FROM collector_state WHERE namespace = :ns"), {"ns": NS})
    session.execute(text("DELETE FROM collect_run WHERE namespace = :ns"), {"ns": NS})
    session.execute(
        text("DELETE FROM alert_channel WHERE name = 'demo-webhook'")
    )
    session.commit()

    # ------------------------------------------------------------ 本地 webhook
    banner("[1/6]", "启动本地 webhook 接收端并注册通道")
    server = ThreadingHTTPServer(("127.0.0.1", 0), HookHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    hook_url = f"http://127.0.0.1:{server.server_address[1]}/alerts"
    upsert_channel(
        session,
        name="demo-webhook",
        kind="webhook",
        format="generic",
        config={"url": hook_url},
        min_severity="WARNING",
    )
    session.commit()
    print(f"  通道 demo-webhook → {hook_url}")

    # ------------------------------------------------------------ 制造故障
    banner("[2/6]", "制造连续 3 次采集失败（源文件不存在）")
    broken = SqliteSource(str(tmp / "does_not_exist.db"))
    for i in range(1, 4):
        run = run_collection(session, registry, broken, namespace=NS)
        session.commit()
        print(f"  第 {i} 次：status={run.status}  errors={run.errors[:1]}")

    state = session.execute(
        text(
            "SELECT consecutive_failures, last_status FROM collector_state WHERE namespace = :ns"
        ),
        {"ns": NS},
    ).mappings().one()
    print(f"  采集状态：连续失败={state['consecutive_failures']} 最后状态={state['last_status']}")
    assert state["consecutive_failures"] >= 3, "连续失败应累计到阈值以上"

    # ------------------------------------------------------------ 首次告警
    banner("[3/6]", "巡检 → 应发出 CRITICAL 告警")
    first = check_and_notify(session, remind_interval_seconds=3600, reopen_cooldown_seconds=0)
    session.commit()
    print(f"  新增={len(first['fired'])} 提醒={len(first['reminded'])} 恢复={len(first['resolved'])}")
    assert len(first["fired"]) == 1, "应产生一条告警"
    assert first["fired"][0]["severity"] == "CRITICAL"
    assert RECEIVED, "webhook 应收到真实 HTTP POST"
    show_received("首次告警")

    # ------------------------------------------------------------ 不重复打扰
    banner("[4/6]", "再次失败 + 巡检 → 不应重复打扰")
    run_collection(session, registry, broken, namespace=NS)
    session.commit()
    before = len(RECEIVED)
    second = check_and_notify(session, remind_interval_seconds=3600, reopen_cooldown_seconds=0)
    session.commit()
    fire_count = session.execute(
        text("SELECT fire_count FROM alert_event WHERE subject LIKE :s"),
        {"s": f"%{NS}%"},
    ).scalars().first()
    print(f"  新增={len(second['fired'])} 提醒={len(second['reminded'])} "
          f"更新={second['updated']}  累计触发次数={fire_count}")
    assert second["fired"] == [] and second["reminded"] == []
    assert len(RECEIVED) == before, "冷却期内不应重复投递"
    print("  ✓ 未重复投递（这正是'告警疲劳治理'的核心）")

    # -------------------------------------------------------------- 恢复
    banner("[5/6]", "修复源 → 巡检 → 自动恢复")
    good = tmp / "recovered.db"
    conn = sqlite3.connect(good)
    conn.execute("CREATE TABLE t1 (id INTEGER PRIMARY KEY)")
    conn.commit()
    conn.close()
    run_collection(session, registry, SqliteSource(str(good)), namespace=NS)
    session.commit()

    before_recovery = len(RECEIVED)
    third = check_and_notify(session, remind_interval_seconds=3600, reopen_cooldown_seconds=0)
    session.commit()
    print(f"  新增={len(third['fired'])} 恢复={len(third['resolved'])}")
    assert len(third["resolved"]) == 1
    show_received("恢复通知", since=before_recovery)

    fourth = check_and_notify(session, remind_interval_seconds=3600, reopen_cooldown_seconds=0)
    session.commit()
    assert fourth["fired"] == [] and fourth["resolved"] == []
    print("  ✓ 全部恢复后巡检静默（不再产生噪音）")

    # -------------------------------------------------------------- 汇总
    banner("[6/6]", "告警清单与投递日志")
    active = list_alerts(session, state="FIRING")
    resolved = list_alerts(session, state="RESOLVED")
    print(f"  活跃告警：{len(active)}    已恢复告警：{len(resolved)}")
    for row in resolved:
        print(f"    [{row['severity']}] {row['title']}  (触发 {row['fire_count']} 次)")

    dispatch = session.execute(
        text(
            """
            SELECT channel, ok, status_code, count(*) AS n
              FROM alert_dispatch_log GROUP BY channel, ok, status_code
            """
        )
    ).mappings().all()
    print("  投递日志：")
    for row in dispatch:
        print(f"    {row['channel']}: ok={row['ok']} status={row['status_code']} × {row['n']}")

    print(f"\n{'=' * 72}")
    print(f"接收端共收到 {len(RECEIVED)} 条真实 HTTP 通知（首次告警 + 恢复通知）")
    print("全部断言通过 ✓")
    print("=" * 72)

    server.shutdown()
    server.server_close()
    session.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
