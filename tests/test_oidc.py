"""OIDC / JWKS 认证测试（docs/09 §9.7 的生产形态）。

用**本地 JWKS HTTP 服务 + 真实 RSA 密钥**验证，不依赖任何外部 IdP：
  1. 有效 RS256 令牌 → 认证成功，角色/团队 claim 正确解析
  2. 错误签名 / 未知 kid / 过期 / audience 不匹配 → 一律 401
  3. **算法混淆攻击**（HS256 用公钥当密钥）必须被拒绝
  4. JWKS 服务不可用 → 认证失败而不是 500
  5. 认证失败在 HTTP 层是 401（不能变成 500 泄露内部错误）
"""

from __future__ import annotations

import dataclasses
import json
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
from jwt.algorithms import RSAAlgorithm

from dg.api.app import app, get_session
from dg.auth import Authenticator, Unauthenticated
from dg.config import settings
from dg.auth.principal import ROLE_EDITOR


# ---------------------------------------------------------------------------
# 本地 JWKS 服务
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def rsa_keys():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return private, private.public_key()


@pytest.fixture
def jwks_server(rsa_keys):
    _, public_key = rsa_keys
    jwk = json.loads(RSAAlgorithm.to_jwk(public_key))
    jwk.update({"kid": "test-key-1", "use": "sig", "alg": "RS256"})
    body = json.dumps({"keys": [jwk]}).encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):  # 静默
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{server.server_address[1]}/.well-known/jwks.json"
    try:
        yield url
    finally:
        server.shutdown()
        server.server_close()


def _oidc_settings(url: str, **overrides):
    base = dict(
        auth_mode="jwt",
        jwt_jwks_url=url,
        jwt_secret="",
        jwt_audience="",
        jwt_issuer="",
        jwt_jwks_cache_seconds=60,
        jwt_jwks_timeout=3,
    )
    base.update(overrides)
    return dataclasses.replace(settings, **base)


def _token(private_key, *, kid="test-key-1", alg="RS256", expires_in=300, **claims):
    payload = {
        "sub": "alice@corp",
        "name": "Alice",
        "roles": ["EDITOR"],
        "teams": ["data"],
        "exp": datetime.now(timezone.utc) + timedelta(seconds=expires_in),
        "iat": datetime.now(timezone.utc),
    }
    payload.update(claims)
    headers = {"kid": kid} if kid else {}
    return pyjwt.encode(payload, private_key, algorithm=alg, headers=headers)


