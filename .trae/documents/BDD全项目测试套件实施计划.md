# BDD 全项目测试套件实施计划（pytest-bdd）

## Context（为什么做）

当前项目有 `tools/java_e2e_verify.py`（1816 行、139 项校验、约 38 组），能对运行中的 Java 控制面（`http://127.0.0.1:8081`）做端到端验证，但它是**命令式脚本**（`check(name, ok, detail)`），不是按文档语义组织的 BDD 场景，可读性与业务可追溯性弱。

目标：**按文档语义、用 Gherkin 重写为全量 BDD 套件**，覆盖 42 项能力与 8 大入口（发现/资产/血缘/质量/可观测/治理/AI 与 Agent/管理），场景对运行中的 8081 服务做真实验证，失败/跳过都明确可见（不静默通过）。

已确认的前置事实：
- 8081 **当前未启动**；`backend/dg-api/target/dg-api-0.1.0.jar`（37MB）与 `web/dist/` 均在；Postgres(25011)、SQL 解析侧车(8099)、Vite(5173) 在跑。
- `pytest 8.3.3` + `pytest_bdd` **已安装**；`psql` 在 PATH（`C:\sandbox\tools\postgresql16\bin\psql.exe`）；`tools/fixtures/dbt/manifest.json` 存在。
- pytest-bdd 8.1 底层为 gherkin-official 29，支持 `zh-CN` 方言（功能/场景/背景/假如/当/那么/并且/但是），**feature 文件首行必须是 `# language: zh-CN`**。

## 已定决策（用户确认）

1. 框架 = **Python pytest-bdd**（Gherkin + Python steps），接入现有 pytest 生态。
2. 覆盖 = **全量对齐现有 38 组校验**（约 130 个场景）。
3. 服务生命周期 = **不自动启动 8081**；套件只校验，未启动则快速失败并打印启动命令。侧车(8099) 仍由套件自动管理（缺失则拉起，结束时仅关闭自己拉起的）。

## 目录布局

新增顶层 `e2e/`（与 `tests/` 平级），**不改动 `tests/`**，`pyproject.toml` 的 `testpaths=["tests"]` 使其默认不受影响、250 个 pytest 用例不会突然要求起服务。

```
e2e/
  conftest.py              # 选项/前置检查/侧车 fixture/world；末尾导入所有 step 模块完成注册
  support/
    __init__.py
    config.py              # BASE/TOKEN/READER_TOKEN/STEWARD_TOKEN/SIDECAR_URL/NAMESPACE/REPO_ROOT
    client.py              # call(method,path,body,token)->(status,json|str)；Api 包装；quote_urn()
    sidecar.py             # sidecar_alive/start_sidecar/stop_sidecar（含 started_by_us 语义）
    db.py                  # psql_binary/psql_exec/seed_metric_series/seed_short_series/seed_backdated_grant
    external.py            # clickhouse/mongo/superset/chrome 探测 + dbt 夹具存在性
    tools_runner.py        # run_engine_audit_loader/run_ci_gate/run_ci_pr_comment/_StubGitApi
    world.py               # World 上下文对象（跨步骤传状态）
  features/                # 18 个 .feature（见下）
  steps/
    __init__.py
    common_steps.py        # 通用 HTTP/断言步骤（所有 feature 复用）
    <area>_steps.py        # 按 feature 拆分的领域步骤
  test_<NN>_<area>.py      # 18 个绑定模块：scenarios("features/NN_xxx.feature")
```

**关键区分**：绑定模块 `e2e/test_*.py` 匹配 pytest 默认 `python_files=test_*.py` 被收集；步骤模块 `e2e/steps/*.py` **故意不匹配** `test_*.py`，由 `e2e/conftest.py` 末尾 `from steps import ...` 导入注册（step 装饰器本质是 fixture）。feature 发现依赖 pytest-bdd 的「调用方目录即 base dir」默认行为，`scenarios("features/NN.feature")` 无需额外 ini。

## 支撑层（`e2e/support/`）——复制而非 import

