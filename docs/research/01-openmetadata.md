# OpenMetadata 深度技术调研

> 调研对象：开源元数据/数据治理平台 **OpenMetadata**（`open-metadata/OpenMetadata`）｜调研日期：2026-10-01
> 一手来源：[官方文档](https://docs.open-metadata.org/)、[元数据规范站](https://openmetadatastandards.org/)、[官方博客](https://blog.open-metadata.org/)、[GitHub 仓库](https://github.com/open-metadata/OpenMetadata)、[产品站](https://open-metadata.org/)。

## 0. 方法与局限

本次环境**网络出口受限**（沙箱内 HTTPS 被拒），无法抓取网页正文，检索工具只返回标题+URL、无摘要。故：机制性结论来自官方文档公开知识与检索到的官方 URL 交叉印证；**易变数字全部标注"待核实"**，须以实时数据为准；标 `(第三方)` 者为社区来源，仅作现象佐证。

## 1. 定位、目标用户、许可与社区

### 1.1 定位

面向数据与 AI 的**统一元数据平台**。早期口号 "discover, collaborate and get your data right"（目录+协作+治理）；2.0 起转向 **"The Open Context Layer for AI Agents"**，把元数据、血缘、质量、术语、策略统一为人和 Agent 共用的"数据上下文"（[2.0 公告](https://blog.open-metadata.org/announcing-openmetadata-2-0-the-open-context-layer-for-ai-agents-83b8ce8b9dde)、[2.0 Release](https://docs.open-metadata.org/v2.0.x/releases/2.0-release)）。它同时推动开放规范 [OpenMetadata Standards](https://openmetadatastandards.org/metadata-specifications/overview/)。

### 1.2 目标用户

| 角色 | 诉求 | 对应能力 |
|---|---|---|
| 数据/平台工程师 | 自动盘点、追踪血缘、接 dbt/Airflow | Ingestion、表级/列级血缘、Pipeline 集成 |
| 治理/数据管家 | 分类分级、PII、术语、契约、审批 | Classification/Tag、Glossary、Contract、Workflow |
| 分析师/科学家 | 找数据、懂数据、信数据 | 搜索、Profiler、数据质量、样例数据 |
| 数据 Owner | 明确责任、跟踪覆盖度 | Team/Ownership、Domain、Data Product、KPI |
| AI/Agent 开发者（2.0） | 给 LLM 可信上下文 | MCP Server、语义搜索 |

### 1.3 许可与公司

**Apache-2.0**（可商用、可闭源分发）。背后公司 **Collate**（`getcollate.io`）提供企业版与托管服务，典型 open-core；据第三方新闻稿已完成 1000 万美元 A 轮（[来源](https://wire.expertini.com/article/collate-raises-10m-series-a-to-solve-the-data-intelligence-challenges-for-enterprise-customers-2025-07-15.pdf)）。创始团队出身前 Uber 数据平台（Databook），**姓名待核实**。

### 1.4 社区与节奏

| 指标 | 情况 |
|---|---|
| GitHub Star | 约 **1 万量级**（待核实） |
| 贡献者 | 数百人（待核实），社区+Collate 全职混合 |
| 发布节奏 | **约每月一个小版本**；1.13→2.0 有破坏性变更（[Breaking Changes](https://openmetadatastandards.org/breaking-changes/)） |
| 治理 | 未见独立基金会治理（对比 DataHub 属 LF AI & Data），**待核实** |

高频发版是双刃剑：迭代快、社区活跃，也是升级痛点主因（见 §5）。

## 2. 功能清单

### 2.1 功能域

| 功能域 | 关键能力 | 来源 |
|---|---|---|
| 数据目录 | 表/列/Topic/Dashboard/Chart/Pipeline/ML Model/Container/存储过程/Search Index/API/File 等多实体统一目录 | [Connectors](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/connectors)、[Core Concepts](https://openmetadatastandards.org/core-concepts/overview/) |
| 元数据采集 | Python 采集框架，CLI/Airflow/Dagster 调度，80+ 连接器（**数量待核实**） | [Ingestion 深潜](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/developers/contribute/codebase-deep-dives/metadata-ingestion) |
| 数据血缘 | **表级+列级**；来源含查询日志解析、视图/存储过程、dbt、BI、ETL、OpenLineage；支持手工编辑 | [Lineage Workflow](https://docs.open-metadata.org/v2.0.x/connectors/ingestion/workflows/lineage) |
| 数据质量 | Profiler（列统计/直方图/空值/唯一值/样例）、表级与列级 Test Case + Test Suite、dbt test 回采、Great Expectations、自定义 SQL 断言 | [Data Quality 文档](https://docs.open-metadata.org/v2.0.x) |
| 数据契约 | YAML 契约实体，兼容 **ODCS** 标准；语义化版本、Draft/Active 状态、契约驱动质量断言 | [Create Data Contracts](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-contracts/create) |
| 术语表 | 多 Glossary、多级术语、同义词、术语关联与审批、CSV 导入导出、术语打标资产 | [Glossary 最佳实践](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-governance/glossary/best-practices) |
| 分类分级 | Classification→Tag→层级 Tag；内置 PII/SensitiveData；**自动分类**（列名正则+取样识别）；标签沿层级/血缘传播；Tag 作为策略锚点 | [Classification](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-governance/classification/best-practices)、[Tag 规范](https://openmetadatastandards.org/governance/tag/) |
| 所有权与协作 | Team 层级、User/Persona、Owner、Domain、Data Product；**Activity Feed**（评论/回复）、**Task**（改描述、申请 Owner、Tag/术语审批）、**Announcement**、关注/投票 | [Teams/Users](https://openmetadatastandards.org/teams-users/overview/) |
| 搜索 | ES/OpenSearch 全文检索、facet 聚合、高级查询语法、排序权重可配、建议/最近浏览 | [Search 规范](https://openmetadatastandards.org/data-assets/search/overview/) |
| 数据洞察 | KPI（资产数、描述/Owner/分级/PII 覆盖率、采集成功率）、周期报告、按 Domain/Data Product 下钻、页面访问分析 | [Data Insights 文档](https://docs.open-metadata.org/v2.0.x) |
| 治理工作流 | 声明式 **Workflow**（JSON 状态机：阶段+触发+转换），实现术语/契约审批、资产发布 | [Governance Workflow](https://docs.open-metadata.org/v2.0.x) |
| AI（2.0 重点） | **MCP Server** 暴露元数据/血缘/语义搜索工具；**语义搜索**向量索引；面向 Agent 的上下文与记忆 | [Semantic Search](https://docs.open-metadata.org/v2.0.x/deployment/semantic-search)、[MCP 工具](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/how-to-guides/mcp/semantic-search)、[官方源码](https://raw.githubusercontent.com/open-metadata/OpenMetadata/main/openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SemanticSearchTool.java) |

### 2.2 连接器覆盖（重点数据源）

| 服务类型 | 代表连接器 |
|---|---|
| 关系型库 ✅ | **MySQL、Hive、Doris、StarRocks**、PostgreSQL、Snowflake、BigQuery、Redshift、Databricks、Trino、ClickHouse、Oracle、SQL Server、Iceberg、Delta、MariaDB、Db2、Greenplum… |
| 消息 ✅ | **Kafka**（含 Schema Registry）、Redpanda、Kinesis、Pulsar、AMQP |
| BI ✅ | **Tableau**、Looker、Power BI、Superset、Metabase、Qlik、Redash、Mode、MicroStrategy、Sigma |
| 管道 ✅ | Airflow、**dbt**（Core/Cloud）、Dagster、Fivetran、Glue、Matillion、Spark、Flink、OpenLineage |
| ML ✅ | MLflow、SageMaker、Sklearn、TensorFlow、Vertex AI |
| 存储/API ✅ | S3、GCS、ADLS、MinIO、SFTP、Google Drive、SharePoint；ES/OpenSearch、REST API 资产 |

Doris/StarRocks 为官方连接器但社区成熟度低于主流商业数仓（**待核实**）。总量官方口径 80~90+（**待核实**）。关键设计：元数据、Usage、血缘、Profiler、质量是**各自独立可选的 workflow**，可分别设定频率与范围。

## 3. 架构与实现原理

### 3.1 技术栈

| 层 | 选型 |
|---|---|
| 后端 | **Java + Dropwizard**（Jetty + JAX-RS/Jersey）；JDBI3，`EntityRepository`→`CollectionDAO`/各实体 DAO |
| 前端 | **React + TypeScript + Ant Design**，Vite 构建（早期 Webpack，**迁移版本待核实**） |
| 规范 | **JSON Schema 单一事实源**（`openmetadata-spec`），代码生成 Java/TS/Python 类型与 API 文档 |
| 元数据库 | **MySQL 8 / PostgreSQL**（MySQL 默认） |
| 搜索 | **Elasticsearch 8.x / OpenSearch**，每实体类型一个索引（如 `table_search_index`） |
| 采集 | **Python 3.9+**，`openmetadata-ingestion`，关系型连接器重度使用 **SQLAlchemy**，配置用 pydantic |
| 调度 | CLI / **Airflow**（`openmetadata-managed-apis` 动态生成 DAG）/ Dagster / `pipelineServiceClient` |
| 血缘解析 | 自研 [openmetadata-sqllineage](https://github.com/open-metadata/openmetadata-sqllineage)（底层 sqlfluff/sqlparse 系） |
| AI | **openmetadata-mcp**（Java 模块）+ 语义搜索向量索引 |

### 3.2 元数据模型

- **Schema-First**：实体/类型/关系先在 JSON Schema 定义（`entity/`、`type/`、`api/`、`events/`），再生成各语言类型。这是项目**最强的工程不变量**，使 API/SDK/UI/采集端天然一致。
- **实体**：`id`(UUID)、`name`、**FQN**（层级式 `service.db.schema.table.column`）、单调递增 `version`、`changeDescription`、`owners`、`tags`、`extension`（自定义属性）。
- **关系**：`owner`/`follows`/`upstream`/`downstream`/`parentOf`/`contains` 等是一等公民，存于 `entity_relationship` 表（`fromId`/`toId`/`relationType`/`json`）。**血缘即关系的一种**，其 `json` 承载 `lineageDetails`（含**列级映射 columnsLineage**、SQL 原文、来源类型）。
- **层级**：`DatabaseService→Database→Schema→Table→Column` 由 `parentOf`/`contains` 表达，并决定 FQN 与权限继承。
- **时序数据**：Profile、测试结果、洞察指标写入 `entity_extension`（JSON+时间戳），与实体主表分离。
- **变更事件**：写操作产生 **ChangeEvent**（[规范](https://openmetadatastandards.org/events/change-event/)），驱动索引更新、Webhook、工作流触发。

### 3.3 存储：为何不用图数据库

| 维度 | OpenMetadata | 图方案（如 DataHub+Neo4j） |
|---|---|---|
| 血缘存储 | RDBMS 的 `entity_relationship` 表 + 关系 json 内的列级映射 | 图数据库/图索引 |
| 查询方式 | 应用层**有界深度递归**（`upstreamDepth`/`downstreamDepth`，**上限待核实**） | Cypher/Gremlin 多跳 |
| 优点 | 组件少、事务一致、同库同事务 | 深链/全图分析强 |
| 代价 | 深链与全图分析弱，遍历成性能热点 | 组件多、双写一致性难 |

即以 **"关系表+应用层遍历+ES 检索"** 换架构简洁性，复杂度上移到 Java 服务层。

### 3.4 Ingestion Framework

| 抽象 | 职责 |
|---|---|
| Source | 产出 `Record`（实体 JSON），组织为若干 `Step`（如 `MysqlMetadataSource`、`DbtSource`） |
| Processor | 记录过滤/转换（filter、add-owner、mark-deleted） |
| Stage | 中间落地（查询日志、血缘中间结果；File/DB 两种） |
| Sink | 写入目标（`metadata-rest`、file、elasticsearch） |
| Workflow | `metadata`/`usage`/`lineage`/`profiler`/`autoClassification`/`testSuite`/`dataInsight`/`application`/`reindex` |

配置全为 YAML 且受 JSON Schema 校验（`metadata ingest -c config.yaml`）。**采集本身不依赖 Airflow**——Airflow 只是"平台侧定时调度"的一种方式，也支持 Dagster 与 cron。取舍：采集独立部署、拉模式、可水平扩展、故障隔离，代价是**双运行时且采集端与服务端版本须对齐**。

### 3.5 血缘采集方式

| 方式 | 原理 |
|---|---|
| SQL 解析 | 采集 `query_history`/慢日志，用 sqllineage 解析表级与列级血缘（数仓/数据库） |
| 视图/存储过程 | 解析 DDL 与过程体 |
| dbt | 解析 `manifest.json`+`catalog.json`，还原 model→source→seed 及列级映射 |
| BI | Looker LookML、Tableau、Power BI 数据源 |
| ETL/编排 | Airflow 任务依赖、Dagster、Fivetran |
| OpenLineage | 接收 Airflow/Spark/Flink 侧事件（**端点与版本待核实**） |
| 手工 | UI 连线、列级映射编辑（兜底） |

### 3.6 API、SDK、事件与扩展

- **REST API**：统一 `/api/v1/...`，由 JSON Schema 生成 OpenAPI（[API Reference](https://docs.open-metadata.org/v2.0.x/api-reference)）；游标分页；`fields` 参数**按需展开**关联对象，避免 N+1。
- **SDK**：Python（`metadata.sdk`）、Java（`openmetadata-java-client`）；MCP Server 相当于"Agent 侧 SDK"。
- **事件/Webhook**：ChangeEvent→Webhook（Generic/Slack/Teams/Google Chat），可按实体与事件类型过滤，带签名与重试（[Webhooks](https://docs.open-metadata.org/v2.0.x/connectors/ingestion/versioning/event-notification-via-webhooks)、[规范](https://openmetadatastandards.org/operations/webhook/)）。
- **扩展点**：自定义属性（不改码扩字段）、自定义连接器（子类化 Source/Sink）、自定义质量测试、Application 插件框架、搜索排序与索引设置；改 JSON Schema 属"重扩展"（需重新代码生成）。

### 3.7 部署、多租户与权限

| 主题 | 现状 |
|---|---|
| 部署 | **Docker Compose**（Server+Ingestion+MySQL+OpenSearch+migrate，可接 Airflow）；**Helm**（on-prem/EKS/AKS/GKE，DB/ES 可外置）；裸机 |
| 资源 | 官方给出最小与生产就绪要求，见 [Minimum](https://docs.open-metadata.org/v2.0.x/deployment/minimum-requirements)、[Production-Ready](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/deployment/production-ready-requirements) |
| 多租户 | **单实例单租户**，逻辑隔离靠 Domain/Data Product/Team+策略作用域（**是否有多租户模式待核实**） |
| 认证 | Basic、LDAP、Google/Azure/Okta/Auth0/Cognito、SAML、OIDC；采集用 **Bot Token** |
| 授权 | **Policy（规则集）→Role（策略组合）→Team/User**；Rule=资源×操作×allow-deny×条件；支持条件化策略（仅 Owner 可改、按 Tag 约束）、层级继承（[Authorization](https://docs.open-metadata.org/v2.0.x/how-to-guides/admin-guide/roles-policies/authorization)、[Policy 规范](https://openmetadatastandards.org/governance/policy/)） |
| 粒度 | 实体级/属性级/操作级（ViewBasic、ViewQueries、EditDescription、EditTags、EditOwners、Delete…），可限定到 Tag 与 Domain |

## 4. 优点与局限

### 4.1 值得借鉴的所长

1. **Schema-First 单一事实源**：一套 Schema 生成 Java/TS/Python 类型+OpenAPI，根除"API/SDK/UI 不一致"。（最强借鉴点）
2. **采集解耦为独立 workflow**：多类型、多频率、可重试、可采样，适配大规模异构环境。
3. **治理闭环而非只读目录**：Feed+Task+Announcement+Workflow 把"人"纳入流程，直接提升描述/打标覆盖率（进而提升 KPI），避免"上线即荒废"。
4. **列级血缘开箱即用**：不改造 ETL，靠查询日志+dbt+BI 即可获得较高覆盖。
5. **数据契约+治理工作流**较早进入开源核心，是"治理可执行化"的良范。
6. **无图数据库**：核心仅"元数据库+搜索引擎+Java 服务+Python 采集"，运维门槛明显低于 DataHub 一类方案。
7. **元数据规范独立开源运营**，利于生态与互操作。

### 4.2 局限与痛点

| 痛点 | 表现 | 证据 |
|---|---|---|
| **强依赖 ES/OpenSearch** | 部署硬门槛与资源大户；索引与库不一致须全量 reindex | 官方部署要求；reindex 应用的存在 |
| **升级/版本对齐成本高** | 月更+破坏性变更+DB 迁移+**采集端与服务端版本须匹配** | [Breaking Changes](https://openmetadatastandards.org/breaking-changes/) |
| **血缘准确性受解析器限制** | 多层 CTE、动态 SQL、存储过程、方言差异导致血缘缺失且**静默失败** | Issue [#14176](https://github.com/open-metadata/OpenMetadata/issues/14176)、[第三方分析](https://www.dpriver.com/blog/openmetadata-mssql-stored-procedures-why-your-lineage-is-silently-empty-and-how/) |
| **大对象性能** | 超大嵌套 Schema（Kafka Topic）致 UI 卡顿 | Issue [#22362](https://github.com/open-metadata/OpenMetadata/issues/22362) |
| **学习曲线陡** | 概念多（Service/IngestionPipeline/Application/Workflow/Policy-Rule/Persona），YAML/JSON 冗长 | 文档体量本身；[第三方对比](https://fastero.com/blog/datahub-vs-openmetadata-open-source-data-catalogs) |
| **自动分类召回有限** | 列名正则+采样，跨语言/拼接字段易漏，仍需人工复核 | 官方分类最佳实践强调人工校验 |
| **open-core 差集** | 部分高级能力在 Collate 商业版 | **边界待核实**（对照 docs.getcollate.io） |
| **深度血缘/图分析弱** | 关系表+应用层遍历，超深链路与全图影响面分析受限 | 架构推导（§3.3） |

## 5. 关键设计决策与取舍

| 决策 | 收益 | 代价/风险 |
|---|---|---|
| JSON Schema 为唯一事实源 | 跨端强一致、API 即规范、易生成 SDK | 变更需代码生成，破坏性变更贵；配置冗长、贡献门槛高 |
| 不上图数据库，用 RDBMS 关系表 | 组件少、事务一致、复用既有 DB 运维 | 深链/图算法弱，遍历成性能与复杂度热点 |
| 采集端独立 Python 进程（拉模式） | 可连任意源、故障隔离、可扩展、可编排 | 双运行时；版本必须对齐；调试链路长 |
| 血缘靠解析+集成而非埋点 | 不改用户 ETL，落地快覆盖广 | 解析器能力=质量天花板；失败静默；方言维护贵 |
| 目录+协作+治理+观测一体化 | 粘性高、治理可落地、KPI 可量化 | 面广、迭代压力大、前端复杂 |
| open-core（Collate） | 商业可持续，核心保持活跃 | 功能差集引发社区摩擦 |
| 自研标准+兼容 OpenLineage | 全栈自洽，借用外部生态补血缘入口 | 自有标准需生态说服力，双模型映射有维护成本 |
| 月更+大版本破坏性变更 | 迭代极快，紧跟 AI 浪潮（2.0 转 Context Layer） | 企业升级负担重，用户倾向锁定旧版 |

## 6. 仓库结构与关键目录

依据官方 [Code Layout](https://docs.open-metadata.org/v2.0.x/developers/architecture/code-layout) 与仓库内 [CLAUDE.md](https://github.com/open-metadata/OpenMetadata/blob/main/CLAUDE.md)（面向 AI 助手的仓库导览，本身值得借鉴）：

```
OpenMetadata/
├── openmetadata-spec/            # ★ JSON Schema 单一事实源
│   └── src/main/resources/json/schema/{entity,type,api,events}/
├── openmetadata-service/         # ★ Java/Dropwizard 后端
│   └── src/main/java/org/openmetadata/
│       ├── service/              # JAX-RS REST 端点
│       ├── service/jdbi3/        # Repository / DAO（CollectionDAO + 各实体 DAO）
│       ├── service/search/       # ES/OpenSearch 索引与查询构建
│       ├── service/workflow/     # 治理工作流引擎
│       ├── service/apps/         # 插件应用（DataInsights/AutoClassification…）
│       ├── auth/                 # 认证授权（Policy/Role/JWT）
│       └── resources/json/data/  # 内置分类、角色、策略、工作流、测试定义
├── openmetadata-ui/              # ★ React + TS + Ant Design 前端
├── openmetadata-mcp/             # ★ 2.0 新增 MCP Server（Java）
├── openmetadata-clients/         # Java Client / SDK
├── openmetadata-dist/            # 打包发行（Docker/tar）
├── ingestion/                    # ★ Python 采集框架
│   └── src/metadata/{ingestion/{source,sink,processor,stage},workflow,
│                     profiler,parsers,great_expectations,cli,generated}
├── openmetadata-docs/            # 官方文档内容源
├── bootstrap/  docker/  conf/    # 沙箱数据、Compose 编排、openmetadata.yaml
└── .github/                      # CI 与发布
```

**相关独立仓库**：`openmetadata-helm-charts`、`openmetadata-sqllineage`、`openmetadata-airflow-managed-apis`、`openmetadata-collate`（商业插件）。第三方社区另有 MCP 实现（如 [us-all/openmetadata-mcp-server](https://github.com/us-all/openmetadata-mcp-server)，宣称 156 工具，**非官方**）。

## 7. 结论与建议

**可复制的方法论**：Schema-First 模型→代码生成→全端对齐；采集独立进程+多 workflow 解耦+外部编排。
**值得抄的具体机制**：关系表表达血缘+应用层有界遍历；`fields` 按需展开的 API；Task/Announcement/Workflow 治理闭环；用覆盖率 KPI 反向驱动治理；ChangeEvent→Webhook 事件外发；自定义属性做轻量扩展；契约绑定质量断言。
**需绕开的坑**：把搜索引擎做成硬依赖；血缘解析失败静默；高频破坏性变更而无 LTS；单租户导致集团多业务线无法共享实例；血缘覆盖率不透明致用户失信。
**待核实清单**：实时 star/贡献者数、连接器精确数量与 Doris/StarRocks 成熟度、1.13→2.0 破坏性变更明细、官方 MCP 工具清单、语义搜索向量实现（ES kNN vs 外部 embedding）、多租户与 open-core 边界、血缘 API 最大跳数、Webpack→Vite 迁移版本。

### 参考来源

- 官方文档：<https://docs.open-metadata.org/>（[System Architecture](https://docs.open-metadata.org/v2.0.x/developers/architecture)、[Code Layout](https://docs.open-metadata.org/v2.0.x/developers/architecture/code-layout)、[Ingestion 深潜](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/developers/contribute/codebase-deep-dives/metadata-ingestion)、[Lineage 深潜](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/developers/contribute/codebase-deep-dives/lineage-ingestion)、[Connectors](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/connectors)、[Lineage Workflow](https://docs.open-metadata.org/v2.0.x/connectors/ingestion/workflows/lineage)、[Data Contracts](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-contracts/create)、[Authorization](https://docs.open-metadata.org/v2.0.x/how-to-guides/admin-guide/roles-policies/authorization)、[Webhooks](https://docs.open-metadata.org/v2.0.x/connectors/ingestion/versioning/event-notification-via-webhooks)、[Semantic Search](https://docs.open-metadata.org/v2.0.x/deployment/semantic-search)、[MCP](https://docs.open-metadata.org/v2.1.x-SNAPSHOT/how-to-guides/mcp/semantic-search)、[2.0 Release](https://docs.open-metadata.org/v2.0.x/releases/2.0-release)）
- 规范站：<https://openmetadatastandards.org/>（[Core Concepts](https://openmetadatastandards.org/core-concepts/overview/)、[Metadata Specifications](https://openmetadatastandards.org/metadata-specifications/overview/)、[Change Event](https://openmetadatastandards.org/events/change-event/)、[Webhook](https://openmetadatastandards.org/operations/webhook/)、[Policy](https://openmetadatastandards.org/governance/policy/)、[Tag](https://openmetadatastandards.org/governance/tag/)、[Breaking Changes](https://openmetadatastandards.org/breaking-changes/)）
- GitHub：<https://github.com/open-metadata/OpenMetadata>（[CLAUDE.md](https://github.com/open-metadata/OpenMetadata/blob/main/CLAUDE.md)、[workflow/README.md](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/workflow/README.md)、[#14176](https://github.com/open-metadata/OpenMetadata/issues/14176)、[#22362](https://github.com/open-metadata/OpenMetadata/issues/22362)、[sqllineage](https://github.com/open-metadata/openmetadata-sqllineage)）
- 第三方（现象佐证）：[DeepWiki 架构](https://deepwiki.com/open-metadata/OpenMetadata/1.1-system-architecture)、[MSSQL 血缘缺失](https://www.dpriver.com/blog/openmetadata-mssql-stored-procedures-why-your-lineage-is-silently-empty-and-how/)、[DataHub vs OpenMetadata](https://fastero.com/blog/datahub-vs-openmetadata-open-source-data-catalogs)、[Atlan 限制综述](https://atlan.com/know/ai-agent/data-for-ai/what-is-openmetadata-used-for/)