# ---------------------------------------------------------------------------
# 1. 正常路径
# ---------------------------------------------------------------------------
class TestJwksHappyPath:
    def test_valid_rs256_token_authenticates(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        principal = auth.authenticate(f"Bearer {_token(private)}")

        assert principal.id == "alice@corp"
        assert principal.name == "Alice"
        assert principal.has_role(ROLE_EDITOR)
        assert principal.teams == frozenset({"data"})
        assert principal.auth_method == "jwt"

    def test_roles_may_be_string_or_scalar(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        p1 = auth.authenticate(f"Bearer {_token(private, roles='STEWARD')}")
        assert p1.has_role("STEWARD")
        p2 = auth.authenticate(f"Bearer {_token(private, roles=['ADMIN', 'READER'])}")
        assert p2.roles == frozenset({"ADMIN", "READER"})

    def test_unknown_role_in_claim_is_dropped(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        principal = auth.authenticate(f"Bearer {_token(private, roles=['SUPERUSER'])}")
        assert principal.roles == frozenset()   # 拒绝静默提权

    def test_jwks_is_cached_across_calls(self, jwks_server, rsa_keys):
        """缓存生效：第二次认证不应再次请求 JWKS（这里只验证不报错且结果一致）。"""
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        first = auth.authenticate(f"Bearer {_token(private)}")
        second = auth.authenticate(f"Bearer {_token(private)}")
        assert first.id == second.id


# ---------------------------------------------------------------------------
# 2. 拒绝路径
# ---------------------------------------------------------------------------
def _forged_hs256(secret: bytes, claims: dict, kid: str | None = "test-key-1") -> str:
    """手工构造 HS256 令牌。

    不能用 pyjwt.encode：PyJWT 2.10 在**编码侧**已拒绝用非对称公钥当 HMAC 密钥，
    所以我们要手工算签名，才能真实测试**验证侧**的行为。
    """
    import base64
    import hashlib
    import hmac

    def b64(raw: bytes) -> bytes:
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    header = {"alg": "HS256", "typ": "JWT"}
    if kid:
        header["kid"] = kid
    segments = [
        b64(json.dumps(header, separators=(",", ":")).encode()),
        b64(json.dumps(claims, separators=(",", ":")).encode()),
    ]
    signing_input = b".".join(segments)
    signature = hmac.new(secret, signing_input, hashlib.sha256).digest()
    return (signing_input + b"." + b64(signature)).decode()


class TestJwksRejections:
    def test_wrong_signing_key_rejected(self, jwks_server):
        attacker = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(attacker)}")

    def test_unknown_kid_rejected(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(private, kid='not-in-jwks')}")

    def test_expired_token_rejected(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(private, expires_in=-3600)}")

    def test_audience_mismatch_rejected(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server, jwt_audience="dg-platform"))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(private, aud='someone-else')}")

        ok = auth.authenticate(f"Bearer {_token(private, aud='dg-platform')}")
        assert ok.id == "alice@corp"

    def test_issuer_mismatch_rejected(self, jwks_server, rsa_keys):
        private, _ = rsa_keys
        auth = Authenticator(settings=_oidc_settings(jwks_server, jwt_issuer="https://idp.corp"))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(private, iss='https://evil.example')}")

    def test_algorithm_confusion_attack_rejected(self, jwks_server, rsa_keys):
        """算法混淆攻击：用 **公钥 PEM 当 HMAC 密钥** 签一个 HS256 令牌。

        这是 JWT 最经典的漏洞 —— 若服务端同时接受 HS256 与 RS256，且用公钥内容
        当共享密钥校验，攻击者无需私钥即可伪造任意令牌（这里还直接伪造了 ADMIN）。
        我们的防护：JWKS 模式**只允许非对称算法**（见 Authenticator._algorithms）。
        """
        _, public_key = rsa_keys
        from cryptography.hazmat.primitives import serialization

        pem = public_key.public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        forged = _forged_hs256(
            pem,
            {
                "sub": "attacker",
                "roles": ["ADMIN"],
                "exp": int((datetime.now(timezone.utc) + timedelta(seconds=300)).timestamp()),
            },
        )
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {forged}")

    def test_plain_hs256_token_rejected_in_jwks_mode(self, jwks_server):
        """更简单的攻击面：用任意字符串密钥签 HS256 —— 同样必须被拒。"""
        forged = _forged_hs256(
            b"any-string-secret",
            {
                "sub": "attacker2",
                "roles": ["ADMIN"],
                "exp": int((datetime.now(timezone.utc) + timedelta(seconds=300)).timestamp()),
            },
        )
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {forged}")

    def test_alg_none_rejected(self, jwks_server):
        """`alg: none` 无签名令牌必须被拒。"""
        import base64

        def b64(raw: bytes) -> bytes:
            return base64.urlsafe_b64encode(raw).rstrip(b"=")

        header = b64(json.dumps({"alg": "none", "typ": "JWT"}).encode())
        payload = b64(
            json.dumps(
                {
                    "sub": "attacker3",
                    "roles": ["ADMIN"],
                    "exp": int((datetime.now(timezone.utc) + timedelta(seconds=300)).timestamp()),
                }
            ).encode()
        )
        forged = (header + b"." + payload + b".").decode()
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {forged}")

    def test_jwks_unreachable_fails_closed(self, rsa_keys):
        """JWKS 服务不可用时必须**失败关闭**（拒绝），不能放行也不能 500。"""
        private, _ = rsa_keys
        auth = Authenticator(
            settings=_oidc_settings("http://127.0.0.1:9/nonexistent", jwt_jwks_timeout=1)
        )
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {_token(private)}")

    def test_garbage_token_rejected(self, jwks_server):
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        with pytest.raises(Unauthenticated):
            auth.authenticate("Bearer not.a.jwt")


