"""身份与角色（docs/09 §9.7 的三层权限模型，v1 落地前两层）。

  ┌ 平台功能权限   RBAC    谁能调哪个 API / 执行哪个动作
  ├ 资产可见性     ABAC    按分类分级（L1–L4）过滤可见资产
  └ 数据行/列访问  ——      v1 不做（属 Phase 3 的策略下发，见 ADR-008）
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

# 角色（对齐 DataHub 的 Admin/Editor/Reader，增加数据管家 STEWARD）
ROLE_ADMIN = "ADMIN"
ROLE_EDITOR = "EDITOR"
ROLE_STEWARD = "STEWARD"
ROLE_READER = "READER"

ALL_ROLES = (ROLE_ADMIN, ROLE_EDITOR, ROLE_STEWARD, ROLE_READER)

# 权限点 → 角色映射
ROLE_PERMISSIONS: dict[str, frozenset[str]] = {
    ROLE_ADMIN: frozenset({"*"}),
    ROLE_EDITOR: frozenset(
        {
            "asset:read",
            "asset:write",
            "lineage:read",
            "lineage:write",
            "model:read",
            "collect:run",
            "index:consume",
        }
    ),
    ROLE_STEWARD: frozenset(
        {
            "asset:read",
            "asset:write",
            "lineage:read",
            "lineage:write",
            "model:read",
            "governance:write",
            "index:consume",
        }
    ),
    ROLE_READER: frozenset({"asset:read", "lineage:read", "model:read"}),
}

# 各角色可看到的最高分级（分级越高越敏感，见 docs/18 §2）
ROLE_MAX_CLASSIFICATION: dict[str, str] = {
    ROLE_ADMIN: "L4",
    ROLE_STEWARD: "L4",
    ROLE_EDITOR: "L3",
    ROLE_READER: "L3",
}

CLASSIFICATION_ORDER = ("L1", "L2", "L3", "L4")
# 未分级的资产按 L2 处理（保守默认，见 docs/18 §2）
DEFAULT_CLASSIFICATION = "L2"


@dataclass(frozen=True)
class Principal:
    """调用主体（用户或服务账号）。"""

    id: str
    name: str = ""
    roles: frozenset[str] = field(default_factory=frozenset)
    teams: frozenset[str] = field(default_factory=frozenset)
    tenant: str = "default"
    auth_method: str = "static"  # static | jwt | disabled

    @property
    def display(self) -> str:
        return self.name or self.id

    def has_role(self, *roles: str) -> bool:
        return bool(self.roles.intersection(roles))

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "roles": sorted(self.roles),
            "teams": sorted(self.teams),
            "tenant": self.tenant,
            "authMethod": self.auth_method,
        }


ANONYMOUS = Principal(id="anonymous", name="匿名", roles=frozenset(), auth_method="none")


def normalize_roles(raw: Iterable[str] | None) -> frozenset[str]:
    """未知角色一律丢弃（拒绝静默提权）。"""
    if not raw:
        return frozenset()
    return frozenset(r.strip().upper() for r in raw if r and r.strip().upper() in ALL_ROLES)


__all__ = [
    "ALL_ROLES",
    "ANONYMOUS",
    "CLASSIFICATION_ORDER",
    "DEFAULT_CLASSIFICATION",
    "Principal",
    "ROLE_ADMIN",
    "ROLE_EDITOR",
    "ROLE_MAX_CLASSIFICATION",
    "ROLE_PERMISSIONS",
    "ROLE_READER",
    "ROLE_STEWARD",
    "normalize_roles",
]
