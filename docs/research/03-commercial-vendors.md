# 商业数据治理 / 数据目录平台对比调研

> 调研对象：海外治理型平台（Collibra / Alation / Informatica / IBM）、现代目录与可观测性厂商（Atlan / Select Star / Datafold / Monte Carlo / Bigeye）、开源质量的商业侧（Great Expectations / Soda / Elementary / dbt）、云厂商（Microsoft Purview / Google Knowledge Catalog / AWS Glue + DataZone）、国内数据中台厂商（DataWorks / Dataphin / DataLeap / WeData / 星环 / 袋鼠云等）。
> 调研日期：2026-10-01 ｜ 用途：为自建通用数据治理平台提供**功能取舍与借鉴点**参考。
> 配套：开源侧见 [01-openmetadata.md](01-openmetadata.md)、[02-datahub.md](02-datahub.md)。逐条原始取证见 `_raw/`。

---

## 0. 方法与局限（必读）

本次环境**网络出口受限**，检索工具**只返回标题 + URL、不返回网页正文**。因此：**机制性结论**（模块、计费单位、元数据模型、血缘路径、并购与更名）由官方 URL 与权威媒体标题交叉印证，可信度较高；**所有具体单价**均来自第三方聚合站或厂商委托报告，一律标 `(第三方) 待核实`，**不得直接用于预算建模**；标 `(厂商内容)` 者存在竞品立场。

---

## 1. 先看结论：已发生的六个市场变化

