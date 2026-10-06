"""数据契约（组 15）与违约豁免（组 17）的领域步骤。

场景之间不共享状态：契约 id 带运行时间戳与随机后缀，并在每个场景的「假如」里真实注册，
因此在**已有数据的库上重复执行**也会从「首次注册」开始。
（用固定 id 时第二次运行会得到「相对已发布版本的变更」，那不是契约逻辑出错。）
"""

from __future__ import annotations

import secrets
import time

from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

_SOURCE = {
    "jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
    "username": "postgres",
    "password": "root",
    "maxDeleteRatio": 0.3,
}

# 与 tools/java_e2e_verify.py:673-684 的契约文档保持一致（ODCS v3.0.2）。
_BASELINE_FIELDS = [
    {"name": "seq", "type": "bigint", "required": True},
    {"name": "event_type", "type": "text", "required": True},
    {"name": "urn", "type": "text", "required": True},
    {"name": "created_at", "type": "timestamp with time zone", "required": True},
]


def new_contract_id(tag: str) -> str:
    """契约 id：时间戳 + 随机后缀，保证「首次注册」语义可重复执行。"""
    return f"e2e_contract_{time.strftime('%H%M%S')}_{tag}_{secrets.token_hex(2)}"


def build_contract_document(contract_id: str, target_urn: str, version: str = "1.0.0",
                            schema: list | None = None, quality: list | None = None) -> dict:
    return {
        "apiVersion": "v3.0.2",
        "kind": "DataContract",
        "id": contract_id,
        "version": version,
        "status": "ACTIVE",
        "dataset": target_urn,
        "primaryKey": ["seq"],
        "schema": schema if schema is not None else [dict(field) for field in _BASELINE_FIELDS],
        "quality": quality if quality is not None else [{"type": "notNull", "column": "event_type"}],
        "sla": {"freshness": "PT24H"},
        "connection": {"dsn": "env:DG_E2E_DSN"},
    }


def register_contract(api, namespace: str, document: dict, allow_breaking: bool = False,
                      justification: str | None = None):
    body: dict = {"document": document, "namespace": namespace}
    if allow_breaking:
        body["allowBreaking"] = True
    if justification:
        body["breakingJustification"] = justification
    return api.call("POST", "/api/v1/contracts", body, token=config.ADMIN_TOKEN)


def _find_event_log(api, namespace: str) -> str | None:
    prefix = f"urn:dg:Dataset:{namespace}"
    status, listing = api.call("GET", f"/api/v1/assets?prefix={prefix}&limit=100",
                               token=config.ADMIN_TOKEN)
    rows = listing.get("assets", []) if isinstance(listing, dict) else []
    own = f"{prefix}."
    for row in rows:
        urn = str(row.get("urn", ""))
        # 只认本命名空间的数据集（prefix 也会命中 java_e2e_sched）
        if urn.startswith(own) and urn.endswith("public.event_log"):
            return urn
    return None


def ensure_event_log_target(api, namespace: str) -> str:
    """定位 {namespace} 下的 event_log 数据集；缺失则先采集一次，仍无则断言失败。"""
    urn = _find_event_log(api, namespace)
    if urn is None:
        api.call("POST", "/api/v1/collect/postgres",
                 {**_SOURCE, "namespace": namespace, "schemas": ["public"]},
                 token=config.ADMIN_TOKEN)
        urn = _find_event_log(api, namespace)
    assert urn is not None, (
        f"未找到 {namespace} 命名空间下以 public.event_log 结尾的数据集 URN"
        "（已尝试采集一次），契约检查无法在缺少目标数据集时进行"
    )
    return urn


def call_admin(world, api, method: str, path: str, body: dict | None = None):
    status, payload = api.call(method, path, body, token=config.ADMIN_TOKEN)
    world.token = config.ADMIN_TOKEN
    world.last_status = status
    world.last_body = payload
    world.calls.append((method, path, status))
    return status, payload


def _broken_document(world, version: str) -> dict:
    """在基线上删掉 urn 列（破坏性变更），改版本号。"""
    document = dict(world.recall("contract_doc"))
    document["version"] = version
    document["schema"] = [field for field in document["schema"] if field["name"] != "urn"]
    return document


# ------------------------------------------------------------------ 组 15 契约

@given("我已准备了一份全新的 ODCS 契约文档（目标为 java_e2e.event_log）")
def _prepare_fresh_document(world, api) -> None:
    target = ensure_event_log_target(api, world.namespace)
    contract_id = new_contract_id("15a")
    document = build_contract_document(contract_id, target, "1.0.0")
    world.remember("contract_id", contract_id)
    world.remember("target_urn", target)
    world.remember("contract_doc", document)


@given("我已登记好 java_e2e.event_log 数据集的契约基线 1.0.0")
def _register_baseline(world, api) -> None:
    target = ensure_event_log_target(api, world.namespace)
    contract_id = new_contract_id("base")
    document = build_contract_document(contract_id, target, "1.0.0")
    status, payload = register_contract(api, world.namespace, document)
    world.calls.append(("POST", "/api/v1/contracts", status))
    assert status == 200 and payload.get("registered") is True, (
        f"登记契约基线失败：HTTP {status} {payload}"
    )
    world.remember("contract_id", contract_id)
    world.remember("contract_urn", payload.get("contract"))
    world.remember("target_urn", target)
    world.remember("contract_doc", document)


