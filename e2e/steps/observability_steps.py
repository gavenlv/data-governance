"""可观测性（组 33 异常检测、组 34 SLO 与事故闭环）的领域步骤。

断言忠实于 tools/java_e2e_verify.py:1370-1470（不强不弱）：
- 33a MAD 稳健 Z 的越界检出，必须回报 method / zThreshold / samples（误报可回溯到方法）；
- 33b 上游异常存在时，下游连锁异常标 PROPAGATED 且被抑制（防告警风暴）；
- 33c 被抑制的异常默认不出现、includeSuppressed 后可查（抑制 ≠ 删除）；
- 33d 样本不足的序列显式跳过（「没判定」不等于「正常」）；
- 33e 未实现的方法 422 并说明 L1/L2 分层；
- 34a 达成率来自真实执行数据（rule_run），无数据时回报 no_data；
- 34b 非法 SLO 定义被拒（类型白名单 + target ∈ (0,1]）；
- 34c/34d/34e/34f 事故影响面来自血缘、闭环强制、时间线留痕、运营总览两个观察点。

每个场景独立可跑：需要合成历史序列的在「假如」里用 support.db 现造（带 @psql），
需要血缘的在「假如」里幂等解析 event_log→alert_event。**绝不 TRUNCATE**，
只写/删自己 dataset_urn 前缀（java_e2e）下的合成行。
"""

from __future__ import annotations

import json

from pytest_bdd import given, parsers, then, when

from support import config, db

# 与紧盯验证脚本一致：event_log→alert_event 的 sql_parse 血缘（幂等 upsert）。
_SEED_SQL = ("INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n"
             "  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'")

_SLO_NAME = "e2e_quality_pass"
_CLOSED_LOOP_RULE_SUFFIX = "e2e_rowcount"


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _get(world, api, path: str):
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _post(world, api, path: str, body: dict):
    status, payload = api.call("POST", path, body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _dataset_urn(world, api, ns: str, table: str) -> str:
    """列出命名空间资产，取以 public.{table} 结尾的数据集 URN；找不到显式失败（不 skip）。"""
    path = f"/api/v1/assets?prefix=urn:dg:Dataset:{ns}&limit=100"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status} {_brief(listing)}"
    head = f"urn:dg:Dataset:{ns}."
    suffix = f"public.{table}"
    urn = next((a.get("urn") for a in (listing.get("assets") or [])
                if str(a.get("urn", "")).startswith(head) and str(a.get("urn", "")).endswith(suffix)), None)
    assert urn, f"未找到 {ns} 命名空间下的 public.{table} 数据集 URN（采集是否成功？）"
    return urn


def _up_urn(world) -> str:
    urn = world.urns.get("anomaly_up")
    assert urn, "尚未定位异常检测上游数据集（前置「我已定位…」未执行）"
    return urn


def _scan(world, api, dataset_urn: str, metric: str):
    return _post(world, api, "/api/v1/observability/anomalies/scan",
                 {"datasetUrn": dataset_urn, "metric": metric, "method": "mad"})


def _open_incident(world, api, primary_urn: str) -> int:
    status, payload = _post(world, api, "/api/v1/observability/incidents", {
        "title": "e2e：event_log 行数异常", "severity": "HIGH", "primaryUrn": primary_urn,
        "source": "anomaly", "sourceRef": "e2e"})
    assert status == 200 and isinstance(payload, dict) and payload.get("incidentId"), (
        f"开事故失败：HTTP {status} {_brief(payload)}")
    incident_id = int(payload["incidentId"])
    world.remember("incident_id", incident_id)
    return incident_id


def _incident_id(world) -> int:
    incident_id = world.recall("incident_id")
    assert incident_id is not None, "尚未开出事故（前置「我已就事故主资产开出…」未执行）"
    return incident_id


def _resolve(world, api, body: dict):
    path = f"/api/v1/observability/incidents/{_incident_id(world)}/resolve"
    return _post(world, api, path, body)


# ============================================== 33) 异常检测：稳健统计 + 上游抑制下游


@given(parsers.parse("我已定位 {ns} 命名空间下 public.{table} 作为异常检测上游数据集"))
def _locate_anomaly_up(world, api, ns: str, table: str) -> None:
    world.urns["anomaly_up"] = _dataset_urn(world, api, ns, table)