**结论：从 `tools/java_e2e_verify.py` 复制约 200 行辅助逻辑，不直接 import。** 原因：`java_e2e_verify.py:41` 在导入时执行 `BASE = sys.argv[1] if len(sys.argv) > 1 else ...`，pytest 下 `sys.argv[1]` 是 pytest 参数，会把 BASE 污染成垃圾值；且模块级还有 `results`、`PASS/FAIL` 等全局态，它是一个可执行脚本而非库。**不重构** `tools/java_e2e_verify.py`（超出本次范围）。

- `client.call()` 语义与 `:54-73` 对齐：urllib、`ensure_ascii=False`、有 body 才加 `Content-Type`、token 真值才加 `Authorization: Bearer`、`timeout=90`、JSONDecodeError 回退原文、HTTPError 返回 `(code, parsed_or_text)`。
- `sidecar.*` 对齐 `:81-115`：`DG_SKIP_SIDECAR=1` 逃逸、`PYTHONPATH=<repo>/src`、`python -m dg.cli sidecar --port 8099`、轮询 40×0.5s；**只在「本来不存活」时才拉起，teardown 只关自己拉起的**。
- `db.psql_exec()` 对齐 `:168-186`：SQL 写 **UTF-8 临时文件** 用 `-f` 执行（Windows GBK 控制台下中文 `-c` 会坏）；`psql_binary()` 候选含 `C:\sandbox\tools\postgresql16\bin\psql.exe`、`DG_PSQL`、`shutil.which`。
- `external.py`：ClickHouse `127.0.0.1:8123`、Mongo `127.0.0.1:27018`、Superset `127.0.0.1:18089`、Chrome 复用 `tools/ui_render_check.py:21-57` 的 `find_chrome()`、dbt 用 `tools/fixtures/dbt/manifest.json`。

## Fixture 设计（`e2e/conftest.py`）

- 选项：`--base-url`（默认 `DG_E2E_BASE_URL` 或 `http://127.0.0.1:8081`）、`--namespace`（默认 `java_e2e`）。
- `api`（session）：绑定 base_url 的 HTTP 客户端。
- `service_up`（session, **autouse**）：前置检查。`GET /healthz`（无令牌）非 200 → `pytest.fail`，打印启动命令；校验 `controlPlane` 含 `java`；`GET /api/v1/me`（管理员）非 200 → 提示 `DG_AUTH_MODE=static` 与开发令牌。
- `sidecar`（session, **autouse**）：起/停侧车。
- `world`（**function**）：每场景全新 `World`，跨步骤传状态。
- `psql`（session）：`psql_binary()`；缺失时默认**快速失败**（提示 `DG_PSQL`），设 `BDD_ALLOW_SKIP_PSQL=1` 才 skip。
- `infra`（session）：探测结果缓存。

`World` 字段：`last_status`、`last_body`、`calls`、`token`、`namespace`、`urns: dict`、`schedule_name`、`policy_id`、`request_id`、`grant_id`、`ai_suggestion_id`、`contract_version`、`psql`、`tmp`（每场景 `TemporaryDirectory`，放 CI 报告/JSON/JSONL）。多数断言要多个前序调用的产物，故以 `world` 为主，少量 `target_fixture=` 产出值。

## Feature 清单（18 个，约 130 场景；均以 `# language: zh-CN` 开头）

| # | 文件 | 覆盖组 | 场景数 |
|---|---|---|---|
| 01 | `01_health_auth.feature` | 1,2 | 4 |
| 02 | `02_model_capabilities.feature` | 3,4 | 3 |
| 03 | `03_ingestion_assets.feature` | 5,6 | 3 |
| 04 | `04_search_schedule.feature` | 7,8 | 6 |
| 05 | `05_sql_parse_lineage_quality.feature` | 9 | 6 |
| 06 | `06_impact_analysis.feature` | 10,11 | 3 |
| 07 | `07_profiling_rules.feature` | 13,14 | 8 |
| 08 | `08_contracts.feature` | 15 | 7 |
| 09 | `09_ci_gate_waiver_plugin.feature` | 16,17,37 | 12 |
| 10 | `10_connectors.feature` | 19,20,21,22,38 | 18 |
| 11 | `11_lineage_graph.feature` | 24 | 7 |
| 12 | `12_access_review.feature` | 25,26 | 10 |
| 13 | `13_policy_audit.feature` | 27,28 | 10 |
| 14 | `14_ai_assist.feature` | 29 | 7 |
| 15 | `15_semantic_mcp_search.feature` | 30,31,32 | 10 |
| 16 | `16_observability.feature` | 33,34 | 11 |
| 17 | `17_edge_agent_engine_audit.feature` | 35,36 | 17 |
| 18 | `18_spa_ui.feature` | 12,23 + ui_render_check 10 项 | 14 |