@given("我已登记好 java_e2e.event_log 数据集的契约并升级到破坏性版本 2.0.0")
def _register_baseline_then_breaking(world, api) -> None:
    target = ensure_event_log_target(api, world.namespace)
    contract_id = new_contract_id("ver")
    baseline = build_contract_document(contract_id, target, "1.0.0")
    status, payload = register_contract(api, world.namespace, baseline)
    world.calls.append(("POST", "/api/v1/contracts", status))
    assert status == 200 and payload.get("registered") is True, (
        f"登记契约基线失败：HTTP {status} {payload}"
    )
    urn = payload.get("contract")
    broken = build_contract_document(
        contract_id, target, "2.0.0",
        schema=[field for field in baseline["schema"] if field["name"] != "urn"])
    status2, payload2 = register_contract(api, world.namespace, broken,
                                          allow_breaking=True,
                                          justification="e2e：urn 列迁到新表")
    world.calls.append(("POST", "/api/v1/contracts", status2))
    assert status2 == 200 and payload2.get("registered") is True, (
        f"显式授权的破坏性变更应可发布：HTTP {status2} {payload2}"
    )
    world.remember("contract_id", contract_id)
    world.remember("contract_urn", urn)
    world.remember("target_urn", target)
    world.remember("contract_doc", baseline)


@when("我以管理员令牌提交该契约文档注册")
def _submit_document(world, api) -> None:
    document = world.recall("contract_doc")
    call_admin(world, api, "POST", "/api/v1/contracts",
               {"document": document, "namespace": world.namespace})


@when(parsers.parse("我以管理员令牌提交该契约的破坏性变更版本 {version}"))
def _submit_breaking_version(world, api, version: str) -> None:
    call_admin(world, api, "POST", "/api/v1/contracts",
               {"document": _broken_document(world, version), "namespace": world.namespace})


@when(parsers.parse("我以管理员令牌以显式授权并附留痕理由提交破坏性变更版本 {version}"))
def _submit_breaking_allowed(world, api, version: str) -> None:
    call_admin(world, api, "POST", "/api/v1/contracts",
               {"document": _broken_document(world, version), "namespace": world.namespace,
                "allowBreaking": True, "breakingJustification": "e2e：urn 列迁到新表"})


@when("我以管理员令牌查询该契约的版本历史")
def _query_versions(world, api) -> None:
    urn = quote_urn(world.recall("contract_urn"))
    call_admin(world, api, "GET", f"/api/v1/contracts/{urn}/versions")


@when("我以管理员令牌查询该契约 2.0.0 与 1.0.0 之间的兼容性 diff")
def _query_version_diff(world, api) -> None:
    urn = quote_urn(world.recall("contract_urn"))
    call_admin(world, api, "GET", f"/api/v1/contracts/{urn}/diff?from=2&to=1")


@when("我以管理员令牌对该契约执行运行时校验")
def _validate_runtime(world, api) -> None:
    urn = quote_urn(world.recall("contract_urn"))
    call_admin(world, api, "POST", f"/api/v1/contracts/{urn}/validate")


@when("我以管理员令牌查询该契约的消费者报告")
def _query_consumers(world, api) -> None:
    urn = quote_urn(world.recall("contract_urn"))
    call_admin(world, api, "GET", f"/api/v1/contracts/{urn}/consumers")


@then("兼容性 diff 中应存在 column_added 类型的变更")
def _diff_has_column_added(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    kinds = [change.get("kind") for change in (body.get("changes") or [])]
    assert "column_added" in kinds, f"变更清单中没有 column_added：{kinds}"


@then("运行时校验应给出违约事件列表")
def _validation_returns_violations(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert isinstance(body.get("violations"), list), (
        f"运行时校验的 violations 应为列表：{body}"
    )


# --------------------------------------------------------------- 组 17 豁免

@given("我已针对 java_e2e.event_log 登记了一份 schema 与实际不符的契约并触发校验产生违约")
def _create_open_violation(world, api) -> None:
    target = ensure_event_log_target(api, world.namespace)
    contract_id = new_contract_id("viol")
    schema = [dict(field) for field in _BASELINE_FIELDS]
    # 声明一个实际不存在的列 → 运行时校验必然产出 missing_column 违约
    schema.append({"name": "nonexistent_probe_col", "type": "text", "required": True})
    document = build_contract_document(contract_id, target, "1.0.0", schema=schema)
    status, payload = register_contract(api, world.namespace, document)
    world.calls.append(("POST", "/api/v1/contracts", status))
    assert status == 200 and payload.get("registered") is True, (
        f"登记不匹配契约失败：HTTP {status} {payload}"
    )
    urn = payload.get("contract")
    validate_path = f"/api/v1/contracts/{quote_urn(urn)}/validate"
    status2, validation = api.call("POST", validate_path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", validate_path, status2))
    assert status2 == 200 and (validation.get("violationCount") or 0) >= 1, (
        f"运行时校验未产出违约事件：HTTP {status2} {validation}"
    )
    status3, listing = api.call("GET", "/api/v1/contracts/violations?status=OPEN&limit=500",
                                token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/contracts/violations?status=OPEN&limit=500", status3))
    rows = listing.get("violations", []) if isinstance(listing, dict) else []
    mine = [row for row in rows if row.get("contract_urn") == urn]
    assert mine, f"未在 OPEN 违约列表中找到本契约的违约事件：{urn}"
    world.remember("violation_id", mine[0]["id"])
    world.remember("contract_urn", urn)


@when("我以管理员令牌尝试不带到期时间豁免该违约事件")
def _exempt_without_until(world, api) -> None:
    violation_id = world.recall("violation_id")
    call_admin(world, api, "POST", f"/api/v1/contracts/violations/{violation_id}/exempt",
               {"reason": "e2e 尝试永久豁免"})


@when("我以管理员令牌带到期时间豁免该违约事件")
def _exempt_with_until(world, api) -> None:
    violation_id = world.recall("violation_id")
    call_admin(world, api, "POST", f"/api/v1/contracts/violations/{violation_id}/exempt",
               {"until": "2030-01-01T00:00:00Z", "reason": "e2e 临时豁免"})