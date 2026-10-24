# 22 · 分批实施计划（把「未实现」逐批变成「已实现」）

> 状态：**Batch 1–5 全部完成**（见 `docs/21 §12`–`§17`），**Batch 6（审计闭环 + CI 门禁）、Batch 7（dbt 连接器）、Batch 8（血缘 L2 校验层）完成**（`docs/21 §19`–`§21`），
> 另有 **NFR-SEC-01 供应链安全要求**落地（`docs/23`、`docs/21 §18`）。
> 依据：`docs/11` 路线图（Phase 0→4）+ `docs/21 §7` 下一步 + `docs/21 §11.7` 本轮未做
> 纪律：**只有真做完并跑过可复现验证的能力，才允许把 `/api/v1/capabilities` 里的状态从
> `NOT_IMPLEMENTED` / `PARTIAL` 改成 `IMPLEMENTED`。** 状态虚标比不实现更糟。
> 另：批次完成定义新增第 6 条「供应链安全扫描通过」（NFR-SEC-01）。

进度：**已实现 41 / 部分 6 / 未实现 1 / 合计 48**（Batch 1 前为 14 / 2 / 21 / 37；另有并行工作流新增 2 项能力，见 `docs/21 §21.6`；Batch 9 新增 `policy.oidc-auth`；Batch 10 把 `dg-sdk` 装配进来，因此 `sdk.*` 三项首次进入计数）。
仅剩的未实现项是 `core.index-opensearch`（检索后端替换，触发条件未到）；
7 项部分实现的缺口与后续批次计划见 `docs/21 §19.6`、`§20.5`、`§22.6` 与 `§23.6`。

---

## 1. 批次划分（按依赖排序，不按文档章节顺序）

排序原则：**先解锁其它批次的（检索、权限、调度），再做依赖它们的（契约门禁、访问申请、AI）。**

| 批次 | 主题 | 能力项 | 为什么排这个顺序 |
|---|---|---|---|
| **Batch 1** ✅ | 目录可用主线 | `core.search-index`、`policy.rbac`、`policy.abac`、`ingestion.scheduler`、`lineage.impact-analysis`、`lineage.sql-parse` | 检索与权限是「目录能用」的两条腿；调度让采集能持续跑；影响分析让血缘有业务用途；SQL 解析是血缘覆盖率的主力来源 |
| **Batch 2** ✅ | 质量与契约 | `quality.profiling`、`quality.rules`、`quality.contract`、`quality.ci-gate` | 依赖 Batch 1 的调度（规则按 cron 跑）与检索（契约/规则是实体，需要进检索）；契约的 quality 段要编译成规则 |
| **Batch 3** ✅ | 血缘可视化 + 连接器 | `ingestion.connectors-more` 🟡、`ingestion.bi-assets` ✅、`lineage.visualization` 🟡 | 可视化依赖影响分析（Batch 1）；连接器依赖调度（Batch 1）。**三项均已交付（其中两项留有明确缺口）** |
| **Batch 4** ✅ | 访问治理（差异化核心） | `policy.access-request`、`policy.compiler`、`policy.audit` | 依赖 `policy.rbac`/`abac`（Batch 1）、资产分级与契约（Batch 2）；`docs/20 §8` 认定这是最值得投入的差异化。**已完成**（compiler 留明确缺口：不直接推送执行引擎、直连绕过不可检测） |
| **Batch 5** ✅ | 高级运营与 AI | `quality.anomaly` ✅、`quality.slo-incident` ✅、`ai.suggestion` ✅、`ai.mcp` ✅、`ai.semantic-layer` ✅、`ingestion.edge-agent` ✅、`ai.semantic-search` 🟡、`core.search-chinese` 🟡；`core.index-opensearch` ❌ 未做 | 依赖前四批的数据积累（质量历史、权限、血缘规模）；AI 无据不能答。**已完成**：7 项落地、2 项带明确缺口（向量检索 / 词典分词），1 项按计划不做 |
| **Batch 9** ✅ | 生产认证与安全基线（**上线前置**） | `policy.oidc-auth` ✅（新增） | 前八批把"功能"补齐了，但认证一直是**静态令牌**（开发形态）。功能再全也不能这样上线：令牌不会过期、不绑定真实身份。因此这一批不新增业务功能，只把"能上线"缺的那块补上（见 §2.9 与 `docs/21 §22`） |
| **Batch 10** ✅ | 客户端 SDK 与命令行 | `sdk.clients` ✅、`sdk.cli` ✅（`dg-sdk` 首次装配）；`sdk.embedded` ❌ 仍未做 | 平台"能用"之后的下一个问题是"怎么被别人用起来"：CI 要脚本化、运维要诊断、外部系统要调用。SDK 不是第二个平台，它只做传输与类型（见 §2.10 与 `docs/21 §23`） |

