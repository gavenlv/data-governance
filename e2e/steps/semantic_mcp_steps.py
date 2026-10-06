"""语义层 / 术语检索 / MCP 工具（组 30、31、32）的领域步骤。

断言口径与 tools/java_e2e_verify.py:1240-1368 保持一致（不强不弱）：
- 30a/30b 自建术语与中文描述资产后重建索引，绝不依赖其它场景的残留。
- 31a-31e 每次调用都按调用者身份走 AccessPolicy（Agent 无后门）。
- 32a/32b 语义层接入使用仓库内 dbt YAML 夹具；不支持的来源格式必须 422。
"""

from __future__ import annotations

import json
import urllib.parse

from pytest_bdd import given, then, when

from support import config
from support.client import quote_urn

_MCP = "/api/v1/ai/mcp"

_DBT_YAML = """version: 2
models:
  - name: event_log
    columns:
      - name: event_id
      - name: amount
    metrics:
      - name: e2e_order_total
        description: 订单总额
        type: SIMPLE
        expr: sum(amount)
"""


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _rebuild_index(world, api) -> None:
    status, payload = api.call("POST", "/api/v1/index/rebuild", token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/index/rebuild", status))
    assert status == 200, f"索引重建失败：HTTP {status} {_brief(payload)}"


# ============================================================ 30) 术语同义扩展 + 中文检索


@given("我已创建术语 GMV 并登记同义词（成交额、销售额）")
def _create_term(world, api) -> None:
    urn = f"urn:dg:GlossaryTerm:{world.namespace}.gmv"
    path = f"/api/v1/assets/{quote_urn(urn)}/aspects/termSpec?entityType=GlossaryTerm"
    status, payload = api.call("POST", path, {
        "data": {"definition": "成交总额", "synonyms": ["成交额", "销售额"], "status": "APPROVED"},
        "source": "MANUAL"}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    assert status == 200, f"术语登记失败：HTTP {status} {_brief(payload)}"
    world.urns["term"] = urn
    _rebuild_index(world, api)


@when("我用术语同义词「成交额」做混合检索")
def _search_synonym(world, api) -> None:
    query = urllib.parse.quote("成交额")
    path = f"/api/v1/ai/search?q={query}&limit=10"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/ai/search?q=成交额&limit=10", status))
    world.last_status = status
    world.last_body = payload


@then("同义检索应命中术语本身并触发术语扩展召回")
def _assert_synonym(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    hits = body.get("results") or []
    term_urn = world.urns.get("term")
    assert term_urn, "术语 URN 缺失（前置未执行）"
    assert term_urn in [hit.get("urn") for hit in hits], (
        f"同义词检索未命中术语本身 {term_urn}：{_brief(body)}"
    )
    expanded = [hit for hit in hits if "glossary_expansion" in (hit.get("retrievers") or [])]
    assert expanded, f"未触发术语扩展召回（无 glossary_expansion 路）：{_brief(body)}"


@given("我已创建带中文描述的客户订单明细数据集")
def _create_cn_asset(world, api) -> None:
    urn = f"urn:dg:Dataset:{world.namespace}.pg.public.customer_order_detail"
    path = f"/api/v1/assets/{quote_urn(urn)}/aspects/descriptions"
    status, payload = api.call("POST", path, {
        "data": {"text": "客户订单明细表：用于验证中文检索能命中描述里的连续中文串",
                 "language": "zh", "source": "MANUAL"}, "source": "MANUAL"}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    assert status == 200, f"中文描述资产写入失败：HTTP {status} {_brief(payload)}"
    world.urns["cn_asset"] = urn
    _rebuild_index(world, api)


@when("我用中文关键词「订单明细」做混合检索")
def _search_chinese(world, api) -> None:
    query = urllib.parse.quote("订单明细")
    path = f"/api/v1/ai/search?q={query}&limit=10"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/ai/search?q=订单明细&limit=10", status))
    world.last_status = status
    world.last_body = payload


@then("检索应命中中文描述的目标资产，且分路状态明确标注向量路不可用")
def _assert_chinese(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    retrievers = body.get("retrievers") or []
    names = {item.get("name") for item in retrievers}
    assert {"lexical", "glossary_expansion", "vector"} <= names, (
        f"检索路不完整（应含 lexical/glossary_expansion/vector）：{sorted(names)}"
    )
    vector = next((item for item in retrievers if item.get("name") == "vector"), None)
    assert vector is not None and vector.get("available") is False, (
        f"向量路应明确标注不可用：{vector}"
    )
    cn_urn = world.urns.get("cn_asset")
    assert cn_urn, "中文资产 URN 缺失（前置未执行）"
    assert cn_urn in [hit.get("urn") for hit in (body.get("results") or [])], (
        f"中文检索未命中目标资产 {cn_urn}：{_brief(body)}"
    )


# ============================================================ 31) MCP 工具集


@when("我分别以管理员与只读身份列出 MCP 工具清单")
def _list_tools_both(world, api) -> None:
    status, admin_res = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _MCP, status))
    status_reader, reader_res = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        token=config.READER_TOKEN)
    world.calls.append(("POST", _MCP, status_reader))
    assert status == 200 and status_reader == 200, (
        f"MCP tools/list 失败：admin HTTP {status} / reader HTTP {status_reader}"
    )
    admin_tools = [t.get("name") for t in (admin_res.get("result") or {}).get("tools", [])]
    reader_tools = [t.get("name") for t in (reader_res.get("result") or {}).get("tools", [])]
    world.remember("admin_tools", admin_tools)
    world.remember("reader_tools", reader_tools)


@then("只读清单应是管理员清单的真子集，且写工具对只读不可见")
def _assert_tools_trimmed(world) -> None:
    admin = set(world.recall("admin_tools") or [])
    reader = set(world.recall("reader_tools") or [])
    assert reader < admin, (
        f"只读工具集应为管理员的真子集：admin={sorted(admin)} reader={sorted(reader)}"
    )
    assert "search_assets" in reader, "只读应能看到读工具 search_assets"
    assert "propose_aspect" in admin, "管理员应能看到写工具 propose_aspect"
    assert "propose_aspect" not in reader, "写工具 propose_aspect 不应出现在只读清单"


@when("我通过 MCP 调用 search_assets 检索 event_log")
def _mcp_search_assets(world, api) -> None:
    status, payload = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "search_assets", "arguments": {"query": "event_log"}}},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _MCP, status))
    world.last_status = status
    world.last_body = payload


