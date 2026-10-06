"""AI 建议闭环（组 29）的领域步骤。

断言口径与 tools/java_e2e_verify.py:1171-1238 保持一致（不强不弱）：
- 29a/29b 直接走通用步骤；此处只承载需要多步编排的 29c-29g。
- 每个场景自带前置（生成 → 拉取收件箱），绝不依赖上一个场景的残留状态。
- 未配置大模型是**确定性事实**：只断言 502 + 原因，绝不调用真实 LLM。
"""

from __future__ import annotations

import json

from pytest_bdd import given, then, when

from support import config
from support.client import quote_urn

_GENERATE = "/api/v1/ai/suggestions/generate?limit=30"
_INBOX = "/api/v1/ai/suggestions?limit=50"
_PENDING = "/api/v1/ai/suggestions?status=PENDING&limit=200"


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _suggestions(world) -> list:
    rows = world.recall("suggestions")
    assert isinstance(rows, list), "收件箱建议列表缺失（前置「我已生成一批待审 AI 建议到收件箱」未执行）"
    return rows


def _pick_description_target(rows: list) -> dict:
    """优先挑 descriptions（采纳后 aspect 顶层带 source=AI_GENERATED）。"""
    target = next((item for item in rows if item.get("aspect_type") == "descriptions"), None)
    if target is None:
        target = rows[0]
    return target


# ==================================================================== 建议生成 / 收件箱


