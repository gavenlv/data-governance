"""数据剖析与质量规则（组 13、14）的领域步骤。

设计：书写的断言与 tools/java_e2e_verify.py:567-667 保持一致（不强不弱）。
目标数据集 URN 在「假如」里现查现用：先列出 java_e2e 命名空间资产，取以
``public.event_log`` 结尾者；找不到就显式失败（绝不转 skip）。
"""

from __future__ import annotations

from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

_SOURCE = {
    "jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
    "username": "postgres",
    "password": "root",
}
_E2E_DSN = "postgresql://postgres:root@localhost:25011/dg"

# 与验证脚本 Batch 2 的四条 YAML 检查一一对应（顺序决定 e2e_rules#N 编号）
_YAML_CHECKS = [
    {"type": "notNull", "column": "event_type"},
    {"type": "uniqueness", "columns": ["seq"], "threshold": 0.999},
    {"type": "rowCount", "threshold": 100000000},
    {"type": "rowCountChange", "maxDropPct": 50, "window": "7d"},
]


def _brief(body) -> str:
    import json

    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _target_urn(world) -> str:
    urn = world.recall("target_urn") or world.urns.get("target")
    assert urn, "未定位到目标数据集 URN（前置「我已定位…可剖析的 event_log 数据集」未执行）"
    return urn


def _post(world, api, path: str, body: dict):
    status, payload = api.call("POST", path, body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _register_yaml_rules(world, api, dataset_urn: str):
    body = {
        "frontend": "yaml",
        "datasetUrn": dataset_urn,
        "namespace": "java_e2e",
        "dsn": _E2E_DSN,
        "document": {"rule": "e2e_rules", "checks": _YAML_CHECKS},
    }
    return _post(world, api, "/api/v1/quality/rules", body)


# ============================================================ 前置：定位目标数据集


@given(parsers.parse("我已定位 {ns} 命名空间下可剖析的 {table} 数据集"))
def _locate_profile_target(world, api, ns: str, table: str) -> None:
    prefix = f"urn:dg:Dataset:{ns}"
    path = f"/api/v1/assets?prefix={prefix}&limit=100"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status}"
    suffix = f"public.{table}"
    urn = next(
        (a.get("urn") for a in (listing.get("assets") or [])
         if str(a.get("urn", "")).endswith(suffix)),
        None,
    )
    assert urn, f"未找到可剖析的数据集（URN 以 {suffix} 结尾）；采集是否成功？{_brief(listing)}"
    world.remember("target_urn", urn)
    world.urns["target"] = urn


# ==================================================================== 13) 剖析


@when("我对目标数据集执行全量实算剖析")
def _profile_full(world, api) -> None:
    body = {**_SOURCE, "datasetUrns": [_target_urn(world)], "samplePercent": 100,
            "includeTopK": True}
    _post(world, api, "/api/v1/quality/profile", body)


@when("我对目标数据集执行 10% 采样剖析")
def _profile_sample(world, api) -> None:
    body = {**_SOURCE, "datasetUrns": [_target_urn(world)], "samplePercent": 10,
            "includeTopK": False}
    _post(world, api, "/api/v1/quality/profile", body)


