"""dgctl（Java 命令行）端到端验证。

为什么单独验证 CLI：SDK 的单测验证的是"请求长什么样"，CLI 还要验证**进程行为** ——
退出码、stderr、`--json` 输出、以及"服务不可达时是否给出可操作的提示"。
这些都在 Spring 容器之外（CLI 不启动容器），只能靠真的跑一次进程来确认。

覆盖：
  doctor / capabilities（含 --json）/ search / lineage / asset / help
  错误令牌 → 1，未知命令 → 2，服务不可达 → 3

用法：
  python tools/sdk_cli_verify.py
  python tools/sdk_cli_verify.py --base http://127.0.0.1:8081 --token dev-admin-token
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
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


def run_cli(jar: Path, args: list[str], base: str, token: str) -> subprocess.CompletedProcess:
    command = ["java", "-jar", str(jar), "dgctl", *args, "--url", base, "--token", token]
    return subprocess.run(command, cwd=str(ROOT), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=120)


def first_asset_urn(base: str, token: str) -> str | None:
    request = urllib.request.Request(f"{base}/api/v1/assets?limit=1")
    request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8"))
        assets = payload.get("assets") or []
        return assets[0]["urn"] if assets else None
    except (urllib.error.URLError, json.JSONDecodeError, KeyError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "backend" / "dg-api" / "target" / "dg-api-0.1.0.jar")
    parser.add_argument("--base", default="http://127.0.0.1:8081")
    parser.add_argument("--token", default="dev-admin-token")
    args = parser.parse_args()

    if not args.jar.exists():
        print(f"找不到 jar：{args.jar}\n先构建：cd backend; mvn -B package -DskipTests")
        return 2

    # 1) doctor
    result = run_cli(args.jar, ["doctor"], args.base, args.token)
    if result.returncode == 0 and "平台可用" in result.stdout:
        ok("dgctl doctor 退出码 0 且给出结论", result.stdout.strip().splitlines()[0])
    else:
        fail("dgctl doctor", f"退出码 {result.returncode}: {result.stdout[-300:]}{result.stderr[-300:]}")

    # 2) capabilities（人读）
    result = run_cli(args.jar, ["capabilities"], args.base, args.token)
    if result.returncode == 0 and "已实现" in result.stdout:
        ok("dgctl capabilities 输出能力计数")
    else:
        fail("dgctl capabilities", f"退出码 {result.returncode}: {result.stdout[-300:]}")

    # 3) capabilities --json 必须是合法 JSON（脚本可直接消费）
    result = run_cli(args.jar, ["capabilities", "--json"], args.base, args.token)
    try:
        payload = json.loads(result.stdout)
        summary = payload.get("summary", {})
        if result.returncode == 0 and "implemented" in summary:
            ok("dgctl capabilities --json 是合法 JSON", f"total={summary.get('total')}")
        else:
            fail("dgctl capabilities --json", f"退出码 {result.returncode}，缺 summary")
    except json.JSONDecodeError:
        fail("dgctl capabilities --json", f"不是 JSON：{result.stdout[:200]}")

    # 4) search
    result = run_cli(args.jar, ["search", "event", "--limit", "3"], args.base, args.token)
    if result.returncode == 0 and "命中" in result.stdout:
        ok("dgctl search 走真实检索接口", result.stdout.strip().splitlines()[0])
    else:
        fail("dgctl search", f"退出码 {result.returncode}: {result.stdout[-300:]}")

    # 5) asset / lineage 用真实 URN
    urn = first_asset_urn(args.base, args.token)
    if urn is None:
        fail("dgctl asset", "平台上取不到任何资产（先跑一次采集）")
    else:
        result = run_cli(args.jar, ["asset", urn], args.base, args.token)
        if result.returncode == 0 and "URN" in result.stdout:
            ok("dgctl asset 读真实资产", urn.rsplit(":", 1)[-1])
        else:
            fail("dgctl asset", f"退出码 {result.returncode}: {result.stdout[-200:]}{result.stderr[-200:]}")

        result = run_cli(args.jar, ["lineage", urn, "--depth", "1"], args.base, args.token)
        if result.returncode == 0 and "节点" in result.stdout:
            ok("dgctl lineage 读真实血缘图")
        else:
            fail("dgctl lineage", f"退出码 {result.returncode}: {result.stdout[-200:]}")

        result = run_cli(args.jar, ["impact", urn, "--depth", "1"], args.base, args.token)
        if result.returncode == 0 and "影响面" in result.stdout:
            ok("dgctl impact 读影响分析")
        else:
            fail("dgctl impact", f"退出码 {result.returncode}: {result.stdout[-200:]}")

    # 6) 错误令牌 → 1（且提示可操作，而不是堆栈）
    result = run_cli(args.jar, ["capabilities"], args.base, "wrong-token")
    if result.returncode == 1 and "错误" in result.stderr and "Traceback" not in result.stderr:
        ok("错误令牌 → 退出码 1（无堆栈，可直接给运维看）")
    else:
        fail("错误令牌 → 退出码 1", f"实际 {result.returncode}: {result.stderr[-200:]}")

    # 7) 未知命令 → 2
    result = run_cli(args.jar, ["nonsense"], args.base, args.token)
    if result.returncode == 2 and "未知命令" in result.stderr:
        ok("未知命令 → 退出码 2")
    else:
        fail("未知命令 → 退出码 2", f"实际 {result.returncode}")

    # 8) 服务不可达 → 3（并说明怎么办）
    result = run_cli(args.jar, ["doctor"], "http://127.0.0.1:1", args.token)
    if result.returncode == 3 and "连接失败" in result.stderr:
        ok("服务不可达 → 退出码 3 且给出排查提示", "与'平台返回错误'可区分")
    else:
        fail("服务不可达 → 退出码 3", f"实际 {result.returncode}: {result.stderr[-200:]}")

    # 9) help
    result = run_cli(args.jar, ["help"], args.base, args.token)
    if result.returncode == 0 and "用法" in result.stdout:
        ok("dgctl help 打印用法")
    else:
        fail("dgctl help", f"退出码 {result.returncode}")

    failed = [item for item in RESULTS if not item[0]]
    print(f"\n共 {len(RESULTS)} 项检查：{len(RESULTS) - len(failed)} 通过 / {len(failed)} 失败")
    for _, name, detail in failed:
        print(f"  - {name}: {detail}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
