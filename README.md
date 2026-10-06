# 通用数据治理平台（Data Governance Platform）

> **当前阶段：框架与界面已按设计选型搭好，功能按能力清单分级标注。**
> 控制面 = **Java 21 + Spring Boot 3 多模块**（`backend/`）；界面 = **Vite + React + TypeScript**（`web/`）；
> 元数据模型与 SQL schema 是两者共享的单一事实源（`model/`、`sql/`）。
> Python 实现保留为**参考实现**（`src/`、`tests/`）—— 它有端到端验证过的采集/血缘/告警逻辑，
> 可作为 Java 侧对齐时的行为基准，见 [`docs/21`](docs/21-implementation-log.md)。

## 现在的状态（一句话）

**框架完整、界面完整、未实现的功能全部显式标注** —— 控制面的 `/api/v1/capabilities` 是唯一的
状态真相源，界面按它渲染状态徽标，因此不会出现「界面以为有、接口其实没有」的漂移。
当前 **42 项能力：34 已实现 / 7 部分实现 / 1 未实现**（`GET /api/v1/capabilities` 可查明细）。
仅剩的未实现项是 `core.index-opensearch`（检索后端替换，触发条件未到）；7 项部分实现的缺口在能力说明里逐条列出。

分批实施进度见 `docs/22`：**Batch 1（检索 / 权限 / 调度 / SQL 解析）、Batch 2（剖析 / 规则引擎 /
ODCS 契约 / CI 门禁）、Batch 3（四个连接器 + BI 资产血缘 + 血缘画布）、
Batch 4（访问申请审批 / 策略编译下发 / 覆盖率与审计）、Batch 5（AI 建议与 MCP / 异常检测与 SLO 事故 /
语义层指标 / Edge Agent 推模式）、Batch 6（引擎审计摄入 / GitHub+GitLab CI 插件 / 仓库 CI 流水线）、
Batch 7（dbt 连接器 + 编译期血缘）
均已完成**（`docs/21 §12`–`§20`）。
状态只在跑过可复现验证后才改，不虚标。

### 已支持的资产类别与数据源

| 数据源 | 资产类别 | 状态 |
|---|---|---|
| PostgreSQL | 数据集（表/视图/列） | ✅ 已对真实系统验证 |
| **ClickHouse** | 数据集（含引擎、分区键、排序键） | ✅ 已对真实系统验证 |
| **MongoDB** | 数据集（无 schema → 采样推断结构） | ✅ 已对真实系统验证 |
| **Apache Superset** | **BI 资产**（仪表板 + 图表）+ `数据集→报表` 血缘 | ✅ 已对真实系统验证 |
| **dbt** | 数据集（model / seed / snapshot / source）+ **编译期确定的表级血缘** | ✅ 已用真实形态的 manifest 夹具端到端验证 |
| BigQuery | 数据集（REST + 服务账号 JWT） | ⚠️ 已实现，**未对真实项目验证**（无凭据） |
| **私有子网 / 数据不出域** | 数据集（**Edge Agent 推模式**：注册 → 心跳 → 上报） | ✅ 控制面侧已对真实接口验证；**Go 单二进制 Agent 本体未实现**（任何能发 HTTP 的采集器可用） |
| MySQL / Trino / Hive / Airflow / SQLite / DuckDB / Tableau | — | ❌ 未实现（界面已标注） |

## 快速开始

### 1) 构建并启动 Java 控制面（含界面）

```powershell
# 前提：JDK 21 + Maven 3.9 + PostgreSQL（默认 jdbc:postgresql://localhost:25011/dg）
cd backend
mvn -B package -DskipTests

cd ..
$env:DG_MODEL_DIR="$PWD\model"; $env:DG_SQL_DIR="$PWD\sql"
$env:DG_WEB_DIST="$PWD\web\dist"; $env:DG_API_PORT='8081'
java -jar backend\dg-api\target\dg-api-0.1.0.jar
#   界面:  http://127.0.0.1:8081/      （右上角填令牌 dev-admin-token）
#   接口:  http://127.0.0.1:8081/docs
#   能力:  http://127.0.0.1:8081/api/v1/capabilities
```

