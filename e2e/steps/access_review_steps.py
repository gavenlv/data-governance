"""访问治理与复核回收（组 25、26）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:1001-1072：
申请自动填充分级/审批链/SLA/最小粒度；禁止自批自用(403/422)；
批准即生成带到期时间的授权；拒绝必须说明原因(422)；
授权列表可读且权限按数组序列化；概览给出待办/超期/即将到期；
复核缺证据时给 NEED_MORE_INFO 且「不假设未使用」；REVOKE 真正回收；吊销必须说明原因(422)；
到期回收可执行且回报条数。

独立性：申请 → 审批 → 授权 → 复核 → 吊销 是有状态链路，每个场景在自己的
「假如」里用 API 幂等走完必要前置（现查现造一份属于自己的授权），不共享场景间状态。
"""

from __future__ import annotations

import json

from pytest_bdd import given, parsers, then, when

from support import config

_PURPOSE = "e2e：排查事件流异常需要读取明细"


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _target_urn(world) -> str:
    urn = world.recall("access_target_urn")
    assert urn, "尚未锁定访问治理目标数据集（前置「我已锁定…作为访问治理目标」未执行）"
    return urn


def _request_id(world):
    request_id = world.recall("access_request_id")
    assert request_id is not None, "尚未提交访问申请"
    return request_id


def _grant_id(world):
    grant_id = world.recall("access_grant_id")
    assert grant_id is not None, "尚无生效授权可供操作"
    return grant_id


# ============================================================ 前置：定位目标数据集


@given("我已锁定 java_e2e 命名空间下的 public.event_log 作为访问治理目标")
def _lock_access_target(world, api) -> None:
    """列出 java_e2e 资产取以 public.event_log 结尾者；不存在则先采集一次，仍无则显式失败。"""
    prefix = "urn:dg:Dataset:java_e2e"
    path = f"/api/v1/assets?prefix={prefix}&limit=100"

    def _query():
        status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
        world.calls.append(("GET", path, status))
        if status == 200 and isinstance(listing, dict):
            for asset in listing.get("assets") or []:
                urn = str(asset.get("urn", ""))
                if urn.startswith(f"{prefix}.") and urn.endswith("public.event_log"):
                    return urn
        return None

    urn = _query()
    if urn is None:
        body = {"jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
                "username": "postgres", "password": "root",
                "namespace": "java_e2e", "schemas": ["public"]}
        status, _ = api.call("POST", "/api/v1/collect/postgres", body, token=config.ADMIN_TOKEN)
        world.calls.append(("POST", "/api/v1/collect/postgres", status))
        assert status == 200, f"采集 java_e2e 命名空间失败：HTTP {status}"
        urn = _query()
    assert urn is not None, "java_e2e 命名空间下未找到 public.event_log 数据集（采集后仍无）"
    world.remember("access_target_urn", urn)


# ============================================================ 前置：申请 → 审批


def _submit_request(world, api) -> None:
    body = {"resourceUrn": _target_urn(world), "granularity": "DATASET", "permissions": ["SELECT"],
            "purpose": _PURPOSE, "durationDays": 30}
    status, payload = api.call("POST", "/api/v1/access/requests", body, token=config.READER_TOKEN)
    world.calls.append(("POST", "/api/v1/access/requests", status))
    world.last_status = status
    world.last_body = payload
    assert status == 200, f"提交访问申请失败：HTTP {status} {_brief(payload)}"
    assert isinstance(payload, dict) and payload.get("requestId") is not None, (
        f"申请未返回申请号：{_brief(payload)}")
    world.remember("access_request_id", payload["requestId"])


