"""令牌解析与身份认证。

支持三种模式（DG_AUTH_MODE）：
  static    静态令牌表（开发/内网；默认为本机开发预置两个令牌）
  jwt       校验 Bearer JWT（HS256 或由算法决定；角色取自可配置 claim）
  disabled  不做认证（**仅限本机开发**，启动会打印警告）

生产必须用 jwt（对接企业 OIDC/SSO）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from dg.auth.principal import ANONYMOUS, Principal, normalize_roles
from dg.config import Settings, settings as default_settings


class AuthError(Exception):
    """认证/授权失败。"""

    status_code = 401
    detail = "unauthenticated"


class Unauthenticated(AuthError):
    status_code = 401
    detail = "缺少或无效的凭证"


class Forbidden(AuthError):
    status_code = 403
    detail = "无权执行该操作"


# 本机开发预置令牌（生产环境必须改掉；DG_AUTH_MODE=disabled 时应删除）
DEV_TOKENS: dict[str, dict[str, Any]] = {
    "dev-admin-token": {
        "id": "admin@local",
        "name": "本地管理员",
        "roles": ["ADMIN"],
        "teams": ["platform"],
    },
    "dev-steward-token": {
        "id": "steward@local",
        "name": "本地数据管家",
        "roles": ["STEWARD"],
        "teams": ["governance"],
    },
    "dev-reader-token": {
        "id": "reader@local",
        "name": "本地只读用户",
        "roles": ["READER"],
        "teams": ["analytics"],
    },
}


@dataclass
class Authenticator:
    settings: Settings = default_settings
    _tokens: dict[str, Principal] | None = None

    # ------------------------------------------------------------------ 初始化

    def __post_init__(self) -> None:
        self._tokens = self._load_static_tokens()

    def _load_static_tokens(self) -> dict[str, Principal]:
        raw = (self.settings.auth_tokens_json or "").strip()
        if raw:
            try:
                entries = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"DG_AUTH_TOKENS 不是合法 JSON：{exc}") from exc
        else:
            entries = [
                {"token": token, **payload} for token, payload in DEV_TOKENS.items()
            ]

        table: dict[str, Principal] = {}
        for entry in entries:
            token = entry.get("token")
            if not token:
                continue
            table[token] = Principal(
                id=entry.get("id") or entry.get("sub") or "unknown",
                name=entry.get("name", ""),
                roles=normalize_roles(entry.get("roles")),
                teams=frozenset(entry.get("teams") or ()),
                tenant=entry.get("tenant", self.settings.default_tenant),
                auth_method="static",
            )
        return table

    # -------------------------------------------------------------------- 认证

    def authenticate(self, authorization: str | None) -> Principal:
        """从 Authorization 头解析主体。失败抛 Unauthenticated。"""
        if self.settings.auth_disabled:
            return Principal(
                id="dev@local",
                name="开发模式（未认证）",
                roles=normalize_roles(["ADMIN"]),
                auth_method="disabled",
            )

        if not authorization or not authorization.lower().startswith("bearer "):
            raise Unauthenticated()
        token = authorization.split(" ", 1)[1].strip()
        if not token:
            raise Unauthenticated()

        if self.settings.auth_mode == "jwt":
            return self._authenticate_jwt(token)

        principal = (self._tokens or {}).get(token)
        if principal is None:
            raise Unauthenticated()
        return principal

    def _authenticate_jwt(self, token: str) -> Principal:
        try:
            import jwt  # PyJWT
        except ImportError as exc:  # pragma: no cover
            raise SystemExit("DG_AUTH_MODE=jwt 需要 PyJWT：pip install pyjwt") from exc

        algorithms = self._algorithms()
        verify_kwargs: dict[str, Any] = {
            "algorithms": algorithms,
            "audience": self.settings.jwt_audience or None,
            "issuer": self.settings.jwt_issuer or None,
            "options": {
                "verify_aud": bool(self.settings.jwt_audience),
                "verify_iss": bool(self.settings.jwt_issuer),
            },
            "leeway": self.settings.jwt_leeway,
        }

        try:
            if self.settings.jwt_jwks_url:
                # OIDC 模式：按 kid 从 JWKS 取公钥（PyJWKClient 自带缓存与轮换）
                signing_key = self._jwks_client().get_signing_key_from_jwt(token)
                claims = jwt.decode(token, signing_key.key, **verify_kwargs)
            else:
                if not self.settings.jwt_secret:
                    raise SystemExit(
                        "DG_AUTH_MODE=jwt 需要 DG_JWT_SECRET（共享密钥）"
                        "或 DG_JWT_JWKS_URL（OIDC）"
                    )
                claims = jwt.decode(token, self.settings.jwt_secret, **verify_kwargs)
        except SystemExit:
            raise
        except Exception as exc:  # 令牌无效 / JWKS 不可用 → 认证失败（不是 500）
            raise Unauthenticated() from exc

        return self._principal_from_claims(claims)

    def _algorithms(self) -> list[str]:
        """允许的算法清单。

        JWKS 模式下默认只允许非对称算法（RS*/ES*）——避免"算法混淆"攻击
        （攻击者用 HS256 + 公钥当密钥伪造令牌）。
        """
        configured = [a.strip() for a in (self.settings.jwt_algorithms or "").split(",") if a.strip()]
        if configured:
            return configured
        if self.settings.jwt_jwks_url:
            return ["RS256", "RS384", "RS512", "ES256", "ES384", "ES512"]
        return [self.settings.jwt_algorithm]

    def _jwks_client(self):
        """延迟构造 PyJWKClient（带缓存），避免启动即发起网络请求。"""
        if getattr(self, "_jwk_client", None) is None:
            import jwt

            self._jwk_client = jwt.PyJWKClient(
                self.settings.jwt_jwks_url,
                cache_keys=True,
                lifespan=self.settings.jwt_jwks_cache_seconds,
                timeout=self.settings.jwt_jwks_timeout,
            )
        return self._jwk_client

    def _principal_from_claims(self, claims: dict) -> Principal:
        roles = claims.get(self.settings.jwt_roles_claim) or claims.get("role") or []
        if isinstance(roles, str):
            roles = [roles]
        teams = claims.get("teams") or claims.get("groups") or []
        if isinstance(teams, str):
            teams = [teams]

        return Principal(
            id=str(claims.get("sub") or claims.get("email") or "unknown"),
            name=str(claims.get("name") or claims.get("preferred_username") or ""),
            roles=normalize_roles(roles),
            teams=frozenset(str(t) for t in teams),
            tenant=str(claims.get("tenant") or self.settings.default_tenant),
            auth_method="jwt",
        )


_authenticator: Authenticator | None = None


def get_authenticator() -> Authenticator:
    global _authenticator
    if _authenticator is None:
        _authenticator = Authenticator()
    return _authenticator


def reset_authenticator() -> None:
    """测试用：清除缓存以重新读取配置。"""
    global _authenticator
    _authenticator = None


__all__ = [
    "ANONYMOUS",
    "AuthError",
    "Authenticator",
    "DEV_TOKENS",
    "Forbidden",
    "Unauthenticated",
    "get_authenticator",
    "reset_authenticator",
]
