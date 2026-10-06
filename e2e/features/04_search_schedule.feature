# language: zh-CN
功能: 检索与调度
  作为数据平台的使用者与运营者
  我需要确认检索能真实命中并给出分面与解释、调度能读能写且非法输入被拒绝
  以便发现入口可用、定时采集可控

  背景:
    假如 命名空间为 java_e2e

  场景: 7a. 索引重建（清空派生视图 → 从 seq=0 重放）
    当 我以管理员令牌调用 POST /api/v1/index/rebuild?batchSize=500
    那么 响应状态码应为 200
    并且 响应体中字段 stats.processed 至少为 1
    并且 响应体中字段 lag.lag 应为 0

  场景: 7b. 检索真实命中（标识符按 _ 切分可搜到）+ 分面 + 可见分级
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已重建检索索引
    当 我以管理员令牌调用 GET /api/v1/search?q=event_log
    那么 响应状态码应为 200
    并且 响应体中字段 count 至少为 1
    并且 响应体中字段 facets.entityType 应为非空列表
    并且 响应体中字段 visibleLevels 应为非空列表

  场景: 7c. 空结果给出最接近候选与解释（而不是空白页）
    当 我以管理员令牌调用 GET /api/v1/search?q=zzz_no_such_asset_zzz
    那么 响应状态码应为 200
    并且 响应体中字段 count 应为 0
    并且 响应体中应包含字段 suggestions

  场景: 8a. 调度清单可读（含互斥方式说明）
    当 我以管理员令牌调用 GET /api/v1/schedules
    那么 响应状态码应为 200
    并且 响应体中字段 schedules 应为非空列表
    并且 响应体应包含文本 advisory

  场景: 8b. 非法调度被显式拒绝而非静默落库
    当 我以管理员令牌调用 POST /api/v1/schedules
      """
      {"schedules":[{"name":"java_e2e_self","source":"postgres","dsn":"postgresql://postgres:root@localhost:25011/dg","namespace":"java_e2e_sched","schemas":["public"],"cron":"*/15 * * * *","enabled":true},{"name":"java_e2e_bad_source","source":"sqlite","dsn":"env:NOT_SET","cron":"0 3 * * *"}]}
      """
    那么 响应状态码应为 200
    并且 恰好 1 条调度被接受、1 条被拒绝且错误指出 sqlite

  场景: 8c. 调度立即执行并真的采集（互斥锁 + 运行留痕）
    假如 我已登记 java_e2e_self 调度
    当 我以管理员令牌调用 POST /api/v1/schedules/java_e2e_self/run
    那么 响应状态码应为 200
    并且 响应体中字段 skipped 应为 False
    并且 响应体中字段 run.status 属于 SUCCEEDED/BLOCKED