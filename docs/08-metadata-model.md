# 08 · 元数据模型设计（Metadata Model）

> 状态：Draft v0.1
> 这一层是平台最贵的资产：**模型改一次，全平台都要跟着改**。因此模型必须少而稳，扩展必须多而活。

---

## 1. 建模思想：URN + Entity + Aspect + Edge

采用"实体-方面-关系"三段式（DataHub 证明了它的可扩展性，OpenMetadata 证明了它可以是单模型的），并用**模型注册表**把"能扩展"和"别乱扩"这对矛盾管起来。

```
URN（唯一标识实体）
  └── Entity（实体，有类型，有生命周期）
        ├── Aspect（方面 = 一组相关属性，可独立版本化、独立更新、独立授权）
        └── Edge（关系边，一等公民，带来源/置信度/时效）

模型的"定义"本身也是元数据（Model Registry）：
  EntityType / AspectType / RelationshipType / 枚举 / 校验规则 / 展示配置
  → 由 YAML 定义 → 代码生成（Java/Python/TS 客户端）→ 向后兼容校验（CI 强制）
```

三条铁律：

1. **Aspect 是变更的最小单位**。"改描述"不应该写回整个 Table 实体（否则并发写会互相覆盖，这是很多早期目录系统的经典 bug）。
2. **Edge 不是实体内部的数组**。血缘、Owner、标签如果塞进实体 JSON，就无法表达置信度、来源、生效时间，也无法独立查询；把它们提升为带属性的边。
3. **一切可扩展的东西都带 `customProperties`**，但**不要用 `customProperties` 逃避建模**：字段被 3 个以上客户/域使用时，必须升格为正式 Aspect（进 Model Registry 走评审）。

---

## 2. URN 规范

统一格式，稳定、可读、可路由：

```
urn:<namespace>:<entityType>:<key1>.<key2>...[:<subResource>]

示例：
urn:dg:dataset:prod.mysql.sales.public.orders            # 表/数据集
urn:dg:dataset:prod.mysql.sales.public.orders.customer_id # 列（作为 dataset 的子资源）
urn:dg:dashboard:superset.42                              # BI 看板
urn:dg:pipeline:airflow.daily_sales_dag                   # 调度/管道
urn:dg:job:spark.session_9f3a                             # 作业运行实例
urn:dg:domain:risk                                        # 业务域
urn:dg:dataProduct:risk.fraud_features                    # 数据产品
urn:dg:glossaryTerm:finance.net_revenue                   # 术语
urn:dg:contract:urn_dg_dataset_prod_mysql_sales_public_orders  # 契约
urn:dg:topic:kafka.prod.orders.events
urn:dg:mlModel:mlflow.fraud_scorer.v3
```

设计要点：

- **namespace 隔离环境与租户**：`prod` / `stg` / `tenantA`，避免测试元数据污染生产目录。
- **列作为子资源**：列级血缘的边指向列 URN，不指向 `(table, columnName)` 元组，避免列改名后血缘错乱（改名通过 URN 重映射表处理，保留血缘历史）。
- **联邦外部 URN**：接入 Iceberg/Glue/Unity 时保留外部唯一标识到 `externalRefs`，不强行翻译成平台 URN（避免双向映射丢失信息）。
- **URN 一旦分配永不复用**：删除的实体 URN 进"墓碑"（tombstone），避免"旧血缘挂到新表上"这类灾难。
- **重命名/迁移**：通过 `AliasAspect` 维护历史 URN 别名，查询时解析别名，血缘不断裂。
- **⚠️ URN 前缀不得依赖"可能缺失的列"**（`research/04` 补充 26 的实测发现）：以 Oracle 为例，**`OWNER` 列只在 `ALL_*`/`DBA_*` 视图中存在，`USER_*` 视图没有**。若把 `OWNER` 用作 URN 的一段（如 `platform.owner.table`），一旦采集账号权限从 `ALL_*` 降到 `USER_*` 层级，该段会**静默缺失**——URN 变形、身份错乱、血缘断裂，且**不会报错**。**约束：采集账号的权限级别属于 URN 设计输入，必须与元数据模型同期评审并写入接口契约**（同类风险见 `09` §9.1"权限静默裁剪"）。