> 说明：`policy.abac` 原计划在 Batch 4，实际提前到 Batch 1 —— 因为**检索的前置可见性过滤
> 本身就要求分级判定**，把它推后会导致"先做一个会泄露无权资产存在性的搜索"。
> 计划调整在此显式记录，而不是悄悄改口径。

### 每批的完成定义（Definition of Done）

一批只有同时满足下面 6 条才算完成：

1. **真实实现**：不是接口占位，端到端真的产生数据/真的判定；
2. **Java 编译 + 单测通过**：`mvn -B test` 全绿，且新增单测覆盖关键纯逻辑；
3. **端到端验证**：`tools/java_e2e_verify.py` 扩展对应检查项并全绿（可复现，非一次性手工验证）；
4. **界面接线**：前端对应页面调真实接口，未实现的部分仍然显式标注；
5. **文档同步**：`docs/21` 增补本批记录（含修掉的真实缺陷与已知边界），能力清单计数更新；
6. **供应链安全**：`python tools/dependency_audit.py` 退出码为 0
   （无已知 CRITICAL/HIGH；MEDIUM 已修或已登记豁免）——
   依据 `docs/23` **NFR-SEC-01**；新增依赖必须同时满足 §4 的 FOSS 选型标准。

---

## 2. 各批明细与结果

### Batch 1（已完成）：目录可用主线

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `core.search-index` | 未实现 | ✅ 已实现 | `ADR-002`（派生视图可丢弃重建）、`docs/09 §9.3`（**前置**授权过滤，不得先取回再筛） |
| `policy.rbac` | 未实现 | ✅ 已实现 | `docs/09 §9.7`（未知角色丢弃，拒绝静默提权） |
| `policy.abac` | 未实现 | ✅ 已实现 | `docs/09 §9.3/§9.7`（任何一处用不同判定就是越权漏洞） |
| `ingestion.scheduler` | 未实现 | ✅ 已实现 | `docs/09 §9.1`（**互斥用 PG advisory lock，不用 Redlock**；凭证不落明文） |
| `lineage.impact-analysis` | 未实现 | ✅ 已实现 | `docs/09 §9.2`（去环、有界闭包、`score = w(v)·α^depth`） |
| `lineage.sql-parse` | 部分实现 | 🟡 部分实现（缺口已更换） | `docs/10 §2`、`docs/09 §9.2`（失败必须显式降级并落样本库） |

验证：`tools/java_e2e_verify.py` **27/27**；Java 单测 62 项；`pnpm build` 通过。详见 `docs/21 §12`。

### Batch 2（已完成）：质量与契约

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `quality.profiling` | 未实现 | ✅ 已实现 | `docs/09 §9.4`（**精度必须标注**；哈希取模采样优先、不用 LIMIT 头部采样；高密级列只输出统计量） |
| `quality.rules` | 未实现 | ✅ 已实现 | `docs/09 §9.4`（统一 IR + 保留 engineHints；规则推到源系统执行；"没跑成"必须显式） |
| `quality.contract` | 未实现 | ✅ 已实现 | `docs/09 §9.5`（契约是可执行的接口；apiVersion 与 version 分开治理；豁免不允许永久） |
| `quality.ci-gate` | 未实现 | ✅ 已实现 | `docs/09 §9.5`（兼容性 + 影响面 + 治理属性三问；插件不可用时降级为"不阻断 + 记录"） |

新增显式标注的未实现项：`quality.ci-plugin`（官方插件与 PR 评论回写）。