@then("MCP 调用应同时返回结构化内容与文本两份结果")
def _assert_mcp_result(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    result = body.get("result") or {}
    structured = result.get("structuredContent") or {}
    assert structured.get("count", 0) > 0, (
        f"structuredContent.count 应 > 0：{_brief(body)}"
    )
    assert result.get("content"), f"应同时返回文本 content：{_brief(body)}"


@when("我以只读身份通过 MCP 尝试提交建议")
def _mcp_propose_as_reader(world, api) -> None:
    urn = world.urns.get("cn_asset") or f"urn:dg:Dataset:{world.namespace}.pg.public.event_log"
    status, payload = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 4, "method": "tools/call",
        "params": {"name": "propose_aspect", "arguments": {
            "urn": urn, "aspectType": "descriptions", "field": "text",
            "value": "越权写入尝试", "rationale": "e2e"}}},
        token=config.READER_TOKEN)
    world.calls.append(("POST", _MCP, status))
    world.last_status = status
    world.last_body = payload


@then("越权调用应返回拒绝结果并指明缺少权限")
def _assert_mcp_denied(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    result = body.get("result") or {}
    assert result.get("isError") is True, f"越权调用应返回拒绝结果 isError=true：{_brief(body)}"
    assert "无权调用" in json.dumps(result, ensure_ascii=False), (
        f"拒绝结果应指明无权限调用：{_brief(result)}"
    )


@when("我以管理员身份通过 MCP 提议为该资产补描述")
def _mcp_propose_as_admin(world, api) -> None:
    urn = world.urns.get("cn_asset")
    assert urn, "前置「我已创建带中文描述的客户订单明细数据集」未执行"
    status, payload = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 6, "method": "tools/call",
        "params": {"name": "propose_aspect", "arguments": {
            "urn": urn, "aspectType": "descriptions", "field": "text",
            "value": "由 Agent 提议的描述", "rationale": "e2e：验证 Agent 的唯一写路径是提建议"}}},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _MCP, status))
    status_pending, pending = api.call("GET", "/api/v1/ai/suggestions?status=PENDING&limit=200",
                                       token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/ai/suggestions?status=PENDING&limit=200", status_pending))
    status_after, aspect = api.call(
        "GET", f"/api/v1/assets/{quote_urn(urn)}/aspects/descriptions", token=config.ADMIN_TOKEN)
    world.calls.append(("GET", f"/api/v1/assets/{quote_urn(urn)}/aspects/descriptions", status_after))
    world.remember("propose_status", status)
    world.remember("pending_status", status_pending)
    world.remember("proposed_body", (payload.get("result") or {}).get("structuredContent") or {})
    world.remember("pending_rows", pending.get("suggestions") if isinstance(pending, dict) else None)
    world.remember("cn_aspect", aspect)


@then("建议应进入待审队列（记为人提交）且资产描述未被直接改写")
def _assert_agent_proposal(world) -> None:
    assert world.recall("propose_status") == 200, (
        f"MCP 提建议失败：HTTP {world.recall('propose_status')}"
    )
    assert world.recall("pending_status") == 200, "建议收件箱不可读"
    urn = world.urns.get("cn_asset")
    rows = world.recall("pending_rows") or []
    human_rows = [row for row in rows
                  if row.get("generator") == "human" and row.get("entity_urn") == urn]
    assert human_rows, (
        f"待审队列未出现 human 来源建议（urn={urn}）："
        f"{[(row.get('generator'), row.get('entity_urn')) for row in rows][:5]}"
    )
    current = str(((world.recall("cn_aspect") or {}).get("data") or {}).get("text"))
    assert "由 Agent 提议的描述" not in current, (
        f"元数据被直接改写（描述中出现了 Agent 提议内容）：{current[:60]}"
    )


@when("我通过 MCP 调用未实现的 resources/list 方法")
def _mcp_unknown_method(world, api) -> None:
    status, payload = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 5, "method": "resources/list", "params": {}},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _MCP, status))
    world.last_status = status
    world.last_body = payload