---

## 3. 实体类型清单（v1 目标）

按域分组，标注 MVP 范围：

| 分组 | 实体类型 | 说明 | MVP |
|---|---|---|---|
| 容器/基础设施 | `Platform` | 源系统实例（mysql-prod、trino-cluster-a） | ✅ |
| | `Container` | Database / Schema / Catalog / Bucket / Project | ✅ |
| 数据资产 | `Dataset` | 表、视图、物化视图、Iceberg/Delta 表、外部表、文件集 | ✅ |
| | `Column` | 列（Dataset 子资源） | ✅ |
| | `Topic` | Kafka/Pulsar 主题 + Schema | P1 |
| | `API` | REST/GraphQL 端点及其字段 | P2 |
| | `MLModel` / `FeatureTable` | 模型、特征 | P2 |
| 处理 | `Pipeline` | DAG / 作业定义（Airflow DAG、dbt model、Spark job） | ✅ |
| | `JobRun` | 作业运行实例（承载运行时血缘与质量结果） | P1 |
| | `Query` | 捕获的查询文本（利用率、join 关系挖掘） | P2 |
| 消费 | `Dashboard` / `Chart` | BI 资产及其字段映射 | P1 |
| 语义 | `GlossaryNode` / `GlossaryTerm` | 业务词汇分层 | P1 |
| | `TagClass` / `Tag` | 分类与标签（含分级 PII/敏感级） | ✅ |
| | `Metric` / `SemanticModel` | 指标定义与语义模型（可来自 dbt/Cube） | P2 |
| 治理 | `Domain` | 业务域（组织维度） | P1 |
| | `DataProduct` | 数据产品（含 SLA、契约、负责人、端口） | P2 |
| | `Contract` | 数据契约（ODCS 兼容） | P1 |
| | `TestCase` / `TestSuite` / `TestResult` | 质量规则与结果 | P1 |
| | `SLO` / `Incident` | 服务目标与事故 | P2 |
| | `Policy` / `AccessRequest` | 策略与访问申请 | P1/P2 |
| | `Classification` | 敏感度分级（含传播规则） | P1 |
| 组织 | `User` / `Team` / `Role` | 人与组织 | ✅ |
| 运营 | `Suggestion` | AI/规则生成的待确认建议 | P2 |
| | `Announcement` / `Task` / `Conversation` | 协作 | P1 |

---

## 4. Aspect 设计（核心示例）

Aspect 是"挂在实体上的一组属性"，以下为 v1 必备 aspect（节选关键定义，其余在实现期补全为正式 schema）。

### 4.1 `DatasetSchema`（结构）
```json
{
  "aspectType": "datasetSchema",
  "fields": [
    { "name": "order_id",  "type": "BIGINT",  "nullable": false, "ordinal": 1,
      "description": "订单主键", "tags": ["urn:dg:tag:core"], "nativeType": "bigint" },
    { "name": "amount",    "type": "DECIMAL(18,2)", "nullable": true, "ordinal": 2,
      "description": null }
  ],
  "primaryKey": ["order_id"],
  "partitionKeys": [{ "field": "dt", "kind": "date", "granularity": "DAY" }],
  "foreignKeys": [
    { "fields": ["customer_id"], "refDataset": "urn:dg:dataset:prod.mysql.sales.public.customers",
      "refFields": ["id"], "confidence": 0.6, "source": "declared" }
  ],
  "schemaHash": "sha256:9f3a...",          // 用于快速判定 schema 变更 & 契约兼容性
  "rawTypeSystem": "mysql"
}
```

关键设计：
- `schemaHash` 是契约兼容性检查、变更告警、下游影响的快速触发器。
- 列级信息放 `datasetSchema` 内（性能与原子性），但**列的治理属性（描述、标签、分类、owner）走独立的 `Column` 实体**，避免"改一列描述要重写整个 schema aspect"的并发冲突。
- `foreignKeys` 带 `source`/`confidence`：隐式外键由查询日志挖掘得到，显式外键由 DDL 得到，二者不可混为一谈。

