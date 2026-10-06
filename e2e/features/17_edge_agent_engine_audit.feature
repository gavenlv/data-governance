# language: zh-CN
功能: Edge Agent（推模式）与引擎侧访问审计摄入
  作为数据治理平台的运营者
  我需要确认私有子网能通过 Agent 凭据把元数据推上来、引擎侧真实访问日志能摄入并改变审计结论
  以便推模式不是绕过治理的侧路、审计闭环的最后一块拼图确实拼上

  背景:
    假如 命名空间为 java_e2e

  场景: 35a. Agent 注册一次性下发凭据（库里只存哈希）并声明未实现 Agent 二进制本身
    当 我以管理员令牌调用 POST /api/v1/edge/agents
      """
      {"agentId":"e2e_edge_agent","displayName":"e2e Agent","namespace":"e2e_edge","capabilities":["postgres"],"version":"0.1.0"}
      """
    那么 响应状态码应为 200
    并且 注册返回的一次性 Agent 凭据应为明文且以 dgagent_ 开头
    并且 注册响应应声明 Go 单二进制 Agent 未实现

  场景: 35b. Agent 用自己凭据自检（与平台令牌两套认证）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    当 我以该 Agent 身份凭据调用 GET /api/v1/edge/agent/whoami
    那么 响应状态码应为 200
    并且 响应体中字段 agentId 应为 e2e_edge_agent
    并且 响应体中字段 namespace 应为 e2e_edge

  场景: 35c. 心跳可写（存活判定依据）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    当 我以该 Agent 身份凭据发送一次心跳
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 OK
    并且 响应体中字段 agentId 应为 e2e_edge_agent

  场景: 35d. 私有子网推上来的元数据进同一套真相源（可检索、走来源保护，不是侧路）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    当 我以该 Agent 身份凭据上报边缘数据集 edge_orders
    那么 响应状态码应为 200
    并且 响应体中字段 accepted 应为 1
    并且 上报说明应声明 AUTO_COLLECTED 来源
    并且 上报后的边缘数据集应能在检索中被命中

  场景: 35e. 上报记录可查（区分「没推」与「推了但被拒」）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    并且 我已让 e2e_edge_agent 心跳一次
    并且 我已让 e2e_edge_agent 提交一次超限上报（必然被拒）
    当 我以管理员令牌调用 GET /api/v1/edge/reports
    那么 响应状态码应为 200
    并且 响应体中字段 count 至少为 2
    并且 上报记录中应存在被拒且带原因的条目

  场景: 35f. 超过单次上限时拒绝而不是截断（截断会让人以为推成功了）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    当 我以该 Agent 身份凭据上报 5001 个数据集
    那么 响应状态码应为 422
    并且 响应体应包含文本 不会截断

  场景: 35g. 凭据吊销立即生效（历史记录保留，可追溯谁在什么时候推了什么）
    假如 我已注册名为 e2e_edge_agent 的 Edge Agent 并保留一次性凭据
    当 我以管理员令牌调用 POST /api/v1/edge/agents/e2e_edge_agent/revoke
      """
      {"reason":"e2e 结束"}
      """
    那么 响应状态码应为 200
    并且 响应体中字段 status 应为 REVOKED
    并且 被吊销的 Agent 再用自身凭据心跳应被拒

  场景: 35h. 管理面仍需平台令牌（Agent 认证路径没有顺带把管理接口放开）
    当 我以匿名令牌调用 GET /api/v1/edge/agents
    那么 匿名访问管理面应返回 401 或 403
    当 我以只读令牌调用 GET /api/v1/edge/agents
    那么 响应状态码应为 200

  场景: 36a. 引擎审计摄入：接受/重复/未解析/被拒四个数字都如实回报
    当 我以管理员令牌摄入一批 trino 审计记录（含历史、重放、未解析与缺字段被拒）
    那么 响应状态码应为 200
    并且 引擎审计摄入应按四个数字如实回报：接受加重复不少于 4、未解析至少 1、被拒恰好 1
    并且 响应体应包含文本 缺少时间字段

  场景: 36b. 摄入幂等：日志重放不会把「一次访问」记成很多次（内容哈希去重）
    假如 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌把同一批 trino 记录再摄入一次
    那么 响应状态码应为 200
    并且 重放摄入应新增 0 条且去重数等于首次接受加重复

  场景: 36c. 接入情况可查：引擎清单、观测窗口、解析率（否则「已接入」只是一句话）
    假如 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌调用 GET /api/v1/access/engine-audit/coverage
    那么 响应状态码应为 200
    并且 响应体中字段 configured 应为 True
    并且 引擎清单中 trino 的记录数至少为 4 且未解析至少为 1

  场景: 36d. 解析不到资产的记录仍然保留并说明原因（丢弃等于宣称这次访问没发生）
    假如 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌调用 GET /api/v1/access/engine-audit?days=3650
    那么 响应状态码应为 200
    并且 未解析记录应保留且都带解析说明

  @psql
  场景: 36e. 使用证据改变最小权限结论：用过的建议保留、窗口完整覆盖且零访问的可回收
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位用于接入审计的 java_e2e event_log 数据集
    并且 我已为目标数据集给 engine-used@local 造好 200 天前的历史授权
    并且 我已为目标数据集给 engine-unused@local 造好 200 天前的历史授权
    并且 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌调用 GET /api/v1/access/least-privilege?limit=200
    那么 响应状态码应为 200
    并且 响应体中字段 usageDataAvailable 应为 True
    并且 最小权限复盘应把 engine-used@local 列为在用、engine-unused@local 列为窗口内零访问的回收候选

  @psql
  场景: 36f. 「被访问但从未被批准」可查 —— 这是接入引擎审计后才存在的能力
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位用于接入审计的 java_e2e event_log 数据集
    并且 我已为目标数据集给 engine-used@local 造好 200 天前的历史授权
    并且 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌调用 GET /api/v1/access/unapproved-access?days=3650
    那么 响应状态码应为 200
    并且 未授权访问主体应包含 engine-bypass@local 且不包含 engine-used@local

  场景: 36g. 审计覆盖范围按实际数据动态生成（接了引擎审计就得改口径）
    假如 引擎审计已摄入一批 trino 记录（含历史、重放、未解析与缺字段被拒）
    当 我以管理员令牌调用 GET /api/v1/access/audit-report?days=90
    那么 响应状态码应为 200
    并且 审计覆盖说明应动态声明已覆盖引擎侧查询且仍列出未接入审计的引擎

  场景: 36h. 参考采集器（tools/engine_audit_load.py）能把 JSONL 日志推上来
    当 我用参考采集器把两条 JSONL 查询日志以 warehouse 引擎推上来
    那么 采集器应无拒绝且接受加去重恰好 2 条

  场景: 36i. 不支持的引擎被明确拒绝（而不是静默当成 other 收下）
    当 我以管理员令牌调用 POST /api/v1/access/engine-audit
      """
      {"engine":"mysql","namespace":"java_e2e","records":[{"user":"x","table":"t","timestamp":"2026-10-04T00:00:00Z"}]}
      """
    那么 响应状态码应为 422
    并且 响应体应包含文本 engine