@given("我已通过 MCP 成功调用检索工具并触发一次越权拒绝")
def _seed_mcp_audit(world, api) -> None:
    status_ok, _ = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": "search_assets", "arguments": {"query": "event_log"}}},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _MCP, status_ok))
    assert status_ok == 200, f"MCP 检索调用失败：HTTP {status_ok}"
    status_deny, _ = api.call("POST", _MCP, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "propose_aspect", "arguments": {
            "urn": f"urn:dg:Dataset:{world.namespace}.pg.public.event_log",
            "aspectType": "descriptions", "field": "text", "value": "越权写入尝试",
            "rationale": "e2e"}}},
        token=config.READER_TOKEN)
    world.calls.append(("POST", _MCP, status_deny))
    assert status_deny == 200, f"MCP 越权调用（应返回拒绝结果）失败：HTTP {status_deny}"


@then("审计事件中应包含 MCP 工具调用或拒绝的留痕")
def _assert_mcp_audit(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    actions = {event.get("action") for event in (body.get("events") or [])}
    assert actions & {"MCP_TOOL_CALLED", "MCP_TOOL_DENIED"}, (
        f"审计事件未包含 MCP 调用/拒绝留痕：{sorted(a for a in actions if a)}"
    )


# ============================================================ 32) 语义层指标


@given("我已把 dbt 指标 e2e_order_total 接入 java_e2e 语义层")
def _ingest_semantic_layer(world, api) -> None:
    status, payload = api.call("POST", "/api/v1/ai/semantic-layer/ingest", {
        "yaml": _DBT_YAML, "namespace": world.namespace, "sourceFormat": "dbt"},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/ai/semantic-layer/ingest", status))
    assert status == 200, f"语义层接入失败：HTTP {status} {_brief(payload)}"
    assert payload.get("ingested") == 1, (
        f"应恰接入 1 个指标，实际 {payload.get('ingested')}：{_brief(payload)}"
    )
    world.remember("metric_urn", f"urn:dg:Metric:{world.namespace}.e2e_order_total")


@when("我查询该指标的口径与上游列级血缘")
def _query_metric_lineage(world, api) -> None:
    metric_path = "/api/v1/ai/semantic-layer/metrics/e2e_order_total"
    status, metric = api.call("GET", metric_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", metric_path, status))
    world.remember("metric_status", status)
    world.remember("metric_body", metric)
    urn = world.recall("metric_urn")
    sub_path = (f"/api/v1/lineage/subgraph?urn={quote_urn(urn)}"
                "&direction=upstream&depth=2&includeColumns=true")
    status2, sub = api.call("GET", sub_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/lineage/subgraph", status2))
    world.remember("sub_status", status2)
    world.remember("sub_body", sub)


@then("指标应落成 Metric 实体，且能沿 consumedBy 血缘上溯到依赖列")
def _assert_metric(world) -> None:
    assert world.recall("metric_status") == 200, (
        f"指标详情不可读：HTTP {world.recall('metric_status')}"
    )
    metric = world.recall("metric_body") or {}
    assert metric.get("columns"), f"指标未解析出依赖列：{_brief(metric)}"
    assert world.recall("sub_status") == 200, (
        f"血缘子图不可读：HTTP {world.recall('sub_status')}"
    )
    sub = world.recall("sub_body") or {}
    up_types = (sub.get("counts") or {}).get("nodesByType") or {}
    assert "Column" in up_types, f"上游子图应含列级节点（Column）：{up_types}"