验证：`tools/java_e2e_verify.py` **48/48**（本批 21 项）；Java 单测 **95 项**（本批 +33）；
`python tools/ci/contract_gate.py` 对真实平台跑通。详见 `docs/21 §13`。

### Batch 3（上半，已完成）：连接器与 BI 资产

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `ingestion.connectors-more` | 未实现 | 🟡 部分实现（5 个连接器） | `docs/11 §1.1`（白名单核心源优先，不追求数量）；连接器状态由 `ConnectorRegistry` 单点声明，且区分"实现了"与"对真实系统验证过" |
| `ingestion.bi-assets`（新增） | — | ✅ 已实现 | `docs/09 §9.1`（BI 资产进同一套采集框架）；血缘边方向必须 from=上游、to=下游，因此新增 `consumedBy` |

已实现：PostgreSQL、ClickHouse、MongoDB、BigQuery（**未对真实系统验证**）、Superset。
未实现：MySQL / Trino / Hive-HMS / dbt / Airflow / SQLite / DuckDB / Tableau。

验证：`tools/java_e2e_verify.py` **58/58**（本批 10 项，全部对**真实运行的系统**采集）；
Java 单测 **115 项**（本批 +20）。详见 `docs/21 §14`。

**一个被真实使用发现的模型缺陷**：`readsFrom` 曾被当作血缘边使用，但它的方向
（报表 → 数据集）与全局约定（from=上游、to=下游）相反，导致"从表出发找不到报表"、
影响分析给出相反结论。修正为新增 `consumedBy` 并把 `readsFrom` 明确为关联语义边。

### Batch 3（下半，已完成）：血缘可视化画布

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `lineage.visualization` | 未实现 | 🟡 部分实现 | `docs/14 §3.3`（**线型 = 可信度**、路径高亮、每条边可确认/驳回）；`docs/09 §9.2`（服务端裁剪必须有界且显式） |

新增接口：`/api/v1/lineage/subgraph`（带属性的节点与边 + 五维裁剪 + 显式截断回报）、
`/api/v1/lineage/edges/{id}/confirm`、`/api/v1/lineage/edges/{id}/reject`、
`/api/v1/lineage/edges/retire`（批量退役）。

**修正了两处让血缘图失去意义的语义问题**：
① `contains` 等结构边被当作血缘（某表"上游"多达 41 个）→ 只沿 `lineage: true` 的关系类型遍历；
② 边类型迁移（`readsFrom` → `consumedBy`）留下方向矛盾的旧边 → 显式批量退役。

验证：`tools/java_e2e_verify.py` **65/65**（本轮 7 项）。详见 `docs/21 §15`。

### Batch 4（已完成）：访问治理 —— 本方案的核心差异化

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `policy.access-request` | 未实现 | ✅ 已实现 | `docs/09 §9.7`（自动填充 + 最小粒度建议 + 禁止自批 + **到期必回收** + 定期复核） |
| `policy.compiler` | 未实现 | 🟡 部分实现 | `ADR-008` + `docs/20 §8`（**不自研执行引擎**）；`docs/09 §9.7`（编译器必须可快照测试；覆盖率必须度量且显式列出盲区） |
| `policy.audit` | 未实现 | ✅ 已实现 | `docs/09 §9.7`（审计报告必须声明覆盖范围："没有记录 ≠ 没有发生"） |

新增显式标注的未实现项：`policy.engine-audit-ingest`（引擎侧访问审计摄入）。

验证：`tools/java_e2e_verify.py` **85/85**（本批 20 项）；Java 单测 **139 项**（本批 +24）。
详见 `docs/21 §16`。

**一处实测发现的"假数字"缺陷**：覆盖率判定里只有 `table` 的策略会跳过所有前缀检查并返回 true，
把覆盖率算成 100%（实际只覆盖一张表）。修复后覆盖率从 100% 变成真实的 8.9% ——
`docs/09 §9.7` 说"宣称已下发而不度量覆盖率是最危险的表述"，这里是它的变体：
**度量了，但数字是假的**，同样危险。

