"""影响分析（组 10）与未实现接口（组 11）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:523-560：
按评分排序 + 可解释理由 + 评分口径；深度截断被显式标注；未实现接口 501 且带设计说明。
event_log 数据集 URN 找不到时**必须失败**（不静默 skip），与原始脚本一致。
"""

from __future__ import annotations

from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

_SEED_SQL = ("INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n"
             "  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'")


@given(parsers.parse("我已锁定 {ns} 命名空间下的 public.{table} 数据集用于影响分析"))
def _lock_dataset(world, api, ns: str, table: str) -> None:
    prefix = f"urn:dg:Dataset:{ns}"
    path = f"/api/v1/assets?prefix={prefix}&limit=100"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status} {listing}"
    urn = next((row["urn"] for row in listing.get("assets", [])
                if row["urn"].startswith(f"urn:dg:Dataset:{ns}.")
                and row["urn"].endswith(f"public.{table}")), None)
    # 找不到目标必须失败：一条「被跳过」的检查与一条「通过」的检查在结果里长得一样
    assert urn is not None, f"未找到 {ns} 命名空间下的 public.{table} 数据集 URN，无法执行影响分析"
    world.remember("impact_urn", urn)


@given("我已解析 event_log 到 alert_event 的 SQL 血缘（为影响分析奠基）")
def _seed_lineage(world, api) -> None:
    body = {"sql": _SEED_SQL, "dialect": "postgres", "namespace": world.namespace}
    status, payload = api.call("POST", "/api/v1/lineage/parse", body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/lineage/parse", status))
    assert status == 200, f"解析 SQL 造血缘失败：HTTP {status} {payload}"


@when(parsers.parse("我以管理员令牌对锁定数据集执行深度 {depth:d} 的含列级影响分析"))
def _impact(world, api, depth: int) -> None:
    urn = world.recall("impact_urn")
    assert urn, "尚未锁定影响分析目标数据集"
    path = (f"/api/v1/lineage/impact/{quote_urn(urn)}"
            f"?direction=downstream&depth={depth}&includeColumns=true")
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload


@then("影响分析结果按评分从高到低排序且首节点给出理由")
def _impact_ranked(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    nodes = body.get("nodes") or []
    assert nodes, f"影响分析节点为空（受影响 {body.get('affectedCount')}）：{body.get('caveat')}"
    scores = [node.get("score") for node in nodes]
    assert all(isinstance(score, (int, float)) for score in scores), (
        f"受影响节点缺少评分，无法排序：{scores}"
    )
    assert all(scores[i] >= scores[i + 1] for i in range(len(scores) - 1)), (
        f"受影响节点未按评分降序排列：{scores}"
    )
    assert nodes[0].get("reasons"), f"首节点未给出可解释理由：{nodes[0]}"