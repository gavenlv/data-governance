"""Edge Agent（推模式）与引擎侧访问审计摄入（组 35、36）的领域步骤。

断言与 tools/java_e2e_verify.py:1471-1662 保持一致（不强不弱）：
- 35 组：Agent 注册/自检/心跳/上报/护栏/吊销/管理面鉴权，全部围绕**Agent 一次性凭据**；
- 36 组：引擎审计摄入四数字、幂等、覆盖、未解析留痕、最小权限复盘、未授权访问、动态覆盖口径。

场景独立性：需要资产存在时先在「假如」里真实采集（幂等 upsert）；需要历史授权时用
psql 只写/删自己的合成行（绝不 TRUNCATE）；Agent 凭据在同一场景内自建并存入 world 传递。
"""

from __future__ import annotations

import json

from pytest_bdd import given, parsers, then, when

from support import config, db, tools_runner

_AGENT_ID = "e2e_edge_agent"
_EDGE_NAMESPACE = "e2e_edge"
_EDGE_DATASET = "urn:dg:Dataset:e2e_edge.postgresql.edge_dw.public.edge_orders"

# 组 36 的固定主体（与验证脚本 36e/36f 一致）
_USED_SUBJECT = "engine-used@local"
_UNUSED_SUBJECT = "engine-unused@local"
_BYPASS_SUBJECT = "engine-bypass@local"

# 一批 trino 记录：历史（拉长观测窗口）、真实访问、同查询重放、未授权直连、
# 解析不到资产、缺时间字段被拒 —— 与验证脚本 36a 的 pending_records 一一对应。
_PENDING_RECORDS = [
    {"user": _USED_SUBJECT, "queryId": "e2e-ea-0", "timestamp": "2025-08-01T00:00:00Z",
     "table": "dg.public.event_log", "operation": "SELECT"},
    {"user": _USED_SUBJECT, "queryId": "e2e-ea-1", "timestamp": "2026-10-04T09:00:00Z",
     "table": "dg.public.event_log", "columns": ["event_id", "amount"],
     "rowsScanned": 1500, "operation": "SELECT"},
    {"user": _USED_SUBJECT, "queryId": "e2e-ea-1", "timestamp": "2026-10-04T09:00:00Z",
     "table": "dg.public.event_log", "columns": ["event_id", "amount"],
     "rowsScanned": 1500, "operation": "SELECT"},
    {"reqUser": _BYPASS_SUBJECT, "id": "e2e-ea-2", "accessTime": "2026-10-04T09:30:00Z",
     "resource": "dg.public.event_log", "access": "select", "result": "ALLOWED"},
    {"user": _USED_SUBJECT, "queryId": "e2e-ea-3", "timestamp": "2026-10-04T10:00:00Z",
     "table": "unknown_ns.public.mystery_table", "operation": "SELECT"},
    {"user": _USED_SUBJECT, "table": "dg.public.event_log"},
]

# 36h：交给参考采集器推送的两条良构查询日志
_LOADER_RECORDS = [
    {"user": _USED_SUBJECT, "queryId": "e2e-loader-1", "timestamp": "2026-10-04T13:00:00Z",
     "table": "dg.public.event_log", "operation": "SELECT"},
    {"user": _USED_SUBJECT, "queryId": "e2e-loader-2", "timestamp": "2026-10-04T13:05:00Z",
     "table": "dg.public.event_log", "operation": "SELECT"},
]