### Batch 5（已完成）：AI 原生能力、可观测性与 Edge Agent

| 能力 ID | 原状态 | 结果 | 关键设计约束（来自设计文档） |
|---|---|---|---|
| `ai.suggestion` | 未实现 | ✅ 已实现 | `docs/13 §3`（AI 只写建议、必须带 provenance 与置信度、人工确认后生效） |
| `ai.mcp` | 未实现 | ✅ 已实现 | `docs/13 §6`（以调用者身份执行、写操作只走建议或审批、全量调用审计） |
| `ai.semantic-layer` | 未实现 | ✅ 已实现 | `docs/13 §1.1`（指标口径必须挂到物理列与血缘上） |
| `ai.semantic-search` | 未实现 | 🟡 部分实现 | `docs/13 §1.1`（检索必须**前置**授权过滤）；RRF 融合已做，**向量路未做**（无 embedding 服务） |
| `quality.anomaly` | 未实现 | ✅ 已实现 | `docs/09 §9.4`（分层检测；**误报率是第一优先级指标**；上游抑制下游） |
| `quality.slo-incident` | 未实现 | ✅ 已实现 | `docs/09 §9.4`（达成率用真实数据；**每次故障必须产出一条规则或检测器**） |
| `ingestion.edge-agent` | 未实现 | ✅ 已实现（控制面侧） | `ADR-012`（数据不出域 → 推模式）；**Go 单二进制 Agent 本体未实现**，但协议与接入点就绪 |
| `core.search-chinese` | 未实现 | 🟡 部分实现 | `docs/09 §9.3`；用 bigram 替代词典分词，写入与查询两侧同构 |

`core.index-opensearch` 本批**按计划不做**（当前 PG tsvector 是可替换实现，
触发条件是实体规模超 500 万或需要真分词/kNN）。

验证：`tools/java_e2e_verify.py` **123/123**（本批 38 项）；Java 单测 **142 项**（本批 +3）；
`tools/ui_render_check.py` **9/9** 标签页渲染通过。详见 `docs/21 §17`。

**本批的缺陷密度最高（14 条）**，其中三条值得单独记住：

1. `INSERT … ON CONFLICT DO NOTHING RETURNING id` + `queryForObject` → 冲突时抛异常，
   于是"重复建议静默跳过"这个设计**从未真正生效**，只是之前没有被触发；
2. `String.valueOf(map.get(k))` 在键缺失时返回字符串 `"null"` ——
   它让"驳回必须给理由"变成"理由可以不填（会被记成 null）"，也让 `sourceFormat` 报错指向错误的地方；
3. 查询侧按 bigram 切、索引侧的**描述**却是整段 lexeme →
   "按中文描述找资产"静默失效（没有报错，只是永远搜不到）。

这三条的共同点：**都不是崩溃，而是"看起来在工作"**。收尾批的价值有一半在于把它们挖出来。



`partial` 的原文是「侧车未部署」。一个**永远返回 501 的代理**不构成能力。
Batch 1 把它做成：Python 侧车以独立进程提供 `POST /api/v1/lineage/parse`，
Java 控制面调用它 → 把解析结果解析为 URN → 写 `edge` 表 → 解析不够好的样本落
`lineage_parse_sample`。端到端脚本会**真的启动侧车进程**、跑一遍、断言边真的进库。

---

### 2.9 Batch 9（已完成）：生产认证与安全基线

前八批解决的是"功能有没有"，这一批解决的是"能不能上线"。触发点很具体：README 的
「已知的、本计划不打算解决的边界」里有一条 **"生产认证仍须从静态令牌迁到 OIDC/JWKS"** ——
它不在能力清单里，因此也不会被任何一次批次进度覆盖；但带着它上线，等于所有身份都靠
一份配置文件里的明文令牌。**功能全绿 ≠ 可上线**，这条边界就是差别所在。

| 能力 ID | 原状态 | 结果 | 关键设计约束 |
|---|---|---|---|
| `policy.oidc-auth`（新增） | — | ✅ 已实现 | `docs/09 §9.7`（认证方式可换、授权判定唯一入口）；`docs/23`（新增依赖必须过扫描） |

落地内容：

