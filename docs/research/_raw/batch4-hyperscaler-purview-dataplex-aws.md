# 批次 4：超大规模云厂商数据治理/目录服务（Purview / Dataplex-Knowledge Catalog / AWS Glue+DataZone）

> 调研对象：Microsoft Purview、Google Cloud Dataplex Universal Catalog（现 Knowledge Catalog）、AWS Glue Data Catalog + Amazon DataZone（现 Amazon SageMaker Catalog）
> 调研日期：2026-10 | 用途：自建数据治理平台"build vs buy"分析
> 一手来源：[learn.microsoft.com](https://learn.microsoft.com/en-us/purview/)、[cloud.google.com/dataplex](https://cloud.google.com/dataplex/pricing)、[docs.aws.amazon.com](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)

## 0. 方法与局限（重要）

本次环境**网络出口受限**（沙箱内 HTTPS 连接被拒：`curl` 退出码 35 / `Invoke-WebRequest` 连接被关闭），无法抓取任何网页正文；检索工具**只返回标题+URL、不返回正文摘要**。因此：

- **机制性结论**（计费单位、元数据模型、血缘实现路径、集成关系）来自官方文档公开知识与检索到的**官方 URL 交叉印证**，可信度较高；
- **所有具体单价数字**一律标注 `待核实`，本报告不提供未经页面正文确认的价格数字，须以实时官方定价页为准；
- 标 `(第三方)` 者为博客/评测来源，仅作现象与抱怨佐证，不作定价依据；
- 标 `(官方)` 且附 URL 者为官方文档/定价页；`待核实` 表示本次未能取回正文验证。

> **跨三家共性发现（命名churn 本身就是结论）**：三家在过去 3 年**全部发生过或正在发生产品更名/重组**——Azure Purview→Microsoft Purview、Data Catalog→Dataplex Universal Catalog→Knowledge Catalog、Amazon DataZone→Amazon SageMaker Catalog。这意味着任何"买"的决策都需把**迁移与重学成本**计入 TCO。

---

## 1. Microsoft Purview

### 1.1 定位与目标客户

微软生态（Azure + Microsoft 365 + Fabric）内的**统一数据治理与数据安全合规平台**。需要区分两个常被混淆的部分：**(a) 数据安全/合规侧**（敏感度标签、DLP、内部风险管理，随 M365 E5 或 Purview 合规套件授权）；**(b) 数据治理侧**（Data Map + Unified Catalog，按 Azure 资源计费）。目标客户是**已深度绑定微软生态**的中大型企业：数据已在 Azure SQL/Synapse/Fabric OneLake/M365 中，且合规与安全团队（而非纯数据团队）是采购驱动方（[Microsoft Purview 数据治理产品页](https://www.microsoft.com/en-us/security/business/risk-management/microsoft-purview-data-governance)、[Unified Catalog 文档](https://learn.microsoft.com/en-us/purview/unified-catalog-data-products-create-manage)）。

### 1.2 商业模式与价格

| 计费项 | 计量单位 | 单价 | 来源 |
|---|---|---|---|
| Data Map（经典） | **Capacity Unit（CU）**，按小时消耗；1 CU ≈ 1 小时 × 一个 4 vCore / 16 GB 的资源组 | 待核实 | [经典 Data Map 定价指南](https://learn.microsoft.com/en-us/purview/data-gov-classic-pricing-data-map) |
| Data Map（弹性 elastic data map，新模式） | 按量计费，取代固定资源组预置 | 待核实 | [弹性 Data Map 定价指南](https://learn.microsoft.com/en-us/purview/concept-guidelines-pricing-data-map) |
| 扫描（scanning） | 通过 CU 消耗间接计量；无独立"每 GB"公开价 | 待核实 | [Purview 定价指南（含 former Azure Purview）](https://learn.microsoft.com/en-us/purview/concept-guidelines-pricing) |
| 治理侧许可 | 按用户 + 按"受治理资产（governed asset）"分层；2025 年 5 月起引入/调整 PAYG 模式 | 待核实 | [Azure Purview 定价页](https://azure.microsoft.com/en-us/pricing/details/purview/)、[Purview 定价页](https://www.microsoft.com/en-us/security/business/microsoft-purview-pricing) |
| 第三方引述 | 套件约 US$12/用户/月、E5 约 US$60/用户/月；E5 含合规能力但**不含** Data Map/Unified Catalog 治理能力 | (第三方) | [ComparEdge 定价综述](https://comparedge.com/tools/microsoft-purview/pricing) |

**扫描成本的驱动因素（结构性）**：CU 消耗与**被扫描资产的数量与规模、扫描频率、分类规则复杂度（SIT 与正则数量）、以及是否启用列级/采样分类**正相关；由于单价未取回正文，**无法在本次给出"每 GB/每 vCore-hour"的可引用数字**（待核实）。对 build-vs-buy 的意义在于：**Purview 的扫描成本是"元数据规模驱动"而非"用户数驱动"**，因此数据量大的企业成本曲线与小型企业差异极大——这与 Google 的"对象数+API 调用"、AWS 的"对象数+请求数"结构相似，但 Purview 额外叠了一层每用户/每受治理资产许可。

**关键机制**：Data Map 是**独立于 M365 许可的 Azure 计量资源**——这是"买了 E5 却发现治理要另外掏钱"这一常见预期落差的根源。2025 年起的 **PAYG 消耗模式**是本次调研中最值得 build-vs-buy 关注的变化：它把成本从"预置容量"转为"用量驱动"，同时显著提高了成本不可预测性（见 1.5）。

### 1.3 核心功能模块清单

- **Data Map**：多源扫描（Azure/AWS S3/本地/多云的 200+ 连接器，数量待核实）、自动分类、资产注册与检索
- **敏感信息类型（SIT）与敏感度标签**：内置+自定义 SIT、标签、自动标记、标签沿数据流动传播
- **血缘（Lineage）**：来自扫描（视图/存储过程静态解析）+ 运行时推送（Atlas 血缘 API）
- **Data Estate Health**：健康管理动作、缺口（未扫描/未分类/无 Owner）清单与修复动作（[Data Estate Health 实验文档](https://raw.githubusercontent.com/Keayoub/purview-data-governance-masterclass/refs/heads/main/Lab-10%20-%20Health%20Management%20Actions.md)）
- **Unified Catalog**：**Data Products**（数据产品）、业务术语表、OKR、数据质量、数据访问策略（[创建与管理数据产品](https://learn.microsoft.com/en-us/purview/unified-catalog-data-products-create-manage)）
- **治理报表与可观测性**：Data Governance Report、Data Observability（均为 Preview）（[治理报表](https://learn.microsoft.com/en-us/purview/unified-catalog-reports-data-governance)、[可观测性](https://learn.microsoft.com/en-us/purview/unified-catalog-observability)）
- **Fabric / OneLake 集成**：扫描 OneLake 快捷方式（shortcut）与镜像数据库（mirrored database），并在 Unified Catalog 内对其做数据质量（[Fabric shortcut 数据质量](https://learn.microsoft.com/en-us/purview/unified-catalog-data-quality-fabric-shortcut-databases)、[Fabric 镜像库数据质量](https://learn.microsoft.com/en-us/purview/unified-catalog-data-quality-fabric-mirrored-databases)）

### 1.4 差异化所长（最有价值的一个设计）

**"敏感信息类型 + 敏感度标签"体系与 M365 安全栈的闭环**。Purview 的标签是**作用于数据本身、并跟随数据流动**的策略载体：同一个标签既驱动 Data Map 里的分类分级，又驱动 M365 侧 DLP、Defender、内部风险管理的策略执行。Dataplex 与 AWS 都能做分类和基于标签的访问控制，但**标签无法像 Purview 那样贯穿 Office/Teams/SharePoint 等最终用户工作流**——这是微软不可复制的护城河，也是"若企业主体是 M365 用户，则 buy 的边际价值最高"的直接依据。

### 1.5 弱点/客户抱怨（真实抱怨）

- **PAYG 消耗模式被直接称为"成本陷阱"**：德语社区文章标题即 *"Microsoft Purview im Pay-as-you-go-Modell: Kostenfalle oder notwendige Evolution?"*（"成本陷阱还是必然演进？"）（(第三方) [stacho.blog, 2026-02](https://stacho.blog/2026/02/17/microsoft-purview-im-pay-as-you-go-modell-kostenfalle-oder-notwendige-evolution/)）
- **PAYG 服务"悄悄"累积计算费用**：第三方分析指出 Purview 等 PAYG 服务会产生持续的计算账单，需要在 Azure 成本管理里专门监控（(第三方) [Office365ITPros, 2026-02](https://office365itpros.com/2026/02/04/dsi-costs-compute/)）
- **UI/概念复杂 + 功能随版本变动**：经典 Data Map 门户 → Unified Catalog 的迁移造成概念割裂（Data Map / Catalog / Unified Catalog / Data Estate Health 并存），第三方评测普遍反映学习曲线陡（(第三方) [Gartner Peer Insights 评论](https://www.gartner.com/reviews/product/microsoft-purview-1861237758)、[ComparEdge 评测](https://comparedge.com/tools/microsoft-purview)）
- **Fabric 集成覆盖不完整**：Snowflake 镜像项在 Purview Data Catalog 中不显示等缺口，属用户在问答区实际反馈（(官方问答) [Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/5935437/snowflake-mirrored-item-on-fabric-doesnt-show-on-p)）
- **经典 Data Map 的 CU 资源组不自动弹性伸缩**：需人工预置/扩缩容，是"成本不可预测"的另一半来源（待核实具体行为细节）
- **双轨分类的一致性维护成本**：合规侧（SIT + 敏感度标签，人工/策略驱动）与治理侧（Data Map 扫描出的自动分类）是两套判定来源，企业需自行维护两者的一致性，否则会出现"标签说机密、扫描说公开"的矛盾结论（结构性结论；具体同步机制**待核实**）

### 1.6 技术实现线索

- **底层基于 Apache Atlas**：Data Map 的元数据与血缘模型沿用 Atlas 类型系统；支持通过 **Atlas Kafka topics（经 Azure Event Hubs）** 程序化读写元数据与血缘，官方提供 BYO Event Hubs 能力（(官方) [Kafka/Event Hubs 集成文档](https://raw.githubusercontent.com/MicrosoftDocs/azure-docs/d6c024c8112479553b79031642601aeedc7b12f9/articles/purview/manage-kafka-dotnet.md)、[Azure-Samples/purview-pubsub](https://github.com/Azure-Samples/purview-pubsub)）
- **血缘实现：静态 + 运行时双路**。静态来自扫描器对视图/存储过程/SQL 脚本的解析；运行时由外部引擎通过 Atlas 血缘 API 推送（Synapse/Data Factory 原生推送，其余需自建）
- **开放扩展点**：Data Plane REST API、Atlas 类型/血缘 API、自定义连接器解决方案加速器（(官方) [Purview 自定义连接器加速器](https://github.com/microsoft/Purview-Custom-Connector-Solution-Accelerator)）、官方成本治理指导（(官方) [CostGuidance.md](https://github.com/microsoft/Data-and-Agent-Governance-and-Security-Accelerator/blob/main/docs/CostGuidance.md)）
- **OpenLineage**：无官方原生集成，需自建桥接（Atlas ↔ OpenLineage 语义不同）；**待核实**
- **可否私有化**：**否**。Purview 是纯 SaaS（Azure 托管、区域固定），无法自托管或离线部署（待核实是否有主权云例外）
- 采集端无开源 agent 独立部署形态；数据驻留受 Azure 区域约束

---

## 2. Google Cloud Dataplex Universal Catalog → Knowledge Catalog

> **命名演变（重要）**：`Data Catalog`（旧）→ `Dataplex Universal Catalog`（约 2024，Data Catalog 并入 Dataplex）→ **`Knowledge Catalog`**（最新更名）。Google 官方保留了三级过渡文档，这本身就是"更名频繁"的证据：
> [从 Data Catalog 过渡到 Dataplex Universal Catalog](https://docs.cloud.google.com/dataplex/docs/transition-to-dataplex-catalog)、[从 Dataplex Universal Catalog 过渡到 Knowledge Catalog](https://docs.cloud.google.com/knowledge-catalog/docs/transition-dataplex-universal-catalog)、[Knowledge Catalog 总览](https://docs.cloud.google.com/dataplex/docs/introduction)

### 2.1 定位与目标客户

**GCP 原生、以 BigQuery 为重心**的统一元数据与数据治理层。目标客户是**数据平台已建立在 BigQuery / BigLake / GCS / Dataproc / Dataflow 之上**的团队，尤其是把"元数据即服务（metadata as a service）"当作平台能力来消费、并愿意用 API/IaC 管理的工程型组织。它更像**可编程的元数据基础设施**，而非端到端治理套件（(官方) [元数据管理总览](https://docs.cloud.google.com/knowledge-catalog/docs/metadata-overview)、[基础数据治理指南](https://docs.cloud.google.com/dataplex/docs/build-foundational-data-governance)）。

### 2.2 商业模式与价格

Dataplex/Knowledge Catalog 是**多计量项叠加**模型，而非单一单价：

| 计费项 | 计量单位 | 单价 | 来源 |
|---|---|---|---|
| 元数据存储 | 按**元数据对象数**（含免费额度） | 待核实 | [Knowledge Catalog（原 Dataplex）定价](https://cloud.google.com/products/knowledge-catalog/pricing)、[Dataplex 定价](https://cloud.google.com/dataplex/pricing) |
| Catalog API 调用 | 按**百万次 API 请求** | 待核实 | 同上 |
| 数据质量任务（Data Quality / Auto Data Quality） | 按 **DPU-hour**（或经 BigQuery 槽位计费；"Premium 处理层"另有计价） | 待核实 | [Auto Data Quality 总览](https://docs.cloud.google.com/dataplex/docs/auto-data-quality-overview) |
| 数据血缘（Data Lineage） | 按血缘事件/处理量计费，**非按资产数** | 待核实 | [关于数据血缘](https://docs.cloud.google.com/dataplex/docs/about-data-lineage) |
| Data Profiling / Data Discovery | 按扫描的 GB 或 DPU | 待核实 | [Dataplex 定价](https://cloud.google.com/dataplex/pricing) |
| Dataplex 处理层（Premium） | **2025-01 起计费体系发生变更** | 待核实 | (第三方) [DevelopersIO: Dataplex Premium 处理层计费变更](https://dev.classmethod.jp/articles/20250120-dataplex-cost-change/) |

**注意**：定价页 URL 同时存在 `cloud.google.com/dataplex/pricing` 与 `cloud.google.com/products/knowledge-catalog/pricing` 两个入口，锚点分别指向 `#data-catalog-pricing` 与 `#dataplex-universal-catalog-pricing`——**新旧计费段落在同一页面并存**，这既是更名痕迹，也是比价时容易踩的坑。

### 2.3 核心功能模块清单

- **Entry / Entry Group**：条目与条目组，作为元数据组织与权限边界；支持**注入自定义来源**（非 GCP 系统也可注册为条目）（[管理条目与注入自定义来源](https://docs.cloud.google.com/knowledge-catalog/docs/ingest-custom-sources)）
- **Aspect Type**：**用户可自定义的元数据模型**（可理解为元数据的 schema），可绑定到任意条目（[Dataplex v1 API 参考](https://docs.cloud.google.cn/dataplex/docs/reference/rpc/google.cloud.dataplex.v1)）
- **Business Glossary**：业务术语表，与**技术元数据目录互补**（业务文档 + 技术文档双轨）（[术语表与技术元数据关系](https://digitalcommons.usu.edu/cgi/viewcontent.cgi?article=1924&context=etd2023)）
- **Data Products**：数据产品概念，用于把资产打包为可消费单元（待核实与 Dataplex 域/区的绑定细节）
- **Data Lineage（Dataplex lineage / Data Lineage API）**：表级+**列级**血缘、血缘可视化（[血缘可视化](https://docs.cloud.google.com/dataplex/docs/lineage-views)）
- **Data Quality**：任务式数据质量 + **Auto Data Quality**（自动生成规则并评估）（[Auto Data Quality 总览](https://docs.cloud.google.com/dataplex/docs/auto-data-quality-overview)）
- **Data Profiling / Data Discovery**：统计画像与敏感数据发现（与 Cloud DLP 协同）
- **BigQuery / BigLake 深度集成**：BigQuery 表/视图元数据与血缘天然入库（[BigQuery 血缘工作方式](https://atlan.com/know/bigquery/how-data-lineage-works/)）

### 2.4 差异化所长（最有价值的一个设计）

**Aspect Type + Entry Group 的可扩展元数据模型**。这是三家当中**最开放的元数据建模能力**：企业可以自定义 aspect 类型（相当于给元数据定义自己的 schema 与字段），把它绑定到 GCP 原生资产或**通过 API 注入的外部系统条目**上，并用 entry group 做组织与权限切分。其结果是元数据平台可以被当作"**元数据基础设施**"来编程使用，而不是被厂商预置的字段集合锁死——这正是自建平台做 build-vs-buy 时必须正面对标的能力（若自建，等价物是你自己的 schema registry + 开放 API）。官方文档明确支持 ingest custom sources（[链接](https://docs.cloud.google.com/knowledge-catalog/docs/ingest-custom-sources)），说明其定位就是"可被外部系统写入"。

### 2.5 弱点/客户抱怨（真实抱怨）

- **更名频繁、文档与心智负担重**：Data Catalog → Dataplex Universal Catalog → Knowledge Catalog 两级更名，导致大量存量文档、教程、URL 失效或语义错位；第三方评测专门以"Rename, Limits, Metering"作为卖点章节（(第三方) [Atlan: Google Knowledge Catalog in 2026 — Rename, Limits, Metering](https://atlan.com/know/ai-agent/gcp/google-knowledge-catalog/)）。**用户搜索成本与迁移成本是真实成本**。
- **计费项碎片化、成本难预算**：存储/API/质量/血缘/画像各自计量，且 Premium 处理层计费在 2025-01 变更，历史上出现过"账单结构改变导致复盘困难"的讨论（(第三方) [DevelopersIO](https://dev.classmethod.jp/articles/20250120-dataplex-cost-change/)）
- **血缘覆盖以 GCP 内为主**：跨云/本地系统的血缘需自行通过 Data Lineage API 推送，覆盖度取决于客户自建成度（官方提供排障文档，侧面说明血缘问题常见：[血缘排障](https://docs.cloud.google.cn/dataplex/docs/troubleshooting-lineage)、[血缘注意事项](https://docs.cloud.google.com/dataplex/docs/lineage-considerations)）
- **强前提**：价值高度依赖数据已在 BigQuery/BigLake 上；对多云/本地为主的客户适配性差（结构性结论）
- **UI 与 IAM 组合复杂**：entry group / aspect / IAM 条件三者叠加，授权模型陡峭（(第三方) 评测类来源 [rfp.wiki Dataplex 评估](https://www.rfp.wiki/artificial-intelligence/data-analytics-governance-platforms/google-cloud-dataplex)）

### 2.6 技术实现线索

- **底层存储**：Google 托管的多租户元数据服务（**不可见、不可自托管**）；客户侧无数据库可访问
- **元数据模型**：`EntryGroup → Entry → Aspect(Type)` 三层；Aspect Type 带模板与必需字段约束，等价于"元数据 schema"（(官方) [Dataplex v1 gRPC/REST 参考](https://docs.cloud.google.cn/dataplex/docs/reference/rpc)）
- **血缘实现：静态解析 + 运行时双路**。静态来自对 BigQuery SQL / 视图定义 / 调度脚本的解析；运行时来自 BigQuery、Dataflow、Dataproc、Composer 等作业执行信息。官方明确列出血缘的**限制与注意事项**（如未能解析动态 SQL），说明静态解析是主力且存在盲区（[血缘注意事项](https://docs.cloud.google.com/dataplex/docs/lineage-considerations)）
- **API/开放扩展点**：Dataplex v1 API（gRPC + REST）、**Data Lineage API**（可写入自定义血缘）、多语言客户端（[Python 客户端库类型定义](https://documentation.s3ns.fr/python/docs/reference/dataplex/2.16.0/google.cloud.dataplex_v1.types)）
- **与 OpenLineage 关系**：Dataplex 使用**自有 Lineage API 与数据模型**（Process/Run/Event），与 OpenLineage 规范**不互通**，跨平台需自建适配器；**待核实**是否存在官方适配
- **可否私有化**：**否**。纯 Google Cloud SaaS；主权/隔离需求只能靠 [S3NS 等主权云变体](https://documentation.s3ns.fr/python/docs/reference/dataplex/2.16.0/google.cloud.dataplex_v1.types) 间接满足（待核实覆盖范围）

---

## 3. AWS：Glue Data Catalog + Amazon DataZone（→ Amazon SageMaker Catalog）

> **命名演变（重要，且是本次调研的关键发现）**：**Amazon DataZone 已被并入 Amazon SageMaker 品牌体系**。官方提供把既有 DataZone **域（domain）升级为"SageMaker 统一域（unified domain）"**的文档，且 SageMaker 定价页已并列出现 "Amazon SageMaker Catalog"（(官方) [升级 DataZone 域至 SageMaker 统一域](https://docs.aws.amazon.com/datazone/latest/userguide/upgrade-domain.html)、[Amazon SageMaker 定价](https://aws.amazon.com/sagemaker/pricing/)）。对外沟通仍大量沿用 "DataZone" 名称（文档域名为 `docs.aws.amazon.com/datazone`），**新旧名并用**是选购与内部沟通的实际风险点。

### 3.1 定位与目标客户

AWS 原生的**技术目录 + 业务目录 + 权限治理三件套**，目标客户是数据已落在 **S3 + Glue + Lake Formation + Redshift** 上的组织。三者分工需明确区分：

- **Glue Data Catalog**：**技术元数据**（Hive 兼容的表/分区/列），兼作 Athena/EMR/Redshift Spectrum 的 Hive Metastore
- **Lake Formation**：**权限中枢**（表/列/行级控制、LF-Tag 标签式 ABAC）
- **DataZone / SageMaker Catalog**：**业务目录 + 数据产品订阅 + 项目隔离**，是"面向消费方的治理门户"

即：AWS 的答案是**用三个独立服务拼出完整治理**，而非单一产品（(官方) [DataZone 术语与概念](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)）。

### 3.2 商业模式与价格

| 计费项 | 计量单位 | 单价 | 来源 |
|---|---|---|---|
| Glue Data Catalog 元数据**存储** | 按**对象数**（每 100,000 对象/月，含免费额度） | 待核实 | [AWS Glue 定价](https://aws.amazon.com/glue/pricing/) |
| Glue Data Catalog **请求** | 按**百万次请求**（含免费额度） | 待核实 | 同上 |
| Glue Crawler | 按 **DPU-hour** | 待核实（第三方常引 ~US$0.44/DPU-hour） | [AWS Glue 定价](https://aws.amazon.com/glue/pricing/)、[AWS Glue 定价解读（第三方）](https://cloudchipr.com/blog/aws-glue-pricing) |
| Glue ETL 作业 | 按 **DPU-hour**（Flex 更低） | 待核实（第三方引 US$0.308 起的 Flex 档） | (第三方) [CloudBurn: Glue $0.308 a DPU-Hour](https://cloudburn.io/blog/aws-glue-pricing) |
| **Glue Data Quality** | 评估规则时按 DPU-hour 计费；可从 Data Catalog 与 Glue Studio 使用 | 待核实 | (官方) [AWS Glue 定价页（含 Data Quality 说明）](https://aws.amazon.com/ar/glue/pricing/) |
| **DataZone / SageMaker Catalog** | **2024-11 起移除按用户订阅费，改为用量计费** | 待核实 | (官方) [DataZone 更新定价并移除用户级订阅费](https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-datazone-pricing-removes-user-subscription-fee/)、[DataZone 定价页](https://aws.amazon.com/datazone/pricing/) |
| SageMaker Unified Studio | 按用户/按用量；Catalog 为其中一部分 | 待核实 | (官方) [SageMaker 定价](https://aws.amazon.com/sagemaker/pricing/) |

**关键机制变化**：DataZone 原本存在的**按用户订阅费在 2024-11 被取消**，转为用量计费（(第三方) 解读：[DevelopersIO](https://dev.classmethod.jp/articles/amazon-datazone-pricing-removes-user-fee/)、[AWS News 摘要](https://aws-news.com/article/01930cfd-8c28-95ad-65e6-2c334bf4842a)）。**注意：任务描述中"DataZone 每用户价"这一命题已失效**——这是本次调研必须纠正的过时假设，同时也意味着"按人头计价的目录产品"这一成本模型在 AWS 侧已不复存在。

### 3.3 核心功能模块清单

- **Glue Data Catalog**：技术元数据、Crawler 自动发现、分区与统计、跨服务共享（Athena/EMR/Redshift）
- **Glue Data Quality**：基于规则的 DQ（DQDL），可从 Catalog / Glue Studio / API 触发
- **Lake Formation**：集中权限管理、**LF-Tag 标签式 ABAC**、行/列级安全、跨账户共享（(官方) [ABAC 注意事项与限制](https://docs.aws.eu/lake-formation/latest/dg/abac-considerations.html)）
- **DataZone / SageMaker Catalog 组织模型**：**Domain（域）→ Project（项目）→ Asset（资产）**，域为治理边界、项目为协作与隔离边界（[术语与概念](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)）
- **订阅/审批工作流（Subscription & Approval）**：消费方以项目名义"订阅"资产，经审批后触发**订阅履行工作流（subscription fulfillment workflow）**，自动在 **AWS Lake Formation 或 Amazon Redshift** 中创建所需权限（(官方) [DataZone 用户指南（订阅履行）](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)）
- **发布 Glue 资产到 DataZone**：数据源可配置"发布 AWS Glue 资产"（自动把技术资产导入业务目录）（[授予对 Glue 资产的访问](https://docs.aws.amazon.com/datazone/latest/userguide/grant-access-to-glue-asset.html)）
- **DataZone 数据血缘**：Glue 作业血缘（Glue v5.0 + Glue Studio 可配置采集）

### 3.4 差异化所长（最有价值的一个设计）

**"数据产品订阅制 + 项目隔离"，且订阅审批能落到真实权限**。这是三家当中**治理闭环最完整**的设计：DataZone 不只是登记"谁想看什么"，而是把审批结果**自动转化为 Lake Formation / Redshift 的实际授权**（官方文档明确描述 subscription fulfillment workflow）——即**目录即权限入口**，治理动作与访问控制是同一件事，不需要额外自建 provisioning 系统。对企业而言，这直接回答了"治理平台如何真正阻断/放行访问"这一自建平台最难做对的部分（对比：Purview 的数据访问策略能力相对薄弱，Dataplex 基本不做数据面授权）。

### 3.5 弱点/客户抱怨（真实抱怨）

- **组件割裂、概念三套**：Glue Data Catalog（技术元数据）+ Lake Formation（权限）+ DataZone/SageMaker Catalog（业务目录）三套模型、三套权限、三套控制台。用户必须在脑中维护"资产在 Catalog、权限在 LF、消费在 DataZone"的映射（(官方) [术语与概念](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-concepts.html)；结构性结论）
- **数据血缘有硬性上限**：官方用户指南明确记载**血缘运行在超过 100 张表时会失败（在第 100 张表后中止）**（(官方文档 PDF) [Amazon DataZone 用户指南](https://docs.aws.amazon.com/datazone/latest/userguide/datazone-ug.pdf)，**具体版本与页码待核实**）。对中大型数仓而言这是**功能性阻断**，而非性能问题。
- **权限排障困难**：官方专门提供 *Troubleshooting Amazon DataZone* 章节，列出 "access denied 或类似困难" 的排查路径——排障文档的存在本身即抱怨的量化证据（(官方) [DataZone 排障](https://docs.aws.amazon.com/datazone/latest/userguide/troubleshooting-datazone.html)）
- **更名与品牌迁移造成困惑**：DataZone → SageMaker Catalog / SageMaker Unified Studio 的迁移路径需要显式"升级域"，历史资料与新名不符（(官方) [升级域文档](https://docs.aws.amazon.com/datazone/latest/userguide/upgrade-domain.html)）
- **成本由多服务叠加**：Catalog 存储+请求、Crawler DPU、DQ DPU、DataZone 用量、Lake Formation、SageMaker Studio 分别计费，第三方普遍反映"看不出总额来自哪里"（(第三方) [DataZone 成本指南](https://awsnegotiations.com/datazone-cost-guide)、[SageMaker Unified Studio 成本指南](https://awsnegotiations.com/sagemaker-unified-studio-cost)）
- **是否存在双重计费的疑问**：社区问答中出现 "SageMaker Unified Studio 使用后端的 DataZone 是否额外收费" 一类问题，反映计费边界不透明（(第三方) [re:Post 问答](https://repost.aws/questions/QUiLvCEkbITXKD-GjAGvP_yg)）

### 3.6 技术实现线索

- **底层存储**：Glue Data Catalog 为**区域级 AWS 托管元数据服务**，数据模型为 Hive 兼容（database/table/partition/column），并向后兼容 Hive Metastore 协议
- **元数据模型**：Glue 技术元数据 + DataZone 的 Domain/Project/Asset/Data Product 业务层；两者通过"发布 Glue 资产"打通（[链接](https://docs.aws.amazon.com/datazone/latest/userguide/grant-access-to-glue-asset.html)）
- **血缘实现**：**以运行时为主**——血缘来自 Glue 作业/Glue Studio 执行信息（Glue v5.0 可配置），而非静态 SQL 解析；这是其血缘覆盖受限（100 表上限）的根因（[re:Invent ANT207: DataZone 数据血缘](https://reinvent.awsevents.com/content/dam/reinvent/2024/slides/ant/ANT207-NEW_Empower-your-data-journey-with-Amazon-DataZones-data-lineage.pdf)）
- **API/开放扩展点**：Glue API、Lake Formation API、DataZone API（有独立 API Reference）；所有 DataZone 动作记录到 CloudTrail（(官方) [DataZone API 参考](https://docs.aws.amazon.com/pdfs/datazone/latest/APIReference/datazone-api.pdf)）
- **与 OpenLineage 关系**：**无官方原生集成**；Glue 血缘需通过自建方式外送（**待核实**是否有官方 OpenLineage 适配）
- **可否私有化**：**否**。纯 AWS SaaS/托管服务；GovCloud/中国区为区域变体而非私有部署（待核实）
- **ABAC 限制**：Lake Formation 的标签式访问控制存在明确的**区域与场景限制**，需在设计初期确认（(官方) [ABAC 注意事项与限制](https://docs.aws.eu/lake-formation/latest/dg/abac-considerations.html)）

---

## 4. 三家横向对比（build vs buy 相关）

| 维度 | Purview | Dataplex / Knowledge Catalog | AWS Glue + DataZone |
|---|---|---|---|
| 计费主轴 | CU（容量单元）+ 每用户/每受治理资产 | 元数据对象 + API 调用 + DPU/事件（多计量叠加） | 对象数 + 请求数 + DPU + 用量 |
| 成本可预测性 | **低**（PAYG 化后更差） | 中低（项多但线性） | 中（多服务叠加，总额不透明） |
| 元数据可扩展性 | 中（Atlas 类型系统，较重） | **高**（Aspect Type 自定义 schema） | 低中（Hive 兼容 + 少数业务层字段） |
| 数据面授权闭环 | 弱（访问策略能力为主） | 基本无 | **强**（审批→Lake Formation 自动授权） |
| 血缘覆盖 | 中（静态+运行时双路） | 中高（列级，GCP 内覆盖好） | **受限**（运行时为主，100 表上限） |
| 非云生态覆盖 | 微软生态内最强 | GCP 内强，跨云弱 | AWS 内强，跨云弱 |
| 私有化 | 否 | 否 | 否 |
| 更名/迁移风险 | 中（Purview 品牌重组） | **高**（两级更名） | **高**（DataZone→SageMaker Catalog） |
| 开源互操作（OpenLineage 等） | 需自建桥接 | 需自建桥接 | 需自建桥接 |

**对自建平台的三点直接含义**：

1. **三家均不可私有化**——若"自建"的动因包含数据不出域/自主可控，则 buy 完全不成立（这是最干净的 buy 否决条件）。
2. **三家均无官方 OpenLineage 原生支持**——自建平台若以 OpenLineage 为血缘标准，反而具备互操作优势，但需自建各家元数据/血缘的双向同步。
3. **三者最强的能力各不相同**（Purview 的标签贯穿性、Dataplex 的可扩展元数据模型、AWS 的授权闭环）——自建平台不需要在每个维度都赢，但必须明确**哪一个是本企业的决胜维度**，否则会陷入"样样对标、样样不如"的陷阱。

---

## 5. 待核实清单（下一步）

1. **所有具体单价**：Purview CU/用户/受治理资产费率；Dataplex 存储/API/DQ/血缘单价；Glue 对象/请求/DPU 单价；DataZone 现行用量费率。→ 需在可联网环境抓取三张官方定价页正文。
2. **Purview 弹性 Data Map 的计量细节**与 2025-05 新定价模型的具体档位。
3. **Dataplex Premium 处理层 2025-01 计费变更的具体前后差异**。
4. **DataZone 血缘 100 表上限**的现行版本与确切范围（是否按单次运行计）。
5. **DataZone → SageMaker Catalog 的完成度**：DataZone 品牌是否已正式退役，还是长期并存。
6. **Knowledge Catalog 的发布时点与范围**（是否已在 Google Cloud Next 2026 正式发布）。
7. 每家是否有任何**官方 OpenLineage 适配**（当前判断为"无"，需确认）。
