# language: zh-CN
功能: 模型注册表与能力清单
  作为平台的使用者与架构负责人
  我需要确认实体/方面模型可读、能力清单如实标注实现程度
  以便后续所有能力都建立在一个唯一事实源与诚实的完成度口径上

  背景:
    假如 命名空间为 java_e2e

  场景: 3. 模型注册表可读（唯一事实源）
    当 我以管理员令牌调用 GET /api/v1/model
    那么 响应状态码应为 200
    并且 响应体中字段 entityTypes 的长度至少为 10

  场景: 4a. 能力清单三态齐备（未实现已显式标注）
    当 我以管理员令牌调用 GET /api/v1/capabilities
    那么 响应状态码应为 200
    并且 响应体中字段 summary.implemented 至少为 1
    并且 响应体中字段 summary.notImplemented 至少为 1
    并且 响应体中字段 summary.total 至少为 1
    并且 响应体中字段 domains 非空

  场景: 4b. Batch 1 能力状态已更新为 IMPLEMENTED
    当 我以管理员令牌调用 GET /api/v1/capabilities
    那么 响应状态码应为 200
    并且 以下能力状态应均为 IMPLEMENTED：core.search-index/policy.rbac/policy.abac/ingestion.scheduler/lineage.impact-analysis

  场景: 4c. 资产版本管理与数据源注册表已声明为 IMPLEMENTED
    当 我以管理员令牌调用 GET /api/v1/capabilities
    那么 响应状态码应为 200
    并且 以下能力状态应均为 IMPLEMENTED：core.asset-versioning/ingestion.datasource-registry