1. **JWT/JWKS 认证**（`dg.auth.mode=jwt`）：公钥来源支持 **JWKS URL / 文件 / 内联 JSON**
   三选一（后两者给内网/air-gapped 与测试）；按 `kid` 选键、随缓存寿命轮换；
   算法白名单**只允许非对称**；校验 `iss` / `aud` / `exp` / `nbf`（时钟偏移可配）；
   角色声明名与"外部组名 → 平台角色"映射可配，最终仍走 `Roles.normalize`。
2. **静态令牌保留为开发形态**，两条认证路径产出**同一种主体**（`Principal`），
   因此授权层（`AccessPolicy`）一行未改。
3. **启动 fail-closed**（`AuthStartupValidator`）：生产环境拒绝 `disabled`、拒绝内置开发令牌、
   拒绝过短令牌、拒绝无 `id` 的令牌、拒绝 JWT 缺签发方/受众/公钥来源。
   危险配置**让控制面起不来**，而不是运行时降级。
4. **生产 profile**（`application-prod.yml`）：关闭 `/docs` 与 `/api-docs`、actuator 只留 `health`
   且不暴露详情、错误响应不带内部信息、默认开启 HSTS。
5. **安全响应头与接口面收缩**：CSP（`script-src 'self'`）、`X-Frame-Options: DENY`、
   `Referrer-Policy: no-referrer`；`/actuator/**` 除 `health` 外一律需要认证。
6. **部署物对齐**：`.env.example` 与 `docker-compose.yml` 原先写的是 Python 侧的
   `postgresql+psycopg2://` DSN —— 对 Java 是非法 JDBC URL，按文档部署会直接起不来。
   已改为 JDBC URL + 分开的用户名/密码，并把 JWT 相关变量补齐。

验证（可复现）：

```
python tools/auth_jwt_verify.py        → 29/29（含 8 类攻击载荷与 2 项生产 fail-closed）
mvn -B test                            → 230 项全绿（本批 +55：认证 52 + SSE 守卫 3）
python tools/java_e2e_verify.py        → 153/153
python tools/ui_render_check.py        → 11/11（CSP 开启后界面仍能渲染）
python -m pytest -q                    → 250 项全绿
python tools/dependency_audit.py       → 退出码 0（阻断 0 / 已豁免 2，见 docs/23 §7.8）
```

本批的缺陷密度不高，但四条都值得记住（详见 `docs/21 §22.5`）：

1. **Nimbus 的 `JWSAlgorithm.parse` 接受任意算法名**（会构造自定义实例而不报错）——
   于是 `RS999` 会安静地进入白名单。算法白名单必须按**固定集合**判断，不能交给库"解析成功"。
2. **"设 0 关闭"从未生效**：`@Scheduled(fixedDelayString=0)` 会在启动时抛
   `IllegalArgumentException`，类注释里写的关闭方式只会把控制面弄挂。改为按配置显式调度。
3. **e2e 有一条检查一直在测过期约定**：它匹配 `/assets/index-*.js`，而构建产物早已是
   `/static/...`（Vite `assetsDir` 为避开 `/assets` 路由而改）。检查本身比被测对象更容易过期。
4. **HSTS 配了却永远不发送**：Spring 默认只在"请求本身是 HTTPS"时发这个头，而生产通常是
   TLS 终结代理之后（到本进程是 HTTP）——配置看起来全对，头却从不出现。
   改为显式对所有请求发送（浏览器按 RFC 6797 忽略经 HTTP 收到的 HSTS，无副作用）。

> 这一批没有新增业务能力，却把一个**长期存在的"可上线"缺口**关掉了：
> 认证从"配置文件里的明文令牌"变成"企业 IdP 签发的短期 JWT"，
> 并且用 29 项端到端检查证明常见攻击载荷（`alg=none`、算法混淆、篡改、受众重放、
> 未知 kid、过期/未生效）在本平台全部失败。

---

### 2.10 Batch 10（已完成）：客户端 SDK 与命令行 dgctl