### 4.2 `Ownership`（所有权）
```json
{
  "aspectType": "ownership",
  "owners": [
    { "type": "TEAM", "urn": "urn:dg:team:risk-platform", "role": "TECHNICAL_OWNER", "since": "2025-03-01" },
    { "type": "USER", "urn": "urn:dg:user:alice", "role": "BUSINESS_OWNER" }
  ]
}
```
角色标准化：`BUSINESS_OWNER` / `TECHNICAL_OWNER` / `DATA_STEWARD` / `PRODUCER` / `CONSUMER`（与 DataHub 的 ownership types 对齐，兼容 OpenMetadata 的 owner 概念）。

### 4.3 `Classification`（分类分级）
```json
{
  "aspectType": "classification",
  "level": "L3",                        // L1 公开 L2 内部 L3 敏感 L4 机密
  "categories": ["PERSONAL_INFO", "FINANCIAL"],
  "piiTypes": ["PHONE", "ID_CARD"],
  "appliedBy": { "kind": "AUTO", "detector": "regex+ner@1.2", "confidence": 0.91 },
  "propagation": { "fromUpstream": true, "inheritedFrom": "urn:dg:dataset:...ods_user" },
  "reviewedBy": null                     // 未人工复核时健康分扣分
}
```
**传播（propagation）是分类分级最容易被做错的地方**：敏感标签必须能沿血缘向下游传播（含列级），且传播来的标签要与本地检测的标签区分，冲突时取更严格级别并标记冲突待复核。Atlas 的 classification propagation 是这块的经典实践，商业产品（Purview/Informatica）做得更完整，本方案要求 v1 就实现**列级传播 + 冲突显性化**。

### 4.4 `LineageEdge`（血缘边，独立边表而非 aspect）
```json
{
  "edgeType": "DERIVES_FROM",
  "from": "urn:dg:dataset:prod.mysql.sales.public.orders.customer_id",
  "to":   "urn:dg:dataset:prod.hive.dw.dim_customer.cust_key",
  "via":  { "job": "urn:dg:pipeline:airflow.daily_sales_dag", "runId": "scheduled__2025-06-01", "queryId": "..." },
  "source": "sql_parse",              // sql_parse | openlineage | query_log | bi_api | manual | inferred_ai
  "confidence": 0.82,
  "transform": "DIRECT",              // DIRECT | INDIRECT | AGGREGATED | MASKED | FILTER
  "transformExpression": "o.customer_id",
  "cardinality": "ONE_TO_ONE",        // ONE_TO_ONE | ONE_TO_MANY | MANY_TO_ONE（UNNEST/EXPLODE/PIVOT 会改变基数）
  "dependencyKind": "VALUE",          // VALUE | CONTROL（窗口函数 PARTITION BY / ORDER BY 是控制依赖）
  "parseLevel": "exact",              // exact | derived | table_level_only | failed（见 09 §9.2）
  "firstSeen": "2025-03-11T02:00:00Z",
  "lastSeen":  "2025-06-01T02:00:00Z",
  "observedCount": 78,
  "state": "ACTIVE"                   // ACTIVE | STALE | PENDING_REVIEW | REJECTED
}
```
四类血缘关系（都实现为边，可统一查询）：
- `DERIVES_FROM`：数据血缘（表/列级）。
- `CONSUMES`：BI/应用消费数据集。
- `WRITES_TO` / `READS_FROM`：作业与数据集。
- `JOINS_WITH`：查询日志挖掘出的高频关联（用于推荐"常一起使用的资产"）。

