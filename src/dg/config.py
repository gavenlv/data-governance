"""平台配置。

不引入 pydantic-settings，保持零额外依赖：配置全部来自环境变量，带合理默认值，
默认值面向"本机 Phase 0 开发"场景（见 docs/10-tech-stack.md §1）。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    return value if value else default


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """运行期配置。"""

    # --- 数据库：唯一真相源（docs/07 §3.1 / ADR-001） ---
    database_url: str = field(
        default_factory=lambda: _env(
            "DG_DATABASE_URL",
            # 本机 PG 监听在 25011（非默认端口），凭据来自 ~/.pgpass
            "postgresql+psycopg2://postgres:root@localhost:25011/dg",
        )
    )

    # --- 模型注册表（docs/08 §6）---
    model_dir: Path = field(
        default_factory=lambda: Path(_env("DG_MODEL_DIR", str(REPO_ROOT / "model")))
    )

    # --- 租户（docs/07 §9 Q2：v1 逻辑隔离，仅保留 tenant 列，不启用 RLS）---
    default_tenant: str = field(default_factory=lambda: _env("DG_TENANT", "default"))
    default_namespace: str = field(default_factory=lambda: _env("DG_NAMESPACE", "prod"))

    # --- 服务 ---
    api_host: str = field(default_factory=lambda: _env("DG_API_HOST", "127.0.0.1"))
    api_port: int = field(default_factory=lambda: _env_int("DG_API_PORT", 8080))

    # --- 认证与授权（docs/09 §9.7）---
    # auth_mode: static（静态开发令牌，默认）| jwt（校验 Bearer JWT）| disabled（仅限本机，危险）
    auth_mode: str = field(default_factory=lambda: _env("DG_AUTH_MODE", "static"))
    # 静态令牌：JSON 数组，形如
    #   [{"token":"...","id":"alice","name":"Alice","roles":["ADMIN"],"teams":["data"]}]
    auth_tokens_json: str = field(default_factory=lambda: _env("DG_AUTH_TOKENS", ""))
    # JWT 校验参数（auth_mode=jwt 时必填其一组）
    #   方式 A：共享密钥（HS256）—— DG_JWT_SECRET
    #   方式 B：OIDC/JWKS（生产推荐）—— DG_JWT_JWKS_URL，如
    #          https://idp.example.com/.well-known/jwks.json
    jwt_secret: str = field(default_factory=lambda: _env("DG_JWT_SECRET", ""))
    jwt_jwks_url: str = field(default_factory=lambda: _env("DG_JWT_JWKS_URL", ""))
    jwt_algorithms: str = field(
        default_factory=lambda: _env("DG_JWT_ALGORITHMS", "")
    )
    jwt_algorithm: str = field(default_factory=lambda: _env("DG_JWT_ALGORITHM", "HS256"))
    jwt_audience: str = field(default_factory=lambda: _env("DG_JWT_AUDIENCE", ""))
    jwt_issuer: str = field(default_factory=lambda: _env("DG_JWT_ISSUER", ""))
    jwt_roles_claim: str = field(default_factory=lambda: _env("DG_JWT_ROLES_CLAIM", "roles"))
    # JWKS 缓存寿命（秒）与网络超时
    jwt_jwks_cache_seconds: int = field(
        default_factory=lambda: _env_int("DG_JWT_JWKS_CACHE_SECONDS", 3600)
    )
    jwt_jwks_timeout: int = field(default_factory=lambda: _env_int("DG_JWT_JWKS_TIMEOUT", 5))
    # 允许的时钟偏移（秒）
    jwt_leeway: int = field(default_factory=lambda: _env_int("DG_JWT_LEEWAY", 30))

    # --- 消费者：单批处理量与轮询间隔 ---
    consumer_batch_size: int = field(default_factory=lambda: _env_int("DG_CONSUMER_BATCH", 500))
    consumer_poll_interval: float = field(
        default_factory=lambda: float(_env("DG_CONSUMER_POLL_INTERVAL", "1.0"))
    )

    # --- 告警（docs/09 §9.1）---
    # 健康巡检间隔（秒）。设为 0 可关闭周期巡检。
    alert_check_interval: int = field(
        default_factory=lambda: _env_int("DG_ALERT_CHECK_INTERVAL_SECONDS", 900)
    )
    # 仍在告警中的问题多久再提醒一次（秒）
    alert_remind_interval: int = field(
        default_factory=lambda: _env_int("DG_ALERT_REMIND_SECONDS", 12 * 3600)
    )
    # 刚恢复的问题在此冷却期内不重新告警（防抖动刷屏，设为 0 关闭）
    alert_reopen_cooldown: int = field(
        default_factory=lambda: _env_int("DG_ALERT_REOPEN_COOLDOWN_SECONDS", 300)
    )

    @property
    def auth_disabled(self) -> bool:
        return self.auth_mode == "disabled"

    def dsn_psycopg2(self) -> str:
        """SQLAlchemy 2.0 使用的 DSN（psycopg2 驱动）。"""
        return self.database_url


settings = Settings()