前九批解决"平台自己能做什么"，这一批解决"别人怎么用它"：CI 要脚本化、运维要一条诊断命令、
外部系统要编程调用。`dg-sdk` 模块此前是一个**只有能力声明的空壳**，而且没有装配进 `dg-api`，
所以它的三项能力连 `/api/v1/capabilities` 都不出现 —— 这种"声称有、其实没接"的状态比标注
"未实现"更难发现，因此本批先把模块接上，再决定哪几项能算实现。

| 能力 ID | 原状态 | 结果 | 关键设计约束 |
|---|---|---|---|
| `sdk.clients` | 未实现 | ✅ **Java** 客户端（名称由「多语言」改为「Java」，见下） | `docs/09 §9.10`（SDK 是访问方式，不是第二个平台） |
| `sdk.cli` | 未实现 | ✅ `dgctl`（与控制面同一产物） | `docs/09 §9.10`（与 HTTP API 一致；退出码稳定） |
| `sdk.embedded` | 未实现 | ❌ 仍未做 | `docs/14 §5`（嵌入卡必须独立只读端点，不能复用管理接口） |

**一个必须显式做的改名**：`sdk.clients` 原名「多语言客户端 SDK」。本轮只交付 Java：
TypeScript 的类型由 `core.codegen` 覆盖但没有运行时客户端，Python 侧是参考实现自带调用。
把名字改成「Java 客户端 SDK」而不是留着原名 + 在 notes 里解释 —— 因为**名称比 notes 更容易被引用**。

四条设计决定：

1. **CLI 与控制面同一个产物**（`java -jar dg-api.jar dgctl ...`），且**不启动 Spring 容器**：
   不连库、不建 Web 服务、不跑迁移。一条诊断命令不该因为数据库连不上而失败 ——
   那正是它要诊断的场景。
2. **CLI 与 SDK 共用同一传输层**：CLI 能做的 SDK 就能做，不会漂移；反过来也防止
   "CLI 里偷偷绕过了鉴权/超时"。
3. **不做隐式重试**：写操作（采集、审批、策略下发）不幂等，客户端擅自重试会造出重复记录。
   SDK 只暴露 `isRetryable()`，由调用方决定。
4. **未稳定的契约不固化类型**：能力清单/资产列表/检索/血缘图有 DTO，
   资产详情与影响分析返回原始 JSON。过早固化会制造"看起来有类型其实会漏字段"的假 API。

验证：

```
python tools/sdk_cli_verify.py   → 11/11
   doctor / capabilities（含 --json 合法 JSON）/ search / asset / lineage / impact /
   错误令牌→1 / 未知命令→2 / 服务不可达→3 / help
mvn -B test                      → 240 项全绿（本批 +10：SDK 传输层）
python tools/java_e2e_verify.py  → 153/153
python tools/ui_render_check.py  → 11/11
python -m pytest -q              → 250 项全绿
```

本批修掉的缺陷（都是"自己写的桩/自己写的输出"带来的）：

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 测试桩用 `ConcurrentHashMap` 存无查询串时的 `getQuery()`（返回 null） | 桩线程 NPE → 客户端只看到"连接被关闭"，看起来像被测代码坏了 | 用 `String.valueOf` 归一；并在桩里打印服务端异常 |
| 2 | CLI 中文输出跟随平台代码页（Windows 上是 GBK） | 同一命令在控制台/CI/脚本捕获三种场景编码不同，断言中文的脚本随机失败 | CLI 显式把 stdout/stderr 固定为 UTF-8，并提示 `chcp 65001` / `--json` |

计数变化：`dg-sdk` 首次装配 → 能力总数 **45 → 48**（已实现 39 / 部分 7 / 未实现 2）。

---

### 2.11 Batch 11（进行中）：血缘可视化剩余缺口

`lineage.visualization` 一直标"部分实现"，剩余缺口有四项。本批先做其中**最能直接影响决策**的一项：
"这张图里有多少条边没人确认过"。原因很直接：血缘的主要风险不是画不出来，而是**画出来了但没人核实**，
而推断出来的边会越用越像真的。

