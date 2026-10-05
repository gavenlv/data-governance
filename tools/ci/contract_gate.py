"""契约与治理 CI 门禁（参考实现，可直接作为 CI 步骤使用）。

用法：

    # GitHub Actions / GitLab CI / Jenkins 里都是一条命令
    python tools/ci/contract_gate.py --contract contracts/dwd_orders.yaml \
        --namespace prod --base-url http://127.0.0.1:8081

环境变量：
    DG_API_TOKEN     访问令牌（必需，除非 --base-url 指向关闭认证的开发实例）
    DG_API_BASE_URL  平台地址（也可用 --base-url）

退出码：
    0  通过（PASS）或有警告（WARN）
    1  被阻断（BLOCK）
    0  平台不可达（降级为"不阻断 + 打印原因"）——除非显式 --fail-closed

为什么"平台不可达时不阻断"是刻意的设计（docs/09 §9.5）：
治理门禁挂死在网络或平台故障上，会让团队直接把它从流水线里删掉；
但降级必须是**显式可见**的（这里会打印醒目提示），而不是静默跳过。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("需要 PyYAML：pip install pyyaml", file=sys.stderr)
    raise SystemExit(2)

RESET, RED, YELLOW, GREEN, BOLD = "\033[0m", "\033[91m", "\033[93m", "\033[92m", "\033[1m"


def load_contract(path: Path) -> dict:
    if not path.exists():
        print(f"{RED}契约文件不存在：{path}{RESET}", file=sys.stderr)
        raise SystemExit(2)
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        print(f"{RED}契约文件顶层应为映射：{path}{RESET}", file=sys.stderr)
        raise SystemExit(2)
    return document


def call_gate(base_url: str, token: str | None, payload: dict, timeout: int = 60) -> dict:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/v1/contracts/ci-check",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="契约与治理 CI 门禁")
    parser.add_argument("--contract", required=True, help="契约文件（YAML/JSON，ODCS 兼容）")
    parser.add_argument("--namespace", default="prod")
    parser.add_argument("--base-url", default=os.environ.get("DG_API_BASE_URL", "http://127.0.0.1:8081"))
    parser.add_argument("--token", default=os.environ.get("DG_API_TOKEN"))
    parser.add_argument("--source", default=os.environ.get("DG_CI_SOURCE", "cli"),
                        help="调用来源标识（github / gitlab / jenkins / cli）")
    parser.add_argument("--allow-breaking", action="store_true",
                        help="允许破坏性变更（需要 PR 里已评审；判定仍会留痕）")
    parser.add_argument("--require-owner", action="store_true")
    parser.add_argument("--require-description", action="store_true")
    parser.add_argument("--require-classification", action="store_true")
    parser.add_argument("--require-no-undetected-consumers", action="store_true")
    parser.add_argument("--fail-closed", action="store_true",
                        help="平台不可达时判为失败（默认：不阻断 + 明确提示）")
    parser.add_argument("--report-file", default=None,
                        help="把结论写成 Markdown 报告（供 PR 评论 / Job Summary 使用）")
    parser.add_argument("--json-out", default=None,
                        help="把平台返回的原始结论写成 JSON（供后续步骤消费）")
    args = parser.parse_args(argv)

    document = load_contract(Path(args.contract))
    payload = {
        "document": document,
        "namespace": args.namespace,
        "allowBreaking": args.allow_breaking,
        "source": args.source,
        "policy": {
            "requireOwner": args.require_owner,
            "requireDescription": args.require_description,
            "requireClassification": args.require_classification,
            "requireNoUndetectedConsumers": args.require_no_undetected_consumers,
        },
    }

    try:
        result = call_gate(args.base_url, args.token, payload)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        print(f"{RED}门禁接口返回 HTTP {exc.code}：{body[:400]}{RESET}", file=sys.stderr)
        if args.report_file:
            Path(args.report_file).write_text(
                markdown_report(None, args.contract, args.namespace, args.source,
                                degrade_reason=f"门禁接口返回 HTTP {exc.code}"), encoding="utf-8")
        return 1 if args.fail_closed else _degraded(args)
    except Exception as exc:  # 网络/超时/解析
        print(f"{YELLOW}门禁接口不可达：{exc}{RESET}", file=sys.stderr)
        if args.report_file:
            Path(args.report_file).write_text(
                markdown_report(None, args.contract, args.namespace, args.source,
                                degrade_reason=f"门禁接口不可达：{exc}"), encoding="utf-8")
        return 1 if args.fail_closed else _degraded(args)

    if args.report_file:
        Path(args.report_file).write_text(
            markdown_report(result, args.contract, args.namespace, args.source), encoding="utf-8")
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    verdict = result.get("verdict", "UNKNOWN")
    color = {"PASS": GREEN, "WARN": YELLOW, "BLOCK": RED}.get(verdict, RESET)
    print(f"{BOLD}契约 {result.get('contract')} {result.get('fromVersion')} → {result.get('toVersion')}{RESET}")
    print(f"判定：{color}{verdict}{RESET}（退出码 {result.get('exitCode')}）")

    for label, key in (("阻断", "blocking"), ("警告", "warnings"), ("通过项", "passed")):
        items = result.get(key) or []
        if items:
            print(f"\n{label}：")
            for item in items:
                print(f"  - {item}")

    diff = result.get("diff") or {}
    changes = diff.get("changes") or []
    if changes:
        print("\n变更清单：")
        for change in changes:
            print(f"  [{change.get('severity')}] {change.get('kind')}"
                  + (f" · {change.get('column')}" if change.get("column") else "")
                  + f" — {change.get('message')}")

    impact = result.get("impact") or {}
    if impact:
        print(f"\n血缘影响面：下游 {impact.get('downstreamCount')} 个"
              f"（关键 {impact.get('criticalCount')} 个）")
        for item in (impact.get("downstream") or [])[:10]:
            print(f"  - {item}")

    governance = result.get("governance") or {}
    if governance.get("missing"):
        print(f"\n治理属性缺失：{', '.join(governance['missing'])}")

    consumers = result.get("consumers") or {}
    if consumers.get("undetected"):
        print(f"\n未登记消费者（血缘上实际在用）：{len(consumers['undetected'])} 个")
        for item in consumers["undetected"][:10]:
            print(f"  - {item}")

    return int(result.get("exitCode", 1 if verdict == "BLOCK" else 0))


def markdown_report(result: dict | None, contract_path: str, namespace: str, source: str,
                    degrade_reason: str | None = None) -> str:
    """把门禁结论渲染成 Markdown（PR 评论 / Job Summary 共用同一份）。

    设计要点：**先给结论，再给证据，最后给"这次没检查什么"**。
    评论里最容易出问题的是最后一段 —— 平台不可达时如果不写清"本次改动未被检查"，
    读者会把绿色当成"检查通过"。
    """
    if result is None:
        return "\n".join([
            "## 契约与治理门禁：**未执行**",
            "",
            f"- 契约文件：`{contract_path}`（命名空间 `{namespace}`）",
            f"- 原因：{degrade_reason or '未知'}",
            "",
            "> ⚠️ **本次改动没有被检查** —— 这是刻意的降级（门禁挂死在平台故障上会被直接删掉），"
            "但绿色不代表通过。需要严格模式请在 CI 里加 `--fail-closed`。",
            "",
        ])

    verdict = result.get("verdict", "UNKNOWN")
    icon = {"PASS": "✅", "WARN": "⚠️", "BLOCK": "⛔"}.get(verdict, "❔")
    lines = [
        f"## 契约与治理门禁：{icon} {verdict}",
        "",
        f"- 契约：`{result.get('contract')}` {result.get('fromVersion')} → {result.get('toVersion')}",
        f"- 命名空间：`{namespace}` · 来源：`{source}`",
        f"- 退出码：`{result.get('exitCode')}`",
        "",
    ]
    for label, key in (("阻断项", "blocking"), ("警告", "warnings"), ("通过项", "passed")):
        items = result.get(key) or []
        if items:
            lines.append(f"### {label}（{len(items)}）")
            lines.extend(f"- {item}" for item in items)
            lines.append("")

    changes = (result.get("diff") or {}).get("changes") or []
    if changes:
        lines.append(f"### 变更清单（{len(changes)}）")
        lines.append("")
        lines.append("| 严重度 | 类型 | 列 | 说明 |")
        lines.append("|---|---|---|---|")
        for change in changes:
            lines.append(f"| {change.get('severity')} | {change.get('kind')} | "
                         f"{change.get('column') or '—'} | {change.get('message')} |")
        lines.append("")

    impact = result.get("impact") or {}
    if impact:
        lines.append(f"### 血缘影响面：下游 {impact.get('downstreamCount')} 个"
                     f"（关键 {impact.get('criticalCount')} 个）")
        lines.append("")
        lines.extend(f"- {item}" for item in (impact.get("downstream") or [])[:10])
        lines.append("")

    governance = result.get("governance") or {}
    if governance.get("missing"):
        lines.append(f"### 治理属性缺失")
        lines.append("")
        lines.append(", ".join(f"`{item}`" for item in governance["missing"]))
        lines.append("")

    consumers = result.get("consumers") or {}
    if consumers.get("undetected"):
        lines.append(f"### 未登记消费者（血缘上实际在用）：{len(consumers['undetected'])} 个")
        lines.append("")
        lines.extend(f"- `{item}`" for item in consumers["undetected"][:10])
        lines.append("")

    lines.append("---")
    lines.append("")
    lines.append("判定依据来自平台（契约兼容性 + 血缘影响面 + 治理属性齐备），"
                 "不是本地静态检查；判定记录已留痕，可在「治理 → 数据契约 → CI 历史」查询。")
    return "\n".join(lines)


def _degraded(args) -> int:
    print(f"{YELLOW}{BOLD}降级为「不阻断 + 记录」：门禁未执行，本次改动未被检查。{RESET}")
    print("  这是刻意的：门禁挂死在平台故障上，团队会直接把它从流水线里删掉。")
    print("  需要严格模式请加 --fail-closed。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
