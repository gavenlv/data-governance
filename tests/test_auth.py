"""认证与授权测试。

重点验证的安全属性（docs/09 §9.3、§9.7）：
  1. 无凭证不可访问；无效凭证不可用
  2. RBAC：角色决定能做什么（读/写/运维）
  3. ABAC：分级决定能看到什么（L4 对 READER 不可见）
  4. **搜索与详情用同一套判定** —— 搜索里不出现的东西，直接 GET 也必须 404
     （任何一处判定不同就是越权漏洞）
  5. 血缘不得泄露无权节点的资产名
  6. 分级修改需要治理权限（不能自己把 L4 降级成 L1）
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

from dg.api.app import app, get_session
from dg.auth import Policy, Principal, normalize_roles
from dg.auth.principal import ROLE_ADMIN, ROLE_EDITOR, ROLE_READER, ROLE_STEWARD
from dg.auth.tokens import Authenticator, Forbidden, Unauthenticated
from dg.config import settings

ADMIN = {"Authorization": "Bearer dev-admin-token"}
STEWARD = {"Authorization": "Bearer dev-steward-token"}
READER = {"Authorization": "Bearer dev-reader-token"}

PUBLIC_ASSET = "urn:dg:Dataset:prod.mysql.sales.public.orders"
SECRET_ASSET = "urn:dg:Dataset:prod.mysql.sales.public.salaries"


# ---------------------------------------------------------------------------
# 纯函数：Authenticator / Policy
# ---------------------------------------------------------------------------
class TestAuthenticator:
    def test_missing_token_rejected(self):
        auth = Authenticator()
        with pytest.raises(Unauthenticated):
            auth.authenticate(None)
        with pytest.raises(Unauthenticated):
            auth.authenticate("Basic abc")

    def test_unknown_token_rejected(self):
        with pytest.raises(Unauthenticated):
            Authenticator().authenticate("Bearer not-a-real-token")

    def test_valid_token_resolves_principal(self):
        p = Authenticator().authenticate("Bearer dev-reader-token")
        assert p.id == "reader@local"
        assert p.has_role(ROLE_READER)
        assert p.auth_method == "static"

    def test_unknown_roles_are_dropped(self):
        """配置里写错角色名不得静默提权。"""
        assert normalize_roles(["admin", "SUPERUSER", "reader"]) == frozenset({"ADMIN", "READER"})
        assert normalize_roles(None) == frozenset()

    def test_jwt_mode(self):
        import jwt as pyjwt

        auth = Authenticator(
            settings=type(settings)(
                database_url=settings.database_url,
                model_dir=settings.model_dir,
                auth_mode="jwt",
                jwt_secret="test-secret",
                jwt_audience="",
                jwt_issuer="",
            )
        )
        token = pyjwt.encode(
            {"sub": "alice@corp", "name": "Alice", "roles": ["EDITOR"], "teams": ["data"]},
            "test-secret",
            algorithm="HS256",
        )
        p = auth.authenticate(f"Bearer {token}")
        assert p.id == "alice@corp"
        assert p.auth_method == "jwt"
        assert p.has_role(ROLE_EDITOR)

        with pytest.raises(Unauthenticated):
            auth.authenticate("Bearer " + pyjwt.encode({"sub": "x"}, "wrong-secret", algorithm="HS256"))


class TestPolicy:
    def test_role_permissions(self):
        reader = Principal(id="r", roles=frozenset({ROLE_READER}))
        assert Policy.can(reader, "asset:read")
        assert not Policy.can(reader, "asset:write")
        assert not Policy.can(reader, "index:rebuild")

        editor = Principal(id="e", roles=frozenset({ROLE_EDITOR}))
        assert Policy.can(editor, "asset:write")
        assert not Policy.can(editor, "governance:write")

        admin = Principal(id="a", roles=frozenset({ROLE_ADMIN}))
        assert Policy.can(admin, "anything:at:all")

    def test_no_role_is_least_privilege(self):
        anon = Principal(id="x", roles=frozenset())
        assert not Policy.can(anon, "asset:read")
        assert Policy.max_visible_level(anon) == "L1"

    def test_classification_visibility(self):
        reader = Principal(id="r", roles=frozenset({ROLE_READER}))
        steward = Principal(id="s", roles=frozenset({ROLE_STEWARD}))
        assert Policy.can_see_classification(reader, "L1")
        assert Policy.can_see_classification(reader, "L3")
        assert not Policy.can_see_classification(reader, "L4")
        assert Policy.can_see_classification(steward, "L4")

    def test_unclassified_defaults_to_l2(self):
        reader = Principal(id="r", roles=frozenset({ROLE_READER}))
        assert Policy.can_see_classification(reader, None)

    def test_forbidden_raised(self):
        reader = Principal(id="r", roles=frozenset({ROLE_READER}))
        with pytest.raises(Forbidden):
            Policy.authorize(reader, "asset:write")

    def test_search_filter_is_prepended_and_consistent(self):
        """搜索过滤条件必须与 can_see_classification 一致。"""
        reader = Principal(id="r", roles=frozenset({ROLE_READER}))
        clause, params = Policy.search_visibility_filter(reader)
        assert "visible_levels" in params
        assert set(params["visible_levels"]) == set(Policy.visible_levels(reader))
        assert "L4" not in params["visible_levels"]
        assert clause


# ---------------------------------------------------------------------------
# HTTP 集成（对测试库操作）
# ---------------------------------------------------------------------------
@pytest.fixture
def client(session):
    def _override_session():
        yield session

    app.dependency_overrides[get_session] = _override_session
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


def _seed(client, headers, urn: str, level: str, name: str):
    r = client.post(
        f"/api/v1/assets/{urn}/aspects/descriptions",
        headers=headers,
        json={"data": {"text": f"{name} 的描述"}, "source": "MANUAL"},
    )
    assert r.status_code == 200, r.text
    r = client.post(
        f"/api/v1/assets/{urn}/aspects/classification",
        headers=headers,
        json={"data": {"level": level}, "source": "MANUAL"},
    )
    assert r.status_code == 200, r.text
    r = client.post("/api/v1/index/consume", headers=headers, json={})
    assert r.status_code == 200, r.text


class TestApiAuthorization:
    def test_healthz_is_public(self, client):
        assert client.get("/healthz").status_code == 200

    def test_api_requires_token(self, client):
        assert client.get("/api/v1/model").status_code == 401
        assert client.get("/api/v1/search").status_code == 401
        assert client.get("/api/v1/me").status_code == 401

    def test_invalid_token_rejected(self, client):
        r = client.get("/api/v1/me", headers={"Authorization": "Bearer nope"})
        assert r.status_code == 401
        assert r.headers.get("WWW-Authenticate") == "Bearer"

    def test_whoami_reports_effective_permissions(self, client):
        r = client.get("/api/v1/me", headers=READER)
        assert r.status_code == 200
        body = r.json()
        assert body["id"] == "reader@local"
        assert "asset:read" in body["permissions"]
        assert "asset:write" not in body["permissions"]
        assert body["maxVisibleLevel"] == "L3"

    def test_reader_cannot_write(self, client):
        r = client.post(
            f"/api/v1/assets/{PUBLIC_ASSET}/aspects/descriptions",
            headers=READER,
            json={"data": {"text": "x"}, "source": "MANUAL"},
        )
        assert r.status_code == 403

    def test_editor_cannot_rebuild_index(self, client):
        assert client.post("/api/v1/index/rebuild", headers=READER, json={}).status_code == 403

    def test_only_steward_can_change_classification(self, client):
        _seed(client, ADMIN, PUBLIC_ASSET, "L2", "公开资产")
        r = client.post(
            f"/api/v1/assets/{PUBLIC_ASSET}/aspects/classification",
            headers=READER,
            json={"data": {"level": "L1"}, "source": "MANUAL"},
        )
        assert r.status_code == 403  # READER 连写权限都没有
        r = client.post(
            f"/api/v1/assets/{PUBLIC_ASSET}/aspects/classification",
            headers=STEWARD,
            json={"data": {"level": "L2"}, "source": "MANUAL"},
        )
        assert r.status_code == 200

    def test_l4_asset_hidden_from_reader_in_both_search_and_detail(self, client, session):
        """核心安全属性：搜索与详情的授权判定必须一致。"""
        _seed(client, ADMIN, SECRET_ASSET, "L4", "薪资表")
        _seed(client, ADMIN, PUBLIC_ASSET, "L2", "订单表")

        # 管理员两者都看得到
        admin_search = client.get("/api/v1/search?q=", headers=ADMIN).json()
        admin_urns = {r["urn"] for r in admin_search["results"]}
        assert {PUBLIC_ASSET, SECRET_ASSET} <= admin_urns

        # READER：搜索里不出现 L4
        reader_search = client.get("/api/v1/search?q=", headers=READER).json()
        reader_urns = {r["urn"] for r in reader_search["results"]}
        assert PUBLIC_ASSET in reader_urns
        assert SECRET_ASSET not in reader_urns
        assert "L4" not in reader_search["visibleLevels"]

        # READER：详情必须同样 404（不确认存在性）
        assert client.get(f"/api/v1/assets/{SECRET_ASSET}", headers=READER).status_code == 404
        # 且 aspect 端点也一致
        assert (
            client.get(f"/api/v1/assets/{SECRET_ASSET}/aspects/descriptions", headers=READER).status_code
            == 404
        )
        # 管理员可以正常读取
        assert client.get(f"/api/v1/assets/{SECRET_ASSET}", headers=ADMIN).status_code == 200

    def test_lineage_does_not_leak_hidden_nodes(self, client, session):
        """血缘不得把无权资产的 URN 带出来。"""
        _seed(client, ADMIN, PUBLIC_ASSET, "L2", "订单表")
        _seed(client, ADMIN, SECRET_ASSET, "L4", "薪资表")
        r = client.post(
            "/api/v1/edges",
            headers=ADMIN,
            json={"fromUrn": SECRET_ASSET, "toUrn": PUBLIC_ASSET, "edgeType": "derivesFrom",
                  "source": "manual", "confidence": 1.0},
        )
        assert r.status_code == 200, r.text

        reader_lin = client.get(
            f"/api/v1/assets/{PUBLIC_ASSET}/lineage?direction=upstream&depth=2", headers=READER
        ).json()
        assert SECRET_ASSET not in {n["urn"] for n in reader_lin["nodes"]}
        assert reader_lin["hiddenNodes"] >= 1

        admin_lin = client.get(
            f"/api/v1/assets/{PUBLIC_ASSET}/lineage?direction=upstream&depth=2", headers=ADMIN
        ).json()
        assert SECRET_ASSET in {n["urn"] for n in admin_lin["nodes"]}

    def test_audit_endpoint_available_to_authenticated_users(self, client):
        _seed(client, ADMIN, PUBLIC_ASSET, "L2", "订单表")
        r = client.get("/api/v1/audit?limit=5", headers=ADMIN)
        assert r.status_code == 200
        assert r.json()["count"] >= 1
