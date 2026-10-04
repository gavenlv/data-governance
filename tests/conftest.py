"""pytest 夹具。

**使用独立的测试库**（默认 `dg_test`，可用 `DG_TEST_DATABASE_URL` 覆盖），
避免测试的 TRUNCATE 清空本地开发/演示数据（见 docs/21 §4 限制 3）。
"""

from __future__ import annotations

import os
from contextlib import contextmanager

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from dg.cli import _split_sql
from dg.config import REPO_ROOT, settings
from dg.model import ModelRegistry

TEST_DSN = os.environ.get(
    "DG_TEST_DATABASE_URL",
    "postgresql+psycopg2://postgres:root@localhost:25011/dg_test",
)

TABLES = (
    "search_doc",
    "consumer_offset",
    "audit_log",
    "event_log",
    "edge",
    "aspect_history",
    "aspect",
    "entity",
    "collect_run",
    "collector_state",
    "collect_schedule",
    "lineage_parse_sample",
    "alert_dispatch_log",
    "alert_event",
    "alert_channel",
)

_engine = create_engine(TEST_DSN, pool_pre_ping=True, future=True)
_TestSession = sessionmaker(bind=_engine, expire_on_commit=False, future=True)


def _apply_schema() -> None:
    """应用 sql/ 下全部迁移（与 `dgctl init` 同一逻辑），保证测试库含最新表。"""
    sql_dir = REPO_ROOT / "sql"
    files = sorted(sql_dir.glob("*.sql"))
    with _engine.begin() as conn:
        for sql_file in files:
            for stmt in _split_sql(sql_file.read_text(encoding="utf-8")):
                conn.execute(text(stmt))


@pytest.fixture(scope="session", autouse=True)
def _test_schema():
    """确保测试库的 schema 就绪（幂等）。"""
    _apply_schema()
    yield


@pytest.fixture(scope="session")
def registry() -> ModelRegistry:
    return ModelRegistry.load(settings.model_dir)


@pytest.fixture
def session():
    """每个测试一个干净状态（仅作用于测试库）。"""
    s = _TestSession()
    s.execute(text(f"TRUNCATE {', '.join(TABLES)} RESTART IDENTITY CASCADE"))
    s.commit()
    try:
        yield s
    finally:
        s.rollback()
        s.close()


@pytest.fixture
def svc(session, registry):
    from dg.core.service import MetadataService

    return MetadataService(session, registry, actor="tester", tenant="default", namespace="prod")


@contextmanager
def session_scope_test():
    """与 dg.db.session_scope 同语义，但作用于**测试库**。

    供需要自建 session 的组件（如调度器）做依赖注入，避免它们硬编码生产库。
    """
    s = _TestSession()
    try:
        yield s
        s.commit()
    except Exception:
        s.rollback()
        raise
    finally:
        s.close()


@pytest.fixture
def scheduler_registry(registry):
    """返回 (registry, session_factory) 供调度器测试使用。"""
    return registry, session_scope_test
