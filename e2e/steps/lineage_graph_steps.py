"""血缘画布与边的人工确认（组 24）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:934-999：
子图节点与边带属性；只沿血缘类型遍历；节点上限显式回报；
确认提升置信度并记名；驳回必须说明原因(422)且该边不再参与遍历；批量退役必须给 edgeType。
独立运行：每个场景在自己的「假如」里采集 + 解析出 event_log→alert_event→collector_state 链。
"""

from __future__ import annotations

from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

_FIRST_SQL = ("INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n"
              "  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'")
_SECOND_SQL = "INSERT INTO collector_state SELECT a.seq FROM alert_event a"


def _parse(world, api, sql: str) -> None:
    body = {"sql": sql, "dialect": "postgres", "namespace": world.namespace}
    status, payload = api.call("POST", "/api/v1/lineage/parse", body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/lineage/parse", status))
    assert status == 200, f"解析 SQL 造血缘失败：HTTP {status} {payload}"


@given("我已解析出 event_log→alert_event→collector_state 两跳血缘链")
def _parse_chain(world, api) -> None:
    _parse(world, api, _FIRST_SQL)
    _parse(world, api, _SECOND_SQL)


@given(parsers.parse("我已锁定 {ns} 命名空间下的 public.{table} 数据集用于血缘画布"))
def _lock_dataset(world, api, ns: str, table: str) -> None:
    prefix = f"urn:dg:Dataset:{ns}"
    path = f"/api/v1/assets?prefix={prefix}&limit=100"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status} {listing}"
    urn = next((row["urn"] for row in listing.get("assets", [])
                if row["urn"].startswith(f"urn:dg:Dataset:{ns}.")
                and row["urn"].endswith(f"public.{table}")), None)
    assert urn is not None, f"未找到 {ns} 命名空间下的 public.{table} 数据集 URN，无法绘制血缘子图"
    world.remember("graph_urn", urn)


def _subgraph(world, api, direction: str, depth: int, node_limit: int | None = None,
              focus_key: str = "graph_urn") -> dict:
    urn = world.recall(focus_key)
    assert urn, "尚未锁定血缘画布焦点数据集"
    limit = f"&nodeLimit={node_limit}" if node_limit is not None else ""
    path = (f"/api/v1/lineage/subgraph?urn={quote_urn(urn)}"
            f"&direction={direction}&depth={depth}{limit}")
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload
    return payload if isinstance(payload, dict) else {}


@when(parsers.parse("我以管理员令牌对锁定数据集向下游查询深度 {depth:d} 的血缘子图"))
def _downstream(world, api, depth: int) -> None:
    _subgraph(world, api, "downstream", depth)


@when(parsers.parse("我以管理员令牌对锁定数据集向上游查询深度 {depth:d} 的血缘子图"))
def _upstream(world, api, depth: int) -> None:
    _subgraph(world, api, "upstream", depth)


@when(parsers.parse(
    "我以管理员令牌对锁定数据集向下游查询深度 {depth:d} 且节点上限为 {limit:d} 的血缘子图"))
def _downstream_limited(world, api, depth: int, limit: int) -> None:
    _subgraph(world, api, "downstream", depth, limit)


@then("子图节点与边均非空")
def _graph_nonempty(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    counts = body.get("counts") or {}
    assert body.get("nodes"), f"血缘子图节点为空：{counts}"
    assert body.get("edges"), f"血缘子图边为空（线型无从绘制）：{counts}"


@then("子图中的每条边都带来源、置信度与边标识")
def _edges_attributed(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    edges = body.get("edges") or []
    assert edges, "血缘子图没有边，无法校验属性"
    for edge in edges:
        for key in ("source", "confidence", "edgeId"):
            assert key in edge, f"血缘边缺少属性 {key}（无法按来源/置信度绘制）：{edge}"


@then("子图上游节点类型中不应出现 Platform 或 Container")
def _no_structural_nodes(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    by_type = (body.get("counts") or {}).get("nodesByType") or {}
    leaked = {"Platform", "Container"} & set(by_type.keys())
    assert not leaked, f"上游出现结构包含节点（contains 不是血缘）：{by_type}"


@then("子图提示中应包含「上限」的裁剪说明")
def _limit_note(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    notes = body.get("notes") or []
    assert any("上限" in str(note) for note in notes), (
        f"节点上限裁剪未被显式回报（不静默截断）：{notes}"
    )


@given("我已锁定 event_log 到 alert_event 的 sql_parse 血缘边")
def _lock_edge(world, api) -> None:
    payload = _subgraph(world, api, "downstream", 3)
    edges = payload.get("edges") or []
    edge = next((item for item in edges
                 if item.get("source") == "sql_parse"
                 and str(item.get("from", "")).endswith("public.event_log")
                 and str(item.get("to", "")).endswith("public.alert_event")), None)
    assert edge is not None, (
        f"未找到 event_log→alert_event 的 sql_parse 血缘边（供确认/驳回演示）：{len(edges)} 条边"
    )
    world.remember("edge_id", edge["edgeId"])


def _edge_path(world, action: str) -> str:
    edge_id = world.recall("edge_id")
    assert edge_id is not None, "尚未锁定血缘边"
    return f"/api/v1/lineage/edges/{edge_id}/{action}"


@when("我以管理员令牌确认锁定血缘边")
def _confirm_edge(world, api) -> None:
    path = _edge_path(world, "confirm")
    status, payload = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@when("我以管理员令牌不带原因地驳回锁定血缘边")
def _reject_blank(world, api) -> None:
    path = _edge_path(world, "reject")
    status, payload = api.call("POST", path, {}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@when("我以管理员令牌带原因地驳回锁定血缘边")
def _reject_with_reason(world, api) -> None:
    path = _edge_path(world, "reject")
    status, payload = api.call("POST", path, {"reason": "e2e：字段映射不正确"},
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


def _current_edge(world, api) -> dict | None:
    edge_id = world.recall("edge_id")
    payload = _subgraph(world, api, "downstream", 3)
    edges = payload.get("edges") or []
    return next((item for item in edges if item.get("edgeId") == edge_id), None)


@then("锁定血缘边在子图中的置信度应达到人工级且记录确认人")
def _edge_confirmed(world, api) -> None:
    edge = _current_edge(world, api)
    assert edge is not None, f"确认后子图中找不到该边：{world.recall('edge_id')}"
    assert edge.get("confidence", 0) >= 0.99, (
        f"确认后置信度未提升到人工级（≥0.99）：{edge.get('confidence')}"
    )
    assert edge.get("confirmedBy"), f"确认后未记录确认人：{edge}"


@then("锁定血缘边不应再出现在下游子图中")
def _edge_gone(world, api) -> None:
    edge = _current_edge(world, api)
    assert edge is None, (
        f"驳回后该边仍参与血缘遍历（应为标记而非继续使用）：{world.recall('edge_id')}"
    )


@then("我随后将锁定血缘边恢复为已确认状态")
def _restore_edge(world, api) -> None:
    path = _edge_path(world, "confirm")
    status, _ = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))


@then("响应状态码应为 400 或 422")
def _status_rejected(world) -> None:
    assert world.last_status in (400, 422), (
        f"期望显式拒绝（400/422），实际 HTTP {world.last_status}：{world.last_body}"
    )