建模注意（依据 `research/04` §2.2 的实现细节）：
- **值依赖与控制依赖必须分开**：窗口函数的 `PARTITION BY`/`ORDER BY` 列不参与值映射但影响结果，用 `dependencyKind=CONTROL` 表达；它参与影响分析，但不参与值级血缘的传播与合规标签传播。
- **基数变化要显式**：`UNNEST`/`EXPLODE`/`PIVOT` 会放大行数，若不标注，影响面评估与行数预估都会失真。
- **`MASKED` 转换是合规的关键信号**：它决定分级标签在传播时能否被阻断（`09` §9.6），必须由解析器或人工显式声明，不能靠猜。
- `parseLevel=failed` 的边**不写入图**，只进补全队列；`table_level_only` 只写表级边——**不要用猜的列级边污染图**。

### 4.5 其它必备 Aspect（简表）

| Aspect | 挂在 | 用途 |
|---|---|---|
| `descriptions` | 几乎所有 | 分语言、分来源（人工/AI/导入）的描述，含 `provenance` |
| `tags` | 几乎全部 | 标签引用（含来源与确认状态） |
| `lifecycle` | Dataset/Topic/API | 生命周期阶段（草稿/生产/废弃/归档）+ 弃用计划 + 替代资产 |
| `trustLevel` | Dataset/Dashboard/Topic | **资产可信状态**：`CERTIFIED`（已认证）／`WARNING`（有已知问题）／`DEPRECATED`（已弃用），含认证人、认证时间、警告原因、替代资产。依据 `research/03`（Alation Trust Flags）：**用户最先想知道的是"这数据能不能信"，其展示优先级应高于"描述是否完整"** |
| `usageStats` | Dataset/Dashboard | 查询次数、独立用户数、最后访问时间（来自查询日志） |
| `qualityProfile` | Dataset/Column | 行数、空值率、唯一值数、分位数、直方图（采样） |
| `dataQuality` | Dataset | 规则通过率、最近结果、趋势 |
| `contractRef` | Dataset | 指向 Contract 实体 + 当前版本 + 兼容状态 |
| `slo` | Dataset/DataProduct | 新鲜度/质量/可用性目标与达成率 |
| `documentation` | 全部 | 富文本/链接（Runbook、Wiki、SOP） |
| `accessPolicySummary` | Dataset/Column | 生效策略摘要（只读投影，真相在 Policy 服务） |
| `customProperties` | 全部 | 逃生舱：未被建模的属性（带命名空间前缀，需登记） |

---

### 4.6 关系语义：删除与级联（易被忽略但很关键）

只定义"有边"是不够的，必须定义**边的语义**：删掉父容器时，其下资产怎么办？删掉术语时，引用它的资产怎么办？Apache Atlas 的 `relationshipCategory`（`ASSOCIATION` / `AGGREGATION` / `COMPOSITION`）是三者中唯一把这件事建模进类型系统的地方，值得借用：

| 关系类别 | 语义 | 平台示例 | 删除父对象时 |
|---|---|---|---|
| `COMPOSITION`（组合） | 子对象不能脱离父对象存在 | `Container──contains──Dataset`、`Dataset──hasColumn──Column` | **级联软删除**（子对象进墓碑，血缘历史保留） |
| `AGGREGATION`（聚合） | 子对象可独立存在 | `Domain──groups──Dataset`、`DataProduct──exposes──Dataset` | 仅解除关系，子对象保留 |
| `ASSOCIATION`（关联） | 纯引用 | `Dataset──taggedWith──Tag`、`Dataset──implements──Contract` | 仅删除边；被引用的对象若成为"孤儿引用"则提示清理 |

平台规则：
- 所有删除默认**软删除 + 墓碑**（见 §5），级联删除需二次确认并展示影响面（走 §3.4 影响分析）；
- 采集产生的"消失"（源系统表被删）**默认不级联删除下游治理信息**，只标记 `lifecycle=DELETED_AT_SOURCE` 并把人工成果（描述、术语映射、质量规则）保留 N 天，允许"复活"；
- 术语/标签/契约等被引用对象的删除必须检查引用方，禁止静默产生悬空引用（定期一致性对账任务负责发现）。

---

## 5. 版本化、审计与时间旅行

