# 09 · 核心子系统设计

> 状态：Draft v0.1
> 本文逐个子系统给出：职责、内部结构、关键算法/协议、与其它子系统的接口、v1 交付范围、已知难点与取舍。

---

## 9.1 采集与集成框架（Ingestion Framework）

### 职责
把 80+ 种异构系统的元数据，可靠、增量、可观测地变成平台统一模型。**这是治理平台工作量最大、也最能决定成败的部分**（连接器是无底洞，必须有框架而非堆代码）。

### 结构（Source → Stage → Sink 流水线）

```
CollectionJob（一次采集运行，有 runId / 状态 / 指标）
  ├─ Source        : 连到源系统，产出"原始元数据"（保留原生结构，不做翻译）
  ├─ Extractor     : 分页/增量拉取（watermark、cursor、批量大小、限流、重试、超时）
  ├─ Normalizer    : 原生结构 → 统一模型 Entity/Aspect（每个 connector 只写这块）
  ├─ Enricher      : 补充信息（owner 从 AD/HR 系统、tags 从文件、描述从 dbt meta）
  ├─ Differ        : 与上次快照对比 → 生成 ADD/UPDATE/DELETE 变更集（Stateful Ingestion）
  ├─ Validator     : 模型校验 + 一致性校验（例如"本应 500 张表只采到 3 张" → 拒绝提交）
  └─ Sink          : 批量提交 MetadataChangeProposal 到 Metadata Core（幂等、可重放）
```

### 关键工程要求（企业落地的真正门槛）

| 要求 | 设计 |
|---|---|
| **增量 + 幂等** | 以 `(urn, aspectType)` 为幂等键；提交带 `expectedVersion`（乐观锁），冲突则重读重算，杜绝"采集覆盖人工编辑" |
| **状态存储** | `ingestion_state`：上次水位、上次 schema 快照哈希、游标；支持重置（full refresh） |
| **绝不覆盖人工内容** | 每个 aspect 字段级保留 `source`：`AUTO_COLLECTED` 的字段不得覆盖 `MANUAL` 的字段（**这是开源平台最常见的客户投诉之一**） |
| **安全阀** | 单次采集最大删除数阈值、最大变更比例阈值（如 >30% 视为异常则挂起待人工确认）；连接失败快速失败不写脏数据 |
| **命名空间隔离防误删** | 每个连接器实例独占一个命名空间前缀（`platform` + 连接名 + 环境），**删除检测只允许在自己命名空间内生效**——这是防止"配置改错导致整批资产被标记删除"的关键隔离（`research/04` §5.1 的实测教训） |
| **内容指纹增量** | 除水位外，用"实体内容指纹"判断是否真变更（避免每次采集产生海量无意义事件）；指纹变化才发事件 |
| **凭证管理** | 不落明文：引用 KMS/Vault 密钥路径；连接测试与凭证轮换 UI；最小权限只读账号模板（每种源系统给 DDL 模板） |
| **隔离与代理** | 支持 HTTP(S) 代理、SSH 隧道、私有网络 Agent 推模式 |
| **限流与成本** | 单源并发上限、QPS 上限、大表采样而非全表（profiling 默认采样 10k 行 + 可配置） |
| **采集自身的可观测性** | 每次 run 的结果（新增/更新/删除/跳过/失败）、耗时、错误明细、趋势；采集失败告警（**"目录悄悄停止更新"是治理平台最常见的隐性失效**） |

### 实现陷阱（编码前必须知道，来自 `research/04` 附录·补充 21–28）

这些是"压测通过、上线后炸掉"的典型项，必须在实现规范里写死，而不是靠工程师自觉：

| 领域 | 陷阱 | 正确做法 |
|---|---|---|
| **调度并发** | K8s CronJob 默认 `concurrencyPolicy: Allow` → **同一采集任务并发重复执行** | 显式设 `Forbid`；采集任务互斥**优先用 PG advisory lock（`pg_advisory_xact_lock`，崩溃不残留）**，不要用缺 fencing token 的 Redlock（GC 停顿下无法保证互斥） |
| **增量漏采** | 仅用 `updated_at` 做游标时，**长事务晚提交的行会被永久漏采** | 复合游标 `(updated_at, id)` + 安全上限 `now() - safety_lag`；**`pg_replication_slots.wal_status = lost` ⇒ 增量链路不可恢复，唯一正确动作是重新全量快照** |
| **限流与熔断** | 令牌桶放进程内存时，**实际 QPS = 速率 × 副本数** | 限流放共享存储（Redis + Lua）；尊重 `Retry-After`（注意**限流也可能返回 403 而非 429**）；熔断粒度必须**单源**，打开期标 `DEGRADED` 而非 `FAILED` |
| **权限静默裁剪** | **SQL Server 的 `INFORMATION_SCHEMA` 会按账号权限静默裁剪**（表现为"元数据稀疏"而非报错）；**MySQL `TABLE_ROWS` 是估算值**；**Snowflake `ACCOUNT_USAGE` 需账户级监控角色（`ACCOUNTADMIN` 或 `GOVERNANCE_VIEWER` 一类）** | 用高权限只读账号；**并加"实体数骤降"护栏**——对照上次快照，骤降即中止并告警、**拒绝软删**（即 DataHub 的 Entity Count Validation Failure）。**必须认识到：权限不足的后果是"元数据缺失被误判为对象不存在"**——两者在采集结果里长得一样，所以采集账号权限清单必须与元数据模型同期评审（`research/04` 补充 26） |
| **Hive 元数据** | **直连 HMS 元数据库会绕过 `MetaStoreFilterHook`，等于绕过 Ranger 过滤** | 走 HMS Thrift API；增量用 `NOTIFICATION_LOG` + `get_next_notification` + `eventId` 单调游标；Hive 3.0 起 SerDe 迁到独立 `SERDES` 表、`CD_ID` 不再与 `TBL_ID` 一对一 |
| **Iceberg 统计** | `lower_bounds`/`upper_bounds` 是 `map<int,binary>`，**v1 用带长度前缀编码、v2+ 用 single-value serialization——用 v2 解码器读 v1 表会得到静默错误的边界值** | 按 `format-version` 选择解码器，并对边界值做合理性校验 |
| **凭据轮换** | ESO `refreshInterval` 默认 1h，**对 TTL=1h 的动态凭据会在过期后才刷新** | 遵循 `refreshInterval ≤ TTL/2`；KEK 轮换只需 re-wrap DEK，**不需重新加密数据** |
| **Delta 扩展点** | 治理信息无处安放时会另建映射表，增加同步负担 | **Delta 的 `domainMetadata` 是官方扩展点**——分类分级、责任人可直接落在表格式自身，比另建映射表更稳（`research/04` 补充 26–28） |
| **库默认值在生产场景失效**（比参数清单更危险的一类坑） | ① **resilience4j `minimumNumberOfCalls` 默认 100** —— 采集任务调用量远低于 Web 服务，**样本长期不足会导致熔断器根本不评估失败率，出现"坏了也不熔断"**（建议显式降到 20 左右）；② **Quartz `misfire` 默认 `MISFIRE_INSTRUCTION_SMART_POLICY`**（由框架自行决定）—— 与采集要求的**确定性重跑语义**冲突，必须显式覆盖；③ **K8s `failedJobsHistoryLimit` 默认 1** —— 采集失败现场会被自动清理，**事后无法排障**，生产须调大；④ **APScheduler `coalesce` 默认 True** 是对的（错过触发合并为一次，**避免停机恢复后的"补偿风暴"打爆源端**），但需显式确认而非依赖默认 | 逐项**显式设置**而非依赖库默认：凡是"影响正确性/可排障性/源端稳定性"的参数，都要在实现规范里写明取值与理由 |

