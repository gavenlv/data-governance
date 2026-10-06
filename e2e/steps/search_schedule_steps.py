"""检索与调度（组 7、8）的领域步骤。"""

from __future__ import annotations

from pytest_bdd import given, parsers, then

from support import config

_E2E_DSN = "postgresql://postgres:root@localhost:25011/dg"

_SELF_SCHEDULE = {
    "name": "java_e2e_self",
    "source": "postgres",
    "dsn": _E2E_DSN,
    "namespace": "java_e2e_sched",
    "schemas": ["public"],
    "cron": "*/15 * * * *",
    "enabled": True,
}


@given("我已重建检索索引")
def _rebuild_index(world, api) -> None:
    status, payload = api.call("POST", "/api/v1/index/rebuild?batchSize=500",
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/index/rebuild?batchSize=500", status))
    assert status == 200, f"索引重建失败：HTTP {status} {payload}"
    world.remember("rebuild", payload)


@given(parsers.parse("我已登记 {name} 调度"))
def _register_schedule(world, api, name: str) -> None:
    schedule = {**_SELF_SCHEDULE, "name": name}
    status, payload = api.call("POST", "/api/v1/schedules", {"schedules": [schedule]},
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/schedules", status))
    assert status == 200, f"登记调度失败：HTTP {status} {payload}"


@then("恰好 1 条调度被接受、1 条被拒绝且错误指出 sqlite")
def _one_rejected(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    applied = body.get("applied") or []
    rejected = body.get("rejected") or []
    assert len(applied) == 1, f"期望接受 1 条，实际 {len(applied)} 条：{body}"
    assert len(rejected) == 1, f"期望拒绝 1 条，实际 {len(rejected)} 条：{body}"
    error = str(rejected[0].get("error", ""))
    assert "sqlite" in error, f"拒绝原因未指出 sqlite：{error}"