_EDGE_DATASETS = [{
    "platform": "postgresql", "database": "edge_dw", "schema": "public",
    "table": "edge_orders", "description": "边缘上报的订单表", "primaryKey": ["id"],
    "columns": [{"name": "id", "type": "BIGINT", "nullable": False},
                {"name": "amount", "type": "DECIMAL", "nullable": True}],
}]


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _call(world, api, method: str, path: str, token, body: dict | None = None):
    status, payload = api.call(method, path, body, token=token)
    world.calls.append((method, path, status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _agent_token(world) -> str:
    token = world.recall("agent_token")
    assert token, "当前场景未持有 Agent 一次性凭据（前置「我已注册…」未执行）"
    return str(token)


# ============================================================ 35) Edge Agent 推模式


@given("我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据")
def _register_agent(world, api) -> None:
    status, payload = _call(world, api, "POST", "/api/v1/edge/agents", config.ADMIN_TOKEN, {
        "agentId": _AGENT_ID, "displayName": "e2e Agent", "namespace": _EDGE_NAMESPACE,
        "capabilities": ["postgres"], "version": "0.1.0"})
    assert status == 200, f"Agent 注册失败：HTTP {status} {_brief(payload)}"
    token = payload.get("token") if isinstance(payload, dict) else None
    assert token, f"注册未一次性下发凭据：{_brief(payload)}"
    world.remember("agent_token", token)


@then("注册返回的一次性 Agent 凭据应为明文且以 dgagent_ 开头")
def _assert_agent_token(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    token = body.get("token")
    assert token and str(token).startswith("dgagent_"), (
        f"未返回 dgagent_ 前缀的一次性明文凭据：{_brief(world.last_body)}")
    world.remember("agent_token", token)


@then("注册响应应声明 Go 单二进制 Agent 未实现")
def _assert_agent_binary_note(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert "未实现" in str(body.get("agentBinary")), (
        f"注册响应未声明 Agent 二进制未实现：{_brief(world.last_body)}")


@when("我以该 Agent 身份凭据调用 GET /api/v1/edge/agent/whoami")
def _agent_whoami(world, api) -> None:
    _call(world, api, "GET", "/api/v1/edge/agent/whoami", _agent_token(world))


@when("我以该 Agent 身份凭据发送一次心跳")
def _agent_heartbeat(world, api) -> None:
    _call(world, api, "POST", "/api/v1/edge/agent/heartbeat", _agent_token(world),
          {"version": "0.1.0"})


@when("我以该 Agent 身份凭据上报边缘数据集 edge_orders")
def _agent_report(world, api) -> None:
    _call(world, api, "POST", "/api/v1/edge/agent/report", _agent_token(world),
          {"namespace": _EDGE_NAMESPACE, "datasets": _EDGE_DATASETS})
    world.remember("edge_dataset", _EDGE_DATASET)


@then("上报说明应声明 AUTO_COLLECTED 来源")
def _assert_auto_collected(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert "AUTO_COLLECTED" in str(body.get("note")), (
        f"上报说明未声明 AUTO_COLLECTED 来源：{_brief(world.last_body)}")


@then("上报后的边缘数据集应能在检索中被命中")
def _assert_edge_searchable(world, api) -> None:
    status, _ = api.call("POST", "/api/v1/index/rebuild", token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/index/rebuild", status))
    status, found = api.call("GET", "/api/v1/ai/search?q=edge_orders&limit=5",
                             token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/ai/search?q=edge_orders&limit=5", status))
    hits = [item.get("urn") for item in (found.get("results") or [])] \
        if isinstance(found, dict) else []
    assert _EDGE_DATASET in hits, (
        f"边缘上报的数据集未进入同一套检索真相源：命中={hits}")


@given("我已让 e2e_edge_agent 心跳一次")
def _given_heartbeat(world, api) -> None:
    status, payload = _call(world, api, "POST", "/api/v1/edge/agent/heartbeat",
                            _agent_token(world), {"version": "0.1.0"})
    assert status == 200, f"心跳失败：HTTP {status} {_brief(payload)}"


@given("我已让 e2e_edge_agent 提交一次超限上报（必然被拒）")
def _given_oversized_rejected(world, api) -> None:
    datasets = [{"platform": "postgresql", "database": "edge_dw", "schema": "public",
                 "table": f"t{i}", "columns": []} for i in range(5001)]
    status, payload = _call(world, api, "POST", "/api/v1/edge/agent/report",
                            _agent_token(world), {"namespace": _EDGE_NAMESPACE,
                                                  "datasets": datasets})
    assert status == 422, f"超限上报应被拒绝（422），实际 HTTP {status}：{_brief(payload)}"


@when(parsers.parse("我以该 Agent 身份凭据上报 {count:d} 个数据集"))
def _agent_report_many(world, api, count: int) -> None:
    datasets = [{"platform": "postgresql", "database": "edge_dw", "schema": "public",
                 "table": f"t{i}", "columns": []} for i in range(count)]
    _call(world, api, "POST", "/api/v1/edge/agent/report", _agent_token(world),
          {"namespace": _EDGE_NAMESPACE, "datasets": datasets})


@then("上报记录中应存在被拒且带原因的条目")
def _assert_rejected_report(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    rows = body.get("reports") or []
    rejected = [row for row in rows
                if row.get("accepted") is False and row.get("reject_reason")]
    assert rejected, (
        f"没有「被拒且带原因」的上报记录（无法区分没推与推了但被拒）：{_brief(world.last_body)}")


@then("被吊销的 Agent 再用自身凭据心跳应被拒")
def _assert_revoked_heartbeat(world, api) -> None:
    token = _agent_token(world)
    status, payload = api.call("POST", "/api/v1/edge/agent/heartbeat", {}, token=token)
    world.calls.append(("POST", "/api/v1/edge/agent/heartbeat", status))
    world.last_status = status
    world.last_body = payload
    assert status == 401, f"吊销后凭据应立即失效（期望 401），实际 HTTP {status}：{_brief(payload)}"


@then("匿名访问管理面应返回 401 或 403")
def _assert_anonymous_denied(world) -> None:
    assert world.last_status in (401, 403), (
        f"匿名访问管理面应被拒（401/403），实际 HTTP {world.last_status}："
        f"{_brief(world.last_body)}")


# ==================================================== 36) 引擎侧访问审计摄入


def _ingest_trino_batch(world, api):
    status, payload = _call(world, api, "POST", "/api/v1/access/engine-audit",
                            config.ADMIN_TOKEN,
                            {"engine": "trino", "namespace": world.namespace,
                             "records": _PENDING_RECORDS})
    assert status == 200, f"引擎审计摄入失败：HTTP {status} {_brief(payload)}"
    world.remember("trino_ingest", payload)
    return payload


@when("我以管理员令牌摄入一批 trino 审计记录（含历史、重放、未解析与缺字段被拒）")
def _when_ingest_trino(world, api) -> None:
    _ingest_trino_batch(world, api)


@given("引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）")
def _given_ingest_trino(world, api) -> None:
    _ingest_trino_batch(world, api)


@then("引擎审计摄入应按四个数字如实回报：接受加重复不少于 4、未解析至少 1、被拒恰好 1")
def _assert_ingest_numbers(world) -> None:
    body = world.recall("trino_ingest") or (world.last_body
                                            if isinstance(world.last_body, dict) else {})
    accepted = body.get("accepted", 0)
    duplicated = body.get("duplicated", 0)
    unresolved = body.get("unresolved", 0)
    rejected = body.get("rejected", 0)
    assert accepted + duplicated >= 4, (
        f"接受加重复应不少于 4，实际 接受 {accepted} + 重复 {duplicated}：{_brief(body)}")
    assert unresolved >= 1, f"未解析应至少 1，实际 {unresolved}：{_brief(body)}"
    assert rejected == 1, f"被拒应恰好 1，实际 {rejected}：{_brief(body)}"


@when("我以管理员令牌把同一批 trino 记录再摄入一次")
def _reingest_trino(world, api) -> None:
    status, payload = _call(world, api, "POST", "/api/v1/access/engine-audit",
                            config.ADMIN_TOKEN,
                            {"engine": "trino", "namespace": world.namespace,
                             "records": _PENDING_RECORDS})
    world.remember("trino_reingest", payload)
    world.last_status = status


@then("重放摄入应新增 0 条且去重数等于首次接受加重复")
def _assert_idempotent(world) -> None:
    first = world.recall("trino_ingest") or {}
    again = world.recall("trino_reingest") or {}
    assert again.get("accepted") == 0, (
        f"重放不应新增，实际新增 {again.get('accepted')}：{_brief(again)}")
    expected = first.get("accepted", 0) + first.get("duplicated", 0)
    assert again.get("duplicated") == expected, (
        f"重放去重数应等于首次接受加重复（{expected}），实际 {again.get('duplicated')}："
        f"{_brief(again)}")


@then("引擎清单中 trino 的记录数至少为 4 且未解析至少为 1")
def _assert_coverage_trino(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    engines = {row.get("engine"): row for row in (body.get("byEngine") or [])}
    trino = engines.get("trino", {})
    assert trino.get("records", 0) >= 4, (
        f"引擎清单中 trino 记录数应至少 4，实际 {trino.get('records')}：{_brief(body)}")
    assert trino.get("unresolved", 0) >= 1, (
        f"引擎清单中 trino 未解析应至少 1，实际 {trino.get('unresolved')}：{_brief(body)}")


@then("未解析记录应保留且都带解析说明")
def _assert_unresolved_kept(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    unresolved = [row for row in (body.get("records") or [])
                  if row.get("resolved") is False]
    assert unresolved, f"未解析记录被丢弃了：{_brief(world.last_body)}"
    missing = [row.get("resource_raw") for row in unresolved if not row.get("resolve_note")]
    assert not missing, f"以下未解析记录缺少解析说明：{missing}"


@given(parsers.parse("我已定位用于接入审计的 {ns} event_log 数据集"))
def _locate_audit_target(world, api, ns: str) -> None:
    path = f"/api/v1/assets?prefix=urn:dg:Dataset:{ns}&limit=200"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status}"
    prefix = f"urn:dg:Dataset:{ns}."
    candidates = [a.get("urn") for a in (listing.get("assets") or [])
                  if str(a.get("urn", "")).startswith(prefix)
                  and str(a.get("urn", "")).endswith("public.event_log")]
    urn = next((item for item in candidates if ".postgresql." in str(item)), None)
    urn = urn or (candidates[0] if candidates else None)
    assert urn, f"未定位到 {ns} 的 event_log 数据集（采集是否成功？）：{_brief(listing)}"
    world.remember("audit_target_urn", urn)


@given(parsers.parse("我已为目标数据集给 {subject} 造好 {days:d} 天前的历史授权"))
def _seed_backdated_grant(world, psql: str, subject: str, days: int) -> None:
    urn = world.recall("audit_target_urn")
    assert urn, "未定位审计目标数据集 URN（前置「我已定位…」未执行）"
    ok = db.seed_backdated_grant(psql, subject, str(urn), days)
    assert ok, f"造历史授权失败：{subject} → {urn}"


@then("最小权限复盘应把 engine-used@local 列为在用、engine-unused@local 列为窗口内零访问的回收候选")
def _assert_least_privilege(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    used = [item for item in (body.get("recentlyUsed") or [])
            if item.get("subject") == _USED_SUBJECT]
    unused = [item for item in (body.get("neverReviewed") or [])
              if item.get("subject") == _UNUSED_SUBJECT]
    assert used, f"用过的授权未进入「在用」：{_brief(world.last_body)}"
    assert unused, f"窗口完整覆盖且零访问的授权未进入「回收候选」：{_brief(world.last_body)}"
    reason = str(unused[0].get("reason"))
    assert "零访问" in reason, f"回收候选未说明「零访问」：{reason}"


@then("未授权访问主体应包含 engine-bypass@local 且不包含 engine-used@local")
def _assert_unapproved(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    actors = {row.get("actor") for row in (body.get("unapproved") or [])}
    assert _BYPASS_SUBJECT in actors, (
        f"「被访问但从未被批准」的主体未被查出：{sorted(actors)}")
    assert _USED_SUBJECT not in actors, (
        f"有授权的 {_USED_SUBJECT} 不应出现在未授权访问里：{sorted(actors)}")


@then("审计覆盖说明应动态声明已覆盖引擎侧查询且仍列出未接入审计的引擎")
def _assert_coverage_note(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    note = body.get("coverageNote") or {}
    covered = " ".join(note.get("covered") or [])
    not_covered = " ".join(note.get("notCovered") or [])
    assert "引擎侧的真实查询" in covered, f"覆盖说明未声明已覆盖引擎侧真实查询：{covered}"
    assert "观测窗口" in covered, f"覆盖说明未附观测窗口：{covered}"
    assert "未接入审计的引擎" in not_covered, f"覆盖说明未列出未接入审计的引擎：{not_covered}"


@when("我用参考采集器把两条 JSONL 查询日志以 warehouse 引擎推上来")
def _run_loader(world, api) -> None:
    result = tools_runner.run_engine_audit_loader(
        _LOADER_RECORDS, "warehouse", world.namespace, api.base_url, config.ADMIN_TOKEN)
    world.remember("loader_result", result)


@then("采集器应无拒绝且接受加去重恰好 2 条")
def _assert_loader(world) -> None:
    result = world.recall("loader_result")
    assert result is not None, "参考采集器未返回结论（脚本失败或无 JSON 输出）"
    assert result.get("rejected") == 0, f"采集器不应有被拒记录：{_brief(result)}"
    total = result.get("accepted", 0) + result.get("duplicated", 0)
    assert total == 2, f"接受加去重应恰好 2，实际 {total}：{_brief(result)}"