场景标题全量对齐现有 `check()` 名（如「7b. 检索真实命中 + 分面 + 可见分级」→「当 我检索 event_log 时 那么 应命中且带分面与可见分级」），保证**组号与文案可逐条回溯**；实施时以本表为唯一场景清单。

## 步骤策略

**通用步骤（`common_steps.py`，复用）**：
- `当 我以{管理员|只读|治理员}令牌调用 {method} {path}` → 调 `api.call`，写 `world.last_*`。
- `并且 请求体为：` + Gherkin docstring → JSON（回退 YAML）解析进 `world.pending_body`。
- `那么 响应状态码应为 {status:d}`；`那么 响应体字段 {path} 应为 {expected}`（点号路径+标量）；`那么 响应字段 {path} 非空` / `至少为 {n:d}`；`那么 响应体中应包含文本 {needle}`。

**领域步骤（每 feature 一个模块）**：实现多字段忠实断言（例：`7b` 需 `facets.entityType` 非空 **且** `visibleLevels` 存在；`14f` 需报错而非静默降级）。

**URN 记账**：`假如 我已采集命名空间 {ns} 的 PostgreSQL public schema` 记录 `world.urns`；05 从资产列表取 `public.event_log` 的 URN，11 优先用 Superset 仪表板数据集 URN，否则回退 `event_log`。

**psql 依赖场景**（16、17 及 26/36 的 `@psql`）：`假如 我构造了指标 {metric} 的合成历史序列` 调 `db.seed_metric_series`；无 psql 默认 fail-fast，可 `BDD_ALLOW_SKIP_PSQL=1` 跳过。

**外部基础设施跳过**：场景打 `@clickhouse/@mongodb/@superset/@chrome`；在 `pytest_collection_modifyitems` 里按 `support/external.py` 探测结果 `item.add_marker(skip(reason=含 host:port 的精确原因))`。**`@bigquery` 仅分组不跳过**（「缺凭据返回可读错误」无需真实凭据，恒可断言）；**`@dbt` 永不跳过**（夹具在库）。**凡是原脚本刻意 FAIL（如 `event_log` URN 缺失）的，BDD 一律 assert/fail，不转 skip。**

## 配置与依赖

1. `pyproject.toml` `[project.optional-dependencies].dev` 增 `"pytest-bdd>=8.1"`（本机已装，registry 化以便复现）。
2. `[tool.pytest.ini_options]` 注册 markers（标签会自动成为 marker，否则告警）：`external/clickhouse/mongodb/superset/bigquery/dbt/chrome/psql`。**不动 `testpaths` 与 `addopts`**。
3. 不新建 `e2e/pytest.ini`（`-c` 会迁移 rootdir 改变路径解析）；依赖绑定模块默认 base dir 即 `e2e/`。

## 运行手册（验证方式）

**(a) 启动控制面**（jar 已在，37MB；`web/dist` 已在）：
```powershell
cd backend; mvn -B package -DskipTests; cd ..
$env:DG_MODEL_DIR="$PWD\model"; $env:DG_SQL_DIR="$PWD\sql"
$env:DG_WEB_DIST="$PWD\web\dist"; $env:DG_API_PORT='8081'
java -jar backend\dg-api\target\dg-api-0.1.0.jar
```
校验：`Invoke-RestMethod http://127.0.0.1:8081/healthz` 返回 200 且 `controlPlane` 含 `java`；保持 `DG_AUTH_MODE=static`。

