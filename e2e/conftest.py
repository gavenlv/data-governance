"""pytest-bdd 套件配置：选项、前置检查、侧车、场景上下文、外部依赖跳过、步骤注册。

运行：python -m pytest e2e -p no:cacheprovider --gherkin-terminal-reporter
前置：控制面已在 --base-url（默认 http://127.0.0.1:8081）运行。本套件**不启动**控制面，
      但会自动管理 SQL 解析侧车（缺失则拉起，结束时仅关闭自己拉起的）。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_E2E_DIR = Path(__file__).resolve().parent
if str(_E2E_DIR) not in sys.path:
    sys.path.insert(0, str(_E2E_DIR))

from support import config, external, sidecar  # noqa: E402
from support.client import Api  # noqa: E402
from support.db import psql_binary  # noqa: E402
from support.world import World  # noqa: E402

_MARKERS = [
    "external: 依赖外部基础设施（缺失则跳过）",
    "clickhouse: 需要 ClickHouse 127.0.0.1:8123",
    "mongodb: 需要 MongoDB 127.0.0.1:27018",
    "superset: 需要 Superset 127.0.0.1:18089",
    "bigquery: BigQuery 分组（缺凭据错误始终可断言，不跳过）",
    "dbt: dbt manifest 连接器（仓库内夹具，不跳过）",
    "chrome: 需要无头 Chrome/Edge",
    "psql: 需要 psql 二进制构造合成历史",
]


def pytest_addoption(parser) -> None:
    group = parser.getgroup("dg-bdd")
    group.addoption(
        "--base-url", action="store", default=config.DEFAULT_BASE_URL,
        help="控制面地址（默认 http://127.0.0.1:8081，可用 DG_E2E_BASE_URL 覆盖）",
    )
    group.addoption(
        "--namespace", action="store", default=config.DEFAULT_NAMESPACE,
        help="端到端验证使用的命名空间（默认 java_e2e）",
    )


def pytest_configure(config) -> None:
    for marker in _MARKERS:
        config.addinivalue_line("markers", marker)


def pytest_collection_modifyitems(config, items) -> None:
    """按外部依赖可用性，为带标签的场景追加 skip（原因含精确 host:port）。"""
    for item in items:
        for tag, (probe, reason) in external.SKIP_PROBES.items():
            if item.get_closest_marker(tag) is None:
                continue
            if not probe():
                item.add_marker(pytest.mark.skip(reason=reason))


@pytest.fixture(scope="session")
def base_url(pytestconfig) -> str:
    return str(pytestconfig.getoption("--base-url")).rstrip("/")


@pytest.fixture(scope="session")
def namespace(pytestconfig) -> str:
    return str(pytestconfig.getoption("--namespace"))


@pytest.fixture(scope="session")
def api(base_url: str) -> Api:
    return Api(base_url)


@pytest.fixture(scope="session", autouse=True)
def service_up(api: Api, base_url: str) -> None:
    """前置检查：控制面未就绪则快速失败并给出启动命令。"""
    status, health = api.call("GET", "/healthz", token=None)
    if status != 200 or not isinstance(health, dict) or "java" not in str(health.get("controlPlane", "")):
        pytest.fail(
            f"控制面未就绪（{base_url}，HTTP {status}）。请先启动：\n{config.START_COMMAND}"
        )
    status, _ = api.call("GET", "/api/v1/me", token=config.ADMIN_TOKEN)
    if status != 200:
        pytest.fail(
            f"管理员令牌不可用（HTTP {status}）。请确认 DG_AUTH_MODE=static 且使用开发令牌"
            "（dev-admin-token / dev-steward-token / dev-reader-token）"
        )


@pytest.fixture(scope="session", autouse=True)
def sidecar_service(service_up) -> None:
    """侧车：缺失则拉起；结束时仅关闭本套件拉起的实例。"""
    sidecar.ensure_sidecar()
    yield
    sidecar.stop_sidecar()


@pytest.fixture
def world(namespace: str) -> World:
    ctx = World(namespace=namespace, token=config.ADMIN_TOKEN)
    yield ctx
    ctx.cleanup()


@pytest.fixture(scope="session")
def psql() -> str:
    binary = psql_binary()
    if binary is None:
        message = (
            "未找到 psql；设置 DG_PSQL 指向 psql 可执行文件，"
            "或设 BDD_ALLOW_SKIP_PSQL=1 跳过相关场景"
        )
        if os.environ.get("BDD_ALLOW_SKIP_PSQL") == "1":
            pytest.skip(message)
        pytest.fail(message)
    return binary


# 注册步骤模块：step 装饰器把 fixture 注入到被装饰函数所在模块的命名空间。
# 这里自动发现 steps 包下的全部模块，并把它们的 pytestbdd_stepdef_* fixture
# 复制进 conftest 命名空间，使 pytest 将其登记为 conftest 级 fixture（全局可见）。
# 采用自动发现，避免每新增一个 feature 都要改本文件。
import importlib  # noqa: E402
import pkgutil  # noqa: E402

import steps as _steps_pkg  # noqa: E402

_THIS = sys.modules[__name__]
for _module_info in pkgutil.iter_modules(_steps_pkg.__path__):
    _module = importlib.import_module(f"steps.{_module_info.name}")
    for _attr in dir(_module):
        if _attr.startswith("pytestbdd_stepdef"):
            setattr(_THIS, _attr, getattr(_module, _attr))