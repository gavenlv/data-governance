"""存活与认证授权（组 1、2）的领域步骤。"""

from __future__ import annotations

from pytest_bdd import parsers, when

from support import config
from support.client import quote_urn


@when(parsers.parse("我以只读令牌尝试写入资产 {name} 的描述"))
def _reader_writes_description(world, api, name: str) -> None:
    urn = f"urn:dg:Dataset:{name}"
    path = f"/api/v1/assets/{quote_urn(urn)}/aspects/descriptions?createEntityIfMissing=false"
    status, payload = api.call("POST", path, {"data": {"text": "越权写入尝试"}},
                               token=config.READER_TOKEN)
    world.token = config.READER_TOKEN
    world.last_status = status
    world.last_body = payload
    world.calls.append(("POST", path, status))