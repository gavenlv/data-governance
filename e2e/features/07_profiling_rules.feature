# language: zh-CN
功能: 数据剖析与质量规则
  作为数据治理平台的运营者
  我需要确认剖析的精度来源被显式标注、规则编译/注册/执行全程可追溯
  以便质量判定建立在真实统计而非静默估算之上

  背景:
    假如 命名空间为 java_e2e

  场景: 13a. 剖析：实算 + 逐列统计 + 精度标注（EXACT）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我对目标数据集执行全量实算剖析
    那么 响应状态码应为 200
    并且 响应体中字段 datasets.0.rowCount 至少为 1
    并且 响应体中字段 datasets.0.columns 应为非空列表
    并且 响应体中字段 datasets.0.sampling.precision 应为 EXACT
    并且 剖析结果中每一列都应带统计指标

  场景: 13b. 采样剖析：标注 ESTIMATED + 采样方式可追溯 + 不外推
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我对目标数据集执行 10% 采样剖析
    那么 响应状态码应为 200
    并且 响应体中字段 datasets.0.sampling.precision 应为 ESTIMATED
    并且 响应体中字段 datasets.0.sampling.method 属于 hash_modulo/tablesample_system
    并且 响应体中字段 datasets.0.sampling.ratio 应为 0.1

  场景: 14a. 规则编译（YAML 前端 → IR → 源库 SQL）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我以 YAML 前端编译四条内置检查规则
    那么 响应状态码应为 200
    并且 编译预览应恰好产生 4 条规则且每条都带源库 SQL

  场景: 14b. 规则编译另两种前端（SQL 断言 / dbt tests）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我分别编译 SQL 断言前端与 dbt tests 前端规则
    那么 两种前端的编译产物非空且各自标注来源前端

  场景: 14c. 不支持的检查类型被拒绝并列出可用模板（422）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我编译一个含不支持检查类型的规则文档
    那么 响应状态码应为 422
    并且 响应体应包含文本 不支持的检查类型
    并且 响应体应包含文本 内置模板

  场景: 14d. 规则注册为实体（有 Owner 位、版本历史、进检索），口令在响应中被遮蔽
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我以 YAML 前端把四条内置检查规则注册为实体
    那么 响应状态码应为 200
    并且 注册结果应恰好 4 条规则且口令均被遮蔽

  场景: 14e. 规则执行留痕：通过 / 失败 / 无法判定三种状态可区分（PASS/FAIL/SKIPPED）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    并且 我已把四条 e2e_rules 规则注册到 java_e2e 命名空间并记录其 URN
    当 我依次执行注册出的三条代表性规则并汇总状态
    那么 规则执行留痕应可区分 PASS 与 FAIL 与 SKIPPED

  场景: 14f. env: 引用的连接未设置时报错（不降级、不静默连错库）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    并且 我已定位 java_e2e 命名空间下可剖析的 event_log 数据集
    当 我注册并执行一条 DSN 引用未设置环境变量的规则
    那么 该规则的执行结果应为 ERROR 且错误指出环境变量未设置