| 需求 | 设计 |
|---|---|
| 谁改了什么 | `audit_log`（append-only）：actor、action、entityUrn、aspectType、before、after、requestId、sourceIp、apiKeyId |
| 回滚/对比 | Aspect 版本表保留最近 N 版（默认 50 版 / 或按重要性无限），UI 提供 diff |
| 时间旅行 | 事件流支持按时间点重建（`asOf` 查询）：用于"上月这张表的 schema 是什么"与合规取证 |
| 软删除 | 实体删除 → 墓碑 + 保留血缘历史；N 天后可硬删（可配置，受合规保留策略约束） |
| 批量变更审计 | 每次采集是一个 `run`，其所有变更打同一 `runId`，可整体回滚（"这次采集误删了 300 张表"必须能一键回退） |

**批量回滚能力是很多开源平台的缺口**：错误采集导致目录被污染后只能手工修，企业用户对此极其敏感。

**版本化的适用范围（避免过度实现，`research/04` §3.2）**：不要对所有 aspect 做全字段 SCD2——那会让存储与查询双双失控。建议**混合策略**：
- **骨架列用真列**（`urn`、`type`、`updated_at`、`state` 等参与索引与过滤的字段）；
- **扩展属性用 JSONB + GIN 索引**（保留灵活性，不随模型变更重建表）；
- **关系用独立边表**（血缘、owner、标签，带来源与时效）；
- **SCD2 只覆盖治理关键属性**：`owner` / `tier`（分级）/ `classification` / `domain` / `policy`——这些是审计与合规真正需要"某时刻是什么"的字段；描述、文档等变更走普通版本表（保留最近 N 版）即可。

---

## 6. 扩展机制（Extension Model）

三级扩展，成本递增，v1/v2 分别交付：

| 级别 | 方式 | 能力 | 需要重启/改核心？ |
|---|---|---|---|
| L1 数据扩展 | `customProperties` + 自定义标签 | 存任意键值、可搜索 | 否 |
| L2 模型扩展 | **Model Registry**：YAML 定义新 EntityType/AspectType/Relationship | 新实体进图、进搜索、进权限、进 UI 泛化页 | 否（热加载 + 代码生成） |
| L3 逻辑扩展 | 扩展包（Plugin）：Connector / QualityRule / Detector / Enricher / AI Skill / PolicyCompiler | 新增行为 | 独立进程/模块加载 |

L2 的 YAML 形态（示意）：
```yaml
# model/extensions/feature_store.yaml
entityType: FeatureTable
displayName: 特征表
parents: [Container]
aspects:
  - ref: core/ownership            # 复用核心 aspect
  - ref: core/tags
  - ref: core/descriptions
  - name: featureSpec              # 新 aspect
    searchable: true
    properties:
      entityRef:   { type: ref, target: Dataset, required: true }   # 特征源表！
      freshnessHz: { type: enum, values: [REALTIME, HOURLY, DAILY] }
      ownerModel:  { type: ref, target: MLModel }
relationships:
  - name: FEATURE_OF
    from: FeatureTable
    to: MLModel
    lineage: true                  # 参与血缘图与影响分析
```

**CI 强制**：模型变更必须通过向后兼容校验（不允许删字段、改类型、收紧枚举、改语义），违者阻断合并——这防止"新增扩展把老客户端/AI Agent 打爆"。

---

## 7. 与外部标准的映射

### 7.1 OpenLineage 接收映射
| OpenLineage 概念 | 平台映射 |
|---|---|
| `RunEvent` + `Job` | `JobRun` 实体（`job` 解析或落为 `Pipeline` 的 run） |
| `inputs[] / outputs[]`（`Dataset` + `facets`） | `Dataset` 实体 + `DERIVES_FROM` 边（`source=openlineage`，confidence 0.95） |
| `SchemaDatasetFacet` | `datasetSchema`（若为首次见到则创建；与已采集 schema 冲突 → 标记冲突） |
| `ColumnLineageDatasetFacet` | **列级血缘边**（优先级最高，直接采信） |
| `DataQualityAssertionsFacet` | `TestResult` + `dataQuality` |
| `ParentRunFacet` | 关联 DAG 与子作业层级 |
| `SQLJobFacet` | 解析 SQL 作为补充血缘来源（与 facet 交叉验证） |