### 落地参数速查表（实现附录，"默认"与"建议"已区分）

来自 `research/04` 附录·补充 21–28 的实测取值，可直接作为编码依据：

| 参数 | 取值 | 性质 |
|---|---|---|
| 增量水位安全上限 `safety_lag` | 5–30 s（按源端最长事务 P99 定） | 建议 |
| 增量批大小 | 1000–5000 | 建议 |
| Debezium `incremental.snapshot.chunk.size` | 1024（默认）；**大表降至 256** | 默认 + 建议 |
| 复制槽告警阈值 | WAL `> 5 GB` 或 `wal_status != 'reserved'` | 建议 |
| 令牌桶 | 稳态 = 配额 × 0.75；burst = 稳态 × 3（**须放 Redis，否则 QPS × 副本数**） | 建议 |
| 退避策略 | base 500 ms / cap 20 s / 最多 5 次 / **Full Jitter** | 建议 |
| 熔断 `minimumNumberOfCalls` | **20**（库默认 100 对采集场景偏大，会"坏了也不熔断"） | 建议 |
| 连接池大小 | `core × 2 + spindles` | 建议 |
| ESO `refreshInterval` | ≤ 动态凭据 TTL / 2（默认 1 h 对 TTL=1h 的场景会在过期后才刷新） | 建议 |
| 失败处理 | 重试 3 次 → **DLQ（须可枚举、可重放）** | 建议 |
| Job 历史保留 | `successfulJobsHistoryLimit` / `failedJobsHistoryLimit` 调大（默认 3/1 会清掉失败现场） | 建议 |

### 连接器分层与优先级

| 层 | 目标 | v1 范围 |
|---|---|---|
| 数据库/数仓 | MySQL、PostgreSQL、Oracle、SQL Server、Hive、ClickHouse、Doris/StarRocks、Snowflake、BigQuery、Redshift、Trino | MySQL、PG、Hive/HMS、ClickHouse、Trino、Doris（6 个） |
| 湖仓/表格式 | Iceberg、Delta、Hudi（经 REST Catalog / HMS / Glue） | Iceberg REST + HMS |
| 转换/调度 | Airflow、dbt、Spark、Flink、DolphinScheduler | Airflow、dbt、Spark |
| 消息 | Kafka、Pulsar、Schema Registry | Kafka + SR |
| BI | Superset、Tableau、PowerBI、Metabase、Looker、Qlik、**SAP BusinessObjects** | Superset、Metabase、Tableau；**SAP BO 走 `/biprws/...`（WebI）与 `/sl/v1/...`（语义层/Universe）**——传统 CMS SDK 的职责已主要由 RESTful Web Service SDK 承担（`research/04`，该演进的官方原文待核实）。**SAP BO / Qlik 在传统行业（制造、金融）占比高而开源平台普遍不支持，是"白名单外按需共建"的典型候选** |
| 对象存储/文件 | S3/OSS/MinIO + CSV/Parquet schema 推断 | S3 + Parquet |
| 其它 | MLflow、Feature Store、API 网关 | P2 |

### 复用策略（重要）
**不要从零写 80 个连接器。** 建议路径：
1. 框架自研（保证模型与增量语义可控）；
2. 连接器**优先移植/参考** OpenMetadata（Apache-2.0，Python，connector 覆盖最广）与 DataHub（Apache-2.0）的现成实现，用我们的 Normalizer 适配层包装，而不是重写采集逻辑；
3. 原生协议优先（HMS Thrift、Iceberg REST、JDBC DatabaseMetaData、BI 官方 API），避免爬页面。
> 许可与合规：Apache-2.0 允许商用与修改，但必须保留版权声明、标注来源；不可直接拷贝 GPL/AGPL 实现（例如部分商业产品的 SDK）。

---

## 9.2 血缘引擎（Lineage Engine）

### 职责
把"数据从哪来、到哪去、中间怎么变"变成带可信度的图，支撑影响分析、根因分析、合规传播、AI 上下文。

### 血缘来源与置信度模型（六路汇聚）

| 来源 | 覆盖 | 精度 | 默认置信度 | 时效 |
|---|---|---|---|---|
| 运行时上报（OpenLineage / Spark / Flink hook） | 中（需改造作业） | 高（真实读写） | 0.95 | 实时 |
| SQL 静态解析（查询日志 SQL / 视图 / dbt manifest / 逻辑计划） | 高（有 SQL 就有） | 中高（列级可达 85–95%） | 0.80 | 准实时（CI/采集时） |
| **代码静态分析**（ETL 脚本、存储过程 PL/SQL、Java/C# 作业代码、调度 DAG 定义） | 中（传统数仓的关键补充） | 中（依赖代码可读性） | 0.75 | 准实时（CI/仓库扫描） |
| 查询日志挖掘（query history / BI） | 高（无需改造） | 中（读关系准，写关系需推断） | 0.70 | 延迟（按天） |
| 人工/BI API 映射 | 低（成本高） | 最高 | 1.00 | 事件驱动 |
| AI 推断（LLM 读代码/文档） | 补充语义与命名相似 | 低 | ≤0.5，必须人工确认 | 按需 |

**非 SQL 来源的具体抓取手段（实现时照此落地，依据 `research/04` §2.3）**：