# ---------------------------------------------------------------------------
# 3. 配置与算法选择
# ---------------------------------------------------------------------------
class TestJwtConfiguration:
    def test_jwks_mode_defaults_to_asymmetric_only(self, jwks_server):
        auth = Authenticator(settings=_oidc_settings(jwks_server))
        algorithms = auth._algorithms()
        assert "HS256" not in algorithms
        assert set(algorithms) == {"RS256", "RS384", "RS512", "ES256", "ES384", "ES512"}

    def test_explicit_algorithms_override(self, jwks_server):
        auth = Authenticator(settings=_oidc_settings(jwks_server, jwt_algorithms="RS256, RS512"))
        assert auth._algorithms() == ["RS256", "RS512"]

    def test_shared_secret_mode_still_works(self):
        shared = dataclasses.replace(
            settings, auth_mode="jwt", jwt_jwks_url="", jwt_secret="s3cr3t", jwt_algorithm="HS256"
        )
        auth = Authenticator(settings=shared)
        token = pyjwt.encode(
            {"sub": "bob", "roles": ["READER"],
             "exp": datetime.now(timezone.utc) + timedelta(seconds=300)},
            "s3cr3t",
            algorithm="HS256",
        )
        assert auth.authenticate(f"Bearer {token}").id == "bob"
        # 用错的密钥签的令牌必须被拒
        bad = pyjwt.encode({"sub": "x", "exp": datetime.now(timezone.utc) + timedelta(seconds=300)},
                           "wrong", algorithm="HS256")
        with pytest.raises(Unauthenticated):
            auth.authenticate(f"Bearer {bad}")

    def test_missing_configuration_fails_loudly(self):
        """既没有 secret 也没有 jwks_url：必须显式报配置错误，不能静默放行。"""
        broken = dataclasses.replace(
            settings, auth_mode="jwt", jwt_jwks_url="", jwt_secret=""
        )
        auth = Authenticator(settings=broken)
        with pytest.raises(SystemExit) as exc:
            auth.authenticate("Bearer whatever")
        assert "DG_JWT_SECRET" in str(exc.value) or "DG_JWT_JWKS_URL" in str(exc.value)


# ---------------------------------------------------------------------------
# 4. HTTP 层表现
# ---------------------------------------------------------------------------
@pytest.fixture
def oidc_client(session, jwks_server, monkeypatch):
    """把 API 切换到 OIDC 模式（不启动外部 IdP）。"""
    import dg.auth.tokens as tokens_module

    def _override_session():
        yield session

    app.dependency_overrides[get_session] = _override_session
    monkeypatch.setattr(
        tokens_module, "_authenticator", Authenticator(settings=_oidc_settings(jwks_server))
    )
    with TestClient(app) as client:
        yield client
    app.dependency_overrides.clear()
    monkeypatch.setattr(tokens_module, "_authenticator", None)


class TestHttpLayer:
    def test_valid_oidc_token_reaches_api(self, oidc_client, rsa_keys):
        private, _ = rsa_keys
        response = oidc_client.get(
            "/api/v1/me", headers={"Authorization": f"Bearer {_token(private)}"}
        )
        assert response.status_code == 200
        body = response.json()
        assert body["id"] == "alice@corp"
        assert body["authMethod"] == "jwt"
        assert "asset:write" in body["permissions"]   # EDITOR

    def test_invalid_token_is_401_not_500(self, oidc_client):
        attacker = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        response = oidc_client.get(
            "/api/v1/me", headers={"Authorization": f"Bearer {_token(attacker)}"}
        )
        assert response.status_code == 401
        assert response.headers.get("WWW-Authenticate") == "Bearer"

    def test_role_from_idp_drives_authorization(self, oidc_client, rsa_keys):
        """IdP 给的 READER 角色 → 写操作必须被拒（授权与认证解耦但在同一条链上）。"""
        private, _ = rsa_keys
        headers = {"Authorization": f"Bearer {_token(private, roles=['READER'])}"}
        response = oidc_client.post(
            "/api/v1/assets/urn:dg:Dataset:prod.x.y.z.t/aspects/descriptions",
            headers=headers,
            json={"data": {"text": "x"}, "source": "MANUAL"},
        )
        assert response.status_code == 403
