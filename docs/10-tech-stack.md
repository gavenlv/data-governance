# 10 · 技术选型与工程策略

> 状态：Draft v0.1
> 选型原则：**真相源用最可靠的、派生视图用最合适的、轮子用最成熟的、自研只留给差异化**。

---

## 1. 选型总表

| 层 | 选型 | 备选 | 理由（与风险） |
|---|---|---|---|
| 控制面语言 | **Java 21 + Spring Boot 3**（或 Kotlin） | Go、Rust、Python | 生态最顺：Kafka、OpenSearch、JDBC、代码生成、OpenMetadata/DataHub 的连接器与模型代码可直接参考移植；JVM 团队招人容易。风险：内存占用、启动慢（用 GraalVM/AppCDS 缓解，不必强求） |
| 采集器/Agent | **Go**（单一静态二进制） | Java、Python | 部署在客户数据侧，要求零依赖、低占用、跨平台（Linux/ARM/Windows）；Go 是唯一无争议选择 |
| 血缘 SQL 解析 | **Python + sqlglot 主解析 + Apache Calcite 对核心数仓层（DWD/DWS）二次校验**（独立"解析服务"） | JSqlParser（注意 LGPL）、自研 ANTLR | sqlglot 方言覆盖（20+ 方言，含 Hive/Spark/Trino/Doris/StarRocks）与列级血缘能力最强；**加 Calcite 是为了校验器能力**——它能发现纯解析器静默通过的错配（`SELECT * FROM a JOIN b` 同名列歧义、隐式类型转换），这是 `research/04` §2.1 的明确选型结论（**原方案曾遗漏此项，见 `19` 评审事实项 2**）。风险：双解析器维护成本 → 只对 top-N 核心表启用；无 Java 能力时退化为"sqlglot + 业务规则 + 运行时日志校准" |
| AI / ML 侧车 | **Python**（FastAPI） | JVM | LLM 生态、embedding、异常检测库（statsmodels/scikit-learn）都在 Python |
| 元数据真相源 | **PostgreSQL 16** | MySQL、CockroachDB | JSONB（Aspect 天然合适）、递归 CTE（血缘多跳）、成熟备份与只读副本；**单库需按 `07` §3.2 的 v1 适用范围（500 万实体 / 5000 万边）验证**，超出则引入派生图库 |/亿级边（配合分区与物化） |
| 事件总线 | **Kafka / Redpanda**（生产）· PostgreSQL outbox（单机/轻量） | Pulsar、NATS、RabbitMQ | 事件是派生视图的源头，需要可重放、可多消费者、持久。轻量形态用 outbox + 表驱动消费者，避免小团队被 Kafka 拖死 |
| 检索引擎 | **OpenSearch**（或 Elasticsearch） | Meilisearch、Typesense、PG 全文 | 需要：中文分词（IK）、聚合、字段级权限过滤、向量检索（kNN）、成熟运维。风险：资源占用 → 提供"轻量模式"（PG + pg_trgm + pgvector 起步） |
| 向量检索 | **OpenSearch kNN**（v1）→ 专用向量库按需 | pgvector、Milvus、Qdrant | 元数据量级（百万级 chunk）用 OpenSearch kNN 足够；千万级以上再拆 |
| 图存储 | **v1：PostgreSQL 边表 + 递归查询 + 物化闭包**；v2 可选派生图库（NebulaGraph/HugeGraph/JanusGraph） | 一开始就上 Neo4j | 见 `07` §3.2：图库是运维负担，且血缘 90% 查询是 1–3 跳。图库只做派生视图，可丢弃可重建 |
| 缓存/队列（轻） | **Redis** | - | 会话、限流、热键缓存、轻任务队列 |
| 对象存储 | **S3 兼容**（S3/OSS/MinIO） | - | 采集快照、导入导出文件、剖析样本（短期）、备份、向量模型产物 |
| 授权引擎 | **v1 平台自持 RBAC + ABAC + 资源层级继承**；细粒度关系授权按需引入 **OpenFGA** | SpiceDB、Casbin、Ory Keto | 依据 `research/04` §3.3：元数据天然层级，继承式判定即可覆盖多数场景；一步到位上 Zanzibar 式 ReBAC 的一致性/缓存失效/权限物化复杂度会超过目录本身。风险：自建判定需严格越权测试 |
| 工作流 | **自研轻量状态机**（审批链 / SLA / 超时升级） | ~~Temporal/Camunda~~ **不采用**，除非出现"跨系统长流程编排"需求（见 §7 反选型） | 审批链与工单流转用状态机即够（`07` §7、`17` §2 同结论）；把 BPMN 引擎列进选型表只会反复引发无意义讨论（`19` 评审过度设计项 4） |
| 调度 | **内置调度**（Quartz/自有 cron + 分布式锁）+ 对外暴露 API 供 Airflow/Argo 触发 | 依赖 Airflow 调度采集 | 治理平台不该要求客户先装 Airflow；同时提供"外部调度"模式给已有调度体系 |
| 身份认证 | **OIDC/OAuth2**（Keycloak/Authing/Azure AD/企业 SSO）+ SCIM 同步 | 自建账号 | 企业必须 SSO；SCIM 同步组织与人员（Owner 归属依赖它） |
| 密钥管理 | **Vault / KMS**（云 KMS、HashiCorp Vault、K8s Secret 起步） | 平台内加密存储 | 采集凭证是最敏感资产，绝不能明文落库 |
| 前端 | **React 18 + TypeScript + Vite** + TanStack Query + 设计系统（Ant Design 5 或 shadcn/ui） | Vue | React 生态对图谱/富交互组件更全；AntD 适合中后台密集型界面 |
| 图谱可视化 | **Cytoscape.js** 或 **AntV G6**（WebGL） | D3 自绘、vis.js | 血缘图需要布局算法、千级节点渲染、交互（展开/聚焦/路径高亮）；自绘 D3 在这个场景是浪费 |
| 富文本/文档 | TipTap / ProseMirror | Markdown only | 资产文档需要表格/图片/内嵌链接 |
| 可观测性 | **OpenTelemetry** + Prometheus + Grafana + Loki | 商业 APM | 平台自身可观测必须做（治理平台不可用 = 信任崩塌） |
| 部署编排 | **Docker Compose（单机） / Helm（K8s）** | 纯二进制、Nomad | 企业两种诉求：小团队要一条命令起、大企业要 K8s 与 GitOps |
| 测试 | JUnit5 + Testcontainers（集成）+ Playwright（E2E）+ **契约测试（消费者驱动）** | - | 平台对外是"被依赖的基础设施"，接口兼容性必须用契约测试锁住 |
| 构建与依赖 | Gradle（多模块）+ pnpm（前端）+ 内部制品库 | Maven | 多模块编译与增量构建更好 |

