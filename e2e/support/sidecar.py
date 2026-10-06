"""SQL 解析侧车（python -m dg.cli sidecar）的启动/探测/停止。

对齐 tools/java_e2e_verify.py:81-115。关键语义：**只关闭本套件拉起的侧车**；
若侧车本来就健康，则复用且 teardown 不杀它。
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import urllib.request

from . import config

_process: subprocess.Popen | None = None


def sidecar_alive() -> bool:
    try:
        with urllib.request.urlopen(config.SIDECAR_URL + "/healthz", timeout=3) as response:
            return response.status == 200
    except Exception:
        return False


def ensure_sidecar() -> bool:
    """确保侧车可用；返回 True 表示由本套件拉起（teardown 需要停止）。"""
    global _process
    if os.environ.get("DG_SKIP_SIDECAR") == "1":
        return False
    if sidecar_alive():
        return False
    env = dict(os.environ)
    env["PYTHONPATH"] = str(config.REPO_ROOT / "src")
    port = config.SIDECAR_URL.rsplit(":", 1)[-1]
    _process = subprocess.Popen(
        [sys.executable, "-m", "dg.cli", "sidecar", "--port", port],
        cwd=str(config.REPO_ROOT), env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(40):
        if sidecar_alive():
            return True
        time.sleep(0.5)
    return True


def stop_sidecar() -> None:
    global _process
    if _process is not None:
        _process.terminate()
        try:
            _process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            _process.kill()
        _process = None