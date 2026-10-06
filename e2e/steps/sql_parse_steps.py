"""SQL 解析与血缘质量（组 9）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:474-521：
「表名解析不到就不写边」「dryRun 只解析不写库」「解析失败必须落样本库」。
需要资产存在时，每个场景在自己的「假如」里通过公共步骤真实采集（幂等 upsert）。
"""

from __future__ import annotations

from pytest_bdd import given, then

from support import config

# 组 9 的原始 SQL（insert-select，形成 event_log → alert_event 的表级与列级血缘）
_FIRST_SQL = ("INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n"
              "  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'")
# 故意写坏的 SQL：用于验证「解析失败落样本库」
_BROKEN_SQL = "INSERT INTO alert_event SELEC broken FROM"


def _parse(world, api, sql: str, **extra) -> dict:
    body = {"sql": sql, "dialect": "postgres", "namespace": world.namespace, **extra}
    status, payload = api.call("POST", "/api/v1/lineage/parse", body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/lineage/parse", status))
    assert status == 200, f"SQL 解析失败：HTTP {status} {payload}"
    return payload if isinstance(payload, dict) else {}


@given("我已解析出 event_log 到 alert_event 的表级血缘")
def _parse_first(world, api) -> None:
    """先建立一跳链（供 9c2 形成两跳链的首跳）。"""
    world.remember("parse_first", _parse(world, api, _FIRST_SQL))


@given("我已登记一条解析失败样本")
def _record_failed_sample(world, api) -> None:
    """故意解析坏 SQL，触发解析样本登记（把「解析不出来」变成可运营指标）。"""
    world.remember("parse_broken", _parse(world, api, _BROKEN_SQL))


@then("解析结果中未解析表名列表应为空")
def _no_unresolved(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    unresolved = body.get("unresolvedTables")
    assert unresolved == [], (
        f"存在未解析到 URN 的表名（会漏写边，宁可缺边不猜）：{unresolved}"
    )