| 来源 | 手段 | 列级 |
|---|---|---|
| Spark | `QueryExecutionListener.onSuccess` → `qe.analyzed()` 遍历 Catalyst `TreeNode`；**`AttributeReference.exprId` 是跨算子追踪列的稳定锚点**（注意 `executionId` 跨层不可靠，需自带关联 ID） | 可 |
| Flink | `StreamGraph→JobGraph→ExecutionGraph`；SQL 侧 `FlinkPlannerImpl`(Calcite) → `getColumnOrigins()` | 静态可 |
| dbt | **`manifest.json` 是最佳非 SQL 血缘源**：`nodes[].depends_on.nodes/refs/sources`、`compiled_code`、`parent_map`/`child_map`、`exposures` | 需解析 `compiled_code` |
| 调度/代码 | Airflow `inlets`/`outlets` 已弃用 → 走官方 OpenLineage Provider；Dagster 用 `AssetKey` 图 + `TableColumnLineage`；Python/ETL 脚本用 `ast` 识别（准确率差，**只做表级 + 显式 `@lineage(...)` 声明**） | 部分 |
| BI | Tableau Metadata API（GraphQL `publishedDatasources→fields{upstreamColumns}`）、Power BI Scanner API（`getInfo`→`scanResult`）、Looker API 4.0、**Metabase MBQL 的 `:source-table`/`:field` 直接给列级映射** | 字段级 |
| 数仓查询日志 | Snowflake `ACCESS_HISTORY.base_objects_accessed.columns[]` + `GET_LINEAGE()`、**Databricks `system.access.column_lineage`**、BigQuery `INFORMATION_SCHEMA.JOBS_BY_PROJECT` + **Data Lineage API（`datacatalog.lineage`）**、Redshift `STL_QUERY`/`STL_DDLTEXT`、ClickHouse `system.query_log` | 列级（**这是覆盖率最高的路径**——引擎自己记录了真实读写）。**BigQuery 的 Data Lineage API 提供 `ProcessOpenLineageRunEvent`——可直接吞 OpenLineage 事件、无需自行解析 query 文本，优先走它**；ClickHouse 物化视图血缘可用 `system.tables.create_table_query` + `dependencies_*` 重建 |

- **代码静态分析为什么必须单列一路**（来自 `research/03` 对 IBM Manta 的调研）：真实企业（尤其传统行业数仓）大量血缘藏在**存储过程（PL/SQL）、ETL 脚本、Java/C# 作业代码**里，SQL 解析与运行时上报都覆盖不到。只做 SQL 解析会系统性漏掉最重的一块，导致"血缘覆盖率看着不低，关键链路却是断的"。
- **血缘引擎要可解耦、可被外部消费**：设计成独立可部署的引擎（输入：SQL/代码/OpenLineage 事件；输出：标准格式血缘），既能被本平台用，也能导出给其它平台（这一点同样是"可导出 = 可回切"原则的体现）。
- **领域模型可对齐 Marquez**（OpenLineage 的参考实现，Apache-2.0）：核心对象 `namespace / job / dataset / run / DatasetVersion`，查询面用 `nodeId + depth`；存储分层照抄它的"**主存 + 可选搜索索引**"（搜索索引不是必需件）。这样做的收益：① 与 OpenLineage 事件语义天然一致，映射无损耗；② 沿用一套已被验证的领域模型，省掉自研建模的试错（`research/05` §10）。
- **开放写入面**：必须允许外部系统通过 API 批量写入自定义实体与血缘（阿里 DataWorks 的做法）。**血缘覆盖率的上限往往由"能否接入非自家引擎产生的血缘"决定**，而不是由解析器能力决定。

**融合算法**（避免"多条低置信边的乘积"这类错误）：
```
confidence(edge) = 1 - Π(1 - c_i × w_i × ρ_i)   // "至少一个为真"模型
  w_i = 来源权重（人工 1.0 / 运行时 0.95 / 解析 0.8 / 日志 0.7 / AI ≤0.5）
  ρ_i = 相关性折减系数（默认 1.0；同族来源取 0）
```
- **必须处理来源相关性**（`19` 独立评审事实项 4 指出的统计缺陷）：静态解析、查询日志、运行时上报在同一作业上**高度相关**（同一段 SQL 被三种方式看到 ≠ 三个独立证据），直接相乘会**系统性高估置信度**。
  - "同族"定义：**同一份原始证据的不同加工方式**（同一段 SQL 被 sqlglot 与 Calcite 分别解析、同一条 query log 被两种规则挖掘）= 同族 → `ρ=0`（族内只取最大值）。
  - 跨族（解析 vs 运行时 vs 人工）= 独立 → `ρ=1`。
  - `w_i` 与 `ρ_i` **必须用真实数据在 Spike S1 中拟合**，不得拍脑袋写死。
- 来源同族（例如都是 sql_parse 的不同解析器）→ 取最大值而非叠加（避免虚假高置信）。
- 冲突检测：同一列有两条互斥的 `transformExpression`（例如 A→C 与 B→C 的映射不同）→ 标记 `CONFLICT`，降级为待复核。
- 时间衰减：`lastSeen` 超过该作业 3 个调度周期未见 → `STALE`，置信度按半衰期衰减，UI 虚线显示。

### SQL 列级血缘解析

```
原始 SQL（含方言）
  → 方言归一化（sqlglot 方言集：hive/spark/postgres/mysql/bigquery/snowflake/trino/doris…）
  → AST
  → 作用域解析（scope resolution）：CTE、子查询、UNION、窗口函数、别名、星号展开
  → 列级表达式求值：为每个输出列计算"来源列集合 + 转换类型"
  → 无法解析 → 降级为表级血缘 + 标记 UNKNOWN（显式降级，不假装成功）
```

**工程化的三层解析流水线（可直接落地，依据 `research/04` 附录·补充 20）**：

| 层 | 做什么 | 关键点 |
|---|---|---|
| **L0 预筛** | 切分语句 + 粗判类型，丢弃无血缘价值内容（注释 / `SET` / `USE`） | 只需 token 级切分（`sqlparse.split` 足够）——**不要在这一层做语义分析** |
| **L1 主解析** | `sqlglot.parse_one` + `qualify` + `lineage()` 产出列级血缘 | ① **必须设置生产韧性开关 `error_level`（`RAISE`/`WARN`/`IMMEDIATE`/`IGNORE`）+ `max_errors`**——批量解析海量 SQL 时若不设，**一条坏 SQL 会中断整批**（这是最容易在压测通过、上线后炸掉的点）；② 校验失败或解析异常 → **降级表级 + 落"解析异常样本库"**（样本库是把方言覆盖率变成可运营指标的关键资产）；③ 可用 sqlglot 自带 Executor 在 CI 上用**小样本回放验证"血缘规则本身"的正确性**（多数团队不知道这个能力，它能显著降低规则回归成本） |
| **L2 校验/复杂语义** | Calcite（JVM）或引擎原生逻辑计划做类型/作用域校验，精解 `MERGE` 与复杂类型 | 只对核心数仓层启用（成本考虑） |
| 横切关注点 | Schema/元数据服务（列名 + 类型 + 大小写规则）、**方言注册表与版本锁定**、结果缓存（`hash(sql)+dialect+schema_version`）、置信度打分、UI 区分值依赖/控制依赖 | 这些不是"某层的功能"，而是贯穿三层的平台能力 |
关键难点与对策（这部分决定血缘可信度，必须提前设计；下表依据 `research/04` §2.1–2.2 的实测与 issue 证据）：

