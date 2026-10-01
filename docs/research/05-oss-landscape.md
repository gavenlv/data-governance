# 开源数据治理生态全景调研（DataHub / OpenMetadata 之外）

> 调研日期：**2026-10-01**　｜　用途：自研数据治理平台的方案设计与选型参考
> 范围：不覆盖 OpenMetadata / DataHub 本体，仅覆盖其衍生项目与其他全部相关项目。
>
> **事实强度声明**：本环境无法直接抓取网页（shell 无出网），所有事实来自 `web_search` 的结果标题与摘要。**精确星标数、部分发布日期、最后提交时间多数量级为估值**，已在文中标注 `未核实`。许可与「已归档/已退役」这类结论性事实，均给出了证据 URL，可直接复核。

---

## 0. 结论速览：2026 年的四个结构性变化

1. **Hadoop 时代的数据治理项目正在批量退场，但并非全线崩塌——需要逐项核实。** Apache Griffin 于 **2025-11-25 正式退役进入 Attic**（[ATTIC-246](https://issues.apache.org/jira/browse/ATTIC-246)、[退役公告](https://www.mail-archive.com/announce%40apache.org/msg10545.html)）；**Amundsen 于 2026 年 9 月因长期不活跃被 GitHub 归档**（[README 横幅](https://raw.githubusercontent.com/amundsen-io/amundsen/main/README.md)）；WhereHows 已死；Apache Atlas 虽未退役，但发布节奏已降至约两年一个 minor 版本（2.5.0，2026 年仍在出安全公告）。
   **但两个常见的「已死」判断是错的**：**Netflix Metacat 仍在提交**（最后 commit 2026-09-15，只是**只发 RC、从不发稳定版**）；**ODD Platform 的公司并未关闭**（官网仍在售 SaaS、2026-06 仍有版本、2026-09 仍有提交）。**教训：活跃度判断必须看仓库实测，不能靠印象。**
2. **湖仓「目录/元数据湖」层正被 ASF 快速接管并标准化。** Apache Gravitino 于 2025-09 发布 **1.0.0** 并**于 2025-06 毕业为 ASF 顶级项目**（最新 1.3.0）（[1.0.0 release notes](https://raw.githubusercontent.com/apache/gravitino-site/refs/heads/main/blog/2025-09-24-gravitino-1-0-0-release-notes.mdx)、[TLP 公告](https://gravitino.apache.org/blog/gravitino-top-level-project/)）；Apache Polaris 于 **2026-02-19 毕业为顶级项目**（最新 1.8.0）（[公告](https://www.globenewswire.com/news-release/2026/02/19/3240735/0/en/Apache-Polaris-Graduates-to-Top-Level-Apache-Project.html)）；语义层标准 OSI 捐给 ASF 成为 **Apache Ossie (Incubating)**（[apache/ossie](https://github.com/apache/ossie)、[Snowflake 博客](https://www.snowflake.com/en/blog/apache-ossie-open-semantic-interchange-incubator/)）。**同时：Casbin 进入 Apache 孵化器、dbt Labs 并入 Fivetran、GX Core 交由 Fivetran 托管——2026 年是开源治理领域的并购与捐赠大年。**
3. **可直接复用的「轮子」高度集中在四层**：元数据目录（Gravitino）、血缘事件标准（OpenLineage + Marquez 领域模型）、SQL 解析与列级血缘（sqlglot）、授权（SpiceDB / OpenFGA）。**而真正的治理语义层——策略的跨引擎下发与执行、契约的运行时强制、异常检测与告警、指标一致性、Agent 上下文治理——几乎没有合格开源实现**，这正是自研的差异化空间。
4. **许可陷阱是 2026 年最大的选型风险。** **Soda Core 自 v4 起改为 Elastic License 2.0**（禁止对外提供托管服务）；**DQOps 为 BSL-1.1 且已停更**；**DataHub 的「查询时策略下推」是 Cloud 独有**；**Unity Catalog OSS 的许可本次调研无法确认**。选型前必须逐项读 `LICENSE`。

---

## 1. Hadoop 时代的元数据与血缘

### 1.1 Apache Atlas —— 模型驱动元数据与血缘的「教科书」

| 项 | 内容 |
|---|---|
| 一句话定位 | ASF 的元数据与治理服务：TypeSystem 驱动的实体图 + 血缘 + 分类标签传播 + 业务术语表 |
| 许可 | Apache-2.0 |
| 活跃度 | **存活但明显放缓，未进 Attic**。最新 **2.5.0**（[镜像目录](http://apache.uvigo.es/atlas/2.5.0/)、[JIRA ATLAS-5231](https://issues.apache.org/jira/browse/ATLAS-5231)）；2.6 分支已开（[ATLAS-5235](https://issues.apache.org/jira/browse/ATLAS-5235)）。**2.5.0 精确发布日期 `未核实`**。发布节奏约 **1 个 minor / 1.5–2.5 年**（方向性判断）。**星标 2,149**（2026-10-01 实测），最后 push 2026-09-29。2026 年仍在出安全公告（[CVE-2026-40563](https://lists.apache.org/thread/3zhg05ckz38crbmx1w6pc4hh62rvfw3p)、[CVE-2026-50622](https://lists.apache.org/thread/6bnp7s4396osml3c0o9opno8f603qd04)），说明有人在维护；但累计约 14 个 CVE 且**未见 2026-07 越权漏洞的修复版本**（`未核实`，选型前务必确认） |
| 核心能力 | TypeSystem（实体/关系/分类/枚举类型）；实体图与关系；表级与列级血缘；classification/trait **沿血缘传播**；业务术语表（Glossary→Category→Term）；REST API v1/v2；Ranger **TagSync** 把 Atlas 分类同步为 Ranger 标签策略 |
| 实现要点 | Java + Spring + Jetty；类型以 JSON 模型文件（`atlas_model`）在启动时载入类型注册表，**关系类型是一等公民**；图存储 **JanusGraph**（后端 BerkeleyDB JE / HBase / Cassandra），索引 **Solr 或 Elasticsearch**；Hook 与 Server 之间用 **Kafka** 解耦（`ATLAS_HOOK` 入 / `ATLAS_ENTITIES` 出）；`addons/` 下为各组件 Hook：Hive、HBase、Sqoop、Storm、Kafka、Falcon、Couchbase——**但 Falcon/Storm/Sqoop 自身已 EOL，实际可用面只剩 Hive + HBase + Kafka** |
| 可复用的部分 | **抄设计（最高价值）**：① 版本化 JSON 的 TypeSystem + 一等关系类型；② Hook/拦截器式血缘发射模式；③ 分类传播的「下游继承只读」语义；④ 术语表本体与 `/lineage?guid=&direction=&depth=` 查询形态。**几乎没有「直接用」的价值**，除非你已经全押 CDP/Cloudera |
| 风险 | 依赖极重（JanusGraph+HBase+Solr+ZooKeeper+Kafka）；与 K8s/云原生范式冲突，无一方 Operator，仅社区 Helm Chart；上游慢、安全补丁滞后；JanusGraph 与 Solr 索引一致性调优是专家活 |

### 1.2 LinkedIn WhereHows —— 已死亡，但失败原因值得读

- **定位**：LinkedIn 第一代元数据 ETL + 发现 + 血缘门户，DataHub 的直系前身。
- **许可**：Apache-2.0。**活跃度：实质已死**——所有搜索结果都指向 fork 而非上游；`未核实`是否带归档横幅，`未核实`最后提交。
- **开创性贡献**：① 把元数据采集做成**调度式 ETL 流水线**（抽取→归一→发布），而非临时抓取；② **从 Hadoop 执行日志反推血缘**，不依赖生产方埋点；③ 统一 dataset 实体（Hive 表、Avro schema、Teradata 表）承载 schema/样例/负责人/合规标签。存储 MySQL + Elasticsearch 索引。
- **可复用**：**仅借鉴**。「无埋点则从执行日志推血缘」这个思路成立；更重要的是它的**失败模式**——过度绑定 LinkedIn 自己的 Hadoop+Azkaban+Teradata 栈，导致无法泛化（参考[《简评 WhereHows 为什么失败》](https://cloud.tencent.cn/developer/article/1921309)）。这正是 LinkedIn 用事件化、可插拔元数据模型重写为 DataHub 的原因。
- **风险**：死项目、无安全补丁、技术栈腐化。

### 1.3 Amundsen（Lyft）—— 2026 年 9 月已归档

| 项 | 内容 |
|---|---|
| 一句话定位 | 「数据界的 Google 搜索」——搜索优先的数据目录 |
| 许可 | Apache-2.0 |
| 活跃度 | **已归档（GitHub 只读）**。README 第 1 行明确写着 *"Due to inactivity, this project was archived in September 2026"*（[证据](https://raw.githubusercontent.com/amundsen-io/amundsen/main/README.md)）——**主仓 `amundsen-io/amundsen` 已确认**。**星标 4,782**。末版：databuilder 7.5.0、frontend 4.1.2、metadata 3.7.0。原为 LF AI & Data 项目 |
| 核心能力 | 表/列搜索、描述与负责人、热度排序、后加的表级血缘 |
| 实现要点 | 四件套：**Frontend**（Flask+React）、**Metadata Service**（Flask+SQLAlchemy，内部分 API/Entity/Proxy 三层）、**Search Service**（Elasticsearch）、**Databuilder**（Python 元数据 ETL 库：`extractor → transformer → publisher/loader` 任务图）。默认图后端 **Neo4j**，但**一方支持切换为 Apache Atlas**（[atlas_proxy.md](https://github.com/amundsen-io/amundsen/blob/2a6ba580/metadata/docs/proxy/atlas_proxy.md)） |
| 可复用的部分 | **抄设计（高价值）**：① `extractor→transformer→publisher/loader` 摄取任务图——任何自研目录都需要这个形状；② **在元数据后端之上加 proxy 抽象**，这是它能从 Neo4j 换到 Atlas 的结构性原因，应从第一天就抄；③ 三服务拆分与「按热度排序」的产品化思路。**不要直接用**（已归档） |
| 风险 | 上游已归档，Python/ES/Neo4j 版本兼容无人修复；两个有状态依赖（Neo4j + ES）对目录而言过重 |

### 1.4 Marquez —— OpenLineage 参考实现（血缘专用项目中最值得依赖的一个）

| 项 | 内容 |
|---|---|
| 一句话定位 | OpenLineage 的参考实现：接收 OpenLineage 事件、聚合为血缘图、提供 REST API 与 Web UI |
| 许可 | Apache-2.0（仓库内 SPDX 头可验） |
| 活跃度 | **代码活跃但发版停滞**。**星标 2,284**，最后 commit **2026-09-27**，但最新 release 仍是 **0.51.1（2025-03-27）**——代码在动、版本没发，说明项目重心已转移。**LF AI & Data 于 2024-02 毕业**（[公告](https://lfaidata.foundation/blog/2024/02/07/lf-ai-data-foundation-announces-graduation-of-marquez-project/)）。创始团队 Datakin 被 **Astronomer 收购**，核心维护者集中在一家公司 |
| 核心能力 | OpenLineage 事件摄取；namespace/job/dataset/run 图；run 状态机；**DatasetVersion**（schema facet 变化即新版本）；**列级血缘**（[Column Lineage Demo](https://marquezproject.ai/blog/column-lineage-demo/)）；标签/归属 facet；REST + React UI |
| 实现要点 | Java + **Dropwizard**（Jetty+Jersey）；**PostgreSQL** 为主存（含迁移脚本），**OpenSearch/ES 为可选**搜索加速（不装则退化为 DB 查询——这个「主存 + 可选索引」的分层很值得抄）。数据模型 `Namespace → Job/Dataset → JobVersion/DatasetVersion → Run`，Run 携带 nominal/actual 起止时间与 facets。API：`POST /api/v1/lineage` 摄取，`GET /api/v1/lineage?nodeId=dataset:...&depth=N` 展开 |
| 可复用的部分 | **抄设计（本报告最高价值项之一）**：**OpenLineage 事件契约本身**。因为它是开放规范且有 Airflow/Spark/dbt/Flink 等 Emitter，你只要让自己的血缘库「消费 OpenLineage 事件」，就自动兼容整个生产者生态。领域模型（版本化、run 状态、facets）与 `nodeId+depth` 图查询形态可直接照搬。若接受 Java+Postgres，**也可直接用**（Docker Compose 起） |
| 风险 | 强依赖生产者生态的 Emitter 覆盖，未覆盖的引擎就是血缘空洞；单主 Postgres，无 HA 方案（`未核实`）；多年仍在 0.x，API 未冻结；维护者集中一家公司；UI 偏功能化，生产需自建前端 |

### 1.5 Apache Griffin —— 已于 2025-11 退役（简要）

- **定位**：ASF 的 Hadoop/Spark 数据质量服务：定义 measure、提交 Spark 作业执行、指标落 Elasticsearch 并出 dashboard。
- **许可**：Apache-2.0。**活跃度：已退役进 Attic**（[ATTIC-246](https://issues.apache.org/jira/browse/ATTIC-246) 2025-09-26 创建、**2025-11-25 解决**；[退役公告](https://www.mail-archive.com/announce%40apache.org/msg10545.html)；[projects.apache.org 条目](https://projects.apache.org/project.html?attic-griffin)）。末版 **0.6.0**，代码实质停在 2018–2021。
- **实现要点**：Scala + Spark 2.x；批（Hive/文件）与流（Spark Streaming）双模式；JSON **measure DSL**（accuracy/completeness/profiling/custom SQL）；经 **Livy** 提交作业；指标入 **Elasticsearch**。
- **为什么停摆（推断）**：栈耦合过重（Spark2+Hive+Livy+ES 只为跑个质量检查）；自制 JSON DSL 完败于 SQL 原生/dbt tests/期望式框架；定位被「仓库内测试 + 数据可观测性产品」夹杀；贡献者基数不足，跟不上 Spark 3.x 与云数仓。
- **可复用**：**仅借鉴**——①「质量规则定义」与「执行作业」分离、规则是可移植的声明式数据；② 批流统一规则模型；③ 指标写入可检索索引而非只写日志。
- **风险与一个坑**：**DolphinScheduler 的 Data Quality 任务插件派生自 Griffin 的质量引擎**（`dolphinscheduler-task-plugin/dolphinscheduler-task-dataquality`，包结构 `plugin/task/dq/rule/parameter` 与 Griffin measure 配置一致）。上游已退役、下游仍在维护自己那份 fork —— 这是一笔需要注意的维护负债。

### 1.6 Apache DolphinScheduler —— 健康的「调度面」（简要）

- **定位**：分布式、GUI 优先、多租户的 DAG 工作流调度器。**ASF 顶级项目（2021-04）**。
- **活跃度**：**非常活跃**，本组唯一健康者。**3.3.2（2025-11-05）→ 3.4.0（约 2026-01-22，引入 OIDC、gRPC 任务、K8s 部署）**（[3.4.0 归档目录](https://archive.apache.org/dist/dolphinscheduler/3.4.0/)、[WhaleOps](https://www.whaleops.com/846839-846849_3852651.html)）；社区发布月度更新。星标 **≈14.5k**（第三方站，估值）。
- **治理相关能力**：Java + Spring Boot + MyBatis-Plus + Quartz + ZooKeeper + Netty；元数据在 MySQL/PostgreSQL；**具名数据源注册中心**（覆盖 MySQL/PG/Hive/Impala/Spark/ClickHouse/Oracle/Doris/Presto-Trino 等）；任务插件含 SQL/Shell/Python/**Spark**/**Flink**/DataX/Sqoop/HTTP/**gRPC**；**Data Quality 任务组件**。
- **它不提供什么（关键）**：**没有数据目录**（无 dataset/table/column 实体）、**没有数据血缘**（只有任务依赖 DAG，无 dataset/列级血缘、不消费 OpenLineage）、**没有业务术语表**、**没有分类标签及传播**、**没有数据级策略引擎**（只有对自身资源的 RBAC）、**没有质量指标仓库**（结果只是任务日志）、**没有跨系统元数据联邦**。
- **可复用**：**直接用**作为调度/编排面；**抄设计**——任务插件 SPI 抽象（可映射为你的「可插拔治理连接器」）、具名数据源注册中心。**不要继承它的 DQ 组件**（源自退役的 Griffin，建议重写）。

---

## 2. 元数据目录（DataHub 之外）

| 项目 | 定位 | 许可 | 活跃度（2026-10-01 实测） | 核心能力 / 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **Netflix Metacat** | 联邦式元数据服务，把 Hive/MySQL/Redshift/S3/Teradata/Snowflake 等异构系统的元数据统一成一套探索 API | Apache-2.0 | **缓慢但在提交**：**1,692 star**；最后 commit 2026-09-15；**但只发 RC——最新 v1.3.3-rc.10（2026-09-15）**，无稳定版；README 陈旧（仍提 Bintray/Travis，"Documentation: TODO"） | Java/Gradle WAR；**Elasticsearch** 做检索，MySQL/Postgres/Aurora 存元数据；核心是 **Connector SPI**（Hive/RDS/Snowflake/Polaris-Iceberg…）；曾用 Thrift，现为 REST；支持任意自定义元数据与 tag/keyword | **抄设计**：connector SPI 与「统一 API 收口异构 catalog」正是 Gravitino 在做的事，值得对照 | **只发 RC、文档陈旧、无治理能力**（无血缘、无分类、无策略、无细粒度 ACL）；设计中心在 Netflix 内部形态 |
| **Magda（CSIRO）** | 联邦式开放数据目录，为内部/外部、文件/库/API 提供统一可搜索视图；支撑 **data.gov.au** | Apache-2.0 | **小而活跃**：**607 star**；最后 commit 2026-09-30；**v7.0.0-alpha.5（2026-09-30）**，7.0 仍是 alpha；README 自陈 *"under active development by a small team"*、作为通用目录 *"expect some rough edges"*；**389 个 open issue** | TypeScript/Node 微服务，K8s + Helm；**OpenSearch** + PostgreSQL + S3/MinIO；dataset/source/distribution 模型；爬取与变更追踪；**RBAC 用 OPA/Rego 实现** | **抄设计（较高价值）**：① 联邦目录聚合模型；② **用 OPA/Rego 做产品内 RBAC 的完整先例**；③ DCAT/CSW 采集 | 团队极小、issue 积压、主版本仍是 alpha、文档滞后；**无列级语义、无 Zanzibar 式继承**；偏开放数据门户而非企业内治理 |
| **CKAN** | 全球最主流的**开放数据门户**平台 | **AGPL-3.0-or-later**（[LICENSE.txt 实测](https://raw.githubusercontent.com/ckan/ckan/master/LICENSE.txt)；GitHub API 因文件特殊报 `NOASSERTION`） | **活跃**：**5,126 star**；最后 commit 2026-10-01；末版 **ckan-2.12.0（2026-08-26）**（同日另发 2.11.6 / 2.10.11）；有 3.0 路线图；**859 个 open issue** | Python（2.11 起迁移到 Flask）+ PostgreSQL + **Solr** + Redis；dataset/resource/organization/group 模型；Harvest 联邦采集（CSW/DCAT）；Datastore/DataPusher；`ckanext-*` 生态庞大；插件接口含 **`IAuthFunctions`** | **可直接用（若做开放数据门户）/ 仅借鉴**：**DCAT 元数据模型**与 Harvest 联邦采集值得抄 | **AGPL 传染性对闭源集成是硬约束**；Python 2 时代扩展债务；859 个 issue；package/resource 模型粒度粗，不做表级血缘与列级权限 |
| **ODD Platform（Open Data Discovery）** | 以**数据实体（Data Entity）**为一等公民的数据发现 + 可观测性目录（含微服务血缘、DQ、术语表） | **Apache-2.0**（LICENSE.md 实测） | **⚠️ 修正先入之见：公司与项目均未关闭。** **1,434 star / 151 fork / 30 贡献者**，未归档；版本 **0.29.0（2026-06-26）**、0.28.0（2026-06-17）；最后 commit **2026-09-21**；官网仍在卖 SaaS 试用。**但存在「商业投入减弱」的信号**：兄弟项目 `odd-collectors` 最后更新 **2026-01-05**、代码仍在厂商命名空间 `com.provectus.oddplatform`、近期提交由单一 `odd-contributor` 账号完成、仓库无 `MAINTAINERS.md`/`GOVERNANCE.md` | Java（Spring Boot）+ **PostgreSQL**；独立 **enricher** 微服务；React UI + Helm；Python **collectors** 以 **ODD Spec** 推送元数据 | **抄设计**：collectors + spec + enricher 的三段式采集架构很干净 | **维护与厂商背书不明确**（主要风险）；贡献者基数小；内置 RBAC 粒度粗；文档偏薄 |
| **Apache Egeria**（补充） | ODPi 出身的「开放元数据与治理」框架：OMAG 服务 + **Open Metadata Types** 类型系统 + Cohort 联邦 | Apache-2.0 | **低频但活跃**：**925 star**；最后 push 2026-10-01；版本 **V6.1（2026-08-19）** | Java；Cohort + Open Metadata Repository Services 联邦式元数据交换；**业务血缘 / Lineage Warehouse / Lineage Integrator OMIS / Lineage Explorer**；完整 **OpenLineage 摄取**（Event Receiver + Log Store + Lovelace 服务 + 内容包）。Egeria 把 OL 定位为「运行期/动态血缘」，自身提供「设计期/静态血缘」 | **可直接用（作为受治理的元数据骨干）**；**抄设计**：**Open Metadata Types 类型系统与 Cohort 联邦协议**是「多系统元数据互操作」最完整的开源设计参考；血缘的「设计期 vs 运行期」二分法很值得抄 | 抽象层极厚、上手极陡；生态小；血缘采集仍需 OL/SQL 生产者提供 |
| **Grai**（补充） | 「预部署」式元数据/血缘平台 | **MIT-0** | **低活跃**：317 star；最后 push **2026-01-30** | Python | **仅借鉴** | 活跃度不足 |
| **DataHub 衍生项目** | ① **DataHub（原 Acryl Data）**：经典 **open-core**，DataHub Core 为 Apache-2.0、DataHub Cloud 为托管/企业版（[官方 Core vs Cloud 对照](https://raw.githubusercontent.com/datahub-project/datahub/master/docs/managed-datahub/managed-datahub-overview.md)），Acryl 2025-05 完成 **$35M B 轮**；② **部署发行版**：`acryldata/datahub-helm`（204★，2026-09-30）、`datahub-cloudformation`、`datahub-terraform-modules`；③ **Agent 封装**：`acryldata/mcp-server-datahub`（80★，2026-09-28）——官方 MCP server，把元数据图暴露给 LLM Agent；④ **已停更**：`acryldata/datahub-actions`（50★，最后 push 2025-05-02，框架已并入主仓）；⑤ **历史授权集成**：`acryldata/datahub-ranger-auth-plugin`（**Apache Ranger 作为 DataHub 授权器**，最后 push **2022-12-28**——「标签策略落到 DataHub」最有参考价值的先例） | 核心 Apache-2.0；Cloud 商业 | DataHub 本体活跃：**12,785 star**、v1.6.0.3（2026-09-25）、最后 commit 2026-09-30 | 衍生项目本身多为部署包装/Agent 封装，技术增量有限。**Fork 生态很薄**，只有个人小 fork。**命名陷阱**：`datopian/metastore`（"DataHub data management system"）是 Datopian 自己的 datahub.io 产品，**与 LinkedIn 的 DataHub 无关** | **仅借鉴**：把 `acryl-datahub` 的 **source/sink 摄取插件体系**当范本，是性价比最高的做法 | **治理能力分层**：DataHub 官方文档明确「OSS 上 unrestricted 列表只影响实体页/masking 展示，**查询时搜索下推是 DataHub Cloud 独有**」（[Policies Guide](https://docs.datahub.com/docs/authorization/policies)）——**执行类能力在商业版** |
| **对照：OpenMetadata（本体不在覆盖范围，仅作参照）** | 统一元数据平台 | Apache-2.0 | **15,361 star（比 DataHub 更多）**；**release 2.0.3（2026-09-30）**（发布说明在文档站而非 GitHub Releases，故 GitHub API 查不到 release 对象）；最后 commit 2026-09-30；商业臂为 Collate | Java + MySQL + Elasticsearch + Airflow（Python 摄取） | — | 同 DataHub：商业版与开源版分层 |

---

## 3. 数据质量与数据契约

> ⚠️ **本节推翻了两个常见误解，选型影响重大**：
> ① **Great Expectations 没有改许可**，仍是 **Apache-2.0**；真正改了许可的是 **Soda Core**（2026-01 起从 Apache-2.0 改为 **Elastic License 2.0**）。
> ② **GX Cloud 已被 FICO 收购并于 2026-06-01 停止公开提供服务**，GX Core 交由 **Fivetran** 托管；仓库已迁至 `fivetran/great_expectations`。

| 项目 | 定位 | 许可 | 活跃度（2026-10-01 实测） | 核心能力 / 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **Great Expectations（GX Core）** | Python 的期望式（Expectation）数据质量框架，200+ 内置期望 + Data Docs 报告 | **Apache-2.0**（HEAD `LICENSE`、PyPI 元数据、2026-07 第三方对比三方一致；**未发现任何改许可的痕迹**） | **活跃**：**11,851 star**；最新 **1.23.2（GitHub 2026-09-28）**；提交至 2026-09-30。仓库已 301 跳转至 **`fivetran/great_expectations`** | Python 3.10–3.13；链路 **Data Context → Data Sources（SQLAlchemy/pandas/Spark）→ Expectation Suite（JSON 序列化）→ Validation Definition → Checkpoints with Actions（OSS 唯一的告警钩子）→ Data Docs**；有 profiler/Data Assistant 自动生成 suite；可插拔 Store（文件系统/SQL）。**无调度、无告警路由、无自带 UI** | **借鉴 + 选择性复用**：期望库与 Data Docs 概念值得吸收。**但它没有可移植的、厂商中立的契约产物**（无 servers/SLA/ownership），**不能作为契约主干**。实践姿势：**契约用 ODCS，从 ODCS 生成 GX suite** | **2026 年两次易主**（GX Cloud 卖给 FICO 后关停、Core 交给 Fivetran）→ 商业模式不稳定信号；Python 依赖树庞大（SQLAlchemy/pandas/可选 pyspark）；OSS 的告警要你自己接编排 |
| **Soda Core** | CLI + Python 引擎，按 **YAML 契约**校验 schema 与数据质量，覆盖 20+ 数据源；仓库现已自称 "Soda Core — **Data Contracts** Engine" | **⚠️ Elastic License 2.0（ELv2）**——仓库 `LICENSE` 实测；**自 v4 起从 Apache-2.0 变更，2026-01-27 公告**（[Soda 博客](https://soda.io/blog/soda-core-license-update-moving-to-elastic-license)）。补充细节：PyPI 元数据至今仍写作 `Proprietary`。**ELv2 = source-available：内部生产使用免费，但禁止以托管/管理服务形式提供给第三方** | **活跃**：**2,431 star**；最新 **v4.25.0（2026-09-23）**；提交至 2026-09-29 | Python 3.9–3.12；契约 YAML + 50+ 内置检查类型；`soda data-source test`、契约 verify/publish、Python API；可本地跑、在 Airflow/Dagster/Prefect 里跑、或经 Soda Cloud 远程跑；数据源覆盖 Postgres/Snowflake/BigQuery/Databricks/Redshift/SQL Server/Athena/DuckDB/Trino/Oracle/SAP HANA/Salesforce/Polars/Pandas/Spark DataFrame 等。**v4 把自研契约语言设为默认，取代了 SodaCL——破坏性变更**；v4 包名从 `soda-core-{source}` 改为 `soda-{source}` | **仅抄设计，不要嵌入**：契约 YAML + 按源适配器的拆分是好蓝图，**但把它嵌进任何可能对外托管的产品，正是 ELv2 禁止的行为** | **许可风险为本组最高**；厂商自有的非标准契约格式；**ML 异常检测与告警在 Soda Cloud**；v3 的 SodaCL 异常检测检查**已弃用**；v4 是破坏性迁移。存在 Apache-2.0 的社区 fork **Provero**，但仅 ~17 star、v0.2.1（2026-03-22） |
| **Elementary** | **dbt 原生**数据可观测性：异常检测测试、元数据表、HTML 报告、Slack/Teams 告警 | Apache-2.0（CLI 与 dbt 包均已实测） | **活跃**：CLI **2,415 star**，dbt 包 `dbt-data-reliability` **525 star**；最新 Python **v0.26.0（2026-09-10）**、dbt 包 **v0.26.0（2026-09-08）** | dbt 包（Jinja 模型）+ Python CLI；**指标表写进你自己的数仓**（零拷贝）；管道健康监控（volume + freshness，Z-score）+ **列级指标异常检测**（空值率、基数、均值、长度）；`edr` CLI 出报告与告警。**异常检测在 OSS 里是真实存在的**（与 Soda 不同） | **直接用（若你的平台以 dbt 为中心）**；否则**抄设计**——「元数据表落在客户数仓里、报告从表里生成」是本组最便宜且可信的可观测性架构 | **强绑定 dbt**（OSS 只支持单个 dbt 项目，且必须有 dbt 才能触发检测）；**最好的检测能力在商业版**（云版有 query-history 监控、自动全覆盖、ML 模型、无 dbt 直连数仓模式、incident 分诊）；OSS 只有 Z-score 等简单统计；单一初创厂商 |
| **dbt tests / dbt-core + dbt-utils + dbt-expectations** | 转换工具，其 `data_tests` 与**模型契约（model contracts）**是 dbt 用户的「事实契约」 | `dbt-core` **Apache-2.0**、`dbt-labs/dbt` monorepo **Apache-2.0**、`dbt-utils` **Apache-2.0**、`dbt-expectations` **Apache-2.0**（均实测）。**Fusion 曾是例外**（ELv2，2025-05 公告），但其运行时已作为 **dbt Core v2 以 Apache-2.0 重新开源**，Fusion 仅保留登录/付费的高级特性 | `dbt-labs/dbt-core` **13,950 star**，每日提交；PyPI **`dbt-core` 1.12.5（2026-09-15）**是当前稳定 Python 线，**`dbt` 2.0.6（2026-09-19）**是 Rust v2 引擎（GitHub release 到 v2.0.5，2026-09-18）；`dbt-utils` **1,817 star**、1.4.1（2026-06-28）；**`calogica/dbt-expectations` 1,235 star、末版 0.10.4（2024-09-10）、最后提交 2024-12-16 → 已休眠约 21 个月**，活跃 fork 由商业公司维护（`metaplane/dbt-expectations`，0.10.10，2025-12-02） | YAML 声明式断言编译为 SQL 在数仓内执行；**`contract: enforced: true` 提供真正的构建期强制**（模型 SQL 产出与声明形状不符则构建失败，并把数据类型/约束写进 DDL）；单测；`dbt-utils` 通用测试宏；`dbt-expectations` 把约 60 个 GX 式测试移植进 dbt | **直接用（有 dbt 时）**：**dbt 模型契约是本组「最便宜的真正强制」**；**抄设计**：「契约 → 生成测试」的转译思路可推广 | 范围只覆盖 dbt 构建的模型（Kafka topic、S3 桶、第三方表一律没有；无 servers/SLA/ownership）；**dbt Labs 已并入 Fivetran（2026-06-01 完成）**；`dbt-expectations` 实质无人维护；v1-Python 与 v2-Rust 双线带来迁移成本 |
| **Deequ** | Scala/Spark 上的「数据的单元测试」库 | Apache-2.0（实测） | **维护中但低速**：**3,648 star**；最新 **2.1.0（2026-09-16）**；PyDeequ 1.7.0（2026-09-14）；**提交偏 CI/bot 化**（2.1.0 的变更日志多为机器人/CI 工作） | Scala 2.12、Java 11+，Spark 3.1–3.5 分构件；**约束校验 + 全量指标计算 + 约束建议（constraint suggestion）+ 基于指标时间序列的异常检测**（`AbsoluteChangeStrategy`、`RelativeRateOfChangeStrategy`）+ **metrics repository** 存时序指标 + 增量/代数状态支持分区与增长数据；已支持 **DQDL**（AWS Glue 的 Data Quality Definition Language） | **抄设计（本组最值得抄）**：**「指标时序仓库 + 异常策略」这一抽象是自研平台最可复用的思想**，且与引擎无关、可在任何引擎重实现。**仅当平台是 Spark 原生时才直接用** | 仅 Spark；无调度、无告警、无 UI、无目录；节奏慢且依赖 AWS 小团队；**同一技术的托管形态（AWS Glue Data Quality）才是厂商的商业产品**（open-core 加值） |
| **DataContract CLI + ODCS** | `datacontract` 是 CLI/Python 库：lint ODCS 契约、连真实数据跑 schema + 质量测试、导出到 25+ 种格式 | CLI **MIT**（`LICENSE`: "MIT License / Copyright (c) 2026 Entropy Data GmbH"）；**ODCS 规范 Apache-2.0**（均实测） | **非常活跃**：CLI **1,074 star**、最新 **v1.2.2（2026-09-25）**、每日提交；README 称约 140 万次下载/月。ODCS **1,156 star**、**v3.2.0 "Peter Flook"（2026-09-08）**、v3.1.0（2025-12）。**由 Bitol 在 Linux Foundation AI & Data 下治理（非厂商私有）** | CLI 命令：`lint`/`test`/`export`/`import`/`breaking`/`changelog`/`catalog`/`dbt sync`/`publish`/**`api`（REST server + Swagger UI + `x-api-key` 鉴权，有公开 demo）**；GitHub Action 与 `datacontract ci` 输出 GitHub/Azure DevOps 注解。**18+ 可测数据源**（Snowflake/Databricks/BigQuery/Athena/Redshift/S3/Azure Blob/ADLS/GCS/Postgres/MySQL/MSSQL/Oracle/Trino/Impala/Kafka/Spark DataFrame/JSON HTTP API/本地 Parquet-JSON-CSV-Delta）。**导出目标含 GX suite、SodaCL(v3)、dbt models/sources/staging SQL**、Avro/Protobuf/JSON Schema/DDL/Terraform。ODCS 3.2.0 新增枚举、map 与 AI/语义上下文块 | **直接用（本组最佳积木）**：MIT + 中立治理 + 可移植格式 + 多格式导出，**把 ODCS 放在契约模型中心，CLI 同时充当 CI 门禁、测试执行器与转译器** | **实操上由厂商主导**（Entropy Data 卖契约管理平台，`publish` 指向它）；`datacontract test` 是**事后（CI/定时）**，**不是运行期门禁**；Python + 各源驱动 extras 带来依赖管理负担；同一厂商同时推规范与实现，存在**规范-实现漂移**风险 |
| **其他（备选与反面案例）** | **OpenMetadata**（见 §2）是唯一**既存契约又执行契约**（定时）的 OSS 目录，含动态 ML 断言、incident 与告警；**Datavines**（Apache-2.0，765 star，JDBC + Spark，仅邮件告警，中文文档）；**DQOps**（架构上正是「跨引擎检查 + 时序异常检测 + 告警 UI」，但 **BSL-1.1 且自 2026-01-05 起无提交**）；**whylogs**（2,834）/ **Evidently**（7,951）/ **NannyML**（2,157）属 ML/漂移监控库，非数仓 DQ；**Qualitis**（WeBank，最后提交 2025-04-09）；**Apache Griffin**（已进 Attic） | 见各条 | DQOps **已停**；Qualitis **休眠**；Griffin **退役** | — | **DQOps / Qualitis / Griffin：不要采用** | BSL / 休眠 / 退役 |

**本节的两个直接答案**：

**(a) 有没有好的「跨引擎、查询时」数据契约强制运行时？——没有。** 现有能力只分四层，真正的运行期强制只有一层且**不在查询时**：

| 层次 | 代表 | 现实 |
|---|---|---|
| **左移 / CI** | **datacontract-cli**（ODCS→25+ 格式 + CI 注解）、buf | PR 阶段拦截。最成熟、最便宜。**不是运行期** |
| **构建期** | **dbt 模型契约**（`contract: enforced: true`） | **真强制**，但只覆盖单个 dbt 项目构建的模型 |
| **事后 / 定时** | **OpenMetadata**（1.9 起有契约对象 + 定时校验；1.10 支持 ODCS 3.1 导入导出）、Soda Core、GX、Deequ | 小时级延迟才发现。最接近「契约运行时」，但在目录侧 |
| **真运行期（broker 侧）** | **Redpanda Data Transforms**（OSS，broker 内 Wasm 校验 + 死信路由） | **唯一生产级且免费**的 broker 侧强制。Confluent 的等价能力是企业/专用层；社区 Schema Registry 的强制**只在客户端侧** |

**最接近你描述的是 OpenMetadata**（唯一既存又执行契约的 OSS 项目），其次是 **Redpanda**（若数据是流式且违约不得触达下游）。**真正的空白**：**没有任何引擎中立的查询网关/代理，能拦截 SQL（Trino、JDBC、数仓 REST）并按契约拒绝、隔离或改写查询/行；也没有任何东西把契约绑定到数仓写入路径的强制上**。「ODCS 契约 → 策略 → 查询/写入时门禁」在开源里确实无人占据。

**(b) 有没有引擎无关的自动异常检测 + 告警？——有，但每个选项都带耦合或许可星号。**
① **OpenMetadata（整体最接近）**：Apache-2.0、15,362 star、连接器覆盖广、DQ 测试套件、**能学习趋势（含季节性）的动态 ML 断言**、自动 **incident + 告警**（Slack/Teams/webhook）；缺陷是动态断言需**约 5 周 profiling 预热**、执行是**定时而非管道内**、Java/React 部署重、免费与 Collate 商业版边界需逐项审计。
② **Elementary OSS（在 dbt 上最划算）**：真实的 Z-score 异常检测 + Slack/Teams 告警 + 数仓原生存储。
③ **Deequ（算法最好、产品最弱）**：真异常策略 + 指标仓库，但仅 Spark、无调度/告警/UI。
**结论**：**没有任何单一开源项目能同时提供「引擎无关的 ML 异常检测」与「一等公民告警」而不带 dbt 耦合、source-available/停更许可或重型目录+商业边界**。务实栈：**ODCS 作契约模型 → datacontract-cli 作 CI/测试执行器 → OpenMetadata（或自研服务）作检测/调度/告警 → 自建指标时序库（结构照抄 Deequ 的 metrics repository + 异常策略）**。

---

## 4. 血缘与 SQL 解析库

> 本节的星标、版本、发布日期均通过 GitHub REST API / `releases.atom` / PyPI JSON API 于 **2026-10-01** 实测，可信度高于本报告其他小节。

| 项目 | 定位 | 许可 | 活跃度（2026-10-01 实测） | 核心能力 / 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **sqlglot** | 零依赖的 Python SQL 解析/转译/优化器，**列级血缘的事实标准引擎** | **MIT** | **极活跃**：**≈9,650 star**，fork 1,309；最后 push **2026-10-01**；最新 **v30.21.0（2026-09-30）**；未归档 | 纯 Python（可选 mypyc 加速、Rust tokenizer `sqlglotrs`）；**34 个方言**；**一方提供列级血缘 API**：`sqlglot.lineage.lineage(column, sql, schema=, sources=, dialect=, scope=, on_node=)` 返回 `Node` 树，`column=None` 可一次取全部输出列的血缘；配套优化器 `qualify`/`build_scope`/`Scope`/`annotate_types`/`unnest_subqueries`；**只做静态分析，不是校验器** | **直接用（强烈推荐）**：自研平台的 SQL 解析与列级血缘基座。DataHub 的生产解析器基于它，SQLMesh 的血缘引擎就是它。**不要自己写 CLL 引擎** | **版本策略激进**：README 明确 **MINOR 版本即可不兼容**（2026-09 一个月内 v30.18→v30.21）；DataHub 因此固定版本并打 `_sqlglot_patch`；血缘质量取决于传入的 schema，**Jinja/dbt 不支持**（[#1713](https://github.com/tobymao/sqlglot/issues/1713)），缺 schema 时退化为 `Placeholder` |
| **sqlparse** | 非校验型 SQL **分词/格式化**工具 | BSD-3-Clause | 维护但低速：**≈4,021 star**；最后 push 2026-08-13；末版 **0.6.0（2026-08-13）**，最近提交均为发布日杂务 | 纯 Python lexer + 栈式分组；分词、语句切分、格式化。**无 AST、无方言语义、无血缘** | **仅借鉴 / 工具性使用**（廉价的语句切分）。**绝不要用它做血缘** | **2026 年 0.6.0 一次性修了 4 个 DoS CVE**（CVE-2026-59893/54284/71491/59894）——对抗性 SQL 可造成 CPU 爆炸；TPC-H 上比 sqlglot 慢约 5 倍 |
| **Apache Calcite** | JVM 上的「动态数据管理框架」：SQL 解析 + **校验** + 可插拔优化器 + RelNode 代数 | Apache-2.0 | **活跃**：**≈5,189 star**；最后 push 2026-10-01；末版 **1.42.0（2026-05-31）** | Java + ANTLR；`SqlParser → SqlNode → SqlToRelConverter → RelNode → Volcano 优化`；血缘接口 **`RelMetadataQuery.getColumnOrigins`**（`RelMdColumnOrigins` 覆盖 Aggregate/Calc/Filter/Join/Project/SetOp/TableScan 等），返回 `Set<RelColumnOrigin>` 带派生标志。**词法方言仅 6 种**（BIG_QUERY/ORACLE/MYSQL/MYSQL_ANSI/SQL_SERVER/JAVA） | **抄设计（JVM 栈外）/ 可直接用（JVM 栈内且有现成 catalog）**：**「关系代数中间表示 + 元数据查询接口」是最成熟的 SQL 语义内核范式** | 必须提供 schema/catalog 才能产出 RelNode；已知血缘缺陷（[CALCITE-4192](https://issues.apache.org/jira/browse/CALCITE-4192)、[CALCITE-4251](https://issues.apache.org/jira/browse/CALCITE-4251)）；无血缘 API/事件格式，跨方言文本映射缺失；Flink/Hive/Dremio/Drill 等 fork 造成版本耦合 |
| **OpenLineage** | 血缘**事件的开放规范 + 客户端 + 集成库**（是接口标准，不是血缘引擎） | Apache-2.0 | **活跃**：**≈2,684 star**；最后 push 2026-09-30；末版 **1.53.0（2026-09-01）**；LF AI & Data 项目 | 事件模型 `RunEvent{job, run, inputs, outputs, facets}`；**`ColumnLineageDatasetFacet` 已迭代到 1-0-1 / 1-1-0 / 1-2-0 三个版本**，语义为「每个输出字段 → inputFields[] → transformations[]（DIRECT/INDIRECT + IDENTITY/TRANSFORMATION/AGGREGATION/JOIN/SORT + masking）」。**列级血缘支持不均**：Spark 默认开启（`io.openlineage.spark3...columnLineage`），**dbt 集成只有表级**。2026-08 提出 [Explicit Lineage Facets](https://openlineage.io/blog/explicit-lineage/) 提案（当前 CLL 是「每个输出全有或全无」、隐式 inputs→outputs 会产生笛卡尔边）。自带 `openlineage-sql`（Rust 内核，9 方言）。消费端：**Marquez**、**DataHub**（REST 端点 + Spark 插件，含列级）、**Egeria**（Open Lineage Event Receiver + Lineage Explorer）、**OpenMetadata**、**Apache Hop** | **直接用（战略级）**：把 OpenLineage 当作**血缘传输的事实标准**——你的血缘服务只要消费事件即可兼容海量生产者；**抄设计**：facet 的可扩展模型与 producer/consumer 分离 | **血缘是「选择性开启」的**：未配置 Listener 的作业什么都不发（observed-only）；**CLL 有损**（dbt 不发、按输出全有或全无）；facet schema 仍在演进，explicit-lineage 提案可能改变模型；**不要指望 OL 自己给你 SQL 文本级 CLL**（其 SQL 解析器方言窄） |
| **Marquez** | OpenLineage 的**参考实现**（见 §1.4） | Apache-2.0 | **代码活跃但发版停滞**：**≈2,284 star**，最后 push **2026-09-27**，但最新 release 仍是 **0.51.1（2025-03-27）**。LF AI & Data 2024-02 毕业 | 见 §1.4 | 见 §1.4 | 发布节奏慢于代码节奏，说明项目重心已在别处；仍为 0.x |
| **Spline（AbsaOSS）** | Spark（及 Python）**运行期**血缘 Agent + Server + UI | Apache-2.0 | **进入维护模式**：**≈668 star**；最后 push 2026-09-18；末版 **1.0.0-RC3（2026-06-02）**（1.0 正式版仍未发）；**最近 10 次提交全是依赖升级，无功能开发**——这是典型的维护模式信号 | Spline Server：Java 21 + **ArangoDB** 图存储 + Producer/Consumer REST；Spark Agent 通过 `spark.sql.queryExecutionListeners=...SplineQueryExecutionListener` 挂载；**支持属性级/列级血缘**（operations + `DerivesFrom` 边）；Python agent 用 `@track_lineage` **声明式**（非推导） | **抄设计**：agent → producer API → 图存储 → consumer/UI 的链路，以及 `DerivesFrom` 边模型 | 功能停更、1.0 长期 RC、单一厂商主导；Spark/Scala bundle 与版本强耦合（**Spark 4 支持 `未核实`**）；引入 ArangoDB 这一额外有状态组件；只看得到运行期 |
| **SQLMesh** | 数据转换框架，其**列级血缘是 sqlglot lineage 的一层薄封装** | Apache-2.0 | **活跃**：**≈3,304 star**；最后 push 2026-10-01；末版 **v0.236.2（2026-09-08）**；**仓库已迁移到 `SQLMesh/sqlmesh`**（原 `TobikoData/sqlmesh` 已 404） | 源码证实：`sqlmesh/core/lineage.py` 直接 `from sqlglot.lineage import Node, lineage as sqlglot_lineage`，配合 `qualify(...)` + `build_scope(query)` + scope 缓存，再分层出 `column_dependencies()`（父模型→列）与递归 `column_description()`。**血缘代码本身仅约 115 行** | **抄设计（最佳公开范例）**：它是「在模型图上运营 sqlglot 血缘」最完整的开源配方，价值在配方而非框架 | 采用它等于采用它的项目格式与调度器；语义偏 dbt 风格且与模型定义绑定；版本节奏跟随 sqlglot；组织改名带来引用抖动 |
| **闭环工具补充** | **sqllineage**（≈1,678 star，**MIT**，v1.5.9 2026-09-05，可插拔 sqlfluff/sqlparse 解析器 + networkx 图，`-l column` 直出列级血缘）；**Apache Hop**（≈1,484 star，2.19.0，**原生 OpenLineage sink**，SQL 派生与流路径两类 CLL，主目标 Marquez）；**jSQLParser**（≈5,964 star，5.4，**仅解析器无血缘**）；**Apache Egeria**（≈925 star，V6.1 2026-08-19，业务血缘/Lineage Warehouse/Lineage Explorer）；**GSP / Gudu SQLFlow**（**商业闭源**，其 OSS sidecar 专门补 DataHub/OpenMetadata 缺失的存储过程、MERGE 列级血缘，但 SQL 会上传到云端服务——**数据出境风险**） | — | 上述版本均为 2026-09 实测 | sqllineage 可直接用于快速分析但语法保真度弱于 sqlglot；Hop 可作 OpenLineage 生产者参考 | **仅借鉴 / 按需选用** | jSQLParser 上游 Maven Central 构件"显著偏旧"，社区推荐用 Manticore 构建；Hop 内存缓冲在硬崩溃时丢事件 |

**参考：DataHub 自身的列级血缘工程（即使不在覆盖范围，也是最好的蓝图）**：≈12,785 star，server v1.6.0.3（2026-09-25）。基于 sqlglot + 大量工程化：SELECT/CREATE VIEW/CTAS/INSERT/UPDATE 的 CLL；子查询、CTE、UNION ALL；`SELECT *` 展开；大小写与 BigQuery 分片归一；**0–1 的 `confidence_score`**；用 `SqlParsingAggregator` 处理临时表/重命名/交换；**固定 sqlglot 版本并只启用 14 条优化规则中的 5 条**（因性能问题排除 `normalize`/`pushdown_predicates`/`optimize_joins`/`eliminate_*`），加协作式超时与解析缓存；并明确列出**不支持项**：标量 UDF、表值函数、`json_extract`、UNNEST、struct、Snowflake 多表插入、多语句脚本、动态 `identifier()`、MERGE 的 CLL、WHERE/GROUP BY/ORDER BY/JOIN 列的"血缘"（[文档](https://docs.datahub.com/docs/lineage/sql_parsing)）。**注意其自身声明这些工具"并非 DataHub SDK 的正式组成部分"** —— 抄设计，不要当稳定 API 依赖。

**本节的直接答案**：(a) 基于 SQL 文本构建跨方言列级血缘服务，**最佳基座是 sqlglot（`build_scope` + AST 遍历）**，Java 栈可用 Calcite 的关系代数层；(b) **真正难的部分**是动态 SQL、UDF/存储过程、dbt/Jinja 模板、临时表与 CTE 作用域、Python/Spark 等非 SQL 变换，以及**跨层 execution-id 关联**——这些在任何开源项目里都没有干净解法。

---

## 5. 权限与策略引擎

> 本节星标、版本、日期均于 **2026-10-01** 通过 GitHub REST API / `ungh.cc` / `commits.atom` / raw LICENSE 文件实测。

| 项目 | 定位 | 许可 | 活跃度（2026-10-01 实测） | 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **SpiceDB** | Zanzibar 风格的**分布式授权数据库**：schema 语言 + 关系元组 + **caveats** + 显式一致性语义 | Apache-2.0 | **活跃（本组生产先例最强）**：**7,109 star**；末版 **v1.56.2（2026-09-11）**，v1.56.0（2026-07-24）；最后 commit 2026-09-29 | Go；gRPC + HTTP 网关；`.zed` schema 语言；存储支持 PostgreSQL / CockroachDB / MySQL / **Spanner** / 内存；权限在查询时由图 dispatch 计算；**caveats 是绑定在关系上的 CEL 表达式**，由调用方提供 context。能力：CheckPermission / BulkCheck / **LookupResources** / LookupSubjects / ExpandPermissionTree；**ZedToken 读写一致性**；**Watch API**（审计流的原料） | **直接用（首选 PDP）**：层级继承是原生的（`permission view = view + parent->view`，任意深度）；**标签类 ABAC 用 caveats**（Netflix 有大规模生产先例）；`LookupResources` 直接支撑「这个主体能看到哪些资产」的目录过滤。**抄设计**：schema 语言、caveats、ZedToken 一致性、Watch 审计 | **只输出布尔决策**——**不会输出「把 email 列脱敏」这类指令**，强制与脱敏要在数据面自己做；标签需同步进 tuple 或 caveat context（有陈旧导致 TOCTOU 的风险，必须真的用 ZedToken）；**无策略编排 UI / 审批流 / 访问复核报表**；运维面（数据存储、迁移、dispatch、schema 演进）成为你的 SLO；有商业托管版，需关注开源/托管功能分层 |
| **OpenFGA** | CNCF **孵化**中的高性能 ReBAC 引擎（Google Zanzibar 启发） | Apache-2.0 | **活跃**：**5,890 star**；末版 **v1.21.0（2026-09-20）**；最后 commit 2026-09-28；**2025-11-11 成为 CNCF 孵化项目**（[TOC PR](https://github.com/cncf/toc/pull/1923)） | Go；gRPC + HTTP；存储支持 PostgreSQL / MySQL / SQLite / 内存；**OpenFGA DSL 模型 + 关系元组 + CEL conditions**；能力：Check / BatchCheck / Expand / **ListObjects** / ListUsers / 上下文元组 / **每 store 模型版本化** / 一致性偏好；有 playground 与 CLI | **直接用**：建模体验与运维都比 SpiceDB 轻（普通 Postgres 即可）；catalog→schema→table→column 各自建对象类型并用 `parent` 关系继承（[Parent-Child 文档](https://openfga.dev/docs/modeling/parent-child)）；**标签策略用 conditions / 上下文元组**实现 ABAC | 同样是**只出布尔决策**；**无原生标签/分类存储、无脱敏、无行过滤输出**；标签要么进 tuple 要么进 condition context；2026 年有 CVE 报告需跟踪（CVE-2026-41131 策略执行不当、CVE-2026-40293 playground 密钥泄露——**聚合站来源，须对 GHSA 复核**）；项目较年轻（2022 起），但已入 CNCF 治理 |
| **Ory Keto** | Ory 生态里最早的 Zanzibar 式 ReBAC 权限服务 | Apache-2.0 | **未归档但节奏最慢**：**5,406 star**；版本 v26.2.0（2026-03-20）、v25.4.0（2025-11-07）；最后 commit **2026-07-29**；仍在 Ory 统一发布train内（v26.3.4，2026-07-28）。**未找到明确的「维护模式」声明** | Go；PostgreSQL / MySQL / CockroachDB；关系元组 + namespace + **OPL（TypeScript 风格的权限语言）**；check/expand 查询 | **仅借鉴**（或仅在 Ory 体系内做纯 ReBAC） | **没有 ABAC**——README 中检索 `ABAC`/`condition`/`caveat`/`attribute` 均无结果；用标签就必须「每个资产 × 每个标签一条 tuple」（基数爆炸 + 自建同步）；**open-core**：README 说明保证 CVE 修复、当前企业构建与高级特性需要 **Ory Enterprise License**；Ory 官方引导生产用户走 Ory Network |
| **Casbin** | **已进入 Apache 孵化器**（仓库现为 **`apache/casbin`**）：嵌入式授权**库**（PERM 元模型），覆盖 ACL/RBAC/ABAC，10+ 语言 | Apache-2.0 | **非常活跃（本组星标最高）**：**20,408 star / 1,759 fork**；末版 **v3.11.0（2026-08-20）**，以 **`3.11.0-incubating`** 发布；最后 commit 2026-09-11；[Apache 孵化器条目](https://incubator.apache.org/clutch/casbin.html)、[2026-08 报告](https://lists.apache.org/thread/tqpm3dhzph7q2pxdsplwo1hjkd5ptyzb) | Go 内核 + 多语言移植；`model.conf` + policy 规则；RBAC（含 role 继承与 **domain/租户**）、**matcher 表达式实现 ABAC**；adapter（PG/MySQL/Redis…）、watcher、enforcer；策略通常在进程内内存加载 | **直接用（仅作进程内粗粒度 RBAC/ABAC 的 PDP）**，否则**仅借鉴**。**抄设计**：model 与 policy 分离 | **无图遍历、无 ListObjects 等价物、无一致性模型**——「这个主体能看到哪些资产」这类查询要自己写；Casbin 文档自身警告**客户端全量加载策略可能是安全风险**；深层层级退化为手工维护 role 图；matcher 是代码，审计会散落；**v3 与 v2 是两个大版本**，移植生态有漂移 |
| **OPA / Cedar**（补充） | 通用策略引擎：OPA（Rego，CNCF 毕业）+ Cedar（AWS 开源，**2026-01 加入 CNCF Sandbox**，[InfoQ](https://www.infoq.com/news/2026/01/cedar-joins-cncf-sandbox/)） | Apache-2.0 | 都活跃 | OPA：Rego 策略 + sidecar/库形态；Cedar：结构化策略语言 + 形式化验证（[2026 对比](https://dev.to/moksh/fine-grained-authorization-in-2026-openfga-spicedb-cerbos-and-cedar-compared-4l04)） | **直接用**：做「策略即代码」的 PDP；**抄设计**：策略与数据分离、策略可单测 | **OPA 无关系图**（层级继承要自己建模）；Cedar 生态尚新；两者都需自建 PAP/PIP（策略管理点与信息点） |
| **Apache Ranger**（补充） | Hadoop 生态的集中式授权/审计，**原生含行过滤与列脱敏** | Apache-2.0 | **活跃**：2.9.0 已发布、2.10.0 在推进（[RANGER-5778](https://issues.apache.org/jira/browse/RANGER-5778)、[2.9.0 release notes](https://cwiki.apache.org/confluence/spaces/RANGER/pages/440304239/Apache+Ranger+2.9.0+-+Release+Notes)） | Java + MySQL/Postgres；Service/Resource/Policy 模型；**Tag-based policy** 与 **Atlas TagSync** 联动（Atlas 分类 → Ranger 标签策略 → 引擎执行）；**行过滤 + 列脱敏（GDS 策略评估，[RANGER-4991](https://issues.apache.org/jira/browse/RANGER-4991)）** | **抄设计（最高价值）+ 可直接用（Hadoop 系引擎）**：它是开源世界**唯一成熟的「数据级行/列策略 + 标签策略」模型**，也是**唯一原生输出「脱敏指令」而非布尔决策**的东西 | 重、偏 Hadoop；插件主要覆盖 Hadoop 系组件；**它需要 Atlas 提供标签模型**；与云原生栈集成需自研 |

**本节的直接答案**：
- 对「(a) 资产层级 ACL 继承 + (b) 基于标签的 ABAC + (c) 列级脱敏」三件套——**没有任何单一开源项目全部覆盖**。最接近的是 **SpiceDB**（覆盖 a 与 b，且有一致性机制与生产先例），**但 c 不在任何授权引擎的能力范围内**。
- **选型建议**：**SpiceDB vs OpenFGA** —— 要 CEL 条件 + 更轻运维选 **OpenFGA**（CNCF 孵化、普通 Postgres、BatchCheck/ListObjects 更好用）；要久经验证的 caveat/一致性机器选 **SpiceDB**。
- **缺的就是「最后一公里」**：① 授权引擎不输出脱敏/行过滤指令；② 没有标签/分类目录（标签得你自己存并同步进 tuple 或 caveat context）；③ 没有「新列自动获得关系」的目录语义约束；④ 没有策略编排 UI、审批流、访问复核报表。**这一层必须自研，且它是治理平台的核心价值。**
- 若想要「三件套开箱即用」的**产品**：**OpenMetadata** 有带条件的策略规则（`matchAllTags(tagFqn…)`、`noOwner()`、`hasDomain()`）与 [OpenMetadata Standards 的 Data Masking Policy 规范](https://openmetadatastandards.org/governance/policy/)，其真实的列脱敏路径是 **标签同步 → Apache Ranger → 引擎**；但这些策略管的是**目录元数据**的访问，不是数据面的读取，层级继承也基于 team/domain/owner 而非关系图。

---

## 6. 表格式与目录

| 项目 | 定位 | 许可 | 活跃度 | 核心能力 / 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **Apache Gravitino** ★ | **联邦式「元数据湖」**：把异构 catalog 统一到一套对象模型与 REST API 之下 | Apache-2.0 | **本报告最值得关注的项目**。**1.0.0 于 2025-09-24 发布**（[release notes](https://raw.githubusercontent.com/apache/gravitino-site/refs/heads/main/blog/2025-09-24-gravitino-1-0-0-release-notes.mdx)）；**2025-06 毕业为 ASF 顶级项目**（[TLP 公告](https://gravitino.apache.org/blog/gravitino-top-level-project/)、[ASF 月报](https://news.apache.org/foundation/entry/2025/06)）；发布节奏 0.8.0（2025-01，强化 AI 支持）→ 0.9.0（2025-05，聚焦 AI/治理/安全）→ 1.0.0 → **最新 1.3.0**（精确日期 `未核实`）；[2025 年度总结（2026-01-05）](https://gravitino.apache.org/blog/2025-summary/)、[ROADMAP](https://raw.githubusercontent.com/apache/gravitino/main/ROADMAP.md) 公开。**星标 `未核实`** | Java；对象模型 **Metalake → Catalog → Schema → Table / Fileset / Topic / Model**；Catalog 类型覆盖 Hive、Iceberg、Paimon、Hudi、JDBC(MySQL/PG)、Kafka、**Doris** 等；一方 **REST API**（`/api/metalakes/...`）与 OpenAPI 文档；关系型后端 MySQL/Postgres；**Iceberg REST 服务**从实验性在 1.3.0 走向「企业级」；内建**多级访问控制**（Metalake/Catalog/Schema/Table 特权，如 MANAGE_USERS、READ_FILESET）（[access-control.md](https://github.com/apache/gravitino-site/blob/main/docs/security/access-control.md)）；0.9.0 起推进 **ABAC 与 KMS** | **直接用（推荐作为元数据联邦层）**：它解决的正是自研平台最费力的「接一堆异构元数据源」的脏活，成熟度已过线；**抄设计**：Metalake 分层对象模型、以及 Fileset/Topic/Model 这类「非表资产」同样进模型的设计 | **它是「目录」不是「治理平台」**：**血缘是新加的、基于 OpenLineage 且受引擎覆盖限制**；**无数据质量**；**有 tagging 但没有分类（classification）体系**；**策略靠下推给 Apache Ranger**；KMS/ABAC 仍在提案阶段。API 仍在演进（1.x 早期）；能力边界以 Iceberg/Paimon/Hive 为中心 |
| **Apache Iceberg REST Catalog（spec v1）** | 表格式目录的**开放 REST 协议** | Apache-2.0 | 活跃（已成事实标准，各引擎/云均实现） | HTTP + OAuth2；`/v1/config`、`/v1/namespaces`、`/v1/namespaces/{ns}/tables`、`/v1/namespaces/{ns}/tables/{t}/metrics`；v1 标准化的重点包括**多表提交（multi-table commits）与服务端规划（server-side planning）**；credential vending 与 remote signing 在持续演进（[Access Delegation 说明](https://iomete.com/resources/blog/iceberg-access-delegation)、[polaris#5123](https://github.com/apache/polaris/issues/5123)） | **直接用 / 抄设计**：你的目录若要被 Trino/Spark/Flink 直接消费，**实现或代理这个协议**是性价比最高的互操作方式 | **它「刻意」不标准化治理**：**没有授权模型、没有标签、没有血缘、没有审计、没有策略、没有联邦、没有数据质量**。**不要把 IRC 当作你的身份/对象模型**——把它当**适配器** |
| **Apache Polaris** | Snowflake 捐赠的 Iceberg 目录，**2026-02-19 毕业为 ASF 顶级项目** | Apache-2.0 | **活跃且治理地位提升**：TLP（[毕业公告](https://www.globenewswire.com/news-release/2026/02/19/3240735/0/en/Apache-Polaris-Graduates-to-Top-Level-Apache-Project.html)）；**最新 1.8.0**（日期 `未核实`）；**星标 `未核实`** | Java（Quarkus）；**Polaris Core + Catalog**；一方管理 API 与 **principal / principal-role / catalog-role 三层 RBAC**；credential vending 支持 **STS / SigV4 / remote signing**；支持多 catalog 与 catalog federation | **直接用**：若目标是 Iceberg 多引擎共享，Polaris 是当前最正统的开源目录服务；**抄设计**：principal-role-privilege 的目录级 RBAC 模型 | 聚焦 Iceberg，**非 Iceberg 资产的治理不在其范围**；**2026 年有一批 CVE 集中在 credential vending 这一面**——凭证下发是攻击面，上线前必须审计；无血缘/质量/分类；毕业不久，API 仍在稳定中 |
| **Unity Catalog OSS** | Databricks 开源的多模态 Catalog（表/卷/函数/模型） | **⚠️ 许可无法确认**——针对性检索**既未找到许可文本，也未找到 2025 年许可变更的任何痕迹**。**不要假设是 Apache-2.0**，任何依赖决策前必须**亲自读仓库的 `LICENSE` 与 `NOTICE`** | 活跃：LF AI & Data 托管，Java/Scala，**0.6.x**；**存在两条发布线**：`v0.x`（核心 server）与 `ai-v0.3.1`（AI/ML 集成）——这是**产品线拆分，不是许可拆分**；另有 **`main` 与 `branch-0.4` 的实际分叉**（[issue #1477](https://github.com/unitycatalog/unitycatalog/issues/1477)）→ **必须锁版本**。2026 年 Databricks 宣布 **Unity Catalog Business Semantics GA 并开源**（[公告](https://www.databricks.com/blog/redefining-semantics-data-layer-future-bi-and-ai)） | Java；Server + CLI + Docker/Helm；**REST API + Iceberg REST**、Delta 与 Iceberg 双支持；**credential vending 确实可用**（服务端 `TemporaryPathCredentialsService` + `pathOperationToPrivileges`，且有**第三方独立实现消费该端点**：SpiceAI、`olai-uc-object-store`——第三方采用比文档声明强得多的证据）；OSS 权限模型是 **`PermissionService`（路径/操作 → 所需特权）+ basic server access control**，是 securable 级 grant，**明确不是 ABAC、不是行/列策略**；生态兼容性有实际回报：IBM watsonx.data 暴露 UC 的 Iceberg REST API，Ray 也在加 UC 支持 | **可直接用**：多模态资产（表/卷/函数/模型）统一在一个 catalog 下，是治理平台对象模型的良好范本；Business Semantics 是开源语义层新选项 | **开源版 ≠ 商业版（差距很大）**：**血缘、审计、ABAC、行/列过滤、受治理的标签、Delta Sharing、Metrics 全部在 Databricks 商业产品里**，不在仓库中；单一大厂主导；**开源/商业的功能边界会移动**（2026 年就开源了 Business Semantics），必须按功能逐项重新核实；Spark 之外引擎集成有限 |
| **Hive Metastore (HMS)** | 事实上的元数据中枢，Thrift API，长期充当 Iceberg 的默认 catalog | Apache-2.0 | 维护中但属「遗产基础设施」 | Thrift IDL（`ThriftHiveMetastore`）；元数据落 RDBMS（MySQL/Postgres）；数据库/表/分区/列/SerDe 模型；有 Notification Event（用于增量同步） | **直接用（互操作层）**：你的目录必须能读写 HMS，因为绝大多数存量引擎默认连它；**抄设计**：Notification Event 做增量元数据同步 | **治理能力极弱**：无列级血缘、无标签/分类、授权模型简陋（SQL Standard Auth / Storage Based Auth 都很粗糙）；分区数量大时性能糟糕；已是「不得不兼容」而非「值得依赖」 |

**本节的直接答案**：对「必须联邦大量异构 catalog 的自研治理平台」——**Gravitino 可以「直接用」作为元数据枢纽**（1.0 + ASF TLP + 联邦对象模型 + 内建多级访问控制，成熟度已过线）。但必须清醒地认识它的边界：

- Gravitino 是**目录**，不是**治理平台**。**血缘是新加的、走 OpenLineage 且受引擎覆盖限制**；**没有数据质量**；**有 tagging 但没有 classification 体系**；**策略靠下推给 Ranger**；KMS/ABAC 仍在提案阶段。
- **Iceberg REST Catalog 是适配器，不是你的身份/对象模型**——它刻意不定义授权、标签、血缘、审计、策略、联邦与质量。
- 推荐的**分层架构**：**Gravitino（或 Polaris）做第 1 层元数据联邦 → 自研（或 DataHub/OpenMetadata 级）治理语义存储做第 2 层**——血缘、分类、标签、策略、质量、术语表、审计都在第 2 层，第 1 层只负责「有哪些资产、长什么样、谁能连」。
- **两条硬性前置检查**：① **Unity Catalog OSS 的许可能否确认**（本次调研无法确认，不得假设 Apache-2.0）；② **逐功能重新核实开源版与商业版的边界**——这条线在移动（2026 年刚开源了 Business Semantics）。

---

## 7. 语义层与指标

| 项目 | 定位 | 许可 | 活跃度 | 实现要点 | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **Cube** | 开源 headless BI / 通用语义层，一份 cube 定义暴露 REST/GraphQL/SQL/MDX | **Apache-2.0（Cube Core）**，Cube Cloud 为商业版（[distribution](https://docs.cube.dev/admin/account-billing/distribution)） | 活跃，**v1.7.0** 等持续发布；星标 ≈20k（第三方站，估值） | TypeScript/Node + **Rust 内核（Cube Store 列式缓存）**；语义模型以 JS/TS/YAML 写在你自己的仓库里；多阶段预聚合 | **直接用**（若你要「成为」语义服务）；**抄设计**：语义模型 + 预聚合缓存分层 | **OSS/Cloud 功能分层**：精细 RBAC 等治理能力偏 Cloud；Node+Rust 运维面；预聚合失效正确性是难点 |
| **MetricFlow / dbt Semantic Layer** | dbt 里的 metrics-as-code，MetricFlow 把指标请求编译为仓库 SQL | dbt Core Apache-2.0；**MetricFlow 已重新开源**（[公告](https://www.getdbt.com/blog/open-source-metricflow-governed-metrics)）；**dbt Fusion 引擎不开源** | MetricFlow 仍在 PyPI 发布（**0.213.0**，[PyPI](https://pypi.org/project/metricflow/0.213.0/)）、可独立使用；但 **dbt Core v2（Rust）处于 Alpha**，战略变动大 | Python；**`dbt-semantic-interfaces`** 定义 YAML 规范与 Pydantic 模型；`dbt parse` 产出 `semantic_manifest.json`；MetricFlow 据此构图并生成 SQL | **抄设计（强烈推荐）**：`dbt-semantic-interfaces` 是**许可最宽松、最值得抄的指标 schema 参考**；也可**直接用**作纯 Python 校验依赖 | **本表战略不确定性最高**：三套许可 + 在途的 Rust 重写；MetricFlow 版本历史上与 dbt-core 版本耦合 |
| **Malloy** | Google 出身的开源**数据关系语言**，语义模型编译为 SQL | MIT（`未核实`，建议确认） | 存活但小众：npm `@malloydata/malloy` 仍在 **0.0.x**；新增 [malloydata/publisher](https://github.com/malloydata/publisher)（开源 Malloy 分析引擎） | TypeScript/Node + **DuckDB 为核心执行**；`source:` 模型、嵌套（nesting）、对称聚合（symmetric aggregates），编译到 BigQuery/DuckDB/Postgres/Snowflake/Trino | **仅借鉴**：语言设计与「语义模型 → 方言 SQL」的编译路径 | 仍是 0.0.x；贡献者少；存在第三方重新发布的 npm 同名包，供应链需警惕 |
| **LookML / Looker、AtScale**（对照，闭源） | 专有语义层 | **闭源商业** | — | LookML（`.lkml` 的 view/model/explore）；AtScale 在既有仓库上建语义层 | **仅作元数据抽取目标**：Looker API 4.0 / AtScale API。**注意**：AtScale 加入开放语义标准不等于开源产品（[AtScale 博文](https://www.atscale.com/blog/open-source-semantic-layer-crucial-for-ai-bi/)） | 供应商锁定；LookML 无开源引擎 |
| **Apache Superset / Lightdash**（补充） | BI 层轻量语义 | Apache-2.0 / MIT（Lightdash `未核实`） | 都活跃 | Superset：dataset 级 saved metrics、计算列、**dataset certification**；SIP-182 语义层在做。Lightdash：指标**直接取自 dbt YAML**，不引入第二套模型 | **直接用**作为治理指标的**消费端**；**抄设计**：Superset 的 certification（认证标记）UX | 语义能力弱于 Cube/MetricFlow；BI 层可被绕过，不是策略执行点 |
| **Apache Ossie（孵化中）** ★ | 语义元数据**交换标准**，前身 Open Semantic Interchange（OSI），由 Snowflake/Salesforce/dbt Labs/BlackRock 发起，2026 年捐给 ASF 更名 | Apache-2.0 | **孵化中、势头强**（[apache/ossie](https://github.com/apache/ossie)、[Snowflake](https://www.snowflake.com/en/blog/apache-ossie-open-semantic-interchange-incubator/)） | 供应商中立的语义/指标定义交换格式 | **直接对齐（战略建议）**：**把自研平台的指标定义 schema 设计成 Ossie 形状**，而不是 Cube/dbt 形状 | 规范早期；落地实现仍以各厂商转换器为主 |

---

## 8. 平台底座：调度与查询引擎

| 项目 | 定位 | 许可 | 活跃度 | 治理相关接口（关键） | 可复用 | 风险 |
|---|---|---|---|---|---|---|
| **Apache Airflow** | 事实标准的 DAG 调度器，已转向**数据感知编排（Assets）** | Apache-2.0 | 非常活跃，**3.3.x** 线（[3.3.2 release notes](https://airflow.apache.org/docs/apache-airflow/3.3.2/release_notes.html)） | **Metadata DB**：`dag/dag_run/task_instance/dag_version/serialized_dag/job/trigger/log` + asset 表（Postgres/MySQL）。2.4 引入 Datasets，**Airflow 3 改名为 Assets** 并增加 asset aliases、asset partitions（AIP-76）。官方 **OpenLineage Provider**（[文档](https://airflow.staged.apache.org/docs/apache-airflow-providers-openlineage/2.6.0/guides/structure.html)、[AIP-53](https://cwiki.apache.org/confluence/download/export/pdfexport-20250621-210625-1159-69233/AIP-53+OpenLineage+in+Airflow_7873abe0de274cf08b72912b2a9e84cb-210625-1159-69234.pdf)）+ `get_hook_lineage_collector()` | **直接用**：调度 + 数据集级血缘；**不要直接读它的 DB**（内部 schema，2.x→3.x 剧变），只用 **REST API 与 OpenLineage**；**抄设计**：Assets 的 URI 标准可作数据集命名基准 | 2.x→3.x 迁移成本；**AIP-72 把 listeners 挪进 Task SDK**，2.x 的 `on_task_instance_*` 不再是稳定契约；asset 语义仍年轻 |
| **Dagster** | 以**软件定义资产**为中心的编排器 | Apache-2.0 | 活跃，**1.13.x** 线（[1.13.22](https://newreleases.io/project/github/dagster-io/dagster/release/1.13.22)）。星标 ≈16k（第三方，估值） | `@asset` + 类型化 **AssetKey**、分区、**asset checks**、类型化 `MetadataValue`；**资产图即血缘图**；`dagster-graphql` 为程序化接口；`dagster-openlineage`、DataHub 插件 | **抄设计（本表最佳）**：**「资产图作为元数据模型 + 类型化元数据 + 资产检查」正是治理平台想要的形状**；也可**直接用**作调度器并输出 OL | 元数据变更需代码部署；元数据质量靠自觉；部分治理能力属 Dagster+ |
| **Argo Workflows / CD**（简要） | K8s 原生工作流 / GitOps | Apache-2.0 | 活跃（CNCF） | Workflows：CRD + step pod，**只有 artifact 级血缘**，无列级血缘、**无原生 OpenLineage**；CD：Application CRD + 同步状态/修订历史 = **部署溯源**（非数据血缘） | **仅借鉴**：CD 的修订历史可作「某时刻线上是哪个 commit」的审计证据；治理上下文只能靠 pod 的 annotations/labels 传递 | 无数据血缘能力；要接治理需自建旁路 |
| **Trino** | 分布式 ANSI SQL 查询引擎，**联邦 catalog + 最完整的治理 SPI** | Apache-2.0 | 活跃，近月度发布（**483**，约 2026-07；[release notes](https://trino.io/docs/current/release.html)） | **策略执行（PEP，最强）**：`io.trino.spi.security.SystemAccessControl`（`checkCanSelectFromColumns`、`filterColumns`、`checkCanCreateTable/InsertInto`、`checkCanGrantTablePrivilege`…）+ `ConnectorAccessControl.getRowFilter()/getColumnMask()` → **真正的行过滤与列脱敏，查询时执行**。内置 **OpenLineage Event Listener**（`event-listener.name=openlineage`，[文档](https://trino.io/docs/current/admin/event-listeners-openlineage.html)）与自定义 `EventListener.queryCompleted(QueryCompletedEvent)`（含读写 TableInfo、列、过滤条件）。元数据面：`system.metadata.*`、`information_schema.*`、`system.runtime.queries`。Catalog 为 `etc/catalog/*.properties`，Iceberg 一方支持 HMS/Glue/Nessie/JDBC/REST | **直接用（首选集成点）**：写自己的 `Plugin`/`SystemAccessControl`/`EventListener`，**不要 fork**。**抄设计**：以 AccessControl 为策略执行契约 | **PDP 必须快（要缓存）**；列级血缘完整性随版本而异；**最大漏洞：直连数仓 JDBC 会完全绕过 Trino**——覆盖率是治理平台成败关键 |
| **Apache Spark** | 批流计算引擎，已客户端-服务化（Spark Connect） | Apache-2.0 | 非常活跃（**4.1.0 已发布**，4.0.0 于 2025 GA） | **血缘**：`SparkListener`（`SparkListenerSQLExecutionStart` 带 SQL 文本与 `SparkPlanInfo`）、**`QueryExecutionListener.onSuccess(sqlText, qe, dur)` → `qe.analyzed()` 拿到已解析逻辑计划 = 列级血缘的实际路径**、Spark Event Log（Spline/OpenMetadata 的解析对象）、官方 OpenLineage Spark 集成。**策略**：**`spark.connect.extensions` → `SparkConnectPlugin`** 拦截 `AnalyzePlan/ExecutePlan`；或自定义 **`CatalogPlugin`/`TableCatalog`**（`spark.sql.catalog.<name>`）让每次表解析都查治理元数据（Unity Catalog OSS 就是这个思路的现成实现） | **直接用**：血缘（listener / event log / OL 集成）；**抄设计**：Connect 插件的策略拦截点 | **Listener 是 opt-in**，未配置的作业什么都不发；**内嵌/本地 Spark 会话无法集中管控**；UDF 与动态 SQL 不透明；**`executionId` 跨层不可靠，必须自带关联 ID** |

**本节的直接答案**：要在查询时执行策略并采集运行期血缘，最佳集成点是 **Trino（第一：`SystemAccessControl` + `ConnectorAccessControl` 的行过滤/列脱敏，配合内置 OpenLineage Listener）**，其次是 **Spark（Connect 插件做策略、`QueryExecutionListener.analyzed()` 做列级血缘）**，Airflow/Dagster 只能做**控制面**的「上游门禁 + 粗粒度血缘」，**不是查询时执行点**。Argo 与 BI 层不具备执行意义（BI 层可被绕过，只能当策略消费端）。

---

## 9. 三分类表：项目 → 可复用 / 可集成 / 仅借鉴

| 分类 | 项目 | 理由摘要 |
|---|---|---|
| **可直接复用（依赖或嵌入）** | **Apache Gravitino**、**OpenLineage（规范 + 集成）**、**Marquez（可部署，但发版停滞）**、**sqlglot（MIT，星标 9.7k）**、**SpiceDB（Apache-2.0，7.1k）/ OpenFGA（Apache-2.0，5.9k）**、**OPA / Cedar**、**ODCS 规范（Apache-2.0）+ DataContract CLI（MIT）**、**Great Expectations（Apache-2.0）**、**DolphinScheduler**、**Trino / Spark / Airflow / Dagster（平台底座）**、**Apache Polaris**（Iceberg 场景）、**Iceberg REST Catalog 协议**、**Unity Catalog OSS**（多模态资产，**需先确认许可**）、**Apache Ossie**（对齐指标规范）、**Cube**（若要语义服务）、**HMS**（互操作必需）、**Microsoft Presidio**（PII 探测） | 活跃、许可宽松（Apache-2.0/MIT）、接口稳定、有明确嵌入点 |
| **可集成（作为被管对象 / 消费端 / 互操作目标）** | **Apache Atlas**（被联邦的元数据源或迁移来源，仍是 Ranger 标签策略的标签来源）、**CKAN**（开放数据门户前端，**AGPL 需注意**）、**Apache Ranger**（Hadoop 系引擎可直接用其行过滤/列脱敏）、**OpenMetadata**（唯一既存又执行契约 + 异常检测 + 告警的 OSS 目录）、**Elementary / dbt tests**（dbt 栈内）、**Deequ**（Spark 栈内）、**dbt Semantic Layer / MetricFlow**、**Apache Egeria**（联邦互操作 + OpenLineage 摄取）、**Hive Metastore**、**SQLMesh**（已用其做转换时）、**Superset / Lightdash**（指标消费端）、**Ory Keto**（仅在 Ory 体系内） | 有明确价值但强绑定特定栈、或带许可约束、或只作为生态入口而非内核 |
| **仅借鉴（抄设计不抄代码）** | **LinkedIn WhereHows**、**Netflix Metacat**、**Amundsen**、**Apache Griffin**、**ODD Platform**、**Magda**、**sqlparse**、**Apache Calcite**（JVM 栈外）、**Spline**（已进维护模式）、**Malloy**、**LookML / AtScale**、**Argo Workflows/CD**、**DataHub 衍生项目**、**Soda Core**（**ELv2，不可嵌入**）、**Casbin**（作进程内 PDP 之外的用法） | 归档/退役/停更/许可受限，或其价值主要在**设计模式**（TypeSystem、ETL 任务图、后端 proxy、measure 与执行分离、关系代数、资产图、metrics repository + 异常策略） |
| **不采用** | **LinkedIn WhereHows**（已死）、**Amundsen**（2026-09 归档）、**Apache Griffin**（2025-11 进 Attic）、**DQOps**（BSL-1.1 且 2026-01 起停更）、**Qualitis**（2025-04 起休眠）、**calogica/dbt-expectations**（2024-12 起休眠）、**Soda Core 作为嵌入依赖**（ELv2 禁止对外托管）、**GSP / Gudu SQLFlow sidecar**（解析走云端，数据出境风险） | 归档/退役/停更/许可禁止，采用即等于永久自己维护一份死代码或承担法律风险 |

> **注意**：上表把 **Metacat 与 ODD Platform 归入「仅借鉴」而非「不采用」**——因为它们**仍在提交**（Metacat 2026-09-15、ODD 2026-09-21），只是不适合作为生产依赖。这与「已归档/已退役」是不同性质的风险，不应混为一谈。

---

## 10. 技术选型清单：自研通用数据治理平台，哪些轮子直接用现成的

| 层 | 建议直接采用 | 理由 |
|---|---|---|
| **元数据联邦底座** | **Apache Gravitino** | 已 1.0 + ASF TLP；原生解决「接一堆异构 catalog」这个最费力的脏活；Metalake 分层对象模型 + 内建多级访问控制，省下 6–12 个月的连接器与模型设计工作。**不要自己做联邦层** |
| **血缘传输标准** | **OpenLineage**（事件契约）+ **Marquez 领域模型**（`namespace/job/dataset/run/DatasetVersion`、`nodeId+depth` 查询） | 生产者生态（Airflow/Spark/Flink/dbt/Trino/Dagster）已经替你写好了埋点；自研血缘服务只要消费事件即可。**抄 Marquez 的「主存 + 可选搜索索引」分层**，运维成本低 |
| **SQL 解析与列级血缘算法** | **sqlglot**（Python 栈，MIT，34 方言，内置 `sqlglot.lineage` 列级血缘 API）/ **Apache Calcite**（JVM 栈，`getColumnOrigins`）；**SQLMesh 的 lineage 实现作参考** | 跨方言解析是典型的「永远做不完」的轮子；**不要自己写 CLL 引擎**——列级血缘自研只在 sqlglot scope 之上做方言特化与 schema 解析 |
| **授权引擎** | **SpiceDB 或 OpenFGA**（关系与层级继承）+ **OPA 或 Cedar**（标签条件、脱敏规则） | Zanzibar 模型天然适配「catalog→schema→table→column」的继承式资产权限；ABAC 交给通用策略引擎，避免把条件逻辑硬编码进授权服务 |
| **数据契约（最优先）** | **ODCS 规范（Apache-2.0，LF/Bitol 治理）+ DataContract CLI（MIT）** | 契约格式刚被标准化且**治理中立**，自研格式毫无意义；CLI 可把契约编译成 dbt tests / GX suite / SodaCL / DDL / Avro 等 25+ 目标，并充当 CI 门禁。**但要清楚：它只做「生成 + 事后测试」，不做运行期强制**——强制层必须自研 |
| **数据质量断言** | **Great Expectations（Apache-2.0）** 或 **dbt 模型契约 + Elementary（均 Apache-2.0）** | GX 的 Suite/Checkpoint 模型成熟且**许可安全**（注意其 2026 年两度易主：GX Cloud 被 FICO 收购后关停、Core 交给 Fivetran，商业模式不稳定但许可未变）。**⚠️ 不要把 Soda Core 当默认选项**：它自 v4 起已是 **Elastic License 2.0**，可用于内部生产但**禁止对外提供托管/管理服务**，且 ML 异常检测在 Soda Cloud。**不要自研断言 DSL** |
| **异常检测与告警** | **OpenMetadata**（若接受其部署重量）或 **Elementary**（dbt 栈）；算法抽象**照抄 Deequ 的 metrics repository + 异常策略** | 唯一「引擎无关 ML 异常检测 + 一等公民告警」的组合需要目录/商业边界，或 dbt 耦合。Deequ 的「指标时序 + 变化阈值策略」是**与引擎无关、可重实现**的最佳抽象。**不要自己发明异常检测算法** |
| **调度与编排** | **Airflow 或 Dagster**（国际栈）/ **DolphinScheduler**（国内栈、GUI 优先、多租户） | 调度器是最不该自研的组件之一。Dagster 的资产图模型更适合治理场景（资产图即血缘图）；DolphinScheduler 胜在 GUI 与多租户 |
| **查询时策略执行 + 运行期血缘** | **Trino**（首选）/ **Spark Connect 插件**（次选） | Trino 的 `SystemAccessControl` + `ConnectorAccessControl` 提供**行过滤与列脱敏**，并内置 OpenLineage Listener；这是唯一「能真正在查询时执行策略」的开源落点 |
| **目录协议/表格式** | **Iceberg REST Catalog 协议**（实现或做代理）；数量大时直接用 **Apache Polaris** | 实现这个协议即可被 Trino/Spark/Flink 消费，是互操作性价比最高的做法 |
| **语义层/指标标准** | **对齐 Apache Ossie**，兼容读 **Cube / dbt Semantic Layer / MetricFlow** 定义；**不要自创指标格式** | 语义层标准刚被 ASF 收编，自研格式必然被淘汰 |
| **PII 识别**（补充） | **Microsoft Presidio**（[框架](https://hoop.dev/blog/microsoft-presidio-open-source-pii-detection-and-anonymization-framework)） | PII 检测/匿名化的成熟开源实现，可作分类打标的探测引擎；参考[2026 敏感数据发现工具对比](https://www.bytebase.com/blog/top-open-source-sensitive-data-discovery-tools/) |

**一句话总结选型原则**：**「连接、解析、存储、授权、调度、质量断言」六类轮子直接用现成的；把自研预算全部投到「治理语义层」——策略建模与跨引擎下发、契约强制、血缘可信度管理、指标一致性、审计与工作流。**

---

## 11. 开源生态的空白点（自研的差异化机会）

1. **跨引擎的「策略下发与执行」闭环（最大空白）**
   授权引擎（SpiceDB/OpenFGA/OPA）只解决 PDP；Ranger 有策略模型但偏 Hadoop；Trino/Spark 有执行点但各自为政。**没有任何开源项目能把「一份策略」下发到 Trino + Spark + 数仓 + BI，并在每一层一致执行、且能证明执行了」**。这是自研最值钱的一层。
2. **数据契约的运行时强制（高置信空白）**
   契约格式已经统一且治理中立（**ODCS 3.2.0，Apache-2.0，Bitol/LF 治理**），但开源能力只覆盖四层中的三层半：**CI 左移**（datacontract-cli）、**构建期**（dbt 模型契约，真强制但只覆盖 dbt 模型）、**事后定时**（OpenMetadata / Soda / GX / Deequ）、**broker 侧真运行期**（仅 Redpanda Data Transforms 一个免费实现，且只在流式场景）。
   **真正的空白是：没有任何引擎中立的查询网关/代理，能拦截 SQL（Trino、JDBC、数仓 REST）并按契约拒绝、隔离或改写查询与行；也没有任何东西把契约绑定到数仓写入路径的强制上。**「ODCS 契约 → 策略 → 查询/写入时门禁」在开源里确实无人占据（[分析文章](https://zircote.com/blog/2026/04/most-data-contract-tools-dont-enforce-contracts/)、[datapro](https://www.datapro.news/p/odcs-v3-1-0-settles-the-data-contract-format-without-settling-enforcement)）。**这让「契约即策略、可在写入侧强制执行」成为最清晰的产品切入点。**
3. **引擎无关的异常检测 + 一等公民告警**
   现状：Elementary 有真检测但强绑 dbt；Soda 的检测与告警在 Cloud 且已改 ELv2；DQOps 架构对但 **BSL-1.1 且自 2026-01 停更**；OpenMetadata 最接近但动态断言需约 5 周预热、执行为定时、部署重。**没有开源项目能同时提供「引擎无关的 ML 异常检测」与「生产级告警路由」而不带上述任一缺陷。**
4. **「可信度感知」的血缘**
   血缘是 opt-in 的（Trino/Spark Listener 不配就没有）、跨层 execution-id 不可靠、动态 SQL/UDF/存储过程不透明。**没有开源项目能回答「这条血缘的可信度是多少、覆盖了多少比例的作业」**——OpenLineage 自己也在 2026-08 才提出 Explicit Lineage Facets 来修「每个输出全有或全无、隐式 inputs→outputs 产生笛卡尔边」的问题，且**尚无部分/置信度加权血缘的标准**。自研可做「血缘覆盖率 + 未知态显式建模 + 关联 ID 体系 + 置信度评分」。
5. **治理语义的对象模型（分类 + 术语 + 策略的统一本体）**
   Atlas 有 TypeSystem/Glossary 但上游放缓；Gravitino 有对象模型但**只有 tagging 没有 classification 体系，策略靠下推给 Ranger**；DataHub/OpenMetadata 有分类但不做**执行**。**「分类 → 术语 → 策略 → 执行」的端到端本体与生命周期管理是空白**。
6. **AI/Agent 的上下文治理（新兴且几乎全空）**
   2026 年的新战场：Snowflake 的 Agent Context Layer、Collate 把 OpenMetadata 做成「语义记忆层」（[Futurum 分析](https://futurumgroup.com/insights/collate-turns-openmetadata-into-a-persistent-semantic-memory-layer-for-enterprise-ai-agents/)）、学术界的「可验证上下文层」（[Zenodo](https://zenodo.org/records/20599942)）、社区级 Atlas MCP Server（[apache-atlas-mcp](https://github.com/DanMeon/apache-atlas-mcp)）、DataHub 官方 `mcp-server-datahub`。**但这些都还是「把元数据喂给 Agent」，没有人做「Agent 取数时的权限、血统、可信度与审计治理」**。这是自研最前瞻的差异化方向。
7. **跨引擎质量与可观测性的统一指标面**
   Soda/GX/Elementary/Deequ 各自绑定一种执行引擎或一种转换框架；**没有跨引擎的、统一的「质量指标时间序列 + SLO + 异常检测 + 告警」开源实现**（Griffin 曾想做，但已退役；DQOps 架构对但已停更且是 BSL）。
8. **元数据变更的影响面分析与「变更管理」工作流**
   血缘图能算下游，但**把「变更申请 → 影响面评估 → 审批 → 通知下游 → 回滚」做成闭环的开源项目不存在**。这也是 DataHub/OpenMetadata 都只做了半截的地方。
9. **数据访问的请求-审批-授权-到期回收工作流**
   开源授权引擎只有 PDP，**没有 PAP/PIP**（策略管理点、策略信息点）：申请、审批、时限、定期复核（access review）、审计报告全部空白。SpiceDB 的 Watch API 只是审计的原料，不是审计产品。
10. **多租户 + 数据网格下的治理边界**
   治理平台自身多租户（域/子域/团队）与「数据产品所有权」的建模，没有成熟开源范式。

---

## 12. 事实核查清单（写入决策文档前应复核）

**已通过 GitHub REST API / `releases.atom` / `commits.atom` / PyPI JSON / raw LICENSE 文件实测（2026-10-01）**，星标/版本/最后提交/许可可直接引用：

- **血缘与解析**：sqlglot、sqlparse、Apache Calcite、OpenLineage、Spline、SQLMesh、Marquez、DataHub、sqllineage、Apache Hop、jSQLParser、Apache Egeria
- **授权**：OpenFGA、SpiceDB、Ory Keto、Casbin（现 `apache/casbin`）
- **目录**：CKAN、Magda、Metacat、ODD Platform、Amundsen、Apache Atlas、OpenMetadata
- **数据质量与契约**：Great Expectations（Apache-2.0，已迁至 `fivetran`）、Soda Core（**ELv2**）、Elementary（CLI 与 dbt 包）、dbt-core / dbt-utils / dbt-expectations、Deequ、DataContract CLI（MIT）、ODCS（Apache-2.0）

**仍未核实，必须复核（按优先级）**：

- **🔴 Unity Catalog OSS 的许可**——检索**既未找到许可文本，也未找到变更痕迹**。**不得假设 Apache-2.0**，须亲自读 `LICENSE` 与 `NOTICE`。
- **🔴 Unity Catalog OSS 的开源/商业边界**：血缘、审计、ABAC、行/列过滤、受治理标签、Delta Sharing、Metrics 判定为商业版专属，但这条线在移动（2026 年刚开源 Business Semantics），需逐项重核。
- **🔴 OpenMetadata 的 OSS vs Collate 边界**：未逐项确认异常检测是否都随免费自托管版发布。
- **🔴 Atlas CVE-2026-50622 是否已有修复版本**；2.4.0/2.5.0 精确发布日期；2.6.0 状态；committer 人数。
- **Gravitino / Polaris / Iceberg 星标数**（未取得可靠值）；Gravitino 1.3.0 与 Polaris 1.8.0 精确发布日期。
- **DolphinScheduler 3.4.0 精确发布日期**（现为从项目博客归档推断的 ≈2026-01-22）；**Griffin 0.6.0 发布日期**。
- **Deequ 实际维护健康度**：有版本发布但 2.1.0 以 CI/bot 变更为主，**bus factor 未知**。
- **Soda「v4 取代 SodaCL」的迁移语义**（来自厂商自家公告）；**Redpanda / Confluent 的契约强制细节**（仅单篇博客分析）；**引擎原生约束强制的程度**（Snowflake/BigQuery 的 PK/FK 常为信息性声明）——三者均未独立验证。
- **OpenFGA 的 2026 年 CVE**（CVE-2026-41131、CVE-2026-40293）来自聚合站，须对 **GHSA** 复核。
- **Malloy / Lightdash 许可**；**Spline 对 Spark 4 的支持**；**jSQLParser 许可沿革**；**dbt Fusion 精确条款**；**GSP sidecar 仓库许可与数据出境影响**。
- **Unity Catalog OSS 的 Delta 受管表**：Delta 4.3 确认了 Delta 生态侧支持，但未确认 OSS UC server 自身是否以 Delta 托管表为元数据存储。
- **Metacat 的 UI/Thrift 细节、Magda 完整 ACL 模型**（OPA/Rego 来源为镜像仓库上的内部架构指南）。

**一个方法学提醒**：本报告的主要事实来源在本环境无法直接抓取网页（shell 无出网），仅依赖搜索结果的标题/摘要 + 受委派的子代理通过 GitHub API 等可取通道的实测。**凡涉及「是否还在维护」「许可是什么」这类决策关键项，请以官方仓库为准复核。**

---

## 13. 主要来源索引

| 主题 | 来源 |
|---|---|
| Apache Attic 退役项目总表 | https://attic.apache.org/projects.html |
| Griffin 退役 | https://issues.apache.org/jira/browse/ATTIC-246 ； https://www.mail-archive.com/announce%40apache.org/msg10545.html ； https://projects.apache.org/project.html?attic-griffin |
| Amundsen 归档横幅 | https://raw.githubusercontent.com/amundsen-io/amundsen/main/README.md |
| Atlas 2.5.0 / 2.6 | http://apache.uvigo.es/atlas/2.5.0/ ； https://issues.apache.org/jira/browse/ATLAS-5231 ； https://issues.apache.org/jira/browse/ATLAS-5235 |
| Atlas TypeSystem / 关系提案 | https://issues.apache.org/jira/secure/attachment/12865996/Atlas%20Relationships%20proposal%20v1.5.pdf |
| Atlas 图库迁移 JanusGraph | https://issues.apache.org/jira/secure/attachment/12868987/ATLAS-1757%20Proposal%20to%20change%20graph%20database.pdf |
| Amundsen 架构 | https://raw.githubusercontent.com/amundsen-io/amundsen/master/docs/architecture.md |
| Amundsen Atlas proxy | https://github.com/amundsen-io/amundsen/blob/2a6ba580/metadata/docs/proxy/atlas_proxy.md |
| Marquez 列级血缘 | https://marquezproject.ai/blog/column-lineage-demo/ |
| Marquez LF 毕业 | https://lfaidata.foundation/blog/2024/02/07/lf-ai-data-foundation-announces-graduation-of-marquez-project/ |
| DolphinScheduler 3.4.0 | https://archive.apache.org/dist/dolphinscheduler/3.4.0/ |
| Gravitino 1.0.0 | https://raw.githubusercontent.com/apache/gravitino-site/refs/heads/main/blog/2025-09-24-gravitino-1-0-0-release-notes.mdx |
| Gravitino TLP / 2025 总结 | https://gravitino.apache.org/blog/gravitino-top-level-project/ ； https://gravitino.apache.org/blog/2025-summary/ |
| Gravitino 访问控制 | https://github.com/apache/gravitino-site/blob/main/docs/security/access-control.md |
| Polaris 毕业 TLP | https://www.globenewswire.com/news-release/2026/02/19/3240735/0/en/Apache-Polaris-Graduates-to-Top-Level-Apache-Project.html |
| Unity Catalog 0.3.1 / Business Semantics | https://www.unitycatalog.io/blogs/introducing-unity-catalog-0-3-1-release ； https://www.databricks.com/blog/redefining-semantics-data-layer-future-bi-and-ai |
| Iceberg REST / credential vending | https://iomete.com/resources/blog/iceberg-access-delegation ； https://github.com/apache/polaris/issues/5123 |
| OpenLineage release / explicit facets | https://newreleases.io/project/github/OpenLineage/OpenLineage/release/1.53.0 ； https://openlineage.io/blog/explicit-lineage/ |
| Trino 访问控制 / OpenLineage | https://trino.io/docs/current/security/built-in-system-access-control.html ； https://trino.io/docs/current/admin/event-listeners-openlineage.html |
| Apache Ossie | https://github.com/apache/ossie ； https://www.snowflake.com/en/blog/apache-ossie-open-semantic-interchange-incubator/ |
| ODCS / 契约强制空白 | https://www.datapro.news/p/odcs-v3-1-0-settles-the-data-contract-format-without-settling-enforcement ； https://zircote.com/blog/2026/04/most-data-contract-tools-dont-enforce-contracts/ |
| Ranger 发布与脱敏 | https://issues.apache.org/jira/browse/RANGER-5778 ； https://issues.apache.org/jira/browse/RANGER-4991 |
| Cedar 加入 CNCF | https://www.infoq.com/news/2026/01/cedar-joins-cncf-sandbox/ |
| dbt 许可 | https://www.getdbt.com/licenses-faq |
| Airflow 3.3.x | https://airflow.apache.org/docs/apache-airflow/3.3.2/release_notes.html |
| Dagster 发布 | https://newreleases.io/project/github/dagster-io/dagster/release/1.13.22 |
| Agent 上下文治理 | https://futurumgroup.com/insights/collate-turns-openmetadata-into-a-persistent-semantic-memory-layer-for-enterprise-ai-agents/ ； https://zenodo.org/records/20599942 |
| **Soda Core 改 ELv2** | https://soda.io/blog/soda-core-license-update-moving-to-elastic-license |
| **GX 易主（FICO 收 GX Cloud / Fivetran 托管 Core）** | https://greatexpectations.io/blog/an-update-from-great-expectations/ ； https://www.fivetran.com/press/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project |
| **dbt Core v2 开源路线** | https://raw.githubusercontent.com/dbt-labs/dbt-core/refs/heads/main/docs/roadmap/2026-06-announcing-v2.md |
| **dbt 模型契约（构建期强制）** | https://docs.getdbt.com/docs/collaborate/govern/model-contracts |
| **ODCS 变更日志（3.2.0）** | https://github.com/bitol-io/open-data-contract-standard/blob/main/CHANGELOG.md |
| **契约工具不强制契约（四层分析）** | https://zircote.com/blog/2026/04/most-data-contract-tools-dont-enforce-contracts/ |
| **OpenMetadata 异常检测 / OSS vs Collate** | https://docs.open-metadata.org/v2.1.x-SNAPSHOT/how-to-guides/data-quality-observability/anomaly-detection |
| **Elementary OSS vs Cloud** | https://docs.elementary-data.com/cloud/cloud-vs-oss |
| **sqlglot 列级血缘 API** | https://sqlglot.com/sqlglot/lineage.html |
| **Calcite 列来源元数据** | https://raw.githubusercontent.com/apache/calcite/main/core/src/main/java/org/apache/calcite/rel/metadata/RelMdColumnOrigins.java |
| **OpenLineage 列级血缘 facet** | https://openlineage.io/spec/facets/1-2-0/ColumnLineageDatasetFacet.json |
| **DataHub SQL 解析能力与限制** | https://docs.datahub.com/docs/lineage/sql_parsing |
| **DataHub 策略（查询时下推仅 Cloud）** | https://docs.datahub.com/docs/authorization/policies |
| **OpenFGA 父子对象建模 / ABAC** | https://openfga.dev/docs/modeling/parent-child ； https://openfga.dev/docs/best-practices/modeling-abac |
| **SpiceDB caveats** | https://authzed.com/docs/spicedb/concepts/caveats |
| **Netflix 在生产中用 SpiceDB 做 ABAC** | https://netflixtechblog.com/abac-on-spicedb-enabling-netflixs-complex-identity-types-c118f374fa89 |
| **Casbin 进入 Apache 孵化器** | https://incubator.apache.org/clutch/casbin.html |
| **Unity Catalog OSS main/branch-0.4 分叉** | https://github.com/unitycatalog/unitycatalog/issues/1477 |
| **Unity Catalog OSS 权限模型** | https://books.japila.pl/unity-catalog-internals/server/PermissionService/ |
| **Trino 访问控制 SPI（行过滤/列脱敏）** | https://javadoc.io/static/io/trino/trino-main/373/io/trino/security/AccessControl.html |
| **Airflow Listener 插件** | https://airflow.apache.org/docs/apache-airflow/3.3.0/howto/listener-plugin.html |
| **Airflow 3.3 新特性** | https://www.astronomer.io/podcast/whats-new-in-apache-airflow-3-3/ |
