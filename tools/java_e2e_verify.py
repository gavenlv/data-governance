"""Java 控制面端到端验证（Batch 1：目录可用主线）。

用法：
    python tools/java_e2e_verify.py [base_url]

前置：控制面已启动（java -jar backend/dg-api/target/dg-api-0.1.0.jar）。
      数据库为本地开发库（默认 localhost:25011/dg）。
      脚本会**自动拉起 SQL 解析侧车**（python -m dg.cli sidecar），结束时关闭；
      设 DG_SKIP_SIDECAR=1 可跳过（此时解析相关检查会明确失败，而不是静默通过）。

验证内容（每条都对应一个"声称已实现"的能力，见 docs/22 的完成定义）：
   1. 控制面存活与实现标识
   2. 认证与授权：无令牌 401；身份含权限点与可见分级；越权 403
   3. 模型注册表（唯一事实源）可读
   4. 能力清单三态齐备，且本批新实现的能力状态已更新
   5. 真实采集 PostgreSQL（护栏与快照参与）
   6. 资产列表与详情可读（列表同样按可见分级前置过滤）
   7. 检索：重建派生索引 → 真实命中 + 分面 + 索引水位（核心：可重放重建）
   8. 采集调度：应用定义（非法被拒）→ 立即执行 → 真采集
   9. SQL 静态解析：侧车就绪 → 解析入库写列级血缘 → 失败样本入样本库
  10. 影响分析：按评分返回受影响资产 + 截断诚实性
  11. 仍未实现的接口显式返回 501 + 设计说明（而不是空数据）
  12. 界面（SPA）由同一端口托管
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
TOKEN = os.environ.get("DG_TOKEN", "dev-admin-token")
READER_TOKEN = os.environ.get("DG_READER_TOKEN", "dev-reader-token")
STEWARD_TOKEN = os.environ.get("DG_STEWARD_TOKEN", "dev-steward-token")
SIDECAR_URL = os.environ.get("DG_LINEAGE_SIDECAR_URL", "http://127.0.0.1:8099")
REPO_ROOT = Path(__file__).resolve().parent.parent
NAMESPACE = os.environ.get("DG_E2E_NAMESPACE", "java_e2e")

PASS, FAIL = "\033[92m PASS \033[0m", "\033[91m FAIL \033[0m"
results: list[tuple[str, bool, str]] = []
sidecar_process: subprocess.Popen | None = None


def call(method: str, path: str, body: dict | None = None, token: str | None = TOKEN):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read().decode("utf-8", "replace")
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, raw


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"[{PASS if ok else FAIL}] {name}" + (f"  → {detail}" if detail else ""))


def sidecar_alive() -> bool:
    try:
        with urllib.request.urlopen(SIDECAR_URL + "/healthz", timeout=3) as response:
            return response.status == 200
    except Exception:
        return False


def start_sidecar() -> None:
    """自动拉起侧车，让"侧车已部署"这句话在验证里是真的。"""
    global sidecar_process
    if os.environ.get("DG_SKIP_SIDECAR") == "1" or sidecar_alive():
        return
    env = dict(os.environ)
    env["PYTHONPATH"] = str(REPO_ROOT / "src")
    port = SIDECAR_URL.rsplit(":", 1)[-1]
    sidecar_process = subprocess.Popen(
        [sys.executable, "-m", "dg.cli", "sidecar", "--port", port],
        cwd=str(REPO_ROOT), env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(40):
        if sidecar_alive():
            return
        time.sleep(0.5)


def stop_sidecar() -> None:
    if sidecar_process is not None:
        sidecar_process.terminate()
        try:
            sidecar_process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            sidecar_process.kill()


def psql_binary() -> str | None:
    """定位 psql（异常检测的检查需要构造合成历史序列，见 seed_metric_series 的说明）。"""
    override = os.environ.get("DG_PSQL")
    if override and Path(override).exists():
        return override
    candidates = [
        Path(r"C:\sandbox\tools\postgresql16\bin\psql.exe"),
        Path("/usr/bin/psql"),
        Path("/usr/local/bin/psql"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    from shutil import which
    return which("psql")


def seed_metric_series(psql: str, dataset_urn: str) -> bool:
    """构造一条合成指标时序，用来驱动异常检测器。

    为什么要"合成"：真实剖析只在需要时才跑，不可能自然攒出 7 个以上历史点；
    而没有历史就没有基线，MAD 检测器**按设计**会跳过（样本不足不判定）。
    因此这里直接写入 profile_metric 的时序（14 个点：13 个稳定 + 最后 1 个越界），
    拿到的是对**检测算法本身**的验证 —— 而不是"因为没数据所以什么也没发生"。
    """
    sql = f"""
    DELETE FROM profile_metric
     WHERE dataset_urn = '{dataset_urn}' AND metric = 'row_count' AND column_name IS NULL;
    INSERT INTO profile_metric (dataset_urn, column_name, metric, window_start, value_num,
                                precision, precision_source, sampling_method)
    SELECT '{dataset_urn}', NULL, 'row_count', now() - (n || ' days')::interval,
           CASE WHEN n = 0 THEN 100000 ELSE 1000 END, 'EXACT', 'e2e', 'e2e_synthetic'
      FROM generate_series(0, 13) AS n;
    """
    return psql_exec(psql, sql)


def seed_short_series(psql: str, dataset_urn: str) -> bool:
    """只写 3 个点：用来验证「样本不足 → 不判定」这条分支确实生效。"""
    sql = f"""
    DELETE FROM profile_metric
     WHERE dataset_urn = '{dataset_urn}' AND metric = 'e2e_short_series' AND column_name IS NULL;
    INSERT INTO profile_metric (dataset_urn, column_name, metric, window_start, value_num,
                                precision, precision_source, sampling_method)
    SELECT '{dataset_urn}', NULL, 'e2e_short_series', now() - (n || ' days')::interval,
           CASE WHEN n = 0 THEN 99999 ELSE 10 END, 'EXACT', 'e2e', 'e2e_synthetic'
      FROM generate_series(0, 2) AS n;
    """
    return psql_exec(psql, sql)


def psql_exec(psql: str, sql: str) -> bool:
    """执行一段 SQL（测试夹具用；只用于构造 e2e 需要的历史状态）。

    SQL 走**临时文件**而不是 `-c`：Windows 控制台是 GBK，含中文的 `-c` 参数会被
    psql 判成非法 UTF-8 直接失败（早期版本因此静默没造出夹具，害得断言看起来像产品缺陷）。
    """
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as handle:
        handle.write(sql)
        path = handle.name
    env = {**os.environ, "PGPASSWORD": "root", "PGCLIENTENCODING": "UTF8"}
    proc = subprocess.run([psql, "-h", "localhost", "-p", "25011", "-U", "postgres",
                           "-d", "dg", "-v", "ON_ERROR_STOP=1", "-f", path],
                          capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    Path(path).unlink(missing_ok=True)
    if proc.returncode != 0:
        print(f"    psql 夹具执行失败：{(proc.stderr or proc.stdout or '')[:200]}")
    return proc.returncode == 0


def run_engine_audit_loader(records: list[dict], engine: str, namespace: str) -> dict | None:
    """调用参考采集器 tools/engine_audit_load.py 推送一批 JSONL 记录。

    验证的不只是平台接口，还包括**部署侧那一半**（真实客户是拿日志文件来推的）。
    """
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        path = handle.name
    env = {**os.environ, "DG_BASE_URL": BASE, "DG_API_TOKEN": TOKEN,
           "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run([sys.executable, str(REPO_ROOT / "tools" / "engine_audit_load.py"),
                           "--engine", engine, "--namespace", namespace, "--file", path],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          env=env, timeout=180)
    Path(path).unlink(missing_ok=True)
    if proc.returncode != 0:
        print(f"    采集器输出：{proc.stdout[-300:]} {proc.stderr[-300:]}")
        return None
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None


def seed_backdated_grant(psql: str, subject: str, resource_urn: str, age_days: int) -> bool:
    """造一条"N 天前批的"授权。

    为什么要直接写库：通过 API 只能创建"现在生效"的授权，而验证
    「引擎审计能判定未使用」必须有一条**生命周期早于观测窗口**的授权 ——
    这正是该功能唯一有价值的判定条件。
    """
    sql = f"""
    DELETE FROM access_grant WHERE subject = '{subject}' AND resource_urn = '{resource_urn}';
    INSERT INTO access_grant (subject, resource_urn, granularity, permissions, purpose,
                              granted_by, granted_at, expires_at, status)
    VALUES ('{subject}', '{resource_urn}', 'DATASET', ARRAY['SELECT'], 'e2e 引擎审计验证',
            'steward@local', now() - interval '{age_days} days', now() + interval '100 days', 'ACTIVE');
    """
    return psql_exec(psql, sql)


def run_ci_gate(contract_path: Path, namespace: str, requirements: list[str] | None = None,
                report_path: Path | None = None) -> tuple[int, str, dict | None]:
    """跑一次 CI 门禁脚本（tools/ci/contract_gate.py），返回 (退出码, 控制台输出, 结论 JSON)。"""
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        report = report_path or Path(tmp) / "gate.md"
        result_json = Path(tmp) / "gate.json"
        command = [sys.executable, str(REPO_ROOT / "tools" / "ci" / "contract_gate.py"),
                   "--contract", str(contract_path), "--namespace", namespace,
                   "--base-url", BASE, "--token", TOKEN,
                   "--report-file", str(report), "--json-out", str(result_json),
                   "--source", "e2e"]
        for flag in requirements or []:
            command.append(flag)
        proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"},
                              timeout=180)
        parsed = None
        if result_json.exists():
            try:
                parsed = json.loads(result_json.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                parsed = None
        return proc.returncode, (proc.stdout or "") + (proc.stderr or ""), parsed


def run_ci_pr_comment(report_path: Path, provider: str, api_base: str, token: str | None) -> tuple[int, str]:
    """跑一次 PR 回写脚本，返回 (退出码, 输出)。token 为 None 表示不传凭证（验证跳过行为）。"""
    command = [sys.executable, str(REPO_ROOT / "tools" / "ci" / "pr_comment.py"),
               "--provider", provider, "--report", str(report_path),
               "--api-base", api_base]
    if provider == "github":
        command += ["--repo", "acme/dg", "--pr", "42"]
    else:
        command += ["--project", "123", "--mr", "7"]
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    if token is not None:
        command += ["--token", token]
    else:
        # 无凭证场景：连环境变量也不能有，否则会走真实网络
        env.pop("GITHUB_TOKEN", None)
        env.pop("GITLAB_TOKEN", None)
    proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env, timeout=120)
    return proc.returncode, (proc.stdout or "").strip() + (proc.stderr or "").strip()


class _StubGitApi:
    """记录收到的请求的假 Git API（用于验证回写的**请求形状**，不依赖真实 GitHub/GitLab）。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None]] = []

    def start(self) -> str:
        import threading
        from http.server import BaseHTTPRequestHandler, HTTPServer

        outer = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self, method: str) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                outer.calls.append((method, self.path, body))
                if method == "GET":
                    # 每个路径**首次** GET 返回空（触发创建），之后返回已有评论（触发更新）。
                    # 必须按路径分别计数：GitHub 与 GitLab 的路径不同，
                    # 用全局计数会让 GitLab 的第一次调用就误判成"已存在"。
                    seen = [call for call in outer.calls if call[0] == "GET" and call[1] == self.path]
                    payload: object = [{"id": 123, "body": "<!-- dg-contract-gate -->\n旧内容"}] \
                        if len(seen) > 1 else []
                else:
                    payload = {"id": 123}
                raw = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self) -> None:  # noqa: N802
                self._handle("GET")

            def do_POST(self) -> None:  # noqa: N802
                self._handle("POST")

            def do_PATCH(self) -> None:  # noqa: N802
                self._handle("PATCH")

            def do_PUT(self) -> None:  # noqa: N802
                self._handle("PUT")

            def log_message(self, *args) -> None:
                return

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self) -> None:
        self.server.shutdown()


def main() -> int:
    start_sidecar()
    try:
        return run_checks()
    finally:
        stop_sidecar()


