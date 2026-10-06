# language: zh-CN
功能: 影响分析与未实现接口
  作为变更评审者
  我需要在改动一张表之前看清会影响谁：按关键性排序、给出可解释理由与评分口径
  并且未实现的接口要返回 501 + 设计说明，而不是空数据

  背景:
    假如 命名空间为 java_e2e

  场景: 10a. 影响分析：按评分排序 + 可解释理由 + 评分口径
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于影响分析
    并且 我已解析 event_log 到 alert_event 的 SQL 血缘（为影响分析奠基）
    当 我以管理员令牌对锁定数据集执行深度 3 的含列级影响分析
    那么 响应状态码应为 200
    并且 响应体中字段 affectedCount 至少为 1
    并且 响应体中字段 nodes.0.score 非空
    并且 响应体中字段 nodes.0.reasons 应为非空列表
    并且 响应体中应包含字段 scoring.formula
    并且 影响分析结果按评分从高到低排序且首节点给出理由

  场景: 10b. 深度截断被显式标注（不把「3 跳内」说成「总共就这些」）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于影响分析
    并且 我已解析 event_log 到 alert_event 的 SQL 血缘（为影响分析奠基）
    当 我以管理员令牌对锁定数据集执行深度 1 的含列级影响分析
    那么 响应状态码应为 200
    并且 响应体中字段 reachedMaxDepth 应为 True
    并且 响应体中字段 truncationNote 非空

  场景: 11. 仍未实现的接口返回 501 且带设计说明（不是空数据）
    当 我以管理员令牌调用 GET /api/v1/collect/alerts
    那么 响应状态码应为 501
    并且 响应体应包含文本 docs/