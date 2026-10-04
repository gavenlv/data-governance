"""策略判定（docs/09 §9.7）。

**本模块是授权判定的唯一入口** —— 搜索、详情、写入、导出、AI 上下文裁剪都必须
调用同一套判定（`09` §9.3 的安全底线：任何一处用不同判定就是越权漏洞）。

判定分为两级：
  RBAC  权限点（asset:read / asset:write / ...）
  ABAC  分级可见性（L1–L4），用于资产过滤与访问拒绝
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from dg.auth.principal import (
    CLASSIFICATION_ORDER,
    DEFAULT_CLASSIFICATION,
    ROLE_MAX_CLASSIFICATION,
    ROLE_PERMISSIONS,
    Principal,
)
from dg.auth.tokens import Forbidden


@dataclass(frozen=True)
class Policy:
    """无状态策略判定器。"""

    # ------------------------------------------------------------- 权限点

    @staticmethod
    def permissions(principal: Principal) -> frozenset[str]:
        perms: set[str] = set()
        for role in principal.roles:
            perms.update(ROLE_PERMISSIONS.get(role, frozenset()))
        return frozenset(perms)

    @classmethod
    def can(cls, principal: Principal, permission: str) -> bool:
        perms = cls.permissions(principal)
        return "*" in perms or permission in perms

    @classmethod
    def authorize(cls, principal: Principal, permission: str) -> None:
        """权限点不足时抛 Forbidden。"""
        if not cls.can(principal, permission):
            raise Forbidden(
                f"缺少权限 {permission}（当前角色：{sorted(principal.roles) or '无'}）"
            )

    # --------------------------------------------------------------- 分级

    @staticmethod
    def max_visible_level(principal: Principal) -> str:
        """该主体可见的最高分级。取所有角色中最高者。"""
        levels = [
            ROLE_MAX_CLASSIFICATION.get(role, "L2")
            for role in principal.roles
        ]
        if not levels:
            return "L1"  # 无角色 → 只能看公开级
        return max(levels, key=lambda lv: CLASSIFICATION_ORDER.index(lv))

    @classmethod
    def visible_levels(cls, principal: Principal) -> list[str]:
        """可见分级列表（含默认级）。

        **搜索结果过滤与详情访问判定都以此为准** —— 保证两者永不冲突。
        """
        top = cls.max_visible_level(principal)
        allowed = list(CLASSIFICATION_ORDER[: CLASSIFICATION_ORDER.index(top) + 1])
        if DEFAULT_CLASSIFICATION not in allowed:
            allowed.append(DEFAULT_CLASSIFICATION)
        return allowed

    @classmethod
    def can_see_classification(cls, principal: Principal, classification: str | None) -> bool:
        level = (classification or DEFAULT_CLASSIFICATION).upper()
        return level in cls.visible_levels(principal)

    @classmethod
    def ensure_asset_visible(cls, principal: Principal, classification: str | None) -> None:
        """资产级可见性判定。不可见时抛 Forbidden。"""
        if not cls.can_see_classification(principal, classification):
            raise Forbidden(
                f"资产分级 {(classification or DEFAULT_CLASSIFICATION)} 超出你的可见范围"
                f"（最高可见 {cls.max_visible_level(principal)}）"
            )

    # ------------------------------------------------- 搜索可见性 SQL 片段

    @classmethod
    def search_visibility_filter(
        cls, principal: Principal, column: str = "classification"
    ) -> tuple[str, dict[str, Any]]:
        """返回可直接注入搜索查询的 WHERE 片段与参数。

        必须**前置过滤**（而不是取回结果再筛）—— 否则会泄露总数与分面统计
        （`09` §9.3）。
        """
        levels = cls.visible_levels(principal)
        clause = f"COALESCE({column}, '{DEFAULT_CLASSIFICATION}') = ANY(:visible_levels)"
        return clause, {"visible_levels": levels}


DEFAULT_POLICY = Policy()


__all__ = ["DEFAULT_POLICY", "Policy"]