def _approve_request(world, api, days: int) -> None:
    path = f"/api/v1/access/requests/{_request_id(world)}/decide"
    body = {"decision": "APPROVED", "note": f"用途明确，批准 {days} 天"}
    status, payload = api.call("POST", path, body, token=config.STEWARD_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload
    assert status == 200, f"批准访问申请失败：HTTP {status} {_brief(payload)}"
    assert isinstance(payload, dict) and payload.get("grantId") is not None, (
        f"批准未生成授权：{_brief(payload)}")
    world.remember("access_grant_id", payload["grantId"])


@given("我已以只读身份提交读取申请并记下申请号")
def _given_submit_only(world, api) -> None:
    _submit_request(world, api)


@given("我已以只读身份申请并经治理员批准一份访问授权")
def _given_request_and_grant(world, api) -> None:
    _submit_request(world, api)
    _approve_request(world, api, 30)


# ==================================================================== 25) 申请 → 审批


@when("我以只读令牌为目标数据集提交读取申请")
def _submit_request_step(world, api) -> None:
    _submit_request(world, api)


@then("申请路由与 SLA 截止时间应已自动填充")
def _route_and_sla(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert body.get("route"), f"申请路由为空（无法判断该由谁审批）：{_brief(body)}"
    assert body.get("slaDueAt"), f"申请 SLA 截止时间为空：{_brief(body)}"


@then("最小粒度建议应给出列级申请")
def _granularity_suggestion(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    suggestion = str(body.get("granularitySuggestion"))
    assert "列级申请" in suggestion, f"最小粒度建议未给出列级申请：{suggestion}"


@then("申请备注应提示不得自批自用")
def _self_approve_note(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    notes = body.get("notes") or []
    assert any("自批自用" in str(note) for note in notes), f"申请备注未提示自批自用：{notes}"


@when("我以只读令牌尝试审批自己刚提交的申请")
def _self_approve(world, api) -> None:
    path = f"/api/v1/access/requests/{_request_id(world)}/decide"
    status, payload = api.call("POST", path, {"decision": "APPROVED", "note": "自批尝试"},
                               token=config.READER_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@then("响应状态码应为 403 或 422")
def _status_forbidden_or_unprocessable(world) -> None:
    assert world.last_status in (403, 422), (
        f"期望权限拒绝或流程拒绝（403/422），实际 HTTP {world.last_status}：{_brief(world.last_body)}")


@when(parsers.parse("我以治理员令牌批准该申请并主张 {days:d} 天期限"))
def _approve_request_step(world, api, days: int) -> None:
    _approve_request(world, api, days)


@then("审批结果应生成带到期时间的授权")
def _grant_with_expiry(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert body.get("grantId"), f"审批通过未生成授权：{_brief(body)}"
    assert body.get("expiresAt"), f"授权缺少到期时间（等于永久权限）：{_brief(body)}"


@then("审批结果的授权时长应为 30 天")
def _grant_duration(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert int(body.get("durationDays") or 0) == 30, (
        f"授权时长应为 30 天，实际 {body.get('durationDays')}：{_brief(body)}")


@when("我以治理员令牌不带任何原因地拒绝该申请")
def _reject_without_reason(world, api) -> None:
    path = f"/api/v1/access/requests/{_request_id(world)}/decide"
    status, payload = api.call("POST", path, {"decision": "REJECTED"}, token=config.STEWARD_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@then("拒绝错误的说明应指出必须给出原因")
def _reject_reason_message(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    message = str(body.get("message") if isinstance(body, dict) else body)
    assert "原因" in message, f"拒绝错误未指出必须给出原因：{message[:80]}"


@when("我以管理员令牌查询生效中的授权列表")
def _list_active_grants(world, api) -> None:
    path = "/api/v1/access/grants?status=ACTIVE"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload


@then("授权列表应包含刚生成的授权且权限以数组序列化")
def _grant_in_active_list(world) -> None:
    grant_id = _grant_id(world)
    body = world.last_body if isinstance(world.last_body, dict) else {}
    grants = body.get("grants") or []
    found = next((g for g in grants if g.get("id") == grant_id), None)
    assert found is not None, f"生效授权列表中未找到授权 #{grant_id}（共 {len(grants)} 条）"
    assert isinstance(found.get("permissions"), list), (
        f"授权权限未按数组序列化：{found.get('permissions')}")


@then("概览应给出待办、超期与即将到期三项数目")
def _overview_three_counts(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    for key in ("overdueRequests", "expiringSoon", "activeGrants"):
        assert key in body, f"治理概览缺少 {key}（没有它授权会演变成永久权限）：{_brief(body)}"


# ============================================================ 26) 复核与回收


@then("复核条目应给出「需更多信息」且理由声明不假设未使用")
def _review_need_more_info(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    items = body.get("items") or []
    assert items, f"复核批次没有任何待复核条目：{_brief(body)}"
    matched = any(
        item.get("suggestedDecision") == "NEED_MORE_INFO"
        and "不假设" in str(item.get("suggestionReason"))
        for item in items
    )
    assert matched, (
        f"复核条目未给出「需更多信息」或不假设未使用的理由："
        f"{[item.get('suggestedDecision') for item in items[:3]]}")


@when("我以管理员令牌在复核批次 e2e-2026Q1 中把该授权判定为回收")
def _review_revoke(world, api) -> None:
    path = "/api/v1/access/reviews/e2e-2026Q1/decide"
    body = {"grantId": _grant_id(world), "decision": "REVOKE", "reason": "e2e：复核回收演示"}
    status, payload = api.call("POST", path, body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@then("被回收的授权应出现在已吊销授权列表中")
def _grant_in_revoked_list(world, api) -> None:
    grant_id = _grant_id(world)
    path = "/api/v1/access/grants?status=REVOKED"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200, f"已吊销授权列表不可读：HTTP {status}"
    grants = payload.get("grants") if isinstance(payload, dict) else []
    grants = grants or []
    assert any(g.get("id") == grant_id for g in grants), (
        f"复核判定 REVOKE 未真正回收授权 #{grant_id}（共 {len(grants)} 条已吊销）")


@when("我以管理员令牌不带原因地吊销该授权")
def _revoke_without_reason(world, api) -> None:
    path = f"/api/v1/access/grants/{_grant_id(world)}/revoke"
    status, payload = api.call("POST", path, {}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@then("回收结果应回报回收条数与说明")
def _sweep_result(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert "expiredGrants" in body, f"到期回收未回报回收条数：{_brief(body)}"
    assert "note" in body, f"到期回收未回报说明：{_brief(body)}"