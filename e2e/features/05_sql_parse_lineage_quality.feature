# language: zh-CN
功能: SQL 解析与血缘质量
  作为数据平台的运营者
  我需要确认 SQL 静态解析能真正把列级血缘写进图、dryRun 只预览不落库、解析失败可运营
  以便「没有血缘」与「解析不出来」永远是可区分的两件事

  背景:
    假如 命名空间为 java_e2e

  场景: 9a. SQL 解析侧车就绪（含 sqlglot 版本与方言数）
    当 我以管理员令牌调用 GET /api/v1/lineage/parse/sidecar
    那么 响应状态码应为 200
    并且 响应体中字段 available 应为 True
    并且 响应体中字段 health.sqlglotVersion 非空
    并且 响应体中字段 health.dialects 的长度至少为 6

  场景: 9b. 解析入库：列级血缘真的写进图（表名解析不到就不写边）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我以管理员令牌调用 POST /api/v1/lineage/parse
      """
      {"sql":"INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'","dialect":"postgres","namespace":"java_e2e"}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 statements 至少为 1
    并且 响应体中字段 tableEdges 至少为 1
    并且 响应体中字段 columnEdges 至少为 1
    并且 解析结果中未解析表名列表应为空

  场景: 9c. dryRun 只解析不写库（先看效果再入库）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我以管理员令牌调用 POST /api/v1/lineage/parse
      """
      {"sql":"INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'","dialect":"postgres","namespace":"java_e2e","dryRun":true}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 dryRun 应为 True
    并且 响应体中字段 columnEdges 至少为 1

  场景: 9c2. 解析第二段 SQL 形成两跳链（供深度截断验证）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log 到 alert_event 的表级血缘
    当 我以管理员令牌调用 POST /api/v1/lineage/parse
      """
      {"sql":"INSERT INTO collector_state SELECT a.seq FROM alert_event a","dialect":"postgres","namespace":"java_e2e"}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 tableEdges 至少为 1

  场景: 9d. 解析失败必须落样本库（把「解析不出来」变成可运营指标）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我以管理员令牌调用 POST /api/v1/lineage/parse
      """
      {"sql":"INSERT INTO alert_event SELEC broken FROM","dialect":"postgres","namespace":"java_e2e"}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 failed 至少为 1
    并且 响应体中字段 samplesRecorded 至少为 1

  场景: 9e. 血缘质量报告暴露解析样本（区分「没有血缘」与「解析不出来」）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已登记一条解析失败样本
    当 我以管理员令牌调用 GET /api/v1/lineage/quality
    那么 响应状态码应为 200
    并且 响应体中字段 parseSamples 应为非空列表