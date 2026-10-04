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
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
TOKEN = os.environ.get("DG_TOKEN", "dev-admin-token")
READER_TOKEN = os.environ.get("DG_READER_TOKEN", "dev-reader-token")
STEWARD_TOKEN = os.environ.get("DG_STEWARD_TOKEN", "dev-steward-token")
SIDECAR_URL = os.environ.get("DG_LINEAGE_SIDECAR_URL", "http://127.0.0.1:8099")
REPO_ROOT = Path(__file__).resolve().parent.parent

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
          and any("不能包含申请人本人" in note for note in request.get("notes", [])),
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
    check("28a. 审计报告显式声明**覆盖范围**（没记录 ≠ 没发生）",
          status == 200 and note.get("covered") and note.get("notCovered")
          and any("直连" in item for item in note.get("notCovered", [])),
          f"已覆盖 {len(note.get('covered', []))} 项 / 未覆盖 {len(note.get('notCovered', []))} 项")

    status, events = call("GET", "/api/v1/access/events?limit=50")
    actions = {event["action"] for event in events.get("events", [])} if isinstance(events, dict) else set()
    check("28b. 访问事件留痕（申请/审批/授权/回收全链路）",
          status == 200 and {"REQUEST_SUBMITTED", "GRANT_CREATED"} <= actions,
          f"{events.get('count')} 条事件：{sorted(actions)[:5]}")

    status, least = call("GET", "/api/v1/access/least-privilege")
    check("28c. 最小权限复盘显式标注「无使用数据」而不是假设未使用",
          status == 200 and least.get("usageDataAvailable") is False
          and "无法判断授权是否在用" in str(least.get("note")),
          str(least.get("note"))[:60])

    # ------------------------------------------------------------ 23) 界面
    status, html = call("GET", "/", token=None)
    is_html = isinstance(html, str) and 'id="root"' in html
    check("23a. 界面（SPA）由同一端口托管", status == 200 and is_html, f"HTTP {status}")

    status, deep = call("GET", "/governance", token=None)
    check("23b. SPA 深链回退到 index.html",
          status == 200 and isinstance(deep, str) and 'id="root"' in deep, f"HTTP {status}")

    failed = [item for item in results if not item[1]]
    print("\n" + "=" * 66)
    print(f"通过 {len(results) - len(failed)}/{len(results)}")
    if failed:
        print("失败项：" + "、".join(item[0] for item in failed))
    print("=" * 66)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
