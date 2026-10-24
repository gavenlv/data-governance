"""语义检索向量路端到端验证。

验证的是**链路与诚实性**，不是模型质量：
  1. 配置 embedding 后，向量索引可重建、可观测（provider / 覆盖数）；
  2. 混合检索出现第三路 `vector`，并且确实贡献了结果（retrievers 含 vector）；
  3. 未配置 embedding 时，`vector` 路明确 `available=false`，词法结果**不受影响**
     —— 不允许"悄悄退化成词法却仍声称语义检索"。

本机没有 pgvector，因此召回是"取候选 + Java 余弦 + 上限"，脚本会检查截断字段存在。
生产质量取决于所配置的 embedding 模型（Provider=openai），`hash` 只是打通链路。

用法：python tools/vector_search_verify.py [--jar ...]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS: list[tuple[bool, str, str]] = []


def ok(name: str, detail: str = "") -> None:
    RESULTS.append((True, name, detail))
    print(f"PASS  {name}" + (f" —— {detail}" if detail else ""))


def fail(name: str, detail: str = "") -> None:
    RESULTS.append((False, name, detail))
    print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))


def http(method: str, url: str, token: str | None = None, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, (json.loads(raw) if raw.startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw


def start(env_extra: dict, log_path: Path):
    env = {"DG_MODEL_DIR": str(ROOT / "model"), "DG_SQL_DIR": str(ROOT / "sql"),
           "DG_WEB_DIST": str(ROOT / "web" / "dist"),
           "DG_SCHEDULER_ENABLED": "false", "DG_SEARCH_CONSUME_INTERVAL_MS": "0",
           "DG_LINEAGE_SIDECAR_URL": "", **env_extra}
    import os
    merged = os.environ.copy()
    merged.update(env)
    log = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        ["java", "-jar", str(JAR), "--server.port=0"], cwd=str(ROOT), env=merged,
        stdout=log, stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return process, log


def wait_port(log_path: Path, process: subprocess.Popen, timeout: float = 180):
    pattern = re.compile(r"Tomcat started on port (\d+)")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            return None
        try:
            found = pattern.search(log_path.read_text(encoding="utf-8", errors="replace"))
        except FileNotFoundError:
            found = None
        if found:
            return int(found.group(1))
        time.sleep(0.5)
    return None


def wait_ready(base: str, process: subprocess.Popen, timeout: float = 120) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            return False
        status, _ = http("GET", f"{base}/actuator/health")
        if status == 200:
            return True
        time.sleep(1)
    return False


def stop(process: subprocess.Popen, log) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
    log.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "backend" / "dg-api" / "target" / "dg-api-0.1.0.jar")
    args = parser.parse_args()
    global JAR
    JAR = args.jar
    if not JAR.exists():
        print(f"找不到 jar：{JAR}\n先构建：mvn -B -f backend/pom.xml package -DskipTests")
        return 2

    temp = Path(tempfile.mkdtemp(prefix="dg-vector-e2e-"))
    token = "dev-admin-token"
    reader = "dev-reader-token"

    # ---- 场景 A：配置了 embedding（hash，仅用于验证链路）----
    log_a = temp / "hash.log"
    process, handle = start({"DG_AI_EMBEDDING_PROVIDER": "hash",
                             "DG_AI_EMBEDDING_DIMENSION": "256"}, log_a)
    try:
        port = wait_port(log_a, process)
        if port is None or not wait_ready(f"http://127.0.0.1:{port}", process):
            print("控制面未能启动：\n" + log_a.read_text(encoding="utf-8", errors="replace")[-2000:])
            return 1
        base = f"http://127.0.0.1:{port}"

        status, vector_status = http("GET", f"{base}/api/v1/ai/search/vectors", token)
        if status == 200 and vector_status.get("available") is True:
            ok("向量路可用（provider=hash）", f"provider={vector_status.get('provider')}")
        else:
            fail("向量路可用", f"{status}: {vector_status}")

        status, rebuilt = http("POST", f"{base}/api/v1/ai/search/vectors/rebuild?limit=2000", token)
        if status == 200 and (rebuilt.get("embedded", 0) + rebuilt.get("skippedUnchanged", 0)) > 0:
            ok("向量索引可重建", f"scanned={rebuilt.get('scanned')} embedded={rebuilt.get('embedded')} "
                                f"skipped={rebuilt.get('skippedUnchanged')}")
        else:
            fail("向量索引可重建", f"{status}: {rebuilt}")

        status, rebuilt_again = http("POST", f"{base}/api/v1/ai/search/vectors/rebuild?limit=2000", token)
        if status == 200 and rebuilt_again.get("embedded", -1) == 0:
            ok("重建是增量的（内容未变不重复向量化）", f"skipped={rebuilt_again.get('skippedUnchanged')}")
        else:
            fail("重建是增量的", f"{status}: {rebuilt_again}")

        status, payload = http("GET", f"{base}/api/v1/ai/search?q=%E8%AE%A2%E5%8D%95&limit=10", token)
        legs = {leg.get("name"): leg for leg in (payload.get("retrievers") or [])} if status == 200 else {}
        if status == 200 and legs.get("vector", {}).get("available") is True:
            ok("混合检索出现第三路 vector", f"hits={legs.get('vector', {}).get('weight')}")
        else:
            fail("混合检索出现第三路 vector", f"{status}: {payload}")
        if status == 200 and payload.get("count", 0) > 0:
            contributed = [item for item in payload.get("results", [])
                           if "vector" in (item.get("retrievers") or [])]
            if contributed:
                ok("向量路确实贡献了结果", f"{len(contributed)} 条命中含 vector")
            else:
                fail("向量路确实贡献了结果", "结果里没有任何条目来自 vector 路（可能与词法完全重合）")
            vector_info = payload.get("vector") or {}
            if "truncated" in vector_info and "scanned" in vector_info:
                ok("截断与扫描量显式回报", f"scanned={vector_info.get('scanned')} truncated={vector_info.get('truncated')}")
            else:
                fail("截断与扫描量显式回报", str(vector_info))
        else:
            fail("混合检索有结果", f"{status}: {str(payload)[:200]}")

        status, _ = http("POST", f"{base}/api/v1/ai/search/vectors/rebuild?limit=10", reader)
        if status == 403:
            ok("重建向量索引需要 index:rebuild（READER → 403）")
        else:
            fail("重建向量索引权限", f"实际 {status}")
    finally:
        stop(process, handle)

    # ---- 场景 B：未配置 embedding：必须明确不可用，且不影响词法 ----
    log_b = temp / "none.log"
    process, handle = start({"DG_AI_EMBEDDING_PROVIDER": "none"}, log_b)
    try:
        port = wait_port(log_b, process)
        if port is None or not wait_ready(f"http://127.0.0.1:{port}", process):
            fail("未配置 embedding 时可启动", "控制面未启动")
        else:
            base = f"http://127.0.0.1:{port}"
            status, payload = http("GET", f"{base}/api/v1/ai/search?q=event&limit=5", token)
            legs = {leg.get("name"): leg for leg in (payload.get("retrievers") or [])} if status == 200 else {}
            if status == 200 and legs.get("vector", {}).get("available") is False:
                ok("未配置 embedding 时 vector 路明确不可用")
            else:
                fail("未配置 embedding 时 vector 路明确不可用", f"{status}: {legs.get('vector')}")
            if status == 200 and payload.get("count", 0) > 0:
                ok("未配置 embedding 时词法结果不受影响", f"{payload.get('count')} 条")
            else:
                fail("未配置 embedding 时词法结果不受影响", f"{status}")
            status, rebuilt = http("POST", f"{base}/api/v1/ai/search/vectors/rebuild", token)
            if status == 502:
                ok("未配置时重建向量索引 → 502（缺配置，不是调用方错误）")
            else:
                fail("未配置时重建向量索引 → 502", f"实际 {status}: {rebuilt}")
    finally:
        stop(process, handle)

    failed = [item for item in RESULTS if not item[0]]
    print(f"\n共 {len(RESULTS)} 项检查：{len(RESULTS) - len(failed)} 通过 / {len(failed)} 失败")
    for _, name, detail in failed:
        print(f"  - {name}: {detail}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