### 1.5) 启动 SQL 解析侧车（血缘列级解析需要）

```powershell
$env:PYTHONPATH='src'
python -m dg.cli sidecar          # http://127.0.0.1:8099
```

侧车只做纯解析（sqlglot，20 方言），不连数据库、不写元数据；解析结果由 Java 控制面
解析为 URN 后写入血缘图，并**不写数据库**这一点保证了只有一个写入者。
侧车不可用时解析接口返回 **502 + 启动命令**，而不是静默返回空血缘
（空血缘与"解析没跑"必须可区分）。

### 2) 界面开发模式（热更新）

```powershell
cd web
pnpm install
pnpm dev        # http://127.0.0.1:5173，/api 自动代理到 8081
```

### 3) 验证

```powershell
python tools/java_e2e_verify.py     # Java 控制面端到端（147 项；自动拉起并关闭 SQL 解析侧车）
cd backend; mvn -B test             # Java 单元测试（150 项，含数组绑定门禁）
$env:PYTHONPATH='src'; python -m pytest -q   # Python 参考实现（250 项）
python tools/ui_render_check.py     # 界面渲染冒烟（无头 Chrome，逐标签页 dump DOM；10 项）
python -m pytest e2e -p no:cacheprovider --gherkin-terminal-reporter   # 全项目 BDD（Gherkin，157 场景）
python -m pytest e2e -m "not external" --gherkin-terminal-reporter     # 跳过外部依赖（ClickHouse/Mongo/Superset/Chrome 缺失时）
python tools/dependency_audit.py --self-test  # 供应链：先证明扫描链路有效（反向用例）
python tools/dependency_audit.py              # 供应链：0 干净 / 1 有阻断项 / 2 未验证
$env:DG_API_TOKEN='dev-admin-token'; python tools/ci/contract_gate.py `
  --contract contracts/example_event_log.yaml --namespace java_e2e   # 契约 CI 门禁（退出码语义）
```

> `e2e/` 是 BDD（Gherkin）全项目验收套件，**要求控制面已在 `http://127.0.0.1:8081` 运行**（套件不启动它，未就绪会快速失败并打印启动命令）；SQL 解析侧车由套件自动起停。它会写开发库 `java_e2e` 命名空间下的数据（幂等，绝不 TRUNCATE）。
>
> `--gherkin-terminal-reporter` 不兼容 xdist，请勿加 `-n`。

CI 插件（可直接接进流水线）：

```yaml
# GitHub Actions
- uses: ./.github/actions/contract-gate        # 或发布后的 owner/repo/actions/contract-gate@v1
  with:
    contract: contracts/example_event_log.yaml
    base-url: ${{ vars.DG_BASE_URL }}
    token: ${{ secrets.DG_API_TOKEN }}
```
```yaml
# GitLab CI
include:
  - local: 'ci/contract-gate.gitlab-ci.yml'
```

### 4) 供应链与依赖安全（NFR-SEC-01）

公司合规要求：**所选技术与第三方依赖无已知 CRITICAL/HIGH 漏洞**，MEDIUM 限时修复或登记豁免，
优先采用**积极修复的 FOSS**，出现无法及时修复的漏洞必须替换或隔离。
完整要求、验收标准与流程见 [`docs/23-dependency-and-supply-chain-nfr.md`](docs/23-dependency-and-supply-chain-nfr.md)。

当前实测（`tools/dependency_audit.py`，OSV 扫描）：

```
依赖清点：Maven 99 · npm 80 · PyPI 8（合计 187 个依赖）
已知漏洞：0 条 CRITICAL / 0 条 HIGH / 0 条 MEDIUM（豁免台账为空 —— 全部靠升级/替换/删除解决）
```

本轮为此完成的修复：Spring Boot `3.1.0 → 3.5.16`（3.1.x 已停止安全维护，原产物含
10 项 CRITICAL、42 项 HIGH）、Tomcat/Jackson/commons-lang3/log4j2 显式固定到已修复补丁版本、
`react-router-dom 6 → 7.18.4`、删除**未使用**的 jjwt 依赖。

