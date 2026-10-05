"""第三方依赖与供应链审计（NFR-SEC-01 的落地工具，见 docs/23）。

三件事，缺一不可：
  1. **清点**：把真正进入运行时的依赖列全（Maven 走 `dependency:tree` 的 compile/runtime 范围，
     npm 走 `pnpm list --prod`，Python 参考实现走 requirements/pyproject）；
  2. **扫描**：用 **OSV**（osv.dev，公开 API，同时覆盖 GitHub Advisory / NVD / GHSA 生态）
     批量查询已知漏洞 —— 不依赖任何需要 NVD API key 或本地库的重型扫描器；
  3. **判定**：按 `security/dependency-waivers.yaml` 的豁免登记与 NFR 的阈值给出
     PASS / FAIL / **UNVERIFIED**。

**最重要的设计纪律：扫描没跑成 ≠ 没有漏洞。**
网络不可达、扫描器缺失、依赖清点为空，都必须返回 UNVERIFIED 并让 CI 失败，
绝不允许输出一个"看起来干净"的报告 —— 那正是供应链安全里最危险的产物。

用法：
  python tools/dependency_audit.py                 # 清点 + 扫描 + 判定（CI 用，返回退出码）
  python tools/dependency_audit.py --inventory-only # 只清点（离线可用）
  python tools/dependency_audit.py --json          # 额外输出机器可读报告
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
WAIVER_FILE = REPO_ROOT / "security" / "dependency-waivers.yaml"
REPORT_DIR = REPO_ROOT / "security" / "reports"

# NFR-SEC-01：运行时依赖不允许出现的严重度（有发现必须修、换、或登记豁免）
BLOCKING_SEVERITIES = {"CRITICAL", "HIGH"}
# 扫描失败的退出码（CI 语义）：与"发现漏洞"区分开，便于流水线给出不同提示
EXIT_OK, EXIT_FINDINGS, EXIT_UNVERIFIED = 0, 1, 2


# --------------------------------------------------------------------------- 清点


def run_tool(args: list[str], cwd: Path) -> tuple[int, str]:
    """调用外部工具。

    Windows 上 `mvn` / `pnpm` 是 `.cmd` 包装脚本，`subprocess` 直接执行会 WinError 2，
    因此这里显式解析真实可执行文件，并在必要时走 shell。
    """
    executable = shutil.which(args[0])
    if executable is None:
        return 127, f"找不到可执行文件：{args[0]}"
    command = [executable, *args[1:]]
    try:
        proc = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=900,
                              shell=os.name == "nt")
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 1, f"执行失败：{exc}"
    return proc.returncode, proc.stdout or ""


def maven_inventory() -> tuple[list[dict], str | None]:
    """从 dg-api 的依赖树取运行时依赖（dg-api 依赖其余全部模块，因此它就是完整运行时集）。

    不加 `-o`：升级依赖后本地仓库不一定齐，离线模式会**静默少列依赖** ——
    而"漏清的依赖"会让报告显得比实际干净，这正是本工具最不能接受的失败方式。
    """
    code, output = run_tool(
        ["mvn", "-B", "dependency:tree", "-pl", "dg-api", "-DoutputType=text"],
        REPO_ROOT / "backend")
    if code != 0:
        return [], f"mvn dependency:tree 退出码 {code}"
    deps: dict[str, dict] = {}
    pattern = re.compile(r"([\w.\-]+):([\w.\-]+):(?:jar|pom):([\w.\-.]+):(\w+)")
    for line in output.splitlines():
        match = pattern.search(line)
        if not match:
            continue
        group, artifact, version, scope = match.groups()
        # 只算**运行时**依赖：test/provided 不进产物，但仍在报告里单列（见 scope 字段）
        deps[f"{group}:{artifact}"] = {
            "ecosystem": "Maven",
            "name": f"{group}:{artifact}",
            "version": version,
            "scope": scope,
            "direct": line.lstrip("[INFO] ").startswith("+-"),
        }
    if not deps:
        return [], "mvn dependency:tree 没有解析出任何依赖"
    return sorted(deps.values(), key=lambda item: item["name"]), None


def npm_inventory() -> tuple[list[dict], str | None]:
    """生产依赖（含传递依赖）：`pnpm list --prod --depth=Infinity`。"""
    code, output = run_tool(["pnpm", "list", "--prod", "--json", "--depth=Infinity"],
                            REPO_ROOT / "web")
    if code != 0:
        return [], f"pnpm list 退出码 {code}"
    try:
        payload = json.loads(output)
    except json.JSONDecodeError as exc:
        return [], f"pnpm list 输出不是合法 JSON：{exc}"

    deps: dict[str, dict] = {}
    for project in payload if isinstance(payload, list) else [payload]:
        for section in ("dependencies",):
            for name, info in (project.get(section) or {}).items():
                _walk_npm(name, info, deps, dev=False)
    if not deps:
        return [], "pnpm list 没有解析出任何生产依赖"
    return sorted(deps.values(), key=lambda item: item["name"]), None


def _walk_npm(name: str, info: dict, out: dict, dev: bool) -> None:
    if not isinstance(info, dict):
        return
    version = str(info.get("version", "")).lstrip("^~")
    if version:
        key = f"{name}@{version}"
        out.setdefault(key, {
            "ecosystem": "npm", "name": name, "version": version,
            "scope": "dev" if dev else "runtime", "direct": False,
        })
    for child_name, child in (info.get("dependencies") or {}).items():
        _walk_npm(child_name, child, out, dev)


def python_inventory() -> tuple[list[dict], str | None]:
    """Python 参考实现与侧车的依赖（pyproject.toml 的依赖声明）。

    声明里只有版本区间（如 `>=1.0`）时**无法**做精确漏洞匹配，因此这里同时读取
    **实际安装版本**（`importlib.metadata`）来扫描：只看区间等于没扫。
    """
    from importlib import metadata

    deps: dict[str, dict] = {}
    pyproject = REPO_ROOT / "pyproject.toml"
    if not pyproject.exists():
        return [], "找不到 pyproject.toml"
    text = pyproject.read_text(encoding="utf-8")
    in_deps = False
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("dependencies"):
            in_deps = True
            continue
        if in_deps and line.startswith("]"):
            break
        if not in_deps or not line.startswith('"'):
            continue
        spec = line.strip('",')
        match = re.match(r"([\w.\-]+)\s*(?:[<>=!~]=?\s*([\w.\-]+))?", spec)
        if not match:
            continue
        name, _ = match.groups()
        try:
            installed = metadata.version(name)
        except metadata.PackageNotFoundError:
            installed = ""
        deps[name] = {"ecosystem": "PyPI", "name": name, "version": installed,
                      "scope": "runtime", "direct": True}
    return sorted(deps.values(), key=lambda item: item["name"]), None


# --------------------------------------------------------------------------- 扫描


def osv_query(deps: list[dict]) -> tuple[list[dict], str | None]:
    """批量查询 OSV 并补齐漏洞详情。返回 (发现列表, 错误说明)。

    `/v1/querybatch` 只返回 `{id, modified}`，严重度与修复版本必须再按 ID 取详情
    （`/v1/vulns/{id}`）。因此这里两步走：批量定位 → 按 ID 去重回源。
    """
    queries = []
    index = []
    for dep in deps:
        if not dep.get("version") or dep.get("scope") == "test":
            continue
        if dep["ecosystem"] == "Maven":
            group, _, artifact = dep["name"].partition(":")
            pkg = {"ecosystem": "Maven", "name": f"{group}:{artifact}"}
        else:
            pkg = {"ecosystem": dep["ecosystem"], "name": dep["name"]}
        queries.append({"package": pkg, "version": dep["version"]})
        index.append(dep)

    matches: list[tuple[dict, str]] = []
    for start in range(0, len(queries), 500):
        chunk = queries[start:start + 500]
        body = json.dumps({"queries": chunk}).encode()
        request = urllib.request.Request(OSV_BATCH_URL, data=body,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            return [], f"OSV 批量查询失败：{exc}"

        for offset, result in enumerate(payload.get("results", [])):
            dep = index[start + offset]
            for vuln in result.get("vulns", []) or []:
                matches.append((dep, vuln.get("id")))

    if not matches:
        return [], None

    details, error = fetch_vuln_details({vuln_id for _, vuln_id in matches})
    if error:
        return [], error

    findings = []
    for dep, vuln_id in matches:
        vuln = details.get(vuln_id) or {}
        findings.append({
            "ecosystem": dep["ecosystem"],
            "package": dep["name"],
            "version": dep["version"],
            "scope": dep.get("scope", "runtime"),
            "id": vuln_id,
            "aliases": vuln.get("aliases", []),
            "summary": (vuln.get("summary") or "").strip(),
            "severity": severity_of(vuln),
            "severitySource": severity_source(vuln),
            "fixedIn": fixed_versions(vuln, dep),
            "affectedPackages": affected_packages(vuln),
            "url": f"https://osv.dev/vulnerability/{vuln_id}",
        })
    return findings, None


def fetch_vuln_details(ids: set[str]) -> tuple[dict[str, dict], str | None]:
    """按 ID 取漏洞详情（并发 6，避免把公开 API 打爆）。"""
    from concurrent.futures import ThreadPoolExecutor

    def fetch(vuln_id: str) -> tuple[str, dict | None, str | None]:
        request = urllib.request.Request(f"https://api.osv.dev/v1/vulns/{vuln_id}")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return vuln_id, json.loads(response.read().decode("utf-8")), None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            return vuln_id, None, f"{vuln_id}: {exc}"

    details: dict[str, dict] = {}
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        for vuln_id, payload, problem in pool.map(fetch, sorted(ids)):
            if payload is not None:
                details[vuln_id] = payload
            elif problem:
                errors.append(problem)
    if errors:
        # 详情取不全时，严重度与修复版本都不可信 → 整个扫描判定为"未完成"
        return details, f"有 {len(errors)} 条漏洞详情获取失败（示例：{errors[0]}）"
    return details, None


# CVSS v3.1 基础分：OSV 里给的是**向量**而不是分数，必须自己算，否则严重度只能靠猜
_CVSS_V3 = {
    "AV": {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2},
    "AC": {"L": 0.77, "H": 0.44},
    "PR_U": {"N": 0.85, "L": 0.62, "H": 0.27},
    "PR_C": {"N": 0.85, "L": 0.68, "H": 0.5},
    "UI": {"N": 0.85, "R": 0.62},
    "CIA": {"H": 0.56, "L": 0.22, "N": 0.0},
}


def cvss_v3_base_score(vector: str) -> float | None:
    """按 CVSS v3.x 规范计算基础分（roundup 到一位小数）。"""
    parts = dict(
        item.split(":", 1) for item in vector.split("/")[1:] if ":" in item
    )
    try:
        scope_changed = parts["S"] == "C"
        av = _CVSS_V3["AV"][parts["AV"]]
        ac = _CVSS_V3["AC"][parts["AC"]]
        pr = _CVSS_V3["PR_C" if scope_changed else "PR_U"][parts["PR"]]
        ui = _CVSS_V3["UI"][parts["UI"]]
        impact_sub = 1 - (1 - _CVSS_V3["CIA"][parts["C"]]) * (1 - _CVSS_V3["CIA"][parts["I"]]) \
            * (1 - _CVSS_V3["CIA"][parts["A"]])
    except KeyError:
        return None
    if impact_sub <= 0:
        return 0.0
    if scope_changed:
        impact = 7.52 * (impact_sub - 0.029) - 3.25 * (impact_sub - 0.02) ** 15
    else:
        impact = 6.42 * impact_sub
    exploitability = 8.22 * av * ac * pr * ui
    raw = min((impact + exploitability) * (1.08 if scope_changed else 1.0), 10.0)
    # CVSS 规范的 roundup：向上取整到一位小数（不能用四舍五入）
    return math.ceil(raw * 10) / 10


def severity_from_score(score: float) -> str:
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    return "LOW"


def severity_of(vuln: dict) -> str:
    """严重度：优先用数据库自带分级（GHSA 的 MODERATE/HIGH 等），否则算 CVSS 向量。

    两者都没有时返回 UNKNOWN —— **不猜**。UNKNOWN 会进入人工复核清单。
    """
    level = str((vuln.get("database_specific") or {}).get("severity") or "").upper()
    if level in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "MODERATE"}:
        return "MEDIUM" if level == "MODERATE" else level
    level = str((vuln.get("ecosystem_specific") or {}).get("severity") or "").upper()
    if level in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "MODERATE"}:
        return "MEDIUM" if level == "MODERATE" else level
    for item in vuln.get("severity", []) or []:
        vector = item.get("score") or ""
        if vector.startswith("CVSS:3"):
            score = cvss_v3_base_score(vector)
            if score is not None:
                return severity_from_score(score)
    return "UNKNOWN"


def severity_source(vuln: dict) -> str:
    if (vuln.get("database_specific") or {}).get("severity"):
        return "database_specific"
    for item in vuln.get("severity", []) or []:
        if str(item.get("score", "")).startswith("CVSS:3"):
            return "cvss_v3_vector"
        if str(item.get("score", "")).startswith("CVSS:4"):
            return "cvss_v4_vector(未计算，回退 UNKNOWN)"
    return "none"


def affected_packages(vuln: dict) -> list[str]:
    return sorted({
        f"{item['package'].get('ecosystem')}:{item['package'].get('name')}"
        for item in vuln.get("affected", []) or []
        if item.get("package")
    })


def fixed_versions(vuln: dict, dep: dict) -> list[str]:
    """该依赖可升级到的修复版本。

    注意：OSV 对 Maven 生态常把漏洞记在**同项目的另一个构件**上
    （例如查 `tomcat-embed-core` 命中 `org.apache.tomcat:tomcat-catalina` 的记录），
    因此这里不按构件名过滤 —— 同一版本线的修复版本号是通用的，按名字过滤会得到"没有修复版本"的假象。
    """
    fixed: list[str] = []
    for affected in vuln.get("affected", []) or []:
        for rng in affected.get("ranges", []) or []:
            if rng.get("type") not in (None, "ECOSYSTEM"):
                continue
            for event in rng.get("events", []) or []:
                if "fixed" in event:
                    fixed.append(str(event["fixed"]))
    return sorted(set(fixed), key=version_key)[:5]


def version_key(value: str) -> tuple:
    """把版本号变成可比较的元组（够用即可：数字段 + 预发布后缀排在后面）。"""
    parts = re.findall(r"\d+", value)
    return tuple(int(item) for item in parts) + ((1,) if "-" not in value else (0,))


# --------------------------------------------------------------------------- 豁免

SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]


def severity_rank(severity: str) -> int:
    return SEVERITY_ORDER.index(severity) if severity in SEVERITY_ORDER else len(SEVERITY_ORDER)


def load_waivers() -> list[dict]:
    """读取豁免登记（YAML）。未登记的漏洞不接受任何豁免 —— 这是纪律的一部分。"""
    if not WAIVER_FILE.exists():
        return []
    import yaml

    payload = yaml.safe_load(WAIVER_FILE.read_text(encoding="utf-8")) or {}
    return payload.get("waivers", []) or []


def waiver_for(finding: dict, waivers: list[dict]) -> tuple[dict | None, str | None]:
    """返回 (生效的豁免, 失效原因)。过期/字段不全的豁免不算生效 —— 且要说明原因。"""
    import datetime as dt

    for waiver in waivers:
        if waiver.get("id") != finding.get("id"):
            continue
        if waiver.get("package") and finding["package"] not in str(waiver["package"]):
            continue
        missing = [key for key in ("reason", "owner", "expires") if not waiver.get(key)]
        if missing:
            return None, f"豁免记录字段不全（缺 {'/'.join(missing)}）"
        try:
            expires = dt.date.fromisoformat(str(waiver["expires"]))
        except ValueError:
            return None, f"豁免到期日格式不合法：{waiver['expires']}"
        if expires < dt.date.today():
            return None, f"豁免已于 {expires} 到期"
        return waiver, None
    return None, None


# --------------------------------------------------------------------------- 主流程


def self_test() -> int:
    """自检：用**已知有漏洞**的版本验证扫描链路真的在工作。

    为什么必须有它：报告"0 条漏洞"有两种可能 —— 真的干净，或者扫描器坏了。
    两种情况下人看到的东西一模一样，因此必须有一个反向用例把"扫描器没工作"钉死。
    这里的样本是历史上有名的版本，若查不出东西，说明 OSV 链路/解析逻辑已失效。
    """
    probes = [
        {"ecosystem": "Maven", "name": "com.fasterxml.jackson.core:jackson-databind",
         "version": "2.21.4", "scope": "runtime"},
        {"ecosystem": "npm", "name": "react-router", "version": "6.30.6", "scope": "runtime"},
    ]
    findings, error = osv_query(probes)
    if error:
        print(f"自检失败：扫描链路不可用（{error}）")
        return EXIT_UNVERIFIED
    for probe in probes:
        hits = [f for f in findings if f["package"] == probe["name"]]
        resolved = [f for f in hits if f["severity"] != "UNKNOWN"]
        if not hits or not resolved:
            print(f"自检失败：{probe['name']}@{probe['version']} 未检出可判定严重度的漏洞 —— "
                  f"扫描链路或严重度解析已失效（检出 {len(hits)} 条，可判定 {len(resolved)} 条）")
            return EXIT_UNVERIFIED
        print(f"自检通过：{probe['name']}@{probe['version']} 检出 {len(hits)} 条"
              f"（示例 {hits[0]['id']} / {hits[0]['severity']}）")
    print("自检结论：扫描链路与严重度解析均有效，'0 条漏洞' 才是可信的。")
    return EXIT_OK


def main() -> int:
    parser = argparse.ArgumentParser(description="第三方依赖与供应链审计（NFR-SEC-01）")
    parser.add_argument("--inventory-only", action="store_true", help="只清点，不联网扫描")
    parser.add_argument("--self-test", action="store_true",
                        help="自检：验证扫描链路能检出已知漏洞（防止'扫描器坏了'被误读成'干净'）")
    parser.add_argument("--json", action="store_true", help="输出机器可读报告 JSON")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    collected = {
        "maven": maven_inventory(),
        "npm": npm_inventory(),
        "pypi": python_inventory(),
    }
    inventory = {key: value for key, (value, _) in collected.items()}
    inventory_errors = {
        key: problem for key, (_, problem) in collected.items() if problem
    }
    counts = {key: len(value) for key, value in inventory.items()}
    print(f"依赖清点：Maven {counts['maven']} · npm {counts['npm']} · PyPI {counts['pypi']}")
    for key, problem in inventory_errors.items():
        print(f"  ⚠ {key} 清点失败：{problem}")

    if args.inventory_only:
        runtime = [d for group in inventory.values() for d in group if d.get("scope") != "test"]
        if args.json:
            print(json.dumps({"inventory": inventory, "runtimeCount": len(runtime),
                              "inventoryErrors": inventory_errors},
                             ensure_ascii=False, indent=2))
        return EXIT_OK if not inventory_errors else EXIT_UNVERIFIED

    if not any(inventory.values()):
        print("依赖清点为空：无法进行扫描（**不视为通过**）")
        return EXIT_UNVERIFIED

    # 任何一路清点失败都必须判定为"未完成"：只扫到一半依赖的报告，
    # 看起来会比真实情况干净 —— 这就是供应链安全里最危险的产物。
    if inventory_errors:
        detail = "；".join(f"{key}: {problem}" for key, problem in inventory_errors.items())
        print(f"\n⚠ 依赖清点不完整，扫描结果不可用于合规结论：{detail}")
        write_report(counts, 0, [], 0, 0, f"依赖清点不完整：{detail}")
        return EXIT_UNVERIFIED

    runtime_deps = [d for group in inventory.values() for d in group if d.get("scope") != "test"]
    findings, error = osv_query(runtime_deps)

    waivers = load_waivers()
    blocking: list[dict] = []
    waived: list[dict] = []
    informational: list[dict] = []
    for finding in findings:
        waiver, problem = waiver_for(finding, waivers)
        if waiver:
            finding["waiver"] = waiver
            waived.append(finding)
        elif problem:
            # 登记了豁免但已过期/不完整 → 依然阻断，并把原因写明
            finding["waiverProblem"] = problem
            blocking.append(finding)
        elif finding["severity"] in BLOCKING_SEVERITIES:
            blocking.append(finding)
        else:
            informational.append(finding)

    print(f"\n扫描：{len(findings)} 条已知漏洞（阻断 {len(blocking)} / 已豁免 {len(waived)} / "
          f"中低风险 {len(informational)}）")
    for item in sorted(findings, key=lambda x: severity_rank(x["severity"])):
        mark = "BLOCK" if item in blocking else ("WAIVED" if item in waived else "info")
        fix = ",".join(item["fixedIn"][:3]) or "无修复版本"
        print(f"  [{mark:6}] {item['severity']:8} {item['package']}@{item['version']} "
              f"{item['id']} → 修复版本 {fix}")
        if item.get("summary"):
            print(f"           {item['summary'][:100]}")
        if item.get("waiverProblem"):
            print(f"           豁免无效：{item['waiverProblem']}")

    if error:
        print(f"\n⚠ 扫描未完成：{error}")
        print("   **未完成 ≠ 没有漏洞**：本结果不得作为合规证据，请恢复网络后重跑。")

    write_report(counts, len(runtime_deps), findings, len(blocking), len(waived), error)

    if args.json:
        print(json.dumps({
            "inventoryCounts": counts, "runtimeDependencyCount": len(runtime_deps),
            "findings": findings, "blocking": len(blocking), "waived": len(waived),
            "scanError": error,
        }, ensure_ascii=False, indent=2))

    if error:
        return EXIT_UNVERIFIED
    return EXIT_FINDINGS if blocking else EXIT_OK


def write_report(counts: dict, runtime_count: int, findings: list[dict], blocking: int,
                 waived: int, error: str | None, extra: dict | None = None) -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    report = {
        "generatedAt": __import__("datetime").datetime.now().astimezone().isoformat(timespec="seconds"),
        "inventoryCounts": counts,
        "runtimeDependencyCount": runtime_count,
        "findings": findings,
        "blocking": blocking,
        "waived": waived,
        "scanError": error,
    }
    if extra:
        report.update(extra)
    (REPORT_DIR / "dependency-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n报告已写入：security/reports/dependency-audit.json")


if __name__ == "__main__":
    raise SystemExit(main())
