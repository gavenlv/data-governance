# language: zh-CN
功能: 采集与资产
  作为数据平台的运营者
  我需要确认能从真实源库采集元数据，并且采到的资产可列、可读、带结构
  以便后续检索、血缘、质量都建立在真实采集出来的资产之上

  背景:
    假如 命名空间为 java_e2e

  场景: 5. 真实采集 PostgreSQL（含护栏与快照）
    当 我以管理员令牌调用 POST /api/v1/collect/postgres
      """
      {"jdbcUrl":"jdbc:postgresql://localhost:25011/dg","username":"postgres","password":"root","namespace":"java_e2e","schemas":["public"],"maxDeleteRatio":0.3}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 status 属于 SUCCEEDED/BLOCKED

  场景: 6a. 资产列表可读且带可见分级（列表同样前置过滤）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我以管理员令牌调用 GET /api/v1/assets?prefix=urn:dg:Dataset:java_e2e&limit=5
    那么 响应状态码应为 200
    并且 响应体中字段 count 至少为 1
    并且 响应体中字段 visibleLevels 应为非空列表

  场景: 6b. 资产详情含结构（datasetSchema）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我获取 java_e2e 命名空间下首个资产的详情
    那么 响应状态码应为 200
    并且 响应体中字段 aspects.datasetSchema.fields 应为非空列表