| 难点 | 具体表现 | 对策 |
|---|---|---|
| **大小写折叠语义** | Snowflake 未加引号折大写、Hive/Spark/Doris/StarRocks/PG 折小写、BigQuery 用反引号、T-SQL 用 `[]` —— **列级血缘错配的第一大来源** | 解析前按方言做标识符归一化（用 schema 中的真实列名做校正）；列名匹配以"归一化 + 原始名"双份保存 |
| 未知方言/私有语法 | 覆盖率长尾（第三方实测单一宽松解析器真实语料覆盖仅约 32%） | 可插拔方言适配器 + **方言能力矩阵**（每方言标注支持级别）；解析失败率按方言监控并作为治理指标 |
| `SELECT *` | schema 未知时无法展开而断链 | 结合 schema 展开；未知时记 `WILDCARD_UNRESOLVED` 通配边并降置信度，schema 到位后重算 |
| 复杂类型下钻 | `STRUCT/ARRAY/MAP`、Snowflake `VARIANT`+`FLATTEN`、Spark 高阶函数 | 以字段路径（`s.a.b[0]`）表达列标识，不塌缩为顶层列 |
| **基数放大** | `UNNEST` / `LATERAL VIEW EXPLODE` / `PIVOT` / `UNPIVOT` 改变行数 | 边显式标注"基数变化"，否则影响面与行数估算失真（`PIVOT` 还需 `IN(...)` 才能定列名） |
| **控制依赖 vs 值依赖** | 窗口函数的 `PARTITION BY` / `ORDER BY` 列不进入值映射，但影响结果 | **分开记录**：值依赖走 `DERIVES_FROM`，控制依赖走单独边类型（不参与值级血缘，但参与影响分析） |
| `MERGE` / 多语句脚本 | `WHEN MATCHED THEN UPDATE` 与 `WHEN NOT MATCHED THEN INSERT` 多对多带条件，最易漏；脚本需按序串联 | 专门解析分支并合并为同一作业的多条边；按语句顺序串联做时序校验 |
| CTE 与物理表同名 | 导致血缘错乱（已知解析器 issue） | 作用域树内优先解析 CTE，未命中才回落物理表；遇到歧义记冲突待确认 |
| **临时表 / 中间表噪声** | ETL 作业产生大量 `tmp_*`/`sess_*` 临时表，若不处理会在血缘图里制造大批"一次性节点"，把真正重要的链路淹掉 | 按会话/作业标识**把临时表血缘折叠（collapse）到其相邻永久表**（DataHub #9632 的做法）；同时用命名 + 生命周期规则把临时表标记为"非资产"（不进目录、不计入覆盖率）——**这一条不做，血缘图的可读性会直接崩掉** |
| 动态 SQL（拼接、Jinja 模板） | 字符串拼接、`EXECUTE IMMEDIATE` 无法静态解析 | 优先取渲染后 SQL（dbt manifest / Airflow rendered template）；取不到则表级 + 人工提示 |
| UDF / 存储过程 | 语义在代码里，解析器不自带完整映射 | 注册 UDF 语义映射表（声明"输入列 → 输出列"）；**存储过程优先靠运行时上报**，不指望静态解析 |
| 解析器版本与许可 | 解析库迭代快（须锁版本，防止结果漂移）；部分 Java 解析器为 LGPL（商用需注意） | 锁定版本 + 升级时跑**真实 SQL 语料回归并 diff 血缘**；许可清单纳入 SCA 扫描 |
| 性能 | 纯 Python 解析慢 | 解析服务独立进程池 + 限长/超时 + 结果缓存（`hash(sql)+dialect+schema_version` → 血缘指纹）；必要时编译加速 |

**置信度分层（与上面的难点对应，直接映射到边的 `state`/`confidence`）**：

| 层级 | 含义 | 边状态 |
|---|---|---|
| `exact` | 列级映射确定（作用域与 schema 完整） | `ACTIVE`，confidence 0.8–0.95 |
| `derived` | 列级可达但含推断（类型转换、UDF、字段路径） | `ACTIVE`，confidence 0.6–0.8 |
| `table_level_only` | 只确定表级关系 | 只写表级边，不写列级边 |
| `failed` | 解析失败 | **显式记录失败**（不是静默返回空），进"人工 + 运行时补全"队列 |

> 关键纪律：**失败必须显式**。开源平台的真实投诉正是"血缘静默为空，用户无法区分'真的没有'与'解析失败'"。


### 血缘图的查询能力（对外能力，不是内部实现）
- `upstream(urn, depth, filters)` / `downstream(urn, depth, filters)`：支持过滤（只看跨系统、只看列级、只看高置信边）。
- **影响分析**：给定"即将变更的内容"（列删除、类型变更、契约版本），返回受影响的资产清单 + 关键性排序（按下游数量、按使用热度、按是否关键报表/对外接口）。
- **根因分析**：给定"某个下游指标异常"，沿上游回溯，结合质量结果与运行时间，输出最可能的源头候选（与质量子系统联动）。
- **合规传播**：敏感标签沿血缘向下游传播，列级优先。
- **爆炸半径评分**：`影响面 = f(下游资产数, 独立负责人数, 关键资产标记, 使用热度)`，用于变更评审排序。

**实现约束（工程实测要点，依据 `research/04` §2.4）**：
- **递归查询必须去环**：真实血缘图有环（自引用模型、双向同步）。用 `UNION`（去重）而非 `UNION ALL`，或 PG14+ 的 `CYCLE ... USING`；否则 `UNION ALL` 在 DAG 多路径下会**路径数指数膨胀**，查询直接打爆。上生产前用 Tarjan 做一次 SCC 检测并标注断环点。
- **有界闭包只预计算 `depth ≤ 3`**：一次索引查找即可回答绝大多数影响分析，存储比全量闭包（最坏 O(V²)）省 1–2 个数量级。
- **缓存必须带血缘版本**：缓存键 = 实体 + 方向 + 深度 + `lineage_version`，且事件驱动失效。开源平台出现过"删除列级血缘后缓存未失效、TTL 内仍返回已删边"的真实缺陷 → 宁可少缓存，不可脏读。
- **双向 BFS**：按度数从小侧扩展，把 O(b^d) 降到 O(b^(d/2))，这是超大图上的关键优化。
- **边表带 `valid_from` / `valid_to`**（或 `run_id`）以支持时间旅行：事故复盘与合规取证都需要"当时那一刻的血缘是什么"。
- **影响度打分**建议 `score(v) = w(v)·α^depth(v)`，其中 `w(v)` 由敏感分级、认证等级、查询频次、下游报表数合成——比单纯按跳数排序更贴近真实的"重要性"。
- **引入图数据库的三个触发条件（满足其一）**：① 需 ≥4 跳交互式遍历且 P99 > 1s；② 需要图算法（社区发现、相似传播）；③ 节点 + 边超过约 1 亿。若引入，倾向 NebulaGraph（分布式多跳、与 Doris/StarRocks 技能栈重叠）或 Neo4j（单机 + APOC/GDS），**不推荐 JanusGraph**（深多跳退化明显）。无论引入哪个，都必须坚持"**关系库为源、图为可重建派生索引**"（ADR-003-R1）。