---

## 2. 为什么是"Java 控制面 + Python 侧车 + Go Agent"

三种语言听起来是负担，但每个选择都有不可替代的理由，且边界清晰：

```
┌───────────────────────── Java / Kotlin 控制面 ─────────────────────────┐
│ Metadata Core · API · 权限 · 目录 · 契约 · 质量调度 · 运营 · Web 后端     │
└──────┬───────────────────────┬──────────────────────┬─────────────────┘
       │ 内部 gRPC/HTTP        │ 内部 gRPC/HTTP        │ 标准 OTLP/OpenLineage
┌──────▼──────────┐   ┌────────▼─────────┐   ┌────────▼──────────────────┐
│ Python 侧车      │   │ Python 侧车       │   │ Go Agent（数据侧）          │
│ 血缘 SQL 解析    │   │ AI(LLM/embedding) │   │ 采集、推送、本地缓存、代理   │
│ 剖析与异常检测    │   │ 向量化、建议引擎   │   │ 零依赖、低占用、跨平台       │
└─────────────────┘   └──────────────────┘   └───────────────────────────┘
```
纪律要求（否则三语言就是灾难）：
- 侧车**无状态**、可水平扩展、协议用 IDL（protobuf）定义并生成客户端；
- 侧车的**业务规则不重复实现**（解析/AI 只做算法，判定与持久化回控制面）；
- 用**统一的 CI 流水线与制品规范**管理三种技术栈，禁止"第二套基础设施"。

若团队只允许一种语言：**Java 单栈**可行（血缘用 Calcite 或嵌入 Python 子进程），但血缘方言覆盖会明显变差——这是要提前接受的代价。

---

## 3. 存储层细节与容量规划

