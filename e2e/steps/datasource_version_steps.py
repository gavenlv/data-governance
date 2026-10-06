"""数据源管理（组 24）与资产版本管理（组 25）的领域步骤。

两条纪律由测试钉死：
  1. **凭据永不回显**：接口只给 hasCredentials 与已脱敏的 endpoint；
     保存连接必须走加密（未配 DG_SECRET_KEY 时宁可 502，明确跳过而不是伪造通过）。
  2. **版本链只增不减**：回滚 = 追加新版本，历史一个都不少。

场景之间不共享状态：需要资产/数据源时先在「假如」里真实采集/保存（幂等），再断言。
"""

from __future__ import annotations

import pytest
from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

# 与本套件其它采集场景一致的源库（既是控制面的库，也是被采集的源）
_DSN = "postgresql://postgres:root@localhost:25011/dg"


def _skip_if_no_secret_key(status: int, payload) -> None:
    """未配置 DG_SECRET_KEY 时保存会被拒绝（502）——这是设计行为，不是缺陷。

    这时**跳过**而不是断言通过：让"没测到"与"测过且正确"在报告里分得清。
    """
    if status == 502 and isinstance(payload, dict) and payload.get("error") == "secret_key_not_configured":
        pytest.skip(
            "控制面未配置 DG_SECRET_KEY：保存连接被拒绝（502 secret_key_not_configured）。"
            "设置 DG_SECRET_KEY 后重启控制面再跑本组场景"
        )


# ------------------------------------------------------------------ 组 24：数据源

