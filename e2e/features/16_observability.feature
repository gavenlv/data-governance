# language: zh-CN
功能: 可观测性（异常检测、SLO 与事故闭环）
  作为数据治理平台的运营者
  我需要确认指标越界能被稳健统计检出、上游异常会抑制下游、SLO 达成率来自真实数据、事故必须闭环
  以便可观测性不是一块看板，而是一条「异常 → 达成率 → 事故 → 沉淀规则」的治理运营链

  背景:
    假如 命名空间为 java_e2e

  @psql
  场景: 33a. MAD 稳健 Z 检出越界（并给出方法/阈值/样本数，误报可回溯到方法）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下 public.event_log 作为异常检测上游数据集
    并且 我已为该上游数据集注入 MAD 可检出的合成历史序列
    当 我对上游数据集发起方法为 mad 的异常扫描
    那么 响应状态码应为 200
    并且 响应体中字段 detectionCount 至少为 1
    并且 响应体中字段 detections.0.method 应为 mad
    并且 响应体中字段 detections.0.samples 至少为 7
    并且 首条检出应给出不小于 3 的稳健 Z 绝对值且扫描回报了阈值

  @psql
  场景: 33b. 上游抑制下游：下游连锁异常被标记为 PROPAGATED 并抑制（防告警风暴）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 event_log→alert_event 上下游数据集并解析出两者的血缘
    并且 我已为上下游数据集注入合成历史序列
    并且 我已对上游数据集完成一次 mad 扫描以留下异常痕迹
    当 我对下游数据集发起方法为 mad 的异常扫描
    那么 响应状态码应为 200
    并且 下游首条检出应被标记为 PROPAGATED 并抑制、来源指向上游

  @psql
  场景: 33c. 被抑制的异常默认不出现，但可查（抑制 ≠ 删除）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 event_log→alert_event 上下游数据集并解析出两者的血缘
    并且 我已为上下游数据集注入合成历史序列
    并且 我已对上游数据集完成一次 mad 扫描以留下异常痕迹
    当 我分别查看默认异常清单与含抑制的异常清单
    那么 含抑制的异常清单应比默认清单多出被抑制的条目

  @psql
  场景: 33d. 样本不足的序列显式跳过（「没判定」不等于「正常」）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下 public.event_log 作为异常检测上游数据集
    并且 我已为该上游数据集注入样本不足的合成短序列
    当 我对上游数据集的短序列发起方法为 mad 的异常扫描
    那么 响应状态码应为 200
    并且 响应体中字段 skipped 应为非空列表
    并且 样本不足的序列应被显式跳过而非判为正常

  场景: 33e. 未实现的方法被明确拒绝并说明分层（L1 静态阈值不在这里重复实现）
    当 我以管理员令牌调用 POST /api/v1/observability/anomalies/scan
      """
      {"method":"stl"}
      """
    那么 响应状态码应为 422
    并且 响应体应包含文本 静态阈值
    并且 响应体应包含文本 seasonal_mad

  场景: 34a. SLO 达成率由真实执行数据算出（通过率取 rule_run；无数据时回报 no_data）
    假如 我已定义 java_e2e 范围内的质量通过率 SLO
    当 我度量该 SLO 的达成率
    那么 响应状态码应为 200
    并且 达成率应来自真实执行数据或明确回报无数据

  场景: 34b. 非法 SLO 定义被拒（类型白名单 + target ∈ (0,1]）
    当 我以管理员令牌调用 POST /api/v1/observability/slos
      """
      {"name":"e2e_bad_slo","sloType":"quality","target":1.5}
      """
    那么 响应状态码应为 422
    并且 响应体应包含文本 不支持的 sloType
    当 我以管理员令牌调用 POST /api/v1/observability/slos
      """
      {"name":"e2e_bad_target","sloType":"quality_pass_rate","target":1.5}
      """
    那么 响应状态码应为 422
    并且 响应体应包含文本 target

  场景: 34c. 开事故自动算血缘影响面（受影响清单直接来自影响分析）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 event_log→alert_event 上下游数据集并解析出两者的血缘
    当 我以 event_log 为主资产开一个 HIGH 级 e2e 事故
    那么 响应状态码应为 200
    并且 响应体中字段 incidentId 非空
    并且 响应体中字段 impact.affectedCount 至少为 0
    并且 响应体中字段 affectedCount 至少为 0

  场景: 34d. 闭环强制：解决事故必须关联沉淀出的规则，或说明为什么不需要
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下 public.event_log 作为事故主资产
    并且 我已就事故主资产开出一个 HIGH 级 e2e 事故并记录其编号
    当 我在未关联沉淀规则的情况下解决该事故
    那么 响应状态码应为 422
    并且 响应体应包含文本 闭环
    当 我关联沉淀规则 e2e_rowcount 后解决该事故
    那么 响应状态码应为 200
    并且 解决结果应记录关联的沉淀规则 URN

  场景: 34e. 事故时间线保留全过程（检测 → 解决 → 沉淀规则）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下 public.event_log 作为事故主资产
    并且 我已就事故主资产开出一个 HIGH 级 e2e 事故并记录其编号
    并且 我已为记录的事故关联沉淀规则并闭环解决
    当 我读取该事故的详情与时间线
    那么 响应状态码应为 200
    并且 事故时间线应包含 DETECTED 与 RESOLVED

  场景: 34f. 运营总览暴露两个真正的观察点：MTTR 与「解决了但没沉淀规则」的事故数
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下 public.event_log 作为事故主资产
    并且 我已就事故主资产开出一个 HIGH 级 e2e 事故并记录其编号
    并且 我已为记录的事故关联沉淀规则并闭环解决
    当 我以管理员令牌调用 GET /api/v1/observability/overview
    那么 响应状态码应为 200
    并且 响应体中应包含字段 mttrHours
    并且 响应体中应包含字段 withoutRule
    并且 响应体中应包含字段 anomaly
    并且 运营总览应给出 MTTR 统计与未沉淀规则的事故清单