## 仓库结构

```
model/**/*.yaml                 元数据模型定义 —— 唯一事实源（Java / Python / 前端生成物都从它派生）
sql/*.sql                       真相源 schema 与迁移（12 个文件，Java 与 Python 共用）
contracts/*.yaml                数据契约示例（ODCS 兼容，可直接用于 CI 门禁）

backend/                        ★ 控制面（Java 21 + Spring Boot 3，docs/10 §1 选型）
├─ dg-model/                    模型注册表：加载 / 校验 / 兼容性检查 + 能力状态标注机制
├─ dg-core/                     元数据内核：真相源 / 版本 / 审计 / outbox 事件 / 血缘图 /
│                               检索索引消费者与检索服务（派生视图，可重放重建）
├─ dg-ingestion/                采集框架 + 护栏 + 连接器（PostgreSQL / ClickHouse / MongoDB /
│                               BigQuery / Superset）+ 采集调度器 + Edge Agent 推模式接入点
├─ dg-lineage/                  OpenLineage 接收 + 血缘查询 + 影响分析 + sqlglot 侧车客户端与入库
├─ dg-quality/                  质量与可观测：剖析（带精度标注）/ 规则 IR 与执行 / ODCS 契约与 CI 门禁 /
│                              异常检测（MAD 与季节性 MAD、上游抑制下游）/ SLO 达成率与事故闭环
├─ dg-policy/                   访问治理：RBAC/ABAC 判定、访问申请审批与到期回收、
│                               策略编译（Trino/数仓/BI/SDK 四类产物）+ 覆盖率度量、审计取证
├─ dg-ai/                       AI 原生能力：建议引擎（人工采纳才落库）/ 混合检索（RRF）/
│                               MCP 工具出口（按权限裁剪 + 全量审计）/ 语义层指标接入
├─ dg-sdk/                      客户端 SDK（骨架，未实现）
└─ dg-api/                      REST API 与装配（Spring Boot 应用入口）

web/                            ★ 界面（Vite + React + TypeScript，docs/10 §1 选型）
├─ src/App.tsx                  八入口信息架构（docs/14 §2）：发现 / 资产 / 血缘 / 质量 / 可观测 /
│                               治理 / AI 与 Agent / 管理
├─ src/pages/                   各入口页面（已实现的接真实 API；未实现的显示设计说明）
├─ src/components/              状态徽标与未实现占位卡
└─ src/api/client.ts            API 客户端（同一套 REST，无 UI 专用后门）

src/  tests/  tools/            Python 参考实现（采集/血缘/调度/告警的完整验证逻辑，docs/21 §2）
│                               + SQL 解析侧车（python -m dg.cli sidecar）
│                               + tools/dependency_audit.py（供应链扫描，NFR-SEC-01）
security/                       依赖豁免台账（dependency-waivers.yaml）+ 扫描报告（reports/）
docs/                           调研报告（research/）+ 设计方案（06–20）+ 实现日志（21）
                                + 分批计划（22）+ 供应链 NFR（23）
```

## 入口与能力状态