@given(parsers.parse("我准备一个名为 {name} 的 PostgreSQL 数据源连接"))
def _prepare_datasource(world, api, name: str) -> None:
    """幂等：先清掉同名残留，再走真实的创建路径（而不是靠 409 复用）。"""
    status, listing = api.call("GET", "/api/v1/datasources", token=config.ADMIN_TOKEN)
    world.calls.append(("GET", "/api/v1/datasources", status))
    if status == 200 and isinstance(listing, dict):
        for row in listing.get("dataSources") or []:
            if row.get("name") == name:
                api.call("DELETE", f"/api/v1/datasources/{row['id']}", token=config.ADMIN_TOKEN)

    body = {
        "name": name,
        "connector": "postgres",
        "namespace": world.namespace,
        "dsn": _DSN,
        "schemas": ["public"],
        "sampleSize": 5,
    }
    status, payload = api.call("POST", "/api/v1/datasources", body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/datasources", status))
    _skip_if_no_secret_key(status, payload)
    assert status == 200, f"保存数据源失败：HTTP {status} {payload}"

    world.last_status = status
    world.last_body = payload
    world.remember("datasource_id", payload.get("id"))
    world.remember("datasource_endpoint", payload.get("endpoint"))


@when(parsers.parse("我只更新该数据源的命名空间为 {ns} 而不提供任何凭据"))
def _update_without_credentials(world, api, ns: str) -> None:
    """只传 name/connector/namespace：留空的 dsn/username/password 必须保持原值。"""
    ds_id = world.recall("datasource_id")
    assert ds_id, "尚未保存数据源"
    body = {
        "name": world.last_body.get("name"),
        "connector": world.last_body.get("connector"),
        "namespace": ns,
    }
    path = f"/api/v1/datasources/{ds_id}"
    status, payload = api.call("PUT", path, body, token=config.ADMIN_TOKEN)
    world.calls.append(("PUT", path, status))
    world.last_status = status
    world.last_body = payload


@then("该数据源的端点应与保存时一致")
def _endpoint_unchanged(world) -> None:
    before = world.recall("datasource_endpoint")
    after = (world.last_body or {}).get("endpoint")
    assert before and after == before, (
        f"留空凭据字段后端点发生了变化：保存时 {before!r}，更新后 {after!r}"
        "（说明「留空即保持原值」没有生效）"
    )


@when("我用该数据源测试连接")
def _test_datasource(world, api) -> None:
    ds_id = world.recall("datasource_id")
    assert ds_id, "尚未保存数据源"
    path = f"/api/v1/datasources/{ds_id}/test"
    status, payload = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


@when("我用该数据源触发一次扫描")
def _scan_datasource(world, api) -> None:
    ds_id = world.recall("datasource_id")
    assert ds_id, "尚未保存数据源"
    path = f"/api/v1/datasources/{ds_id}/scan"
    status, payload = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload


# ------------------------------------------------------------------ 组 25：版本管理

def _first_dataset_urn(world, api, ns: str) -> str:
    prefix = f"urn:dg:Dataset:{ns}"
    status, listing = api.call("GET", f"/api/v1/assets?prefix={prefix}&limit=5",
                               token=config.ADMIN_TOKEN)
    world.calls.append(("GET", f"/api/v1/assets?prefix={prefix}&limit=5", status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status}"
    assets = listing.get("assets") or []
    assert assets, "资产列表为空，无法获取版本时间线（采集是否成功？）"
    return assets[0]["urn"]


@when(parsers.parse("我获取 {ns} 命名空间下首个资产的版本时间线"))
def _version_timeline(world, api, ns: str) -> None:
    urn = _first_dataset_urn(world, api, ns)
    path = f"/api/v1/assets/{quote_urn(urn)}/versions"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload
    world.remember("urn", urn)


@then("版本时间线中每个 aspect 恰有一个当前版本")
def _exactly_one_current(world) -> None:
    versions = (world.last_body or {}).get("versions") or []
    assert versions, "版本时间线为空"
    by_aspect: dict[str, int] = {}
    for row in versions:
        if row.get("is_current"):
            by_aspect[row["aspect_type"]] = by_aspect.get(row["aspect_type"], 0) + 1
    bad = {aspect: count for aspect, count in by_aspect.items() if count != 1}
    missing = sorted({row["aspect_type"] for row in versions} - set(by_aspect))
    assert not bad and not missing, (
        f"每个 aspect 应恰有一个当前版本；重复的：{bad}，没有当前版本的：{missing}"
    )


@then("版本时间线中不应夹带完整版本内容")
def _timeline_is_light(world) -> None:
    """列表刻意不带 data：时间线只回答"有哪些版本"，内容按需单取。"""
    versions = (world.last_body or {}).get("versions") or []
    offenders = [row.get("version") for row in versions if "data" in row]
    assert not offenders, f"版本时间线夹带了完整内容（data）：版本 {offenders}"


def _history_rows(world, aspect: str) -> list[dict]:
    versions = (world.last_body or {}).get("versions") or []
    return sorted(
        (row for row in versions if row.get("aspect_type") == aspect),
        key=lambda row: row["version"],
    )


@when(parsers.parse("我读取该资产 {aspect} 的任一历史版本内容"))
def _read_history_version(world, api, aspect: str) -> None:
    rows = _history_rows(world, aspect)
    historical = [row for row in rows if not row.get("is_current")]
    if not historical:
        pytest.skip(
            f"{aspect} 尚无历史版本（需要至少两次覆盖写才会产生历史）："
            "历史表只留有被覆盖过的版本"
        )
    urn = world.recall("urn")
    target = max(row["version"] for row in historical)
    path = f"/api/v1/assets/{quote_urn(urn)}/aspects/{aspect}/history/{target}"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload
    world.remember("history_version", target)


@when(parsers.parse("我把该资产的 {aspect} 回滚到上一个历史版本"))
def _rollback(world, api, aspect: str) -> None:
    rows = _history_rows(world, aspect)
    historical = [row for row in rows if not row.get("is_current")]
    if not historical:
        pytest.skip(f"{aspect} 尚无历史版本，无法验证回滚（需要至少两次覆盖写）")
    urn = world.recall("urn")
    target = max(row["version"] for row in historical)
    current = next(row["version"] for row in rows if row.get("is_current"))
    path = f"/api/v1/assets/{quote_urn(urn)}/aspects/{aspect}/rollback?version={target}"
    status, payload = api.call("POST", path, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", path, status))
    world.last_status = status
    world.last_body = payload
    world.remember("rollback_before", current)
    world.remember("rollback_target", target)


@then("回滚后的新版本应等于回滚前的版本号加一")
def _rollback_appends_version(world) -> None:
    before = world.recall("rollback_before")
    body = world.last_body or {}
    assert before is not None, "未记录回滚前的版本号"
    assert body.get("previousVersion") == before, (
        f"回滚响应的 previousVersion 应为 {before}，实际 {body.get('previousVersion')}"
    )
    assert body.get("version") == before + 1, (
        f"回滚应追加新版本 {before + 1}，实际 {body.get('version')}"
        "（回滚不是删历史，而是新版本）"
    )
    assert body.get("restoredFrom") == world.recall("rollback_target"), (
        f"restoredFrom 应为被回滚到的版本 {world.recall('rollback_target')}，"
        f"实际 {body.get('restoredFrom')}"
    )