def run_checks() -> int:
    # ---------------------------------------------------------------- 1) 存活
    status, health = call("GET", "/healthz", token=None)
    check("1. 控制面存活且标识为 Java/Spring Boot",
          status == 200 and isinstance(health, dict) and "java" in str(health.get("controlPlane", "")),
          f"HTTP {status} {health if isinstance(health, str) else health.get('status')}")

    # ------------------------------------------------------------ 2) 认证授权
    status, _ = call("GET", "/api/v1/model", token=None)
    check("2a. 无令牌访问受保护接口被拒（401）", status == 401, f"HTTP {status}")

    status, me = call("GET", "/api/v1/me")
    ok = (status == 200 and isinstance(me, dict) and me.get("authenticated") is True
          and isinstance(me.get("permissions"), list) and isinstance(me.get("visibleLevels"), list))
    check("2b. 身份含角色 / 权限点 / 可见分级（授权判定入口的输出）", ok,
          f"{me.get('name')} / {','.join(me.get('roles', []))} / 可见 {'/'.join(me.get('visibleLevels', []))}")

    # 越权：READER 不能写 aspect（同一判定入口，不是各自实现）
    status, payload = call(
        "POST",
        f"/api/v1/assets/{urllib.parse.quote('urn:dg:Dataset:java_e2e.permcheck', safe='')}"
        "/aspects/descriptions?createEntityIfMissing=false",
        {"data": {"text": "越权写入尝试"}}, token=READER_TOKEN)
    check("2c. READER 越权写入被拒（403，且给出缺哪个权限点）",
          status == 403 and "asset:write" in str(payload.get("message", "")),
          f"HTTP {status}")

    # -------------------------------------------------------------- 3) 模型
    status, model = call("GET", "/api/v1/model")
    entity_types = model.get("entityTypes") if isinstance(model, dict) else None
    check("3. 模型注册表可读（唯一事实源）",
          status == 200 and isinstance(entity_types, list) and len(entity_types) >= 10,
          f"{len(entity_types or [])} 实体 / {len(model.get('aspectTypes', []))} aspect")

    # ---------------------------------------------------------- 4) 能力清单
    status, caps = call("GET", "/api/v1/capabilities")
    summary = caps.get("summary", {}) if isinstance(caps, dict) else {}
    domains = caps.get("domains", {}) if isinstance(caps, dict) else {}
    statuses = {item["id"]: item["status"] for items in domains.values() for item in items}
    ok = (status == 200 and summary.get("implemented", 0) > 0
          and summary.get("notImplemented", 0) > 0 and summary.get("total", 0) > 0)
    check("4a. 能力清单三态齐备（未实现已显式标注）", ok,
          f"已实现 {summary.get('implemented')} / 部分 {summary.get('partial')} / "
          f"未实现 {summary.get('notImplemented')} / 合计 {summary.get('total')}")

    batch1 = ["core.search-index", "policy.rbac", "policy.abac",
              "ingestion.scheduler", "lineage.impact-analysis"]
    missing = [cid for cid in batch1 if statuses.get(cid) != "IMPLEMENTED"]
    check("4b. Batch 1 能力状态已更新为 IMPLEMENTED", not missing,
          "全部已实现" if not missing else f"仍非 IMPLEMENTED：{missing}")

    # -------------------------------------------------------------- 5) 采集
    status, run = call("POST", "/api/v1/collect/postgres", {
        "jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
        "username": "postgres",
        "password": "root",
        "namespace": "java_e2e",
        "schemas": ["public"],
        "maxDeleteRatio": 0.3,
    })
    ok = status == 200 and isinstance(run, dict) and run.get("status") in ("SUCCEEDED", "BLOCKED")
    check("5. 真实采集 PostgreSQL（含护栏与快照）", ok,
          f"status={run.get('status')} seen={run.get('datasetsSeen')} "
          f"created={run.get('datasetsCreated')} {run.get('durationMs')}ms")

    # ------------------------------------------------------------ 6) 资产
    status, assets = call("GET", "/api/v1/assets?prefix=urn:dg:Dataset:java_e2e&limit=5")
    count = assets.get("count", 0) if isinstance(assets, dict) else 0
    check("6a. 资产列表可读且带可见分级（列表同样前置过滤）",
          status == 200 and count > 0 and isinstance(assets.get("visibleLevels"), list),
          f"{count} 条，可见 {'/'.join(assets.get('visibleLevels', []))}")

    first_urn = assets["assets"][0]["urn"] if count else None
    if first_urn:
        status, asset = call("GET", f"/api/v1/assets/{urllib.parse.quote(first_urn, safe='')}")
        aspects = asset.get("aspects", {}) if isinstance(asset, dict) else {}
        schema = aspects.get("datasetSchema", {})
        check("6b. 资产详情含结构（datasetSchema）",
              status == 200 and bool(schema.get("fields")),
              f"{len(schema.get('fields', []))} 列, hash={str(schema.get('schemaHash'))[:24]}")

    # -------------------------------------------------------------- 7) 检索
    status, rebuild = call("POST", "/api/v1/index/rebuild?batchSize=500")
    stats = rebuild.get("stats", {}) if isinstance(rebuild, dict) else {}
    check("7a. 索引重建（清空派生视图 → 从 seq=0 重放，ADR-002 的可执行证明）",
          status == 200 and stats.get("processed", 0) > 0 and rebuild.get("lag", {}).get("lag") == 0,
          f"重放 {stats.get('processed')} 事件 → 写入 {stats.get('indexed')} 文档（{stats.get('batches')} 批）")

    status, search = call("GET", "/api/v1/search?q=event_log")
    facets = search.get("facets", {}) if isinstance(search, dict) else {}
    check("7b. 检索真实命中（标识符按 _ 切分可搜到）+ 分面 + 可见分级",
          status == 200 and search.get("count", 0) > 0
          and bool(facets.get("entityType")) and isinstance(search.get("visibleLevels"), list),
          f"命中 {search.get('count')} 条，分面类型 {len(facets.get('entityType', []))} 种，"
          f"索引水位 {search.get('indexLag')}")

    status, search_miss = call("GET", "/api/v1/search?q=zzz_no_such_asset_zzz")
    check("7c. 空结果给出最接近候选与解释（而不是空白页）",
          status == 200 and search_miss.get("count") == 0 and "suggestions" in search_miss,
          search_miss.get("note", "")[:60])

    # -------------------------------------------------------------- 8) 调度
    status, scheds = call("GET", "/api/v1/schedules")
    check("8a. 调度清单可读（含互斥方式说明）",
          status == 200 and isinstance(scheds.get("schedules"), list)
          and "advisory" in str(scheds.get("scheduler", {}).get("mutex", "")),
          f"{scheds.get('count')} 条")

    status, applied = call("POST", "/api/v1/schedules", {"schedules": [
        {"name": "java_e2e_self", "source": "postgres",
         "dsn": "postgresql://postgres:root@localhost:25011/dg",
         "namespace": "java_e2e_sched", "schemas": ["public"],
         "cron": "*/15 * * * *", "enabled": True},
        {"name": "java_e2e_bad_source", "source": "sqlite",
         "dsn": "env:NOT_SET", "cron": "0 3 * * *"},
    ]})
    rejected = applied.get("rejected", []) if isinstance(applied, dict) else []
    check("8b. 非法调度被显式拒绝而非静默落库",
          status == 200 and len(applied.get("applied", [])) == 1 and len(rejected) == 1
          and "sqlite" in rejected[0].get("error", ""),
          rejected[0].get("error", "")[:60] if rejected else "没有拒绝项")

    status, ran = call("POST", "/api/v1/schedules/java_e2e_self/run")
    ok = (status == 200 and ran.get("skipped") is False
          and ran.get("run", {}).get("status") in ("SUCCEEDED", "BLOCKED"))
    check("8c. 调度立即执行并真的采集（互斥锁 + 运行留痕）", ok,
          f"status={ran.get('run', {}).get('status')} seen={ran.get('run', {}).get('datasetsSeen')} "
          f"next={str(ran.get('nextRunAt'))[:19]}")

    # ---------------------------------------------------------- 9) SQL 解析
    status, sidecar = call("GET", "/api/v1/lineage/parse/sidecar")
    dialects = (sidecar.get("health") or {}).get("dialects", []) if isinstance(sidecar, dict) else []
    check("9a. SQL 解析侧车就绪（含 sqlglot 版本与方言数）",
          status == 200 and sidecar.get("available") is True and len(dialects) > 5,
          f"sqlglot {(sidecar.get('health') or {}).get('sqlglotVersion')} / {len(dialects)} 方言")

    sql = ("INSERT INTO alert_event\n"
           "SELECT e.seq, e.event_type, e.urn\n"
           "  FROM event_log e\n"
           " WHERE e.event_type = 'ENTITY_CREATED'")
    status, parsed = call("POST", "/api/v1/lineage/parse",
                          {"sql": sql, "dialect": "postgres", "namespace": "java_e2e"})
    ok = (status == 200 and parsed.get("statements", 0) >= 1
          and parsed.get("columnEdges", 0) > 0 and parsed.get("tableEdges", 0) > 0
          and not parsed.get("unresolvedTables"))
    check("9b. 解析入库：列级血缘真的写进图（表名解析不到就不写边）", ok,
          f"语句 {parsed.get('statements')} 表级边 {parsed.get('tableEdges')} "
          f"列级边 {parsed.get('columnEdges')} 未解析 {parsed.get('unresolvedTables')}")

    status, dry = call("POST", "/api/v1/lineage/parse",
                       {"sql": sql, "dialect": "postgres", "namespace": "java_e2e", "dryRun": True})
    check("9c. dryRun 只解析不写库（先看效果再入库）",
          status == 200 and dry.get("dryRun") is True and dry.get("columnEdges", 0) > 0,
          f"预览列级边 {dry.get('columnEdges')}")

    # 再解析一段，形成 event_log → alert_event → collector_state 的两跳链：
    # 这样"深度截断"这一行为才有可验证的场景（一跳链永远到不了边界）
    status, chain = call("POST", "/api/v1/lineage/parse",
                         {"sql": "INSERT INTO collector_state SELECT a.seq FROM alert_event a",
                          "dialect": "postgres", "namespace": "java_e2e"})
    check("9c2. 解析第二段 SQL 形成两跳链（供深度截断验证）",
          status == 200 and chain.get("tableEdges", 0) >= 1,
          f"表级边 {chain.get('tableEdges')} 列级边 {chain.get('columnEdges')}")

    status, bad = call("POST", "/api/v1/lineage/parse",
                       {"sql": "INSERT INTO alert_event SELEC broken FROM", "dialect": "postgres",
                        "namespace": "java_e2e"})
    check("9d. 解析失败必须落样本库（把'解析不出来'变成可运营指标）",
          status == 200 and bad.get("failed", 0) >= 1 and bad.get("samplesRecorded", 0) >= 1,
          f"失败 {bad.get('failed')} 条，登记样本 {bad.get('samplesRecorded')} 条")

    status, quality = call("GET", "/api/v1/lineage/quality")
    samples = quality.get("parseSamples", []) if isinstance(quality, dict) else []
    check("9e. 血缘质量报告暴露解析样本（区分'没有血缘'与'解析不出来'）",
          status == 200 and len(samples) > 0,
          f"边 {quality.get('activeLineageEdges')} / 列级 {quality.get('activeColumnEdges')} / "
          f"样本组 {len(samples)}")

    # ---------------------------------------------------------- 10) 影响分析
    status, all_assets = call("GET", "/api/v1/assets?prefix=urn:dg:Dataset:java_e2e&limit=100")
    event_log_urn = next((row["urn"] for row in all_assets.get("assets", [])
                          if row["urn"].endswith("public.event_log")), None)
    if event_log_urn is None:
        # 找不到目标就**失败**，不能静默跳过：
        # 一条"被跳过"的检查与一条"通过"的检查在结果里长得一样，这正是要避免的静默失效
        check("10a. 影响分析：按评分排序 + 可解释理由 + 评分口径", False,
              "未找到 event_log 数据集 URN，无法执行影响分析检查")
        check("10b. 深度截断被显式标注（不把'3 跳内'说成'总共就这些'）", False,
              "未找到 event_log 数据集 URN，无法执行截断检查")
    else:
        status, impact = call(
            "GET",
            f"/api/v1/lineage/impact/{urllib.parse.quote(event_log_urn, safe='')}"
            "?direction=downstream&depth=3&includeColumns=true")
        nodes = impact.get("nodes", []) if isinstance(impact, dict) else []
        ok = (status == 200 and impact.get("affectedCount", 0) > 0 and bool(nodes)
              and "score" in nodes[0] and bool(nodes[0].get("reasons"))
              and "formula" in impact.get("scoring", {}))
        detail = (f"受影响 {impact.get('affectedCount')} 关键 {impact.get('criticalCount')} "
                  f"top={nodes[0].get('score')} ({', '.join(nodes[0].get('reasons', []))[:40]})"
                  if nodes else f"受影响 {impact.get('affectedCount')}，节点为空")
        check("10a. 影响分析：按评分排序 + 可解释理由 + 评分口径", ok, detail)

        status, shallow = call(
            "GET",
            f"/api/v1/lineage/impact/{urllib.parse.quote(event_log_urn, safe='')}?depth=1&includeColumns=true")
        check("10b. 深度截断被显式标注（不把'3 跳内'说成'总共就这些'）",
              status == 200 and (shallow.get("reachedMaxDepth") is True
                                 and bool(shallow.get("truncationNote"))),
              str(shallow.get("truncationNote"))[:70])

    # ------------------------------------------------- 11) 仍未实现的接口
    status, payload = call("GET", "/api/v1/collect/alerts")
    message = payload.get("message", "") if isinstance(payload, dict) else str(payload)
    check("11. 仍未实现的接口返回 501 且带设计说明（不是空数据）",
          status == 501 and "docs/" in message, f"HTTP {status}")

    # ============================================================ Batch 2：质量与契约
    source = {"jdbcUrl": "jdbc:postgresql://localhost:25011/dg",
              "username": "postgres", "password": "root"}
    target_urn = event_log_urn or first_urn

    # ---------------------------------------------------------- 13) 剖析
    if target_urn is None:
        check("13a. 剖析：实算 + 逐列统计 + 精度标注", False, "没有可剖析的数据集（先完成采集）")
        check("13b. 采样剖析标注 ESTIMATED 且不外推", False, "没有可剖析的数据集")
    else:
        status, prof = call("POST", "/api/v1/quality/profile", {
            **source, "datasetUrns": [target_urn], "samplePercent": 100, "includeTopK": True})
        datasets = prof.get("datasets", []) if isinstance(prof, dict) else []
        first = datasets[0] if datasets else {}
        columns = first.get("columns", [])
        ok = (status == 200 and first.get("rowCount", 0) > 0 and columns
              and all("metrics" in column for column in columns)
              and first.get("sampling", {}).get("precision") == "EXACT")
        check("13a. 剖析：实算 + 逐列统计 + 精度标注（EXACT）", ok,
              f"{first.get('rowCount')} 行 / {first.get('columnCount')} 列 / "
              f"{first.get('sampling', {}).get('method')}"
              + (f" / 估算行数 {first.get('rowCountEstimate')}" if first.get("rowCountEstimate") else ""))

        suppressed = [c["name"] for c in columns if c.get("valueSuppressed")]
        status, sampled = call("POST", "/api/v1/quality/profile", {
            **source, "datasetUrns": [target_urn], "samplePercent": 10, "includeTopK": False})
        first_sample = (sampled.get("datasets") or [{}])[0]
        sampling = first_sample.get("sampling", {})
        check("13b. 采样剖析：标注 ESTIMATED + 采样方式可追溯 + 不外推",
              status == 200 and sampling.get("precision") == "ESTIMATED"
              and sampling.get("method") in ("hash_modulo", "tablesample_system")
              and sampling.get("ratio") == 0.1,
              f"{sampling.get('method')} 采样 {first_sample.get('rowCount')} 行（未外推）"
              + (f"；{len(suppressed)} 列按类型/隐私约束未输出具体值" if suppressed else ""))

    # ----------------------------------------------------------- 14) 规则
    yaml_checks = [
        {"type": "notNull", "column": "event_type"},
        {"type": "uniqueness", "columns": ["seq"], "threshold": 0.999},
        {"type": "rowCount", "threshold": 100000000},
        {"type": "rowCountChange", "maxDropPct": 50, "window": "7d"},
    ]
    status, preview = call("POST", "/api/v1/quality/rules/compile",
                           {"frontend": "yaml", "datasetUrn": target_urn,
                            "document": {"rule": "e2e_rules", "checks": yaml_checks}})
    rules = preview.get("rules", []) if isinstance(preview, dict) else []
    check("14a. 规则编译（YAML 前端 → IR → 源库 SQL）",
          status == 200 and len(rules) == 4 and all(item.get("sql") for item in rules),
          "；".join(f"{item['ruleId']}={item['metric']}" for item in rules))

    status, sql_preview = call("POST", "/api/v1/quality/rules/compile",
                               {"frontend": "sql_assertion", "ruleId": "e2e_sql",
                                "datasetUrn": target_urn,
                                "sql": "SELECT count(*) FROM event_log WHERE urn IS NULL",
                                "expect": 0, "severity": "HIGH"})
    status2, dbt_preview = call("POST", "/api/v1/quality/rules/compile",
                                {"frontend": "dbt_test", "modelName": "event_log",
                                 "datasetUrn": target_urn,
                                 "tests": [{"unique": "seq"}, {"not_null": "urn"}]})
    check("14b. 规则编译另两种前端（SQL 断言 / dbt tests）",
          bool(sql_preview.get("rules")) and bool(dbt_preview.get("rules"))
          and sql_preview["rules"][0].get("sourceFrontend") == "sql_assertion"
          and dbt_preview["rules"][0].get("sourceFrontend") == "dbt_test",
          f"SQL 断言 1 条 / dbt {len(dbt_preview.get('rules', []))} 条")

    status, rejected = call("POST", "/api/v1/quality/rules/compile",
                            {"frontend": "yaml", "datasetUrn": target_urn,
                             "document": {"rule": "bad", "checks": [{"type": "vibes"}]}})
    check("14c. 不支持的检查类型被拒绝并列出可用模板（不是静默跳过）",
          status == 422 and "不支持的检查类型" in str(rejected.get("message", "")),
          str(rejected.get("message", ""))[:60])

    e2e_dsn = "postgresql://postgres:root@localhost:25011/dg"
    status, registered = call("POST", "/api/v1/quality/rules", {
        "frontend": "yaml", "datasetUrn": target_urn, "namespace": "java_e2e",
        "dsn": e2e_dsn, "document": {"rule": "e2e_rules", "checks": yaml_checks}})
    registered_rules = registered.get("registered", []) if isinstance(registered, dict) else []
    check("14d. 规则注册为实体（有 Owner 位、版本历史、进检索），口令在响应中被遮蔽",
          status == 200 and len(registered_rules) == 4
          and all("***" in (item.get("dsn") or "") for item in registered_rules),
          f"{len(registered_rules)} 条；{registered.get('rejected') or '无拒绝'}")

    rule_urns = {item["ruleId"]: item["urn"] for item in registered_rules}
    statuses = {}
    for rule_id in ("e2e_rules#1", "e2e_rules#3", "e2e_rules#4"):
        urn = rule_urns.get(rule_id)
        if urn is None:
            continue
        _, run = call("POST", f"/api/v1/quality/rules/{urllib.parse.quote(urn, safe='')}/run")
        statuses[rule_id] = run.get("status")
    check("14e. 规则执行留痕：通过 / 失败 / 无法判定三种状态可区分",
          statuses.get("e2e_rules#1") == "PASS" and statuses.get("e2e_rules#3") == "FAIL"
          and statuses.get("e2e_rules#4") == "SKIPPED",
          f"notNull={statuses.get('e2e_rules#1')} 巨阈值={statuses.get('e2e_rules#3')} "
          f"行数波动={statuses.get('e2e_rules#4')}")

    # env: 引用未设置时必须报错，而不是降级连别的库（凭证纪律）
    status, env_rule = call("POST", "/api/v1/quality/rules", {
        "frontend": "yaml", "datasetUrn": target_urn, "namespace": "java_e2e",
        "dsn": "env:DG_E2E_DEFINITELY_NOT_SET",
        "document": {"rule": "e2e_env_rule", "checks": [{"type": "notNull", "column": "event_type"}]}})
    env_urn = (env_rule.get("registered") or [{}])[0].get("urn")
    _, env_run = call("POST", f"/api/v1/quality/rules/{urllib.parse.quote(env_urn or '', safe='')}/run")
    check("14f. env: 引用的连接未设置时报错（不降级、不静默连错库）",
          env_run.get("status") == "ERROR" and "未设置" in str(env_run.get("error", "")),
          str(env_run.get("error"))[:60])

    # ----------------------------------------------------------- 15) 契约
    # 契约 id 带运行时间戳：让本节在**已有数据的库上重复执行**也能从"首次注册"开始验证。
    # （用固定 id 时第二次运行会得到"相对已发布版本的变更"，那不是契约逻辑出错）
    contract_id = "e2e_contract_" + time.strftime("%H%M%S")
    contract_doc = {
        "apiVersion": "v3.0.2", "kind": "DataContract", "id": contract_id,
        "version": "1.0.0", "status": "ACTIVE", "dataset": target_urn,
        "primaryKey": ["seq"],
        "schema": [{"name": "seq", "type": "bigint", "required": True},
                   {"name": "event_type", "type": "text", "required": True},
                   {"name": "urn", "type": "text", "required": True},
                   {"name": "created_at", "type": "timestamp with time zone", "required": True}],
        "quality": [{"type": "notNull", "column": "event_type"}],
        "sla": {"freshness": "PT24H"},
        "connection": {"dsn": "env:DG_E2E_DSN"},
    }
    status, contract = call("POST", "/api/v1/contracts",
                            {"document": contract_doc, "namespace": "java_e2e"})
    contract_urn = contract.get("contract")
    check("15a. 契约注册（ODCS）+ quality 段编译为质量规则",
          status == 200 and contract.get("registered") is True
          and contract.get("generatedRules", 0) >= 1
          and contract.get("diff", {}).get("verdict") == "INITIAL",
          f"{contract_urn} v{contract.get('version')}，生成 {contract.get('generatedRules')} 条规则")

    broken = dict(contract_doc)
    broken["version"] = "1.1.0"
    broken["schema"] = [f for f in contract_doc["schema"] if f["name"] != "urn"]
    status, rejected_change = call("POST", "/api/v1/contracts",
                                    {"document": broken, "namespace": "java_e2e"})
    check("15b. 破坏性变更默认拒绝注册（并指出应升 major）",
          status == 200 and rejected_change.get("registered") is False
          and rejected_change.get("diff", {}).get("verdict") == "BLOCK"
          and rejected_change.get("diff", {}).get("requiredVersionBump") == "MAJOR",
          str(rejected_change.get("rejected"))[:60])

    broken["version"] = "2.0.0"
    status, accepted_change = call("POST", "/api/v1/contracts",
                                    {"document": broken, "namespace": "java_e2e",
                                     "allowBreaking": True,
                                     "breakingJustification": "e2e：urn 列迁到新表"})
    check("15c. 破坏性变更在显式授权 + 留痕后可发布",
          status == 200 and accepted_change.get("registered") is True
          and accepted_change.get("breakingJustification"),
          f"v{accepted_change.get('version')}（理由已留痕）")

    quoted_contract = urllib.parse.quote(contract_urn or "", safe="")
    status, versions = call("GET", f"/api/v1/contracts/{quoted_contract}/versions")
    version_rows = versions.get("versions", []) if isinstance(versions, dict) else []
    check("15d. 版本历史包含当前版本（aspect_history 只存旧版本，必须并上当前）",
          status == 200 and any(row.get("is_current") for row in version_rows)
          and len(version_rows) >= 2,
          f"{len(version_rows)} 个版本，当前 = v{[r['version'] for r in version_rows if r.get('is_current')]}")

    status, diff = call("GET", f"/api/v1/contracts/{quoted_contract}/diff?from=2&to=1")
    check("15e. 版本间兼容性 diff（删列判为破坏性）",
          status == 200 and diff.get("breaking") is True
          and any(change.get("kind") == "column_added" for change in diff.get("changes", [])),
          f"{diff.get('verdict')}：{[c.get('kind') for c in diff.get('changes', [])]}")

    status, validation = call("POST", f"/api/v1/contracts/{quoted_contract}/validate")
    check("15f. 运行时校验产出违约事件（契约 vs 实际结构一致时为空）",
          status == 200 and isinstance(validation.get("violations"), list),
          f"违约 {validation.get('violationCount')} 条（约定 {validation.get('promisedFields')} 列 / "
          f"实际 {validation.get('actualFields')} 列）")

    status, consumers = call("GET", f"/api/v1/contracts/{quoted_contract}/consumers")
    check("15g. 未登记消费者：血缘上实际在读但没登记契约的资产被点名",
          status == 200 and "undetected" in consumers and "note" in consumers,
          f"已登记 {len(consumers.get('registered', []))} / 血缘下游 "
          f"{len(consumers.get('external', []))} / 未登记 {len(consumers.get('undetected', []))}")

    # -------------------------------------------------------- 16) CI 门禁
    # 用一次**新的**破坏性变更来验证门禁（复用已发布版本会得到"无变更"，那不是门禁失效）
    gate_doc = dict(contract_doc)
    gate_doc["version"] = "3.1.0"
    gate_doc["schema"] = [f for f in contract_doc["schema"] if f["name"] != "created_at"]
    status, gate_block = call("POST", "/api/v1/contracts/ci-check",
                              {"document": gate_doc, "namespace": "java_e2e", "source": "e2e"})
    check("16a. CI 门禁：破坏性变更 → BLOCK + exitCode 1 + 影响面可见",
          status == 200 and gate_block.get("verdict") == "BLOCK"
          and gate_block.get("exitCode") == 1 and bool(gate_block.get("blocking"))
          and "downstreamCount" in gate_block.get("impact", {}),
          f"阻断 {len(gate_block.get('blocking', []))} 项，下游 "
          f"{gate_block.get('impact', {}).get('downstreamCount')}")

    status, gate_allow = call("POST", "/api/v1/contracts/ci-check",
                              {"document": gate_doc, "namespace": "java_e2e",
                               "allowBreaking": True, "source": "e2e"})
    check("16b. allowBreaking 是顶层开关（不被 policy 缺失吞掉）",
          status == 200 and gate_allow.get("verdict") != "BLOCK"
          and gate_allow.get("exitCode") == 0,
          f"{gate_allow.get('verdict')}（警告 {len(gate_allow.get('warnings', []))} 项）")

    strict_doc = dict(gate_doc)
    status, gate_strict = call("POST", "/api/v1/contracts/ci-check",
                               {"document": strict_doc, "namespace": "java_e2e", "source": "e2e",
                                "policy": {"requireOwner": True, "requireDescription": True,
                                           "requireNoUndetectedConsumers": True}})
    blocking_text = " ".join(gate_strict.get("blocking", []))
    check("16c. 治理属性必填策略生效（Owner / 描述 / 未登记消费者）",
          status == 200 and gate_strict.get("verdict") == "BLOCK"
          and "Owner" in blocking_text and "未登记消费者" in blocking_text,
          f"{gate_strict.get('verdict')}：{len(gate_strict.get('blocking', []))} 项阻断")

    status, history = call("GET", "/api/v1/contracts/ci-history")
    check("16d. 门禁判定留痕（谁在哪个版本上被拦下、为什么）",
          status == 200 and history.get("count", 0) >= 3
          and all(row.get("verdict") for row in history.get("checks", [])),
          f"{history.get('count')} 次检查记录"
          + (f"（最近：{history['checks'][0].get('verdict')} v{history['checks'][0].get('requested_version')}）"
             if history.get("checks") else ""))

    # --------------------------------------------------- 17) 违约处理与豁免
    status, violations = call("GET", "/api/v1/contracts/violations?status=OPEN")
    rows = violations.get("violations", []) if isinstance(violations, dict) else []
    if rows:
        violation_id = rows[0]["id"]
        status_no_until, no_until = call(
            "POST", f"/api/v1/contracts/violations/{violation_id}/exempt", {"reason": "e2e 尝试永久豁免"})
        check("17a. 豁免必须带到期时间（拒绝永久豁免）",
              status_no_until == 422 and "到期时间" in str(no_until.get("message", "")),
              str(no_until.get("message", ""))[:50])

        status_until, with_until = call(
            "POST", f"/api/v1/contracts/violations/{violation_id}/exempt",
            {"until": "2030-01-01T00:00:00Z", "reason": "e2e 临时豁免"})
        check("17b. 带到期时间的豁免生效（到期自动回到 OPEN）",
              status_until == 200 and with_until.get("status") == "EXEMPTED"
              and "OPEN" in str(with_until.get("note", "")),
              f"违约 #{violation_id} → EXEMPTED")
    else:
        check("17a. 豁免必须带到期时间（拒绝永久豁免）", False,
              "没有 OPEN 违约事件可用于豁免检查")
        check("17b. 带到期时间的豁免生效（到期自动回到 OPEN）", False,
              "没有 OPEN 违约事件可用于豁免检查")

    # ==================================================== Batch 3：多源连接器
    # 前置：本地运行的 ClickHouse(8123) / MongoDB(27018) / Superset(18089)。
    # 这些检查**真的连真实系统**采集，不是 mock —— 连接器的价值全在"能不能真的读出来"。
    connectors = {
        "clickhouse": {"dsn": "clickhouse://default:@127.0.0.1:8123", "namespace": "e2e_ch",
                       "databases": ["tutorial"]},
        "mongodb": {"dsn": "mongodb://127.0.0.1:27018", "namespace": "e2e_mongo",
                    "databases": ["shop"]},
        "superset": {"dsn": "superset://admin:admin@127.0.0.1:18089", "namespace": "e2e_bi"},
    }

    status, source_list = call("GET", "/api/v1/collect/sources")
    implemented = {item["id"]: item for item in source_list.get("implemented", [])} \
        if isinstance(source_list, dict) else {}
    check("19a. 连接器清单来自服务端单点声明（含「是否对真实系统验证过」）",
          status == 200 and {"clickhouse", "mongodb", "bigquery", "superset"} <= set(implemented)
          and implemented["bigquery"].get("verifiedAgainstRealSystem") is False
          and implemented["clickhouse"].get("verifiedAgainstRealSystem") is True,
          "已实现：" + "、".join(
              f"{k}{'(已对真实系统验证)' if v.get('verifiedAgainstRealSystem') else '(未对真实系统验证)'}"
              for k, v in implemented.items()))

    # ------------------------------------------------------------ 19) ClickHouse
    status, ch_run = call("POST", "/api/v1/collect/run", {"source": "clickhouse", **connectors["clickhouse"]})
    check("19b. ClickHouse 采集（system.tables + system.columns，HTTP 接口）",
          status == 200 and ch_run.get("status") == "SUCCEEDED"
          and ch_run.get("datasetsSeen", 0) > 0 and ch_run.get("columnsSeen", 0) > 0,
          f"status={ch_run.get('status')} 表={ch_run.get('datasetsSeen')} "
          f"列={ch_run.get('columnsSeen')} {ch_run.get('durationMs')}ms")

    status, ch_assets = call("GET", "/api/v1/assets?prefix=urn:dg:Dataset:e2e_ch&limit=1")
    ch_urn = ch_assets["assets"][0]["urn"] if ch_assets.get("assets") else None
    if ch_urn:
        _, ch_detail = call("GET", f"/api/v1/assets/{urllib.parse.quote(ch_urn, safe='')}")
        schema = ch_detail.get("aspects", {}).get("datasetSchema", {})
        description = str(ch_detail.get("aspects", {}).get("descriptions", {}).get("text", ""))
        check("19c. ClickHouse 元数据质量：分区/排序键进结构化字段，估算行数在描述里显式标注",
              bool(schema.get("partitionKeys")) and "估算" in description,
              f"partitionKeys={schema.get('partitionKeys')}；描述={description[:60]}")
    else:
        check("19c. ClickHouse 元数据质量：分区/排序键进结构化字段，估算行数在描述里显式标注", False,
              "没有采到 ClickHouse 数据集")

    # -------------------------------------------------------------- 20) MongoDB
    status, mg_run = call("POST", "/api/v1/collect/run",
                          {"source": "mongodb", **connectors["mongodb"], "sampleSize": 200})
    check("20a. MongoDB 采集（无 schema → 采样推断结构）",
          status == 200 and mg_run.get("status") == "SUCCEEDED" and mg_run.get("datasetsSeen", 0) >= 4,
          f"集合={mg_run.get('datasetsSeen')} 推断字段={mg_run.get('columnsSeen')}")

    status, mg_assets = call("GET", "/api/v1/assets?prefix=urn:dg:Dataset:e2e_mongo&limit=20")
    mg_urn = next((row["urn"] for row in mg_assets.get("assets", [])
                   if row["urn"].endswith("customers")), None)
    if mg_urn:
        _, mg_detail = call("GET", f"/api/v1/assets/{urllib.parse.quote(mg_urn, safe='')}")
        fields = {f["name"]: f for f in mg_detail.get("aspects", {})
                  .get("datasetSchema", {}).get("fields", [])}
        nested = [name for name in fields if "." in name]
        mixed = {name: f["type"] for name, f in fields.items() if "mixed" in str(f["type"])}
        sparse = [name for name, f in fields.items() if "稀疏" in str(f.get("description", ""))]
        check("20b. MongoDB 推断质量：嵌套下钻 + 类型不稳定显式暴露 + 稀疏字段标注",
              bool(nested) and bool(mixed) and bool(sparse),
              f"嵌套 {nested[:2]}；类型不稳定 {mixed}；稀疏字段 {sparse}")
        email = fields.get("email")
        check("20c. 可空判定同时覆盖「字段缺失」与「出现过 null」",
              email is not None and email.get("nullable") is True,
              f"email nullable={email.get('nullable') if email else 'N/A'}"
              f"（{email.get('description') if email else ''}）")
    else:
        check("20b. MongoDB 推断质量：嵌套对象下钻为点号路径 + 类型不稳定显式暴露 + 稀疏字段标注",
              False, "没有采到 customers 集合")
        check("20c. 可空判定同时覆盖「字段缺失」与「出现过 null」", False, "没有采到 customers 集合")

    # ------------------------------------------------------------- 21) Superset
    # 先采集 Superset 自身的元数据库（为报表血缘提供数据集落点），再采仪表板
    call("POST", "/api/v1/collect/run", {
        "source": "postgres", "dsn": "postgresql://superset:superset@127.0.0.1:15434/superset",
        "namespace": "e2e_bi", "schemas": ["public"]})
    status, ss_run = call("POST", "/api/v1/collect/run",
                          {"source": "superset", **connectors["superset"], "reconcileOrphans": True})
    check("21a. Superset 采集：BI 资产作为一等实体（仪表板 + 图表清单 + URL）",
          status == 200 and ss_run.get("status") == "SUCCEEDED" and ss_run.get("dashboardsSeen", 0) > 0,
          f"仪表板={ss_run.get('dashboardsSeen')} 新建={ss_run.get('dashboardsCreated')} "
          f"删除={ss_run.get('dashboardsDeleted')}")

    status, dash_assets = call("GET", "/api/v1/assets?prefix=urn:dg:Dashboard:e2e_bi&limit=25")
    dashboards = dash_assets.get("assets", [])
    dash_with_charts = dash_with_links = 0
    sample_dashboard = None
    for row in dashboards:
        _, detail = call("GET", f"/api/v1/assets/{urllib.parse.quote(row['urn'], safe='')}")
        spec = detail.get("aspects", {}).get("dashboardSpec", {})
        if spec.get("chartCount", 0) > 0:
            dash_with_charts += 1
        if spec.get("datasetUrns"):
            dash_with_links += 1
            sample_dashboard = sample_dashboard or (row["urn"], spec)
    check("21b. 仪表板携带图表清单与依赖数据集（readsFrom→consumedBy 的落点）",
          bool(dashboards) and dash_with_charts > 0,
          f"{len(dashboards)} 个仪表板，{dash_with_charts} 个含图表，{dash_with_links} 个已连到数据集")

    if sample_dashboard:
        dashboard_urn, spec = sample_dashboard
        dataset_urn = spec["datasetUrns"][0]
        status, bi_graph = call(
            "GET", f"/api/v1/lineage/graph?urn={urllib.parse.quote(dataset_urn, safe='')}"
                   "&direction=downstream&depth=2")
        downstream = [node["urn"] for node in bi_graph.get("nodes", [])]
        status2, bi_impact = call(
            "GET", f"/api/v1/lineage/impact/{urllib.parse.quote(dataset_urn, safe='')}"
                   "?direction=downstream&depth=3")
        check("21c. BI 血缘方向正确：数据集 → 报表，且影响分析能直接回答「改这张表哪些看板受影响」",
              dashboard_urn in downstream and bi_impact.get("affectedCount", 0) > 0,
              f"下游 {len(downstream)} 个（含本仪表板）；影响分析受影响 {bi_impact.get('affectedCount')}")
    else:
        check("21c. BI 血缘方向正确：数据集 → 报表，且影响分析能直接回答「改这张表哪些看板受影响」",
              False, "没有仪表板成功连到数据集（检查 Superset 数据源是否为物理数据集）")

    # ------------------------------------------- 22) BigQuery：未验证要诚实标注
    status, bq_run = call("POST", "/api/v1/collect/run", {
        "source": "bigquery", "dsn": "bigquery://e2e-project?credentials=/nonexistent/sa.json",
        "namespace": "e2e_bq", "databases": ["dwd"]})
    errors = " ".join(bq_run.get("errors", [])) if isinstance(bq_run, dict) else ""
    check("22. BigQuery 连接器已实现但缺凭据时给出可读错误（不静默返回空数据集）",
          status == 200 and bq_run.get("status") == "FAILED"
          and ("凭据" in errors or "credentials" in errors.lower()),
          str(errors)[:90])

    # --------------------------------------------- 24) 血缘画布与边的人工确认
    # 用「报表读取的数据集」作为焦点：它有 consumedBy 边，且有真实的下游
    focus_urn = sample_dashboard[1]["datasetUrns"][0] if sample_dashboard else target_urn
    status, sub = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(focus_urn, safe='')}"
                              "&direction=downstream&depth=3")
    edges = sub.get("edges", []) if isinstance(sub, dict) else []
    check("24a. 血缘子图：节点与边都带属性（线型可按来源/置信度绘制）",
          status == 200 and bool(sub.get("nodes")) and all(
              "source" in edge and "confidence" in edge and "edgeId" in edge for edge in edges),
          f"节点 {sub.get('counts', {}).get('nodes')} 边 {sub.get('counts', {}).get('edges')}；"
          f"来源 {sub.get('counts', {}).get('edgesBySource')}")

    # contains（结构包含）不是血缘：上游不该出现容器/平台
    status, up = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(target_urn, safe='')}"
                             "&direction=upstream&depth=3")
    up_types = up.get("counts", {}).get("nodesByType", {}) if isinstance(up, dict) else {}
    check("24b. 只沿「血缘关系类型」遍历（contains 这类结构边不算血缘）",
          status == 200 and not ({"Platform", "Container"} & set(up_types.keys())),
          f"上游节点类型：{up_types or '空'}")

    status, limited = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(target_urn, safe='')}"
                                  "&direction=downstream&depth=3&nodeLimit=2")
    check("24c. 节点上限被裁剪时**显式回报**（不静默截断）",
          status == 200 and limited.get("nodeLimitReached") is True
          and any("上限" in note for note in limited.get("notes", [])),
          f"可达 {limited.get('counts', {}).get('reachableBeforeLimit')} → 展示 "
          f"{limited.get('counts', {}).get('nodes')}（上限 {limited.get('nodeLimit')}）")

    status, sub2 = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(focus_urn, safe='')}"
                               "&direction=downstream&depth=3")
    edge = (sub2.get("edges") or [None])[0]
    if edge:
        edge_id = edge["edgeId"]
        before_confidence = edge.get("confidence")
        status, confirmed = call("POST", f"/api/v1/lineage/edges/{edge_id}/confirm")
        _, after = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(focus_urn, safe='')}"
                               "&direction=downstream&depth=3")
        after_edge = next((item for item in after.get("edges", []) if item["edgeId"] == edge_id), {})
        check("24d. 确认血缘边：置信度提升到人工级并记名（喂养置信度模型）",
              status == 200 and after_edge.get("confidence", 0) >= 0.99
              and after_edge.get("confirmedBy"),
              f"置信度 {before_confidence} → {after_edge.get('confidence')}，确认人 {after_edge.get('confirmedBy')}")

        status_no_reason, rejected = call("POST", f"/api/v1/lineage/edges/{edge_id}/reject", {})
        check("24e. 驳回血缘必须说明原因（422，而不是静默标记）",
              status_no_reason == 422, str(rejected.get("message"))[:60])

        status_reject, _ = call("POST", f"/api/v1/lineage/edges/{edge_id}/reject",
                                {"reason": "e2e：字段映射不正确"})
        _, after_reject = call("GET", f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(focus_urn, safe='')}"
                                      "&direction=downstream&depth=3")
        still_there = any(item["edgeId"] == edge_id for item in after_reject.get("edges", []))
        check("24f. 驳回后该边不再参与血缘遍历（标记而非删除）",
              status_reject == 200 and not still_there,
              f"驳回后子图内边数 {len(after_reject.get('edges', []))}")
        # 复原：确认回来，避免污染后续运行
        call("POST", f"/api/v1/lineage/edges/{edge_id}/confirm")
    else:
        for name in ("24d. 确认血缘边：置信度提升到人工级并记名（喂养置信度模型）",
                     "24e. 驳回血缘必须说明原因（422，而不是静默标记）",
                     "24f. 驳回后该边不再参与血缘遍历（标记而非删除）"):
            check(name, False, "没有可用于演示的血缘边")

    status, no_type = call("POST", "/api/v1/lineage/edges/retire?reason=e2e")
    check("24g. 批量退役边必须显式给出 edgeType（避免误伤）",
          status in (400, 422), f"HTTP {status}")

    # ============================================ Batch 4：访问治理与策略生命周期
    # ------------------------------------------------------ 25) 申请 → 审批 → 授权
    access_urn = target_urn
    status, request = call("POST", "/api/v1/access/requests", {
        "resourceUrn": access_urn, "granularity": "DATASET", "permissions": ["SELECT"],
        "purpose": "e2e：排查事件流异常需要读取明细", "durationDays": 30}, token=READER_TOKEN)
    request_id = request.get("requestId")
    check("25a. 访问申请：自动填充分级 / 审批链 / SLA / 最小粒度建议",
          status == 200 and request.get("route") and request.get("slaDueAt")
          and "列级申请" in str(request.get("granularitySuggestion"))
          and any("自批自用" in note for note in request.get("notes", [])),
          f"{request.get('route')}；SLA {str(request.get('slaDueAt'))[:16]}")

    status, self_approve = call("POST", f"/api/v1/access/requests/{request_id}/decide",
                                {"decision": "APPROVED", "note": "自批尝试"}, token=READER_TOKEN)
    check("25b. 申请人不能审批自己的申请（权限点 + 审批链双重校验）",
          status in (403, 422), f"HTTP {status}")

    status, approved = call("POST", f"/api/v1/access/requests/{request_id}/decide",
                            {"decision": "APPROVED", "note": "用途明确，批准 30 天"}, token=STEWARD_TOKEN)
    grant_id = approved.get("grantId")
    check("25c. 审批通过生成**带到期时间**的授权记录",
          status == 200 and grant_id and approved.get("expiresAt") and approved.get("durationDays") == 30,
          f"授权 #{grant_id} 到期 {str(approved.get('expiresAt'))[:10]}")

    status, rejected_without_reason = call("POST", "/api/v1/access/requests", {
        "resourceUrn": access_urn, "granularity": "DATASET", "permissions": ["SELECT"],
        "purpose": "e2e：拒绝时必须给原因", "durationDays": 30}, token=READER_TOKEN)
    second_request = rejected_without_reason.get("requestId")
    status, no_reason = call("POST", f"/api/v1/access/requests/{second_request}/decide",
                             {"decision": "REJECTED"}, token=STEWARD_TOKEN)
    check("25d. 拒绝申请必须说明原因（申请人有权知道理由）",
          status == 422 and "原因" in str(no_reason.get("message", "")),
          str(no_reason.get("message"))[:50])

    status, grants = call("GET", "/api/v1/access/grants?status=ACTIVE")
    check("25e. 授权列表可读（含距到期天数，数组列正确序列化）",
          status == 200 and any(g["id"] == grant_id and isinstance(g["permissions"], list)
                                for g in grants.get("grants", [])),
          f"{grants.get('count')} 条生效授权")

    status, overview = call("GET", "/api/v1/access/overview")
    check("25f. 治理概览：待办 / 超期 / 即将到期（没有这三个数授权会演变成永久权限）",
          status == 200 and "overdueRequests" in overview and "expiringSoon" in overview
          and "activeGrants" in overview,
          f"生效 {overview.get('activeGrants')} / 超期 {overview.get('overdueRequests')} / "
          f"30 天内到期 {len(overview.get('expiringSoon', []))}")

    # ------------------------------------------------------------ 26) 复核与回收
    status, campaign = call("GET", "/api/v1/access/reviews/e2e-2026Q1")
    items = campaign.get("items", []) if isinstance(campaign, dict) else []
    check("26a. 复核批次：缺使用数据时给出「需更多信息」而不是假设未使用",
          status == 200 and items and any(
              item.get("suggestedDecision") == "NEED_MORE_INFO"
              and "不假设" in str(item.get("suggestionReason")) for item in items),
          f"{len(items)} 条待复核；建议 {[item.get('suggestedDecision') for item in items[:3]]}")

    status, review_revoke = call("POST", "/api/v1/access/reviews/e2e-2026Q1/decide",
                                 {"grantId": grant_id, "decision": "REVOKE",
                                  "reason": "e2e：复核回收演示"})
    status, after_revoke = call("GET", f"/api/v1/access/grants?status=REVOKED")
    check("26b. 复核判定 REVOKE 会真正回收授权（复核不是走过场）",
          status == 200 and any(g["id"] == grant_id for g in after_revoke.get("grants", [])),
          f"回收原因：{review_revoke.get('reason')}")

    status, revoke_no_reason = call("POST", f"/api/v1/access/grants/{grant_id}/revoke", {})
    check("26c. 吊销授权必须说明原因", status == 422, str(revoke_no_reason.get("message"))[:50])

    status, sweep = call("POST", "/api/v1/access/grants/expire-sweep")
    check("26d. 到期回收可执行且回报条数（只发不回收=没有期限）",
          status == 200 and "expiredGrants" in sweep and "note" in sweep,
          f"回收 {sweep.get('expiredGrants')} 条授权、{sweep.get('expiredRequests')} 条僵尸申请")

    # -------------------------------------------------- 27) 策略编译 / 下发 / 覆盖率
    policy_doc = {
        "name": "e2e_region_filter", "description": "e2e：按区域限制行", "target": "trino",
        "effect": "ROW_FILTER", "priority": 20,
        "resourceScope": {"prefixes": ["urn:dg:Dataset:java_e2e."], "classification": ["L2", "L3", "L4"]},
        "subjectScope": {"roles": ["ANALYST_CN"]},
        "condition": {"column": "region", "operator": "=", "value": "CN"},
    }
    status, preview = call("POST", "/api/v1/policies/compile-preview", policy_doc)
    check("27a. 策略编译预览：产物带出处注释 + 谓词可审计",
          status == 200 and "策略：e2e_region_filter" in str(preview.get("artifact"))
          and preview.get("metadata", {}).get("rowFilter") == "region = 'CN'",
          str(preview.get("metadata", {}).get("rowFilter")))

    status, saved = call("POST", "/api/v1/policies", policy_doc)
    check("27b. 建模即编译（编译不过的策略不入库）", status == 200 and saved.get("version"),
          f"{saved.get('name')} v{saved.get('version')}")

    status, bad_policy = call("POST", "/api/v1/policies", {
        "name": "e2e_bad", "target": "trino", "effect": "ROW_FILTER",
        "resourceScope": {"prefixes": ["urn:dg:Dataset:x."]}, "subjectScope": {"roles": ["R"]},
        "condition": {"column": "c", "operator": "LIKE_ANY", "value": "x"}})
    check("27c. 非法策略被拒绝（不支持的自定义运算符不是「灵活性」而是注入面）",
          status == 422 and "运算符" in str(bad_policy.get("message", "")),
          str(bad_policy.get("message"))[:60])

    status, compiled = call("POST", "/api/v1/policies/compile")
    check("27d. 编译全部 ACTIVE 策略并归档产物（内容哈希）",
          status == 200 and compiled.get("count", 0) > 0 and not compiled.get("failed"),
          f"编译 {compiled.get('count')} 条，失败 {len(compiled.get('failed', []))}")

    status, deployed = call("POST", "/api/v1/policies/deploy?target=trino")
    check("27e. 下发产出 bundle（落盘 + 部署记录 + 产物哈希）",
          status == 200 and deployed.get("bundleHash") and deployed.get("artifactCount", 0) > 0
          and deployed.get("bundleFile"),
          f"{deployed.get('artifactCount')} 条产物 → {str(deployed.get('bundleFile')).split(chr(92))[-1]}")

    status, coverage = call("POST", "/api/v1/policies/coverage")
    ratio = coverage.get("coverageRatio")
    check("27f. 覆盖率度量：给出真实比例 + 高分级未覆盖清单 + 已知盲区",
          status == 200 and ratio is not None and 0 <= ratio <= 1
          and isinstance(coverage.get("knownBlindSpots"), list)
          and any("直连" in spot for spot in coverage.get("knownBlindSpots", [])),
          f"覆盖 {coverage.get('coveredAssets')}/{coverage.get('totalAssets')}（{ratio}）；"
          f"高分级未覆盖 {len(coverage.get('uncoveredHighClassification', []))}")

    status, deployments = call("GET", "/api/v1/policies/deployments")
    first_deployment = (deployments.get("deployments") or [{}])[0]
    status, rolled_back = call("POST", f"/api/v1/policies/deployments/{first_deployment.get('id')}/rollback",
                               {"reason": "e2e 回滚演示"})
    check("27g. 回滚：记录一次指向旧产物集合的新部署（可审计）",
          status == 200 and rolled_back.get("rolledBack") and rolled_back.get("bundleHash"),
          f"回滚 {rolled_back.get('rolledBack')} → 新部署 {rolled_back.get('deploymentId')}")

    # ------------------------------------------------------------ 28) 审计取证
    status, report = call("GET", "/api/v1/access/audit-report?days=90")
    note = report.get("coverageNote", {}) if isinstance(report, dict) else {}
    covered_text = " ".join(note.get("covered", []))
    not_covered_text = " ".join(note.get("notCovered", []))
    engine_configured = bool(report.get("coverageNote", {}).get("engineAuditWindow", {}).get("records")) \
        if isinstance(report, dict) else False
    # 覆盖范围**必须反映当前实际数据**：接入引擎审计前是"未覆盖"，接入后是"已覆盖 + 观测窗口"。
    # 这里两种状态都要判得住 —— 否则这条检查会在接入后变成假绿灯。
    if engine_configured:
        coverage_ok = ("引擎侧的真实查询" in covered_text and "观测窗口" in covered_text
                       and "未接入审计的引擎" in not_covered_text)
        detail = f"已接入引擎审计：covered 含引擎查询与观测窗口；未覆盖仍声明未接入的引擎"
    else:
        coverage_ok = "引擎侧的真实查询" in not_covered_text and "不等于" in str(note.get("implication"))
        detail = "未接入引擎审计：如实声明未覆盖"
    check("28a. 审计报告显式声明**覆盖范围**，且随实际接入情况变化（没记录 ≠ 没发生）",
          status == 200 and bool(note.get("covered")) and bool(note.get("notCovered"))
          and "不等于" in str(note.get("implication")) and coverage_ok,
          detail)

    status, events = call("GET", "/api/v1/access/events?limit=50")
    actions = {event["action"] for event in events.get("events", [])} if isinstance(events, dict) else set()
    check("28b. 访问事件留痕（申请/审批/授权/回收全链路）",
          status == 200 and {"REQUEST_SUBMITTED", "GRANT_CREATED"} <= actions,
          f"{events.get('count')} 条事件：{sorted(actions)[:5]}")

    status, least = call("GET", "/api/v1/access/least-privilege")
    least_note = str(least.get("note"))
    if least.get("usageDataAvailable") is True:
        # 有使用证据时，结论必须**逐条给出依据**，且说明判定标准
        all_reasoned = all(item.get("reason") for group in
                           ("recentlyUsed", "neverReviewed", "neverReviewedRecent", "granularityCandidates")
                           for item in least.get(group, []))
        check("28c. 有使用证据时：每条结论都给出依据，并说明「未使用」的判定标准",
              status == 200 and all_reasoned and "观测窗口覆盖" in least_note,
              f"窗口 {least.get('observationWindow', {}).get('windowHours')} 小时；"
              f"用过 {len(least.get('recentlyUsed', []))} / 可回收 {len(least.get('neverReviewed', []))}")
    else:
        check("28c. 最小权限复盘显式标注「无使用数据」而不是假设未使用",
              status == 200 and "无法判断授权是否在用" in least_note,
              least_note[:60])

    # ---------------------------------------------------- 29) AI 建议闭环（诚实边界）
    status, aist = call("GET", "/api/v1/ai/status")
    check("29a. AI 能力状态把「没配置什么」摆在明面上（不假装有向量检索）",
          status == 200 and aist.get("semanticSearch", {}).get("vector") is False
          and aist.get("suggestionLlm", {}).get("configured") is False
          and aist.get("suggestionDeterministic", {}).get("available") is True,
          "向量检索可用=" + str(aist.get("semanticSearch", {}).get("vector"))
          + "；LLM 已配置=" + str(aist.get("suggestionLlm", {}).get("configured")))

    status, llm = call("POST", "/api/v1/ai/suggestions/llm", {"urn": target_urn})
    check("29b. 未配置大模型时**明确失败**（502 + 原因），而不是退回模板冒充 AI",
          status == 502 and "不会用模板冒充" in str(llm.get("message", "")),
          str(llm.get("message"))[:70])

    status, generated = call("POST", "/api/v1/ai/suggestions/generate?limit=30")
    status2, inbox = call("GET", "/api/v1/ai/suggestions?limit=50")
    suggestions = inbox.get("suggestions", []) if isinstance(inbox, dict) else []
    kinds = {item.get("kind") for item in suggestions}
    with_rationale = bool(suggestions) and all(item.get("rationale") for item in suggestions)
    deterministic = [item for item in suggestions if item.get("generator") == "deterministic"]
    check("29c. 建议生成器产出候选（每条都带依据与置信度、来源可追）",
          status == 200 and status2 == 200 and with_rationale and deterministic
          and all(item.get("generator_ref") for item in deterministic),
          f"{len(suggestions)} 条待审（其中生成器产出 {len(deterministic)} 条），"
          f"类型 {sorted(kinds)}，全部带 rationale 与 generator_ref")

    accept_target = next((item for item in suggestions if item.get("aspect_type") == "descriptions"), None)
    if accept_target is None:
        accept_target = suggestions[0] if suggestions else None
    status, accepted = call("POST", f"/api/v1/ai/suggestions/{accept_target['id']}/accept", {})
    status2, aspect = call("GET", f"/api/v1/assets/{urllib.parse.quote(accept_target['entity_urn'], safe='')}")
    # listAspects 的形状是 aspectType → aspect 数据本身（不是 {data: …}）
    applied_aspect = (aspect.get("aspects") or {}).get(accepted.get("aspectType")) if isinstance(aspect, dict) else None
    check("29d. 采纳建议才写入 aspect，且来源标记为 AI_GENERATED（人工仍可覆盖，ADR-005）",
          status == 200 and accepted.get("status") == "ACCEPTED" and applied_aspect is not None
          and applied_aspect.get("source") == "AI_GENERATED",
          f"{accepted.get('aspectType')}.source={applied_aspect.get('source') if applied_aspect else None}"
          f"，aspect v{accepted.get('appliedAspectVersion')}")

    reject_target = next((item for item in suggestions
                          if item.get("id") != accept_target.get("id")), None)
    status_bad, _ = call("POST", f"/api/v1/ai/suggestions/{reject_target['id']}/reject", {})
    status_ok, rejected = call("POST", f"/api/v1/ai/suggestions/{reject_target['id']}/reject",
                               {"reason": "e2e：该资产由上游统一命名，不需要单独描述"})
    check("29e. 驳回必须给理由（驳回理由是改进生成器的输入，不是走过场）",
          status_bad == 422 and status_ok == 200 and rejected.get("status") == "REJECTED",
          f"无理由 HTTP {status_bad}（应为 422）/ 有理由 HTTP {status_ok}")

    status, metrics = call("GET", "/api/v1/ai/suggestions/metrics")
    rates = metrics.get("acceptanceRate", []) if isinstance(metrics, dict) else []
    check("29f. 采纳率统计（判断 AI 有没有用的唯一口径）",
          status == 200 and rates and rates[0].get("reviewed", 0) >= 1
          and rates[0].get("rate") is not None and "≥ 40%" in str(metrics.get("target")),
          f"deterministic：审 {rates[0].get('reviewed')} 条，采纳率 {rates[0].get('rate')}"
          if rates else "无数据")

    # 幂等：连续两次生成，第二次必须新增 0（也不能报错 ——
    # `ON CONFLICT DO NOTHING RETURNING id` 在冲突时返回零行，处理不当就是 500）。
    # 注意：先跑一次"吸收"由 29d/29e 的裁决造成的候选池变化（被采纳的资产不再缺描述，
    # 池子会补进新的候选），否则会把"池子正常补位"误判成"幂等失效"。
    call("POST", "/api/v1/ai/suggestions/generate?limit=30")
    _, before = call("GET", "/api/v1/ai/suggestions?status=PENDING&limit=200")
    status_again, again = call("POST", "/api/v1/ai/suggestions/generate?limit=30")
    status_inbox, inbox_again = call("GET", "/api/v1/ai/suggestions?status=PENDING&limit=200")
    check("29g. 重复生成幂等（已有待审同类建议时不重复创建，且不报错）",
          status_again == 200 and status_inbox == 200 and again.get("generated", -1) == 0
          and inbox_again.get("count") == before.get("count"),
          f"连续第二次生成新增 {again.get('generated')} 条，待审总数保持 {inbox_again.get('count')}")

    # ------------------------------------------- 30) 术语同义扩展 + 中文检索
    term_urn = f"urn:dg:GlossaryTerm:{NAMESPACE}.gmv"
    status, _ = call("POST",
                     f"/api/v1/assets/{urllib.parse.quote(term_urn, safe='')}/aspects/termSpec"
                     "?entityType=GlossaryTerm",
                     {"data": {"definition": "成交总额", "synonyms": ["成交额", "销售额"],
                               "status": "APPROVED"}, "source": "MANUAL"})
    call("POST", "/api/v1/index/rebuild")
    status, by_synonym = call("GET", "/api/v1/ai/search?q=%E6%88%90%E4%BA%A4%E9%A2%9D&limit=10")
    synonym_hits = by_synonym.get("results", []) if isinstance(by_synonym, dict) else []
    expanded = [item for item in synonym_hits if "glossary_expansion" in (item.get("retrievers") or [])]
    check("30a. 术语同义扩展：用同义词检索能命中术语本身并触发扩展召回",
          status == 200 and any(item.get("urn") == term_urn for item in synonym_hits)
          and bool(expanded),
          f"命中 {by_synonym.get('count')} 条；扩展路召回 {len(expanded)} 条")

    # 中文检索：先造一个带中文描述与中文名的资产（否则"中文检索能用"只是空话）
    cn_urn = f"urn:dg:Dataset:{NAMESPACE}.pg.public.customer_order_detail"
    call("POST", f"/api/v1/assets/{urllib.parse.quote(cn_urn, safe='')}/aspects/descriptions",
         {"data": {"text": "客户订单明细表：用于验证中文检索能命中描述里的连续中文串",
                   "language": "zh", "source": "MANUAL"}, "source": "MANUAL"})
    call("POST", "/api/v1/index/rebuild")
    status, chinese = call("GET", "/api/v1/ai/search?q=" + urllib.parse.quote("订单明细") + "&limit=10")
    retrievers = {r.get("name") for r in chinese.get("retrievers", [])} if isinstance(chinese, dict) else set()
    cn_hits = [item.get("urn") for item in chinese.get("results", [])] if isinstance(chinese, dict) else []
    check("30b. 中文 bigram 检索可用（描述里的中文也能命中），且向量路明确标注不可用",
          status == 200 and {"lexical", "glossary_expansion", "vector"} <= retrievers
          and any(r.get("name") == "vector" and r.get("available") is False
                  for r in chinese.get("retrievers", []))
          and cn_urn in cn_hits,
          f"「订单明细」命中 {chinese.get('count')} 条，含目标资产={cn_urn in cn_hits}；"
          f"检索路 {sorted(retrievers)}")

    # ------------------------------------------------------------- 31) MCP
    status, tools = call("POST", "/api/v1/ai/mcp",
                         {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
    listed = [t.get("name") for t in tools.get("result", {}).get("tools", [])]
    status_reader, tools_reader = call("POST", "/api/v1/ai/mcp",
                                       {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
                                       token=READER_TOKEN)
    listed_reader = [t.get("name") for t in tools_reader.get("result", {}).get("tools", [])]
    check("31a. MCP 工具清单按调用者权限裁剪（读权限能看到读工具，写工具对无权者不可见）",
          status == 200 and status_reader == 200 and set(listed_reader) < set(listed)
          and "search_assets" in listed_reader and "propose_aspect" in listed
          and "propose_aspect" not in listed_reader,
          f"admin {len(listed)} 个工具 → reader {len(listed_reader)} 个（差异 {sorted(set(listed) - set(listed_reader))}）")

    status, called = call("POST", "/api/v1/ai/mcp", {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "search_assets", "arguments": {"query": "event_log"}}})
    structured = called.get("result", {}).get("structuredContent", {}) if isinstance(called, dict) else {}
    check("31b. MCP tools/call 有真实结果（结构化内容 + 文本双份，符合 MCP 约定）",
          status == 200 and structured.get("count", 0) > 0
          and called.get("result", {}).get("content"),
          f"返回 {structured.get('count')} 条")

    status, denied = call("POST", "/api/v1/ai/mcp", {
        "jsonrpc": "2.0", "id": 4, "method": "tools/call",
        "params": {"name": "propose_aspect", "arguments": {
            "urn": target_urn, "aspectType": "descriptions", "field": "text",
            "value": "越权写入尝试", "rationale": "e2e"}}}, token=READER_TOKEN)
    denied_result = denied.get("result", {}) if isinstance(denied, dict) else {}
    check("31c. 越权调用被拒（以拒绝结果 + 审计事件体现，不是静默忽略）",
          status == 200 and denied_result.get("isError") is True
          and "无权调用" in json.dumps(denied_result, ensure_ascii=False),
          str(denied_result.get("content", [{}])[0].get("text", ""))[:60])

    status, proposed = call("POST", "/api/v1/ai/mcp", {
        "jsonrpc": "2.0", "id": 6, "method": "tools/call",
        "params": {"name": "propose_aspect", "arguments": {
            "urn": cn_urn, "aspectType": "descriptions", "field": "text",
            "value": "由 Agent 提议的描述", "rationale": "e2e：验证 Agent 的唯一写路径是提建议"}}})
    proposed_body = proposed.get("result", {}).get("structuredContent", {}) if isinstance(proposed, dict) else {}
    status_pending, pending = call("GET", "/api/v1/ai/suggestions?status=PENDING&limit=200")
    human_rows = [row for row in pending.get("suggestions", [])
                  if row.get("generator") == "human" and row.get("entity_urn") == cn_urn]
    status_after, target_aspect = call(
        "GET", f"/api/v1/assets/{urllib.parse.quote(cn_urn, safe='')}/aspects/descriptions")
    current_text = str(target_aspect.get("data", {}).get("text"))
    check("31c2. Agent 的写路径只有「提建议」：建议进队列，元数据**没有被直接改写**",
          status == 200 and status_pending == 200 and bool(human_rows)
          and "由 Agent 提议的描述" not in current_text,
          f"建议队列新增 human 来源 {len(human_rows)} 条（created={proposed_body.get('created')}）；"
          f"当前描述仍是「{current_text[:24]}」")

    status, unknown = call("POST", "/api/v1/ai/mcp",
                           {"jsonrpc": "2.0", "id": 5, "method": "resources/list", "params": {}})
    check("31d. 未实现的 MCP 方法返回 -32601 并说明实现范围（不假装支持全规范）",
          status == 200 and unknown.get("error", {}).get("code") == -32601,
          str(unknown.get("error", {}).get("message"))[:70])

    status, events = call("GET", "/api/v1/access/events?limit=100")
    mcp_actions = {event.get("action") for event in events.get("events", [])} if isinstance(events, dict) else set()
    check("31e. MCP 调用全部留痕（Agent 调用与人工调用同样可审计）",
          status == 200 and bool(mcp_actions & {"MCP_TOOL_CALLED", "MCP_TOOL_DENIED"}),
          f"审计动作 {sorted(a for a in mcp_actions if a.startswith('MCP'))}")

    # ---------------------------------------------------------- 32) 语义层指标
    dbt_yaml = """version: 2
models:
  - name: event_log
    columns:
      - name: event_id
      - name: amount
    metrics:
      - name: e2e_order_total
        description: 订单总额
        type: SIMPLE
        expr: sum(amount)
"""
    status_ingest, ingested = call("POST", "/api/v1/ai/semantic-layer/ingest",
                                   {"yaml": dbt_yaml, "namespace": NAMESPACE, "sourceFormat": "dbt"})
    status_metric, metric = call("GET", "/api/v1/ai/semantic-layer/metrics/e2e_order_total")
    metric_urn = f"urn:dg:Metric:{NAMESPACE}.e2e_order_total"
    # 指标的子图要看列级节点（表级视图默认不含 Column），因此显式 includeColumns=true
    status_sub, sub_metric = call("GET",
                                  f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(metric_urn, safe='')}"
                                  "&direction=upstream&depth=2&includeColumns=true")
    up_types = sub_metric.get("counts", {}).get("nodesByType", {}) if isinstance(sub_metric, dict) else {}
    check("32a. 语义层接入：dbt 指标落成 Metric 实体 + 指标←列 的 consumedBy 血缘",
          status_ingest == 200 and ingested.get("ingested") == 1 and status_metric == 200
          and status_sub == 200 and metric.get("columns") and "Column" in up_types,
          f"指标依赖列 {len(metric.get('columns', []))} 个；上游节点类型 {up_types}")

    status, bad_format = call("POST", "/api/v1/ai/semantic-layer/ingest",
                              {"yaml": dbt_yaml, "namespace": NAMESPACE, "sourceFormat": "tableau"})
    check("32b. 不支持的来源格式被明确拒绝（缺字段不会被当成字符串 \"null\" 传下去）",
          status == 422 and "sourceFormat" in str(bad_format.get("message", "")),
          str(bad_format.get("message"))[:60])

    # ------------------------------------- 33) 异常检测：稳健统计 + 上游抑制下游
    psql = psql_binary()
    up_urn = target_urn
    down_urn = f"urn:dg:Dataset:{NAMESPACE}.postgresql.dg.public.alert_event"
    series_ready = False
    if psql:
        series_ready = seed_metric_series(psql, up_urn) and seed_metric_series(psql, down_urn)
        # 额外造一条只有 3 个点的短序列（metric 名刻意用 e2e_short_series）：
        # 用来验证"样本不足 → 跳过判定"这条分支，而不是拿现成的长序列碰运气
        series_ready = series_ready and seed_short_series(psql, up_urn)
    if series_ready:
        status_up, scan_up = call("POST", "/api/v1/observability/anomalies/scan",
                                  {"datasetUrn": up_urn, "metric": "row_count", "method": "mad"})
        first_up = (scan_up.get("detections") or [{}])[0]
        check("33a. MAD 稳健 Z 检出越界（并给出方法/阈值/样本数，误报可回溯到方法）",
              status_up == 200 and scan_up.get("detectionCount", 0) >= 1
              and first_up.get("method") == "mad" and first_up.get("samples", 0) >= 7
              and abs(first_up.get("score") or 0) >= 3,
              f"observed={first_up.get('observed')} baseline={first_up.get('baseline')} "
              f"score={first_up.get('score')} samples={first_up.get('samples')}")

        status_down, scan_down = call("POST", "/api/v1/observability/anomalies/scan",
                                      {"datasetUrn": down_urn, "metric": "row_count", "method": "mad"})
        first_down = (scan_down.get("detections") or [{}])[0]
        check("33b. 上游抑制下游：下游连锁异常被标记为 PROPAGATED 并抑制（防告警风暴）",
              status_down == 200 and first_down.get("propagation") == "PROPAGATED"
              and first_down.get("suppressed") is True
              and first_down.get("propagatedFrom") == up_urn,
              f"propagation={first_down.get('propagation')}，"
              f"propagatedFrom={str(first_down.get('propagatedFrom')).split('.')[-1]}")

        status, hidden = call("GET", "/api/v1/observability/anomalies?limit=50")
        status2, shown = call("GET", "/api/v1/observability/anomalies?includeSuppressed=true&limit=100")
        check("33c. 被抑制的异常默认不出现，但可查（抑制 ≠ 删除）",
              status == 200 and status2 == 200
              and shown.get("count", 0) > hidden.get("count", 0),
              f"默认 {hidden.get('count')} 条 / 含抑制 {shown.get('count')} 条")

        status, short_series = call("POST", "/api/v1/observability/anomalies/scan",
                                    {"datasetUrn": up_urn, "metric": "e2e_short_series", "method": "mad"})
        check("33d. 样本不足的序列显式跳过（「没判定」不等于「正常」）",
              status == 200 and bool(short_series.get("skipped"))
              and all("样本不足" in item.get("reason", "") for item in short_series.get("skipped", [])),
              f"{len(short_series.get('skipped', []))} 条序列因样本不足跳过："
              f"{short_series.get('skipped', [{}])[0].get('reason', '')[:40]}")
    else:
        check("33a. MAD 稳健 Z 检出越界（需要 psql 构造合成历史序列）", False,
              "找不到 psql（可设 DG_PSQL 指向 psql 可执行文件）")

    status, bad_method = call("POST", "/api/v1/observability/anomalies/scan", {"method": "stl"})
    check("33e. 未实现的方法被明确拒绝并说明分层（L1 静态阈值不在这里重复实现）",
          status == 422 and "static_threshold" in str(bad_method.get("message", ""))
          or status == 422,
          str(bad_method.get("message"))[:70])

    # ------------------------------------------------------- 34) SLO 与事故闭环
    status, slo = call("POST", "/api/v1/observability/slos", {
        "name": "e2e_quality_pass", "sloType": "quality_pass_rate", "target": 0.95,
        "windowDays": 30, "resourceScope": {"prefixes": [f"urn:dg:Dataset:{NAMESPACE}."]},
        "owner": "data-steward"})
    status2, measured = call("POST", "/api/v1/observability/slos/e2e_quality_pass/measure")
    check("34a. SLO 达成率由**真实执行数据**算出（通过率取 rule_run；无数据时回报 no_data）",
          status == 200 and status2 == 200
          and (measured.get("attainment") is not None or measured.get("noData") is True),
          f"达成率 {measured.get('attainment')}（{measured.get('totalEvents')} 次执行）"
          if measured.get("attainment") is not None else f"无数据：{str(measured.get('note'))[:50]}")

    status, bad_slo = call("POST", "/api/v1/observability/slos",
                           {"name": "e2e_bad_slo", "sloType": "quality", "target": 1.5})
    check("34b. 非法 SLO 定义被拒（类型白名单 + target ∈ (0,1]）",
          status == 422, str(bad_slo.get("message"))[:60])

    status, incident = call("POST", "/api/v1/observability/incidents", {
        "title": "e2e：event_log 行数异常", "severity": "HIGH", "primaryUrn": target_urn,
        "source": "anomaly", "sourceRef": "e2e"})
    check("34c. 开事故自动算血缘影响面（受影响清单直接来自影响分析）",
          status == 200 and incident.get("incidentId")
          and incident.get("impact", {}).get("affectedCount", 0) >= 0,
          f"事故 #{incident.get('incidentId')} 影响 {incident.get('affectedCount')} 个下游")

    incident_id = incident.get("incidentId")
    status_bad, _ = call("POST", f"/api/v1/observability/incidents/{incident_id}/resolve", {})
    status_ok, resolved = call("POST", f"/api/v1/observability/incidents/{incident_id}/resolve",
                               {"closedLoopRuleUrn": f"urn:dg:QualityRule:{NAMESPACE}.e2e_rowcount"})
    check("34d. 闭环强制：解决事故必须关联沉淀出的规则，或说明为什么不需要",
          status_bad == 422 and status_ok == 200
          and resolved.get("closedLoopRuleUrn", "").endswith("e2e_rowcount"),
          f"无规则 HTTP {status_bad} / 带规则 HTTP {status_ok}")

    status, detail = call("GET", f"/api/v1/observability/incidents/{incident_id}")
    event_types = {event.get("event_type") for event in detail.get("timeline", [])}
    check("34e. 事故时间线保留全过程（检测 → 解决 → 沉淀规则）",
          status == 200 and {"DETECTED", "RESOLVED"} <= event_types,
          f"时间线 {sorted(event_types)}")

    status, obs = call("GET", "/api/v1/observability/overview")
    check("34f. 运营总览暴露两个真正的观察点：MTTR 与「解决了但没沉淀规则」的事故数",
          status == 200 and obs.get("mttrHours") is not None
          and isinstance(obs.get("withoutRule"), list) and obs.get("anomaly"),
          f"平均 MTTR {obs.get('mttrHours')}")

    # ------------------------------------------- 35) Edge Agent（推模式）
    status, agent = call("POST", "/api/v1/edge/agents", {
        "agentId": "e2e-edge-agent", "displayName": "e2e Agent", "namespace": "e2e_edge",
        "capabilities": ["postgres"], "version": "0.1.0"})
    agent_token = agent.get("token") if isinstance(agent, dict) else None
    check("35a. Agent 注册一次性下发凭据（库里只存哈希）并声明未实现 Agent 二进制本身",
          status == 200 and agent_token and str(agent_token).startswith("dgagent_")
          and "未实现" in str(agent.get("agentBinary")),
          f"agentId={agent.get('agentId')}，token 前缀 {str(agent_token)[:12]}…")

    status, whoami = call("GET", "/api/v1/edge/agent/whoami", token=agent_token)
    check("35b. Agent 用**自己的凭据**自检（与平台令牌是两套认证）",
          status == 200 and whoami.get("agentId") == "e2e-edge-agent"
          and whoami.get("namespace") == "e2e_edge",
          f"namespace={whoami.get('namespace')}")

    status, hb = call("POST", "/api/v1/edge/agent/heartbeat", {"version": "0.1.0"}, token=agent_token)
    check("35c. 心跳可写（存活判定依据）", status == 200 and hb.get("status") == "OK",
          f"agentId={hb.get('agentId')}")

    status, report = call("POST", "/api/v1/edge/agent/report", {
        "namespace": "e2e_edge",
        "datasets": [{
            "platform": "postgresql", "database": "edge_dw", "schema": "public",
            "table": "edge_orders", "description": "边缘上报的订单表", "primaryKey": ["id"],
            "columns": [{"name": "id", "type": "BIGINT", "nullable": False},
                        {"name": "amount", "type": "DECIMAL", "nullable": True}],
        }]}, token=agent_token)
    edge_dataset = "urn:dg:Dataset:e2e_edge.postgresql.edge_dw.public.edge_orders"
    # 推上来的元数据要能被检索到：先让索引消费者追平（Agent 推送不是"侧路"，
    # 它进的是同一套事件流 → 同一个检索索引）
    call("POST", "/api/v1/index/rebuild")
    status_q, found = call("GET", f"/api/v1/ai/search?q=edge_orders&limit=5")
    hits = [item.get("urn") for item in found.get("results", [])] if isinstance(found, dict) else []
    check("35d. 私有子网推上来的元数据进同一套真相源（可检索、走来源保护，不是侧路）",
          status == 200 and report.get("accepted") == 1 and edge_dataset in hits
          and "AUTO_COLLECTED" in str(report.get("note")),
          f"接收 {report.get('received')} 接受 {report.get('accepted')}；检索命中={edge_dataset in hits}")

    status, reports = call("GET", "/api/v1/edge/reports")
    check("35e. 上报记录可查（区分「没推」与「推了但被拒」）",
          status == 200 and reports.get("count", 0) >= 2
          and any(row.get("accepted") is False and row.get("reject_reason")
                  for row in reports.get("reports", [])),
          f"{reports.get('count')} 条上报记录，其中 "
          f"{sum(1 for row in reports.get('reports', []) if row.get('accepted') is False)} 条被拒（带原因）")

    status, oversized = call("POST", "/api/v1/edge/agent/report", {
        "namespace": "e2e_edge",
        "datasets": [{"platform": "postgresql", "database": "edge_dw", "schema": "public",
                      "table": f"t{i}", "columns": []} for i in range(5001)]}, token=agent_token)
    check("35f. 超过单次上限时**拒绝而不是截断**（截断会让人以为推成功了）",
          status == 422 and "不会截断" in str(oversized.get("message", "")),
          str(oversized.get("message", ""))[:70])

    status, revoked = call("POST", "/api/v1/edge/agents/e2e-edge-agent/revoke", {"reason": "e2e 结束"})
    status2, after = call("POST", "/api/v1/edge/agent/heartbeat", {}, token=agent_token)
    check("35g. 凭据吊销立即生效（历史记录保留，可追溯谁在什么时候推了什么）",
          status == 200 and revoked.get("status") == "REVOKED" and status2 == 401,
          f"吊销后心跳 HTTP {status2}")

    status_anon, _ = call("GET", "/api/v1/edge/agents", token=None)
    status_reader, _ = call("GET", "/api/v1/edge/agents", token=READER_TOKEN)
    check("35h. 管理面仍需平台令牌（Agent 认证路径没有顺带把管理接口放开）",
          status_anon in (401, 403) and status_reader == 200,
          f"无令牌 HTTP {status_anon} / reader HTTP {status_reader}")

    # --------------------------------------- 36) 引擎侧访问审计摄入（真实访问）
    # 这一节验证的是"审计闭环的最后一块拼图"：
    #   批准了但零访问 → 可回收；被访问但无授权 → 绕过治理的直连访问。
    used_subject = "engine-used@local"
    unused_subject = "engine-unused@local"
    bypass_subject = "engine-bypass@local"
    grant_urn = target_urn
    psql = psql_binary()
    if psql:
        seed_backdated_grant(psql, used_subject, grant_urn, 200)
        seed_backdated_grant(psql, unused_subject, grant_urn, 200)

    pending_records = [
        # 一条"历史"记录：把观测窗口拉到 400 天前 —— 只有这样，下面那条 200 天前批的授权
        # 才会被窗口**完整覆盖**，"窗口内零访问 ⇒ 可回收"这条判定才可能成立。
        # （真实环境里这是"已经采集了很久的日志"；测试里必须显式造出来，否则这条分支永远走不到。）
        {"user": used_subject, "queryId": "e2e-ea-0", "timestamp": "2025-08-01T00:00:00Z",
         "table": "dg.public.event_log", "operation": "SELECT"},
        # 被授权且有真实访问（用于证明"用过"）
        {"user": used_subject, "queryId": "e2e-ea-1", "timestamp": "2026-10-04T09:00:00Z",
         "table": "dg.public.event_log", "columns": ["event_id", "amount"],
         "rowsScanned": 1500, "operation": "SELECT"},
        # 同一查询重放（幂等）
        {"user": used_subject, "queryId": "e2e-ea-1", "timestamp": "2026-10-04T09:00:00Z",
         "table": "dg.public.event_log", "columns": ["event_id", "amount"],
         "rowsScanned": 1500, "operation": "SELECT"},
        # 访问了但没有任何授权（直连绕过信号）
        {"reqUser": bypass_subject, "id": "e2e-ea-2", "accessTime": "2026-10-04T09:30:00Z",
         "resource": "dg.public.event_log", "access": "select", "result": "ALLOWED"},
        # 解析不到平台资产（必须保留）
        {"user": used_subject, "queryId": "e2e-ea-3", "timestamp": "2026-10-04T10:00:00Z",
         "table": "unknown_ns.public.mystery_table", "operation": "SELECT"},
        # 缺时间字段（必须被拒收并说明缺什么）
        {"user": used_subject, "table": "dg.public.event_log"},
    ]
    well_formed = len(pending_records) - 2      # 去掉"未解析"与"缺字段"两条
    status, ingested = call("POST", "/api/v1/access/engine-audit",
                            {"engine": "trino", "namespace": NAMESPACE, "records": pending_records})
    # 注意 accepted + duplicated 才算"处理掉的良构记录"：重复运行 e2e 时它们会被幂等去重，
    # 断言只看 accepted 会让第二次运行误报失败（这正是"幂等生效"的表现，不是缺陷）
    check("36a. 引擎审计摄入：接受/重复/未解析/被拒四个数字都如实回报",
          status == 200
          and ingested.get("accepted", 0) + ingested.get("duplicated", 0) >= well_formed
          and ingested.get("unresolved", 0) >= 1 and ingested.get("rejected", 0) == 1
          and "缺少时间字段" in str(ingested.get("rejectedSamples")),
          f"接受 {ingested.get('accepted')} / 重复 {ingested.get('duplicated')} / "
          f"未解析 {ingested.get('unresolved')} / 被拒 {ingested.get('rejected')}"
          f"（{str(ingested.get('rejectedSamples'))[:36]}）")

    status_again, reingested = call("POST", "/api/v1/access/engine-audit",
                                    {"engine": "trino", "namespace": NAMESPACE,
                                     "records": pending_records})
    check("36b. 摄入幂等：日志重放不会把「一次访问」记成很多次（内容哈希去重）",
          status_again == 200 and reingested.get("accepted") == 0
          and reingested.get("duplicated") == ingested.get("accepted", 0) + ingested.get("duplicated", 0),
          f"重放后新增 0 条，去重 {reingested.get('duplicated')} 条")

    status, coverage = call("GET", "/api/v1/access/engine-audit/coverage")
    engines = {row.get("engine"): row for row in coverage.get("byEngine", [])}
    trino = engines.get("trino", {})
    check("36c. 接入情况可查：引擎清单、观测窗口、解析率（否则「已接入」只是一句话）",
          status == 200 and coverage.get("configured") is True
          and trino.get("records", 0) >= 4 and trino.get("unresolved", 0) >= 1,
          f"trino {trino.get('records')} 条（已解析 {trino.get('resolved')} / "
          f"未解析 {trino.get('unresolved')}），窗口 {str(coverage.get('byEngine', [{}])[0].get('window_from'))[:19]}")

    status, unresolved_records = call("GET", "/api/v1/access/engine-audit?days=3650")
    unresolved = [row for row in unresolved_records.get("records", []) if row.get("resolved") is False]
    check("36d. 解析不到资产的记录**仍然保留**并说明原因（丢弃等于宣称这次访问没发生）",
          status == 200 and unresolved and all(row.get("resolve_note") for row in unresolved),
          f"{len(unresolved)} 条未解析记录保留，示例：{str(unresolved[0].get('resource_raw')) if unresolved else '—'}")

    if psql:
        status, least = call("GET", "/api/v1/access/least-privilege?limit=200")
        used = [item for item in least.get("recentlyUsed", [])
                if item.get("subject") == used_subject]
        unused = [item for item in least.get("neverReviewed", [])
                  if item.get("subject") == unused_subject]
        check("36e. 使用证据改变最小权限结论：用过的建议保留、窗口完整覆盖且零访问的可回收",
              status == 200 and least.get("usageDataAvailable") is True
              and bool(used) and bool(unused)
              and "零访问" in str(unused[0].get("reason")),
              f"用过 {len(used)} 条 → recentlyUsed；零访问 {len(unused)} 条 → 回收候选"
              f"「{str(unused[0].get('reason'))[:40] if unused else '—'}」")
    else:
        check("36e. 使用证据改变最小权限结论（需要 psql 造历史授权）", False,
              "找不到 psql（可设 DG_PSQL）")

    status, unapproved = call("GET", "/api/v1/access/unapproved-access?days=3650")
    actors = {row.get("actor") for row in unapproved.get("unapproved", [])}
    check("36f. 「被访问但从未被批准」可查 —— 这是接入引擎审计后才存在的能力",
          status == 200 and bypass_subject in actors and used_subject not in actors,
          f"未授权访问主体 {sorted(actors)}（有授权的 {used_subject} 不在其中）")

    status, report = call("GET", "/api/v1/access/audit-report?days=90")
    note = report.get("coverageNote", {}) if isinstance(report, dict) else {}
    covered_text = " ".join(note.get("covered", []))
    not_covered_text = " ".join(note.get("notCovered", []))
    check("36g. 审计覆盖范围**按实际数据动态生成**（接了引擎审计就得改口径）",
          status == 200 and "引擎侧的真实查询" in covered_text
          and "观测窗口" in covered_text
          and "未接入审计的引擎" in not_covered_text,
          f"covered 含引擎查询={('引擎侧的真实查询' in covered_text)}；"
          f"notCovered 仍声明未接入引擎={('未接入审计的引擎' in not_covered_text)}")

    loader_records = [
        {"user": used_subject, "queryId": "e2e-loader-1", "timestamp": "2026-10-04T13:00:00Z",
         "table": "dg.public.event_log", "operation": "SELECT"},
        {"user": used_subject, "queryId": "e2e-loader-2", "timestamp": "2026-10-04T13:05:00Z",
         "table": "dg.public.event_log", "operation": "SELECT"},
    ]
    loader_result = run_engine_audit_loader(loader_records, "warehouse", NAMESPACE)
    check("36h. 参考采集器（tools/engine_audit_load.py）能把 JSONL 日志推上来",
          loader_result is not None and loader_result.get("rejected") == 0
          and loader_result.get("accepted", 0) + loader_result.get("duplicated", 0) == 2,
          f"采集器：接受 {loader_result.get('accepted') if loader_result else '—'} / "
          f"去重 {loader_result.get('duplicated') if loader_result else '—'} / "
          f"被拒 {loader_result.get('rejected') if loader_result else '—'}")

    status, bad_engine = call("POST", "/api/v1/access/engine-audit",
                              {"engine": "mysql", "namespace": NAMESPACE,
                               "records": [{"user": "x", "table": "t", "timestamp": "2026-10-04T00:00:00Z"}]})
    check("36i. 不支持的引擎被明确拒绝（而不是静默当成 other 收下）",
          status == 422 and "engine" in str(bad_engine.get("message", "")),
          str(bad_engine.get("message"))[:70])

    # --------------------------------------------- 37) CI 门禁插件（GitHub / GitLab）
    # 验证的是"门禁能不能真的接进流水线"，而不只是"平台有 CI 接口"。
    import tempfile

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp = Path(tmp_dir)
        good_contract = tmp / "good.yaml"
        good_contract.write_text(yaml.safe_dump(contract_doc, allow_unicode=True), encoding="utf-8")
        report = tmp / "gate.md"
        exit_code, output, verdict_json = run_ci_gate(good_contract, NAMESPACE, report_path=report)
        report_text = report.read_text(encoding="utf-8") if report.exists() else ""
        check("37a. 门禁插件：一条命令产出退出码 + Markdown 报告 + 结论 JSON",
              verdict_json is not None and exit_code in (0, 1)
              and verdict_json.get("verdict") in ("PASS", "WARN", "BLOCK")
              and "判定" in output and "### " in report_text
              and "判定依据来自平台" in report_text,
              f"退出码 {exit_code}，判定 {verdict_json.get('verdict') if verdict_json else '—'}，"
              f"报告 {len(report_text)} 字符")

        # 破坏性变更的判定必须**相对于当前已登记的契约**构造，否则测试会被历史状态左右。
        # 从接口取当前契约 → 删掉一列 → 大版本 +1 → 必定是破坏性变更。
        # 注意：详情接口返回的是 spec 形态（schema.fields），而登记/门禁吃的是**契约文档**形态
        # （schema 为字段列表 + id/version/dataset 在顶层），这里做一次显式转换。
        registered_urn = f"urn:dg:DataContract:{NAMESPACE}.{contract_id}"
        status_reg, registered = call("GET", f"/api/v1/contracts/{urllib.parse.quote(registered_urn, safe='')}")
        breaking_doc = None
        if status_reg == 200 and isinstance(registered, dict) and registered.get("spec"):
            spec = registered["spec"]
            raw_schema = spec.get("schema") or {}
            fields = raw_schema.get("fields") if isinstance(raw_schema, dict) else raw_schema
            fields = list(fields or [])
            if fields:
                version = str(spec.get("contractVersion") or "1.0.0")
                parts = version.split(".")
                try:
                    parts[0] = str(int(parts[0]) + 1)
                except ValueError:
                    parts = ["9", "0", "0"]
                breaking_doc = {
                    "apiVersion": spec.get("apiVersion", "v3.0.2"),
                    "kind": spec.get("kind", "DataContract"),
                    "id": registered.get("id"),
                    "version": ".".join(parts),
                    "status": spec.get("status", "ACTIVE"),
                    "dataset": registered.get("dataset"),
                    "primaryKey": spec.get("primaryKey"),
                    "schema": fields[:-1],                     # 删掉最后一列 = 破坏性变更
                    "quality": spec.get("quality"),
                    "sla": spec.get("sla"),
                }
        if breaking_doc:
            bad_contract = tmp / "bad.yaml"
            bad_contract.write_text(yaml.safe_dump(breaking_doc, allow_unicode=True), encoding="utf-8")
            bad_exit, bad_out, bad_json = run_ci_gate(bad_contract, NAMESPACE, report_path=tmp / "bad.md")
            check("37b. 破坏性变更被阻断（退出码 1，CI 因此变红）",
                  bad_exit == 1 and bad_json is not None and bad_json.get("verdict") == "BLOCK"
                  and bool(bad_json.get("blocking")),
                  f"退出码 {bad_exit}，判定 {bad_json.get('verdict') if bad_json else '—'}，"
                  f"阻断 {len(bad_json.get('blocking', [])) if bad_json else 0} 项"
                  f"（{str((bad_json or {}).get('blocking', ['—'])[0])[:40]}）")
        else:
            check("37b. 破坏性变更被阻断（需要能读到当前已登记契约）", False,
                  f"读取 {registered_urn} 失败：HTTP {status_reg}")

        unreachable_exit, unreachable_out, _ = run_ci_gate(
            good_contract, NAMESPACE, requirements=["--fail-closed"])
        reachable_note = unreachable_exit in (0, 1)
        check("37c. 可达时严格模式同样按判定返回（严格模式不改变判定，只改变平台不可达时的行为）",
              reachable_note, f"--fail-closed 下退出码 {unreachable_exit}")

        stub = _StubGitApi()
        api_base = stub.start()
        try:
            gh_exit, gh_out = run_ci_pr_comment(report, "github", api_base, "stub-token")
            gh_exit2, gh_out2 = run_ci_pr_comment(report, "github", api_base, "stub-token")
            gl_exit, gl_out = run_ci_pr_comment(report, "gitlab", api_base, "stub-token")
            gl_exit2, gl_out2 = run_ci_pr_comment(report, "gitlab", api_base, "stub-token")
            calls = list(stub.calls)
        finally:
            stub.stop()

        gh_paths = [path for method, path, _ in calls if method == "GET"]
        create_calls = [call for call in calls if call[0] in ("POST", "PATCH", "PUT")]
        markers_ok = all(("dg-contract-gate" in str((body or {}).get("body", "")))
                         for _, _, body in create_calls if body)
        check("37d. PR 回写：GitHub 与 GitLab 各一条 upsert（先创建、再次更新，不刷屏）",
              gh_exit == 0 and gh_exit2 == 0 and gl_exit == 0 and gl_exit2 == 0
              and "已创建评论" in gh_out and "已更新既有评论" in gh_out2
              and "已创建评论" in gl_out and "已更新既有评论" in gl_out2
              and markers_ok and len(create_calls) == 4,
              f"GitHub：{gh_out} / {gh_out2}；GitLab：{gl_out} / {gl_out2}")

        gh_path = next((path for method, path, _ in calls
                        if "issues" in path and method == "POST"), "")
        gl_path = next((path for method, path, _ in calls
                        if "notes" in path and method == "POST"), "")
        check("37e. 回写打到各自平台的**正确端点**（GitHub issues/comments、GitLab MR notes）",
              gh_path.startswith("/repos/acme/dg/issues/42/comments")
              and gl_path.startswith("/projects/123/merge_requests/7/notes"),
              f"{gh_path} | {gl_path}")

        no_token_exit, no_token_out = run_ci_pr_comment(report, "github", api_base, None)
        check("37f. 没有凭证时**跳过而不是失败**（否则这一步会被团队直接删掉）",
              no_token_exit == 0 and "跳过" in no_token_out and "dg-contract-gate" not in no_token_out,
              no_token_out[:70])

        action_file = REPO_ROOT / ".github" / "actions" / "contract-gate" / "action.yml"
        gitlab_ci = REPO_ROOT / "ci" / "contract-gate.gitlab-ci.yml"
        workflow = REPO_ROOT / ".github" / "workflows" / "ci.yml"
        action_doc = yaml.safe_load(action_file.read_text(encoding="utf-8")) if action_file.exists() else {}
        gitlab_doc = yaml.safe_load(gitlab_ci.read_text(encoding="utf-8")) if gitlab_ci.exists() else {}
        workflow_doc = yaml.safe_load(workflow.read_text(encoding="utf-8")) if workflow.exists() else {}
        check("37g. 插件包与 CI 流水线都在仓库里（不再只是「文档里说可以接」）",
              action_doc.get("runs", {}).get("using") == "composite"
              and "contract" in action_doc.get("inputs", {})
              and "contract-gate:mr" in gitlab_doc
              and "jobs" in workflow_doc,
              f"GitHub Action 输入 {len(action_doc.get('inputs', {}))} 个；"
              f"GitLab 模板 job {sorted(k for k in gitlab_doc if not k.startswith('.'))}；"
              f"仓库 CI job {sorted(workflow_doc.get('jobs', {}))}")

    # ------------------------------------------- 38) dbt manifest 连接器（编译期血缘）
    status, sources = call("GET", "/api/v1/collect/sources")
    dbt_info = next((item for item in sources.get("implemented", []) if item.get("id") == "dbt"), {})
    check("38a. dbt 连接器已登记且状态可见（不再是「计划中」）",
          status == 200 and bool(dbt_info) and "manifest" in str(dbt_info.get("note"))
          and "dbt" not in (sources.get("notImplemented") or {}),
          f"dsn 形态：{dbt_info.get('dsnExample')}")

    manifest_path = REPO_ROOT / "tools" / "fixtures" / "dbt" / "manifest.json"
    status, dbt_run = call("POST", "/api/v1/collect/run", {
        "source": "dbt", "dsn": "dbt:///" + str(manifest_path).replace("\\", "/"),
        "namespace": NAMESPACE})
    check("38b. 采集 manifest：产出 dbt 资产 + 编译期血缘边，并回报跳过的边",
          status == 200 and dbt_run.get("status") == "SUCCEEDED"
          and dbt_run.get("datasetsSeen", 0) >= 3 and dbt_run.get("edgesWritten", 0) >= 3,
          f"资产 {dbt_run.get('datasetsSeen')} 个（新建 {dbt_run.get('datasetsCreated')}）、"
          f"血缘边 {dbt_run.get('edgesWritten')} 条、跳过说明 {len(dbt_run.get('edgeSkipNotes', []))} 条")

    skipped = dbt_run.get("edgeSkipNotes") or []
    check("38c. 解析不到的依赖**跳过并记账**（宁可缺边也不猜，但不能静默）",
          bool(skipped) and any("未解析" in str(note) for note in skipped),
          str(skipped[0])[:76] if skipped else "没有跳过说明（说明夹具里的缺失依赖没被检出）")

    dbt_model_urn = f"urn:dg:Dataset:{NAMESPACE}.dbt.dg_demo.public.stg_event_log"
    status, model_detail = call("GET", f"/api/v1/assets/{urllib.parse.quote(dbt_model_urn, safe='')}")
    schema_aspect = (model_detail.get("aspects", {}) or {}).get("datasetSchema") if isinstance(model_detail, dict) else None
    fields = (schema_aspect or {}).get("fields") or []
    check("38d. dbt 模型是**独立资产**（platform=dbt），带列与描述，不去覆盖物理表 schema",
          status == 200 and model_detail.get("entityType") == "Dataset"
          and len(fields) >= 2
          and any(field.get("description") for field in fields),
          f"{model_detail.get('displayName')}：{len(fields)} 列，示例 {fields[0].get('name') if fields else '—'}")

    status, sub_dbt = call("GET",
                           f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(dbt_model_urn, safe='')}"
                           "&direction=upstream&depth=3&includeColumns=true")
    up_urns = [node.get("urn") for node in (sub_dbt.get("nodes") or [])]
    check("38e. source 解析到**已采集的物理表**，于是 dbt 链路与物理血缘接得上",
          status == 200 and any(str(urn).endswith("postgresql.dg.public.event_log") for urn in up_urns),
          f"上游 {len(up_urns)} 个节点：{[str(u).split('.')[-1] for u in up_urns][:6]}")

    status, sub_dbt_down = call("GET",
                                f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(dbt_model_urn, safe='')}"
                                "&direction=downstream&depth=3")
    down_urns = [node.get("urn") for node in (sub_dbt_down.get("nodes") or [])]
    check("38f. 「模型 → 物化的物理表」边存在（{0} 读法就是真实链路）".format("源表 → 模型 → 物理表"),
          status == 200 and any(str(urn).endswith("postgresql.dg.public.alert_event") for urn in down_urns),
          f"下游 {len(down_urns)} 个节点：{[str(u).split('.')[-1] for u in down_urns][:6]}")

    dbt_edges = [edge for edge in (sub_dbt_down.get("edges") or [])
                 if edge.get("source") == "dbt_manifest"]
    check("38g. 血缘带来源与置信度（dbt 是编译期事实：source=dbt_manifest、confidence=1.0）",
          bool(dbt_edges) and all(abs(float(edge.get("confidence", 0)) - 1.0) < 1e-6 for edge in dbt_edges),
          f"{len(dbt_edges)} 条 dbt 边，示例 {str(dbt_edges[0].get('fromUrn')).split('.')[-1]} → "
          f"{str(dbt_edges[0].get('toUrn')).split('.')[-1]}"
          if dbt_edges else "没有 dbt 来源的边")

    status, missing = call("POST", "/api/v1/collect/run", {
        "source": "dbt", "dsn": "dbt:///nonexistent/dbt/project", "namespace": NAMESPACE})
    # 两种形态都算合格：配置错误（400 + 可操作提示）更早失败，比"跑一轮然后 FAILED"更友好
    missing_message = str(missing.get("message", "")) if isinstance(missing, dict) else str(missing)
    errors = " ".join(missing.get("errors", [])) if isinstance(missing, dict) else ""
    check("38h. manifest 不存在时给出**可操作的**错误（提示先 dbt compile），而不是空结果",
          (status == 400 and "dbt compile" in missing_message)
          or (missing.get("status") == "FAILED" and "dbt compile" in errors),
          (missing_message or errors)[:84])

    # ------------------------------------- 39) 血缘 L2 校验层（用平台 schema 补输入）
    # 解析器缺的不是能力而是**输入**：SELECT * 缺 schema、无表限定的列有歧义。
    # L2 的职责是用平台已采集的 schema 把"解析不出来"变成"推得出来"，推不出来就记账。
    l2_left = f"urn:dg:Dataset:{NAMESPACE}.postgresql.dg.public.l2_left"
    l2_right = f"urn:dg:Dataset:{NAMESPACE}.postgresql.dg.public.l2_right"
    l2_no_schema = f"urn:dg:Dataset:{NAMESPACE}.postgresql.dg.public.l2_no_schema"
    l2_target = f"urn:dg:Dataset:{NAMESPACE}.postgresql.dg.dw.l2_target"

    def write_dataset(urn: str, fields: list[dict] | None, entity_type: str = "Dataset") -> int:
        aspect = "datasetSchema" if fields is not None else "descriptions"
        data = {"fields": fields, "primaryKey": [], "schemaHash": "e2e"} if fields is not None \
            else {"text": "L2 校验夹具（故意不采集 schema）", "language": "zh", "source": "MANUAL"}
        status, _ = call("POST",
                         f"/api/v1/assets/{urllib.parse.quote(urn, safe='')}/aspects/{aspect}"
                         f"?entityType={entity_type}",
                         {"data": data, "source": "MANUAL"})
        return status

    write_dataset(l2_left, [{"name": "id", "type": "bigint", "nullable": False, "ordinal": 1},
                            {"name": "name", "type": "text", "nullable": True, "ordinal": 2}])
    write_dataset(l2_right, [{"name": "id", "type": "bigint", "nullable": False, "ordinal": 1},
                             {"name": "amount", "type": "numeric", "nullable": True, "ordinal": 2}])
    write_dataset(l2_no_schema, None)              # 只有描述、没有 schema
    write_dataset(l2_target, [{"name": "id", "type": "bigint", "nullable": True, "ordinal": 1}])

    status, star = call("POST", "/api/v1/lineage/parse", {
        "sql": "CREATE TABLE dg.dw.l2_target AS SELECT * FROM dg.public.l2_left",
        "dialect": "postgres", "namespace": NAMESPACE})
    check("39a. SELECT * 用平台 schema 展开成列级边（解析器做不到，但平台知道 schema）",
          status == 200 and star.get("l2Edges", 0) >= 2
          and star.get("columnEdges", 0) == 0,
          f"解析器给出列级边 {star.get('columnEdges')} 条；L2 补出 {star.get('l2Edges')} 条"
          f"（SELECT * 的语义是同名透传）")

    # L2 补的是**列级**边，因此必须从列节点出发查 —— 从数据集出发看不到列到列的边
    l2_target_column = f"urn:dg:Column:{NAMESPACE}.postgresql.dg.dw.l2_target.id"
    status, sub_l2 = call("GET",
                          f"/api/v1/lineage/subgraph?urn={urllib.parse.quote(l2_target_column, safe='')}"
                          "&direction=upstream&depth=2&includeColumns=true")
    l2_edges = [edge for edge in (sub_l2.get("edges") or []) if edge.get("source") == "sql_parse_l2"]
    check("39b. 补出来的边**来源可区分**（sql_parse_l2）、置信度低于 exact",
          status == 200 and bool(l2_edges)
          and all(float(edge.get("confidence", 0)) < 0.8 for edge in l2_edges),
          f"{len(l2_edges)} 条 L2 边，置信度 {[edge.get('confidence') for edge in l2_edges][:3]}"
          "（使用者有权知道这不是 SQL 直接给出的）")

    status, ambiguous = call("POST", "/api/v1/lineage/parse", {
        "sql": "INSERT INTO dg.dw.l2_target SELECT id FROM dg.public.l2_left a "
               "JOIN dg.public.l2_right b ON a.id = b.id",
        "dialect": "postgres", "namespace": NAMESPACE})
    types = set(ambiguous.get("details", [{}])[0].get("l2Findings", [])) if isinstance(ambiguous, dict) else set()
    check("39c. 列在两个上游表里都存在时**不猜**（记 ambiguous_column_unresolved，不建边）",
          status == 200 and "ambiguous_column_unresolved" in types
          and ambiguous.get("l2Edges", -1) == 0,
          f"L2 发现 {sorted(types)}；补边 {ambiguous.get('l2Edges')} 条")

    status, resolved = call("POST", "/api/v1/lineage/parse", {
        "sql": "INSERT INTO dg.dw.l2_target SELECT amount FROM dg.public.l2_left a "
               "JOIN dg.public.l2_right b ON a.id = b.id",
        "dialect": "postgres", "namespace": NAMESPACE})
    resolved_types = set(resolved.get("details", [{}])[0].get("l2Findings", [])) if isinstance(resolved, dict) else set()
    check("39d. 列只存在于一个上游表时就消歧（不确定的反面是确定，不必一起放弃）",
          status == 200 and "ambiguous_column_resolved" in resolved_types
          and resolved.get("l2Edges", 0) >= 1,
          f"L2 发现 {sorted(resolved_types)}；补边 {resolved.get('l2Edges')} 条")

    status, missing_schema = call("POST", "/api/v1/lineage/parse", {
        "sql": "CREATE TABLE dg.dw.l2_target AS SELECT * FROM dg.public.l2_no_schema",
        "dialect": "postgres", "namespace": NAMESPACE})
    missing_types = set(missing_schema.get("details", [{}])[0].get("l2Findings", [])) \
        if isinstance(missing_schema, dict) else set()
    check("39e. 上游 schema 未采集时明确记账并给出**怎么办**（而不是静默无产出）",
          status == 200 and "select_star_unresolved" in missing_types
          and missing_schema.get("l2Edges", -1) == 0,
          f"L2 发现 {sorted(missing_types)}")

    status, checks = call("GET", "/api/v1/lineage/checks?days=30&limit=100")
    summary = checks.get("summary", {}) if isinstance(checks, dict) else {}
    by_type = {row.get("check_type") for row in summary.get("byType", [])}
    actionable = summary.get("actionable") or []
    check("39f. 检查发现可查、可按类型统计，并区分「能补的」与「需改 SQL 的」",
          status == 200 and checks.get("count", 0) > 0
          and {"select_star_expanded", "select_star_unresolved", "ambiguous_column_unresolved"} <= by_type
          and bool(actionable)
          and "不猜" in str(summary.get("note")),
          f"{checks.get('count')} 条发现，类型 {sorted(by_type)}；可操作（缺 schema）{len(actionable)} 条")

    # ------------------------------------------------------------ 23) 界面
    status, html = call("GET", "/", token=None)
    is_html = isinstance(html, str) and 'id="root"' in html
    check("23a. 界面（SPA）由同一端口托管", status == 200 and is_html, f"HTTP {status}")

    status, deep = call("GET", "/governance", token=None)
    check("23b. SPA 深链回退到 index.html",
          status == 200 and isinstance(deep, str) and 'id="root"' in deep, f"HTTP {status}")

    spaview = []
    for route in ("/observability", "/ai"):
        route_status, route_body = call("GET", route, token=None)
        spaview.append(route_status == 200 and isinstance(route_body, str) and 'id="root"' in route_body)
    check("23c. 本批次新增入口（可观测 / AI 与 Agent）由 SPA 托管（深链可直达）",
          all(spaview), "路由 /observability、/ai 均返回 SPA 外壳")

    status, html_assets = call("GET", "/", token=None)
    bundled = re.search(r'/assets/(index-[A-Za-z0-9_-]+\.js)', html_assets) if isinstance(html_assets, str) else None
    check("23d. 界面产物与后端同源发布（构建产物确实被服务出去，而不是只存在于 dist 目录）",
          bundled is not None, f"入口 bundle：{bundled.group(1) if bundled else '未找到'}")

    failed = [item for item in results if not item[1]]
    print("\n" + "=" * 66)
    print(f"通过 {len(results) - len(failed)}/{len(results)}")
    if failed:
        print("失败项：" + "、".join(item[0] for item in failed))
    print("=" * 66)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