| 入口 | 已实现 | 未实现（界面已标注设计说明） |
|---|---|---|
| **发现** | **全文检索（标识符切分 + 中文二元切分 + 前置授权过滤 + 分面 + 索引水位）**、资产浏览、最近更新 | 词典分词（现为 bigram）、向量检索、数据产品货架 |
| **资产** | 资产列表、资产详情（结构/Aspect/Owner/分级）、版本历史；**多源资产**（ClickHouse / MongoDB / Superset 仪表板） | 质量状态页签、协作与评论 |
| **血缘** | 列级血缘图、OpenLineage 接收、血缘质量报告、**SQL 静态解析入库（侧车）**、**影响分析/爆炸半径**、**交互式血缘画布（线型=可信度、路径高亮、边可确认/驳回）** | 时间轴回放、大图聚合视图、列级端到端路径追踪 |
| **质量** | **剖析（精度标注 + 隐私约束）**、**规则引擎（YAML / SQL 断言 / dbt tests 三前端）**、执行记录、**数据契约与违约事件**、**CI 门禁 + GitHub/GitLab 插件（PR 评论回写）** | 分布漂移（PSI/KS）与 STL 分解 |
| **可观测** | **异常检测（MAD / 季节性 MAD、样本不足显式跳过、上游抑制下游）**、**SLO 与错误预算（无数据时回报 no_data）**、**事故时间线与影响面 + 闭环强制沉淀规则** | 告警通道（邮件/IM/on-call）与告警状态机 |
| **治理** | **访问申请与审批**、**授权与到期回收**、**定期复核与最小权限复盘（使用证据来自引擎审计）**、**引擎审计摄入（真实访问 / 未授权访问清单）**、**策略建模/编译/下发/回滚 + 覆盖率度量**、**审计取证（覆盖范围随实际数据动态生成）** | 策略直推执行引擎、多级会签与代理审批、术语复核、健康分 |
| **AI 与 Agent** | **建议收件箱（人工采纳才落库、来源 AI_GENERATED、驳回需理由、采纳率统计）**、**混合检索（词法 + 术语同义扩展 + RRF）**、**MCP 工具出口（按权限裁剪、全量审计、Agent 只能提建议）**、**语义层指标接入与口径血缘** | 大模型推理（未配置即明确失败）、向量检索、MCP 的 resources/prompts/sampling |
| **管理** | 采集（含护栏）、采集健康度、**采集调度（YAML apply + cron + advisory lock 互斥）**、**检索索引维护（水位/重建）**、**Edge Agent 推模式（注册/心跳/上报/吊销/上报记录）**、模型摘要、**能力清单** | 告警、更多连接器、Go 单二进制 Agent 本体、OIDC/JWKS |

## 文档导航

**先读这三份**：

- [`docs/00-overview.md`](docs/00-overview.md) — 总体方案与执行摘要
- [`docs/21-implementation-log.md`](docs/21-implementation-log.md) — 实现日志（含「按选型重建为 Java 控制面」的记录与偏离说明）
- [`docs/12-build-vs-extend-and-adr.md`](docs/12-build-vs-extend-and-adr.md) — 自研 vs 二开 vs 采购 + 架构决策记录

**合规与安全**：[`docs/23-dependency-and-supply-chain-nfr.md`](docs/23-dependency-and-supply-chain-nfr.md)
— NFR-SEC-01（第三方依赖无已知 CRITICAL/HIGH、修复时限、FOSS 选型标准、替换与例外流程、
持续证据链）。需要向公司安全/合规提交材料时，从这一份开始。

调研报告在 `docs/research/`（OpenMetadata / DataHub / 商业厂商 / 标准与技术 / 开源生态）；
设计方案在 `docs/06`–`docs/20`。完整索引见 `docs/00-overview.md` §9。

## 免责说明

- **未实现的功能一律显式标注**（代码用 `@Unimplemented` + `CapabilityDescriptor`，
  接口用 `/api/v1/capabilities`，界面用状态徽标 + 设计说明卡）。调用未实现接口会得到
  **501 + 设计出处**，而不是空数据 —— 这是刻意的（docs/21 §3）。
- **AI 能力同样如实标注**：未配置大模型时接口返回 **502 + 怎么配置**，
  不会用模板拼一段"看起来像 AI"的文字；向量检索未实现就不宣称语义相似度（docs/21 §17）。
- 界面验证目前只做到「逐标签页渲染出关键内容」（`tools/ui_render_check.py`，无头 Chrome），
  **不等于视觉与交互评审**。
- **供应链扫描只能发现"已知"漏洞**：`tools/dependency_audit.py` 报告 0 条的含义是
  "当前没有已知漏洞"，不是"没有漏洞"。SBOM 生成与制品签名验证尚未接入（见 `docs/23 §8`）。
- 调研文档中的易变事实标注为「待核实」，正式引用前请以官方文档为准。
- 界面**未做浏览器视觉回归验证**（无浏览器自动化环境），当前验证方式是：构建产物可加载 +
  SPA 深链回退 + 所有数据接口端到端打通。
