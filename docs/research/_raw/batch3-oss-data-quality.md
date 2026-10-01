# Batch 3 — 开源 / 开放核心数据质量与数据契约工具研究

> 用途：为「build vs buy」分析提供事实基线，刻画开源工具相对商业数据治理平台（Collibra / Alation / Atlan / Informatica / Acceldata / Monte Carlo 等）的强项与弱项。
> 覆盖范围（仅此）：Great Expectations、Soda、Elementary、dbt；以及为「开源共性短板」对照所需的少量周边项目（OpenMetadata、DataHub、Apache Atlas、Amundsen、OpenLineage/Marquez、ODCS、Data Contract CLI）。
> 时间基准：**2026-10-01**。
> 写作纪律：每条结论尽量附来源链接。凡本次未能取回页面正文、无法逐字确认的数字与说法，一律标注 **待核实**。

## 0. 方法与可信度声明（必读）

- 本次研究通过检索工具获取公开来源（官方文档、官方定价页、GitHub issue/discussion、第三方对比分析、学术论文与厂商白皮书）。
- **局限**：部分厂商定价页为客户端动态渲染，本次未能取回正文。因此凡是「具体金额」「具体名额配额」，除下方明确标注来源可佐证者外，其余均标 **待核实**，并给出官方定价页 URL 供后续人工核对。请勿把标 待核实 的数字直接写进对外报价/决策文档。
- 已可交叉验证的硬数字只有一项：**dbt Cloud Starter ≈ 100 美元/用户/月**（两个独立第三方来源标题均给出该数字，见 §4）。
- 学术侧的一条中性结论可作全局背景：在数据质量 checks 表达能力上，**专有工具通常提供更完整的功能与更灵活的自定义规则能力**（[arXiv 2604.09163](https://arxiv.org/pdf/2604.09163v1)）。这是本批「开源短板」清单的底层原因之一。

---

## 1. Great Expectations（GX Core / GX Cloud）

### 1.1 定位与目标客户

- 定位：Python 原生**数据验证（validation）框架**（GX Core）+ 托管式数据质量 SaaS（GX Cloud）。它回答的问题是「这份数据是否符合我声明的期望」，而非「这条数据血缘从哪来」「这个指标该由谁负责」。
- 目标客户：有数据平台/数据工程团队的中大型企业；数据量较大、需要在离线（批处理/Spark）与在线（DataFrame）两侧做一致性校验的团队；已用 Airflow/Dagster 但缺少统一校验层的团队。
- 相关：[GX Overview](https://docs.greatexpectations.io/docs/0.18/reference/learn/conceptual_guides/gx_overview/)、[Data Validation workflow](https://docs.greatexpectations.io/docs/0.18/oss/guides/validation/validate_data_overview/)。

### 1.2 商业模式与价格

| 形态 | 模式 | 价格 | 来源 |
|---|---|---|---|
| GX Core | Apache 2.0 开源，免费 | 0 | [PyPI great-expectations](https://pypi.org/pypi/great-expectations/json)、[GX 仓库](https://github.com/great-expectations/great_expectations) |
| GX Cloud | SaaS 订阅，分层（免费 Essentials 层 + 付费层，按 Data Asset / 用量计费） | **具体金额待核实** | [官方定价页](https://greatexpectations.io/pricing/)、[GX Cloud FAQ](https://greatexpectations.io/gx-cloud-faqs/) |
| GX Cloud 第三方整理 | 计划分层梳理 | 待核实 | [Modern DataTools — GX Pricing](https://www.modern-datatools.com/tools/great-expectations/pricing) |

- **重要治理事实（2026-05-13 公告）**：Fivetran 宣布**接任 Great Expectations 开源社区与 GX Core 项目的管理者（steward）**。这直接改变「开源长期治理风险」的评估结论——GX Core 的路线图从此由一家数据集成厂商主导。
  - [Fivetran 官方新闻稿](https://www.fivetran.com/de/press/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project)
  - [UK Tech News 报道](https://uktechnews.co.uk/2026/05/14/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project/)
  - [日文报道（ATP)](https://www.atpartners.co.jp/news/2026-05-18-dataops-fivetran-takes-over-management-of-great-expectations-community-and-gx-core-project)
  - 佐证：代码仓库已可从 `fivetran/great_expectations` 路径访问其 discussions，例如 [Discussion #5667](https://github.com/fivetran/great_expectations/discussions/5667)。

### 1.3 核心功能模块清单

- **Expectation（期望）**：单条断言，如 `expect_column_values_to_not_be_null`、分布/基数/正则/跨列比较等。
- **Expectation Suite（期望套件）**：一组期望的集合，可版本化、按数据资产绑定。
- **Validator / Batch**：把套件作用于一批数据（表、查询结果、DataFrame、Spark DF）。
- **Checkpoint（检查点）**：把「Batch 定义 + Suite + Action 列表」打包为一次可调度执行的校验单元，是生产环境的主入口。见 [Create a Checkpoint](https://legacy.017.docs.greatexpectations.io/docs/guides/validation/checkpoints/how_to_create_a_new_checkpoint/)。
- **Actions（后置动作）**：更新 Data Docs、发 Slack/邮件、触发回调、写 DataHub 等。见 [Create a Checkpoint with Actions](https://deploy-preview-10480.docs.greatexpectations.io/docs/core/trigger_actions_based_on_results/create_a_checkpoint_with_actions/)。
- **Data Docs**：自动生成的静态 HTML 校验报告站点（含期望说明与校验结果）。见 [Limit Validation Results in Data Docs](https://docs.greatexpectations.io/docs/0.18/oss/guides/validation/limit_validation_results/)。
- **Data Context / Store 后端**：Filesystem / Ephemeral / 对象存储（S3/GCS）等元数据与结果持久化方式。
- **GX Cloud UI**：托管式界面，用于配置数据资产、期望、运行记录查看与告警。
- **执行引擎覆盖**：Pandas、Spark、SQL（多方言仓储）。

### 1.4 差异化所长

1. **表达能力最强的一档**：可以写任意 Python 自定义 Expectation，覆盖业务规则而非仅 schema 约束。
2. **离线/在线一致性校验**：同一套期望既可跑 Spark/Pandas，也可跑 SQL 仓库，适合「训练/推理一致性」类场景。
3. **Data Docs 自动文档化**：期望本身可读、可作为数据合同的事实文档（虽然需要自托管）。
4. **Git 原生**：套件是代码/JSON，天然进代码评审流程。

### 1.5 弱点与抱怨（带来源）

1. **必须写代码，业务方无法自助**：核心资产是 Python/JSON，治理角色无法直接参与。工程投入被普遍认为是主要门槛（[Great Expectations vs Soda 对比](https://fastero.com/blog/great-expectations-vs-soda-data-quality-compared)、[Data Quality Frameworks 对比](https://pipecode.ai/blogs/data-quality-frameworks-great-expectations-dbt-tests-soda-core)）。
2. **性能问题：同一 DataFrame 多期望触发多次全表扫描**，大表场景耗时显著。
   - [官方 Discourse：Multiple Full Data Scans When Validating a DataFrame](https://discourse.greatexpectations.io/t/multiple-full-data-scans-when-validating-a-dataframe-with-multiple-expectations/2294/2)
   - [Issue #3620：GCP 上执行时间过长](https://github.com/great-expectations/great_expectations/issues/3620)
   - [Issue #7608：0.16.6 Spark map_condition 内存问题](https://github.com/great-expectations/great_expectations/issues/7608)
3. **破坏性升级（0.18 → GX Core 1.x）**：API 全面重写，官方自己发了「Changes to know」与迁移指南，说明迁移成本被官方承认。
   - [Changes to know for GX Core 1.0](https://greatexpectations.io/blog/changes-to-know-for-gx-core-1-0/)
   - [GX V0 to V1 Migration Guide](https://docs.greatexpectations.io/docs/0.18/reference/learn/migration_guide/)
4. **平台兼容性缺口**：例如 1.0 以上版本在 Microsoft Fabric 上不可用（[Discourse #1903](https://discourse.greatexpectations.io/t/gx-version-1-0-not-work-on-microsoft-fabric/1903)）。
5. **无内置调度/告警工作流/审批**：Checkpoint 只是「可被调度的对象」，调度要靠 Airflow/Dagster/Prefect 或 GX Cloud；Data Docs 是静态站点，无权限、无审批、无工单流转。
6. **无业务术语表、无分类分级、无血缘**：血缘需外挂 DataHub 等 Action（[替换 DataHubValidationAction 的 issue](https://github.com/datahub-project/datahub/issues/16195) 反映这类集成本身也在变动）。
7. **文档体系混乱**：同时存在 `0.18`、`core`、`legacy` 多套文档路径（本次检索即可见 `docs.greatexpectations.io/docs/0.18/...` 与 `deploy-preview-*.docs.greatexpectations.io/docs/core/...` 并存），检索成本高。

### 1.6 技术实现线索

- 语言：Python（`pip install great_expectations`），PyPI 包元数据可见作者为 "The Great Expectations Team"。
- 部署：库 + CLI（`great_expectations checkpoint run`）；云版为 GX Labs 托管 SaaS。
- 元数据存储：可插拔 Store 后端（本地文件系统、临时内存、S3/GCS 等）；GX Cloud 存于其 SaaS。
- 编排集成：Airflow / Dagster / Prefect / dbt / Spark 均可调用 Checkpoint；官方也提供 Actions 挂钩。
- 许可证：Apache 2.0。

---

## 2. Soda（Soda Core / Soda Library + SodaCL + Soda Cloud）

### 2.1 定位与目标客户

- 定位：**SQL-first、无代码（YAML DSL）的数据质量测试与数据契约验证**平台。SodaCL 是它真正的护城河。
- 目标客户：以数据仓库（Snowflake/BigQuery/Databricks/Postgres 等）为核心、希望「分析师也能写检查」的数据团队；需要把质量门禁放进 CI/CD 的工程团队。
- 相关：[What is Soda?](https://docs.soda.io/)、[Soda Core v3 总览](https://docs.soda.io/soda-documentation/soda-v3/overview-main)。

### 2.2 商业模式与价格

| 形态 | 模式 | 价格 | 来源 |
|---|---|---|---|
| Soda Core / Soda Library（OSS） | Apache 2.0 开源，免费 | 0 | [soda-core 仓库](https://github.com/sodadata/soda-core)、[官方 README](https://raw.githubusercontent.com/sodadata/soda-core/refs/heads/main/README.md) |
| Soda Cloud | SaaS 订阅，分层（Starter / Standard / Enterprise），官方宣称「透明定价」 | **具体金额待核实** | [Soda 定价页](https://soda.io/pricing)（法语版可见 [soda.io/fr/pricing](https://soda.io/fr/pricing)）、[Soda for Databricks 落地页含 pricing 锚点](https://launch.soda.io/databricks#pricing) |
| 第三方整理 | 分层说明与 AI-native 定位 | 待核实 | [Modern DataTools — Soda Pricing](https://www.modern-datatools.com/tools/soda/pricing) |

- 采购注意：官方定价页标注为「透明定价」，但实际报价常与数据源数量/数据集规模/检查次数挂钩，**务必以商务报价为准（待核实）**。

### 2.3 核心功能模块清单

- **SodaCL 检查语言**：YAML 声明式检查，覆盖 missing、duplicate、validity、schema evolution、reference（跨表引用完整性）、row count、freshness、数值分布等类目；官方定位为「50+ 内置检查」。
  - [Write SodaCL checks](https://docs.soda.io/soda-documentation/soda-v3/soda-cl-overview)
  - [Compare data using SodaCL（跨数据源/跨 schema 比对）](https://docs.soda.io/soda-documentation/soda-v3/soda-cl-overview/compare)
- **Soda Library / CLI**：`soda scan` 本地或 CI 执行；配置为 `configuration.yml` + `checks.yml` 双文件。
- **数据契约（Data Contracts）**：Soda Cloud 侧管理契约、并支持契约验证（verify）。
  - [Data Testing / What is a data contract](https://docs.soda.io/data-testing#what-is-a-data-contract)
  - [Verify a data contract（Soda v3 文档）](https://docs.soda.io/soda-documentation/soda-v3/data-contracts/data-contracts-verify)
  - [Cloud-managed data contracts: verify a contract](https://docs.soda.io/data-testing/cloud-managed-data-contracts/verify-a-contract.md)
  - [生产环境规模化落地数据契约（官方博客）](https://soda.io/blog/data-contracts-at-scale-in-production)
- **CI/CD 集成**：[soda-github-action](https://github.com/sodadata/soda-github-action) 把检查嵌入流水线做质量门禁。
- **Soda Cloud**：集中式仪表盘、异常/动态阈值、告警与事件跟踪、契约管理。
- **多执行引擎**：SQL（多方言）、Spark、Pandas。
- **发布说明可追踪**：[Soda Core release notes](https://docs.soda.io/release-notes/soda-core)。

### 2.4 差异化所长

1. **YAML 而非代码**：把「写检查」的门槛从数据工程师降到分析师，这是 GX 最明显的对照面（[GX vs Soda 对比](https://fastero.com/blog/great-expectations-vs-soda-data-quality-compared)）。
2. **SodaCL 的跨表/跨源比对**是开源质量工具里少见的原生能力。
3. **数据契约有明确落点**：CLI 可导出/对接，Data Contract CLI 官方支持导出 SodaCL（[Export: SodaCL](https://docs.datacontract.com/exports/sodacl)）。
4. **CI 门禁成熟**：GitHub Action 直接可用，配合 PR 检查体验好。
5. **生态互操作**：Data Contract CLI 的 check generation 明确集成 Soda（[DeepWiki: Check Generation and Soda Integration](https://deepwiki.com/datacontract/datacontract-cli/5.2-check-generation-and-soda-integration)）。

### 2.5 弱点与抱怨（带来源）

1. **v3 → v4 的换代与文档割裂**：Soda Core 4.x 已发布（官方标注 "First Public Release of Version 4"），但主文档站仍以 "Soda v3" 为路径组织，用户容易踩到版本错配。
   - [v4.0.5 发布记录](https://newreleases.io/project/github/sodadata/soda-core/release/v4.0.5)、[v4.0.8](https://newreleases.io/project/github/sodadata/soda-core/release/v4.0.8)、[v4.1.0](https://newreleases.io/project/github/sodadata/soda-core/release/v4.1.0)
   - [Soda v3 文档入口](https://docs.soda.io/soda-documentation/soda-v3)、[Soda Core release notes](https://docs.soda.io/release-notes/soda-core)
2. **OSS 版本缺 UI / 调度 / 告警与协作**：仪表盘、事件、告警、契约管理均在 Soda Cloud 侧，纯 OSS 只能拿到 CLI + 结果文件。
3. **数据源驱动安装碎片化**：按数据源安装不同 `soda-core-<datasource>` 包，环境矩阵复杂（[soda-core README](https://raw.githubusercontent.com/sodadata/soda-core/refs/heads/main/README.md)）。
4. **部分能力成熟度仍在演进**：例如自动化新鲜度检测的查询能力、扫描结果指标缺少期望值字段等，都是社区提出并长期跟进的 issue。
   - [Issue #1254：Queries for automated freshness detection](https://github.com/sodadata/soda-core/issues/1254)
   - [Issue #1817：metrics 应包含 expected value](https://github.com/sodadata/soda-core/issues/1817)
5. **供应商层不确定性**：Soda 近年在定位上向「AI-native data quality」迁移（第三方评述，**待核实**：[Modern DataTools](https://www.modern-datatools.com/tools/soda/pricing)、[Information Difference 厂商档案 2025-10](https://www.informationdifference.com/wp-content/uploads/Information-Difference-Vendor-Profile-Soda-October-2025-Word.pdf)），开源与云的功能边界可能继续移动。
6. **无业务术语表/分类分级/审批流**：与 §5 共性短板一致，Soda 不解决治理语义层问题。

### 2.6 技术实现线索

- 语言：Python（CLI + 库），许可证 Apache 2.0。
- 部署：本地/容器/CI 运行 CLI；云版为 Soda Cloud SaaS（通过 API key 回传结果）。
- 配置：`configuration.yml`（数据源）+ `checks.yml`（SodaCL 检查）。
- 元数据存储：OSS 侧无服务端存储，结果输出为文件/日志；集中存储与历史趋势在 Soda Cloud。
- 编排集成：Airflow、dbt、GitHub Actions、Databricks 等；有独立 GitHub Action。

---

## 3. Elementary（dbt-native 数据可观测性）

### 3.1 定位与目标客户

- 定位：**长在 dbt 上的数据可观测性层**——不重写校验语言，而是扩展 dbt tests 并叠加异常检测与报告。
- 目标客户：**已经在用 dbt 的 analytics engineering 团队**，希望在 dbt 之上补齐「开箱即用的异常检测 + 人可读报告 + 告警」。
- 相关：[Elementary OSS vs Cloud](https://docs.elementary-data.com/cloud/cloud-vs-oss)。

### 3.2 商业模式与价格

| 形态 | 模式 | 价格 | 来源 |
|---|---|---|---|
| Elementary OSS（dbt package + `edr` CLI） | Apache 2.0 开源，免费 | 0 | [仓库](https://github.com/elementary-data/elementary)、[dbt-data-reliability README](https://raw.githubusercontent.com/elementary-data/dbt-data-reliability/refs/tags/0.19.4/README.md) |
| Elementary Cloud | SaaS，**以询价（quoted）为主**，非公开标价 | **具体金额待核实** | [官方定价页](https://www.elementary-data.com/pricing)、[Cloud FAQ](https://docs.elementary-data.com/cloud/resources/faq) |
| 第三方整理 | 明确列为「dbt package 免费、Cloud 询价」 | 待核实 | [Modern DataTools — Elementary Pricing](https://www.modern-datatools.com/tools/elementary/pricing) |
| 社区赞助 | 通过 GitHub Sponsors 获资助 | — | [GitHub Sponsors: elementary](https://github.com/sponsors/elementary) |

- **采购注意**：询价制意味着无法在预算阶段做自底向上的精确测算；第三方「hidden costs / negotiation」类分析存在但属非官方口径（[CostBench](https://costbench.com/software/data-observability/elementary-data/hidden-costs/)，**待核实**）。

### 3.3 核心功能模块清单

- **dbt 原生测试扩展**：在其上定义 schema/column 级测试，跟随 dbt 运行。
- **异常检测测试（anomaly detection tests）**：volume、freshness、schema 变更、column 级异常，按历史基线自动判定。
  - OSS 与 Cloud 的异常检测能力差异有官方专页：[OSS vs Cloud Anomaly Detection](https://docs.elementary-data.com/data-tests/anomaly-detection-tests-oss-vs-cloud)。
- **`edr` CLI**：`edr monitor`（跑监控）、`edr report`（生成静态 HTML 报告）、`edr send-report`（分发）。
- **告警通道**：Slack / Teams / Email。
- **血缘与影响面**：基于 dbt manifest 的模型/列级血缘。
- **Source freshness 监控**：对 dbt sources 做新鲜度与波动监控。
- **Elementary Cloud**：托管 UI、告警配置界面、历史趋势、协作视图。
- **dbt Fusion 集成（演进中）**：[dbt Fusion (Beta) 集成页](https://docs.elementary-data.com/oss/integrations/dbt-fusion)。

### 3.4 差异化所长

1. **零摩擦接入 dbt**：不需要新的 DSL 或新的执行器，复用 dbt 的 DAG、物化与调度。
2. **异常检测开箱可用**：相比 GX/Soda 需要手写阈值，Elementary 直接给统计基线，冷启动快。
3. **非工程角色可读**：静态 HTML 报告与 Cloud UI 对分析师/业务方友好。
4. **客户案例可佐证价值**：[StubHub International 案例](https://www.elementary-data.com/customer-stories/stubhub-international)。

### 3.5 弱点与抱怨（带来源）

1. **强绑定 dbt**：非 dbt 资产（流式、SaaS 原始表、非 dbt 数仓对象）覆盖弱；不用 dbt 的组织基本不适用。
2. **OSS 与 Cloud 能力被刻意切分**：异常检测的配置深度与告警管理在 Cloud，官方专门写了一页说明差异（[OSS vs Cloud Anomaly Detection](https://docs.elementary-data.com/data-tests/anomaly-detection-tests-oss-vs-cloud)），这是「社区版裁剪」的直接证据。
3. **结果落回数仓带来成本与权限问题**：需在目标数仓建 `elementary` schema 存测试结果与统计表，涉及额外权限与存储/查询成本。
4. **阈值与窗口调参痛点**（真实社区提问）：
   - [dbt Community：freshness_anomalies 只能看最近一天历史，无法只看最近一小时](https://discourse.getdbt.com/t/how-can-i-make-elementary-freshness-anomalies-tests-check-only-the-last-hours-history-instead-of-the-last-day/20509)
   - [Stack Overflow：自定义异常检测阈值怎么做](https://stackoverflow.com/questions/79848075/how-to-dbt-local-elementary-anomaly-tests-custom-thresholds)
5. **与平台细节的兼容性 bug 常年存在**：列引用忽略 dbt quote 配置、Redshift 上标识符超长导致失败、source freshness 报表报错等。
   - [Issue #1902：timestamp_column 不遵守 dbt column quote 配置](https://github.com/elementary-data/elementary/issues/1902)
   - [Issue #695：Redshift "Relation name is longer than 127 characters"](https://github.com/elementary-data/dbt-data-reliability/issues/695)
   - [Issue #1270：跑 source freshness 报表报错](https://github.com/elementary-data/elementary/issues/1270)
6. **无业务术语表 / 审批流 / 分类分级 / 事故管理**：与 §5 共性短板一致。

### 3.6 技术实现线索

- 语言：Python（CLI `edr`）+ dbt Jinja/SQL macros（package）。
- 部署：跟随 dbt 运行；报告为静态 HTML 或推送 Cloud。
- 元数据存储：**结果与统计写入目标数据仓库的 `elementary` schema**（这是它与其他工具最大的架构差异）。
- 元数据来源：dbt artifacts（`manifest.json` / `run_results.json`）。
- 连接器：依赖 dbt adapter 生态，自身不维护独立连接器矩阵。
- 许可证：Apache 2.0。

---

## 4. dbt（dbt Core + dbt Cloud / dbt platform）— 事实上的 OSS/ELT 治理层

### 4.1 定位与目标客户

- 定位：从「转换工具」演进为**事实上的开源治理层**：模型定义即契约、测试即质量门禁、文档/血缘由编译产物自动生成、跨项目治理由 Mesh 承载。
- 目标客户：analytics engineering 团队；中大型企业中已把 dbt 作为数仓建模唯一入口的组织（此时治理能力实际被 dbt 生态承接）。
- 相关：[dbt 定价页](https://www.getdbt.com/pricing)、[Model contracts 文档](https://docs.getdbt.com/docs/collaborate/govern/model-contracts)、[About dbt Mesh](https://docs.getdbt.com/docs/mesh/about-mesh)。

### 4.2 商业模式与价格

| 形态 | 模式 | 价格 | 来源 |
|---|---|---|---|
| dbt Core | Apache 2.0 开源，免费 | 0 | [dbt-core 仓库](https://github.com/dbt-labs/dbt-core) |
| dbt Cloud — Developer | 单人免费档 | 0 | [官方定价](https://www.getdbt.com/pricing)、[Trial and billing](https://docs.getdbt.com/docs/dbt-ai/pricing-billing/trial-and-billing) |
| dbt Cloud — Starter | 按席位订阅 | **约 100 美元/用户/月**（第三方一致口径） | [CompareEdge: "from $100/user/mo"](https://comparedge.com/tools/dbt-cloud/pricing#tier-starter)、[Automation Atlas: "Cloud Starter $100/seat"](https://automationatlas.io/answers/dbt-pricing-explained-2026/) |
| dbt Cloud — Enterprise | 定制报价 + 查询计量 | 待核实 | [官方定价](https://www.getdbt.com/pricing)、[Billing 文档](https://docs.getdbt.com/docs/cloud/billing)、[Vendr 成交数据（$27,930 示例）](https://www.vendr.com/marketplace/dbt-cloud) |
| 席位定义 | 计费席位/用户规则 | — | [Users and licenses / seats and users](https://docs.getdbt.com/docs/platform/manage-access/seats-and-users) |

- 第三方对「企业实际支付」的分析可参考 [VendorBenchmark：dbt Labs Pricing in 2026](https://vendorbenchmark.com/vendors/dbt-labs-pricing)、[Paradime：dbt Cloud Pricing](https://www.paradime.io/guides/dbt-cloud-pricing)（**具体成交额待核实**）。

### 4.3 核心功能模块清单

- **dbt tests**：内置 generic tests（`unique` / `not_null` / `accepted_values` / `relationships`）+ 自定义 generic/singular tests；另有 unit tests。
- **Model contracts（模型契约）**：`contract: {enforced: true}` + 列级 `data_type` 与 `constraints`（not_null / primary_key / foreign_key / unique / check）。
  - [Model contracts](https://docs.getdbt.com/docs/collaborate/govern/model-contracts)
  - [contract 配置](https://docs.getdbt.com/reference/resource-configs/contract)
  - [constraints](https://docs.getdbt.com/reference/resource-properties/constraints)
  - [模型治理总览](https://docs.getdbt.com/docs/mesh/govern/about-model-governance)
- **dbt docs**：`dbt docs generate` / `dbt docs serve`，从 manifest/catalog 自动生成文档站与 DAG。
  - [Build and view your docs](https://docs.getdbt.com/docs/build/view-documentation)
- **dbt Explorer（Cloud）**：项目级数据发现、血缘、模型健康、跨项目探索。
  - [Discover data with dbt Explorer](https://docs.getdbt.com/docs/collaborate/explore-projects)
  - [Explore multiple projects / project-level lineage graph](https://docs.getdbt.com/docs/explore/explore-multiple-projects)
  - 官方文档历史提交明确写出 Explorer 的可用档位（"available on Tea..."，即 Team 及以上）：[docs 提交 56118a2](https://github.com/dbt-labs/docs.getdbt.com/commit/56118a2c38aee38dea3990ee03dc6c77a5f9c590)
- **dbt Mesh（跨项目治理）**：`public` 模型、跨项目 `ref`、模型版本（model versions）、访问控制与治理约定。
  - [About dbt Mesh](https://docs.getdbt.com/docs/mesh/about-mesh)
  - [Intro to dbt Mesh（社区最佳实践）](https://raw.githubusercontent.com/dbt-labs/docs.getdbt.com/ab45fe5d7da7d820a8e8a10894627208c48d0942/website/docs/best-practices/how-we-mesh/mesh-1-intro.md)
- **Discovery API**：以 API 方式查询 dbt 元数据（供外部治理平台消费）。
  - [Discovery API / querying](https://docs.getdbt.com/docs/dbt-apis/discovery-querying)
- **CI 工作流**：`state:modified` 等基于 manifest 的增量校验，配合 PR 门禁。

### 4.4 差异化所长

1. **契约与模型定义同源**：契约写在模型 YAML 里，和转换逻辑同一次编译、同一次部署，避免「契约漂移」。
2. **血缘/文档零成本**：不靠爬取，直接来自编译器产物 `manifest.json`，准确度高于大多数靠日志推断的血缘方案。
3. **跨项目治理在开源生态中几乎无替代**：Mesh + model versions + public 模型解决的是「多团队共享数据产品」问题，GX/Soda/Elementary 都不在这层。
4. **生态最大**：适配器、包管理（dbt package hub）、社区实践与人才供给最充足。
5. **治理可渐进**：从加一个 test 开始，不需要一次性上治理平台。

### 4.5 弱点与抱怨（带来源）

1. **测试只覆盖数据质量的一个子集**：只有断言式测试，**没有**异常检测、没有跨系统业务规则、没有事件/值班/事故管理。dbt 官方自己把「beyond dbt tests」当作议题：[dbt Summit: Testing 1,2,3 — Catching silent data failures beyond dbt tests](https://www.getdbt.com/dbt-summit/agenda/testing-1-2-3-catching-silent-data-failures-beyond-dbt-tests)。
2. **测试失败容易「静默」**：测试结果不会自动变成对人可见告警，需要额外工具补位（[Bigeye: So you've implemented dbt tests / great expectations, now what?](https://www.bigeye.com/blog/so-youve-implemented-dbt-tests-great-expectations-now-what)、[AnomalyArmor: How to Catch Silent dbt Test Failures](https://blog.anomalyarmor.ai/how-to-catch-silent-dbt-test-failures-before-they-hit-dashboards/)）。
3. **契约有生效范围限制**：契约只在受支持的物化方式（如 table / incremental）上被强制；view、ephemeral 等不支持，且必须**显式声明全部列**，模型改动成本高。
   - [Model contracts 文档中的限制说明](https://docs.getdbt.com/docs/collaborate/govern/model-contracts)
   - [FAQ：间接引用的 upstream public 模型为何不出现在 Explorer](https://docs.getdbt.com/faqs/Project_ref/indirectly-reference-upstream-model)（反映 Mesh/Explorer 的语义边界会带来困惑）
4. **治理能力被切到 Cloud**：Explorer、Mesh 相关的托管能力属 Cloud 档位；dbt Core 用户拿不到 UI、权限、跨项目发现。这是「开源版被裁剪」的典型案例（[Explorer 档位说明](https://github.com/dbt-labs/docs.getdbt.com/commit/56118a2c38aee38dea3990ee03dc6c77a5f9c590)、[dbt Core vs dbt Cloud 对比](https://dataworkers.io/resources/dbt-cloud-vs-dbt-core/)）。
5. **dbt Core 无调度/无告警/无权限**：`dbt docs serve` 是本地静态站，无 RBAC、无业务术语表、无分类分级、无审批（[dbt docs 文档](https://docs.getdbt.com/docs/build/view-documentation)）。
6. **Fusion 引擎迁移带来新不确定性**：治理构造（如模型治理）在新引擎上的对齐仍在推进中，社区已开 issue：[Polish model governance constructs for dbt Mesh · dbt-fusion #25](https://github.com/dbt-labs/dbt-fusion/issues/25)。

### 4.6 技术实现线索

- 语言/运行时：dbt Core 为 Python；新引擎 dbt Fusion 为 Rust（[dbt-fusion 仓库/issue 可见](https://github.com/dbt-labs/dbt-fusion/issues/25)）。
- 部署：dbt Core 本地/CI/自建调度；dbt Cloud 为托管 SaaS。
- 元数据存储：编译产物 `manifest.json` / `catalog.json` / `run_results.json`；Cloud 侧另有 Discovery API 后端。
- 编排集成：Airflow / Dagster / Prefect / dbt Cloud scheduler 均常用。
- 许可证：dbt Core Apache 2.0。

---

## 5. 周边对照（仅用于「开源共性短板」取证）

| 项目 | 定位 | 商业模式 | 关键短板证据 |
|---|---|---|---|
| **OpenMetadata** | 开源元数据/目录 + 数据质量 + 术语表 | Apache 2.0 开源 + Collate 商业云 | 权限模型缺陷（有全权限仍无法编辑/删除 DQ 测试规则）[Issue #10859](https://github.com/open-metadata/OpenMetadata/issues/10859)；术语表审批流不完善 [Issue #14391](https://github.com/open-metadata/OpenMetadata/issues/14391)、[Issue #13964](https://github.com/open-metadata/OpenMetadata/issues/13964)；文档中的审批能力见 [Glossary approval](https://docsv1.netlify.app/v1.3.x/how-to-guides/data-governance/glossary/approval) |
| **DataHub** | 元数据平台/血缘/目录 | Apache 2.0 开源 + Acryl/DataHub Cloud | **审批工作流与 Change Proposals 属 Managed（Cloud）专有**：[Change Proposals](https://docs.datahub.com/docs/managed-datahub/change-proposals)、[Approval Workflows](https://raw.githubusercontent.com/datahub-project/datahub/refs/heads/master/docs/managed-datahub/approval-workflows.md)、[Managed DataHub 总览](https://docs.datahub.com/docs/managed-datahub/managed-datahub-overview) |
| **Apache Atlas** | Hadoop 时代血缘/分类治理 | Apache 2.0 | 项目成熟度模型的官方自评可参考 [Apache Atlas Project Maturity Model](https://cwiki.apache.org/confluence/download/export/pdfexport-20250626-260625-1005-235007/Apache+Atlas+Project+Maturity+Mo_7b7f090e73ed47adaf3a0dd9babecf86-260625-1005-235008.pdf)；生态重心已明显向 DataHub/OpenMetadata 迁移（[第三方对比](https://www.decube.io/post/open-source-data-catalog-comparison)） |
| **Amundsen** | 元数据驱动的数据发现 | Apache 2.0 | **因长期不活跃，项目已于 2026 年 9 月归档（archived）**：[amundsen README](https://raw.githubusercontent.com/amundsen-io/amundsenfrontendlibrary/master/README.md)、[amundsen 仓库](https://github.com/amundsen-io/amundsen) |
| **OpenLineage / Marquez** | 血缘事件标准 + 元数据服务 | Apache 2.0 | **列级血缘覆盖不完整**（官方自己写的现状总结）：[The Current State of Column-level Lineage](https://openlineage.io/blog/column-lineage/)；具体缺口：CSV schema/列级血缘未采集 [Discussion #2568](https://github.com/OpenLineage/OpenLineage/discussions/2568)、Snowflake 列级血缘支持问题 [Discussion #2271](https://github.com/OpenLineage/OpenLineage/discussions/2271)、Spark TempView 列级血缘缺失 [Issue #2672](https://github.com/OpenLineage/OpenLineage/issues/2672)；Marquez 侧作业分组/层级、无类型字段等仍在 issue 阶段 [Marquez #1928](https://github.com/MarquezProject/marquez/issues/1928)、[Marquez #2261](https://github.com/MarquezProject/marquez/issues/2261) |
| **ODCS（开放数据契约标准）** | 数据契约的开放标准（Bitol 主导） | 开放标准，免费 | 最新版本 **ODCS v3.1.0**：[发布公告](https://bitol.io/bitol-announces-odcs-v3-1-0-stronger-smarter-and-stricter/)、[v3.1.0 Changelog](https://bitol-io.github.io/open-data-contract-standard/v3.1.0/changelog/)、[规范仓库](https://github.com/bitol-io/open-data-contract-standard) |
| **Data Contract CLI** | 契约的校验/生成/导出工具链 | 开源（Apache 2.0） | 支持导出 SodaCL：[Export: SodaCL](https://docs.datacontract.com/exports/sodacl)；支持 RDF 导出 [Exports: RDF](https://docs.datacontract.com/exports/rdf)；**真实落地被放弃的案例**：[Data Contract CLI 技术验证及导入见送理由整理（日文）](https://zenn.dev/sugato/articles/1dd6891e902d2b) |

> 全局背景补充：数据契约的落地障碍被广泛认为是**组织性的而非技术性的**（[Gable: Why You Can't Seem to Adopt Data Contracts](https://www.gable.ai/blog/why-you-cant-seem-to-adopt-data-contracts-no-matter-how-hard-you-try)）。

---

## 6. 开源数据质量 / 治理工具的共性短板清单

> 共 15 条。每条给出「现象 → 证据」。这些是「build vs buy」中**买方付费真正买到的东西**，也是自建方案里最容易被低估的部分。

1. **缺少业务术语表与治理审批工作流（OSS 层基本缺位或极弱）**
   OSS 目录产品的审批流多为商业版专有：DataHub 的 Change Proposals / Approval Workflows 位于 `managed-datahub` 文档域；OpenMetadata 的术语表审批长期有未解 issue。
   → [DataHub Change Proposals](https://docs.datahub.com/docs/managed-datahub/change-proposals)、[DataHub Approval Workflows（managed）](https://raw.githubusercontent.com/datahub-project/datahub/refs/heads/master/docs/managed-datahub/approval-workflows.md)、[OpenMetadata #14391](https://github.com/open-metadata/OpenMetadata/issues/14391)、[OpenMetadata #13964](https://github.com/open-metadata/OpenMetadata/issues/13964)

2. **缺少分级分类与合规能力（PII 自动识别、数据出境、审计留痕）**
   质量工具（GX/Soda/Elementary/dbt）本身不含 PII 识别、分类分级与合规报表；学术研究亦指出数据网格/治理实践中 **PII 处理是最显著的缺口之一**。
   → [arXiv 2604.09163（专有工具在规则定义上更强）](https://arxiv.org/pdf/2604.09163v1)、[数据网格论文（PII 明显不足）](http://dolgozattar.uni-bge.hu/60435/1/csatari_levente_data_mesh_tdk_dolgozat.pdf)

3. **血缘依赖手工或覆盖不完整（尤其列级与跨系统）**
   OpenLineage 官方自述列级血缘仍处演进中，多个连接器与场景（CSV、Snowflake、Spark TempView）明确缺失。
   → [OpenLineage: The Current State of Column-level Lineage](https://openlineage.io/blog/column-lineage/)、[OpenLineage Discussion #2568](https://github.com/OpenLineage/OpenLineage/discussions/2568)、[Discussion #2271](https://github.com/OpenLineage/OpenLineage/discussions/2271)、[Issue #2672](https://github.com/OpenLineage/OpenLineage/issues/2672)

4. **无多租户 / 无 SaaS 运维能力：自建即自负运维**
   自建目录/血缘/质量平台需要自己承担部署、升级、备份、扩缩容、监控；第三方对比普遍把运维人力列为开源方案的主要隐性成本。
   → [Build vs. Buy Data Catalogs](https://atlan.com/build-vs-buy-data-catalog/)、[Acceldata: Purchase vs Build](https://www.acceldata.io/blog/purchase-vs-build-a-practical-guide-to-data-governance-platforms)、[Open Source Data Observability Compared](https://dataworkers.io/resources/open-source-data-observability-compared/)

5. **连接器/驱动碎片化，缺乏真正开箱即用的生态**
   Soda 按数据源分包安装；GX 需为不同执行引擎写不同代码；Elementary 完全依赖 dbt adapter。
   → [soda-core README](https://raw.githubusercontent.com/sodadata/soda-core/refs/heads/main/README.md)、[Elementary dbt Fusion 集成](https://docs.elementary-data.com/oss/integrations/dbt-fusion)

6. **无 SLO / 事故管理 / 值班分派**
   开源质量工具输出的是「检查结果」，不是「事件」。缺少事件去重、分派、升级、复盘、SLO 追踪。dbt 官方也把「测试之外的静默失败」当成独立议题。
   → [dbt Summit: beyond dbt tests](https://www.getdbt.com/dbt-summit/agenda/testing-1-2-3-catching-silent-data-failures-beyond-dbt-tests)、[Bigeye: now what?](https://www.bigeye.com/blog/so-youve-implemented-dbt-tests-great-expectations-now-what)

7. **权限与细粒度授权/行级安全薄弱**
   OSS 目录的权限模型粗糙甚至存在功能性缺陷：OpenMetadata 有全权限用户仍无法编辑/删除数据质量测试规则。
   → [OpenMetadata Issue #10859](https://github.com/open-metadata/OpenMetadata/issues/10859)、[DataHub 访问控制策略说明](https://blog.gitcode.com/45b4685db3939918a37b2cfb9bd1ba87.html)

8. **社区版功能被刻意裁剪（open-core 的必然结果）**
   逐项可证：GX Cloud 专有 UI/告警；Soda Cloud 专有仪表盘/契约管理/事件；Elementary Cloud 专有异常检测配置深度与告警管理；dbt Cloud 专有 Explorer/Mesh 托管能力；DataHub Cloud 专有审批工作流。
   → [Elementary OSS vs Cloud Anomaly Detection](https://docs.elementary-data.com/data-tests/anomaly-detection-tests-oss-vs-cloud)、[Elementary OSS vs Cloud](https://docs.elementary-data.com/cloud/cloud-vs-oss)、[DataHub Managed 总览](https://docs.datahub.com/docs/managed-datahub/managed-datahub-overview)、[dbt Explorer 档位](https://github.com/dbt-labs/docs.getdbt.com/commit/56118a2c38aee38dea3990ee03dc6c77a5f9c590)

9. **破坏性升级频繁，迁移成本由用户承担**
   GX 0.18 → GX Core 1.x 属 API 重写（官方发布迁移指南）；Soda Core 3.x → 4.x 换代且文档仍以 v3 组织。
   → [Changes to know for GX Core 1.0](https://greatexpectations.io/blog/changes-to-know-for-gx-core-1-0/)、[GX V0→V1 迁移指南](https://docs.greatexpectations.io/docs/0.18/reference/learn/migration_guide/)、[soda-core v4.0.5 首发](https://newreleases.io/project/github/sodadata/soda-core/release/v4.0.5)、[Soda v3 文档](https://docs.soda.io/soda-documentation/soda-v3)

10. **项目治理与商业化路线不确定，存在「弃养/易主」风险**
    Amundsen 因长期不活跃于 2026-09 归档；Great Expectations 的开源社区与 GX Core 于 2026-05 转由 Fivetran 接管。开源组件的长期路线图不由用户控制。
    → [Amundsen README（归档声明）](https://raw.githubusercontent.com/amundsen-io/amundsenfrontendlibrary/master/README.md)、[Fivetran 接管 GX 新闻稿](https://www.fivetran.com/de/press/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project)、[UK Tech News 报道](https://uktechnews.co.uk/2026/05/14/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project/)

11. **告警噪音与缺少抑制/去重/静默策略**
    数据测试天然产生大量告警，行业讨论普遍把 alert fatigue 列为数据测试规模化的第一障碍。
    → [Secoda: Alert Fatigue in Data Testing](https://www.secoda.co/blog/what-is-data-testing-alert-fatigue)、[Alert Fatigue 通用案例（InfoQ）](https://www.infoq.com/news/2024/06/alert-fatigue-cloudflare/)

12. **需要写代码/工程化投入，业务与治理角色无法自助**
    GX 需 Python；dbt 需 SQL+YAML+Git；Elementary 需 dbt 环境；Data Contract CLI 需工程化接入。业务术语/规则无法由数据治理专员在界面上完成。
    → [GX vs Soda（Python 代码 vs YAML）](https://fastero.com/blog/great-expectations-vs-soda-data-quality-compared)、[Data Observability: Build vs. Buy](https://adriennevermorel.com/articles/data-observability-build-vs-buy/)

13. **无内置工作流引擎（审批、工单、变更记录、责任分配）**
    变更契约、新增术语、修改规则都没有原生的「提交—评审—批准—留痕」链路；OSS 侧只能靠 Git PR 变通，非工程角色无法参与。
    → [DataHub Change Proposals（仅 Managed）](https://docs.datahub.com/docs/managed-datahub/change-proposals)、[OpenMetadata 术语表审批 issue](https://github.com/open-metadata/OpenMetadata/issues/14391)

14. **元数据/血缘覆盖弱于商业平台，尤其跨系统与非 dbt 资产**
    血缘多来自单点（dbt manifest 或单一 orchestrator），跨系统、SaaS 源、非托管作业覆盖不足；第三方对比与 OSS 目录对比文章反复指出覆盖深度差距。
    → [Open Source Data Catalog 对比（Decube）](https://www.decube.io/post/open-source-data-catalog-comparison)、[OpenLineage 列级血缘现状](https://openlineage.io/blog/column-lineage/)、[Atlan Alternatives: 6 Open-Source Data Catalogs Compared](https://dataworkers.io/blog/atlan-alternatives-open-source-data-catalogs-2026/)

15. **总拥有成本被系统性低估：人力 > 许可证**
    开源方案省下的是订阅费，付出的是平台工程人力、集成开发、升级验证与运行时基础设施；商业对比材料与自建经验都指向同一结论。数据质量工具的「建 vs 买」分析中，**人力折算通常是主导项**。
    → [Data Observability: Build vs. Buy](https://adriennevermorel.com/articles/data-observability-build-vs-buy/)、[Acceldata: Purchase vs Build](https://www.acceldata.io/blog/purchase-vs-build-a-practical-guide-to-data-governance-platforms)、[Atlan: Build vs. Buy Data Catalogs](https://atlan.com/build-vs-buy-data-catalog/)

### 6.1 附：典型「开源组合」与商业平台的覆盖差

| 能力 | 典型 OSS 组合（dbt + GX/Soda + Elementary + DataHub/OpenMetadata + OpenLineage） | 商业平台常见覆盖 |
|---|---|---|
| 断言式数据质量检查 | ✅ 强 | 强 |
| 异常检测/动态阈值 | ⚠️ 部分（Elementary/Soda 需 Cloud） | 强 |
| 数据契约 | ⚠️ 有标准（ODCS）与工具，落地靠工程 | 强（含工作流） |
| 血缘（含列级、跨系统） | ⚠️ 覆盖不全 | 强（含业务血缘） |
| 业务术语表 + 审批工作流 | ❌ 弱/无（多属商业版） | 强 |
| 分类分级 / PII / 合规留痕 | ❌ 基本无 | 强 |
| 权限/RBAC/行级安全 | ⚠️ 粗粒度 | 强 |
| SLO / 事故管理 / 值班 | ❌ 无 | 强 |
| 多租户 SaaS 运维 | ❌ 自担 | 内置 |
| 每次大版本升级的迁移成本 | ⚠️ 高 | 供应商承担 |

（✅/⚠️/❌ 为基于上文证据的定性判断；其中标注 ⚠️ 的项，其「强/弱」结论高度依赖是否购买对应厂商的 Cloud 版。）

---

## 7. 一手来源清单（便于复核）

**官方文档**
- Great Expectations：[GX Overview](https://docs.greatexpectations.io/docs/0.18/reference/learn/conceptual_guides/gx_overview/)、[Validation workflow](https://docs.greatexpectations.io/docs/0.18/oss/guides/validation/validate_data_overview/)、[迁移指南](https://docs.greatexpectations.io/docs/0.18/reference/learn/migration_guide/)、[GX Core 1.0 变更](https://greatexpectations.io/blog/changes-to-know-for-gx-core-1-0/)、[定价](https://greatexpectations.io/pricing/)、[Cloud FAQ](https://greatexpectations.io/gx-cloud-faqs/)
- Soda：[docs.soda.io](https://docs.soda.io/)、[SodaCL 总览](https://docs.soda.io/soda-documentation/soda-v3/soda-cl-overview)、[数据契约验证](https://docs.soda.io/soda-documentation/soda-v3/data-contracts/data-contracts-verify)、[Soda Core 发布说明](https://docs.soda.io/release-notes/soda-core)、[定价](https://soda.io/pricing)
- Elementary：[OSS vs Cloud](https://docs.elementary-data.com/cloud/cloud-vs-oss)、[OSS vs Cloud 异常检测](https://docs.elementary-data.com/data-tests/anomaly-detection-tests-oss-vs-cloud)、[Cloud FAQ](https://docs.elementary-data.com/cloud/resources/faq)、[定价](https://www.elementary-data.com/pricing)
- dbt：[模型契约](https://docs.getdbt.com/docs/collaborate/govern/model-contracts)、[contract 配置](https://docs.getdbt.com/reference/resource-configs/contract)、[constraints](https://docs.getdbt.com/reference/resource-properties/constraints)、[dbt Explorer](https://docs.getdbt.com/docs/collaborate/explore-projects)、[dbt Mesh](https://docs.getdbt.com/docs/mesh/about-mesh)、[Discovery API](https://docs.getdbt.com/docs/dbt-apis/discovery-querying)、[席位与许可](https://docs.getdbt.com/docs/platform/manage-access/seats-and-users)、[定价](https://www.getdbt.com/pricing)、[Billing](https://docs.getdbt.com/docs/cloud/billing)
- 契约标准：[ODCS v3.1.0 公告](https://bitol.io/bitol-announces-odcs-v3-1-0-stronger-smarter-and-stricter/)、[ODCS Changelog](https://bitol-io.github.io/open-data-contract-standard/v3.1.0/changelog/)、[Data Contract CLI](https://docs.datacontract.com/open-data-contract-standard)
- 元数据/血缘：[DataHub Managed 总览](https://docs.datahub.com/docs/managed-datahub/managed-datahub-overview)、[OpenLineage 列级血缘现状](https://openlineage.io/blog/column-lineage/)

**第三方中性分析**
- [Modern DataTools — GX 定价](https://www.modern-datatools.com/tools/great-expectations/pricing) / [Soda 定价](https://www.modern-datatools.com/tools/soda/pricing) / [Elementary 定价](https://www.modern-datatools.com/tools/elementary/pricing) / [dbt Cloud 定价](https://www.modern-datatools.com/tools/dbt-cloud/pricing)
- [Soda vs Elementary](https://www.modern-datatools.com/compare/soda-vs-elementary)、[Soda vs Great Expectations](https://www.modern-datatools.com/compare/soda-vs-great-expectations)
- [Data Observability: Build vs. Buy](https://adriennevermorel.com/articles/data-observability-build-vs-buy/)
- [Open Source Data Catalog 对比](https://www.decube.io/post/open-source-data-catalog-comparison)
- [arXiv 2604.09163（数据质量工具能力研究）](https://arxiv.org/pdf/2604.09163v1)

**待核实清单（本次未取回页面正文，需人工核对）**
1. GX Cloud 各档具体月费/年费与配额 → [定价页](https://greatexpectations.io/pricing/)
2. Soda Cloud 各档具体月费与数据集计数规则 → [定价页](https://soda.io/pricing)
3. Elementary Cloud 具体报价与最低年费 → [定价页](https://www.elementary-data.com/pricing)
4. dbt Cloud Enterprise 实际成交价与查询计量单价 → [定价页](https://www.getdbt.com/pricing)
5. dbt Cloud Starter 的席位下限与「$100/用户/月」是否含查询用量
6. OpenMetadata / DataHub 商业云的具体标价