### 可视化
- 默认 3 跳，按需展开；节点按平台/域着色，边按来源画线型（实线=运行时/人工，虚线=解析，点线=推断）。
- 提供"列到列"路径追踪视图：`A.col1 → B.col2 → C.metric`，边显示转换表达式。
- **大图性能**：服务端做子图裁剪（按深度/类型/置信度阈值），前端 WebGL 渲染（Cytoscape/G6），节点数 > 2000 时切换为聚合视图（域级/表级）。

---

## 9.3 搜索与发现（Search & Discovery）

### 检索架构
```
查询 → 查询理解（意图/实体/筛选词解析 + 可选 LLM 改写）
     → 并行执行：① 关键词/BM25 检索（OpenSearch）② 向量检索（语义/embedding）
     → 融合排序（RRF 倒数排名融合）
     → 业务加权（使用热度、健康分、个人/团队相关性、已收藏、被引用次数）
     → 权限过滤（结果级 + 字段级）
     → 分组与聚合（按类型/平台/域/标签）→ 返回
```

### 关键设计
- **索引单元**：以"可检索的元数据块"为文档（资产概要、列、术语、契约、文档段落分成 chunk 各自成文档，指向同一 URN），支持"搜到某个列/某段文档"。
- **权限过滤**：索引文档携带 `domainId` / `classification` / `aclRef`，查询时注入过滤条件（**搜索绝不能泄露用户无权查看的资产名**——这是数据目录的安全底线，很多开源实现做得很粗）。
  - 关键实现约束（来自 `research/04` §3.3）：**不要用 Zanzibar 式 `list-objects` 来算"我可见的所有资产"**——它在超大命名空间下等价于"枚举候选再逐个 check"，代价极高。正确做法是**异步把"用户/团队 → 可见资源范围"物化为位图或反向索引**，查询时直接做集合过滤；授权变更时增量更新该物化视图（可接受秒级延迟，并给用户明确的"权限刚变更，稍后生效"提示）。
  - 同一套判定必须同时服务：搜索、详情页、API、导出、**AI 上下文裁剪**（`13` §4）。任何一处用不同的判定就是越权漏洞。
  - **必须"前置过滤"而不是"结果后过滤"**（`research/04` §3.1）：后过滤会**泄漏总数与分面统计**——用户能看到"共 132 条结果，其中 47 条你无权查看"，这本身就泄露了资产存在性与规模。正确做法是把可见范围条件注入查询 DSL（`filter` 子句）后再执行。
  - 排序质量：**RRF 融合**（BM25 + kNN，`rank_constant` 取 60）之后，对 Top-K 再叠一层 cross-encoder reranker（仅 P4 语义检索启用，因为成本高）。
- **中文支持**：IK 分词器（`ik_max_word` 建索引 + `ik_smart` 查询）+ 同义词表 + 拼音/别名；术语表作为同义词源（"GMV"能搜到"成交总额"）。
  - 关键细节（`research/04` §3.1）：**纯中文分词器会切坏 `dwd_order_detail` 这类标识符**，因此必须叠加"按 `_`/驼峰切分（`word_delimiter_graph`）"的分析链；标识类字段（`urn`/`fqn`/`platform`/库表名）用 `keyword` + `normalizer(lowercase)`，否则聚合筛选面会出现大小写重复项。
  - 索引演进**永远走 alias**：字段类型不可原地修改，必须"新建索引 + `_reindex` + 切别名"实现零停机切换（这也是"派生视图可重建"能力的具体用法）。
- **行为元数据（Behavioral Metadata）**：从查询日志挖掘"谁查了什么、查了多少次、和什么一起查"，产出：
  - 资产热度 / 趋势（用于排序与"本周热门资产"）；
  - 推荐（"看了这张表的人还看了"、"常与它 join 的表"）；
  - **反向价值发现**："被高频查询但无人负责/无文档"的资产 → 自动生成治理待办。
  > 这是 Alation/Amundsen 最值得抄的一招：让治理优先级由真实使用数据决定，而不是拍脑袋。
- **空结果处理**：无结果时给出"最接近的候选 + 术语建议 + 申请新资产"入口，并把这次搜索记为需求信号（驱动的治理 backlog）。

---

## 9.4 数据质量与可观测性（Quality & Observability）

### 三层能力，逐步升级
| 层 | 是什么 | 交付 |
|---|---|---|
| L1 剖析 Profiling | 对数据集采样统计：行数、空值率、唯一值数、min/max/分位数、直方图、字符串长度分布、Top-K 值 | P1 |
| L2 规则 Rules | 声明式规则 + 调度执行 + 结果留痕 | P1 |
| L3 可观测 Observability | 无规则/少规则下的异常检测、SLO、事故管理、根因关联 | P2 |

### 质量结果的统一接入（先接入，再自研）

来自 `research/03`（Alation 的"开放 DQ 框架"）的关键判断：**企业通常已经有质量工具（Great Expectations / Soda / dbt tests / 自研脚本），治理平台最大的价值不是替换它们，而是把结果汇聚起来并关联到资产、血缘与 SLO。**

因此 v1 的顺序是：
1. **定义"质量结果摄入契约"**（一个标准 API：`{dataset, rule, metric, status, observed, expected, executedAt, engine, evidenceUrl}`）——任何工具都能推结果进来；
2. **接入已有工具**（dbt `run_results.json`、GX `ValidationResult`、Soda 扫描结果、调度系统的检查任务）；
3. 只有在"客户没有工具"或"需要与血缘/契约深度联动"时才提供自研执行器。

这样做的收益：立刻可用（不要求客户迁移）、平台聚焦在"关联与运营"这一真正差异化的部分；风险是被指责"只是展示别人的结果"——对策是把**关联能力**做深（结果 → 资产 → 血缘影响面 → SLO → 待办 → 事故），这是外部工具做不到的。