| 交付 | 状态 | 说明 |
|---|---|---|
| 未确认血缘队列 `GET /lineage/review` | ✅ | 「显式待复核」与「低置信度且从未人工确认」**分开计数**（原因可区分），附最老一条等待天数 |
| 血缘健康度 `GET /lineage/health` | ✅ | 结构 / 来源 / 解析粒度三个口径分开给，**不合成健康分** |
| 界面「审查与健康」页签 | ✅ | 四个指标卡 + 队列表格；`ui_render_check` 12/12 |
| 时间轴回放 | ❌ | 需要边的 `valid_from/valid_to` 版本化（当前是 first_seen/last_seen + state） |
| 超限聚合视图 | ❌ | 当前超限时显式提示并建议收窄条件 |
| 列级端到端路径追踪 `GET /lineage/path` + 界面入口 | ✅ | 只走 ACTIVE 边；深度硬上限；「没路径」与「没找够」分开回答；链路可信度取最弱一环 |

修复的真实缺陷：`(? || ' days')::interval` 在 PostgreSQL 报"无法确定参数类型"（改 `make_interval`）；
`lineage_check_finding` 的时间列是 `occurred_at` 而不是 `created_at`（接口 500，靠真实调用发现）。



---

### 2.12 Batch 12：嵌入式资产卡（sdk.embedded）

`docs/14 §5` 要求"把治理信息放进用户已有的工作现场"。落地时真正的难点是凭据：
iframe URL 会进浏览器历史与代理日志，长期 Bearer 不能放进去；复用管理接口又会让"嵌入"
变成绕过权限的入口。因此拆成"签发 + 只读投影"两个端点，令牌短期、单资产、只读。

| 能力 ID | 原状态 | 结果 | 关键约束 |
|---|---|---|---|
| `sdk.embedded` | 未实现 | ✅ 已实现 | 独立只读端点；签名令牌绑定 URN；字段白名单；未配置密钥 502 不降级 |

验证：`tools/embed_verify.py` **9/9**、dg-api **66** 项单测（本批 +7）、`pnpm build` 通过。

---
### 2.13 Batch 13：语义检索的向量路（ai.semantic-search）

混合检索此前只有两路（词法 + 术语扩展），`vector` 路显式写着"未实现"。本批把它打通，
同时守住一条底线：**没有配模型就不宣称语义检索**。

| 能力 ID | 原状态 | 结果 | 关键约束 |
|---|---|---|---|
| `ai.semantic-search` | 部分 | ✅ 已实现（机制） | 三路召回 + RRF；embedding 可插拔（none/hash/openai）；授权前置过滤；无 pgvector 时为有上限扫描并显式回报 truncated |

三个诚实边界（写进能力 notes 与类注释）：`hash` provider **不是语义模型**（只用于打通链路与回归）；
真实语义质量取决于所配置的模型；ANN 索引（HNSW/IVFFlat）未接入，装了 pgvector 可平滑替换。

验证：`tools/vector_search_verify.py` **10/10**（含"未配置时不降级"与 502 语义）；dg-ai 新增 7 项单测。

---
## 3. 已知的、本计划不打算解决的边界（诚实前置）

- `docs/09 §9.3` 的**中文词典分词**不在本轮范围：用 bigram 替代（Batch 5 已实现写入/查询同构切分），
  但它不是分词 —— 召回率高、精确率低。要真分词需 `pg_jieba/zhparser` 或 OpenSearch+IK。
- **向量检索**（embedding + kNN）未实现（Batch 5 只做了词法 + 术语扩展两路）；
  因此平台**不宣称语义相似度**，`ai.semantic-search` 保持"部分实现"。
- **行/列级数据访问**（策略真正落到引擎）属 Batch 4，且 `docs/20 §8` 已明确
  执行层应复用 Trino `SystemAccessControl` / Ranger / Lake Formation，不自建。
- ~~生产认证仍须从静态令牌迁到 OIDC/JWKS~~ —— **Batch 9 已完成**（§2.9）。
  剩余的相关边界（不影响上线，但不该被当成"已解决"）：
  **多 IdP 同时接入**（当前一个部署只校验一个 `issuer`）、**令牌吊销/内省**
  （当前依赖 IdP 的短有效期），以及 MFA —— 后者属 IdP 职责，平台不重复实现。
