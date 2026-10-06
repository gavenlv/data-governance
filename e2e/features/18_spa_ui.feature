# language: zh-CN
功能: 单页界面（SPA）由控制面同源托管
  作为平台使用者
  我需要确认界面产物确实由同一端口服务出去、深链可直达
  以便「文档里说能打开的入口」在浏览器里真的能打开

  背景:
    假如 命名空间为 java_e2e

  场景: 23a. 界面（SPA）由同一端口托管
    当 我以匿名令牌调用 GET /
    那么 响应状态码应为 200
    并且 响应体应包含文本 id="root"

  场景: 23b. SPA 深链回退到 index.html
    当 我以匿名令牌调用 GET /governance
    那么 响应状态码应为 200
    并且 响应体应包含文本 id="root"

  场景: 23c. 本批次新增入口（可观测 / AI 与 Agent）由 SPA 托管（深链可直达）
    当 我依次以匿名令牌访问 SPA 路由 /observability 与 /ai
    那么 两个路由都应返回 SPA 外壳（含 id="root"）

  场景: 23d. 界面产物与后端同源发布（构建产物确实被服务出去，而不是只存在于 dist 目录）
    当 我以匿名令牌调用 GET /
    那么 响应状态码应为 200
    并且 首页应引用构建产物 /static/index-*.js

  场景: 23e. 资产页深链可直达（构建产物目录不得与 SPA 路由撞名）
    当 我依次以匿名令牌访问 SPA 路由 /assets 与 /assets/urn%3Adg%3ADataset%3Ajava_e2e.postgresql.dg.public.event_log
    那么 两个路由都应返回 SPA 外壳（含 id="root"）

  @chrome
  场景: 18-1. 渲染 可观测 / 异常检测 时关键文案出现
    当 我用无头浏览器渲染页面 /observability?tab=anomalies
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-2. 渲染 可观测 / SLO 时关键文案出现
    当 我用无头浏览器渲染页面 /observability?tab=slos
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-3. 渲染 可观测 / 事故 时关键文案出现
    当 我用无头浏览器渲染页面 /observability?tab=incidents
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-4. 渲染 AI / 建议收件箱 时关键文案出现
    当 我用无头浏览器渲染页面 /ai?tab=inbox
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-5. 渲染 AI / 混合检索 时关键文案出现
    当 我用无头浏览器渲染页面 /ai?tab=search
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-6. 渲染 AI / 语义层指标 时关键文案出现
    当 我用无头浏览器渲染页面 /ai?tab=metrics
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-7. 渲染 AI / MCP 工具 时关键文案出现
    当 我用无头浏览器渲染页面 /ai?tab=mcp
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-8. 渲染 管理 / Edge Agent 时关键文案出现
    当 我用无头浏览器渲染页面 /admin?tab=edge
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-9. 渲染 治理 / 引擎审计 时关键文案出现
    当 我用无头浏览器渲染页面 /governance?tab=engine
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中

  @chrome
  场景: 18-10. 渲染 管理 / 能力清单 时关键文案出现
    当 我用无头浏览器渲染页面 /admin?tab=capabilities
    那么 该标签页的关键文案应全部出现在渲染后的 DOM 中