### 规则 DSL（设计取向：兼容生态 + 表达力）
不发明第 N 种 DSL 语法，而是**做成可编译的统一中间表示（IR）**，支持三种前端语法：
- dbt tests（复用已有测试）
- SQL 断言（`SELECT count(*) FROM t WHERE <violation>` 这类最直观的形式）
- YAML 声明式规则（内置模板：非空、唯一、值域、枚举、正则、引用完整性、行数波动、新鲜度、schema 不变）

```yaml
rule: freshness_and_uniqueness
dataset: urn:dg:dataset:prod.hive.dw.dim_customer
checks:
  - type: freshness      column: updated_at   maxLag: PT6H
  - type: uniqueness     columns: [cust_key]  threshold: 0.999
  - type: rowCountChange maxDropPct: 30       window: 7d
  - type: customSql      sql: "SELECT count(*) FROM ${table} WHERE amount < 0"  expect: 0
scheduling: { cron: "0 3 * * *", timezone: "Asia/Shanghai" }
severity: { onFail: HIGH, notify: [owner, "#data-alerts"] }
```

**IR 的核心字段**（内部唯一模型，所有前端都编译到它；依据 `research/04` §4.1）：
```
{ rule_id, dataset, column?, 
  metric(null_count | missing_count | distinct_count | duplicate_count | row_count |
         freshness_seconds | percentile | pattern_match_rate | custom_sql),
  operator(= | != | > | >= | < | <= | between | in),
  threshold, window, severity, dimension(DAMA 六维), owner, schedule, on_fail,
  engine_hints{...} }
```

**编译目标（IR → 多引擎，避免被单一 DSL 锁定）**：

| 目标 | 用途 | 说明 |
|---|---|---|
| 平台 SQL 断言 | 默认执行路径 | 推到源系统/查询引擎执行，平台不搬数据 |
| SodaCL | 面向分析同学的可读语法 | 生态兼容，降低迁移成本。**⚠️ 许可警示（`research/05` 核实）：Soda Core 已于 2026-01 改为 ELv2（非 OSI 开源许可）**，商业产品内嵌/分发前必须做许可评审；必要时只借语法形态、自研执行器 |
| Great Expectations suite | 复用已有 GX 投资 | 注意 GX 1.x 已改为 Python 优先 fluent API，需按版本适配 |
| dbt `data_tests` | 与转换同源、随 dbt 运行 | dbt 1.8 起 `tests:` 更名为 `data_tests:`，并新增 `unit_tests:`（用静态输入验证逻辑，不连仓库数据） |
| 引擎原生（Spark/Deequ 约束） | 大数据量分布式校验 | 大表首选 |

风险与对策：**IR 抽象不当会退化为"最小公分母"**（GX 的 `mostly`、Soda 的百分比、Deequ 的 `CheckLevel` 语义并不一一对应）→ 保留 `engine_hints` 承载引擎特有语义，且**默认执行路径不依赖任何外部 DSL**。
执行器：生成 SQL 推到源系统/查询引擎执行（不在平台侧搬数据）；结果与 `JobRun`、`TestCase` 关联；趋势与通过率进健康分。

### 异常检测（L3）

**分层递进实现（成本从低到高，够用即止；依据 `research/04` §4.2）**：

| 层 | 方法 | 适用 |
|---|---|---|
| L1 静态阈值/规则 | `row_count > 0`、`null_rate < 1%` | 默认层，覆盖大部分有效告警（最可靠） |
| L2 稳健统计 | **MAD 稳健 Z 分数** `|x−median| / (1.4826·MAD) > 3` | **默认统计层**（抗离群值优于 3σ） |
| L3 季节性分解 | **STL 分解（`robust=True`）后对 remainder 设阈值**；等价于 Seasonal-Hybrid ESD | **性价比最高**，用于日/周周期指标 |
| L4 变点检测 | CUSUM、Page-Hinkley、PELT（`ruptures`） | 缓慢漂移（数据源悄然变化） |
| L5 多指标 ML | Isolation Forest / LOF / Autoencoder 重构误差 | 单个指标都不越界但整体异常 |
| L6 流式/在线 | ADWIN、KSWIN、Half-Space Trees（`river`） | 高频实时数据（按需，v3+） |

分布漂移检测用 **PSI**（`Σ(A%−E%)·ln(A%/E%)`，> 0.25 视为显著）、KS 检验（数值）、卡方（类别）。

工程要点：
- **季节性对齐是成败关键**：先按业务日历（周内/月末/节假日/大促）对齐再做统计，否则误报率爆炸；训练窗口至少 2–3 个完整周期。
- **上游告警抑制下游**：某表异常时，其下游同批异常应被抑制为"传播"而非独立告警——**这是治理平台相比纯可观测性产品的独有优势**（因为同时掌握血缘）。
- 与血缘联动：某表行数异常 → 自动检查上游 + 下游，给出"源侧 vs 传播"判断；同一批异常聚合为一个 Incident，而不是 N 条告警。
- 告警疲劳治理：分级、去重、冷却期、误报标记回写自动调阈值；**误报率是第一优先级指标**（可观测性产品死于噪音）。

### Profiling 的实现取舍

| 项 | 设计 |
|---|---|
| 分档执行 | **轻量**（随采集走，只取元数据级统计）／**中量**（1% 采样 + 草图算法，每日）／**重量**（全量精确，每周或按需） |
| 采样方式 | 优先**哈希取模**（`MOD(ABS(HASH(pk)),100) < 1`，可重复、可分片）；`TABLESAMPLE` 次之；**`LIMIT` 头部采样有偏，不用** |
| 大基数指标 | distinct 用 **HyperLogLog**（m=2^14，误差约 0.8%）；分位数用 **KLL**（或 t-digest，尾部更准）；top-k 用 **SpaceSaving** |
| **复用免费统计** | 优先读湖仓表格式自带统计（Iceberg manifest 的 `lower_bounds`/`upper_bounds`/`null_value_counts`/`record_count`，Delta 统计）——**零成本拿到 min/max/空值率/行数**，这是接入湖仓的第一优先动作 |
| 结果存储 | 时序表，唯一键 `(dataset, column, metric, window)`；与健康分、SLO、趋势图共用 |
| **行数/大小类指标必须标注精度来源** | 这是通则而非个别坑：**MySQL `TABLE_ROWS`、Oracle `NUM_ROWS`、SQL Server `sys.dm_db_partition_stats` 返回的都是估算值**，与真实行数可能有量级差异。因此凡"元数据级统计"（区别于"profiling 实算"）都必须携带 `precision: ESTIMATED \| EXACT` 与来源字段，**UI 与告警规则都要据此区分**——否则会基于估算值触发误报，或让用户用估算值做决策（`research/04` 补充 26） |
| **隐私约束** | top-k 值与"异常样例值"可能泄漏 PII → **高密级列默认只输出统计量**，不输出具体值；这一条必须写进实现规范而不是"注意一下" |


