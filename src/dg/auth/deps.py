"""FastAPI 认证/授权依赖。

用法：
    @app.get("/x")
    def x(principal: Principal = Depends(get_principal)): ...

    @app.post("/y")
    def y(principal: Principal = Depends(require("asset:write"))): ...
"""

from __future__ import annotations

from fastapi import Depends, Header, HTTPException

from dg.auth.policy import Policy
from dg.auth.principal import Principal
from dg.auth.tokens import Forbidden, Unauthenticated, get_authenticator


def get_principal(authorization: str | None = Header(default=None)) -> Principal:
    try:
        return get_authenticator().authenticate(authorization)
    except Unauthenticated as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=str(exc.detail) if hasattr(exc, "detail") else str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


def require(permission: str):
    """依赖工厂：要求特定权限点。"""

    def _dep(principal: Principal = Depends(get_principal)) -> Principal:
        try:
            Policy.authorize(principal, permission)
        except Forbidden as exc:
            raise HTTPException(status_code=403, detail=str(exc)) from exc
        return principal

    return _dep


__all__ = ["get_principal", "require"]