@then("剖析结果中每一列都应带统计指标")
def _assert_columns_have_metrics(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    datasets = body.get("datasets")
    assert isinstance(datasets, list) and datasets, f"剖析响应缺少 datasets：{_brief(world.last_body)}"
    columns = datasets[0].get("columns") or []
    assert columns, f"剖析结果没有任何列统计：{_brief(datasets[0])}"
    missing = [column.get("name") for column in columns if "metrics" not in column]
    assert not missing, f"以下列缺少 metrics 统计指标：{missing}"


# ==================================================================== 14) 规则


@when("我以 YAML 前端编译四条内置检查规则")
def _compile_yaml_rules(world, api) -> None:
    _post(world, api, "/api/v1/quality/rules/compile", {
        "frontend": "yaml", "datasetUrn": _target_urn(world),
        "document": {"rule": "e2e_rules", "checks": _YAML_CHECKS}})


@then("编译预览应恰好产生 4 条规则且每条都带源库 SQL")
def _assert_compile_four(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    rules = body.get("rules")
    assert isinstance(rules, list) and len(rules) == 4, (
        f"期望编译出 4 条规则，实际 {len(rules) if isinstance(rules, list) else rules}："
        f"{_brief(world.last_body)}"
    )
    missing = [rule.get("ruleId") for rule in rules if not rule.get("sql")]
    assert not missing, f"以下规则的编译产物缺少源库 SQL：{missing}"


@when("我分别编译 SQL 断言前端与 dbt tests 前端规则")
def _compile_other_frontends(world, api) -> None:
    urn = _target_urn(world)
    _, sql_preview = _post(world, api, "/api/v1/quality/rules/compile", {
        "frontend": "sql_assertion", "ruleId": "e2e_sql", "datasetUrn": urn,
        "sql": "SELECT count(*) FROM event_log WHERE urn IS NULL", "expect": 0,
        "severity": "HIGH"})
    world.remember("sql_preview", sql_preview)
    _, dbt_preview = _post(world, api, "/api/v1/quality/rules/compile", {
        "frontend": "dbt_test", "modelName": "event_log", "datasetUrn": urn,
        "tests": [{"unique": "seq"}, {"not_null": "urn"}]})
    world.remember("dbt_preview", dbt_preview)


@then("两种前端的编译产物非空且各自标注来源前端")
def _assert_other_frontends(world) -> None:
    sql_preview = world.recall("sql_preview") or {}
    dbt_preview = world.recall("dbt_preview") or {}
    sql_rules = sql_preview.get("rules") or []
    dbt_rules = dbt_preview.get("rules") or []
    assert sql_rules, f"SQL 断言前端未编译出规则：{_brief(sql_preview)}"
    assert dbt_rules, f"dbt tests 前端未编译出规则：{_brief(dbt_preview)}"
    assert sql_rules[0].get("sourceFrontend") == "sql_assertion", (
        f"来源前端标注错误：{sql_rules[0].get('sourceFrontend')}")
    assert dbt_rules[0].get("sourceFrontend") == "dbt_test", (
        f"来源前端标注错误：{dbt_rules[0].get('sourceFrontend')}")


@when("我编译一个含不支持检查类型的规则文档")
def _compile_unsupported(world, api) -> None:
    _post(world, api, "/api/v1/quality/rules/compile", {
        "frontend": "yaml", "datasetUrn": _target_urn(world),
        "document": {"rule": "bad", "checks": [{"type": "vibes"}]}})


@when("我以 YAML 前端把四条内置检查规则注册为实体")
def _register_rules_entity(world, api) -> None:
    _register_yaml_rules(world, api, _target_urn(world))


@then("注册结果应恰好 4 条规则且口令均被遮蔽")
def _assert_registered_masked(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    registered = body.get("registered") or []
    assert len(registered) == 4, (
        f"期望注册 4 条规则，实际 {len(registered)}；拒绝 {body.get('rejected')}")
    unmasked = [item.get("ruleId") for item in registered if "***" not in (item.get("dsn") or "")]
    assert not unmasked, f"以下规则的 DSN 未遮蔽口令：{unmasked}"


@given("我已把四条 e2e_rules 规则注册到 java_e2e 命名空间并记录其 URN")
def _register_rules_for_exec(world, api) -> None:
    status, payload = _register_yaml_rules(world, api, _target_urn(world))
    assert status == 200, f"规则注册失败：HTTP {status} {_brief(payload)}"
    registered = payload.get("registered") or []
    assert len(registered) == 4, (
        f"期望注册 4 条规则，实际 {len(registered)}；拒绝 {payload.get('rejected')}")
    for item in registered:
        world.urns[item["ruleId"]] = item["urn"]


@when("我依次执行注册出的三条代表性规则并汇总状态")
def _run_three_rules(world, api) -> None:
    statuses: dict[str, object] = {}
    for rule_id in ("e2e_rules#1", "e2e_rules#3", "e2e_rules#4"):
        urn = world.urns.get(rule_id)
        if urn is None:
            continue
        path = f"/api/v1/quality/rules/{quote_urn(urn)}/run"
        status, run = api.call("POST", path, token=config.ADMIN_TOKEN)
        world.calls.append(("POST", path, status))
        statuses[rule_id] = run.get("status") if isinstance(run, dict) else None
    world.remember("rule_statuses", statuses)


@then("规则执行留痕应可区分 PASS 与 FAIL 与 SKIPPED")
def _assert_three_statuses(world) -> None:
    statuses = world.recall("rule_statuses") or {}
    assert statuses.get("e2e_rules#1") == "PASS", (
        f"非空检查应为 PASS，实际 {statuses.get('e2e_rules#1')}：{statuses}")
    assert statuses.get("e2e_rules#3") == "FAIL", (
        f"巨阈值行数检查应为 FAIL，实际 {statuses.get('e2e_rules#3')}：{statuses}")
    assert statuses.get("e2e_rules#4") == "SKIPPED", (
        f"行数波动（窗口内无基线）应为 SKIPPED，实际 {statuses.get('e2e_rules#4')}：{statuses}")


@when("我注册并执行一条 DSN 引用未设置环境变量的规则")
def _run_env_rule(world, api) -> None:
    reg_status, registered = _post(world, api, "/api/v1/quality/rules", {
        "frontend": "yaml", "datasetUrn": _target_urn(world), "namespace": "java_e2e",
        "dsn": "env:DG_E2E_DEFINITELY_NOT_SET",
        "document": {"rule": "e2e_env_rule", "checks": [{"type": "notNull", "column": "event_type"}]}})
    env_urn = ((registered.get("registered") or [{}])[0].get("urn")
               if isinstance(registered, dict) else None)
    assert env_urn, f"env 规则未注册成功：HTTP {reg_status} {_brief(registered)}"
    path = f"/api/v1/quality/rules/{quote_urn(env_urn)}/run"
    status, env_run = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = env_run
    world.remember("env_rule_run", env_run)


@then("该规则的执行结果应为 ERROR 且错误指出环境变量未设置")
def _assert_env_error(world) -> None:
    env_run = world.recall("env_rule_run")
    assert isinstance(env_run, dict), f"环境变量规则未返回结构化结果：{_brief(env_run)}"
    assert env_run.get("status") == "ERROR", (
        f"期望 ERROR（不得降级连别的库），实际 {env_run.get('status')}：{_brief(env_run)}")
    error = str(env_run.get("error") or "")
    assert "未设置" in error, f"错误未指出环境变量未设置（不静默连错库）：{error}"