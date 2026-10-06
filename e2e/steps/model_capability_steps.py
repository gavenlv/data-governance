"""模型与能力清单（组 3、4）的领域步骤。

4b 需要把「能力 id → 状态」的映射摊平后逐项断言：这是能力清单作为
「单点事实源」的核心约束，不能只看 summary 的数字。
"""

from __future__ import annotations

from pytest_bdd import parsers, then


@then(parsers.parse("以下能力状态应均为 {expected}：{ids}"))
def _capabilities_implemented(world, expected: str, ids: str) -> None:
    caps = world.last_body if isinstance(world.last_body, dict) else {}
    domains = caps.get("domains", {})
    statuses = {
        item["id"]: item["status"]
        for items in domains.values()
        for item in items
    }
    missing = [cid for cid in ids.split("/") if statuses.get(cid) != expected]
    assert not missing, (
        f"以下能力状态不是 {expected}：{[(cid, statuses.get(cid)) for cid in missing]}"
    )