实现注意（来自 `research/04`）：
- `columnLineage` facet 的方向是 **"输出列 → 输入列"**（`fields: {"<输出列>": {inputFields: [...]}}`），与平台边表方向（`from` 为上游、`to` 为下游）相反，映射时必须显式反转，这是最常见的实现错误；
- facet 的 `transformations[].type`（`DIRECT` / `INDIRECT`）映射为平台的 `transform` 枚举（`IDENTITY` / `DERIVED`）；带 `masking` 子类型的转换要额外登记为"已脱敏"，参与分级传播的判断（§4.3）；
- OpenLineage 只标准化事件形状，**不标准化数据集命名**（各实现的 `namespace` 语义不一），因此必须做命名归一化与冲突检测（同名不同物 / 同物不同名）。

### 7.2 ODCS（Open Data Contract Standard）映射
契约 YAML 中的 `schema`、`quality`、`slaProperties`、`stakeholders`/`team`、`roles`、`servers` 分别映射到 `datasetSchema`、`TestCase` 生成、`slo`、`Ownership`、策略角色、`Platform` 连接信息。**契约是"可执行的元数据"**：契约里的 quality 定义直接生成质量规则，而不是只做文档。

实现注意（来自 `research/04`）：
- **版本基准统一为一句话**：本平台以 **ODCS v3.2.0**（Bitol/LF AI & Data 治理，Apache-2.0）为契约格式基准；**"`required`/`unique`/`primaryKey` 可隐式推导为可执行断言"这一特性自 v3.1.0 引入**（`19` 独立评审事实项 6 指出：各文档曾分别写 v3.1/v3.2，此处统一，避免"兼容哪个版本"无唯一答案）。对接前仍需复核 `research/04` 附录·补充 7 的字段级细节。
- ODCS 的 `quality` 段区分 **五类**：`library`（用 `metric` 如 `nullValues`/`rowCount`/`freshness` + `mustBe`/`mustNotBe` 表达断言）、`reconciliation`（跨源对账，靠 `source`/`target` 差异化字段）、`custom`（引擎配置经 `customProperties` 传递）、`sql`（内联 SQL 断言）、**`text`（自然语言规则）**。⚠️ **`text` 类是关键细节**：它**不自动执行**，是供人或 LLM 消费的规则描述——因此它天然是 `13` AI 层的输入（AI 可读取自然语言口径并据此检查/生成结构化规则），**但绝不能当作已生效的校验**。原稿只写了四类，漏了 `text`（`research/04` 已修正）。
- 契约 `status` 的完整枚举为 **`proposed`｜`draft`｜`active`｜`deprecated`｜`retired`**（注意有 `proposed`，别漏）。
- 以上内容（含 `required`/`unique`/`primaryKey` 的隐式推导）正好对应平台规则 DSL 的"声明式 + SQL 断言"两种前端（`09` §9.4），无需自造语法；
- 每条断言带 `dimension`（DAMA 六维）与 `severity`/`businessImpact`，可直接驱动告警级别与 SLO 计算；
- **`apiVersion`（规范版本）与 `version`（契约版本）必须分开治理**：前者决定解析器兼容性，后者才是业务变更；混为一谈会导致"换了标准版本却被当成破坏性业务变更"；
- 生态工具 **`datacontract-cli`（MIT）的能力此前被低估**（`research/04` 已修正）：`export` 支持 **16 种**格式（含 `odcs`/`sodacl`/`dbt`/`dbt-staging-sql`/`great-expectations`/`sql`/`jsonschema`/`avro`/`protobuf`/`rdf`/`bigquery`/`html`/`excel`/`markdown`），`import` 支持 13 种（`sql`/`dbt`/`glue`/`bigquery`/`databricks`/`snowflake`/`unity`/`postgres`/`excel`…）。**这条直接强化了 `09` §9.4 的"IR + 多编译器"设计可行性**：契约可以作为"多引擎规则的单一事实源"，并用现成 CLI 完成转译——**所以我们更不该自研转译器**。平台的 CI 插件应对齐这套能力而非另造一套。