**(b) 运行 BDD**（侧车自动管理）：
```powershell
python -m pytest e2e -p no:cacheprovider --gherkin-terminal-reporter          # 全量
python -m pytest e2e -m "not external" --gherkin-terminal-reporter            # 跳外部基础设施
python -m pytest e2e/test_08_contracts.py --gherkin-terminal-reporter         # 单个 feature
python -m pytest e2e -k "破坏性变更" --gherkin-terminal-reporter               # 单场景
python -m pytest e2e --collect-only -q                                        # 采集审计
```
可选 `--cucumberjson=e2e-report.json` 产出 CI 机读结果。`--gherkin-terminal-reporter` **不兼容 xdist**，禁止 `-n`。

**(c) 隔离回归**：`python -m pytest --collect-only -q` 应仍只收集 `tests/`；`python -m pytest e2e --collect-only -q` 收集场景。

## 实施阶段（每阶段以「采集干净 + 子集可跑」收尾）

1. 脚手架：`support/*`、`conftest.py`、markers、`common_steps.py`、feature 01 冒烟。证明默认 `pytest` 仍只收 `tests/`，且服务未起时 fail 文案正确。
2. 只读核心：01/02/03/04 + 18 的 SPA HTTP 部分（无 seeding、无外部依赖）。
3. 侧车相关：05 → 06/07/11；证明侧车自动起停且不误杀已有侧车。
4. 契约与 CI：08/09 与 10 的 dbt 部分；接 `tools_runner`（contract_gate/pr_comment/_StubGitApi），用 `world.tmp` 处理报告。
5. 治理流程：12/13/17；接 `db.py` seeding（定 fail-vs-skip）。
6. AI/语义/MCP/可观测：14/15/16；16 接 `seed_metric_series`/`seed_short_series`。
7. 外部连接器：10 剩余 `@clickhouse/@mongodb/@superset`；实现探测与采集期跳过；确认 `@bigquery`/`@dbt` 仍运行。
8. UI 渲染：18 的 `@chrome` 场景（复用 `tools/ui_render_check.py` 的 `render()`），Chrome 缺失则干净跳过。
9. 收口：CI 任务（`pytest e2e -m "not external"`）+ 一致性守卫（断言每个 `check("N...` 组号都在 feature 中出现）+ README §3 增补运行说明。

## 风险与缓解

- **外部基础设施缺失**（CH/Mongo/Superset）：按 host:port 精确 reason 跳过，绝不静默通过；`@bigquery/@dbt/连接器清单` 保持可断言。
- **共享开发库被改**：套件面向 live dev 库与命名空间 `java_e2e`（与现有脚本一致）。缓解：**绝不 TRUNCATE**；写入走幂等 API 语义；seeding 仅 `DELETE ... WHERE dataset_urn/subject=<自己的合成行>`；**不复用 `tests/conftest.py` 的 TRUNCATE 路径**；README 说明会改开发数据。
- **场景间状态泄漏**：`world` 每场景全新；场景不得依赖前序场景产物；每个 feature 在自己的「假如」里重新推导所需 URN。
- **AI/模型凭据**：只断言未配置路径（502 + 原因）与确定性生成器输出，绝不调真实 LLM。
- **SPA/无头 Chrome**：Chrome 缺失则跳过并列出候选；只断言文档化的 10 组关键文案；运行前确保 `web/dist` 已构建。
- **psql**：默认清晰失败 + `DG_PSQL` 提示，`BDD_ALLOW_SKIP_PSQL=1` 才跳过。
- **侧车双重管理**：检测到已有健康侧车则不拉起、teardown 不杀。
- **中文 Gherkin 解析失败**：所有 feature 首行 `# language: zh-CN`；加守卫校验。
- **marker 告警**：新增标签必须同步进 `markers` 列表。

## 关键文件

- `tools/java_e2e_verify.py`（辅助语义与 38 组断言的来源）
- `tools/ui_render_check.py`（`@chrome` 用例与 `find_chrome`/`render`）
- `pyproject.toml`（dev 依赖 + markers；`testpaths` 保持只收 `tests/`）
- `README.md`（启动命令与 §3 验证命令）
- 新增：`e2e/**`（support + conftest + features + steps + 18 个绑定模块）