### 3.1 PostgreSQL 表设计要点
| 表 | 说明 | 关键索引/分区 |
|---|---|---|
| `entity` | urn, type, namespace, tenant, created/updated, deleted_at, lifecycle | PK(urn)，索引(type, namespace)，部分索引 `where deleted_at is null` |
| `aspect` | urn, aspect_type, version, data(jsonb), source, updated_by, updated_at | PK(urn, aspect_type)，GIN(jsonb_path_ops) 按需；历史表 `aspect_history` 按时间分区 |
| `edge` | from_urn, to_urn, edge_type, source, confidence, state, first_seen, last_seen, via_job | 索引(from_urn, edge_type)、(to_urn, edge_type)、部分索引 `where state='ACTIVE'` |
| `lineage_closure_mv` | 物化 1–3 跳可达对（定期刷新 / 增量维护） | 支撑影响分析的常用查询 |
| `event_log` | append-only 变更日志（或直接 Kafka） | 按时间分区 + 保留策略 |
| `run` / `task` | 采集、质量、审批运行记录 | 按时间分区 |
| `audit_log` | 审计 | 按月分区，只追加，独立只读账号 |

规模估算（供容量设计，假设中型企业）：
- 资产：10 万 Dataset、200 万 Column、5 千 Dashboard、2 万 Pipeline → 实体约 220 万；
- 血缘边：每 Dataset 平均 5 条表级 + 列级展开 20 条 → 量级 10⁶–10⁷；
- Aspect 存储：JSONB 平均 2 KB × 220 万 × 平均 4 aspect ≈ 18 GB（未压缩）；PostgreSQL TOAST + 压缩后约 5–8 GB，单库完全可承载。
- 结论：**v1 不需要分布式存储**。过早引入图数据库/分库分表是纯粹的复杂度浪费。

### 3.2 数据保留与成本
- 采集快照（原始元数据）保留 30 天；剖析样本不留存或加密保留 7 天（可配）。
- 审计日志保留 ≥ 1 年（合规）；事件日志保留 30–90 天（可重放窗口）。
- 向量：只对"描述/文档/术语"做向量化（不做全量列），控制成本。

---

## 4. 安全设计

| 面 | 措施 |
|---|---|
| 传输 | 全链路 TLS；内部 mTLS（K8s 可选） |
| 静态加密 | 数据库透明加密 + 敏感列（凭证引用、AI 证据片段）应用层加密 |
| 凭证 | Vault/KMS 引用，绝不明文；采集账号最小权限 DDL 模板 |
| 认证 | OIDC + MFA 继承企业策略；服务账号用 OAuth2 client credentials，可轮换 |
| 授权 | 平台 RBAC/ABAC + 资源层级继承（细粒度关系授权按需引入 OpenFGA）；**API、搜索、AI 上下文裁剪使用同一套授权判定**（避免"UI 看不到、API 能拿到"的经典漏洞） |
| 输入安全 | SQL 相关的一切（质量规则、剖析、策略编译）必须参数化/白名单，禁止拼接受用户输入的 SQL |
| 输出脱敏 | 搜索片段、AI 回答、导出文件中的敏感采样值默认脱敏；采样数据永不返回原始行（只返回统计与格式样例） |
| AI 安全 | 发送给 LLM 的上下文做字段级过滤（按用户权限裁剪）、PII 剔除/替换、prompt 注入防护（元数据内容视为不可信输入）、审计每次 LLM 调用 |
| 供应链 | 依赖扫描（SCA）、镜像签名（cosign）、SBOM 生成、许可证合规扫描（**特别针对从 Apache-2.0 项目移植的代码**）。**已知高风险项**：Soda Core 自 2026-01 起为 ELv2（非开源许可）；Unity Catalog OSS 的许可无法确认（`research/05`）→ 两者均不得"想当然"引入。**完整要求见 `docs/23` NFR-SEC-01**：无已知 CRITICAL/HIGH、MEDIUM 限时修复、优先维护活跃的 FOSS、例外需登记到期日；已落地工具 `tools/dependency_audit.py`（OSV 扫描 + 反向自检 + 三态退出码） |
| 审计 | 所有写操作、所有数据访问申请与策略下发、所有导出，全部留痕且防篡改（append-only + 哈希链） |

---

## 5. 测试与质量策略

| 层级 | 手段 | 重点 |
|---|---|---|
| 单元 | JUnit / pytest / go test | 血缘解析（**用真实 SQL 语料库做黄金用例**）、置信度融合、策略编译、健康分 |
| 集成 | Testcontainers（PG/OpenSearch/Kafka） | 采集端到端、事件重放、索引重建、权限判定 |
| 契约 | 消费者驱动契约测试 + 模型兼容性 CI | 模型与 API 变更不得破坏老客户端 |
| 性能 | 基准数据集 = **v1 适用范围（500 万实体 / 5000 万边，见 `07` §3.2）+ 2 倍压力档** | 3 跳血缘查询、搜索 P95、批量采集吞吐、索引重建时长；**`lineage_closure_mv`（depth≤3）的行数与刷新耗时是硬指标**（`19` 评审 B1：此前只估了 Aspect 容量，漏估闭包表） |
| 混沌 | 杀索引/杀消费者/网络分区 | 派生视图可重建、采集不丢不重、降级可用 |
| 安全 | SAST/DAST、越权测试（横向/纵向） | **重点测"搜索与 API 的权限一致性"**、SSRF（连接器 URL）、SQL 注入（质量规则） |
| 数据质量自检 | 平台自身的对账任务 | 真相源 ↔ 派生视图一致性、血缘断链检测、孤儿实体检测 |
| E2E | Playwright | 核心用户旅程：采集→发现→理解→申请→审批→审计 |