### SLO 与事故
- SLO 定义：新鲜度、质量通过率、可用性（采集成功）、schema 稳定性；达成率按周/月统计。
- 事故：自动/手动创建 → 关联资产与血缘 → 影响面（受影响的下游清单）→ 时间线（Timeline）→ 复盘（Postmortem）→ 沉淀为新的质量规则（**闭环：每次故障必须产出一条规则或一个检测器**）。

---

## 9.5 数据契约（Data Contracts）

### 立场：契约是"可执行的接口"，不是文档
| 能力 | 设计 |
|---|---|
| 定义 | YAML，**ODCS 兼容**（成熟标准，避免自造），支持 schema / quality / SLA / 语义 / 访问 / 示例 |
| 注册与版本 | 契约即实体，版本语义化（major/minor/patch），发布需审批（生产者 + 消费者代表） |
| 兼容性检查 | Schema 兼容策略（向后/向前/全兼容），**由平台提供 diff 引擎**：删列、改类型、收紧 nullable、改语义 = major（breaking） |
| CI 门禁 | GitHub/GitLab/Jenkins 插件：PR 阶段调用平台 → 校验契约兼容性 + 血缘影响面 + 必填治理属性 → 结果回写 PR |
| 运行时校验 | 采集到实际 schema 与契约不符 → 违约事件（Violation）+ 告警 + 记入契约健康度 |
| 消费者关系 | 消费者显式订阅（谁在用我），变更时定向通知；未订阅但血缘显示在用的 → 自动提示"存在未登记消费者"（**这是契约落地的关键：让生产者看到真实消费者**） |
| 违约处理 | 违约级别（阻断/告警/记录）+ 宽限期 + 豁免（带到期时间，不允许永久豁免） |

### 与血缘、质量的协同
契约是三者交汇点：契约声明 → 生成质量规则（质量子系统执行）→ 违约沿血缘影响分析（血缘子系统）→ 通知消费者（协作子系统）→ 记入 SLO 与健康分（运营子系统）。

---

## 9.6 分类分级与合规（Classification & Compliance）

### 自动化识别流水线
```
列名/注释规则（正则 + 词典：手机号/身份证/邮箱/银行卡/地址/姓名）
  → 采样数据模式识别（格式校验：Luhn 校验银行卡、身份证校验位、手机号段）
  → 统计特征（唯一率、长度分布、值域）
  → （推荐）**Microsoft Presidio**（Apache-2.0，成熟的开源 PII 探测/匿名化框架）作为自由文本列的探测引擎，而不是自研 NER
  → 置信度融合 → 候选分级 + 候选 PII 类型
  → 高置信自动应用；中低置信进"待复核"队列（按风险排序）
```
分级体系可配置（默认 L1–L4），支持映射到国标（如《数据安全法》/行业分级）、GDPR 特殊类别、PCI-DSS 等外部框架（合规映射是给审计看的，不要混进内部分级语义）。

### 标签传播（Propagation）—— 最易做错的地方
- 沿血缘**列级优先**传播：`orders.phone` 是 PII → `dw.customer_phone`、`report.contact` 自动继承。
- 传播标签标记 `inheritedFrom`，与本地检测标签共存；**冲突取更严格**并生成"需复核"任务。
- 传播需防"过度污染"：聚合/脱敏转换（`transform=AGGREGATED` 且声明了脱敏）时允许**阻断传播**，但必须显式声明（默认传播，谨慎阻断——宁可过度保护）。
- 脱敏/遮蔽转换登记：`hash/掩码/截断/泛化` 作为转换元数据，参与"是否可降级"的判断。

### 合规能力
- 数据主体权利支撑：给定某人标识，借助血缘 + 分类找"哪些资产含有该主体的数据"（DSAR 支持，v2 差异化能力）。
- 保留与生命周期：保留期策略 + 到期提醒 + 归档/删除证据留痕。
- 审计报告：一键导出"某类敏感数据分布 / 谁有权访问 / 访问记录 / 复核记录"（给审计与合规部门）。

---

## 9.7 访问治理与策略执行（Access Governance & Policy Enforcement）

### 为什么这块是自研平台最值得投入的差异化
开源平台大多只做"目录内的权限"（谁能看这个资产卡片），而企业的真实痛点是**"数据本身的访问权限"**：谁批、批多久、批到什么粒度、下游执行引擎怎么生效。商业产品（Collibra/SailPoint/Immuta 组合）价值主要在这里。

### 权限模型：三层组合
| 层 | 模型 | 管什么 | 实现 |
|---|---|---|---|
| 平台功能权限 | RBAC | 谁能采、谁能改字典、谁是管理员 | 内置角色 + 权限点 |
| 资产授权 | ABAC | 谁能看/改哪个资产（按域、标签、分类分级） | 策略引擎（属性 + 分级 + 域） |
| 数据访问授权 | ReBAC（有限） + ABAC | 谁能读哪份数据的哪些行/列 | **v1：平台内"资源层级继承 + 属性规则"**（容器→表→列天然继承，成本可控）；**v3：需要跨组织的细粒度关系授权时再引入 OpenFGA/SpiceDB** + 策略编译 |

> 选型倾向（依据 `research/04` 的结论修订）：**不要一步到位上 Zanzibar 式模型**。OpenFGA/SpiceDB 的 relationship tuple 建模与运维复杂度对 v1 是过度投入，且权限错误的后果严重。
> 分阶段路径：① v1 用"资源层级继承（Container→Dataset→Column）+ 属性规则（分级/域/标签）"覆盖绝大多数场景；② v2 加入"授权关系"（谁被授予了哪个资产/列的什么权限、有效期）作为独立实体，仍由平台自持；③ v3 若出现跨域、跨组织、需多维关系的授权诉求（例如"经手过某项目的人可访问其数据集"），再引入 OpenFGA 承载，平台侧只做策略建模与编译。这样既避免失控，也保留演进空间。


### 访问申请与审批
```
用户在资产/列上申请 → 系统自动填充（分类分级、用途、期限、最小粒度建议=列级而非表级）
  → 策略路由（按域 Owner / 数据管家 / 分级高低决定审批链）
  → 审批（工作流引擎，带 SLA 与超时升级）
  → 批准 → 生成授权记录（有效期、用途、粒度）
  → 到期自动回收 + 使用情况复盘（"批准了但 90 天未使用" → 回收建议）
```

