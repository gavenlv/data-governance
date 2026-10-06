"""策略生命周期与审计取证（组 27、28）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:1074-1169：
编译预览产物带出处注释 + 谓词可审计；建模即编译带版本；非法运算符被拒(422)；
编译 ACTIVE 策略无失败；下发给出 bundle 哈希/产物数/落盘文件；
覆盖率给出 0~1 的真实比例 + 直连绕过盲区；回滚记录指向旧产物集合的新部署；
审计报告声明覆盖范围且随实际接入变化；访问事件留痕全链路；
最小权限复盘逐条给出依据或显式标注「无使用数据」。

独立性：27d/27e/27g 的「假如」里先建模（幂等 upsert）再编译/下发，不依赖其它场景的残留状态。
"""

from __future__ import annotations

import json

from pytest_bdd import given, then, when

from support import config

# 与验证脚本 Batch 4 的策略文档一致
_POLICY = {
    "name": "e2e_region_filter",
    "description": "e2e：按区域限制行",
    "target": "trino",
    "effect": "ROW_FILTER",
    "priority": 20,
    "resourceScope": {"prefixes": ["urn:dg:Dataset:java_e2e."], "classification": ["L2", "L3", "L4"]},
    "subjectScope": {"roles": ["ANALYST_CN"]},
    "condition": {"column": "region", "operator": "=", "value": "CN"},
}


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _post(world, api, path: str, body=None):
    status, payload = api.call("POST", path, body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _body(world) -> dict:
    return world.last_body if isinstance(world.last_body, dict) else {}


# ================================================================ 27) 策略生命周期


@given("我已把区域限制策略建模入库")
def _model_policy(world, api) -> None:
    status, payload = _post(world, api, "/api/v1/policies", _POLICY)
    assert status == 200, f"策略建模失败：HTTP {status} {_brief(payload)}"


@given("我已把区域限制策略建模并编译归档")
def _model_and_compile_policy(world, api) -> None:
    status, payload = _post(world, api, "/api/v1/policies", _POLICY)
    assert status == 200, f"策略建模失败：HTTP {status} {_brief(payload)}"
    status, payload = _post(world, api, "/api/v1/policies/compile")
    assert status == 200, f"策略编译失败：HTTP {status} {_brief(payload)}"


@when("我以管理员令牌预览一条按区域限制行的策略编译产物")
def _compile_preview(world, api) -> None:
    _post(world, api, "/api/v1/policies/compile-preview", _POLICY)


@then("编译产物应带「策略：e2e_region_filter」出处注释")
def _artifact_provenance(world) -> None:
    artifact = str(_body(world).get("artifact"))
    assert "策略：e2e_region_filter" in artifact, f"编译产物缺少出处注释：{artifact[:120]}"


@then("编译元数据应给出可审计的行过滤谓词")
def _row_filter_metadata(world) -> None:
    metadata = _body(world).get("metadata") or {}
    assert metadata.get("rowFilter") == "region = 'CN'", (
        f"行过滤谓词不可审计：{metadata.get('rowFilter')}")


@when("我以管理员令牌把区域限制策略建模入库")
def _upsert_policy(world, api) -> None:
    _post(world, api, "/api/v1/policies", _POLICY)


@then("入库策略应带回显的版本号")
def _policy_version(world) -> None:
    version = _body(world).get("version")
    assert version, f"入库策略缺少版本号：{_brief(_body(world))}"


@when("我以管理员令牌提交一条使用不支持运算符 LIKE_ANY 的策略")
def _submit_unsupported_policy(world, api) -> None:
    _post(world, api, "/api/v1/policies", {
        "name": "e2e_bad", "target": "trino", "effect": "ROW_FILTER",
        "resourceScope": {"prefixes": ["urn:dg:Dataset:x."]}, "subjectScope": {"roles": ["R"]},
        "condition": {"column": "c", "operator": "LIKE_ANY", "value": "x"}})


@then("拒绝说明应指出不支持的运算符")
def _unsupported_operator(world) -> None:
    body = _body(world)
    message = str(body.get("message") if body else world.last_body)
    assert "运算符" in message, f"拒绝说明未指出不支持的运算符：{message[:80]}"


@then("编译结果应至少 1 条产物且失败清单为空")
def _compile_all_ok(world) -> None:
    body = _body(world)
    assert int(body.get("count") or 0) > 0, f"编译产物数为 0：{_brief(body)}"
    assert not body.get("failed"), f"编译存在失败项：{body.get('failed')}"


@when("我以管理员令牌把已编译产物下发到 trino 目标")
def _deploy_trino(world, api) -> None:
    _post(world, api, "/api/v1/policies/deploy?target=trino")


@then("下发结果应给出 bundle 哈希、产物条数与落盘文件")
def _deploy_bundle(world) -> None:
    body = _body(world)
    assert body.get("bundleHash"), f"下发结果缺少 bundle 哈希：{_brief(body)}"
    assert int(body.get("artifactCount") or 0) > 0, f"下发产物条数为 0：{_brief(body)}"
    assert body.get("bundleFile"), f"下发结果缺少落盘文件：{_brief(body)}"


@then("覆盖率比例应为 0 到 1 之间的真实值")
def _coverage_ratio(world) -> None:
    ratio = _body(world).get("coverageRatio")
    assert isinstance(ratio, (int, float)) and 0 <= ratio <= 1, (
        f"覆盖率比例不是 0~1 的真实值：{ratio}")


@then("已知盲区清单应显式声明直连绕过")
def _coverage_blind_spots(world) -> None:
    spots = _body(world).get("knownBlindSpots")
    assert isinstance(spots, list) and any("直连" in str(spot) for spot in spots), (
        f"已知盲区清单未声明直连绕过：{spots}")


@given("我已把 trino 目标产物下发过一次并记下部署号")
def _deploy_once(world, api) -> None:
    status, payload = _post(world, api, "/api/v1/policies/deploy?target=trino")
    assert status == 200, f"下发失败：HTTP {status} {_brief(payload)}"
    deployment_id = payload.get("deploymentId") if isinstance(payload, dict) else None
    assert deployment_id is not None, f"下发未返回部署号：{_brief(payload)}"
    world.remember("deployment_id", deployment_id)


@when("我以管理员令牌对记下的部署执行回滚")
def _rollback(world, api) -> None:
    deployment_id = world.recall("deployment_id")
    assert deployment_id is not None, "尚无部署可回滚"
    _post(world, api, f"/api/v1/policies/deployments/{deployment_id}/rollback",
          {"reason": "e2e 回滚演示"})


@then("回滚结果应记录指向旧产物集合的新部署")
def _rollback_record(world) -> None:
    body = _body(world)
    assert body.get("rolledBack"), f"回滚结果未记录被回滚的部署：{_brief(body)}"
    assert body.get("bundleHash"), f"回滚结果未指向旧产物集合（缺 bundle 哈希）：{_brief(body)}"


# ================================================================ 28) 审计取证


@then("审计报告应显式声明已覆盖与未覆盖的范围")
def _coverage_note_ranges(world) -> None:
    note = _body(world).get("coverageNote") or {}
    assert note.get("covered"), f"审计报告未声明覆盖范围：{_brief(_body(world))}"
    assert note.get("notCovered"), f"审计报告未声明未覆盖范围：{_brief(_body(world))}"
    assert "不等于" in str(note.get("implication")), (
        f"审计报告未声明「没有记录不等于没有发生」：{note.get('implication')}")


@then("审计报告的覆盖声明应随引擎审计的实际接入情况变化")
def _coverage_note_dynamic(world) -> None:
    note = _body(world).get("coverageNote") or {}
    covered_text = " ".join(str(item) for item in (note.get("covered") or []))
    not_covered_text = " ".join(str(item) for item in (note.get("notCovered") or []))
    window = note.get("engineAuditWindow") or {}
    if window.get("records"):
        # 已接入引擎审计：covered 里要有引擎查询与观测窗口；未覆盖仍声明未接入的引擎
        ok = ("引擎侧的真实查询" in covered_text and "观测窗口" in covered_text
              and "未接入审计的引擎" in not_covered_text)
        detail = "已接入引擎审计：覆盖声明应含引擎查询与观测窗口"
    else:
        # 未接入：如实声明「引擎侧真实查询」未覆盖，且强调没记录≠没发生
        ok = ("引擎侧的真实查询" in not_covered_text
              and "不等于" in str(note.get("implication")))
        detail = "未接入引擎审计：应如实声明未覆盖"
    assert ok, f"审计覆盖声明未反映实际接入情况（{detail}）：covered={covered_text[:80]}"


@then("事件流应包含申请提交与授权创建两类动作")
def _event_actions(world) -> None:
    body = _body(world)
    events = body.get("events") or []
    actions = {event.get("action") for event in events}
    missing = {"REQUEST_SUBMITTED", "GRANT_CREATED"} - actions
    assert not missing, f"访问事件流缺少动作 {missing}（共 {len(events)} 条）"


@then("最小权限复盘应逐条给出依据或显式标注「无使用数据」")
def _least_privilege(world) -> None:
    body = _body(world)
    note = str(body.get("note"))
    if body.get("usageDataAvailable") is True:
        groups = ("recentlyUsed", "neverReviewed", "neverReviewedRecent", "granularityCandidates")
        missing = [item for group in groups for item in body.get(group, []) if not item.get("reason")]
        assert not missing, f"有使用证据时存在未给出依据的结论：{len(missing)} 条"
        assert "观测窗口覆盖" in note, f"未说明「未使用」的判定标准：{note[:100]}"
    else:
        assert "无法判断授权是否在用" in note, (
            f"无使用数据时未显式标注（不得假设未使用）：{note[:100]}")