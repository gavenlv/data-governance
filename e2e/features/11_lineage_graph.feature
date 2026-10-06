# language: zh-CN
功能: 血缘画布与边的人工确认
  作为数据血缘的复核者
  我需要血缘子图的节点与边都带属性、只沿血缘类型遍历、裁剪显式回报
  并能确认或驳回一条边，让置信度模型吃进人工背书

  背景:
    假如 命名空间为 java_e2e

  场景: 24a. 血缘子图：节点与边都带属性（线型可按来源/置信度绘制）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    当 我以管理员令牌对锁定数据集向下游查询深度 3 的血缘子图
    那么 响应状态码应为 200
    并且 子图节点与边均非空
    并且 子图中的每条边都带来源、置信度与边标识

  场景: 24b. 只沿「血缘关系类型」遍历（contains 这类结构边不算血缘）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    当 我以管理员令牌对锁定数据集向上游查询深度 3 的血缘子图
    那么 响应状态码应为 200
    并且 子图上游节点类型中不应出现 Platform 或 Container

  场景: 24c. 节点上限被裁剪时显式回报（不静默截断）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    当 我以管理员令牌对锁定数据集向下游查询深度 3 且节点上限为 2 的血缘子图
    那么 响应状态码应为 200
    并且 响应体中字段 nodeLimitReached 应为 True
    并且 子图提示中应包含「上限」的裁剪说明

  场景: 24d. 确认血缘边：置信度提升到人工级并记名（喂养置信度模型）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    并且 我已锁定 event_log 到 alert_event 的 sql_parse 血缘边
    当我以管理员令牌确认锁定血缘边
    那么 响应状态码应为 200
    并且 锁定血缘边在子图中的置信度应达到人工级且记录确认人

  场景: 24e. 驳回血缘必须说明原因（422，而不是静默标记）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    并且 我已锁定 event_log 到 alert_event 的 sql_parse 血缘边
    当我以管理员令牌不带原因地驳回锁定血缘边
    那么 响应状态码应为 422

  场景: 24f. 驳回后该边不再参与血缘遍历（标记而非删除）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已解析出 event_log→alert_event→collector_state 两跳血缘链
    并且 我已锁定 java_e2e 命名空间下的 public.event_log 数据集用于血缘画布
    并且 我已锁定 event_log 到 alert_event 的 sql_parse 血缘边
    当我以管理员令牌带原因地驳回锁定血缘边
    那么 响应状态码应为 200
    并且 锁定血缘边不应再出现在下游子图中
    并且 我随后将锁定血缘边恢复为已确认状态

  场景: 24g. 批量退役边必须显式给出 edgeType（避免误伤）
    当 我以管理员令牌调用 POST /api/v1/lineage/edges/retire?reason=e2e
    那么 响应状态码应为 400 或 422