@given("我已生成一批待审 AI 建议到收件箱")
def _generate_and_fetch(world, api) -> None:
    status, generated = api.call("POST", _GENERATE, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _GENERATE, status))
    assert status == 200, f"建议生成失败：HTTP {status} {_brief(generated)}"
    world.remember("generated", generated)

    status2, inbox = api.call("GET", _INBOX, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", _INBOX, status2))
    assert status2 == 200, f"建议收件箱不可读：HTTP {status2} {_brief(inbox)}"
    rows = inbox.get("suggestions") if isinstance(inbox, dict) else None
    assert isinstance(rows, list), f"收件箱响应缺少 suggestions：{_brief(inbox)}"
    world.remember("suggestions", rows)
    world.last_status = status2
    world.last_body = inbox


@when("我请求用大模型为目标数据集生成建议")
def _llm_generate(world, api) -> None:
    urn = world.recall("target_urn") or world.urns.get("target")
    assert urn, "未定位到目标数据集 URN（前置「我已定位…可剖析的 event_log 数据集」未执行）"
    status, payload = api.call("POST", "/api/v1/ai/suggestions/llm", {"urn": urn},
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/ai/suggestions/llm", status))
    world.last_status = status
    world.last_body = payload


@then("建议候选应每条都带依据，且至少一条来自确定性生成器并附来源引用")
def _assert_candidates(world) -> None:
    rows = _suggestions(world)
    assert rows, "收件箱为空：生成器没有产出任何候选建议"
    missing = [item.get("id") for item in rows if not item.get("rationale")]
    assert not missing, f"以下建议缺少依据 rationale：{missing}"
    deterministic = [item for item in rows if item.get("generator") == "deterministic"]
    assert deterministic, (
        f"收件箱中没有确定性生成器产出的建议：{[item.get('generator') for item in rows]}"
    )
    no_ref = [item.get("id") for item in deterministic if not item.get("generator_ref")]
    assert not no_ref, f"以下生成器建议缺少来源引用 generator_ref：{no_ref}"


# ==================================================================== 采纳 / 驳回


@when("我采纳其中一条建议并回读目标资产落库的 aspect")
def _accept_and_read(world, api) -> None:
    rows = _suggestions(world)
    assert rows, "收件箱没有可采纳的建议"
    target = _pick_description_target(rows)
    suggestion_id = target.get("id")
    path = f"/api/v1/ai/suggestions/{suggestion_id}/accept"
    status, accepted = api.call("POST", path, {}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    assert status == 200, f"采纳建议失败：HTTP {status} {_brief(accepted)}"

    urn = accepted.get("entityUrn")
    aspect_type = accepted.get("aspectType")
    detail_path = f"/api/v1/assets/{quote_urn(urn)}"
    status2, asset = api.call("GET", detail_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", detail_path, status2))
    assert status2 == 200, f"资产详情不可读：HTTP {status2} {_brief(asset)}"
    applied = (asset.get("aspects") or {}).get(aspect_type) if isinstance(asset, dict) else None
    world.remember("accepted", accepted)
    world.remember("applied_aspect", applied)


@then("该建议应标记为 ACCEPTED，且写入 aspect 的来源为 AI_GENERATED")
def _assert_accept(world) -> None:
    accepted = world.recall("accepted") or {}
    assert accepted.get("status") == "ACCEPTED", (
        f"建议状态应为 ACCEPTED，实际 {accepted.get('status')}：{_brief(accepted)}"
    )
    applied = world.recall("applied_aspect")
    assert applied is not None, "采纳后目标资产的 aspect 未落库"
    assert applied.get("source") == "AI_GENERATED", (
        f"aspect 来源应为 AI_GENERATED，实际 {applied.get('source')}：{_brief(applied)}"
    )


@when("我对一条待审建议先无理由驳回、再有理由驳回")
def _reject_twice(world, api) -> None:
    rows = _suggestions(world)
    assert rows, "收件箱没有可驳回的建议"
    suggestion_id = rows[0].get("id")
    path = f"/api/v1/ai/suggestions/{suggestion_id}/reject"
    bad_status, _ = api.call("POST", path, {}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, bad_status))
    ok_status, rejected = api.call(
        "POST", path, {"reason": "e2e：该资产由上游统一命名，不需要单独描述"},
        token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, ok_status))
    world.remember("reject_bad_status", bad_status)
    world.remember("reject_ok_status", ok_status)
    world.remember("rejected", rejected)


@then("无理由驳回应被拒（422），而有理由驳回应成功并标记为 REJECTED")
def _assert_reject(world) -> None:
    bad = world.recall("reject_bad_status")
    ok = world.recall("reject_ok_status")
    rejected = world.recall("rejected") or {}
    assert bad == 422, f"无理由驳回应返回 422，实际 {bad}"
    assert ok == 200, f"有理由驳回应返回 200，实际 {ok}：{_brief(rejected)}"
    assert rejected.get("status") == "REJECTED", (
        f"有理由驳回应标记 REJECTED，实际 {rejected.get('status')}：{_brief(rejected)}"
    )


# ==================================================================== 采纳率 / 幂等


@given("我已实际采纳一条建议并带有理由地驳回一条建议")
def _accept_and_reject_for_metrics(world, api) -> None:
    rows = _suggestions(world)
    assert rows, "收件箱没有可裁决的建议"
    target = _pick_description_target(rows)
    suggestion_id = target.get("id")
    path = f"/api/v1/ai/suggestions/{suggestion_id}/accept"
    status, _ = api.call("POST", path, {}, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    assert status == 200, f"采纳失败：HTTP {status}"

    remaining = [item for item in rows if item.get("id") != suggestion_id]
    if remaining:
        reject_id = remaining[0].get("id")
        reject_path = f"/api/v1/ai/suggestions/{reject_id}/reject"
        status2, _ = api.call("POST", reject_path,
                              {"reason": "e2e：采纳率统计口径，非真实驳回"}, token=config.ADMIN_TOKEN)
        world.calls.append(("POST", reject_path, status2))
        assert status2 == 200, f"驳回失败：HTTP {status2}"


@then("采纳率统计应给出已审条数与可计算的采纳率（目标 ≥ 40%）")
def _assert_metrics(world) -> None:
    metrics = world.last_body if isinstance(world.last_body, dict) else {}
    rates = metrics.get("acceptanceRate") or []
    assert rates, f"采纳率统计为空：{_brief(metrics)}"
    top = rates[0]
    assert (top.get("reviewed") or 0) >= 1, f"首条统计 reviewed 应 >= 1：{_brief(rates)}"
    assert top.get("rate") is not None, f"采纳率应可计算（非 None）：{_brief(top)}"
    assert "≥ 40%" in str(metrics.get("target")), (
        f"目标口径应给出「≥ 40%」：{metrics.get('target')}"
    )


def _pending_keys(body) -> set:
    items = body.get("items") if isinstance(body, dict) else None
    return {(it.get("entityUrn"), it.get("kind")) for it in (items or [])}


@when("我再次触发建议生成并对比生成前后的待审总数")
def _generate_again(world, api) -> None:
    # 确定性生成器的候选池远大于单次调用能吸收的量，"第二次生成必须新增 0 条"
    # 这个**数字代理**在本数据状态下并不成立（池子会持续补位，导致误报）。
    # 因此改为断言 29g 的真正语义：已有待审同类建议的资产不得被重复创建，且不报错。
    _, before = api.call("GET", _PENDING, token=config.ADMIN_TOKEN)
    status_again, again = api.call("POST", _GENERATE, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", _GENERATE, status_again))
    status_inbox, after = api.call("GET", _PENDING, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", _PENDING, status_inbox))
    world.remember("again", again)
    world.remember("again_status", status_again)
    world.remember("before_keys", _pending_keys(before))
    world.remember("before_count", before.get("count") if isinstance(before, dict) else None)
    world.remember("after_count", after.get("count") if isinstance(after, dict) else None)
    world.remember("inbox_status", status_inbox)


@then("第二次生成不得重复创建同类建议，且不报错")
def _assert_idempotent(world) -> None:
    assert world.recall("again_status") == 200, (
        f"第二次生成失败（应为 200，不得因冲突报错）：HTTP {world.recall('again_status')}"
    )
    assert world.recall("inbox_status") == 200, (
        f"生成后收件箱不可读：HTTP {world.recall('inbox_status')}"
    )
    again = world.recall("again") or {}
    items = again.get("items") or []
    before_keys = world.recall("before_keys") or set()
    dups = [(it.get("entityUrn"), it.get("kind")) for it in items
            if (it.get("entityUrn"), it.get("kind")) in before_keys]
    assert not dups, (
        f"重复创建了已有待审同类建议：{dups}（生成前已有 {len(before_keys)} 条待审）"
    )
    seen: set = set()
    for it in items:
        key = (it.get("entityUrn"), it.get("kind"))
        assert key not in seen, f"同一次生成内出现重复建议：{key}"
        seen.add(key)