1. **Informatica 已被 Salesforce 以 $80 亿收购，2025-11-18 完成并从 NYSE 退市**（(官方) [Salesforce](https://www.salesforce.com/jp/news/press-releases/2025/11/19/salesforce-completes-acquisition-of-informatica/)）。**风险性质需重新定性**：收购意图持续一年以上（2024-04 路透即报道双方 advanced talks、当次未成），交易由 $60 亿无担保定期贷款支持 → **产品线被立即砍掉的风险较低，但治理产品将深度绑定 Salesforce Data Cloud 生态，锁定风险从"厂商倒闭"转为"生态绑定"**。
2. **Select Star 已被 Snowflake 收购**，官方措辞为收购 "the Select Star Technology" 并并入 **Horizon Catalog**（(官方) [Snowflake](https://www.snowflake.com/en/blog/snowflake-acquire-select-star/)；(媒体) [InfoWorld](https://www.infoworld.com/article/4095809/snowflake-to-acquire-select-star-to-enhance-its-horizon-catalog.html)）。"买目录 = 绑定单一云厂商"从风险变为事实。
3. **IBM 收购 Manta**，把深度列级血缘/静态解析能力并入 watsonx.data intelligence（(第三方) [Crunchbase](https://www.crunchbase.com/acquisition/ibm-acquires-manta-tools--f7c0435b)）。
4. **三家云厂商全部在更名**：Azure Purview→Microsoft Purview；Google Data Catalog→Dataplex Universal Catalog→**Knowledge Catalog**；Amazon DataZone→**SageMaker Catalog**（(官方) [Google 过渡](https://docs.cloud.google.com/knowledge-catalog/docs/transition-dataplex-universal-catalog)、(官方) [AWS 域升级](https://docs.aws.amazon.com/datazone/latest/userguide/upgrade-domain.html)）。迁移与重学成本必须计入 TCO。
5. **AWS DataZone 2024-11 取消按用户订阅费，改用量计费**（(官方) [What's New](https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-datazone-pricing-removes-user-subscription-fee/)）。"AWS 按人头收费"的对照假设已失效。
6. **开源侧路线图同样失控**：Amundsen 于 **2026-09 归档**（[仓库](https://github.com/amundsen-io/amundsen)）；GX Core 与开源社区于 **2026-05 转由 Fivetran 接管**（(官方) [Fivetran](https://www.fivetran.com/de/press/fivetran-to-become-steward-of-the-great-expectations-open-source-community-and-gx-core-project)）。

> 「治理」在海外已被 Gartner 定义为**独立软件品类**（首届《Magic Quadrant for Data and Analytics Governance Platforms》2025，Collibra、Informatica、IBM 各自公告入选 Leader；完整名单 `待核实`，[Gartner 文档页](https://www.gartner.com/en/documents/6059363)）。国内则普遍把治理做成开发平台内的模块——这是 §3.8 范式差异的起点。

---

## 2. 海外厂商逐个卡片

### 2.1 Collibra

| 字段 | 内容 |
|---|---|
| 定位与客户 | 治理/目录开创者（2008 年成立，2021-11 估值 US$52.5 亿），产品为 **Data Intelligence Cloud**，自我定位"数据与 AI 的 **system of engagement**"；客户为大型企业、金融、医疗、公共部门，采购驱动方常是治理与合规部门。**FedRAMP 需加限定**：只有「**Collibra Platform for Government (CPG)**」在 FedRAMP Marketplace 上（ID `FR1706403967`），**商业版 SaaS 是否同样获授权 `待核实`**——受监管客户若假设商业版等价合规会踩坑。 |
| 价格 | **无公开 list price**，AWS Marketplace 上架页标 **Private Offer Only**（[链接](https://aws.amazon.com/marketplace/pp/prodview-wyy5ooi6xfwrk)）。可确认的计费结构：**命名用户 + 分级许可**（官方管理指南含 Viewer license 表），**Data Quality 为单独加购**（DQ Addendum / Rapid Start / Ignite）。第三方数字：$120k–170k/年起（(第三方) 待核实 [ComparEdge](https://comparedge.com/tools/collibra/pricing)）；另有 "$14,167–16,500/user/month" 量级自相矛盾、**不建议引用**；**"按资产数计费"未获官方证实**。 |
| 核心模块 | 目录、术语表、策略/规则库、**工作流引擎**、列级血缘、Data Quality & Observability（源自 **2021-02-03** 收购 OwlDQ 的预测型 DQ）、**Collibra Protect**（数据访问治理，是 2025-06 收购 Raito 的落点）、**Data Marketplace（含"数据篮"）**、数据隐私（**CCPA 与 GDPR 为分别授权的模块**——合规能力按法规逐项加购，是价格结构的直接证据）、Assessments、CCSH 自托管与 Edge；**实施（公共部门实施、驻场架构师等）是独立售卖的服务线**。 |
| 差异化所长 | **可配置元模型（Operating Model）+ 内置工作流**：Community / Domain / Asset Type / Relation 构成可裁剪的治理骨架，审批与认证由引擎执行——治理是一套可演进的模型，不是一张表。【[工作流文档](https://developer.collibra.com/workflows/workflows.md)、[审批示例](https://developer.collibra.com/workflows/out-of-the-box-workflows-walk-throughs/approval-process.md)、[Data Marketplace](https://www.collibra.com/products/data-marketplace)】 |
| 弱点/抱怨 | 价格高且不透明、实施重；**"买而不用"**——用户称"实施没问题，真正的困难是让用户参与进来"（(第三方) [PeerSpot](https://www.peerspot.com/products/collibra-platform-room-for-improvement)）；概念体系重。 |
| 技术线索 | SaaS 为主，另提供 **CCSH 自托管 + Edge**（K8s/Helm，需客户自建集群）实现"采集侧落地、元数据回传云"（[Edge 指南](https://productresources.collibra.com/docs/cpsh/latest/Content/Edge/to_edge.htm)）；血缘为 **harvester → stitching → 渲染三段式**，支持 JSON 自定义血缘模型；**Jobserver 已于 2024-09-30 EOL，但迁移并非一次性事件**——2026 年初仍有政府在采购"Job Server Migration Services"，存量客户被迫迁移 Edge；官方另维护"不同部署形态能力不对等"的 [Feature availability 对照表](https://productresources.collibra.com/docs/cpsh/latest/Content/ReleaseNotes/ref_feature-availability.htm)。官方博文《From pilot to payoff: why successful data governance takes time》**等于厂商自认治理见效慢**。元数据底座对外称**知识图谱**并提供 [Knowledge Graph API](https://developer.collibra.com/api/guides/knowledge-graph/examples.md)；**血缘支持 OpenLineage 列级 facet**；2026-09 在 Neo4j GraphSummit 发布面向 AI Agent 的运行时治理（图存储选型**未确认**）；已发布 **MCP Server**（官方博客）；**AI Copilot 仍标 in preview**。四年内两次并购（OwlDQ 2021-02、Raito 2025-06）说明能力版图仍靠收购补齐——**买来的是拼装能力，集成成本与路线图连续性都有风险**。 |

### 2.2 Alation

| 字段 | 内容 |
|---|---|
| 定位与客户 | 以**行为元数据**起家的目录，主张"元数据从使用行为中长出来"；用户以分析师与数据管家为主。2025 年产品名已变为 **Alation Agentic Data Intelligence Platform**（Gartner Peer Insights 产品名，属更名一手证据），并于 **2025-05 收购 Numbers Station**（AI/LLM）——这是理解其 AIOS/Agentic 叙事的关键动作（成立年份与总部**待核实**）。 |
| 价格 | **无公开 list price**。唯一可引用锚点：厂商委托 Forrester TEI 载明"**300 名用户对应年费 US$246,000**"（≈ **$820/用户/年**），为 2019 年数据、需按当前口径复核（[TEI PDF](https://www.alation.com/wp-content/uploads/Forrester-TEI-Alation-Final-10.08.2019.pdf)）。许可单位为**命名用户 + 角色分层**（GSA 价目表中出现 `CL-ENTR-SUB-SW-150 … Named User - Creator Tier 4`，单价未取到）。融资 **2022-11 Series E US$123M、估值 $17 亿**（Thoma Bravo 领投，(媒体) [Reuters](https://www.reuters.com/5f374b32b07b/markets/us/data-provider-alation-valued-17-bln-after-thoma-bravo-backed-fundraise-2022-11-02/)）——**并非被收购**。 |
| 核心模块 | 目录与搜索、**Behavioral Analysis（查询日志驱动的人气与 Join 推荐）**、**Trust Flags（认证/警告/弃用）**、Data Governance App（术语与策略审批）、血缘、数据质量（开放 DQ 框架）、**Data Products Marketplace**（受治理数据产品的消费入口）、Alation AI/Agent（Agent Studio / Agent Builder；**是否提供 MCP Server `待核实`**）、Connected Sheets。 |
| 差异化所长 | **查询日志驱动的行为元数据**：把真实 SQL 使用行为经 [Query Log Ingestion](https://www.alation.com/docs/en/latest/datasources/AddDataSources/QueryLogIngestion.html) 喂进 [Active Metadata Graph](https://www.alation.com/product/active-metadata-graph/)，产出人气排序、常用 Join 与"该被认证的表"建议——**用行为解决冷启动，而非靠行政命令要求填元数据**。（注：人气排序与自动推荐 Steward 的**机制细节**本次仅有产品页标题的强力暗示，官方文档原文未取到，`待核实`。） |
| 弱点/抱怨 | 价格高、推广重（"目录采纳难"是品类通病，(厂商内容) [Atlan 分析](https://atlan.com/alation-data-catalog-adoption-challenges/)）；对非技术用户仍有门槛。 |
| 技术线索 | **Alation Cloud Service 与 Customer-Managed Alation（客户自管/本地，保留 on-prem 版本节奏）双形态**（[部署形态](https://docs.alation.com/en/latest/welcome/CloudAndOnPrem/index.html)）；**搜索引擎为 Elasticsearch（官方确认），版本锁定 7.4 且需客户侧独立升级、与平台升级强耦合**，索引重建/HA 设计同样是客户侧作业；底层云为 AWS（由官方状态页 AWS 故障事件侧证）；元数据图 + OCF/QLI 双摄取通道，**视图级血缘仍为 Beta**；以 **Open Data Quality Initiative** 开放接入第三方 DQ 引擎；Agent Studio 采用 **Zero Data 架构**（承诺第三方 LLM 侧不留数据）。**最实用的一条工程线索是 View-Based QLI**——拿不到原始查询日志时，可把一张 **SQL 视图**当作 QLI 数据源，这对多数无法取得原始日志权限的企业是复刻"查询日志驱动行为分析"的关键（适用范围 `待核实`）。 |

### 2.3 Informatica（IDMC / CDGC / Axon）

| 字段 | 内容 |
|---|---|
| 定位与客户 | 老牌全栈：CDGC（云数据治理与目录）+ Axon（治理与政策）+ 数据质量 + MDM + 数据市场；客户为超大型企业与受监管行业。**已被 Salesforce 收购**。 |
| 价格 | **IPU（Informatica Processing Unit）消费制 + 询价**，无公开单价（(第三方) 待核实 [Automation Atlas](https://automationatlas.io/answers/informatica-pricing-explained-2026/)）→ **成本随用量增长且不可预测**。 |
| 核心模块 | 目录与元数据、术语表与政策、**CLAIRE 元数据驱动 AI 引擎**、数据质量、MDM、参考数据、数据市场、**Catalog of Catalogs（联邦目录）**、列级血缘。 |
| 差异化所长 | **治理与数据搬运在同一平台内闭环**，两条硬证据：(1) **CDGC 的知识图谱跑在 Amazon Neptune 上**——AWS 官方博客公开了其图存储选型，是四家中**唯一被官方确认的图数据库**（[AWS Database Blog](https://aws.amazon.com/cn/blogs/database/how-informatica-cloud-data-governance-and-catalog-uses-amazon-neptune-for-knowledge-graphs/)）；(2) **CDAM 的访问策略在 Cloud Data Integration 内部被物理执行**（CDI 转换文档中出现 `_cdam_` 前缀的注入列）——即**策略不是"记录在目录里"，而是被编译进实际数据流**。这解释了为何其治理比纯目录产品"更硬"，也是自建平台最难对标的一环（需同时拥有数据搬运能力）。另有 CLAIRE 以元数据特征驱动分类与匹配。 |
| 弱点/抱怨 | IPU 用量与总价难预测——**厂商自己发布"如何优化成本/峰值负载"的教程，通常对应真实的账单失控投诉**，官方还专门发了《The Truth About Informatica Pricing》回应市场抱怨（该博文存在本身即价格不透明的证据）；产品线庞杂、**Axon 与 CDGC 长期并行销售**，市场上甚至存在专门的 Axon→CDGC 迁移工具，说明重叠确实困扰客户；一条可引用的 AWS Marketplace 评论称"**目前没看到任何 ROI 或实际收益**"；PowerCenter EOL 引发被迫迁移，且第三方材料把支持终止指向 **2026 年**（`待核实`，以官方公告为准）——**这是一次正在发生、有明确时间压力的迁移浪潮，也是自建方案争取预算的天然窗口期**；被 CRM 厂商收购后治理线的生态绑定风险见 §1（(媒体) [Fortune](https://fortune.com/2025/12/06/informatica-ceo-amit-walia-why-8-billion-merger-with-salesforce/)）。 |
| 技术线索 | IDMC 为多云 SaaS（POD 化、支持 AWS PrivateLink / Azure Private Link；**已作为 Microsoft Azure Native ISV Service 交付，且 CDGC 另有 Azure 版数据表**——多云交付有官方依据，是"避免单一云绑定"的可论证优势）；**图数据库为 Amazon Neptune（官方确认）**；本地侧通过 **Secure Agent 部署在客户 VPC**，另有 Customer Managed（含 **GovCloud 版**）、**BYOK**、**自带 Kubernetes 集群**等形态（[Runtime Environments](https://docs.informatica.com/content/dam/source/GUID-E/GUID-E20198F3-49C4-4FF5-B34D-A11A8E7CA3DF/45/en/IICS_July2025_RuntimeEnvironments_en.pdf)）；元数据由 **Scanner** 批量采集；血缘为**双机制**：元数据规则/Scanner 驱动 + **AI 推断血缘**；CDGC 实施本身按 PSU（专业服务单元）分级单独计价。内部元数据仓库与搜索引擎选型 `待核实`。 |

### 2.4 IBM watsonx.data intelligence

| 字段 | 内容 |
|---|---|
| 定位与客户 | 由 IGC → IBM Knowledge Catalog → watsonx.data intelligence 演进；主打**混合云与受监管行业**。 |
| 价格 | **未公开**，随 Cloud Pak for Data / watsonx 订阅或用量计价，`待核实`。 |
| 核心模块 | Knowledge Catalog、术语表、数据质量、血缘（**Manta 引擎**）、数据产品与治理、与 watsonx.data 集成。 |
| 差异化所长 | **Manta 的"自动"血缘：靠解析代码/脚本/字节码自动连边，而非人工映射**。机制（均来自官方 Manta 文档）：自动提取并分析 **DDL 脚本**；把 **Db2 PL/SQL 脚本**投递到 `input/db2/` 即可解析；列级追踪可经 `@MANTALineage` 代码注解锚定；**Java / C# 应用级扫描器**有独立学术论文描述其调用上下文数据流分析（ICSE-SEIP 2024）。这条路径接近**静态代码分析**，在存储过程/ETL 脚本密集的传统数仓里覆盖率高且**不依赖运行时流量**——**只做 SQL 解析会漏掉存储过程、Java 批处理与报表层**。 |
| 弱点/抱怨 | **最强证据来自 IBM 自己的文档**：官方故障排查条目"**购物车中查看对象很慢**"且跨版本长期存在；**血缘图渲染失败需管理员手工重同步**；"**从 Hive 连接导入血缘元数据时不会添加任何资产**"（血缘空洞）；已知问题含"**未授权用户可能访问 profiling 结果**"等安全相邻项；运维是常态负担（重启 lineage pods、发布 gate timeout）；产品命名边界混乱（Knowledge Catalog / watsonx.data intelligence / 遗留服务计划页 / Manta Data Lineage / Data Product Hub / Software Hub 多套名称并存）。另有一篇 Aalto 硕士论文记录受访者对推荐 Manta "持保留意见"。**另有一个易被忽略的"定位可见性"问题**：Gartner Peer Insights 把 `watsonx.data intelligence` 归入「**augmented data quality solutions**」市场、本地版 CP4D 归入「**data preparation tools**」——**两个品类都不对口目录/治理买家**，导致 IBM 很难出现在同一张选型对比表里。这对买方是"短名单看不见"，而非产品质量问题。 |
| 技术线索 | **双形态交付**：SaaS（IBM Cloud，多区域）与**自托管（作为 IBM Software Hub 服务部署在 Red Hat OpenShift 上）**——**这是四家中唯一提供完整私有化的**，另有 Fusion HCI 一体机形态；治理服务与 Db2 系同栈分发，**数据质量执行由 DataStage 承载**；分类侧提供 **160 个预置 data classes**；支持业务术语审批工作流与数据保护规则（PDP/PEP）。**关键开放性**：Manta 支持 **OpenLineage 事件摄取**（事件文件放入 `temp/openlineage/`），提供 **Open Manta** 导出格式，且血缘**可导出到 Collibra 与 Alation**——这意味着 **Manta 可被当作独立"血缘引擎"解耦使用**，对自建方案是重要参考。治理域已暴露 **MCP server** 集成锚点。内部搜索引擎与元数据仓库选型 `待核实`。 |

### 2.5 Atlan

| 字段 | 内容 |
|---|---|
| 定位与客户 | 现代目录 / **"Context Layer for Enterprise AI"**；客户为中大型企业，采购方多为数据平台与治理团队，主打协作体验。 |
| 价格 | **无公开 list price**；第三方估算起步约 **US$100k/年**（(第三方) 待核实 [ComparEdge](https://comparedge.com/tools/atlan/pricing)）。融资 **2024-05 Series C US$105M、估值 US$750M**（(官方) [BusinessWire](https://www.businesswire.com/news/home/20240508754010/en/)）。 |
| 核心模块 | 目录与搜索、**Data Asset 360°**、列级血缘、术语表、**Apps Framework**、**Playbooks（无代码自动化）**、自定义元数据、Atlan AI、MCP Server。 |
| 差异化所长 | **元数据"可编程"**：Apps Framework 允许在平台内构建自定义应用与界面，Playbooks 把"资产被认证时自动打标/通知/建单"做成无代码自动化。官方为其单列 `apps-framework`(17 篇) 与 `metadata-lakehouse`(51 篇) 标签，说明是**一等公民能力而非插件**。【[构建应用](https://docs.atlan.com/product/capabilities/build-apps)、[Asset 360°](https://atlan.com/data-asset-360/)】 |
| 弱点/抱怨 | 价格高、隐藏成本（实施/集成/培训被第三方单列，(第三方) 待核实 [CostBench](https://costbench.com/software/data-catalog/atlan/hidden-costs/)）；采纳率仍是通病。 |
| 技术线索 | SaaS 多租户 + **私有连接** + **自部署运行时**的混合采集（[官方](https://docs.atlan.com/platform/concepts/private-connectivity/private-connectivity)）；图库/搜索引擎未确认，`待核实`；有 MCP Server 与 Google ADK 集成。 |

### 2.6 Select Star

| 字段 | 内容 |
|---|---|
| 定位与客户 | **全自动**目录 + 列级血缘 + 治理，"零人工维护"；客户为中大型企业。**2025-11 被 Snowflake 收购并入 Horizon Catalog**。 |
| 价格 | **无公开 list price**；第三方估算约 **US$300/用户/月**（(第三方) 待核实 [CostBench](https://costbench.com/software/data-catalog/select-star/calculator/)）→ **按用户计价，全员推广时成本最陡**。 |
| 核心模块 | 自动目录与搜索、自动列级血缘、自动 PII 检测（官方标 **beta**）、Snowflake Horizon 联动、Chrome 扩展、MCP。 |
| 差异化所长 | **以仓库查询日志为主的血缘与使用度来源**，深度绑定 Snowflake `ACCESS_HISTORY` 类审计日志，实现"不让人填、自动就有"（[工作机制](https://www.selectstar.com/resources/how-does-select-star-work)）。**代价：血缘质量与日志保留期强绑定，仓库外 ETL 会断链**。 |
| 弱点/抱怨 | 独立路线图风险已实现；治理能力偏薄（第三方常定位为"血缘强、治理弱"）；PII 检测仍为 beta（[官方文档](https://docs.selectstar.com/data-management/automated-pii-detection-beta)）。 |
| 技术线索 | 纯 SaaS，无自托管（`待核实`）；血缘基于**查询日志解析**而非静态 SQL；AI 能力据第三方称以 **MCP** 形式对外开放（(第三方) 待核实，**官方页面未确认**）。 |

### 2.7 Datafold

| 字段 | 内容 |
|---|---|
| 定位与客户 | **data reliability / data diff**，受众为 analytics engineer 与数据工程师（尤其 dbt 用户），主张把数据正确性变成 **CI/CD 门禁**。 |
| 价格 | **无公开 list price**；官方 Resource Management 文档表明存在资源限额，推断与**比对作业消耗**强相关（[官方 FAQ](https://docs.datafold.com/faq/resource-management)）→ 成本随使用频率增长。融资 US$20M（(媒体) [VentureBeat](https://venturebeat.com/business/data-reliability-platform-datafold-raises-20m)）。 |
| 核心模块 | **Data Diff（行级比对，可定位到具体主键行的增删改）**、列级血缘、数据对账、**CI for data（dbt PR 门禁）**、开源 `data-diff`、AI Agents。 |
| 差异化所长 | **主动验证而非被动监控**：在 PR 阶段做行级比对并阻断合并，把"数据回归测试"提升为与代码单测同等的门禁（[官方](https://docs.datafold.com/data-diff/what-is-data-diff)）。**这是最容易被自研替代的一块**——算法已开源，价值在与自家调度/CI 的集成。 |
| 弱点/抱怨 | 不是目录/治理产品（无术语表、无治理流程）；不接 CI 就无价值；与 dbt tests 及开源 `data-diff` 功能重叠，付费理由需自证（(第三方) [PeerSpot](https://www.peerspot.com/products/datafold-reviews)）。 |
| 技术线索 | SaaS + 开源 `data-diff`；**直连仓库执行分片/校验和比对**，产生真实仓库查询成本；元数据来自 dbt manifest + SQL 解析；无自托管（`待核实`）。 |

### 2.8 Monte Carlo

| 字段 | 内容 |
|---|---|
| 定位与客户 | **数据可观测性开创者**，提出 "data downtime"；2025 年起扩展为 **"Data + AI Observability"**（同一控制面覆盖数据、ML 模型与 AI Agent）；客户为大型企业数据平台团队。 |
| 价格 | **无公开 list price**，Start / Scale 两档**订单表单 + 询价**（(官方) [Start 档](https://montecarlo.ai/pricing/start-order-form/)）→ 按资产/表规模计价，成本随数据增长。融资 **2022-05 Series D US$135M、估值 US$1.6B**（(媒体) [TechCrunch](https://techcrunch.com/2022/05/24/monte-carlo-raises-135m-series-d-at-1-6b-price-showing-that-unicorn-rounds-are-still-a-thing/)）。 |
| 核心模块 | 无阈值 ML 自动监控、五支柱（freshness/volume/schema/distribution/lineage）、血缘、**Incident IQ**、Sample & Reproduce（问题行抽样复现）、Data Product Dashboard、AI Agents、Agent/模型可观测性。 |
| 差异化所长 | **血缘驱动的告警降噪 + incident 协作闭环**。自动阈值已成品类标配；难复制的是用血缘判断"根因在上游、影响在下游"，把 N 条告警收敛成 1 个 incident，并让协作与复盘发生在同一工作台（[Automated Monitoring](https://docs.getmontecarlo.com/docs/automated-monitoring)、[Incident IQ 发布](https://www.businesswire.com/news/home/20210714005290/en/)）。 |
| 弱点/抱怨 | 价格高、对中小团队不友好（(第三方) [Sparvi](https://sparvi.io/blog/monte-carlo-alternative-small-teams)）；ML 检测需持续调参与白名单，误报调优是长期负担；产品线扩张带来"焦点漂移"担忧。 |
| 技术线索 | SaaS 多云多区域（官方自述从 AWS 走向多云，[Redefining Hosting](https://montecarlo.ai/blog-redefining-hosting-a-customer-driven-journey-to-better-deployments/)）；读取仓库元数据与查询历史、**不搬运业务数据**（抽样复现除外）；时序 ML 异常检测；无自托管。 |

### 2.9 Bigeye

| 字段 | 内容 |
|---|---|
| 定位与客户 | 数据可观测性平台（Toro Data Labs），核心主张 **autothresholds（自动阈值）**；官方披露过 **5 万张表**数据湖的监控实践。 |
| 价格 | **无公开 list price**；第三方估算 Starter **US$45k/年**起（(第三方) 待核实 [ComparEdge](https://comparedge.com/tools/bigeye/pricing)）。融资 US$45M Series B（2021-09，(媒体) [BusinessWire](https://www.businesswire.com/news/home/20210923005170/en/)）。 |
| 核心模块 | Autothresholds、依赖驱动监控、**Bigconfig（配置即代码）+ `bigeye-cli`**、agent 式连接、freshness/volume/schema 监控。 |
| 差异化所长 | **"自动学习"与"可编程导出"同时提供**：autothresholds 解决冷启动，Bigconfig/CLI 把全部监控定义导出为 YAML 进版本控制并批量下发——**自动推断降本 + as-code 保证可审计、可迁移**（[Anomaly Detection](https://www.bigeye.com/platform/anomaly-detection)、[Bigconfig](https://www.bigeye.com/blog/bigconfig-empowers-data-teams-to-implement-data-reliability-at-scale)）。 |
| 弱点/抱怨 | 品牌被 Monte Carlo 挤压，常被定位为"更便宜的替代"；**融资停在 2021 年**，独立性与长期投入存疑（`待核实`）；真实用户抱怨原文未取证。 |
| 技术线索 | SaaS + **客户侧 agent 采集**（可穿透私有网络，官方文档可配置 agent 内存等参数）；元数据与血缘以连接元数据 + 查询历史为主；无完全自托管（`待核实`）。 |

### 2.10 数据质量侧：Great Expectations / Soda / Elementary / dbt

| 厂商 | 定价 | 核心模块 | 所长 | 弱点 | 技术线索 |
|---|---|---|---|---|---|
| **Great Expectations** | GX Core（Apache-2.0）免费 + GX Cloud 分层（**金额待核实**，[定价页](https://greatexpectations.io/pricing/)） | Expectation/Suite/Checkpoint/Actions/Data Docs | **表达力最强**：可写任意 Python 期望，覆盖业务规则；离线/在线同套期望 | 必须写代码，业务方无法自助；**多期望触发多次全表扫描**（[官方 Discourse](https://discourse.greatexpectations.io/t/multiple-full-data-scans-when-validating-a-dataframe-with-multiple-expectations/2294/2)）；0.18→1.x **破坏性重写** | Python；Store 可插拔；**2026-05 起由 Fivetran 接管社区与 GX Core** |
| **Soda** | Soda Core（Apache-2.0）免费 + Soda Cloud 分层（**金额待核实**，[定价页](https://soda.io/pricing)） | **SodaCL（YAML 检查语言）**、跨表/跨源比对、契约验证、GitHub Action | **把写检查的门槛从工程师降到分析师**；跨表引用完整性是开源侧少见的原生能力 | OSS 无 UI/调度/告警/协作；v3→v4 换代而文档仍以 v3 组织，版本错配陷阱 | Python CLI；`configuration.yml` + `checks.yml`；按数据源分包安装 |
| **Elementary** | 开源 dbt package 免费可自托管 + Cloud（**金额待核实**，[定价页](https://www.elementary-data.com/pricing)） | dbt 测试、异常检测、freshness/volume/schema、血缘来自 dbt DAG、`edr` CLI | **计算落在客户数仓内**：结果写回自家仓库 → 零数据出域、零额外采集管道、可完全自托管 | **强绑定 dbt**，dbt 之外的 pipeline 覆盖弱或缺失；OSS 与 Cloud 的能力差异被官方显式承认；结果写回数仓 `elementary` schema 带来额外权限与计算成本 | dbt artifacts + 数仓信息模式；血缘用编译期 DAG，不做列级推断 |
| **dbt** | dbt Core 开源 + dbt Cloud（Starter ≈ **US$100/用户/月**，(第三方) 交叉验证，[定价页](https://www.getdbt.com/pricing)） | 模型契约、tests、docs、**Explorer**、**Mesh（跨项目血缘）** | **契约与模型定义同源**：血缘来自编译器产物而非日志推断，准确性天然更高 | Explorer/Mesh 属 Cloud 档位；契约仅对 table/incremental 生效且需显式声明全部列 | SQL + YAML + Git；manifest 即元数据 |

> 共性结论：**它们把"检查"做得很好，把"治理"留给了别人**——术语表、审批、分类分级、合规留痕、事故管理在开源层普遍缺位（详见 §6）。

### 2.11 Microsoft Purview

| 字段 | 内容 |
|---|---|
| 定位与客户 | 微软生态内的**统一数据治理与安全合规平台**，须区分**安全合规侧**（敏感度标签、DLP，随 M365 E5 授权）与**治理侧**（Data Map + Unified Catalog，按 Azure 资源独立计费）。客户数据已在 Azure/Fabric/M365。 |
| 价格 | Data Map 经典版按 **Capacity Unit（CU）** 小时消耗（1 CU ≈ 1 小时 × 4 vCore/16GB 资源组），2025 年起引入**弹性 Data Map（PAYG）**；治理侧另有按用户/按"受治理资产"许可。**单价待核实**（(官方) [经典 Data Map 定价指南](https://learn.microsoft.com/en-us/purview/data-gov-classic-pricing-data-map)、[定价指南](https://learn.microsoft.com/en-us/purview/concept-guidelines-pricing)）。 |
| 核心模块 | Data Map（多源扫描 + 自动分类）、敏感信息类型（SIT）与敏感度标签、血缘（静态解析 + Atlas 血缘 API 运行时推送）、**Data Estate Health**、Unified Catalog（**Data Products**、术语表、数据质量、数据访问策略）、Fabric/OneLake 集成。 |
| 差异化所长 | **标签贯穿整个 M365 工作流**：同一枚敏感度标签既驱动 Data Map 分类分级，又驱动 Office/Teams/SharePoint 的 DLP 与内部风险管理——**分类结果作用于数据本身并随数据流动**，这是 Dataplex 与 AWS 无法复制的护城河。【[Unified Catalog 数据产品](https://learn.microsoft.com/en-us/purview/unified-catalog-data-products-create-manage)】 |
| 弱点/抱怨 | PAYG 化后被直称"**成本陷阱**"，计算费用持续累积且需专门监控（(第三方) [stacho.blog](https://stacho.blog/2026/02/17/microsoft-purview-im-pay-as-you-go-modell-kostenfalle-oder-notwendige-evolution/)）；经典门户→Unified Catalog 迁移造成概念割裂；**合规侧标签与治理侧扫描是两套判定来源**，一致性需人工维护。 |
| 技术线索 | 底层沿用 **Apache Atlas** 类型系统，可经 Azure Event Hubs 读写 **Atlas Kafka topics** 程序化注入元数据与血缘（[官方文档](https://raw.githubusercontent.com/MicrosoftDocs/azure-docs/d6c024c8112479553b79031642601aeedc7b12f9/articles/purview/manage-kafka-dotnet.md)）；提供 Data Plane REST API 与自定义连接器加速器；**无官方 OpenLineage 原生集成、需自建桥接（`待核实`）**；**纯 SaaS，不可自托管**。 |

### 2.12 Google Dataplex Universal Catalog → Knowledge Catalog

| 字段 | 内容 |
|---|---|
| 定位与客户 | GCP 原生、以 BigQuery 为重心的统一元数据层，形态更接近**可编程的"元数据基础设施"**而非端到端治理套件；客户数据已在 BigQuery/BigLake/GCS。 |
| 价格 | **多计量项叠加**：元数据存储（按对象数）+ API 调用（按百万次）+ 数据质量（DPU-hour）+ 血缘（按血缘事件/处理量）+ 画像（按 GB/DPU）。**单价全部待核实**（[定价页](https://cloud.google.com/dataplex/pricing)）；新旧计费段落在同一页面并存。 |
| 核心模块 | Entry / Entry Group（元数据组织与权限边界，**支持注入自定义来源**）、**Aspect Type（用户自定义元数据 schema）**、Business Glossary、Data Products、列级血缘（Data Lineage API）、Data Quality 与 Auto Data Quality、Profiling。 |
| 差异化所长 | **Aspect Type + Entry Group 的可扩展元数据模型**：企业自定义 aspect 类型并绑定到 GCP 原生资产或**通过 API 注入的外部系统条目**——元数据平台可被"编程使用"，不被厂商预置字段锁死。【[注入自定义来源](https://docs.cloud.google.com/knowledge-catalog/docs/ingest-custom-sources)、[元数据总览](https://docs.cloud.google.com/knowledge-catalog/docs/metadata-overview)】 |
| 弱点/抱怨 | **两级更名**致存量文档与 URL 大面积失效，第三方专以 "Rename, Limits, Metering" 作为分析卖点（(第三方) [Atlan](https://atlan.com/know/ai-agent/gcp/google-knowledge-catalog/)）；计费项碎片化；血缘以 GCP 内为主，官方专列[血缘注意事项](https://docs.cloud.google.com/dataplex/docs/lineage-considerations)说明静态解析盲区。 |
| 技术线索 | `EntryGroup → Entry → Aspect(Type)` 三层模型；血缘为**静态解析 + 运行时双路**；提供 Dataplex v1 API（gRPC+REST）与 Data Lineage API；**自有血缘模型（Process/Run/Event），与 OpenLineage 规范不互通、需自建适配器（`待核实` 是否有官方适配）**；**纯 SaaS，不可自托管**。 |

### 2.13 AWS Glue Data Catalog + Amazon DataZone（→ SageMaker Catalog）

| 字段 | 内容 |
|---|---|
| 定位与客户 | AWS 原生三件套：**Glue Data Catalog**（技术元数据，兼作 Hive Metastore）+ **Lake Formation**（权限中枢）+ **DataZone/SageMaker Catalog**（业务目录与数据产品订阅）；客户数据在 S3/Glue/Redshift。 |
| 价格 | Glue Catalog 按**对象数 + 请求数**；Crawler 与作业按 **DPU-hour**；DQ 按 DPU-hour；**DataZone 自 2024-11 取消按用户订阅费，改用量计费**（[官方](https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-datazone-pricing-removes-user-subscription-fee/)）。单价待核实。 |
| 核心模块 | Glue 技术元数据与 Crawler、Glue Data Quality（DQDL）、Lake Formation（**LF-Tag 标签式 ABAC、行/列级安全**）、**Domain → Project → Asset 模型**、**订阅与审批工作流**、Glue 血缘。 |
| 差异化所长 | **治理闭环最完整："目录即权限入口"**。消费方以项目名义订阅资产，审批通过后由 **subscription fulfillment workflow 自动在 Lake Formation / Redshift 创建实际授权**——治理动作与访问控制是同一件事，无需另建 provisioning 系统（[DataZone 概念](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)）。这正是自建平台最难做对、也最值得对标的一环。 |
| 弱点/抱怨 | **三套模型、三套控制台**（资产在 Catalog、权限在 LF、消费在 DataZone），心智负担重；**血缘有硬性功能上限**：官方用户指南明确"表数超过 100 张时血缘运行会在第 100 张后中止"（[用户指南 PDF](https://docs.aws.amazon.com/pt_br/datazone/latest/userguide/datazone-ug.pdf)），对中大型数仓属功能性阻断；权限排障困难（官方专设排障章节）。 |
| 技术线索 | Glue Catalog 为区域级托管、Hive 兼容模型；血缘**以运行时为主**（来自 Glue 作业执行信息）而非静态 SQL 解析——这正是 100 表上限的根因；提供 Glue/DataZone API 并记录 CloudTrail；**无官方 OpenLineage 集成**；**纯 SaaS，不可自托管**。 |

---

## 3. 国内厂商（重点看治理模块）

> 取证限制更大：官方文档公开度不均，中立评测稀缺，真实抱怨样本不足。以下以官方 URL 与检索摘要为依据，**逐页正文未取回**。

### 3.1 阿里云 DataWorks

| 字段 | 内容 |
|---|---|
| 定位与客户 | 一站式大数据**开发治理**平台（以 MaxCompute 为核心，兼容 Hive/Spark/Flink 多引擎节点）；客户覆盖互联网、金融、零售到政企，IDC 中国数据治理平台报告称其连续四年第一（(二手) [转述](http://www.linkingapi.com/archives/19592)）。 |
| 价格 | 标准/专业/企业版**包年包月**、地域间有差异；公共调度与独享调度/数据集成资源组**分别计费**；数据保护伞已商业化。**金额待核实**（[版本与计费](https://help.aliyun.com/zh/dataworks/billing-of-dataworks-advanced-editions)）。 |
| 核心模块 | 数据集成、数据开发、**数据质量 DQC**、**数据地图 DataMap（表级/字段级血缘与影响分析）**、**数据保护伞（分级分类→识别→脱敏→使用诊断）**、**数据资产治理（治理项/健康分/治理单元/治理报告）**、智能数据建模、数据服务 API、运维中心、开放平台（OpenAPI + 扩展程序）。 |
| 差异化所长 | **治理被产品化成可量化、可派单的运营动作**（健康分 + 治理项）；更值得照搬的是**开放平台双通道**——OpenAPI 之外还有可插拔扩展程序（提交/发布卡点），并支持 **"OpenAPI 批量接入自定义实体与血缘"**，即对异构系统开放元数据与血缘的**写入面**。【[数据资产治理](https://help.aliyun.com/zh/dataworks/user-guide/data-asset-governance)、[OpenAPI 批量接入](https://help.aliyun.com/zh/dataworks/user-guide/openapi-batch-register-custom-entity-and-lineage)】 |
| 弱点/抱怨 | **血缘覆盖受采集配置与手动操作限制**（官方 OpenLake 文档自述）；"元数据采集成功后血缘仍不可见"是社区高频提问；官方 FAQ 承认提交节点会报"节点输入输出与代码中血缘不一致"；概念体系重；**专有云（私有化）版本功能与文档明显滞后**（存量文档集中在 2019–2020 年版本）。 |
| 技术线索 | MaxCompute 为核心的多引擎；血缘由数据地图承载并支持自定义实体/血缘写入；**血缘底层存储介质未公开，待核实**；自研调度 + 资源组隔离；有专有云形态；AI 侧 DataWorks Agent/Copilot 已产品化并接入 DeepSeek-R1（[官方](https://www.alibabacloud.com/help/ja/dataworks/user-guide/dataworks-agent)）。 |

### 3.2 Dataphin / 瓴羊（OneData 方法论）

| 字段 | 内容 |
|---|---|
| 定位与客户 | 面向大型集团/政企的"智能数据建设与治理"平台，卖点是**把 OneData 方法论产品化**：先业务板块与规范定义，再建模开发，最后资产盘点治理。 |
| 价格 | 有计费说明与购买指引页，**报价未公开**（[计费说明](https://www.alibabacloud.com/help/ja/dataphin/semimanaged-v4/product-overview/billing-description)）；区分**全托管 / 半托管（独享）**部署形态（[部署模式与版本](https://help.aliyun.com/zh/dataphin/product-version-introduction/)）。 |
| 核心模块 | **规范建模（业务板块/维度逻辑表/事实逻辑表/原子指标/派生指标，"规划"功能）**、数据集成与开发、资产盘点与资产全景目录、数据质量、数据安全、数据服务。 |
| 差异化所长 | **把"口径治理"前置为建模阶段的硬约束**：指标 = 原子指标 + 业务限定 + 时间周期，口径在模型层被强制统一，而非事后在术语表补文档（(二手) [解读"规划"功能](https://developer.aliyun.com/article/784988)）。这是国内方法论对"指标口径冲突"这一本土头号痛点的原创解法。 |
| 弱点/抱怨 | **方法论重、实施重**：要求企业先做业务板块划分与规范定义，落地周期长、对甲方组织能力要求高（(二手) [知乎](https://www.zhihu.com/question/436060339)）；强绑定阿里云与 MaxCompute；公开信息信噪比低（大量"费用/选型"文章实为内容营销稿）。 |
| 技术线索 | 全托管运行在阿里云之上，半托管资源归属细节未公开；AI 与血缘底层实现 `待核实`。 |

### 3.3 字节跳动 DataLeap

| 字段 | 内容 |
|---|---|
| 定位与客户 | 火山引擎"大数据研发治理套件"，源自字节内部实践，主打**"研发治理一体化"与分布式治理**；客户为互联网/新零售/金融与政企。 |
| 价格 | 计费文档拆分细、公开度在国内较高：版本服务、独享资源组、**智能助手分别计费**（[版本计费](https://docs.volcengine.com/docs/BigDataResearchandDevelopmentGovernanceSuite/Versionservicebillinginstructions)、[智能助手计费](https://docs.volcengine.com/docs/BigDataResearchandDevelopmentGovernanceSuite/Intelligentassistantbilling)）。媒体口径"低至 200 元/月"（(二手) [OSCHINA](https://my.oschina.net/u/5588928/blog/8645399)），完整规格未公开。 |
| 核心模块 | 数据集成、数据开发、数据地图与资产消费、数据质量、数据安全（分类分级与安全标签）、**存储健康分 + 治理大盘**、任务运维、数据服务、AI 智能助手（"数小秘"）。 |
| 差异化所长 | **存储健康分**：把存储成本、小文件、生命周期等治理目标折算成 0–100 分并配可视大盘，让治理从"审计清单"变成**可运营的体检指标**，动线明确指向成本治理（[官方](https://docs.volcengine.com/docs/BigDataResearchandDevelopmentGovernanceSuite/Storagehealthscore)）。 |
| 弱点/抱怨 | 强绑定火山引擎/EMR 生态；**私有化版本以独立文档序列维护、版本号与公有云不同步**，存在"私有化落后"风险；社区讨论量低于阿里/腾讯，中立负评样本不足。 |
| 技术线索 | 与火山引擎 EMR/Spark 耦合；血缘有体系化经验输出，但**是否用图数据库未明示，待核实**；AI 已作为**独立计费项**产品化。 |

### 3.4 腾讯云 WeData

| 字段 | 内容 |
|---|---|
| 定位与客户 | 腾讯云数据开发治理平台；IDC 2025 中国数据治理平台市场份额报告称其位列前三（(二手) [腾讯云社区](https://cloud.tencent.com/developer/article/2572773)）；客户偏金融、政企、泛互联网。 |
| 价格 | **按资源组分项计费**：服务/执行/集成/调度资源分别计费（[执行资源](https://www.tencentcloud.com/ko/document/product/1174/60630)、[集成资源组](https://www.tencentcloud.com/document/product/1174/60631)、[调度资源](https://intl.cloud.tencent.com/document/product/1174/60632)），另有官方 SPU 报价页。**金额待核实**。 |
| 核心模块 | 数据集成、数据开发（开发/生产隔离）、数据质量、数据安全、数据资产/元数据、数据服务 API、运维中心（执行与调度资源分离）、WeData+AI。 |
| 差异化所长 | **执行 / 调度 / 集成三类资源分离的分项计费**。相对国内普遍"打包卖版本"，它把"开发态能力"与"运行态资源"在架构与计费上同时解耦，采购侧可算账、可裁剪，**避免为闲置资源付费**（[官方资源计费文档组](https://www.tencentcloud.com/document/product/1174/60633)）。 |
| 弱点/抱怨 | 强绑定腾讯云体系，信创/私有化公开资料以营销稿为主、缺可独立验证的适配清单；**文档分散在多站点与两套编号空间**（1174 / 1267），同一功能存在多份路径；中立负评样本少。 |
| 技术线索 | 与腾讯云大数据组件族（TBDS / TCHouse 等）协同；三类资源组的架构线索来自计费文档分类；元数据与血缘存储介质、图数据库使用**未明示，待核实**。 |

### 3.5 星环科技 Transwarp

| 字段 | 内容 |
|---|---|
| 定位与客户 | 国产基础软件厂商（科创板 688031）：TDH + **TDS（数据开发与治理一站式平台）** + Sophon；客户以**金融、政企、能源、运营商**等强私有化诉求行业为主。 |
| 价格 | **License + 实施交付**（非 SaaS 订阅），报价未公开；可见线索为招股书、年报与政府采购分项报价表（项目级，不能作目录价）（(二手) [招股说明书](https://wap.stockstar.com/detail/SN2022101200012496)）。 |
| 核心模块 | 数据集成、数据开发与建模、数据治理（元数据/标准/质量/安全）、数据资产管理与目录、数据服务、Sophon 与 SophonLLMOps、**Astro 数据治理 Planner Agent**。 |
| 差异化所长 | **全栈自研 + 私有化优先**：在不可上公有云、需完全离线交付的环境中提供端到端能力——云厂商托管形态天然做不到，这也解释了其客户结构（[TDH](https://www.transwarp.cn/doc/tdh/9.5/overview)、[TDS](https://www.transwarp.io/product/tds/scene/3)）。 |
| 弱点/抱怨 | **上市三年仍未盈利**，且年报被出具**信息披露监管问询函**（(二手) [MODB](https://www.modb.pro/db/1916686991345856512)、[正观新闻](https://wap.zhengguannews.cn/html/zgh/359677.html)）——供应商长期存续与续保风险须纳入评估；客户高度集中于项目型大客户，标准化程度偏低、实施人力占比高；中立吐槽样本少。 |
| 技术线索 | TDH 为自研大数据基础平台；多款产品基于鲲鹏原生开发并获认证（(二手) [品玩](https://www.pingwest.com/a/297580)）；**具体组件与 OS/芯片/数据库适配清单待核实**；AI 已从 Sophon 延伸到大模型运营平台与治理 Agent。 |

### 3.6 袋鼠云 DTinsight / 数栈

| 字段 | 内容 |
|---|---|
| 定位与客户 | 云原生一站式数据中台 PaaS，覆盖数据开发/治理/资产/服务/可视化/数据科学；客户以国央企、金融、港口、水利、制造为主（[中铁十一局案例](https://www.dtstack.com/cases/zhongtie)）。 |
| 价格 | **私有化 License + 实施**，报价未公开（比价站可见产品条目但无标价）；完成数亿元 B 轮融资（[官方公告](https://www.dtstack.com/news/3613)）。 |
| 核心模块 | 数据集成、离线/实时开发、数据资产、数据质量、数据安全、数据服务 API、**指标平台（指标 + AI + BI）**、数据可视化与数字孪生、数据科学平台。 |
| 差异化所长 | **指标层作为"治理成果 → 业务消费"的中间层**：治理产出资产、指标层统一口径、BI/AI 直接消费——在小体量团队里比"元数据目录 + 消费侧自助"见效更快（(二手) [指标+AI+BI](http://zonghe.ctocio.cn/zonghe/2024/1105/235343.html)）。 |
| 弱点/抱怨 | **产品化程度与文档公开度低于云厂商**，无完整公开在线文档中心，功能细节难以在采购前独立验证；技术文章多由本公司工程师发布，第三方中立评测稀缺；客户偏项目制，存在重实施交付的共性风险。 |
| 技术线索 | 云原生 PaaS、多引擎（离线 + 实时数据湖）；元数据/血缘底层存储与图数据库使用**未明示，待核实**；信创适配清单未公开。 |

### 3.7 补充厂商

| 厂商 | 治理模块看点 | 备注 |
|---|---|---|
| **华为云 DataArts Studio** | 分层架构（数据架构→建模→指标平台）+ 基础包/增量包计费 | **官方文档自证新旧版本能力不对称**：版本对照表中"轻量数据治理能力"在旧版本模式标注为"不支持"（[产品描述 PDF](https://support.huaweicloud.com/intl/en-us/productdesc-dataartsstudio/dataartsstudio-productdesc-pdf.pdf)）；有专门的血缘约束限制页与华为云 Stack 私有化形态 |
| **网易数帆 EasyData** | **数据治理 360 - 健康诊断**（体检式诊断界面）+ 数据质量稽核 | 文档公开度较好（[官方](https://study.sf.163.com/documents/read/EasyDataBook/easydasset_diagnose.md)）；未公开报价 |
| **亚信科技 DataGo / DataOS** | 行业数据资产管理（DataGo V3.5 有公开白皮书）、DataOS 支持 30+ 异构数据源采集 | 项目招标制、价格未公开；**无公开在线文档中心**，细节难验证 |
| **亿信华辰 睿治** | 全生命周期数据治理 + **官方信创专区** | License + 实施；SaaS 能力弱于云厂商 |
| **普元 易数 / DAMP / MDM** | 数据资产管理 + **主数据 MDM**（"全集团单一事实来源"） | 客户以央国企为主（[官方 MDM](https://www.primeton.com/products/mdm)）；价格未公开、技术文档少 |
| **数梦工场** | 数字政府/智慧城市数据中台与数据中枢 | 政府项目制；通用治理产品化能力弱于上述厂商 |

### 3.8 范式差异：国内"数据中台" vs 海外"数据目录"

| 维度 | 国内（数据中台 / 研发治理一体化） | 海外（数据目录 / 元数据协作） |
|---|---|---|
| 主战场 | **生产侧**：集成、开发、调度、质量、安全与研发流程同平台 | **消费侧**：目录、搜索、术语表、协作、认证徽章 |
| 核心资产 | **指标与口径**（原子/派生指标、指标中台） | **术语表与数据契约** |
| 交付形态 | 私有化 License + 实施 + 方法论咨询（OneData、华为数据之道） | 以 SaaS 为主，开源可选（DataHub / OpenMetadata 自托管） |
| 治理抓手 | 治理项 + 健康分 + 治理报告（可派单） | 术语表覆盖率、认证、质量 SLA、契约违约告警 |
| 血缘 | 以**任务/作业**为驱动，覆盖受采集配置限制 | 以**元数据事件**为驱动，平台化程度高、开放 API 成熟 |
| 合规驱动 | 信创、等保、分级分类、数据出境 | GDPR/CCPA、数据主权、AI 治理 |
| 生态 | 强绑定自家云与引擎（MaxCompute / EMR / 腾讯云 / 华为云） | 引擎中立，强调 dbt / Snowflake / Databricks 集成 |

**最关键的一条差异**：海外的"数据契约"是**生产者对消费者的可自动校验承诺**（违约可被机器检测）；国内的"指标口径"是**组织内部的事前人工约定**（依赖建模阶段对齐，无法自动检测违约）。国内平台普遍把治理做到"建起来"就结束，海外范式先把"用起来"做成产品再倒逼生产侧——这正是"数据中台建了三年没人用"的根因。

---

## 4. 厂商 × 能力维度对比矩阵

图例：**●** 强/原生 ｜ **◐** 有但不完整/需插件 ｜ **○** 弱或无 ｜ **—** 不适用

| 厂商 | 目录搜索 | 血缘深度 | 数据质量 | 数据契约 | 术语表 | 分类分级 | 工作流审批 | 协作 | AI 助手 | 可观测性/SLO | MDM | 数据市场/产品 | 细粒度权限 | 开放 API | 私有化 | 价格透明 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Collibra** | ● | ● | ● | ◐ | ● | ● | ● | ● | ● | ◐ | ○ | ● | ● | ● | ● | ○ |
| **Alation** | ● | ● | ◐ | ○ | ● | ◐ | ● | ● | ● | ◐ | ○ | ◐ | ◐ | ● | ● | ○ |
| **Informatica** | ● | ● | ● | ◐ | ● | ● | ● | ◐ | ● | ◐ | ● | ● | ● | ● | ● | ○ |
| **IBM watsonx** | ● | ● | ● | ◐ | ● | ● | ● | ◐ | ● | ○ | ◐ | ◐ | ● | ● | ● | ○ |
| **Atlan** | ● | ● | ◐ | ◐ | ● | ● | ● | ● | ● | ◐ | ○ | ◐ | ◐ | ● | ○ | ○ |
| **Select Star** | ● | ● | ◐ | ○ | ◐ | ◐ | ○ | ◐ | ◐ | ○ | ○ | ○ | ◐ | ● | ○ | ○ |
| **Datafold** | ○ | ● | ● | ◐ | ○ | ○ | ○ | ○ | ◐ | ◐ | ○ | ○ | ○ | ● | ○ | ○ |
| **Monte Carlo** | ◐ | ● | ● | ○ | ○ | ◐ | ◐ | ● | ● | ● | ○ | ◐ | ○ | ● | ○ | ○ |
| **Bigeye** | ○ | ◐ | ● | ○ | ○ | ○ | ○ | ◐ | ◐ | ● | ○ | ○ | ○ | ● | ○ | ○ |
| **Great Expectations** | ○ | ○ | ● | ◐ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ● | ● | ◐ |
| **Soda** | ○ | ○ | ● | ● | ○ | ○ | ○ | ○ | ○ | ◐ | ○ | ○ | ○ | ● | ● | ◐ |
| **Elementary** | ◐ | ◐ | ● | ◐ | ○ | ○ | ○ | ◐ | ○ | ● | ○ | ○ | ○ | ● | ● | ○ |
| **Microsoft Purview** | ● | ● | ◐ | ○ | ● | ● | ◐ | ◐ | ● | ◐ | ○ | ● | ● | ● | ○ | ● |
| **Google Knowledge Catalog** | ● | ● | ● | ○ | ● | ◐ | ◐ | ◐ | ● | ◐ | ○ | ◐ | ◐ | ● | ○ | ● |
| **AWS Glue + DataZone** | ● | ◐ | ● | ○ | ◐ | ◐ | ● | ◐ | ◐ | ◐ | ○ | ● | ● | ● | ○ | ● |
| **阿里 DataWorks** | ● | ● | ● | ◐ | ◐ | ● | ● | ◐ | ● | ◐ | ○ | ◐ | ● | ● | ● | ◐ |
| **Dataphin（OneData）** | ● | ● | ● | ◐ | ● | ● | ● | ◐ | ◐ | ◐ | ◐ | ◐ | ● | ◐ | ● | ○ |
| **字节 DataLeap** | ● | ● | ● | ○ | ◐ | ● | ● | ◐ | ● | ◐ | ○ | ◐ | ● | ● | ● | ◐ |
| **腾讯 WeData** | ● | ◐ | ● | ○ | ◐ | ● | ● | ◐ | ● | ◐ | ○ | ◐ | ● | ● | ● | ◐ |
| **星环 Transwarp** | ● | ◐ | ● | ○ | ● | ● | ◐ | ○ | ◐ | ◐ | ● | ◐ | ● | ◐ | ● | ○ |
| **袋鼠云 DTinsight** | ● | ◐ | ● | ○ | ◐ | ● | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ● | ○ |

**四点提示**：

1. **没有一家是全能**。Collibra/Informatica 在"治理型"维度最完整，但可观测性弱于 Monte Carlo；反之亦然。**"全都要"在商业市场上也不存在**。
2. **"私有化"列是自研最干净的否决依据**：Purview、Knowledge Catalog、AWS DataZone、Select Star、Monte Carlo、Bigeye、Atlan 均为纯 SaaS 或仅混合采集，**无法离线部署**；仅 Collibra（CCSH/Edge）、Alation（Customer-Managed）、Informatica、IBM、国内厂商与开源侧支持。
3. **"价格透明"几乎全为 ○/◐**：海外目录与可观测性厂商 **6/6 不公开 list price**（均需询价/订单表单），国内报价也普遍未公开。**采购前无法建模 TCO 是品类常态**，而这本身是自研的隐性优势。
4. **三种成本风险形状须分别建模**：按**用户**（Select Star、部分 Purview 许可）→ 全员推广时爆炸；按**资产/表**（Monte Carlo、Atlan、Glue、Dataplex）→ 数据增长时爆炸；按**比对/扫描作业资源**（Datafold、Informatica IPU、Purview CU）→ 使用频率增长时爆炸。

---

## 5. 每个厂商最值得借鉴的一个设计点

1. **Collibra — 可配置元模型（Operating Model）**：Community/Domain/Asset Type/Relation 是**可定义的数据而非硬编码枚举**。→ 元模型注册表（类型+关系+约束）作一等公民，新增资产类型不改代码。
2. **Collibra — 内置工作流引擎**：术语/资产的"提交—评审—批准—留痕"走引擎而非 Git PR。→ 声明式状态机（阶段+触发+转换），让非工程角色可参与治理。
3. **Alation — 行为元数据驱动冷启动**：从查询日志推导人气、常用 Join 与"该被认证的表"。→ 查询日志作独立元数据源，产出 popularity / co-usage **派生视图**（可重建），而非要求团队填表。
4. **Alation — Trust Flags**：用"认证/警告/弃用"三个显式标记表达**可信状态**。→ 资产级 trust 状态机，展示优先级高于描述完整度。
5. **Alation — 开放 DQ 框架**：不自研 DQ 引擎，而定义**结果接入契约**。→ 一个 DQ 结果摄入 API 胜过自研规则引擎。
6. **Informatica — 元数据即 AI 的输入（CLAIRE）**：分类、匹配、推荐消费元数据特征而非纯正则。→ 自动分类走"规则+统计+语义相似度"融合并输出置信度。
7. **Atlan — 元数据可编程（Apps Framework + Playbooks）**：允许在平台内建应用与无代码自动化。→ **开放扩展 UI（slot）+ 事件触发动作**，让治理规则由业务方配置；比功能清单长度更决定长期采纳率。
8. **Atlan — Asset 360°**：单一资产的聚合视图（血缘+画像+治理状态+评论+查询历史）是**唯一真正被高频使用的页面**。→ 把"资产详情页"当核心产品做，其余功能为其供数。
9. **Select Star — 查询日志即血缘**：先用审计日志打通高价值链路换取采纳，**同时显式标注覆盖边界**（日志保留期、仓库外 ETL 断链）。
10. **Datafold — Data Diff 作 CI 门禁**：主动验证优于被动监控。→ 行级比对 + PR 阻断，与自家调度/CI 深度集成。
11. **Monte Carlo — 血缘驱动的告警收敛**：用血缘判断根因在上游、影响在下游，把 N 条告警合成 1 个 incident。→ 事件归并算法以血缘闭包为输入，**这是可观测性最难复制、最值得投入的部分**。
12. **Monte Carlo — Incident IQ 协作闭环**：告警不是终点，工单分派、复盘、SLO 追踪才是。→ 告警→事件→协作→复盘的数据模型要一次设计对。
13. **Bigeye — 自动阈值 + 配置即代码双轨**：→ **任何自动推断出的配置都必须能导出成声明式文件**，否则用户不敢用。
14. **Bigeye — 客户侧 agent 采集**：可穿透私有网络的推模式。→ Pull + Edge Agent 双模（对应 `00-overview.md` 第 7 项差异化）。
15. **Soda — YAML 而非代码**：把写检查的门槛降到分析师。→ 规则 DSL 优先服务业务角色，Python 表达式作逃生通道。
16. **Elementary — 计算推向数仓**：结果写回客户自己的仓库，平台侧只做 UI 与协作。→ **零数据出域**是合规场景的杀手级特性，也让平台侧职责大幅变轻。
17. **dbt — 契约与模型定义同源**：血缘来自编译器产物而非日志推断。→ 有编译期元数据（dbt manifest / SQL 解析）时优先用，**日志推断只作补充**。
18. **Purview — 标签贯穿工作流**：分类结果作用于数据本身并随数据流动。→ 把"标签"设计成**策略载体**而非展示属性，同时驱动脱敏、访问与审计。
19. **Purview — Data Estate Health**：把"未扫描/未分类/无 Owner"做成可派单的缺口清单。→ 治理运营的健康分与待办中心（对应 D8）。
20. **Google Knowledge Catalog — Aspect Type 可扩展元数据 schema**：企业自定义 aspect 并绑定任意条目（含 API 注入的外部系统）。→ 等价物是**自有 schema registry + 开放写入 API**，自研必须正面对标。
21. **AWS DataZone — 审批结果自动落到真实权限**："目录即权限入口"。→ 这就是 `00-overview.md` 中策略编译下发（D7）的目标形态，**是自研最有价值的差异化方向**。
22. **阿里 DataWorks — OpenAPI 批量接入自定义实体与血缘**：对异构系统开放元数据与血缘的**写入面**。→ 血缘不能只靠自家引擎解析，否则覆盖永远是瓶颈（国内最值得照搬的一条）。
23. **DataWorks — 健康分 + 治理项 + 治理单元**：把治理做成可量化、可下钻、可派单的产品功能。→ 治理运营闭环，投入小、见效快、甲方感知强。
24. **DataWorks — 数据保护伞闭环**：分级分类→识别→脱敏→**使用诊断**。→ 分级之后必须有"谁在怎么用"的诊断视图，否则合规只停在打标。
25. **Dataphin — 口径治理前置到建模**：指标 = 原子指标 + 业务限定 + 时间周期，在模型层强制统一。→ 面向集团/多业务线时指标层是刚需；单一业务线则属过度设计。
26. **字节 DataLeap — 存储健康分**：把成本类治理目标折算成分数并配大盘。→ 治理指标要"可运营"，而不只是"可查询"。
27. **腾讯 WeData — 执行/调度/集成资源三分离**：开发态能力与运行态资源在架构与计费上解耦。→ 若平台对外交付，**平台能力与计算资源分开计价**能显著降低客户的闲置成本顾虑。
28. **星环 — 全栈自研 + 私有化优先**：→ **从第一天把"可离线部署、可换 OS/芯片/数据库"作为架构约束**，而非后期适配。
29. **华为云 DataArts — 主动承认血缘边界**：官方把血缘约束单列成页。→ 血缘必须带 source/confidence/freshness/conflict，文档要写清"什么情况下血缘会不准"。
30. **Informatica — 策略被编译进数据流（CDAM 在 CDI 内物理执行）**：访问策略不是"登记在目录里"，而是在数据管道中被强制施加（转换中出现 `_cdam_` 注入列）；其知识图谱跑在 **Amazon Neptune**。→ **这是全行业对 `00-overview.md` 中"策略编译下发到数据面"（D7）最有力的背书**：该能力商业侧已有先例，且被证明可落地；也是自研唯一能同时超过开源（不做）与多数商业目录（只登记不执行）的方向。
31. **IBM Manta — 血缘引擎可解耦**：Manta 支持 **OpenLineage 事件摄取**、提供 Open Manta 导出格式，且血缘**可导出到 Collibra 与 Alation**；其路径是**静态代码分析（DDL/PLSQL/Java/C# 字节码 + 代码注解）而非 SQL 解析**。→ 自研的血缘层应设计成**可独立部署、可被外部消费的血缘引擎**（含 OpenLineage 摄取面），并覆盖存储过程与报表层——只看 SQL 必然漏掉传统数仓最重的一块。
32. **Alation — Connected Sheets（把治理成果推回业务工作面）**：元数据与可信数据直接出现在 Google Sheets 等业务用户日常界面。→ 治理成果必须**出现在数据消费现场**，而不是要求业务用户"来治理平台看一下"；这是提升采纳率最直接的手段。

---

## 6. 商业产品普遍做得好、而开源普遍做得弱的点

| # | 差距点 | 证据 |
|---|---|---|
| 1 | **审批工作流与变更提案** | DataHub 的 Change Proposals / Approval Workflows 位于 `managed-datahub` 文档域，**属商业版专有**（[Change Proposals](https://docs.datahub.com/docs/managed-datahub/change-proposals)、[Approval Workflows](https://raw.githubusercontent.com/datahub-project/datahub/refs/heads/master/docs/managed-datahub/approval-workflows.md)）；OpenMetadata 术语表审批长期有未解 issue（[#14391](https://github.com/open-metadata/OpenMetadata/issues/14391)、[#13964](https://github.com/open-metadata/OpenMetadata/issues/13964)） |
| 2 | **业务术语表治理**（多级术语、同义词、审批、覆盖率度量） | 同 #1：术语审批在 OSS 侧缺位或极弱 |
| 3 | **分类分级与合规留痕**（PII 识别、数据出境、审计报告） | 质量工具（GX/Soda/Elementary/dbt）不含 PII 识别与合规报表；数据网格/治理实践研究亦指出 **PII 处理是最显著缺口之一**（[学位论文](http://dolgozattar.uni-bge.hu/60435/1/csatari_levente_data_mesh_tdk_dolgozat.pdf)）；另有研究指出**专有工具在 DQ 规则定义上功能更全、更灵活**（[arXiv 2604.09163](https://arxiv.org/pdf/2604.09163v1)） |
| 4 | **血缘的列级与跨系统覆盖** | OpenLineage 官方自述列级血缘仍在演进，CSV、Snowflake、Spark TempView 等场景明确缺失（[官方博客](https://openlineage.io/blog/column-lineage/)、[Discussion #2568](https://github.com/OpenLineage/OpenLineage/discussions/2568)、[Issue #2672](https://github.com/OpenLineage/OpenLineage/issues/2672)） |
| 5 | **SLO / 事故管理 / 值班分派** | 开源工具输出的是"检查结果"而非"事件"：缺去重、分派、升级、复盘、SLO 追踪（[Bigeye](https://www.bigeye.com/blog/so-youve-implemented-dbt-tests-great-expectations-now-what)） |
| 6 | **细粒度权限与行级安全** | OpenMetadata 存在"拥有全权限仍无法编辑/删除 DQ 测试规则"的功能性缺陷（[Issue #10859](https://github.com/open-metadata/OpenMetadata/issues/10859)） |
| 7 | **多租户与 SaaS 运维能力** | 自建需自担部署、升级、备份、扩缩容、监控；第三方普遍把运维人力列为开源方案的主要隐性成本（[Atlan](https://atlan.com/build-vs-buy-data-catalog/)、[Acceldata](https://www.acceldata.io/blog/purchase-vs-build-a-practical-guide-to-data-governance-platforms)） |
| 8 | **开箱即用的连接器生态** | Soda 按数据源分包安装、GX 需为不同引擎写不同代码、Elementary 完全依赖 dbt adapter（[soda-core README](https://raw.githubusercontent.com/sodadata/soda-core/refs/heads/main/README.md)） |
| 9 | **告警降噪、抑制与去重** | alert fatigue 被普遍列为数据测试规模化的第一障碍（[Secoda](https://www.secoda.co/blog/what-is-data-testing-alert-fatigue)）；商业侧以血缘收敛 + incident 合并为核心卖点 |
| 10 | **业务角色可自助（无代码）** | GX 需 Python、dbt 需 SQL+YAML+Git；治理专员无法在界面上维护术语与规则（[GX vs Soda](https://fastero.com/blog/great-expectations-vs-soda-data-quality-compared)） |
| 11 | **升级迁移成本由谁承担** | GX 0.18→1.x 属 API 重写并发布官方迁移指南（[官方](https://greatexpectations.io/blog/changes-to-know-for-gx-core-1-0/)）；Soda Core 3.x→4.x 换代而文档仍以 v3 组织（[v4.0.5](https://newreleases.io/project/github/sodadata/soda-core/release/v4.0.5)）。商业平台的迁移由供应商承担 |
| 12 | **项目长期存续与路线图可控性** | Amundsen 2026-09 归档、GX Core 2026-05 易主 Fivetran——**开源组件的长期路线图不由用户控制** |
| 13 | **数据契约的落地失败风险** | 存在公开记录的放弃案例：团队对 Data Contract CLI 做技术验证后决定不导入（[日文复盘](https://zenn.dev/sugato/articles/1dd6891e902d2b)）；行业分析把契约落地障碍归为**组织性而非技术性**（[Gable](https://www.gable.ai/blog/why-you-cant-seem-to-adopt-data-contracts-no-matter-how-hard-you-try)）——与 §3.8「国内口径约定不可自动校验」互为印证 |

**两点反向补充（buy 的隐性优势，自研无法享有）**：

- **云市场承诺额度核销**：Anomalo 可通过 Snowflake MCD 与 Databricks 承诺额度采购（[官方](https://www.anomalo.com/blog/anomalo-is-now-mcd-eligible-on-snowflake-marketplace/)），Atlan/Monte Carlo/Bigeye/Acceldata/Select Star 均已上架各大云市场。**"买"能把支出从新增预算变成已承诺预算的核销，而"自建"不能。**
- **供应商承担升级与 SLA**：这是用订阅费换来、在自建方案里必须以人力折算的成本。

---

## 7. 对自研平台的取舍建议（对应 `00-overview.md` 的 D1–D8）

1. **D2 目录与发现 / D3 血缘**：对标开源（OpenMetadata 的开箱可用 + DataHub 的事件驱动），**不要**对标 Collibra 的功能广度——广度靠生态，不靠自研。
2. **D5 契约 / D6 分类分级**：契约以 **ODCS v3.1.0** 为格式基准（[规范](https://github.com/bitol-io/open-data-contract-standard)）；分类分级对标 Purview 的"标签即策略载体"与 DataWorks 数据保护伞的"识别→脱敏→使用诊断"闭环。
3. **D7 访问治理与策略**：**自研唯一站得住的差异化**（开源不做，商业侧只有 AWS 做到闭环）。直接对标 DataZone 的"审批结果自动落到真实授权"，把审批与策略编译下发做成同一件事。
4. **D8 治理运营与 AI**：健康分/待办/记分卡对标 DataWorks 与 DataLeap；AI 走 **Suggestion + provenance + 人工确认**（对标商业 AI 推荐，但补上它们普遍缺少的可审计性）。
5. **明确不做**：ETL 调度、BI、查询引擎、MDM、通用 BPMN——与 `00-overview.md` 的 Non-Goals 一致；MDM 在商业侧（Informatica、IBM、星环、普元）是独立且重的产品线。
6. **从第一天就必须是架构约束的两条**：**私有化/离线可部署**（星环与国内厂商的客户结构证明这是政企入场券）；**元数据/血缘的开放写入 API**（DataWorks 的 OpenAPI 批量接入最值得照搬）。
7. **采集路径的战略选择（直接决定工程量与成败）**：商业侧的元数据来源只有三条路径——**(a) 查询日志驱动**（Select Star、Monte Carlo 部分）：覆盖广、零人工，但受审计日志保留期制约，仓库外 ETL 会断链；**(b) dbt/manifest 驱动**（Elementary、Datafold 部分）：零配置、编译期血缘最准，但只在 dbt 世界内有效；**(c) 连接器/元数据驱动**（Atlan、Bigeye）：通用，但需长期维护庞大连接器矩阵。**自研应优先组合 (a)+(b)，而不是重造 (c) 的连接器矩阵**——连接器广度是 2–3 年社区累积的结果，自研在此维度投入产出比最低（与 `00-overview.md` 的风险表「连接器维护失控」一致）。
8. **必须承认的直接竞品是 Elementary**：它是唯一免费、可自托管的生产级选项，且其"计算推进数仓、平台侧只做 UI 与协作"的架构就是自建方案最好的参考形态。**若自建平台在「目录 + 质量」范围内无法显著超过"Elementary + OpenMetadata 组合"，则立项理由只剩 D7 策略执行与 D8 治理运营。**
9. **面向国内客户时的定位约束（不要照搬海外范式）**：国内客户在政企/金融场景买的是**生产链路**（集成/开发/调度/质量/安全同平台），**纯目录 + 术语表型产品几乎不可能单独立项**；但"研发治理一体化"的代价是重——DataWorks / Dataphin / DataArts 的弱点高度一致（实施重、概念体系多、上手门槛高）。因此差异化的正确切口是**治理动作的自动化率**（自动识别、自动推荐规则、自动生成口径、自动派单），**而不是再造一个开发 IDE**——后者是自研最不该进入的战场。

---

## 8. 待核实清单

| 项目 | 状态 |
|---|---|
| 全部海外厂商具体单价（Collibra/Alation/Atlan/Select Star/Monte Carlo/Bigeye/Datafold） | 均为第三方聚合站数字，**待核实** |
| Collibra 第三方报价量级矛盾（$120k–170k/年 vs $14,167–16,500/user/month） | 口径疑似错误，**待核实** |
| Alation Forrester TEI 的 $246,000/年（300 用户） | 2019 年、厂商委托，**需按当前口径复核** |
| Purview CU / 受治理资产费率；Dataplex 存储/API/DQ/血缘单价；Glue 对象/请求/DPU 单价；DataZone 现行费率 | 定价页 URL 已定位，**正文未取回** |
| DataZone 血缘"100 表上限"的现行版本与触发范围 | 官方 PDF 已定位，**版本与页码待核实** |
| DataWorks / Dataphin / WeData / DataArts / DataLeap 各版本与资源组单价 | 计费页已定位，**金额未公开**（DataLeap 仅"低至 200 元/月"营销口径） |
| Google "Knowledge Catalog" 正式发布时点与范围 | **待核实** |
| 各家是否有官方 OpenLineage 适配 | 当前判断为"无"，**待核实** |
| 全部厂商 G2 / Gartner Peer Insights 抱怨原文 | 仅取回评论页 URL，**正文未取回**；国内厂商（袋鼠云、网易数帆、亚信、数梦工场）中立负评样本尤其不足 |
| Select Star 交易金额与"技术收购 vs 整体收购"性质 | **待核实** |
| Collibra 内部图存储/搜索引擎选型、AI Copilot 的 LLM 供应商、工作流是否为 BPMN 2.0 | **待核实**（工作流确实存在且可编辑，但"BPMN"表述未从官方文档取到；另无公开 GSA SKU 单价，与 IBM 侧能命中价目表形成对比） |
| 各家内部元数据仓库与搜索引擎选型 | 仅确认 **Alation 用 Elasticsearch**、**Informatica 知识图谱用 Amazon Neptune**；Collibra、IBM、Informatica 的其余选型**待核实** |
| Informatica 的 IPU 单价与 CDGC 的 IPU 计量公式；IBM 任意货币单价与三档 editions 权益 | **待核实**（官方价格页只讲模型不讲单价） |
| G2 / Gartner Peer Insights / Reddit 的评论正文 | 本次检索**未返回任何 g2.com 或 reddit.com 结果**，真实抱怨措辞实质缺失，需在具备抓取能力的环境补做 |

**主要来源索引**：[Collibra 工作流](https://developer.collibra.com/workflows/workflows.md)、[Alation 部署形态](https://docs.alation.com/en/latest/welcome/CloudAndOnPrem/index.html)、[Alation Trust Flags](https://www.alation.com/docs/en/latest/steward/UseTrustFlags/index.html)、[Informatica CDGC](https://www.informatica.com/content/dam/informatica-com/en/collateral/data-sheet/cloud-data-governance-and-catalog_data-sheet_4152en.pdf)、[Atlan Apps Framework](https://docs.atlan.com/product/capabilities/build-apps)、[Snowflake 收购 Select Star](https://www.snowflake.com/en/blog/snowflake-acquire-select-star/)、[Datafold Data Diff](https://docs.datafold.com/data-diff/what-is-data-diff)、[Monte Carlo Monitoring](https://docs.getmontecarlo.com/docs/automated-monitoring)、[Bigeye Bigconfig](https://www.bigeye.com/blog/bigconfig-empowers-data-teams-to-implement-data-reliability-at-scale)、[Elementary OSS vs Cloud](https://docs.elementary-data.com/cloud/cloud-vs-oss)、[dbt 模型契约](https://docs.getdbt.com/docs/collaborate/govern/model-contracts)、[ODCS v3.1.0](https://bitol.io/bitol-announces-odcs-v3-1-0-stronger-smarter-and-stricter/)、[Purview 定价指南](https://learn.microsoft.com/en-us/purview/data-gov-classic-pricing-data-map)、[Knowledge Catalog 元数据总览](https://docs.cloud.google.com/knowledge-catalog/docs/metadata-overview)、[DataZone 概念](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)、[DataWorks 数据资产治理](https://help.aliyun.com/zh/dataworks/user-guide/data-asset-governance)、[DataLeap 存储健康分](https://docs.volcengine.com/docs/BigDataResearchandDevelopmentGovernanceSuite/Storagehealthscore)、[WeData 资源计费](https://www.tencentcloud.com/document/product/1174/60633)、[星环 TDS](https://www.transwarp.io/product/tds/scene/3)、[华为云 DataArts 产品描述](https://support.huaweicloud.com/intl/en-us/productdesc-dataartsstudio/dataartsstudio-productdesc-pdf.pdf)

> 逐条原始取证（含完整来源与取证说明）见 `_raw/`：`batch2-atlan-selectstar-datafold-observability.md`、`batch3-oss-data-quality.md`、`batch4-hyperscaler-purview-dataplex-aws.md`、`batch5-china-vendors.md`。