@given("我已定位 event_log→alert_event 上下游数据集并解析出两者的血缘")
def _locate_up_down_and_lineage(world, api) -> None:
    world.urns["anomaly_up"] = _dataset_urn(world, api, world.namespace, "event_log")
    world.urns["anomaly_down"] = _dataset_urn(world, api, world.namespace, "alert_event")
    status, payload = api.call("POST", "/api/v1/lineage/parse",
                               {"sql": _SEED_SQL, "dialect": "postgres", "namespace": world.namespace},
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/lineage/parse", status))
    assert status == 200, f"解析 event_log→alert_event 血缘失败：HTTP {status} {_brief(payload)}"


@given("我已为该上游数据集注入 MAD 可检出的合成历史序列")
def _seed_up_series(world, psql) -> None:
    ok = db.seed_metric_series(psql, _up_urn(world))
    assert ok, f"注入合成历史序列失败（psql 执行返回非 0）：{_up_urn(world)}"


@given("我已为上下游数据集注入合成历史序列")
def _seed_both_series(world, psql) -> None:
    up = _up_urn(world)
    down = world.urns.get("anomaly_down")
    assert down, "尚未定位下游数据集"
    assert db.seed_metric_series(psql, up), f"注入上游合成序列失败：{up}"
    assert db.seed_metric_series(psql, down), f"注入下游合成序列失败：{down}"


@given("我已为该上游数据集注入样本不足的合成短序列")
def _seed_short_series(world, psql) -> None:
    ok = db.seed_short_series(psql, _up_urn(world))
    assert ok, f"注入样本不足短序列失败：{_up_urn(world)}"


@given("我已对上游数据集完成一次 mad 扫描以留下异常痕迹")
def _scan_up_first(world, api) -> None:
    status, payload = _scan(world, api, _up_urn(world), "row_count")
    assert status == 200 and isinstance(payload, dict), f"上游异常扫描失败：HTTP {status}"
    assert payload.get("detectionCount", 0) >= 1, (
        f"上游应至少检出 1 条异常（供下游传播判定）：{_brief(payload)}")


@when("我对上游数据集发起方法为 mad 的异常扫描")
def _scan_up(world, api) -> None:
    _scan(world, api, _up_urn(world), "row_count")


@when("我对下游数据集发起方法为 mad 的异常扫描")
def _scan_down(world, api) -> None:
    down = world.urns.get("anomaly_down")
    assert down, "尚未定位下游数据集"
    _scan(world, api, down, "row_count")


@when("我对上游数据集的短序列发起方法为 mad 的异常扫描")
def _scan_short(world, api) -> None:
    _scan(world, api, _up_urn(world), "e2e_short_series")


@when("我分别查看默认异常清单与含抑制的异常清单")
def _list_hidden_and_shown(world, api) -> None:
    hidden_status, hidden = _get(world, api, "/api/v1/observability/anomalies?limit=50")
    shown_status, shown = _get(world, api, "/api/v1/observability/anomalies?includeSuppressed=true&limit=100")
    world.remember("hidden_status", hidden_status)
    world.remember("shown_status", shown_status)
    world.remember("hidden_count", (hidden or {}).get("count") if isinstance(hidden, dict) else None)
    world.remember("shown_count", (shown or {}).get("count") if isinstance(shown, dict) else None)
    # last_* 指向默认清单，便于人工排查
    world.last_status, world.last_body = hidden_status, hidden


@then("首条检出应给出不小于 3 的稳健 Z 绝对值且扫描回报了阈值")
def _assert_mad_explainable(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    detections = body.get("detections") or []
    assert detections, f"未检出任何异常：{_brief(world.last_body)}"
    first = detections[0]
    assert abs(first.get("score") or 0) >= 3, (
        f"首条检出的稳健 Z 绝对值应 ≥ 3（可回溯到方法/阈值），实际 score={first.get('score')}")
    assert body.get("zThreshold") is not None, "扫描未回报 zThreshold，误报无法回溯到阈值"
    assert first.get("method") == "mad", f"首条检出的方法应为 mad，实际 {first.get('method')}"


@then("下游首条检出应被标记为 PROPAGATED 并抑制、来源指向上游")
def _assert_propagated(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    detections = body.get("detections") or []
    assert detections, f"下游未检出任何异常：{_brief(world.last_body)}"
    first = detections[0]
    assert first.get("propagation") == "PROPAGATED", (
        f"下游连锁异常应标为 PROPAGATED，实际 {first.get('propagation')}")
    assert first.get("suppressed") is True, "被判为传播的异常应被抑制（防告警风暴）"
    assert first.get("propagatedFrom") == _up_urn(world), (
        f"传播来源应为上游 {_up_urn(world).split('.')[-1]}，实际 {first.get('propagatedFrom')}")
    assert str(first.get("suppressReason") or ""), "被抑制的异常应给出抑制原因"


@then("含抑制的异常清单应比默认清单多出被抑制的条目")
def _assert_suppressed_hidden(world) -> None:
    hidden_status = world.recall("hidden_status")
    shown_status = world.recall("shown_status")
    hidden_count = world.recall("hidden_count")
    shown_count = world.recall("shown_count")
    assert hidden_status == 200 and shown_status == 200, (
        f"异常清单不可读：默认 HTTP {hidden_status} / 含抑制 HTTP {shown_status}")
    assert hidden_count is not None and shown_count is not None, "异常清单缺少 count 字段"
    assert shown_count > hidden_count, (
        f"含抑制清单应多于默认清单（抑制 ≠ 删除），默认 {hidden_count} 条 / 含抑制 {shown_count} 条")


@then("样本不足的序列应被显式跳过而非判为正常")
def _assert_short_series_skipped(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    skipped = body.get("skipped") or []
    assert skipped, f"样本不足的序列未被显式跳过（不显式说明就等于「看着正常」）：{_brief(world.last_body)}"
    bad = [item for item in skipped if "样本不足" not in str(item.get("reason", ""))]
    assert not bad, f"以下序列的跳过原因未说明样本不足：{bad}"


# ============================================================ 34) SLO 与事故闭环


@given("我已定义 java_e2e 范围内的质量通过率 SLO")
def _define_slo(world, api) -> None:
    status, payload = _post(world, api, "/api/v1/observability/slos", {
        "name": _SLO_NAME, "sloType": "quality_pass_rate", "target": 0.95, "windowDays": 30,
        "resourceScope": {"prefixes": [f"urn:dg:Dataset:{world.namespace}."]},
        "owner": "data-steward"})
    assert status == 200, f"定义 SLO 失败：HTTP {status} {_brief(payload)}"


@when("我度量该 SLO 的达成率")
def _measure_slo(world, api) -> None:
    _post(world, api, f"/api/v1/observability/slos/{_SLO_NAME}/measure", {})


@then("达成率应来自真实执行数据或明确回报无数据")
def _assert_slo_measurement(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    attainment = body.get("attainment")
    no_data = body.get("noData")
    assert attainment is not None or no_data is True, (
        f"达成率既非真实计算值也未回报 no_data：{_brief(world.last_body)}")
    if attainment is not None:
        assert 0 <= float(attainment) <= 1, f"达成率越界：{attainment}"
        assert body.get("totalEvents", 0) >= 1, (
            f"有达成率却没有执行事件数（须来自真实执行数据）：{_brief(world.last_body)}")
    else:
        assert str(body.get("note") or ""), "回报 no_data 时应给出原因说明"


@given(parsers.parse("我已定位 {ns} 命名空间下 public.{table} 作为事故主资产"))
def _locate_incident_asset(world, api, ns: str, table: str) -> None:
    world.urns["incident_urn"] = _dataset_urn(world, api, ns, table)


@given("我已就事故主资产开出一个 HIGH 级 e2e 事故并记录其编号")
def _open_incident_for_asset(world, api) -> None:
    urn = world.urns.get("incident_urn")
    assert urn, "尚未定位事故主资产"
    _open_incident(world, api, urn)


@given("我已为记录的事故关联沉淀规则并闭环解决")
def _resolve_with_rule(world, api) -> None:
    _resolve(world, api,
             {"closedLoopRuleUrn": f"urn:dg:QualityRule:{world.namespace}.{_CLOSED_LOOP_RULE_SUFFIX}"})


@when("我以 event_log 为主资产开一个 HIGH 级 e2e 事故")
def _open_incident_when(world, api) -> None:
    _open_incident(world, api, _up_urn(world))


@when("我在未关联沉淀规则的情况下解决该事故")
def _resolve_blank(world, api) -> None:
    _resolve(world, api, {})


@when(parsers.parse("我关联沉淀规则 {suffix} 后解决该事故"))
def _resolve_with_named_rule(world, api, suffix: str) -> None:
    _resolve(world, api, {"closedLoopRuleUrn": f"urn:dg:QualityRule:{world.namespace}.{suffix}"})


@when("我读取该事故的详情与时间线")
def _read_incident(world, api) -> None:
    _get(world, api, f"/api/v1/observability/incidents/{_incident_id(world)}")


@then("解决结果应记录关联的沉淀规则 URN")
def _assert_closed_loop_rule(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert body.get("status") == "RESOLVED", f"事故未进入已解决状态：{_brief(world.last_body)}"
    rule_urn = str(body.get("closedLoopRuleUrn") or "")
    assert rule_urn.endswith(_CLOSED_LOOP_RULE_SUFFIX), (
        f"解决结果未记录沉淀规则 URN：{_brief(world.last_body)}")


@then("事故时间线应包含 DETECTED 与 RESOLVED")
def _assert_timeline(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    timeline = body.get("timeline") or []
    event_types = {event.get("event_type") for event in timeline if isinstance(event, dict)}
    assert {"DETECTED", "RESOLVED"} <= event_types, (
        f"事故时间线未保留全过程（检测 → 解决 → 沉淀规则），实际 {sorted(event_types)}")


@then("运营总览应给出 MTTR 统计与未沉淀规则的事故清单")
def _assert_overview_observables(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    mttr = body.get("mttrHours")
    without_rule = body.get("withoutRule")
    anomaly = body.get("anomaly")
    assert mttr is not None, f"运营总览未暴露 MTTR：{_brief(world.last_body)}"
    assert isinstance(without_rule, list), (
        f"「解决了但没沉淀规则」的事故清单应为列表：{_brief(world.last_body)}")
    assert anomaly, "运营总览未附带异常检测概览（误报治理缺少观察点）"
    if isinstance(mttr, list) and mttr:
        assert mttr[0].get("resolved_count", 0) >= 1, (
            f"已有已解决事故却未统计 MTTR：{mttr[0]}")
        assert mttr[0].get("avg_hours") is not None, f"MTTR 平均时长为空：{mttr[0]}"