# language: zh-CN
功能: 多源连接器
  作为数据治理平台的运营者
  我需要确认连接器清单来自服务端单点声明，且各源都能真的连上真实系统采出元数据
  以便「支持哪些源、采到什么程度」建立在诚实标注与真实采集而非 mock 之上

  背景:
    假如 命名空间为 java_e2e

  场景: 19a. 连接器清单来自服务端单点声明（含「是否对真实系统验证过」）
    当 我以管理员令牌调用 GET /api/v1/collect/sources
    那么 响应状态码应为 200
    并且 连接器清单应声明已实现 clickhouse/mongodb/bigquery/superset
    并且 连接器清单中 bigquery 标注「对真实系统验证过」应为 false
    并且 连接器清单中 clickhouse 标注「对真实系统验证过」应为 true

  @clickhouse @external
  场景: 19b. ClickHouse 采集（system.tables + system.columns，HTTP 接口 8123）
    当 我以管理员令牌调用 POST /api/v1/collect/run
      """
      {"source":"clickhouse","dsn":"clickhouse://default:@127.0.0.1:8123","namespace":"e2e_ch","databases":["tutorial"]}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 SUCCEEDED
    并且 响应体中字段 datasetsSeen 至少为 1
    并且 响应体中字段 columnsSeen 至少为 1

  @clickhouse @external
  场景: 19c. ClickHouse 元数据质量：分区/排序键进结构化字段，估算行数显式标注
    假如 我已采集 ClickHouse 命名空间 e2e_ch 的 tutorial 库
    当 我取回 e2e_ch 命名空间下首个 ClickHouse 资产的详情
    那么 响应状态码应为 200
    并且 响应体中字段 aspects.datasetSchema.partitionKeys 应为非空列表
    并且 响应体应包含文本 估算

  @mongodb @external
  场景: 20a. MongoDB 采集（无 schema → 采样推断结构）
    当 我以管理员令牌调用 POST /api/v1/collect/run
      """
      {"source":"mongodb","dsn":"mongodb://127.0.0.1:27018","namespace":"e2e_mongo","databases":["shop"],"sampleSize":200}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 SUCCEEDED
    并且 响应体中字段 datasetsSeen 至少为 4

  @mongodb @external
  场景: 20b. MongoDB 推断质量：嵌套下钻 + 类型不稳定显式暴露 + 稀疏字段标注
    假如 我已采集 MongoDB 命名空间 e2e_mongo 的 shop 库
    当 我取回 e2e_mongo 命名空间下 customers 集合资产的详情
    那么 响应状态码应为 200
    并且 MongoDB 推断结果应含下钻出的嵌套点号路径字段
    并且 MongoDB 推断结果应显式暴露类型不稳定的字段
    并且 MongoDB 稀疏字段应被标注覆盖度

  @mongodb @external
  场景: 20c. 可空判定同时覆盖「字段缺失」与「出现过 null」
    假如 我已采集 MongoDB 命名空间 e2e_mongo 的 shop 库
    当 我取回 e2e_mongo 命名空间下 customers 集合资产的详情
    那么 响应状态码应为 200
    并且 MongoDB 的 email 字段应被判为可空

  @superset @external
  场景: 21a. Superset 采集：BI 资产作为一等实体（仪表板清单）
    当 我以管理员令牌调用 POST /api/v1/collect/run
      """
      {"source":"superset","dsn":"superset://admin:admin@127.0.0.1:18089","namespace":"e2e_bi","reconcileOrphans":true}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 SUCCEEDED
    并且 响应体中字段 dashboardsSeen 至少为 1

  @superset @external
  场景: 21b. 仪表板携带图表清单与依赖数据集
    假如 我已把命名空间 e2e_bi 的 Superset 仪表板采集入库
    当 我汇总 e2e_bi 命名空间下仪表板的图表清单与数据集依赖
    那么 e2e_bi 命名空间应至少有 1 个仪表板带图表清单

  @superset @external
  场景: 21c. BI 血缘方向正确：数据集 → 报表
    假如 我已把命名空间 e2e_bi 的 Superset 仪表板采集入库
    当 我定位一个已连到数据集的仪表板并查询其数据集的下游血缘
    那么 该仪表板应出现在其数据集的下游节点中
    并且 影响分析应报告受影响的报表数大于 0

  @bigquery
  场景: 22. BigQuery 已实现但缺凭据时给出可读错误（不静默返回空数据集）
    当 我以管理员令牌调用 POST /api/v1/collect/run
      """
      {"source":"bigquery","dsn":"bigquery://e2e-project?credentials=/nonexistent/sa.json","namespace":"e2e_bq","databases":["dwd"]}
      """
    那么 BigQuery 缺凭据时应给出可读错误而不是静默空数据集

  @dbt
  场景: 38a. dbt 连接器已登记且状态可见（不再是「计划中」）
    当 我以管理员令牌调用 GET /api/v1/collect/sources
    那么 响应状态码应为 200
    并且 dbt 连接器应已登记且说明指向 manifest

  @dbt
  场景: 38b. 采集 manifest：产出 dbt 资产 + 编译期血缘边
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    当 我把 dbt 夹具 manifest 采集到 java_e2e 命名空间
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 SUCCEEDED
    并且 响应体中字段 datasetsSeen 至少为 3
    并且 响应体中字段 edgesWritten 至少为 3

  @dbt
  场景: 38c. 解析不到的依赖跳过并记账（宁可缺边也不猜，但不能静默）
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    当 我把 dbt 夹具 manifest 采集到 java_e2e 命名空间
    那么 响应状态码应为 200
    并且 dbt 采集应回报解析不到的上游依赖（未解析）

  @dbt
  场景: 38d. dbt 模型是独立资产（platform=dbt），带列与描述
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    并且 我已把 dbt manifest 采集到 java_e2e 命名空间
    当 我取回 dbt 模型 stg_event_log 的资产详情
    那么 响应状态码应为 200
    并且 dbt 模型应是独立资产且带列与描述

  @dbt
  场景: 38e. source 解析到已采集的物理表，dbt 链路与物理血缘接得上
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    并且 我已把 dbt manifest 采集到 java_e2e 命名空间
    当 我查询 dbt 模型 stg_event_log 的上游血缘子图
    那么 响应状态码应为 200
    并且 dbt 模型的上游应包含已采集的物理表 event_log

  @dbt
  场景: 38f. 「源表 → 模型 → 物理表」边存在
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    并且 我已把 dbt manifest 采集到 java_e2e 命名空间
    当 我查询 dbt 模型 stg_event_log 的下游血缘子图
    那么 响应状态码应为 200
    并且 dbt 模型的下游应包含物化出的物理表 alert_event

  @dbt
  场景: 38g. 血缘带来源与置信度（source=dbt_manifest、confidence=1.0）
    假如 我已把 java_e2e 命名空间的 PostgreSQL 物理表采集入库
    并且 我已把 dbt manifest 采集到 java_e2e 命名空间
    当 我查询 dbt 模型 stg_event_log 的下游血缘子图
    那么 响应状态码应为 200
    并且 dbt 血缘边应带来源 dbt_manifest 且置信度为 1.0

  @dbt
  场景: 38h. manifest 不存在时给出可操作的错误（提示先 dbt compile）
    当 我以管理员令牌调用 POST /api/v1/collect/run
      """
      {"source":"dbt","dsn":"dbt:///nonexistent/dbt/project","namespace":"java_e2e"}
      """
    那么 manifest 不存在时应给出可操作的错误并提示先 dbt compile