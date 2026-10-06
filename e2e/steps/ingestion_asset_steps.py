"""采集与资产（组 5、6）的领域步骤。

场景之间不共享状态：需要资产存在时，先在「假如」里真实采集一次
（幂等 upsert），再断言。这样每个场景都能独立重跑。
"""

from __future__ import annotations

from pytest_bdd import given, parsers, when

from support import config
from support.client import quote_urn

_SOURCE = {
    "jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
    "username": "postgres",
    "password": "root",
    "maxDeleteRatio": 0.3,
}


@given(parsers.parse("我已采集 PostgreSQL 命名空间 {ns} 的 public schema"))
def _collect_postgres(world, api, ns: str) -> None:
    body = {**_SOURCE, "namespace": ns, "schemas": ["public"]}
    status, payload = api.call("POST", "/api/v1/collect/postgres", body,
                               token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/collect/postgres", status))
    assert status == 200, f"采集失败：HTTP {status} {payload}"
    world.remember("collect", payload)


@when(parsers.parse("我获取 {ns} 命名空间下首个资产的详情"))
def _first_asset_detail(world, api, ns: str) -> None:
    prefix = f"urn:dg:Dataset:{ns}"
    status, listing = api.call("GET", f"/api/v1/assets?prefix={prefix}&limit=5",
                               token=config.ADMIN_TOKEN)
    world.calls.append(("GET", f"/api/v1/assets?prefix={prefix}&limit=5", status))
    assert status == 200 and isinstance(listing, dict), f"资产列表不可读：HTTP {status}"
    assets = listing.get("assets") or []
    assert assets, "资产列表为空，无法获取详情（采集是否成功？）"
    urn = assets[0]["urn"]
    path = f"/api/v1/assets/{quote_urn(urn)}"
    status, payload = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = payload