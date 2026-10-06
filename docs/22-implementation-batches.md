# 22 · 分批实施计划（把「未实现」逐批变成「已实现」）

> 状态：**Batch 1–5 全部完成**（见 `docs/21 §12`–`§17`），**Batch 6（审计闭环 + CI 门禁）、Batch 7（dbt 连接器）、Batch 8（血缘 L2 校验层）完成**（`docs/21 §19`–`§21`），
> 另有 **NFR-SEC-01 供应链安全要求**落地（`docs/23`、`docs/21 §18`）。
> 依据：`docs/11` 路线图（Phase 0→4）+ `docs/21 §7` 下一步 + `docs/21 §11.7` 本轮未做
> 纪律：**只有真做完并跑过可复现验证的能力，才允许把 `/api/v1/capabilities` 里的状态从
> `NOT_IMPLEMENTED` / `PARTIAL` 改成 `IMPLEMENTED`。** 状态虚标比不实现更糟。
> 另：批次完成定义新增第 6 条「供应链安全扫描通过」（NFR-SEC-01）。

进度：**已实现 36 / 部分 7 / 未实现 1 / 合计 44**（Batch 1 前为 14 / 2 / 21 / 37；另有并行工作流新增 2 项能力，见 `docs/21 §21.6`）。
仅剩的未实现项是 `core.index-opensearch`（检索后端替换，触发条件未到）；
7 项部分实现的缺口与后续批次计划见 `docs/21 §19.6` 与 `§20.5`。

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

## 3. 已知的、本计划不打算解决的边界（诚实前置）

- `docs/09 §9.3` 的**中文词典分词**不在本轮范围：用 bigram 替代（Batch 5 已实现写入/查询同构切分），
  但它不是分词 —— 召回率高、精确率低。要真分词需 `pg_jieba/zhparser` 或 OpenSearch+IK。
- **向量检索**（embedding + kNN）未实现（Batch 5 只做了词法 + 术语扩展两路）；
  因此平台**不宣称语义相似度**，`ai.semantic-search` 保持"部分实现"。
- **行/列级数据访问**（策略真正落到引擎）属 Batch 4，且 `docs/20 §8` 已明确
  执行层应复用 Trino `SystemAccessControl` / Ranger / Lake Formation，不自建。
- 生产认证仍须从静态令牌迁到 OIDC/JWKS。
