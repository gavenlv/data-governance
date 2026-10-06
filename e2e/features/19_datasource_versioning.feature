# language: zh-CN
功能: 数据源管理与资产版本管理
  作为数据平台的运营者
  我需要把连接保存成可复用实体（凭据加密且永不回显），并让资产的每一次变更都可追溯、可回滚
  以便扫描不必反复抄写完整连接细节，而"谁在什么时候改了什么"永远有据可查

  背景:
    假如 命名空间为 java_e2e

  场景: 24a. 数据源清单可读且如实声明加密状态
    当 我以管理员令牌调用 GET /api/v1/datasources
    那么 响应状态码应为 200
    并且 响应体中应包含字段 cipher.configured
    并且 响应体中字段 cipher.algorithm 应为 AES-256-GCM
    并且 响应体中应包含字段 dataSources

  场景: 24b. 连接信息属于采集运维面：只读令牌不可见
    当 我以只读令牌调用 GET /api/v1/datasources
    那么 响应状态码应为 403

  场景: 24c. 保存连接后端点脱敏、凭据不回显
    假如 我准备一个名为 bdd-ds-24c 的 PostgreSQL 数据源连接
    那么 响应状态码应为 200
    并且 响应体中字段 connector 应为 postgres
    并且 响应体中字段 hasCredentials 应为 True
    并且 响应体中字段 endpoint 不应包含文本 root
    并且 响应体中字段 endpoint 不应包含文本 @

  场景: 24d. 编辑时留空凭据字段即保持原值（无需重抄完整连接）
    假如 我准备一个名为 bdd-ds-24d 的 PostgreSQL 数据源连接
    当 我只更新该数据源的命名空间为 java_e2e 而不提供任何凭据
    那么 响应状态码应为 200
    并且 响应体中字段 namespace 应为 java_e2e
    并且 响应体中字段 hasCredentials 应为 True
    并且 该数据源的端点应与保存时一致

  场景: 24e. 用保存的连接测试连通性，并一键扫描
    假如 我准备一个名为 bdd-ds-24e 的 PostgreSQL 数据源连接
    当 我用该数据源测试连接
    那么 响应状态码应为 200
    并且 响应体中字段 ok 应为 True
    当 我用该数据源触发一次扫描
    那么 响应状态码应为 200
    并且 响应体中字段 status 属于 SUCCEEDED/BLOCKED
    并且 响应体中字段 dataSourceId 非空

  场景: 25a. 版本时间线：每个 aspect 恰有一个当前版本，且历史版本被保留
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我获取 java_e2e 命名空间下首个资产的版本时间线
    那么 响应状态码应为 200
    并且 响应体中字段 count 至少为 1
    并且 响应体中字段 versions 应为非空列表
    并且 版本时间线中每个 aspect 恰有一个当前版本
    并且 版本时间线中不应夹带完整版本内容

  场景: 25b. 历史版本内容可读取（时间旅行）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我获取 java_e2e 命名空间下首个资产的版本时间线
    那么 响应状态码应为 200
    当 我读取该资产 datasetSchema 的任一历史版本内容
    那么 响应状态码应为 200
    并且 响应体中字段 aspectType 应为 datasetSchema
    并且 响应体中应包含字段 data

  场景: 25c. 回滚 = 用旧版本内容追加新版本（历史不满不减）
    假如 我已采集 PostgreSQL 命名空间 java_e2e 的 public schema
    当 我获取 java_e2e 命名空间下首个资产的版本时间线
    那么 响应状态码应为 200
    当 我把该资产的 datasetSchema 回滚到上一个历史版本
    那么 响应状态码应为 200
    并且 回滚后的新版本应等于回滚前的版本号加一
    并且 响应体中字段 restoredFrom 非空
    当 我获取 java_e2e 命名空间下首个资产的版本时间线
    那么 响应状态码应为 200
    并且 版本时间线中每个 aspect 恰有一个当前版本