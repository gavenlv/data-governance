# language: zh-CN
功能: 控制面存活与认证授权
  作为平台运维者与安全负责人
  我需要确认控制面真的活着、认证授权走同一个判定入口
  以便后续所有能力验证都建立在一个可信的控制面上

  背景:
    假如 命名空间为 java_e2e

  场景: 1. 控制面存活且标识为 Java/Spring Boot
    当 我以匿名令牌调用 GET /healthz
    那么 响应状态码应为 200
    并且 响应体中字段 controlPlane 应为 java-spring-boot

  场景: 2a. 无令牌访问受保护接口被拒（401）
    当 我以匿名令牌调用 GET /api/v1/model
    那么 响应状态码应为 401

  场景: 2b. 身份含角色 / 权限点 / 可见分级（授权判定入口的输出）
    当 我以管理员令牌调用 GET /api/v1/me
    那么 响应状态码应为 200
    并且 响应体中字段 authenticated 应为 True
    并且 响应体中字段 roles 非空
    并且 响应体中字段 permissions 非空
    并且 响应体中字段 visibleLevels 非空

  场景: 2c. READER 越权写入被拒（403，且给出缺哪个权限点）
    当 我以只读令牌尝试写入资产 java_e2e.permcheck 的描述
    那么 响应状态码应为 403
    并且 响应体应包含文本 asset:write