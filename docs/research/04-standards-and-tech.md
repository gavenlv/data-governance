# 04 · 数据治理平台底层标准与关键技术实现原理

> Draft v1.0 · 读者：架构组、元数据/血缘/质量子系统研发
> 本文为**精简版**；逐项深挖版见 `04-standards-and-tech-appendix.md`。来源以官方规范与仓库源码为主；无来源数值标「工程经验」，未证实项标「待核实」。关联 `08-metadata-model.md`、`02-datahub.md`。

---

## 1. 元数据互操作标准与模型

### 1.1 OpenLineage

LF AI & Data 托管，参考实现 Marquez（[openlineage.io](https://openlineage.io/)）。目标：消除"引擎×平台 N×M 适配"。

模型（[Object Model](https://openlineage.io/docs/spec/object-model)）：`Job(namespace,name)`／`Run(runId)`／`Dataset(namespace,name)`；信封 `eventTime/eventType/producer/schemaURL/job/run/inputs/outputs`，`eventType∈START|RUNNING|COMPLETE|ABORT|FAIL|OTHER`。Facet 为带 `_producer`/`_schemaURL` 的扩展 JSON；关键 facet：`schema`、**`columnLineage`**（`fields["输出列"].inputFields[]{namespace,name,field,transformations[]{type:DIRECT|INDIRECT,subtype,masking}}`，[Facet](https://openlineage.io/docs/1.50.0/spec/facets/dataset-facets/column_lineage_facet/)）、`lifecycleStateChange`、`dataSource/parent/sql/ownership/outputStatistics/dataQualityAssertions`。**方向易错：输出列→输入列**。

集成 Airflow provider、Spark（`spark.openlineage.*`）、Flink JobListener、dbt `dbt-ol`、Dagster、GX、Trino EventListener；传输 HTTP `/api/v1/lineage`｜Kafka｜文件；SQL 内核为 Rust `sqlparser-rs`。

**选型：只作采集适配层协议**——它标准化事件形状但不标准化数据集命名（各实现 `namespace` 语义不一）；内部主表用 `lineage_edge`，对外暴露兼容 endpoint。

### 1.2 三种目录元模型

| 维度 | OpenMetadata Standards | DataHub PDL | Atlas TypeSystem |
|---|---|---|---|
| 定义语言 | JSON Schema | PDL(IDL 代码生成) | Java TypeDef + REST |
| 演进代价 | 高 | **低**（加 Aspect） | 高 |
| 关系表达 | 扁平 `entityRelationship` | Aspect 隐式推导 | **一等公民 + category** |

- **OpenMetadata Standards**（[站点](https://openmetadatastandards.org/)）：全 JSON Schema 的独立仓库；字段 `id(uuid)/name/fullyQualifiedName/displayName/description/version/owners/tags/tier/columns/tableConstraints/profile`；关系 `entityRelationship(fromEntity,toEntity,relationType)`；服务端 = Schema 校验 + `entity_relationship` 表 + `entity_extension(JSON)`。可校验性最强，但 schema 变更即破坏性契约。
- **DataHub**：`urn:li:dataset:(urn:li:dataPlatform:hive,db.table,PROD)` 为全局地址；Aspect 独立版本化；**关系不建边表，由 Aspect 字段值隐式推导**；写入 MCP，变更日志 MCL 含 before/after（[MCP/MCL](https://docs.datahub.com/docs/advanced/mcp-mcl)）。
- **Atlas**（[TypeSystem](https://atlas.apache.org/#/TypeSystem)）：`AtlasEntityDef(superTypes,attributeDefs)`／`AtlasRelationshipDef`／`AtlasClassificationDef`(标签可继承)；`AtlasAttributeDef` 约束 `cardinality∈SINGLE|LIST|SET/isUnique/isIndexable/valuesMinCount`。**`relationshipCategory∈ASSOCIATION|AGGREGATION|COMPOSITION` 是三者中唯一把关系语义建模出来的设计**（表达级联删除）。实例 `AtlasEntity`；存储 JanusGraph + Solr/ES。代价：类型变更重、社区活跃度下滑、API 晦涩。

**选型：内部主模型取 DataHub 式 entity-aspect；对外输出 OpenMetadata Standards JSON Schema + OpenLineage 事件；借 Atlas `relationshipCategory` 三值语义定义级联删除。**

### 1.3 数据契约与 Data Mesh 标准

**ODCS**（Bitol / LF AI & Data，**v3.2.0**，[站点](https://bitol-io.github.io/open-data-contract-standard/)）：顶层 `apiVersion`(规范版本)/`kind:DataContract`/`id`/`version`(契约版本)/`status`/`name`/`domain`/`dataProduct`/`description(purpose,usage,limitations)`/`authoritativeDefinitions`/`servers`/`schema`/`roles`/`slaProperties`/`team`/`price`/`quality`；**`apiVersion` 与 `version` 须分开治理**。

`schema[].properties[]` 含 `name/businessName/required/unique/primaryKey/partitioned/classification(public|restricted)/criticalDataElement/transformLogic/quality[]`。**`quality` 最有价值**：`type∈library|reconciliation|custom|sql|text`（`text` 为自然语言规则，供人或 LLM 消费、不自动执行）；`library` 用 `metric(nullValues|missingValues|duplicateValues|rowCount|freshness)`+`mustBe`(`= 0`、`< 0.05`、`between 0 and 5`)，`reconciliation` 增加 `source`/`target` 做跨源对账，统一带 `dimension`(DAMA 六维)/`severity`/`businessImpact`；v3.1.0 起 `required/unique/primaryKey` 可隐式推导为断言。`datacontract-cli` 可 `export`/`import` 到 ODCS、jsonschema、pydantic-model、sodacl、dbt、Avro、Protobuf、GX、BigQuery、RDF、Excel 等，并以 `test` 实跑。**核心洞察：契约 = 可生成校验逻辑、可直接跑测试的机器可读文件。**

相关：ODPS 有两条线（Open Data Product Initiative **v4.0** 与 Bitol **Open Data Product Standard v1.1.0**），须先确认；**DPDS**（**Open Data Mesh Initiative**，`kind:DataProductDescriptor`，`interfaceComponents.{output,discovery,observability,control}Ports`）。**Data Mesh** 是组织范式（领域所有权、数据即产品、自助平台、联邦计算治理），后者由 ODCS + 策略引擎承担。契约标准未收敛 → 自研须"内部 IR + 多格式导入导出"。

### 1.4 DCAT / schema.org / Frictionless / W3C PROV

| 标准 | 核心结构 | 定位 |
|---|---|---|
| **DCAT 3**（[W3C Rec. 2024-08-22](https://www.w3.org/TR/vocab-dcat-3/)） | `dcat:Catalog/Dataset/Distribution/DataService`；v3 新增 `dcat:Resource`/`DatasetSeries`/`Relationship`；`dct:title/description/publisher/contactPoint/license/accrualPeriodicity`、`dcat:keyword/theme/distribution/accessURL/downloadURL/mediaType` | 对外门户交换 |
| **DCAT-AP 3.0**（[SEMIC](https://semiceu.github.io/DCAT-AP/releases/3.0.0/)） | 命名空间 `http://data.europa.eu/r5r#`；`dct:title/description/contactPoint/publisher/theme`、`dcatap:applicableLegislation` 强制；HVD 依 (EU) 2023/138 | 欧盟合规导出 |
| **schema.org Dataset**（[Dataset](https://schema.org/Dataset)） | `name/description/creator/distribution(DataDownload)/license/keywords/variableMeasured/temporalCoverage` | Google Dataset Search |
| **Frictionless**（[Data Package](https://specs.frictionlessdata.io/data-package/)｜[Table Schema](https://specs.frictionlessdata.io/table-schema/)） | `datapackage.json(resources[])` + Table Schema(`fields[].type/format/constraints{required,unique,minLength,maximum,pattern,enum}`、`primaryKey`、`foreignKeys[]`)；v2 发布于 2024-06 | 文件级交付契约 |
| **W3C PROV**（[PROV-O](https://www.w3.org/TR/prov-o/)） | `prov:Entity/Activity/Agent`；`wasGeneratedBy/used/wasDerivedFrom/wasAttributedTo/wasAssociatedWith`；**qualification 模式** `prov:qualifiedDerivation→prov:Derivation` 让"边"也能带属性；`prov:Bundle` 分域 | 溯源概念模型 |

**选型：DCAT-AP 只作对外导出格式**（无 schema/血缘/质量表达，不宜作内部主模型）。内部血缘概念对齐 PROV-O（Entity=数据集/列，Activity=Job/Run，Agent=团队，ODCS `transformLogic`→`prov:Plan`），但不用 RDF 三元组做主存储。

### 1.5 元数据源接入接口

**Iceberg REST Catalog——2023 年后最重要的接口标准化成果**（[OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)），Databricks/Snowflake/BigQuery/Athena/Polaris/Gravitino/S3 Tables 均已实现。端点 `/v1/config`(`defaults/overrides/endpoints`)、`/v1/{prefix}/namespaces`、`/v1/{prefix}/namespaces/{ns}/tables/{table}`(+`HEAD`/`/metrics`/`/register`/`/credentials`)、`/v1/oauth/tokens`、`/transactions/commit`。语义：`ETag`/`If-None-Match` 乐观并发、**提交用 `requirements[]`/`updates[]`**、`X-Iceberg-Access-Delegation: vended-credentials|remote-signing`。`LoadTableResult`=`metadata-location/metadata/config`/**`storage-credentials`（credential vending，临时 S3/GCS/ADLS 凭证）**。`TableMetadata`：`format-version(1/2/3)/table-uuid/location/last-sequence-number/schemas+current-schema-id/partition-specs/snapshots+snapshot-log/metadata-log/refs/statistics`（[Spec](https://iceberg.apache.org/spec/)）；v3 引入 Deletion Vectors 与 Row Lineage。**选型：把"Iceberg REST 客户端"做成通用元数据源适配器**，一次覆盖 Polaris/Unity/Glue/S3 Tables/Snowflake Open Catalog。

| 数据源 | 接口 | 要点 |
|---|---|---|
| Unity Catalog | `/api/2.1/unity-catalog/{catalogs,schemas,tables,volumes,functions}`；`/lineage-tracking/table-lineage\|column-lineage/{name}`（[lineage](https://docs.databricks.com/aws/en/data-governance/unity-catalog/data-lineage)） | 系统表 `system.access.table_lineage`/`column_lineage` **是云数仓最开箱即用的列级血缘源**；2025 加 governed tags + ABAC `GRANT`（[ABAC](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/grant-policies)） |
| AWS Glue | `GetDatabases/GetTables/GetPartitions/GetTableVersions/SearchTables`（[API](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-catalog-tables.html)）；Iceberg REST 端点 `https://glue.{region}.amazonaws.com/iceberg`（SigV4） | HMS 兼容结构（`StorageDescriptor/SerDeInfo`）；Schema Registry 支持 Avro/JSON/Protobuf；Lake Formation 加权限与 LF-Tags |
| Hive Metastore | Thrift `:9083`：`get_all_databases/get_all_tables/get_table/get_fields/get_partitions`（[IDL](https://github.com/apache/hive/blob/master/standalone-metastore/metastore-common/src/main/thrift/hive_metastore.thrift)） | 后端表 `DBS/TBLS/SDS/COLUMNS_V2/PARTITIONS/TABLE_PARAMS/TAB_COL_STATS`；**直连元数据库快但与版本强耦合、绕过缓存与权限**；HMS 3.x notification API 可近实时增量 |
| Schema Registry | `GET /subjects`、`/subjects/{s}/versions`、`/schemas/ids/{id}`、`POST /compatibility/...`（[API](https://docs.confluent.io/platform/current/schema-registry/develop/api.html)） | 兼容级别 `BACKWARD`(默认)/`FORWARD`/`FULL`+`_TRANSITIVE`/`NONE`；wire format = magic byte `0x00` + 4 字节 schema id |
| Delta | `_delta_log/*.json`+checkpoint；Writer v7 = Table Features（`delta.feature.*`）；Delta Sharing `/shares/.../query`（[PROTOCOL](https://github.com/delta-io/delta/blob/master/PROTOCOL.md)） | 跨组织共享的元数据协议 |

---

## 2. 血缘（Lineage）实现技术

### 2.1 SQL 解析方案对比

| 方案 | 语言/许可 | 真 AST | 方言 | 列级血缘 | 结论 |
|---|---|---|---|---|---|
| **sqlglot** | Python/MIT | ✅ | 24–30 个方言模块 | 内置 `sqlglot.lineage` | **主解析器首选** |
| sqlparse | Python/BSD | ❌ token 流 | — | 不可行 | 仅切分/格式化 |
| **Apache Calcite** | Java/Apache-2.0 | ✅ SqlNode+RelNode | ANSI + Babel | `RelMetadataQuery.getColumnOrigins()` | 校验 + 核心表二次验证 |
| JSqlParser | Java/**LGPL-2.1** | ✅ | 宽松混合语法 | 无 | 仅表名集合；注意许可 |
| ANTLR 自定义 | 多语言 | ✅ | 自写 | 自写 | 私有方言，成本极高 |
| sqlfluff | Python/MIT | segment 树（非语义 AST） | ~15 | 无 | 只做 lint |
| pg_query/libpg_query | C+Rust | ✅ | 仅 PG | 无 | PG 高保真（视图/PL-pgSQL） |

**sqlglot**（[GitHub](https://github.com/tobymao/sqlglot)）：`optimizer.optimize(expr,schema,dialect)`、`qualify_columns(expand_stars=True)`、`build_scope()`；`lineage("c",sql,schema,dialect)→Node{name,source_name,downstream[],source}`（先 qualify 规整为单 SELECT + 显式别名，再建作用域树逐层向上游递归）。方言含 bigquery/snowflake/databricks/spark/hive/doris/starrocks/duckdb/presto/trino/redshift/clickhouse/mysql/postgres/tsql/oracle/athena。坑：CTE 同名错乱（[#1647](https://github.com/tobymao/sqlglot/issues/1647)）；无 schema 时 `*` 断链；已到 30.x 须锁版本；`mypyc` 约 5x 提速（[Fivetran](https://www.fivetran.com/blog/how-we-accelerated-transpilation-by-compiling-sqlglot-with-mypyc)）。

**Calcite**（[文档](https://calcite.apache.org/docs/)）：`SqlParser`(JavaCC+FMpp) → `SqlValidator`(列存在性、`*` 展开、**JOIN 列歧义**、类型推导) → `SqlToRelConverter` → `RelNode` → **VolcanoPlanner**(CBO)/**HepPlanner**(RBO) → `getColumnOrigins(rel,col)→Set<RelColumnOrigin>(originTable/originColumnOrdinal/isDerived)`（[RelMetadataQuery](https://calcite.apache.org/javadocAggregate/org/apache/calcite/rel/metadata/RelMetadataQuery.html)）；元数据驱动、抗别名链与 `USING` 合并。生态 Flink SQL/Hive CBO/Drill/Kylin/Dremio；边界 UNION/窗口函数/相关变量有缺口（[CALCITE-6744](https://issues.apache.org/jira/browse/CALCITE-6744)）。**需校验器的理由**：`SELECT * FROM a JOIN b` 同名列时纯解析器静默取一个、Calcite 报歧义。**Coverage 实证**：CWI 实测语料上 JSqlParser 覆盖率最高但仅 **31.59%**（[CWI](https://ir.cwi.nl/pub/34763/34763.pdf)）→ 长尾必须多解析器兜底。开源实现：DataHub 自 [#8334](https://github.com/datahub-project/datahub/pull/8334) 走 schema-aware sqlglot；**OpenMetadata 内核仍以 sqllineage 为主**；SQLMesh/dbt-colibri 基于 sqlglot。

> **选型：sqlglot 主解析 + Calcite 对核心数仓层二次校验**，不一致者记低置信度并进人工/运行时校准队列。风险：双解析器维护成本 → 只对 top-N 核心表启用；JVM 运维成本 → 无 Java 能力时退化为"sqlglot + 业务规则 + 运行时日志校准"；纯 Python 性能 → 限 SQL 长度、解析超时、独立进程池、按 `hash(sql)+dialect+schema_version` 缓存。

### 2.2 列级血缘实现路径与方言难点

**算法**：①SQL→AST；②建作用域树（展开 CTE/子查询/JOIN 别名/`LATERAL VIEW`/`UNNEST`，标识符绑定）；③自底向上推导（保列 CAST/ROUND；多对一 SUM/MAX→`INDIRECT`；一对多 `EXPLODE`/`UNNEST`）；④`SELECT *` 展开（依赖 catalog schema）；⑤沿作用域链列替换回解到源表列；⑥产出边 `(upstream_ds,upstream_col)→(downstream_ds,downstream_col,transform_type,transform_expression,confidence,source)`，`transform_type∈DIRECT|INDIRECT|AGGREGATED|MASKED|FILTER`、`asserted_by∈parser|runtime|llm|manual`；⑦多语句脚本按序串联。

**方言难点**：①**大小写折叠**——Snowflake 折大写，Hive/Spark/**Doris**/**StarRocks**/PG 折小写，**BigQuery** 反引号，T-SQL `[bracket]`，**列级血缘错配第一大来源**；②**复杂类型下钻**——`STRUCT/ARRAY/MAP`、Snowflake `VARIANT`+`FLATTEN`，须表达字段路径 `s.a.b[0]`；③**表函数与行数放大**——`UNNEST`/`LATERAL VIEW EXPLODE`/`PIVOT`/`UNPIVOT`/`QUALIFY`，须标 `cardinality` 放大，`PIVOT` 需 `IN(...)` 才能定列名；④**递归 CTE** 无法静态终止；⑤**`MERGE`**——`WHEN MATCHED THEN UPDATE SET`/`WHEN NOT MATCHED THEN INSERT` 多对多带条件，最易漏；⑥**UDF/存储过程/动态 SQL**——字符串拼接与 `EXECUTE IMMEDIATE` 不可静态解析，靠运行时兜底；⑦**窗口函数的 `PARTITION BY`/`ORDER BY` 列是控制依赖而非值依赖**，须分开记录；⑧**新方言滞后**——Doris/StarRocks `DISTRIBUTED BY`/`BUCKETS`、ClickHouse `ARRAY JOIN`/`FINAL`；⑨**方言专有函数需自建"输入列→输出列"映射表**，sqlglot/Calcite 都不自带。

**对策**：方言能力矩阵 + 置信度分层（`exact`/`derived`/`table_level_only`/`failed`）；`failed` 落人工 + 运行时补全队列；建回归语料库（每方言 ≥200 条含边界）。

### 2.3 非 SQL 血缘

| 来源 | 手段 | 列级 |
|---|---|---|
| Spark | `QueryExecutionListener.onSuccess` 取 `qe.analyzed/optimizedPlan`，遍历 Catalyst `TreeNode`；**`AttributeReference.exprId`/`qualifier` 是跨算子追踪列的稳定锚点** | 可 |
| Flink | `StreamGraph→JobGraph→ExecutionGraph`；SQL 侧 `TableEnvironment→FlinkPlannerImpl(Calcite)→RelNode`，静态走 `getColumnOrigins()` | 可（静态） |
| dbt | **`manifest.json` 是最佳非 SQL 血缘源**：`nodes[].depends_on.nodes/refs/sources/columns/`**`compiled_code`**`/relation_name`、`parent_map`/`child_map`、`exposures`（[规范](https://docs.getdbt.com/reference/artifacts/manifest-json)） | 需解析 `compiled_code` |
| 调度/代码 | `inlets`/`outlets`+`airflow.lineage` **已弃用**（[#44983](https://github.com/apache/airflow/issues/44983)），现行走 OpenLineage Provider；Dagster 用 `AssetKey` 图 + `TableColumnLineage`；Python/ETL 用 `ast` 识别 `pd.read_sql`/`merge`，准确率差 → 只做表级 + `@lineage(...)` 显式声明 | 部分 |
| BI | Tableau Metadata API（GraphQL `publishedDatasources→fields{upstreamColumns{table{name}}}`）（[模型](https://help.tableau.com/current/api/metadata_api/en-us/docs/meta_api_model.html)）；Power BI Scanner API（`POST /admin/workspaces/getInfo`→`scanStatus`→`scanResult`）（[Scan Result](https://learn.microsoft.com/en-us/rest/api/power-bi/admin/workspace-info-get-scan-result)）；Looker API 4.0（`lookml_model_explore.fields[].sql`）；Metabase MBQL 的 `:source-table`/`:field` **直接给列级映射** | 字段级 |
| 运行时日志 | Snowflake `ACCESS_HISTORY.base_objects_accessed.columns[]`、`SNOWFLAKE.CORE.GET_LINEAGE()`（[文档](https://docs.snowflake.com/en/sql-reference/functions/get_lineage-snowflake-core)）；BigQuery `INFORMATION_SCHEMA.JOBS_BY_PROJECT`；Redshift `STL_QUERY`/`SVL_STATEMENTTEXT`；ClickHouse `system.query_log` | 列级 |

> **选型：三源合并**——日志解析（高覆盖）+ 静态解析（全量）+ 运行期 Agent（精准）；用日志修正静态结果并发现临时表、动态 SQL、外部工具直连。存储层必须保留 `source` 字段，冲突时以运行时为准但保留全部证据。

### 2.4 血缘图存储与查询

| 方案 | 优势 | 劣势 |
|---|---|---|
| Neo4j | 原生图存储（index-free adjacency）、Cypher/GQL、APOC/GDS | 水平写扩展靠 Fabric；社区版无集群 |
| NebulaGraph | graphd+metad(Raft)+storaged(RocksDB)；`GO n STEPS` 对 VID 直达多跳最优 | 属性索引须显式创建；深度优先弱；nGQL 生态小 |
| JanusGraph | Gremlin 标准、后端灵活、索引后端 ES/Solr | **无 index-free adjacency，深 k-hop 退化最明显**；运维重 |
| HugeGraph | Apache、Gremlin + REST、1.5+ PD 计算存储分离 | 生态文档弱 |
| 关系库 + 递归 CTE | 无新组件、事务一致（[WITH RECURSIVE](https://www.postgresql.org/docs/current/queries-with.html)） | **遇环无限循环，须 `UNION` 去重 / PG14+ `CYCLE ... USING` / visited 数组**；`UNION ALL` 在 DAG 多路径下指数膨胀 |
| 关系库 + **有界闭包** | `lineage_closure(ancestor,descendant,depth,path)` 一次索引查找 | 全量闭包写放大 O(depth)、最坏 O(V²) 行 → **只预计算 `depth<=3`**，省 1–2 个数量级存储 |
| ES 搜索索引 | 一跳快、与搜索统一 | 多跳靠应用层 BFS+mget；百万节点不可行 |
| 物化路径 / 位图 | RoaringBitmap 或 PG `bit varying` 存祖先/后代集，集合运算即影响面求交并 | 节点集变动需重建 |

**多跳基准**：独立评测（Applied Sciences 13(9):5770, 2023）在 **LDBC SNB** 上比四库：**Nebula Graph 与 TigerGraph 领先，Neo4j 居中，JanusGraph 深多跳退化最明显**（[PDF](https://eg-fr.uc.pt/bitstream/10316/113292/1/Experimental-Evaluation-of-Graph-Databases-JanusGraph-Nebula-Graph-Neo4j-and-TigerGraphApplied-Sciences-Switzerland.pdf)）；厂商对比（[NebulaGraph](https://www.nebula-graph.io/posts/performance-comparison-neo4j-janusgraph-nebula-graph)）须打折并自建 PoC。设计输入（工程经验）：1–3 跳单起点 <100ms → 关系库即可；3–10 跳 → 10^5 边递归 CTE 亚秒~数秒，10^6–10^8 边图库优势明显；全图算法 → Neo4j GDS / NebulaGraph Graph Computing。

**开源实际形态**：DataHub = MySQL/PG 存 aspect + **ES 作图检索层**（多跳由 ES 提供 → 索引延迟会让查询返回空而非报错，[#18809](https://github.com/datahub-project/datahub/issues/18809)）；OpenMetadata = `entity_relationship`+`field_relationship` + ES；Marquez = 纯 PostgreSQL（`GET /api/v1/lineage?nodeId=...&depth=N`）；Atlas = JanusGraph；Amundsen = Neo4j（约 2022 后停更）。

> **选型：Postgres 承载 `entity`+`lineage_edge`+有界闭包（depth≤3）+ Redis 缓存 + ES 检索**——也是 DataHub/OpenMetadata 的实际形态。仅当 ①需 ≥4 跳交互式遍历且 P99>1s、②需图算法、③节点+边超 1 亿 之一成立才引入图库；引入则倾向 **NebulaGraph**（分布式多跳友好、国产生态、与 Doris/StarRocks 技能栈重叠）或 **Neo4j**（单机够且要 APOC/GDS），**不推荐 JanusGraph**。风险：图库的 schema 迁移、多租户隔离、与主元数据事务一致性都是额外成本 → 必须"关系库为源、图为可重建派生索引"。

**影响分析**：①**双向 BFS**（按度数选小侧）把 O(b^d) 降到 O(b^(d/2))；②缓存键 = 实体+方向+深度+`lineage_version` 并事件驱动失效，否则脏读（[#18636](https://github.com/datahub-project/datahub/issues/18636)）；③预计算 + 增量重算（脏集按拓扑序批量重算子图）；④真实血缘图有环，需 Tarjan SCC 检测与断环标注；⑤影响度打分 `score(v)=w(v)·α^depth(v)`，`w(v)` 由 PII 标签、认证等级、查询频次、下游报表数合成；⑥边表加 `valid_from`/`valid_to`（或 `run_id`）支持 time-travel。

---

## 3. 元数据存储与检索

### 3.1 搜索引擎设计

**粒度**：一实体一文档，列内嵌 `nested`；列数 >2000 时拆"实体索引 + 字段索引"（DataHub/OpenMetadata 均按实体类型分索引）。

**Mapping 要点**：①标识类（`urn`/`fqn`/`platform`）用 **`keyword`**（聚合/facet 只能基于 keyword）+ `normalizer(lowercase)`；②`description` 用 `text`+`.keyword`，`name` 用 `edge_ngram`/`search_as_you_type`；③**中文分词**：ES 无内置中文分析器，常用 **IK（`analysis-ik`，`ik_max_word` 建索引 + `ik_smart` 查询）**、SmartCN、jieba（[analysis-ik](https://github.com/infinilabs/analysis-ik)）——**更稳的组合是"IK + 字段名按 `_`/驼峰切分（`word_delimiter_graph`）+ 同义词表"**，否则纯中文分词器会切坏 `dwd_order_detail` 这类标识符；④向量 `dense_vector`（`dims`+`index:true`+`similarity:cosine|dot_product|l2_norm`，HNSW `m`/`ef_construction`）（[dense_vector](https://www.elastic.co/guide/en/elasticsearch/reference/current/dense-vector.html)）；⑤`dynamic` 建议 `strict`；⑥**演进永远走 alias**，字段类型不可原地改。

**字段级权限过滤**：**Document Level Security** 用 role 的 query 模板（`{"term":{"domain":"{{_user.metadata.domain}}"}}`）、**Field Level Security** 在 `field_security.grant` 列白名单（[DLS](https://www.elastic.co/guide/en/elasticsearch/reference/current/document-level-security.html)｜[FLS](https://www.elastic.co/guide/en/elasticsearch/reference/current/field-level-security.html)）；OpenSearch 对应 `masked_fields`。坑：FLS 对 `aggregation`/`sort` 同样生效，但高基数分面的"桶存在性"仍会泄漏。**更常见做法：权限前置过滤**——查询 DSL 注入"用户可见资源范围"（`terms` filter / `terms_lookup`）+ 权限指纹缓存；后过滤会泄漏总数与分面统计。

**语义/混合检索**：**RRF（Reciprocal Rank Fusion）**——BM25 与 kNN 按 `1/(k+rank)`（`rank_constant` 默认 60）融合，ES 8.14+/OpenSearch 已内建 RRF retriever（[RRF](https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html)），免调权重；再叠 **cross-encoder reranker**（`bge-reranker-v2-m3`）对 top-20 重排 + `function_score` 提升权威/热度。Embedding 中文/多语首选 `bge-m3`/`bge-large-zh`。**换模型即需重建索引** → 文档须写 `embedding_model`/`embedding_version`，支持双写 + 影子索引。替代栈：**pgvector**（元数据已在 PG 上时省一套中间件，倾向起步方案）、Milvus/Qdrant。

### 3.2 元数据仓库建模与版本化

| 范式 | 优势 | 劣势 |
|---|---|---|
| EAV | 属性极灵活 | 属性多了 JOIN/行转列地狱 |
| 宽表 | 查询快、索引简单 | 扩展要 DDL、大量 NULL |
| 图（节点+边） | 关系表达与遍历自然 | 属性查询弱；属性与关系混存会爆炸 |
| 文档 JSONB | 演进友好、一次读全实体；PG 可用 GIN(`jsonb_path_ops`)+生成列补查询 | 强 schema 校验需应用层 |

> **选型（混合建模）**：①**骨架列**（`id`/`fqn`/`type`/`platform`/`owner_id`/`domain`/`tier`/`updated_at`）用真列 + B-Tree；②**扩展属性**用 JSONB + GIN；③**关系独立边表** `entity_relationship(from_id,to_id,relation_type,attributes jsonb)`；④**搜索索引与血缘图是派生物，必须能从主存储全量重建**。理由：DataHub（aspect 表 + 关键列冗余）与 OpenMetadata（`entity_extension`+`entity_relationship`）验证过的路径。

**版本化与审计**：①`entity_version(entity_id,version,snapshot jsonb,changed_by,changed_at,change_source)`；②时序数据（profile/usage/断言/血缘观测）走时序表 + 日/月分区 + TTL 降采样（90 天原始 + 长期聚合）；③审计 `audit_log(actor,action,target_urn,before,after,request_id,ip,ts)` 追加写不可改；合规需 **bitemporal**（`valid_from/valid_to` 业务有效期 + `tx_from/tx_to` 系统记录期），**SCD2（`valid_from`/`valid_to`/`is_current`/`surrogate_key`）是最小可用实现**；④**不要对所有实体上 SCD2**——只让治理关键属性（owner/tier/classification/domain/policy）走 SCD2，其余走快照保留 N 版。

### 3.3 多租户与权限

| 模型 | 表达力 | 管理成本 | 数据目录适配度 |
|---|---|---|---|
| RBAC | 弱 | 低，但**角色爆炸** | 中：权限来自"域+项目+标签"组合 |
| ABAC | 强（OPA/Rego、Cedar） | 策略集中但调试难 | 高：域/密级/tier/环境天然是属性 |
| ReBAC | 最强（图可达性） | 需专用服务与物化 | 高：资产天然有层级与共享关系 |

**ReBAC / Zanzibar**（[Zanzibar, ATC'19](https://www.usenix.org/conference/atc19/presentation/pang)）：**relation tuple** `object#relation@user`（`table:db.orders#reader@user:alice`），支持 userset（`...@group:analytics#member`）；**namespace config** 定义 userset rewrite（union/intersection/exclusion/tupleToUserset，如 `viewer = editor + parent->viewer`）；**一致性**用 zookie / SpiceDB ZedToken 解决 new-enemy 问题。实现：**OpenFGA**（CNCF sandbox，DSL + `check`/`expand`/`list-objects`）、**SpiceDB**（Authzed，schema 语言 + `LookupResources`/`LookupSubjects` + caveats）（[OpenFGA](https://openfga.dev/docs/concepts)｜[SpiceDB](https://authzed.com/docs/spicedb/concepts/schema)）、Ory Keto、Permify——**各家元组格式与 DSL 不兼容，切换即重写模型**（⚠️ `object#relation@user` 是 Zanzibar/**SpiceDB** 的文本形式；**OpenFGA 的元组是三字段 JSON `{user,relation,object}`**，勿混用）。映射到目录（以 Zanzibar 形式示意）：`catalog#viewer@user`、`schema#parent@catalog`、`table#viewer@team#member`。

> **限制**：Zanzibar 风格 `list-objects`（"我可见的所有表"）在超大命名空间很贵（枚举候选再逐个 check）。**搜索引擎侧过滤不能靠它**，须异步把"用户→可见资源"物化成反向索引/位图 → ReBAC 与 ES 前置过滤必须协同设计。

> **选型（分阶段）**：RBAC（平台管理员/域管理员/资产 owner/只读）+ ABAC（域/标签/tier/密级/环境）+ **有限 ReBAC（仅资源层级继承与共享）**。顺序：先 RBAC → 授权判断加"资源层级继承"（本质最小 ReBAC）→ 最后才评估 OpenFGA/SpiceDB。**不要第一天就上 Zanzibar 实现**，其一致性/缓存/权限物化复杂度远超数据目录本身。

**参考实现**：OpenMetadata Role+Policy（规则含 `resources/operations/effect/conditions`）；DataHub Platform Policies + Metadata Policies；Atlas + Apache Ranger 做 tag-based masking；Unity Catalog governed tags + ABAC。

---

## 4. 数据质量与可观测性

### 4.1 规则 DSL 对比

| 维度 | Great Expectations | SodaCL | dbt tests | Deequ | ODCS quality |
|---|---|---|---|---|---|
| 形态 | Python 原生 | YAML 声明式 | YAML + SQL | Scala/PyDeequ | 契约内嵌 |
| 表达力 | **最强** | 中 | 中 | 强（大数据） | 弱-中 |
| 上手成本 | 高（1.x 演进中） | **最低** | 低 | 中 | 低 |
| 大数据量 | 一般 | 中 | 依赖仓库 | **最佳（Spark 分布式）** | 依赖引擎 |
| 与转换同源 | 否 | 否 | **是** | 否 | **是（与 schema 同源）** |
| 内置异常检测 | 无 | **有** | 无 | **有（3 策略）** | 无 |

- **Great Expectations**：`DataContext`/`Datasource`/`ExpectationSuite`/`Checkpoint`/`ValidationResult`；`expect_*` 族（`expect_column_values_to_not_be_null`/`_to_be_unique`/`_to_be_between`/`expect_table_row_count_to_be_between`）（[Gallery](https://greatexpectations.io/expectations/)）。**1.x 重大变化：配置从 yml 转向 Python 优先 fluent API（`gx.get_context()`）**（[迁移](https://docs.greatexpectations.io/docs/0.18/reference/learn/migration_guide/)）。结果 JSON：`{success,expectation_config,result{observed_value,unexpected_count,unexpected_percent,partial_unexpected_list},meta,exception_info}`。缺点：依赖重、无内置调度。
- **SodaCL**（[Overview](https://docs.soda.io/soda-cl/soda-cl-overview.html)）：`checks for dim_customer:` 下 `- row_count > 0`、`- missing_count(email) = 0`、`- duplicate_count(order_id) = 0`、`- invalid_percent(status) < 1%`、`- freshness(updated_at) < 1d`、`- schema:`、`- reference`、`- failed rows`。**内置异常检测**：对 `row_count` 等指标启用 anomaly score，自动学习历史分布（[文档](https://docs.soda.io/soda-cl/anomaly-detection.html)）。
- **dbt tests**：generic `unique`/`not_null`/`accepted_values`/`relationships`；singular tests（`tests/*.sql`）；包 `dbt-utils`、`dbt-expectations`。**1.8 起 `tests:` 更名 `data_tests:` 并新增 unit tests**（`unit_tests:` 用静态输入验证输出）（[data tests](https://docs.getdbt.com/docs/build/data-tests)｜[unit tests](https://docs.getdbt.com/docs/build/unit-tests)）；`contract: enforced: true` 校验列名与类型。
- **Deequ**（[GitHub](https://github.com/awslabs/deequ)）：四类 Runner——`VerificationSuite`/`AnalysisRunner`/**`ConstraintSuggestionRunner`（KLL 草图自动推荐规则）**/`ColumnProfilerRunner`；`Check(CheckLevel.Error,"d").isComplete("c").isUnique("id").hasMin("v",_>0)`；策略类 `RelativeRateOfChangeStrategy`/`AbsoluteChangeStrategy`/`OnlineNormalStrategy`。

> **选型：内部统一"规则 IR" + 多方言编译器**，而非选一个 DSL 当内部模型。IR：`{rule_id,dataset,column?,metric(null_count|distinct_count|row_count|freshness_seconds|percentile|pattern_match_rate|custom_sql),operator,threshold,window,severity,dimension(DAMA 六维),owner,schedule,on_fail}`；编译到 SodaCL（给分析同学）/GX suite/dbt `data_tests`/SQL（Spark/Trino 侧）。理由：不被单一 DSL 锁死；契约（ODCS `quality`）与规则天然同源。风险：IR 抽象不当会退化为最小公分母（GX 的 `mostly`、Soda 的 `%`、Deequ 的 `CheckLevel` 语义不同）→ 须留 `engine_hints`。

### 4.2 异常检测算法

1. **静态阈值/规则**：`row_count > 0`、`null_rate < 1%`。最可靠，作默认层；
2. **统计检验**：3σ；**稳健 Z 分数（MAD）** `|x−median|/(1.4826·MAD) > 3`（**推荐作默认统计层**）；IQR/Tukey 栅栏；Grubbs；**Generalized ESD**；**Seasonal-Hybrid ESD**（[Twitter AnomalyDetection](https://github.com/twitter/AnomalyDetection)：STL 分解后对残差做 ESD）；
3. **时间序列**：**STL 分解**（`seasonal/trend/remainder`，`robust=True`）后对 remainder 设阈值——**性价比最高**；Prophet（趋势+傅里叶季节性+节假日，预测区间定界，慢且需离线训练）；SARIMA/Holt-Winters（需 ≥3 周期样本）；
4. **变点检测**（缓慢漂移）：CUSUM、Page-Hinkley、**PELT**（`ruptures`：`rpt.Pelt(model="rbf").fit_predict(signal,pen=...)`）；
5. **ML**（多指标同时轻微偏离）：Isolation Forest（`sklearn.ensemble.IsolationForest`，无标注、O(n log n)）、LOF、One-Class SVM、Autoencoder 重构误差；
6. **流式/在线**：ADWIN、KSWIN、Half-Space Trees（`river`）；**数据漂移**：**PSI** `Σ(A%−E%)·ln(A%/E%)`，>0.25 显著；KL/JS 散度；KS 检验（数值）/卡方（类别）。

**工程要点**：①**季节性对齐是成败关键**——先按业务日历（周内/月内/节假日/大促）对齐再统计，否则误报率极高；②训练窗口 ≥2–3 个完整周期；③**告警疲劳治理**：分级、去重、冷却期、**上游告警抑制下游**、误报率回写自动调阈值；④异常必须带归因线索（哪个指标、偏离多少 σ、对比窗口、上游是否同时异常）。

### 4.3 Profiling 实现

**采样**：全量（小表/关键表，Spark/Deequ 分布式）；`TABLESAMPLE`；**哈希取模** `WHERE MOD(ABS(HASH(pk)),100)<1`（可重复、可分片）；`LIMIT` 头部采样有偏。误差直觉：估 1% 空值率时 n=10000 的 95% 置信区间约 ±0.2%；估 ppm 级重复率必须全量。

**指标**：①基础——行数、null 率、distinct 数（**HyperLogLog**，m=2^14 误差 ~0.8%）、min/max、mean/stddev、分位数（**KLL**，Deequ 默认；或 **t-digest** 擅长尾部）、top-k（**SpaceSaving**）；②**直方图**——等宽（受离群值影响）、**等深/等频**（更反映分布）、对数分桶（长尾）；③模式——正则推断（email/手机号/身份证/日期）、`numberFormat`/`dateFormat`；④依赖——组合键唯一性、**函数依赖发现**（HyFD/TANE/SPIDER）、**包含依赖/外键推断**（BINDER/MIND，用于 join 推荐）；⑤跨表相似度——**MinHash+LSH** 估 Jaccard。工具：`ydata-profiling`、Deequ `ColumnProfilerRunner`、Spark `df.summary()`、**DuckDB `SUMMARIZE`**（单机极快）。

**工程要点**：①**画像分三档**——轻量（随采集走的元数据）、中量（采样 1% + HLL/KLL，每日）、重量（全量精确，每周或按需）；②**优先复用湖仓表格式自带统计**——Iceberg manifest 的 `lower_bounds/upper_bounds/null_value_counts/record_count` 与 Delta 统计可"免费"给出 min/max/空值率/行数；③结果存时序表，唯一键 `(dataset,column,metric,window)`；④**隐私**——`partial_unexpected_list`/top-k 值可能泄漏 PII → 高密级列默认只输出统计量。

---

## 5. 元数据采集工程

### 5.1 采集器框架设计

**四层抽象（DataHub/OpenMetadata/Amundsen 共识）**：①**Source/Extractor**（DataHub `Workunit`、Amundsen `Extractor`）；②**Mapper/Transformer**（→内部规范化模型）；③**Sink/Loader**（`datahub-rest`/`datahub-kafka`）；④**Orchestrator**（DataHub `Pipeline`+`recipe.yml`，OpenMetadata `TopologyRunner`+Workflow YAML）。配置形态：DataHub `recipe.yml`（`source:{type,config}`+`sink`+`transformers`+`stateful_ingestion`，[Recipe](https://docs.datahub.com/docs/metadata-ingestion/recipe)）；OpenMetadata 的 `source.serviceConnection` 由**每服务类型的 JSON Schema** 定义（强类型连接配置），也支持 Airflow DAG 部署（[Workflows](https://docs.open-metadata.org/latest/connectors/ingestion/workflows)）。

**增量/全量**：全量可自我修复（能发现删表）但配额与耗时高 → 大目录须分片全量（按 schema/库并行 + checkpoint）。**增量三路**：①时间水位（`information_schema.tables.last_altered`，**很多库不维护，不可靠**）；②事件驱动（HMS notification、Glue EventBridge、Schema Registry 变更事件）；③**内容指纹**（对"DDL + 列定义 + 分区列表"做 hash 比对上次快照）——**最通用，推荐作默认**。**删除检测**：DataHub **Stateful Ingestion**（`stateful_ingestion.enabled:true`+`remove_stale_metadata:true`，`StaleEntityRemovalHandler` 把"上轮出现本轮未出现"的实体标 stale 再按 retention 删除）（[源码](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/state/stale_entity_removal_handler.py)）。踩坑：多个 source 混写同一实体会误删 → 命名空间隔离 + 实体上记录"负责采集器"字段。

**限流与背压**：令牌桶（Guava `RateLimiter`/`aiolimiter`）+ 并发信号量；429/`Retry-After` 做指数退避+抖动（`min(cap, base·2ⁿ)·random(0.5,1.5)`）；熔断避免拖垮源库；**元数据服务写入侧也要限流**。

**凭证管理**：①**绝不把明文凭证存进元数据服务**——只存 `secretRef`（`vault://kv/data/db/mysql-prod#password`、`aws-sm://arn:...`），执行时由采集 worker 解析；②首选 **HashiCorp Vault**：KV v2 存静态凭证，**`database/` secrets engine 生成动态短时凭证**（`database/creds/readonly-role`，TTL 分钟级）；认证用 AppRole 或 K8s auth；`transit` 引擎做字段级**信封加密**（DEK 加密数据、KEK 在 Vault/KMS）（[Vault DB Secrets](https://developer.hashicorp.com/vault/docs/secrets/databases)）；③云上用 Secrets Manager+KMS / GCP Secret Manager / Azure Key Vault + External Secrets Operator；④**DB 侧优先 key-pair / IAM 临时凭证**（Snowflake key-pair、RDS IAM auth）；⑤凭证 TTL + 自动轮换，且采集失败必须能区分"认证失败"与"网络失败"。

**调度**：Airflow（复用平台、可观测好，但采集的轻量高频需求会挤占调度器）vs 内置调度（APScheduler/Celery beat/K8s CronJob + 分布式锁）。**选型：平台自带调度保证开箱可用 + 提供 Airflow Provider 接入企业既有调度。** 要点：幂等、优先级队列、分布式锁、死信队列；**"采集运行失败"本身要作为可观测实体写入平台**；采集过程自身也应发 OpenLineage 事件。

### 5.2 各类数据源的采集方式

| 数据源 | 首选接口 | 关键坑 |
|---|---|---|
| 关系库 | `information_schema`（`TABLES`/`COLUMNS`/`VIEWS`/`TABLE_CONSTRAINTS`/`KEY_COLUMN_USAGE`/`REFERENTIAL_CONSTRAINTS`/`PARTITIONS`）；JDBC `DatabaseMetaData` | **PostgreSQL 的 `information_schema` 是视图，大目录下比直查 `pg_catalog`（`pg_class`/`pg_attribute`/`pg_description`）慢一个量级**；MySQL 8 已是数据字典表 |
| JDBC 细节 | `getCatalogs/getSchemas/getTables/getColumns/getPrimaryKeys/getImportedKeys/getIndexInfo/getFunctions/getTablePrivileges` | `getColumns` 返回 `COLUMN_NAME/DATA_TYPE/TYPE_NAME/COLUMN_SIZE/NULLABLE/REMARKS/COLUMN_DEF/ORDINAL_POSITION`；**Oracle 默认不返回 `REMARKS`**（需 `remarksReporting=true` 或直查 `ALL_TAB_COMMENTS`）；`getColumns(null,null,"%","%")` 全库扫描，必须带 schema 限定 |
| Oracle 专有 | `ALL_TAB_COLUMNS`/`ALL_TAB_COMMENTS`/`ALL_CONS_COLUMNS`/**`ALL_DEPENDENCIES`**（对象依赖，血缘金矿）/`DBMS_METADATA.GET_DDL` | 视图 DDL 原文是最可靠的列级血缘输入 |
| SQL Server / MySQL | `sys.tables`/`sys.columns`/**`sys.sql_expression_dependencies`**；`SHOW CREATE TABLE` | 后者比 `information_schema` 更完整（注释、引擎、分区） |
| 湖仓表格式 | Iceberg（`metadata/vN.metadata.json`+`version-hint.text`，或 REST Catalog）；Delta（`_delta_log/*.json`+checkpoint）；Hudi（`.hoodie/`+timeline） | **元数据本身就是文件，可直接读文件系统/对象存储，不依赖任何服务——"零接入成本"路径，强烈建议自研支持** |
| BI | Tableau Metadata API（GraphQL）、Power BI Admin Scanner API、Looker API 4.0、Superset `/api/v1/dataset`、Metabase `/api/database`+`/api/field/{id}` | 权限门槛（Tableau 需 Site Administrator，Power BI 需 Fabric Administrator + 服务主体）、分页、rate limit；**行级权限 RLS 不在元数据里** |
| 消息系统 | Kafka AdminClient + Schema Registry | 几十万 topic 时 `describeTopics` 必须分批；**subject 命名策略（`TopicNameStrategy`/`RecordNameStrategy`/`TopicRecordNameStrategy`）决定 subject 与表的映射** |
| 云数仓 | Snowflake `INFORMATION_SCHEMA`+`ACCOUNT_USAGE`；BigQuery `INFORMATION_SCHEMA`+`JOBS_BY_PROJECT`；Redshift `SVV_TABLE_INFO`/`SYS_QUERY_HISTORY` | Snowflake `ACCOUNT_USAGE` 延迟 45min–3h 但含 `ACCESS_HISTORY`/`OBJECT_DEPENDENCIES`；BigQuery 注意 region 限定与项目配额 |

---

## 6. AI/LLM 在数据治理中的应用现状（2024–2025）

| 能力 | 代表实现 | 成熟度 | 主要风险 |
|---|---|---|---|
| 自然语言搜索目录 | DataHub Cloud NL 搜索、Collate（OpenMetadata 商业版）Metadata Agent、Atlan AI、Alation、Dataplex Universal Catalog（Gemini） | 中：语义检索可上线，"直接问答"仍易错 | 幻觉、权限泄漏 |
| 自动描述/标签 | OpenMetadata 内置 AI 自动描述（OpenAI/Anthropic/Azure/Bedrock，配置于 `aiConfig`）、[OpenMetadata AI SDK](https://github.com/open-metadata/ai-sdk) | **较高**（人审后可用） | 成本、幻觉、覆盖人工内容 |
| PII 检测 | 正则+列名词典+采样值+ML 分类器（**Microsoft Presidio** 预置 `EMAIL_ADDRESS`/`PHONE_NUMBER`/`CREDIT_CARD`/`IBAN` 识别器；云侧 Google DLP、AWS Macie） | 高（组合方案） | 漏检/误检 |
| AI 血缘推断 | ①静态解析失败的 SQL 用 LLM 尽力解析；②非 SQL 代码反推；③BI 计算字段/DAX 反推 | **低-中** | 须与运行时血缘交叉验证，并单独标注 `asserted_by=llm` |
| text-to-SQL 与语义层 | dbt Semantic Layer/MetricFlow（`semantic_models`+`metrics`）、Cube、LookML、AtScale、[Snowflake Cortex Analyst](https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst)、Databricks Genie | 中 | 直接面向业务仍不可靠 |
| MCP 对接 | DataHub MCP Server、OpenMetadata/Collate MCP、dbt MCP、Snowflake、Databricks | 快速成熟中 | 授权与注入 |

**text-to-SQL 现实**：基准已换代——Spider 1.0（~90% 已刷满，无区分度）、**Spider 2.0**（真实企业级、多方言、长上下文，SOTA 显著下降）、**BIRD**（真实脏数据+外部知识，SOTA 70%+ 量级且随难度分层大幅下降）（[BIRD](https://bird-bench.github.io/)｜[Spider 2.0](https://spider2-sql.github.io/)）。**结论：单靠 schema 喂 LLM 不足以面向业务，把语义层（指标定义、维度、口径、join 关系、同义词）作为 grounding 是准确率提升的关键。** 选型：**语义层作为自研平台"一等公民"（独立实体 + 版本化 + 血缘），而非 BI 工具附属配置；text-to-SQL 只作语义层之上的自然语言入口，默认"生成语义层查询"而非"生成裸 SQL"。**

**MCP（Model Context Protocol）**（2024-11 Anthropic 发布，[规范](https://modelcontextprotocol.io/)）：JSON-RPC 2.0；服务端原语 `tools/resources/prompts`，客户端原语 `sampling/roots/elicitation`；传输 stdio 与 **Streamable HTTP**（2025-03-26 取代 HTTP+SSE）。**修订序列**：2024-11-05 → 2025-03-26 → 2025-06-18（elicitation / structured tool output / server 明确为 OAuth Resource Server）→ 2025-11-25（新增 **Tasks** 异步长任务）→ **2026-07-28（已发布）：无状态化（移除 session）、extensions 机制、direct discovery、授权加固**。授权基于 OAuth 2.1 + **RFC 9728**（Protected Resource Metadata）+ **RFC 8707**（Resource Indicators，`resource` 参数绑定受众——**最常踩的坑：省略该参数会导致 token audience 错误、每次调用 401**）。**治理归属已变更：2025-12-09 MCP 随 Agentic AI Foundation（AAIF）捐赠给 Linux Foundation**，"Anthropic 的 MCP"表述已过时；规范演进走 SEP 流程（SEP-932 治理、SEP-1302 工作组）。**无状态化对设计的根本影响：会话级上下文不再由协议隐式承载，身份/权限/审计必须逐请求显式传递。** 生态：OpenAI（2025-03）、Google（2025-04）、Microsoft（Build 2025，含 MCP on Windows 与 Server Registry）均已支持。**选型：把 MCP Server 作为平台标准出口之一（搜索资产/取 schema/取血缘/跑质量/查术语），并从第一天就把"工具级授权"设计进授权层；把"工具即数据资产"纳入目录治理范围——事后补授权几乎必然重构。**

**风险与最佳实践**：

1. **幻觉**：描述/分类/血缘可能完全错误 → 强制"AI 生成"标记 + 置信度 + 来源（prompt 版本、模型、输入指纹）；人审工作流；**AI 内容不得自动覆盖人工内容**。
2. **成本**：按"表×列×每日刷新"全量生成描述，可达数千美元/月（工程经验，待核实）→ 只对"新增/变更/高价值"实体生成、prompt 缓存（同输入指纹不重算）、小模型（本地 7B–32B 做分类/摘要，大模型只做难例）。
3. **权限泄漏（最严重）**：RAG 若不带 ACL，会把用户无权看的表名、列名甚至样本值注入 prompt 并回显 → **检索前置授权过滤**（在向量库/ES 查询层注入可见资源过滤，**不是拿到结果再过滤**）；样本值经 Presidio/DLP 脱敏后才送 LLM，高密级列直接不送；按租户隔离向量索引；LLM 网关记录"用户→prompt→引用资源"以满足审计。
4. **Prompt 注入**：**元数据本身（表描述、列注释、样本值）是用户可控内容** → 把元数据包在明确分隔的不可信区域并在系统提示中声明；工具返回结果不直接当指令；输出侧校验（生成 SQL 时强制只读、表白名单、行数上限、超时）。
5. **评价与可审计**：离线评测集（人工标注 200–500 样本，测准确率/召回率/幻觉率）+ 上线后抽样人评；AI 写操作走审计日志；模型/SDK 升级会静默改变输出分布 → 记录 `model_version` 并在变更时重跑评测。

---

## 7. 选型总览

| 领域 | **首选** | 备选 | 主要风险 |
|---|---|---|---|
| 血缘事件协议 | **OpenLineage**（采集适配层） | 自定义 + PROV-O 对齐 | namespace 语义需自建治理 |
| 内部元模型 | **entity-aspect（DataHub 式）** | TypeDef（Atlas 式） | 无跨 aspect 事务，读取需拼装 |
| 对外元数据标准 | **OpenMetadata Standards JSON Schema** | DCAT-AP（对外门户） | schema 即契约 |
| 数据契约 | **ODCS v3.2.0** + 内部 IR 转换 | datacontract.com spec | 标准未收敛 |
| SQL 解析/列级血缘 | **sqlglot 主 + Calcite 对核心层二次校验** | 纯 sqlglot | 双解析器维护成本；纯 Python 性能 |
| 非 SQL 血缘 | **dbt manifest + 运行时日志 + BI Metadata API + OL hooks 三源合并** | Spark Listener / 引擎计划 | 各源字段语义不一 |
| 血缘存储 | **Postgres（entity + edge + 有界闭包 depth≤3）+ Redis + ES** | NebulaGraph / Neo4j | 深遍历需预计算与缓存失效设计 |
| 搜索 | **ES/OpenSearch + IK 分词 + RRF 混合检索** | pgvector 起步 | 换 embedding 需重建索引 |
| 权限模型 | **RBAC + ABAC + 有限 ReBAC（层级继承）** | OpenFGA / SpiceDB | 一步到位上 Zanzibar 会失控 |
| 质量规则 | **内部规则 IR + 多编译器（SodaCL/GX/dbt/SQL）** | 直接用 GX | IR 易退化为最小公分母 |
| 异常检测 | **静态阈值 → MAD → STL+MAD**，Prophet 仅高价值指标 | Isolation Forest / River | 季节性对齐做不好则误报爆炸 |
| 采集框架 | **四层抽象 + recipe YAML + 内容指纹增量 + Stateful 删除检测** | Airflow-only | stale 误删需命名空间隔离 |
| AI 能力 | **语义层为一等公民 + MCP 出口 + AI 内容强制标记与人审** | 直接 LLM 生成 | 权限泄漏与幻觉 |

---

## 8. 参考来源

**标准与模型**：[OpenLineage](https://openlineage.io/)｜[Object Model](https://openlineage.io/docs/spec/object-model)｜[ColumnLineage Facet](https://openlineage.io/docs/1.50.0/spec/facets/dataset-facets/column_lineage_facet/)｜[integrations](https://openlineage.io/docs/integrations/about/)｜[OpenMetadata Standards](https://openmetadatastandards.org/)｜[Schemas](https://openmetadatastandards.org/schemas/overview/)｜[DataHub metadata events](https://docs.datahub.com/docs/what/mxe)｜[DataHub MCP & MCL](https://docs.datahub.com/docs/advanced/mcp-mcl)｜[Atlas TypeSystem](https://atlas.apache.org/#/TypeSystem)｜[Atlas Model](https://cwiki.apache.org/confluence/display/ATLAS/Atlas+Model)｜[ODCS](https://bitol-io.github.io/open-data-contract-standard/)｜[ODCS repo](https://github.com/bitol-io/open-data-contract-standard)｜[DPDS](https://github.com/opendatamesh-initiative/odm-specification-dpdescriptor)｜[datacontract.com](https://datacontract.com/)｜[DCAT v3](https://www.w3.org/TR/vocab-dcat-3/)｜[DCAT-AP 3.0](https://semiceu.github.io/DCAT-AP/releases/3.0.0/)｜[schema.org Dataset](https://schema.org/Dataset)｜[Frictionless Data Package](https://specs.frictionlessdata.io/data-package/)｜[Table Schema](https://specs.frictionlessdata.io/table-schema/)｜[PROV-O](https://www.w3.org/TR/prov-o/)

**元数据源接口**：[Iceberg REST OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)｜[Iceberg Spec](https://iceberg.apache.org/spec/)｜[UC lineage](https://docs.databricks.com/aws/en/data-governance/unity-catalog/data-lineage)｜[UC ABAC GRANT](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/grant-policies)｜[Glue Catalog API](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-catalog-tables.html)｜[Glue Schema Registry](https://docs.aws.amazon.com/glue/latest/dg/schema-registry.html)｜[HMS Thrift IDL](https://github.com/apache/hive/blob/master/standalone-metastore/metastore-common/src/main/thrift/hive_metastore.thrift)｜[HMS Design](https://cwiki.apache.org/confluence/display/Hive/Design)｜[Confluent SR API](https://docs.confluent.io/platform/current/schema-registry/develop/api.html)｜[Schema Evolution](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)｜[Delta PROTOCOL](https://github.com/delta-io/delta/blob/master/PROTOCOL.md)

**血缘实现**：[sqlglot](https://github.com/tobymao/sqlglot)｜[sqlglot.lineage](https://sqlglot.com/sqlglot/lineage.html)｜[sqlglot #1647](https://github.com/tobymao/sqlglot/issues/1647)｜[mypyc speedup](https://www.fivetran.com/blog/how-we-accelerated-transpilation-by-compiling-sqlglot-with-mypyc)｜[Calcite](https://calcite.apache.org/docs/)｜[RelMetadataQuery](https://calcite.apache.org/javadocAggregate/org/apache/calcite/rel/metadata/RelMetadataQuery.html)｜[CALCITE-6744](https://issues.apache.org/jira/browse/CALCITE-6744)｜[JSqlParser](https://github.com/JSQLParser/JSqlParser)｜[CWI coverage 31.59%](https://ir.cwi.nl/pub/34763/34763.pdf)｜[DataHub SQL parsing](https://docs.datahub.com/docs/lineage/sql_parsing)｜[DataHub PR #8334](https://github.com/datahub-project/datahub/pull/8334)｜[sqllineage](https://github.com/reata/sqllineage)｜[SQLMesh lineage](https://sqlmesh.readthedocs.io/en/stable/_readthedocs/html/sqlmesh/core/lineage.html)｜[dbt-colibri](https://github.com/b-ned/dbt-colibri)｜[Spline spark agent](https://github.com/AbsaOSS/spline-spark-agent)｜[flink-sql-lineage](https://github.com/Flink-zhisheng/flink-sql-lineage)｜[dbt manifest.json](https://docs.getdbt.com/reference/artifacts/manifest-json)｜[Airflow #44983](https://github.com/apache/airflow/issues/44983)｜[Tableau Metadata API model](https://help.tableau.com/current/api/metadata_api/en-us/docs/meta_api_model.html)｜[Power BI scan result](https://learn.microsoft.com/en-us/rest/api/power-bi/admin/workspace-info-get-scan-result)｜[Looker API](https://docs.cloud.google.com/looker/docs/reference/looker-api/latest/methods/LookmlModel/all_lookml_models)｜[Metabase API](https://www.metabase.com/docs/latest/api-documentation)｜[Snowflake ACCESS_HISTORY](https://docs.snowflake.com/en/user-guide/access-history)｜[Snowflake GET_LINEAGE](https://docs.snowflake.com/en/sql-reference/functions/get_lineage-snowflake-core)｜[BigQuery INFORMATION_SCHEMA.JOBS](https://cloud.google.com/bigquery/docs/information-schema-jobs)｜[Redshift SVL_STATEMENTTEXT](https://docs.aws.amazon.com/redshift/latest/dg/r_SVL_STATEMENTTEXT.html)｜[ClickHouse query_log](https://clickhouse.com/docs/reference/system-tables/query_log)｜[Neo4j Cypher](https://neo4j.com/docs/cypher-manual/current/)｜[NebulaGraph](https://docs.nebula-graph.io/)｜[LDBC SNB](https://ldbcouncil.org/benchmarks/snb/)｜[Four-DB benchmark (2023)](https://eg-fr.uc.pt/bitstream/10316/113292/1/Experimental-Evaluation-of-Graph-Databases-JanusGraph-Nebula-Graph-Neo4j-and-TigerGraphApplied-Sciences-Switzerland.pdf)｜[NebulaGraph comparison](https://www.nebula-graph.io/posts/performance-comparison-neo4j-janusgraph-nebula-graph)｜[JanusGraph architecture](https://raw.githubusercontent.com/JanusGraph/janusgraph/master/docs/getting-started/architecture.md)｜[HugeGraph architecture](https://hugegraph.apache.org/versions/1.5/docs/guides/architectural/#1-overview)｜[PG WITH RECURSIVE](https://www.postgresql.org/docs/current/queries-with.html)｜[DataHub #18636](https://github.com/datahub-project/datahub/issues/18636)｜[DataHub #18809](https://github.com/datahub-project/datahub/issues/18809)｜[Marquez](https://github.com/MarquezProject/marquez)

**存储/检索/权限**：[ES dense_vector](https://www.elastic.co/guide/en/elasticsearch/reference/current/dense-vector.html)｜[ES RRF](https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html)｜[ES DLS](https://www.elastic.co/guide/en/elasticsearch/reference/current/document-level-security.html)｜[ES FLS](https://www.elastic.co/guide/en/elasticsearch/reference/current/field-level-security.html)｜[analysis-ik](https://github.com/infinilabs/analysis-ik)｜[Zanzibar ATC'19](https://www.usenix.org/conference/atc19/presentation/pang)｜[OpenFGA concepts](https://openfga.dev/docs/concepts)｜[SpiceDB schema](https://authzed.com/docs/spicedb/concepts/schema)

**质量与可观测性**：[GX docs](https://docs.greatexpectations.io/docs/)｜[GX gallery](https://greatexpectations.io/expectations/)｜[GX migration](https://docs.greatexpectations.io/docs/0.18/reference/learn/migration_guide/)｜[SodaCL overview](https://docs.soda.io/soda-cl/soda-cl-overview.html)｜[Soda anomaly detection](https://docs.soda.io/soda-cl/anomaly-detection.html)｜[dbt data tests](https://docs.getdbt.com/docs/build/data-tests)｜[dbt unit tests](https://docs.getdbt.com/docs/build/unit-tests)｜[Deequ](https://github.com/awslabs/deequ)｜[Deequ anomaly example](https://github.com/awslabs/deequ/blob/master/src/main/scala/com/amazon/deequ/examples/anomaly_detection_example.md)｜[Prophet](https://facebook.github.io/prophet/)｜[Twitter AnomalyDetection](https://github.com/twitter/AnomalyDetection)｜[ruptures](https://centre-borelli.github.io/ruptures-docs/)｜[DataSketches](https://datasketches.apache.org/)｜[DuckDB SUMMARIZE](https://duckdb.org/docs/guides/meta/summarize)｜[ydata-profiling](https://docs.profiling.ydata.ai/)

**采集工程**：[DataHub recipe](https://docs.datahub.com/docs/metadata-ingestion/recipe)｜[StaleEntityRemovalHandler](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/state/stale_entity_removal_handler.py)｜[OM ingestion workflows](https://docs.open-metadata.org/latest/connectors/ingestion/workflows)｜[Amundsen databuilder](https://www.amundsen.io/amundsen/databuilder/)｜[Vault DB secrets](https://developer.hashicorp.com/vault/docs/secrets/databases)

**AI / LLM**：[MCP spec](https://modelcontextprotocol.io/)｜[MCP 2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)｜[MCP 2026-07-28 规范](https://blog.modelcontextprotocol.io/posts/2026-07-28/)｜[MCP 授权](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/index)｜[AAIF 成立公告](https://www.linuxfoundation.org/press/linux-foundation-announces-the-formation-of-the-agentic-ai-foundation)｜[BIRD](https://bird-bench.github.io/)｜[Spider 2.0](https://spider2-sql.github.io/)｜[Snowflake Cortex Analyst](https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst)｜[dbt Semantic Layer](https://docs.getdbt.com/docs/build/semantic-models)｜[Microsoft Presidio](https://microsoft.github.io/presidio/)｜[OpenMetadata AI SDK](https://github.com/open-metadata/ai-sdk)｜[Collate MCP](https://www.getcollate.io/blog/introducing-the-model-context-protocol-mcp-in-collate)

**待核实项**：① OpenMetadata 血缘内核是 sqllineage 还是已切 sqlglot；② Unity Catalog Lineage REST API 公开支持状态、BigQuery Data Lineage API 的 GA 区域与配额；③ sqlglot 方言模块精确清单与生产语料 QPS（须自建压测）；④ Spline Server 默认持久化后端、Power BI Scanner API 端点版本；⑤ 图库在亿级节点任意深度交互式多跳下的真实表现（厂商基准须打折并自建 LDBC 风格 PoC）；⑥ ES 与 OpenSearch 能力分叉（OpenSearch 有 `masked_fields`；ES 有 `int8_hnsw`/`bbq_hnsw` 与 `rrf` retriever）——**同一套 mapping 不能假设两边等价**。

---

## 附：实现细节补充（逐项来源见 appendix）

- **ES/OpenSearch 硬限制**：`nested_fields.limit` 默认 **50**、`nested_objects.limit` 默认 **10000**、`flattened.depth_limit` 默认 **20**、`ignore_above` 默认 **256**；`dynamic` 建议 `false`/`strict`。**BM25 默认 `k1=1.2`、`b=0.75`**，长文本建议 `b` 降到 **0.3–0.5**。RRF 除 `rank_constant`(60) 外还有 **`rank_window_size`**。FLS 靠重写 `_source` 实现且**不覆盖所有 API 路径**，须叠"alias + 索引分租户"。
- **DataHub 检索**：按实体类型构建聚合后的 search document，字段类型为自定义 **`SearchFieldType`**；**一个实体只能有一个 `browsePathV2`**。**MCE 已废弃、MAE 仍存在**。运维坑：**ES 索引漂移会导致"URN 可访问但 browse 视图缺失"**。
- **OpenMetadata 检索**：**按实体类型一索引**（`table_search_index`…），且 **`searchIndex` 本身也是被纳管资产**；`POST /v1/search/reindex`（新版 distributed-only），索引晋级走**四阶段 staged→promoted**；**`entity_extension` 是"不改 schema 挂自定义属性"的逃生舱**。
- **Deequ 精确 API**：`AnomalyDetection.runAnomalyDetection(...)` 配 **`AnomalyDetectionOptions(batchSize, computationInterval, differentiationInterval)`**；约束建议 `ConstraintSuggestionRunner().addConstraintRules(Rules.DEFAULT).setKLLParameters(KLLParameters(sketchSize=2048, shrinkingFactor=0.64, numberOfBuckets=2))`。
- **剖析与关系发现**：DataSketches 家族含 `HllSketch`/`KllSketch`/**`ThetaSketch`（集合运算基数）**，Spark 4.x 有 `hll_sketch_agg`/`kll_sketch_quantile`；**FD/IND 用 `HyFD`/`TANE` 与 `BINDER`/`MIND`（平台 `Metanome`）**自动推荐主键/外键/Join 路径；`TABLESAMPLE` 是**先采样后过滤**（`BERNOULLI` 行级 vs `SYSTEM` 块级）。
- **已停更/退休项**：**Apache Griffin 2023 年从 Apache 退休（Attic）**；Twitter `AnomalyDetection`(S-H-ESD) **原包已归档**；Amundsen 约 2022 后基本停更。