### 7.3 Iceberg REST / Glue / HMS 联邦
外部 Catalog 作为 `Platform` + `Container` 存在，其表以 `Dataset` 投影（`externalRefs` 保留原生标识）；不复制数据，只缓存元数据并定期刷新；若外部 Catalog 是权威，则平台侧对应 aspect 标记 `readOnly: true`（避免"双向编辑打架"）。

**实现建议（依据 `research/05` §10）：直接用 Apache Gravitino 作为联邦层，不自研。** 它已于 2025-09 发布 1.0.0 并毕业为 ASF 顶级项目，原生解决"接一堆异构 Catalog"这件最费力的脏活（Metalake → Catalog → Schema → Table/ Fileset / Topic / Model 的统一对象模型 + REST API + 内建多级访问控制）。自研联邦层预计要多花 6–12 个月，且没有任何差异化价值——**平台的差异化在治理闭环，不在连接异构 Catalog**。

---

### 7.4 对外互操作出口（降低锁定、便于迁移与共存）

内部模型自持，但**对外必须提供标准格式的进出口**，否则无法与前后的工具链共存，也无法让客户放心（`16` §4）：

| 出口 | 格式 | 用途 |
|---|---|---|
| **元数据标准出口** | **OpenMetadata Standards JSON Schema** 形态的导出/导入 API | 与 OM 生态互操作、作为"可回切"的保险（`research/04` §1.2 结论） |
| 血缘事件出口 | OpenLineage 兼容 endpoint（可写可读） | 作业与其它平台零改造对接 |
| 血缘语义标准 | **W3C PROV-O 映射**（`used` / `wasGeneratedBy` / `wasDerivedFrom`，按域用 Bundle 划分） | 无论采集端来自 OpenLineage、Atlas 还是自研，都能获得标准语义与可迁移性（`research/04` 附录·补充 16 的结论：血缘统一收敛到 PROV-O） |
| 契约出口 | ODCS YAML（原样导出，保留 `apiVersion`/`version` 分离） | 契约可脱离平台独立使用（Git 化、`datacontract-cli` 校验） |
| 治理即代码出口 | `dgctl export` 输出契约/策略/规则/术语的 YAML 目录 | GitOps、Code Review、批量迁移 |
| 对外门户出口 | DCAT-AP / schema.org Dataset（可选） | 面向外部数据开放门户或跨组织共享场景 |
| 全量快照 | 实体/方面/边/审计的脱敏快照 | 备份、迁移、审计取证、迁移对账 |

**设计约束**：出口格式一旦发布即视为对外契约（需版本化与兼容性承诺），不得随内部模型随意变动；映射层是独立模块，内部模型演进时由映射层吸收差异。

> ⚠️ **v1 只承诺两个对外契约（采纳 `19` 评审过度设计项 6）**：**OpenLineage（血缘事件）** 与 **ODCS（契约）**。上表其余出口（OpenMetadata Standards JSON Schema、PROV-O、`dgctl export`、DCAT-AP/schema.org、脱敏快照）在 v1 标记为**"实验性导出，不承诺兼容性"**，仅用于内部迁移与验证。
> 理由（评审原文）：**出口即对外契约，列得越多、回切与演进成本越高**。承诺范围应随版本逐步扩大，而不是一次性铺开 7 类。

---

## 8. 模型的反模式（明确禁止）

1. **把血缘、Owner、标签塞进实体 JSON** → 无法独立版本化/授权/查询。
2. **用宽表存所有实体类型** → 字段膨胀、语义混乱；应"统一信封 + 类型化 aspect"。
3. **实体内部引用其他实体只用名字** → 必须用 URN，名字会变。
4. **删除即物理删除** → 血缘断裂、审计缺失。
5. **无 `source`/`confidence` 的血缘** → 用户无法判断可信度，最终整体不信任。
6. **无 `customProperties` 命名空间** → 各团队键名冲突、无法清理。
7. **模型变更不做兼容校验** → 扩展一次，全平台客户端崩。
