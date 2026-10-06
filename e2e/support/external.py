"""外部依赖探测：决定 @clickhouse / @mongodb / @superset / @chrome 场景是否跳过。

原则：探测失败 = 带精确 host:port 原因跳过（绝不静默通过）。
BigQuery 与 dbt 不属于外部依赖（前者断言「缺凭据的可读错误」，后者用仓库内夹具）。
"""

from __future__ import annotations

import os
import shutil
import socket
from pathlib import Path

from . import config

_CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome Dev\Application\chrome.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]


def _tcp_open(addr: tuple[str, int], timeout: float = 1.5) -> bool:
    try:
        with socket.create_connection(addr, timeout=timeout):
            return True
    except OSError:
        return False


def clickhouse_available() -> bool:
    return _tcp_open(config.CLICKHOUSE_ADDR)


def mongodb_available() -> bool:
    return _tcp_open(config.MONGODB_ADDR)


def superset_available() -> bool:
    return _tcp_open(config.SUPERSET_ADDR)


def find_chrome() -> str | None:
    override = os.environ.get("DG_CHROME")
    if override and Path(override).exists():
        return override
    for candidate in _CHROME_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    return shutil.which("chrome") or shutil.which("chromium") or shutil.which("msedge")


def dbt_fixture_present() -> bool:
    return (config.REPO_ROOT / "tools" / "fixtures" / "dbt" / "manifest.json").exists()


# 标签 -> (探测函数, 跳过原因)
SKIP_PROBES: dict[str, tuple] = {
    "clickhouse": (clickhouse_available,
                   f"ClickHouse 未运行（期望 {config.CLICKHOUSE_ADDR[0]}:{config.CLICKHOUSE_ADDR[1]}）；"
                   "该场景连真实系统采集，缺失即跳过"),
    "mongodb": (mongodb_available,
                f"MongoDB 未运行（期望 {config.MONGODB_ADDR[0]}:{config.MONGODB_ADDR[1]}）；"
                "该场景连真实系统采集，缺失即跳过"),
    "superset": (superset_available,
                 f"Superset 未运行（期望 {config.SUPERSET_ADDR[0]}:{config.SUPERSET_ADDR[1]}）；"
                 "该场景连真实系统采集，缺失即跳过"),
    "chrome": (lambda: find_chrome() is not None,
               "找不到无头 Chrome/Edge（可用 DG_CHROME 指定路径）；界面渲染场景跳过"),
}