### 策略编译与下发（Active Policy）—— 真正的"执行"
```
策略（平台内建模，人可读）
  → 编译器（按目标引擎生成产物）
     ├─ Trino/Spark：行过滤谓词 + 列掩码表达式（如 CASE WHEN ... THEN 'MASKED'）
     ├─ 数仓权限：GRANT/REVOKE 语句 + 行访问策略（Snowflake RAP / BigQuery policy tags）
     ├─ BI：数据集可见性/字段隐藏配置（Superset/Tableau API 下发）
     └─ 应用层 SDK：令牌声明（JWT claim）中的可见范围
  → 下发（推送 or 引擎定时拉取）+ 版本管理 + 失败回滚
```
**落地到哪些引擎（具体集成点，依据 `research/05` §8 的核实）**：

| 引擎 | 执行机制 | 结论 |
|---|---|---|
| **Trino** | `SystemAccessControl`（`checkCanSelectFromColumns`、`filterColumns`…）+ `ConnectorAccessControl.getRowFilter()` / `getColumnMask()` → **查询时真实的行过滤与列脱敏**；内置 OpenLineage Event Listener 可同时产出运行期血缘 | **首选集成点**：写自己的 Plugin/AccessControl，**不要 fork**；PDP 判定必须带缓存（在查询路径上） |
| **Spark** | `spark.connect.extensions` → `SparkConnectPlugin` 拦截 `AnalyzePlan`/`ExecutePlan`；或自定义 `CatalogPlugin`/`TableCatalog` 让每次表解析都查治理元数据 | **次选**：覆盖 Spark 作业；血缘可用 `QueryExecutionListener.onSuccess(...).analyzed()` 拿到已解析逻辑计划（列级血缘的实际路径） |
| 数仓原生策略 | Snowflake 行访问策略、BigQuery policy tags、Hive/Spark 的 Ranger 行过滤与列脱敏 | 按客户实际引擎选择；Ranger 是 Hadoop 体系现成落点 |
| BI 层 | 数据集可见性/字段隐藏（Superset、Tableau API） | **只作消费端**，不进执行路径：BI 可被绕过，不能作为安全边界 |
| 应用 SDK | JWT claim 中的可见范围 | 仅作辅助，不可作为唯一防线 |

**必须写进产品说明的最大漏洞：直连数仓 JDBC 会完全绕过 Trino/Spark 等执行点。** 因此策略执行的"覆盖率"必须被显式度量与运营（哪些路径未被覆盖、哪些账号是直连），并给出补偿手段（网络层限制直连、数据库原生策略兜底、审计发现异常直连）——**宣称"策略已下发"而不度量覆盖率，是最危险的产品表述**。

要点：**策略编译器必须可测试**（给定策略 → 期望产物快照测试），否则错误策略直接造成数据泄露或大面积不可用。

---

## 9.8 治理运营（Governance Operations）

治理平台最容易的死法是"上线了没人用"。运营子系统是解药。

### 资产健康分（Health Score）
多因子加权（权重可配置，建议初始值）：
```
健康分 = 100 × ( 0.15·有Owner
              + 0.15·有描述(非AI草稿)
              + 0.10·有分级
              + 0.15·有质量规则且通过
              + 0.10·有契约/SLO
              + 0.10·血缘完整(上下游均已登记)
              + 0.10·近期被使用(非僵尸资产)
              + 0.10·无未处理告警/复核任务
              + 0.05·有文档/Runbook )
```
- 分档（优/良/待改进/差）驱动 UI 徽标与待办。
- **"治理待办"（Action Center）**：把分数缺口转成可分配、可关闭的任务清单（这是把治理从"报表"变成"工作流"的关键）。
- 反模式警告：健康分不能只看"填了没有"，否则会催生"为了分数字垃圾描述"。对策：描述质量抽查、AI 复核、"被查看/被引用次数"作为正向信号。

### 治理 KPI 看板
覆盖率类（Owner/描述/分级/契约/质量规则覆盖率）、采纳类（周活用户、搜索成功率、资产浏览→使用转化）、质量类（告警数/MTTR/误报率）、风险类（高敏资产未分级数、超期未复核授权的比例）、成本类（采集与存储成本）。**每个 KPI 都要有负责人和趋势线**，否则只是壁纸。

### 域记分卡（Domain Scorecard）
按 Domain/团队聚合：资产数、健康分分布、SLO 达成、响应时长、契约覆盖。用于季度治理评审与资源分配。

---

## 9.9 协作与工作流（Collaboration & Workflow）

| 能力 | 设计要点 |
|---|---|
| Owner 与责任人 | 技术/业务 Owner、数据管家，支持团队与轮值（on-call）；无主资产自动上报 |
| 评论与讨论 | 资产级 + 列级讨论；@提及；与 IM（钉钉/飞书/Slack/Teams）双向同步（**不要指望用户来平台里聊天**） |
| 待办与任务 | 统一 Action Center：复核分类、确认血缘、补描述、处理违约、审批申请、关闭事故 |
| 通知与订阅 | 变更订阅（schema/契约/质量/血缘新增）、告警、审批提醒；按渠道与频率可配；有摘要聚合（避免告警淹没） |
| 公告 Announcement | 资产废弃、迁移、故障预案，带生效时间与置顶 |
| 工作流引擎 | 不自研 BPMN：用轻量状态机（审批链、SLA、超时升级）覆盖 90% 场景；复杂流程留给 Temporal/Camunda 集成 |
| 幂等集成 | 外部工单（Jira/禅道）双向同步：平台生成工单、状态回写 |

---

## 9.10 开发者与集成面（Developer Surface）

| 接口 | 用途 | 设计要点 |
|---|---|---|
| REST + GraphQL | 全功能 API、UI 自身也走 API | 版本化（`/api/v1`）、游标分页、字段选择（GraphQL 解决移动端/嵌入式过度取数） |
| SDK | Python（数据团队）、Java、TypeScript | **由模型注册表代码生成**，保证与模型一致 |
| Events / Webhook | 元数据变更驱动下游 | 至少支持 Webhook + Kafka topic；带签名、重试、死信 |
| OpenLineage 端点 | 作业运行时上传血缘 | 兼容标准，作业零改造接入 |
| CI 插件 | GitHub/GitLab/Jenkins/Argo | 契约与治理门禁 |
| CLI | 批量操作、CI、本地开发 | `dgctl`：apply/export/diff/search |
| Embedded Widget | 在 BI/IDE/查询工具里展示治理信息 | iframe/Web Component + 只读 token（降低使用门槛的关键） |
| MCP Server | AI Agent 用标准协议访问元数据 | 见 `13-ai-native-layer.md` |
| 文件导入导出 | 迁移、批量治理 | Excel/CSV 往返（企业现实中大量靠表格治理，必须支持且能校验） |