---

## 6. 性能与扩展设计要点

1. **读多写少的优化**：资产详情页做二级缓存（Redis + 本地 Caffeine），缓存键含权限上下文哈希（避免越权缓存污染）。
2. **采集写入批量与背压**：批量提交（500–2000 条/事务）+ 队列背压；采集任务与前台 API **资源隔离**（不同线程池/不同副本），保证大批采集不拖垮前台。
3. **血缘查询分级**：≤3 跳走物化闭包（毫秒级）；>3 跳走异步任务 + 结果缓存（返回 jobId 轮询/SSE 推送）。
4. **索引重建**：支持按实体类型/时间范围部分重建，全量重建 < 2h（并行 consumer + bulk indexing + 分批提交）。
5. **水平扩展点**：API（无状态）、Worker（采集/质量/AI 各自独立队列与扩缩容）、消费者（Kafka 分区数决定并行度）。**PostgreSQL 是唯一纵向扩展点**，用只读副本分流搜索/报表类查询。
6. **多租户**：逻辑隔离（tenant 列 + 行级安全 RLS + 索引过滤）为默认；高隔离要求时"每租户独立 schema/独立实例"可选（数据模型不变，靠部署拓扑解决）。

---

## 7. 反选型（明确不采用，及原因）

| 不采用 | 原因 |
|---|---|
| 一开始就微服务化（10+ 服务） | 治理平台流量特征不匹配；分布式事务与联调成本远超收益 |
| 一开始就引入图数据库作为真相源 | 运维、备份、事务、团队技能都是新负担；用 PG 起步，图库按需派生 |
| 自研全文检索引擎 / 自研 SQL 解析器 | 成熟度差距巨大，属于纯粹的重复发明 |
| 自研 BPMN 工作流引擎 | 复杂度无底洞，用状态机或 Temporal |
| 第一天就上 Zanzibar 式授权引擎（OpenFGA/SpiceDB） | 调研结论（`research/04` §3.3）：一致性、缓存失效与"用户→可见资源"物化的复杂度会超过数据目录本身；分层演进更稳 |
| 自研 BI/查询引擎/调度平台 | 明确 Non-Goal（见 `07` §7） |
| 用 MongoDB/Elasticsearch 当元数据主存 | 需要跨实体事务、时间旅行、强一致与关系查询；搜索引擎当主存是经典陷阱 |
| 端到端"元数据也数据湖化"（存进 Iceberg/湖仓） | 读延迟与更新开销不适合交互式目录（可作为**分析副本**后期可选） |
| 全量列级向量化 / 全量数据采样剖析 | 成本失控且收益低；按需与分层 |
| 与某个开源项目做代码 fork 后长期维护 | 分离度高时 fork 成本极高（升级地狱）；定位为"参考/移植组件"而非"fork 产品" |

---

## 8. 工程实践与交付形态

| 项 | 做法 |
|---|---|
| 仓库结构 | 单仓库多模块（monorepo）：`core` `api` `ingestion` `lineage` `quality` `policy` `ai` `web` `agent` `model`（模型 YAML + 生成代码）`sdk` `deploy` |
| 分支与版本 | 主干开发 + 发布分支；语义化版本；**元数据模型版本与平台版本解耦**（模型兼容性单独承诺） |
| 文档 | 架构决策记录（ADR）、模型参考、连接器开发指南、运维手册（Runbook）、SLO 与容量说明 |
| 发布 | API/模型变更走"废弃公告 → 双写/双读 → 移除"三段式；连接器独立版本化（可单独升级，避免整体升级风险） |
| 升级 | 数据库迁移可回滚（每一步 forward + backward）；大版本升级提供预检脚本与影响报告 |
| 支持 | 采集诊断包导出（脱敏）、健康检查端点、`dgctl doctor` 自检命令 |
| 可交付形态 | 单机版（compose）、标准版（Helm）、联邦版（控制面 + Agent）；同一代码库，配置差异 |
