# 21 · 实现日志与决策记录（Phase 0）

> 状态：**进行中**。本文件记录"设计 → 实现"过程中的决策、已验证的主张与已知限制。
> 设计文档（`00`–`20`）仍然有效；本文只记录实现期的偏离与实证。

---

## 1. 当前实现状态

> **注意**：本表记录的是 **Python 参考实现**（`src/dg`）在转为 Java 控制面之前的阶段状态，
> 保留作为历史与对照。**当前线上形态是 Java 控制面 + React 界面**，
> 其能力状态以 `GET /api/v1/capabilities` 为单一事实源，逐批记录见 §11–§17。
> （第 42 项能力的当前分布：已实现 32 / 部分 7 / 未实现 3。）

| 能力 | 状态 | 证据 |
|---|---|---|
| Model Registry（YAML → 校验 + 兼容性检查） | ✅ | 11 实体 / 8 aspect / 8 关系；`dgctl model` |
| **代码生成**（YAML → Python / TypeScript / JSON Schema） | ✅ | 3 个目标；`dgctl codegen`，`--check` 供 CI 阻断漂移 |
| PostgreSQL 真相源 schema（8 张表） | ✅ | `sql/001_init.sql`、`dgctl doctor` |
| 实体 / Aspect 读写 + 版本 + 审计（哈希链） | ✅ | 85 项测试 |
| 乐观锁与幂等写（内容指纹） | ✅ | 第二次采集全部 noop、零新事件 |
| 字段级来源保护（**采集不覆盖人工内容**） | ✅ 端到端验证 | `tools/e2e_verify.py` 第 3 项 |
| 采集批次整体回滚 | ✅ | 含"只影响本次 run"的隔离测试 |
| 事件流（outbox）+ 可重放派生视图 | ✅ 端到端验证 | 索引重建 31 事件 → 16 文档，lag=0 |
| 血缘边（来源/置信度/时效/控制依赖）与环安全遍历 | ✅ | 环安全（UNION 去环）、可见性裁剪 |
| 采集框架 + **3 个连接器**（PostgreSQL、SQLite、DuckDB） | ✅ | 多源共存测试；DuckDB 还支持裸文件 schema 推断 |
| **采集护栏**（删除检测 + 实体数骤降中止 + 命名空间隔离） | ✅ 端到端验证 | 25 表删到 5 表 → BLOCKED，实体全部存活（`test_guard.py`） |
| **采集运行记录与健康度** | ✅ | `collect_run` / `collector_state`；`dgctl runs --health`、`/api/v1/collect/health` |
| **采集失败告警**（分级/去重/冷却/恢复/多通道） | ✅ 端到端验证 | 连续失败 → CRITICAL → 真实 webhook 投递；重复巡检不重复打扰 |
| **内置调度器**（APScheduler + PG advisory lock 互斥） | ✅ | 显式设置 max_instances/coalesce/misfire；多副本互斥测试 |
| **列级血缘解析**（sqlglot + 语料回归集 + 异常样本库） | ✅ 端到端验证 | 4 条列级边（MASKED/AGGREGATED）；`dgctl lineage parse` |
| **OIDC/JWKS 认证**（RS256 + 算法混淆防护） | ✅ | 21 项测试，含 `alg:none` 与 HS256 混淆攻击被拒 |
| REST API（健康、身份、模型、搜索、资产、血缘、审计、采集、调度、解析） | ✅ | `/api/v1/*` |
| 最小可用 UI（搜索页 + 资产详情页 + 令牌 + 身份显示） | ✅（原生 JS，待替换为 React） | `src/dg/api/static/index.html` |
| CLI（`dgctl`） | ✅ | doctor / init / model / codegen / collect / **runs** / **schedule** / **lineage** / index / serve |
| **部署骨架**（Dockerfile + docker-compose + .env.example） | ✅ 已实跑验证 | 见 §9 |
| 质量 / 契约 / 策略下发 | ⛔ 未开始 | 属 Phase 1–3 |

**测试**：212 项全部通过。**端到端**：`tools/e2e_verify.py` 9/9；`tools/demo_end_to_end.py` 全链路断言通过。

---

## 2. ADR-013：控制面语言选择（**偏离设计方案，需知悉**）

- **背景**：设计文档（`10` §1、`12` ADR-006）默认控制面为 **Java 21 + Spring Boot**。
- **实现时的现实**：本机已具备完整 Python 栈（FastAPI / SQLAlchemy / psycopg2 / pydantic / pytest / sqlglot），**零依赖准备即可运行与验证**；血缘解析与 AI 侧车按设计本就在 Python（`10` §2），同栈可消除跨语言 RPC。
- **决策**：Phase 0 控制面用 **Python 3.12 + FastAPI + SQLAlchemy**。
- **语言无关的资产已落地**：模型 YAML、SQL schema、HTTP/JSON 契约、生成的多语言类型、语义规则及其测试。若确定 Java 栈，**替换的是实现而非设计**。
- **代价 / 风险**：与设计文档不一致；Python 高并发写吞吐弱于 JVM（Phase 0 采集为批处理，不构成瓶颈）。
- **复审触发**：团队统一技术栈；或写入吞吐成为瓶颈。

## 3. ADR-014：部署形态（实现期细化）

- **Dev 形态**：`docker compose up -d` —— PostgreSQL + API 两个容器，首次启动自动 `dgctl init`。
- **本机无 Docker 时的形态**：直接指向已有 PostgreSQL（`DG_DATABASE_URL`），`dgctl serve` 即可；这也是当前开发机的实际形态。
- **生产形态**：按 `07` §4「标准版」——API 无状态多副本 + Worker 分离 + PG 主从 + OpenSearch；本仓库暂未提供 Helm chart（属 Phase 1）。
- **取舍说明**：单机 compose **不含 OpenSearch/Kafka**。派生视图当前用 PostgreSQL（`search_doc` + `tsvector`），这正好落在 `10` §1 预定的"轻量模式（PG 起步）"降级路径上，**架构无需改动即可替换为 OpenSearch**（消费者接口不变）。

---

## 4. 已验证的架构主张（跑出来的，不是声称的）

| 主张 | 出处 | 验证方式 | 结果 |
|---|---|---|---|
| 单一真相源 + 事件流 + **派生视图可丢弃重建** | ADR-002 | 丢弃 `search_doc` 后从 0 重放 | 31 事件 → 16 文档，内容逐字段一致 |
| **采集绝不覆盖人工内容**（字段级） | ADR-005 | 先写 MANUAL、再以 AUTO_COLLECTED 写不同内容 | 人工内容保留，`protectedFields=['text']` |
| **采集批次可整体回滚**，且不影响其他批次 | ADR-005 | 同一 run_id 建实体/改 aspect 后回滚 | 新建实体进墓碑、新建 aspect 删除、更新 aspect 回退；**其他源的资产不受影响** |
| **内容指纹增量** | `09` §9.1 | 连续采集两次 | 第二次 0 写入、0 新事件 |
| **多源共存** | `09` §9.1 | SQLite + PostgreSQL 同时采集 | 两个 Platform、URN 前缀隔离、无冲突 |
| **授权判定唯一**（搜索/详情/血缘同源） | `09` §9.3 | L4 资产对 READER：搜索不出现 + 详情 404 + 血缘节点被裁剪 | 三者一致 |
| 血缘遍历环安全、深度有界、排除控制依赖 | `09` §9.2 | 构造环 + CONTROL 边 | 无重复节点、不死循环、控制依赖不入值级血缘 |
| **模型是单一事实源** | `08` §6 | 生成物与模型比对（CI 模式） | 不一致即失败；生成物可 import/编译 |

---

## 5. 授权模型说明（v1 落地范围）

按 `09` §9.7 的三层模型，v1 落地前两层：

| 层 | 状态 | 说明 |
|---|---|---|
| 平台功能权限（RBAC） | ✅ | 4 角色：`ADMIN` / `STEWARD` / `EDITOR` / `READER`；权限点：`asset:read|write`、`lineage:read|write`、`model:read`、`governance:write`、`index:consume|rebuild`、`collect:run` |
| 资产可见性（ABAC） | ✅ | 分级 L1–L4；READER/EDITOR 最高可见 L3，STEWARD/ADMIN 可见 L4；未分级按 L2 处理 |
| 数据行/列访问 | ⛔ | 属 Phase 3 的策略下发（ADR-008），**未实现** |

**认证方式**：`DG_AUTH_MODE=static`（静态令牌，开发）/ `jwt`（校验 Bearer JWT，生产对接 OIDC）。
内置开发令牌：`dev-admin-token` / `dev-steward-token` / `dev-reader-token` —— **生产必须替换**。

安全细节：不可见资产的详情返回 **404 而非 403**（不确认存在性，避免资产枚举）；修改分级需要 `governance:write`（防止把自己的 L4 资产降级）；血缘遍历对节点做可见性裁剪（不能借血缘泄露无权资产名）。

---

## 6. 已知限制（诚实列出，避免误判为"已完成"）

1. **OIDC 已验证但未对接真实 IdP**：用本地 JWKS 服务 + 真实 RSA 密钥完成了端到端测试（含算法混淆防护），但未在真实 Keycloak/Entra ID 上端到端跑过。
2. **中文全文检索不可用**：检索用 PG `simple` 配置，不做中文分词；已固化为测试（`test_chinese_search_is_a_known_limitation_here`），补 `pg_jieba/zhparser` 或迁到 OpenSearch+IK 时该测试会失败并提醒更新。**标识符切分（`_`/驼峰）已实现**，按表名列名检索可用。
3. **列级血缘只做了 L1（sqlglot）**：L2 校验层（Calcite）未做，因此 `SELECT *`（缺 schema 时）、JOIN 列歧义、隐式类型转换这几类问题无法通过"二次校验"发现，只能靠运行时上报兜底（见 `research/04` §2.1）。
4. **血缘的对象解析有覆盖边界**：SQL 里的表名必须能解析到平台 URN 才写边；**同名表存在歧义时拒绝猜测**（宁可缺边）。跨 schema 同名表较多的环境需要更明确的命名约定或显式 `ns.platform.db.schema.table` 全路径。
5. **连接器 3 个**：PostgreSQL、SQLite、DuckDB（含 Parquet/CSV 裸文件 schema 推断）。Hive/HMS、Trino、dbt、BI 类（Tableau/Superset 等）仍在白名单内未实现。
6. **数据行/列访问未做**：属 Phase 3 的策略下发（ADR-008），当前授权只到"API 与资产可见性"这一层。
7. **UI 仍是原生 JS 最小实现**：未按 `14` 的六入口信息架构做；无待办中心、血缘画布、建议收件箱。
8. **解析异常样本库尚无消费界面**：样本已落库并提供 `/api/v1/lineage/quality`，但没有"把失败样本转成语料用例"的工作流。
9. **告警只有采集类规则**：目前是 `collect.*` 四条（连续失败 / 护栏拦截 / 陈旧 / 调度从未成功）。质量、契约、血缘的告警尚未纳入同一状态机。
10. **告警确认（ack）不阻断状态机**：确认只是留痕，条件恢复时仍会自动 RESOLVED（这是有意的：确认≠已修复）。

---

## 7. 下一步（按依赖与风险排序）

前四项与告警均已完成（见 §10），这里是更新后的清单：

1. **把质量/契约/血缘告警并入同一状态机** —— 告警框架已就绪，缺的是更多规则；
2. **血缘 L2 校验层**（Calcite）—— 补齐 `SELECT *` 展开、JOIN 列歧义、隐式类型转换的二次校验（需 JVM 侧服务）；
3. **更多连接器**（Hive/HMS、Trino、dbt、BI 类）—— 按 `11` §1.1 白名单顺序；
4. **前端工程化**（Vite + React + TS，消费已生成的 `web/generated/model_gen.ts`），落地 `14` 的六入口信息架构、待办中心与**告警看板**；
5. **解析样本 → 语料用例的工作流**（把 `/api/v1/lineage/quality` 里的失败样本转成 `tests/corpus` 用例）；
6. **质量与契约**（Phase 1：profiling、规则 IR、ODCS 契约与 CI 门禁）；
7. **真实 IdP 联调**（Keycloak/Entra ID 上的端到端验证）。

---

## 8. 如何运行（本机，无 Docker）

```powershell
$env:PYTHONPATH='src'
$env:DG_DATABASE_URL='postgresql+psycopg2://postgres:root@localhost:25011/dg'

python -m dg.cli doctor                     # 健康 + schema + 模型自检
python -m dg.cli init                       # 初始化 schema（幂等）
python -m dg.cli codegen                    # 由模型生成 Python/TS/JSON Schema
python -m dg.cli codegen --check            # CI：校验生成物是否最新

# 采集（三个源示例）
python -m dg.cli collect postgres --dsn "postgresql://postgres:root@localhost:25011/dg" --schemas public
python -m dg.cli collect sqlite   --dsn "C:\path\to\analytics.db" --namespace demo
python -m dg.cli collect duckdb   --dsn "C:\path\to\lake.duckdb" --namespace lake --files "C:\path\to\events.parquet"

# 采集护栏与健康度
python -m dg.cli collect sqlite --dsn ... --max-delete-ratio 0.2   # 收紧阈值
python -m dg.cli collect sqlite --dsn ... --accept-deletions       # 人工确认后接受删除
python -m dg.cli runs --health                                     # 采集健康度总览

# 调度（治理即代码）
python -m dg.cli schedule apply -f config/schedules.example.yaml
python -m dg.cli schedule list
python -m dg.cli schedule run analytics-sqlite
python -m dg.cli serve --with-scheduler                            # 常驻调度

# 列级血缘
python -m dg.cli lineage parse -f etl.sql --dialect hive --namespace prod
python -m dg.cli lineage quality                                   # 解析质量与失败样本

python -m dg.cli index rebuild              # 从事件流重建派生索引
python -m dg.cli serve --port 8080
#   UI:  http://127.0.0.1:8080/      （页面右上角填令牌）
#   API: http://127.0.0.1:8080/docs

python -m pytest tests -q                   # 212 项测试（独立 dg_test 库）
python tools/e2e_verify.py                  # 9 项端到端验证（需服务运行中）
python tools/demo_end_to_end.py             # 三连接器 + 血缘 + 索引的全链路演示
python tools/lineage_demo.py                # 列级血缘解析演示（含多方言与降级）
```

### 用 Docker（Dev 形态）

```bash
cp .env.example .env      # 按需修改
docker compose up -d      # PostgreSQL + API；首启自动建表
# UI: http://127.0.0.1:8080/
```

### 用令牌调用 API

```bash
curl -H "Authorization: Bearer dev-admin-token" http://127.0.0.1:8080/api/v1/me
curl -H "Authorization: Bearer dev-reader-token" "http://127.0.0.1:8080/api/v1/search?q=order"
```

---

## 9. 容器化部署验证记录（ADR-014 的实证）

在 Docker 29.1.3 上实跑，完整步骤如下（全部通过）：

| 步骤 | 命令 | 结果 |
|---|---|---|
| 构建镜像 | `docker compose build` | ✅ `dg-platform:0.1.0` 构建成功（含非 root 用户、健康检查） |
| 启动整套 | `docker compose up -d` | ✅ `dg-postgres` healthy → `dg-api` started，**5 秒后 `/healthz` 返回 200** |
| 认证（容器内） | 无令牌请求 | ✅ HTTP 401 |
| 身份（容器内） | `GET /api/v1/me`（admin 令牌） | ✅ `admin@local` / ADMIN / 可见 L4 |
| **容器内自举采集** | `docker compose exec api python -m dg.cli collect postgres --dsn postgresql://dg:***@postgres:5432/dg --schemas public` | ✅ 8 表 / 83 列 / 84 ms / 0 错误 |
| 派生索引重建 | `docker compose exec api python -m dg.cli index rebuild` | ✅ 18 事件 → 10 文档，lag=0 |
| 搜索 | `GET /api/v1/search?q=event` | ✅ 命中 `urn:dg:Dataset:prod.postgresql.dg.public.event_log` |
| 端到端脚本 | `python tools/e2e_verify.py http://127.0.0.1:8081` | ✅ **9/9 通过**（含 ADR-002/ADR-005 与授权一致性） |
| 清理 | `docker compose down -v` | ✅ 容器、网络、数据卷全部移除 |

**结论**：`Dockerfile` 与 `docker-compose.yml` 不是"写了没跑"的骨架，而是**已验证可用的部署形态**。
端口通过环境变量覆盖（本次用 8081/25433，避免与开发实例冲突），这本身也验证了 compose 的参数化设计。

---

## 10. 第二轮实现记录（护栏 / 调度 / 血缘 / OIDC / 第三个连接器）

### 10.1 采集护栏：补上"删除检测"这个真实缺口

此前实现**根本没有删除检测** —— 源端删表后目录不会反映，等于"只增不减的假目录"。本轮补齐：

| 机制 | 实现 |
|---|---|
| 状态快照 | `collector_state`，按 `(source, namespace, scope)` 隔离 → **命名空间隔离防误删** |
| 删除检测 | 上轮快照 ∖ 本轮结果 = 待删集合 |
| 四条护栏 | 最大删除数（200）、最大删除比例（30%）、实体数保留率（70%）、首次采集不判删除 |
| 拦截语义 | `status=BLOCKED` + **不执行任何删除** + **不更新基线**（关键：否则下次无法再检出） |
| 放行语义 | 软删（`lifecycle=DELETED_AT_SOURCE`），**aspect 与版本历史保留** → 可复活 |
| 人工确认 | `--accept-deletions`（确认后接受删除并更新基线） |

**实测**：25 张表删到 5 张 → `BLOCKED`，原因写明"删除比例 80.0% 超过阈值 30%；实体数骤降：保留率 20.0% 低于阈值 70%"，**25 个实体全部存活**，CLI 退出码 3（区别于普通失败 1）。

顺带修掉一个**隐蔽崩溃**：`ensure_entity` 原先用 `ON CONFLICT ... DO UPDATE ... WHERE deleted_at IS NULL`，墓碑实体再次被采集时会走到"既不插入也不更新"的分支并导致查询无行。现在墓碑实体会**自动复活**并发出 `ENTITY_RESURRECTED` 事件（消费者据此重建索引文档）。

### 10.2 内置调度器与采集健康度

- **调度定义可 Git 管理**：`config/schedules.example.yaml` → `dgctl schedule apply -f`，落 `collect_schedule` 表；
- **凭证不落明文**：DSN 支持 `env:VAR_NAME`（生产应改 Vault/KMS 引用）；输出时口令自动遮蔽；
- **分布式互斥用 PG advisory lock**，不用 Redlock（`09` §9.1：Redlock 缺 fencing token，GC 停顿下不保证互斥）；
- **显式设置 APScheduler 三个参数**（`max_instances=1`、`coalesce=True`、`misfire_grace_time=300`），不依赖库默认值；
- **健康度**：`dgctl runs --health` 与 `/api/v1/collect/health`（连续失败次数、最后成功时间、陈旧小时数、总体 HEALTHY/DEGRADED/UNHEALTHY）；
- 非法 cron 的调度**只跳过该条**，不让整个调度器起不来。

### 10.3 列级血缘解析（sqlglot）

三层流水线的 L0 + L1 已实现，L2（Calcite 校验）未做：

| 能力 | 实测结果 |
|---|---|
| 别名 → 表名还原 | `ods.orders o` → `ods.orders`（不给 schema 时 sqlglot 的 `source_name` 为空，必须自建别名映射） |
| 转换类型识别 | `DIRECT` / `AGGREGATED`(SUM) / **`MASKED`(md5)** / `INDIRECT`(计算) / `FILTER` |
| **控制依赖分离** | 窗口函数 `PARTITION BY`/`ORDER BY` 标记为 `CONTROL`，不参与值级血缘；且**同名列不会被误判**（按输出列作用域判定） |
| 基数标注 | `LATERAL VIEW explode` → `ONE_TO_MANY` |
| 多方言 | hive / spark / trino / postgres / bigquery / doris…（未注册方言显式报错并列出支持列表） |
| 显式降级 | `SELECT *` → `table_level_only` + 原因；语法错误 → `failed` + 错误详情 |
| **语料回归集** | `tests/corpus/lineage_corpus.yaml`（14 个用例，含 `must_not_have` 回归断言） |
| **解析异常样本库** | `lineage_parse_sample`，按内容哈希聚合计数 → 方言覆盖率成为可运营指标 |
| OpenLineage 互操作 | `map_openlineage_column_lineage` 做**方向反转**（OL 是"输出列→输入列"） |

**名字解析不猜**：SQL 里的表名必须唯一解析到平台 URN 才写边；同名表有歧义时返回 None 并计入 `unresolvedTables`（实测遇到过一次：同一命名空间下两张 `dim_customer` 来自不同库 → 拒绝解析）。

### 10.4 OIDC / JWKS 对接

- 支持 `DG_JWT_JWKS_URL`（OIDC）+ PyJWKClient 缓存与轮换；保留共享密钥模式用于内网；
- **JWKS 模式默认只允许非对称算法**（RS*/ES*），并显式拒绝 `alg:none`；
- **21 项测试**用本地 JWKS 服务 + 真实 RSA 密钥完成，包含：
  - 算法混淆攻击（**用公钥 PEM 当 HMAC 密钥**伪造 ADMIN 令牌）被拒绝；
  - `alg:none` 无签名令牌被拒绝；
  - 错误签名 / 未知 kid / 过期 / audience 与 issuer 不匹配 → 401；
  - **JWKS 不可用时失败关闭**（拒绝而非放行，也不是 500）。

### 10.5 第三个连接器：DuckDB（含裸文件 schema 推断）

除了库内表/视图/schema/主键，还支持用 DuckDB **推断 Parquet / CSV / JSON 文件的 schema** ——
数据湖里大量资产没有 catalog，这条路径把它们纳入治理范围（`DESCRIBE` 不读数据，对超大文件也安全）。

顺带修掉一个真实缺陷：**URN 路径段不允许含点**，而文件名天然含点（`orders_2026.parquet`）。
现在 URN 段统一规范化（点/冒号/空格 → 下划线），**展示名保留原名**，兼顾"URN 稳定合法"与"展示可读"。

### 10.6 本轮发现并修正的真实缺陷（诚实记录）

| # | 缺陷 | 影响 | 修正 |
|---|---|---|---|
| 1 | 采集**没有删除检测** | 目录"只增不减"，与源端脱节 | `collector_state` + 护栏（§10.1） |
| 2 | `ensure_entity` 对墓碑实体**查询无行崩溃** | 复活路径不可用 | 改为显式复活语义 + 事件（§10.1） |
| 3 | `rollback_run` 漏了"该批次新建的 aspect 应删除"分支 | 错误采集无法完全回退 | 增加 dropped 分支（上一轮） |
| 4 | 窗口函数控制依赖被当成值依赖 | 影响分析误报 | 按输出列作用域判定（§10.3） |
| 5 | URN 路径段含点导致采集报错 | 裸文件无法采集 | 段名规范化 + 保留展示名（§10.5） |
| 6 | `CollectionScheduler` 硬编码生产 session | 不可测 | 改为依赖注入 `session_factory` |
| 7 | 测试库未自动应用新迁移 | 新表在测试中缺失 | conftest 改为执行 `sql/*.sql` 全部迁移 |
| 8 | conftest 辅助函数以 `test_` 开头被 pytest 误收集 | 测试噪音 | 重命名 |

### 10.7 采集失败告警（补齐"失败告警"这一缺口）

健康度看板必须**被主动查看**，而告警会**主动找到人** —— 目录悄悄停止更新恰恰是没人会主动去看的那种故障。本轮补齐：

| 能力 | 实现 |
|---|---|
| 四条规则 | 连续失败 ≥3（CRITICAL）、护栏拦截（WARNING）、陈旧 >48h（WARNING）、已启用调度从未成功（WARNING） |
| **去重** | `dedup_key = rule:subject:scope`；部分唯一索引保证同一问题只有一个活跃告警，反复失败只累计 `fire_count` |
| **冷却/提醒** | 仍在告警中的问题按 `remind_interval`（默认 12h）才再提醒一次 |
| **恢复** | 条件消失自动 `RESOLVED` 并发恢复通知 |
| **防抖动** | 刚恢复的问题在 `reopen_cooldown`（默认 300s）内不立刻重新告警 |
| 通道 | `webhook`（generic/slack/feishu/dingtalk/teams 五种 payload）+ **`log` 永远兜底** |
| 可排查 | `alert_dispatch_log` 记录每次投递结果（"告警到底发出去没有"） |
| 集成 | 调度器每 `DG_ALERT_CHECK_INTERVAL_SECONDS`（默认 900s）巡检；`dgctl alerts …`；`/api/v1/alerts*` |

**实测（`tools/alert_demo.py`，起本地 HTTP 接收端）**：
连续 3 次真实失败 → `CRITICAL` 告警 → **webhook 收到真实 POST（HTTP 200）**；
再失败 → **不重复投递**（`fire_count` 累计到 2）；修复源 → 自动 `RESOLVED` + 恢复通知；此后巡检静默。

顺带修掉一处**设计不一致**：`format` 存在 `alert_channel` 的独立列里，而 `channel_from_row` 只从 `config` 读 → 表里配的格式不生效（实测发现后修正为"列优先、config 可覆盖"）。

---

## 11. 按设计选型重建为 Java 控制面 + React 界面（框架与界面完整化）

### 11.1 决策

**用户决定：按 `docs/10 §1` 的选型把控制面重建为 Java 21 + Spring Boot 3**（覆盖 `ADR-013` 的 Python 选择）。
本轮目标是「**框架搭好、界面做好、未实现功能全部标注**」—— 不追求功能全覆盖，追求**结构与状态的诚实**。

### 11.2 交付

**Java 控制面（`backend/`，9 个 Maven 模块，模块划分对齐 `docs/10 §8`）**

| 模块 | 状态 | 说明 |
|---|---|---|
| `dg-model` | ✅ | 模型注册表：YAML 加载 / 校验 / **兼容性检查** |
| `dg-core` | ✅ | 元数据内核：真相源 / 版本 / 乐观锁 / 审计 / outbox 事件 / 血缘图遍历 / schema 迁移 |
| `dg-ingestion` | ✅（单连接器） | 采集框架 + 护栏 + PostgreSQL 连接器 + 健康度 |
| `dg-lineage` | ✅（侧车未部署） | OpenLineage 接收 + 血缘质量报告 + sqlglot 侧车客户端 |
| `dg-quality` | 骨架 | 质量与契约（6 项能力均标注未实现） |
| `dg-policy` | 骨架 | 访问治理与策略（5 项） |
| `dg-ai` | 骨架 | AI 原生能力（4 项） |
| `dg-sdk` | 骨架 | 客户端 SDK（3 项） |
| `dg-api` | ✅ | Spring Boot 应用：REST + 认证 + 能力清单 + 前端托管 |

**界面（`web/`，Vite + React + TypeScript）**

- 按 `docs/14 §2` 实现**六入口**：发现 / 资产 / 血缘 / 质量 / 治理 / 管理；
- 已实现的入口接真实 API（资产列表与详情、血缘查询、采集与健康度、模型、能力清单）；
- 未实现的入口渲染**占位卡**：标明设计出处、计划阶段、将提供什么、以及**具体缺口**；
- **界面不做专用后门**：所有数据都经同一套 REST + 调用者令牌（`docs/09 §9.3`）。

### 11.3 「未实现全部标注」的执行机制（本轮的核心设计）

状态**只有一处真相**，三个出口同源：

| 出口 | 载体 |
|---|---|
| 代码 | `@Unimplemented(doc, phase, summary)` 注解 + `CapabilityDescriptor` |
| 接口 | `GET /api/v1/capabilities`（按域分组，含 `status` 与 `notes`）；未实现接口返回 **501 + 设计出处** |
| 界面 | 状态徽标（已实现/部分实现/未实现）+ 占位卡（设计出处、阶段、缺口清单） |

**当前计数：14 已实现 / 2 部分实现 / 21 未实现（合计 37）**，由控制面实时计算，界面不维护自己的清单
（否则两者必然漂移）。

关键取舍：调用未实现接口返回 **501 而不是空数据** —— 空数据会让人误以为「目录里没有」，
而实际上是「检索还没做」（`docs/09 §9.2` 点名要避免的静默失败）。

### 11.4 验证（可复现）

```
python tools/java_e2e_verify.py     → 15/15 通过
  1. 控制面存活（标识 java-spring-boot）
  2a. 无令牌 → 401        2b. 令牌解析出身份与角色
  3. 模型注册表：11 实体 / 8 aspect
  4. 能力清单三态齐备（14 / 2 / 21 / 37）
  5. 真实采集 PostgreSQL：SUCCEEDED，seen=15 created=15 225ms
  6a. 资产列表  6b. 资产详情含 datasetSchema（7 列 + schemaHash）
  7. 血缘图查询  7b. 血缘质量报告（边 6 / 列级 4 / 侧车未部署）
  8a/8b/8c. 检索、调度、影响分析 → 均 501 + 设计说明
  9a. 界面由同一端口托管  9b. SPA 深链回退 index.html
```

另有构建级验证：`mvn package` 成功产出 34 MB 可执行 jar；`pnpm build` 成功（`tsc -b` 严格模式通过）。

### 11.5 Java 侧与 Python 参考实现的关系

| | Java 控制面（`backend/`） | Python 参考实现（`src/`） |
|---|---|---|
| 定位 | **按设计选型的主实现** | 行为基准与算法参考 |
| 已覆盖 | 模型注册表、真相源读写、来源保护、批次回滚、事件流、护栏、采集、OpenLineage、血缘遍历、认证（静态令牌） | 上述 + 搜索索引、调度、告警状态机、SQL 列级解析、SQLite/DuckDB 连接器、OIDC/JWKS、审计哈希链、代码生成 |
| 共享 | `model/**.yaml`（唯一事实源）、`sql/*.sql`（同一份 DDL） | 同 |

**说明**：两者共用同一份模型与 DDL，因此不会出现「两套 schema」。Python 侧的实现细节可作为
Java 侧对齐时的验收基准（例如告警的去重/冷却/恢复语义、血缘的语料回归集）。

### 11.6 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | `AuthProperties` 漏 `@ConfigurationProperties` | 应用启动即崩 | 补注解 |
| 2 | `dg-sdk` 缺 Spring 依赖 | 模块编译失败 | 补 `spring-boot-starter` |
| 3 | Spring Boot 3.1 无 `RestClient`（3.2 才引入） | 编译失败 | 改用 `RestTemplate` |
| 4 | `Map<?,?>.getOrDefault` 捕获类型 + varargs 传参错误 | 编译失败 | 引入 `str()` 辅助方法与显式数组构造 |
| 5 | `CollectionService` 的 created 判断/列数统计/快照序列化不严谨 | 统计口径错误 | 改为先查存在性、显式计数、直接序列化 |
| 6 | 静态资源全部要求认证 | 界面无法加载 | 改为 `/api/**` 需认证、其余放行（数据仍受保护） |

### 11.7 已知限制（本轮未做）

1. **界面未做浏览器视觉验证**：环境无浏览器自动化，验证止于「构建产物可加载 + SPA 深链回退 + 数据接口打通」；
2. **Java 侧测试当时未补齐**：本轮以 `mvn package` + 端到端脚本验证；单元测试沿用 Python 侧（212→238 项）。
   **已在 §12 补齐 Java 单测（62 项）**；
3. **静态令牌而非 OIDC**：Java 侧未做 JWKS/RS256 对齐（仍然如此，见 §12.5）；
4. **血缘 SQL 解析侧车未部署**：**已在 §12 部署**（`python -m dg.cli sidecar`）；
5. **前端单 chunk 1.1 MB**：AntD 全量引入未做代码分割（仍然如此，非阻塞）。

---

## 12. Batch 1：目录可用主线（检索 / 权限 / 调度 / 影响分析 / SQL 解析）

按 `docs/22` 的分批计划执行的第一批。目标是把「目录真的能用」所依赖的五项能力从
`NOT_IMPLEMENTED` 变成**有可复现验证的 `IMPLEMENTED`**，而不是把状态标签改一改。

### 12.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `core.search-index` | 未实现 | **已实现** | 事件流消费者（`consumer_offset` 记进度、幂等、从真相源读当前值）+ `search_doc` 派生视图 + `POST /api/v1/index/rebuild` 从 seq=0 重放重建 + 标识符切分 + 索引水位 |
| `policy.rbac` | 未实现 | **已实现** | 角色→权限点（ADMIN/STEWARD/EDITOR/READER），未知角色丢弃；接口按权限点强制，越权 403 并说明缺哪个点 |
| `policy.abac` | 未实现 | **已实现** | 分级可见性 L1–L4；`AccessPolicy` 为**唯一判定入口**，搜索/列表/详情/写入共用 |
| `ingestion.scheduler` | 未实现 | **已实现** | DB 调度定义 + cron（5/6 段自动归一化）+ `pg_try_advisory_lock` 互斥 + YAML apply + 运行留痕 + 立即执行 |
| `lineage.impact-analysis` | 未实现 | **已实现** | 可达闭包 + `score(v)=w(v)·0.7^depth(v)` + 可解释理由 + 深度截断显式标注 |
| `lineage.sql-parse` | 部分实现 | **部分实现（缺口换了一批）** | 侧车**真的部署并真的被调用**：解析→URN 解析→写表级/列级边→失败样本入 `lineage_parse_sample`；剩下的是 L2 校验层（Calcite）与 BI 内嵌 SQL |

同时新增两项**显式标注的未实现能力**，避免把"PG tsvector 版本"说成"检索做完了"：
`core.search-chinese`（中文分词）、`core.index-opensearch`（OpenSearch 后端）。

能力计数：**已实现 19 / 部分 2 / 未实现 18 / 合计 39**（此前 14 / 2 / 21 / 37）。

### 12.2 三条值得记住的设计取舍

1. **互斥下沉到数据库，而不是应用层**
   用 `pg_try_advisory_lock` 而不是 Redis 分布式锁：Redlock 缺 fencing token，
   GC 停顿/时钟漂移下无法保证互斥；advisory lock 随连接释放，进程崩溃不留死锁。
   锁键算法与 Python 参考实现一致（`sha256("dg-collect:"+name)` 前 8 字节），
   **两种实现同时跑也互斥**。多副本同时轮询是安全的。

2. **授权过滤必须前置，且只有一处判定**
   `AccessPolicy` 是唯一入口，搜索、资产列表、详情、写入全部调用它；
   可见范围作为 SQL 条件**注入查询**而不是取回后再筛 —— 后过滤会泄露总数与分面统计
   （"共 132 条，其中 47 条你无权查看"本身就是泄露）。
   分面统计用与结果**同一套 WHERE**，这条在实现时踩过一次（参数漏传直接 500，
   见 §12.4 缺陷 2）。

3. **`visibleLevels` 参数不可为 null**
   `SearchService.search(...)` 显式要求传入可见分级，为空即抛错。
   把"忘记做权限过滤"从静默返回全部资产，变成一次显式失败。

### 12.3 验证（可复现）

```
python tools/java_e2e_verify.py     → 27/27 通过
  1.  控制面存活（标识 java-spring-boot）
  2a. 无令牌 → 401            2b. 身份含权限点与可见分级
  2c. READER 越权写入 → 403（并说明缺 asset:write）
  3.  模型注册表：11 实体 / 8 aspect
  4a. 能力清单三态齐备        4b. Batch 1 五项状态均为 IMPLEMENTED
  5.  真实采集 PostgreSQL：SUCCEEDED seen=15
  6a. 资产列表（带可见分级）  6b. 详情含 datasetSchema
  7a. 索引重建：重放 190 事件 → 写入 174 文档（ADR-002 的可执行证明）
  7b. 检索真实命中 + 分面 + 水位   7c. 空结果给出最接近候选
  8a. 调度清单（互斥方式说明） 8b. 非法调度被拒（sqlite 不支持）  8c. 立即执行真采集
  9a. 侧车就绪（sqlglot 27.29.0 / 20 方言）
  9b. 解析入库：表级边 1 / 列级边 3 / 未解析表 0
  9c. dryRun 不写库            9c2. 两跳链（供截断验证）
  9d. 解析失败落样本库         9e. 质量报告暴露样本
  10a. 影响分析评分 + 理由     10b. 深度截断显式标注
  11. 仍未实现的接口 501 + 设计说明
  12a. SPA 同端口托管          12b. 深链回退
```

另：`mvn -B test` → **62 项 Java 单测全绿**（dg-model 8 / dg-core 7 / dg-ingestion 34 / dg-policy 13）；
`pnpm build`（`tsc -b` 严格模式）通过；Python 参考实现 **250 项测试全绿**
（238 项原有 + 12 项新增侧车测试 `tests/test_sidecar.py`）。

### 12.4 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | `PostgreSQL` 的 `text[]` 列被 Jackson 序列化时爬到 JDBC 内部对象（`PgArray → connection → V3ReplicationProtocol`） | 检索接口 500，且把数据库连接暴露给序列化器 | 显式把 `java.sql.Array` 转成 `List<String>` |
| 2 | 分面统计漏传 tsquery 参数（SQL 有 4 个占位符、只传了 3 个） | 检索接口 500；若被"容错"处理会变成**分面统计错误**，而分面正是最易泄露无权资产存在性的地方 | 结果与分面共用同一份 WHERE 参数 |
| 3 | Spring `CronExpression` 只认 6 段，而配置与文档用的是 5 段 | 应用调度直接报错（或在别处被静默解释成别的语义） | `CronUtil` 显式归一化并在响应里回显归一化结果 |
| 4 | `JdbcTarget` 解析 `?user=&password=` 后把口令留在 URL 中 | 口令会随异常、连接池指标、日志被打印出来 | 取出后从 URL 删除 |
| 5 | `dg-api` 的 `/api/v1/search` 与旧占位路径并存 | 同一语义两套路径（一处真、一处假），比"没实现"更隐蔽 | 删除占位，语义只保留一处 |
| 6 | e2e 脚本中影响分析检查在找不到目标资产时**静默跳过** | "被跳过"与"通过"在结果里长得一样 —— 正是本项目要避免的静默失效 | 找不到目标即判 **FAIL** 并说明原因 |
| 7 | 侧车对不支持的方言抛 `ValueError`（HTTP 500），Java 侧把它当成"侧车不可用" | 使用者传错方言时得到 502 与"服务可能坏了"的暗示，会去排查一个没坏的服务 | 侧车改 `HTTPException(400)`；Java 区分 `ParseRejected`(400) 与 `SidecarUnavailable`(502) |
| 8 | 深度截断检查用一跳链验证（永远到不了边界，检查实际无效） | 一条永远通过的检查等于没有检查 | 先用第二段 SQL 造两跳链，再验证 `depth=1` 时的截断标注 |

### 12.5 本批之后仍然未做（诚实清单）

1. **中文分词**：PG `simple` 配置只做标识符切分，中文按字切分不可用（`core.search-chinese`）；
2. **向量检索 / RRF 融合 / 重排**：属 `ai.semantic-search`（Batch 5）；
3. **影响分析缺"使用热度"项**：`w(v)` 少了热度，因为还没有查询日志/BI 审计日志接入 ——
   响应里**显式声明未计入**，而不是悄悄按 0 处理；
4. **血缘 L2 校验层（Calcite）**：`SELECT *` 展开、JOIN 列歧义、隐式类型转换仍无二次校验；
5. **告警仍未实现**（`/api/v1/collect/alerts` → 501）：目前只能靠 `/api/v1/collect/health` 主动查看；
6. **静态令牌认证**：生产须迁 OIDC/JWKS；
7. **界面无浏览器视觉验证**：仍是"构建产物 + 接口打通"级别的验证；
8. 前端仍是单 chunk（1.15 MB）。

---

## 13. Batch 2：质量与契约（剖析 / 规则 / ODCS 契约 / CI 门禁）

按 `docs/22` 的第二批。目标是把 D4（质量与可观测）与 D5（数据契约）从"骨架"变成可用能力，
并且**契约与质量真的接通**（契约的 quality 段编译成质量规则落库并排期）。

### 13.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `quality.profiling` | 未实现 | **已实现** | 行数/空值率/唯一值/分位数/长度/Top-K/新鲜度；采样优先哈希取模、TABLESAMPLE 次之；**精度分开存**（EXACT 实算 vs ESTIMATED 估算，各自带来源）；高密级列只输出统计量 |
| `quality.rules` | 未实现 | **已实现** | 统一 IR + 三种前端（YAML / SQL 断言 / dbt tests）；编译为源库 SQL 执行；规则是实体（Owner/版本历史/可检索）；执行留痕含编译产物；**SKIPPED 是独立状态** |
| `quality.contract` | 未实现 | **已实现** | ODCS 兼容（apiVersion 与 version 分开治理）；契约是一等实体（版本历史复用 `aspect_history`）；兼容性 diff 引擎 + 版本号诚实性校验；违约事件（去重计数）；消费者订阅 + **未登记消费者**；豁免必须带到期时间 |
| `quality.ci-gate` | 未实现 | **已实现** | PR 阶段一次回答三件事（兼容性 / 血缘影响面 / 治理属性）；破坏性默认 BLOCK；判定留痕；`tools/ci/contract_gate.py` 可直接作为 CI 步骤 |

新增两项**显式标注的未实现能力**：`quality.ci-plugin`（官方 GitHub/GitLab 插件打包与 PR 评论回写）、
`core.search-chinese` / `core.index-opensearch`（Batch 1 已加）。

能力计数：**已实现 23 / 部分 2 / 未实现 15 / 合计 40**（Batch 1 后为 19 / 2 / 18 / 39）。

### 13.2 一条被验证的架构主张：新增 aspect 不改核心代码

本批新增了 2 个实体类型（`DataContract`、`QualityRule`）、2 个 aspect（`contractSpec`、`ruleSpec`）
与 1 个关系类型（`appliesTo`），**Java 控制面一行核心代码都没改** ——
`ModelRegistry` / `MetadataService` 完全按 `model/**.yaml` 驱动，新增类型立即获得：
版本历史（`aspect_history`）、字段级来源保护、检索索引（`search_doc` 消费者按类型白名单）、
审计与事件流。这正是 `docs/11` Phase 0 的 Go/No-Go 判据「后续新增 aspect 不改核心代码」的实证。

代价也真实存在：模型校验会**拦住**没声明的属性（本批就拦住了 `primaryKey` 与 `severity/onFail` 两处混淆），
这是好事 —— 严格校验把"不一致"从生产事故提前成了启动失败。

### 13.3 三条值得记住的设计取舍

1. **精度来源与值一起存，且不静默外推**
   `row_count`（实算）与 `row_count_estimate`（`pg_class.reltuples` 估算）分开存在两行，
   各自带 `precision` 与 `precision_source`。采样时用户得到的是**采样范围内的实测值**，
   平台**不做总体外推** —— 外推会得到一个看起来精确、实际有偏的数字，比标注"这是估算"更危险。

2. **"没跑成"必须是独立状态（SKIPPED）**
   行数波动这类相对判定在首次执行时没有基线。把它算成 PASS，规则会在**悄悄失效的同时让面板保持绿色**。
   因此 `rule_run.status` 的取值是 PASS / FAIL / ERROR / **SKIPPED**，界面上单独显示。

3. **契约的价值来自"生产者不能随便改"**
   破坏性变更**默认拒绝注册**，需要显式 `allowBreaking` + 理由（留痕）；版本号与变更性质不符也直接拦
   （版本号骗人时消费者无法据此判断风险）。豁免必须带到期时间，到期自动回到 OPEN ——
   允许永久豁免的体系里，所有违约最终都会被永久豁免。

### 13.4 验证（可复现）

```
python tools/java_e2e_verify.py     → 48/48 通过（Batch 1 的 27 项 + 本批 21 项）
  13a. 剖析：396 行 / 10 列 / full_scan / 精度 EXACT（并单独存了估算行数）
  13b. 采样剖析：hash_modulo 10% → ESTIMATED，未外推；2 列按类型/隐私约束未输出具体值
  14a. YAML 前端编译：4 条检查 → IR（含 uniqueness 的"唯一率→重复率"转换）
  14b. 另两种前端：SQL 断言 1 条 / dbt tests 2 条
  14c. 不支持的检查类型 → 422 并列出可用模板
  14d. 规则注册为实体（响应中 DSN 口令被遮蔽）
  14e. 执行状态三态可区分：notNull=PASS / 巨阈值=FAIL / 行数波动=SKIPPED
  14f. env: 引用未设置 → ERROR（不降级、不静默连错库）
  15a. 契约注册（ODCS）+ quality 段生成质量规则
  15b. 破坏性变更默认拒绝（并指出应升 MAJOR）
  15c. 显式授权 + 留痕后可发布
  15d. 版本历史包含当前版本（2 个版本）
  15e. 版本间 diff（删列 → 破坏性）
  15f. 运行时校验产出违约事件（7 条：实际 10 列 / 约定 3 列）
  15g. 未登记消费者 2 个（血缘上实际在读）
  16a. CI 门禁 BLOCK + exitCode 1 + 影响面（下游 2）
  16b. allowBreaking 是顶层开关（不被 policy 缺失吞掉）
  16c. 治理属性必填策略生效（Owner / 描述 / 未登记消费者 → BLOCK）
  16d. 门禁判定留痕（16 次记录）
  17a. 豁免不带到期时间 → 422
  17b. 带到期时间的豁免生效
```

另有：`mvn -B test` → **95 项 Java 单测全绿**（新增 33 项：规则编译器 18 + 契约 diff 15）；
`pnpm build` 通过；`python tools/ci/contract_gate.py` 对真实平台跑通（退出码语义正确）；
模型生成物已重新生成并通过 `dgctl codegen --check`；Python 参考实现 **250 项测试全绿**
（本批新增的模型类型由 `test_codegen.py` 的"生成物可导入且忠实"用例守住，并因此发现缺陷 9）。

### 13.5 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 剖析对 `uuid` 列生成 `MIN(event_id)` | 真实库直接报错「函数 min(uuid) 不存在」——全表剖析失败 | 按类型能力决定生成什么（`uuid` 无 MIN、`json` 无等值比较），并显式标注"该类型不支持有意义的 min/max" |
| 2 | `format('%I.%I', ?, ?)` 未加 `::text` | PostgreSQL 无法推断参数类型，估算行数静默失败（正是本方案要避免的静默失效） | 参数显式 `::text`，并给失败路径加 `log.warn` |
| 3 | 把 check 的 `severity`（HIGH）当成 `onFail` 写进规则 | 模型校验拒绝（`onFail` 只允许 BLOCK/ALERT/RECORD）→ 契约注册 422 | 区分「严重级别」与「失败动作」，并按级别推导默认动作；错误信息给出提示 |
| 4 | `contractSpec` 未声明 `primaryKey` | 带主键的契约注册被拒（模型严格校验） | 在模型中声明该属性并重新生成产物 |
| 5 | 版本列表只查 `aspect_history` | **当前版本从列表里消失**（history 存的是被替换的旧版本） | 与 `aspect` 表取并集并标出 `is_current` |
| 6 | `allowBreaking` 只在传了 policy 对象时才读 | 顶层开关被静默忽略 → 明确授权的破坏性变更仍被 BLOCK | 无论是否传 policy 都读取顶层开关 |
| 7 | `contract_ci_check` 查询里 `? IS NULL` 未 cast | 可空参数参与 `IS NULL` 比较时 PostgreSQL 无法推断类型 → 门禁历史 500 | 显式 `CAST(? AS text)` |
| 8 | e2e 契约检查用固定 id | 第二次运行会得到"相对已发布版本的变更"，把测试自身的幂等问题报成产品缺陷 | 契约 id 带运行时间戳；并记录这条经验 |
| 9 | 代码生成器按 YAML 声明顺序输出 dataclass 字段 | `contractSpec` 生成出 `apiVersion / kind / contractVersion` 的顺序 → **生成的 Python 模块无法 import**（`non-default argument follows default argument`），Python 侧测试当场变红 | 生成器把必填字段排在可选字段之前：排序约束是生成器的职责，不该要求模型作者记住它 |

### 13.6 本批之后仍然未做（诚实清单）

1. **异常检测与 SLO/事故**（`quality.anomaly` / `quality.slo-incident`）：Batch 5；底座已具备（时序指标已落库）；
2. **外部质量工具结果摄入**：docs/09 §9.4 的"先接入再自研"（GX / Soda / dbt run_results 摄入契约）未实现，
   当前只能通过 SQL 断言前端承接这些工具的判定；
3. **官方 CI 插件**：只有门禁 API + 参考脚本，没有 Action/GitLab 组件与 PR 评论回写；
4. **中文分词**与**语义检索**：仍未实现（`core.search-chinese` / `ai.semantic-search`）；
5. **contracts 没有删除接口**：测试契约只能留在库里（已按命名空间隔离）；
6. 界面仍无浏览器视觉验证，前端仍是单 chunk（1.2 MB）。

---

## 14. Batch 3（上半）：资产类别扩展 —— 多源连接器 + BI 资产

按 `docs/22` 的第三批，先做**连接器与资产类别**（血缘可视化画布留下一轮）。
目标：让平台能真的纳管 ClickHouse / MongoDB / BigQuery / Superset 四类系统，
并且把 BI 资产（报表）接进血缘 —— 这是"治理平台相对 BI 自带血缘"最有价值的增量。

### 14.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `ingestion.connectors-more` | 未实现 | 🟡 **部分实现（5 个连接器）** | PostgreSQL / **ClickHouse** / **MongoDB** / **BigQuery** / **Superset**；注册表单点声明状态，含"是否对真实系统验证过" |
| `ingestion.bi-assets`（新增） | — | ✅ 已实现 | BI 资产作为一等实体：Dashboard + `dashboardSpec`（图表清单/URL/依赖数据集/Owner）+ 独立快照与护栏 + **数据集→报表血缘方向** |

计数：**已实现 24 / 部分 3 / 未实现 14 / 合计 41**（Batch 2 后为 23 / 2 / 15 / 40）。

### 14.2 四个连接器的关键取舍

| 连接器 | 实现方式 | 为什么这么选 |
|---|---|---|
| **ClickHouse** | HTTP 接口（8123）+ `system.tables` / `system.columns` | HTTP 是一等公民，两次查询就能拿到全部元数据；JDBC 驱动只为读元数据不值得引入。**分区键/排序键进 `datasetSchema.partitionKeys`**（模型里本就预留的结构化字段），引擎与 `total_rows` 进描述并**显式标注"估算"** |
| **MongoDB** | 官方同步驱动 + **采样推断** | MongoDB 没有 schema，连接器的核心任务是"推断结构"。规则单独成类（`MongoTypeInference`）并有单测：采样多条取**并集**、嵌套对象下钻为点号路径、**数值族收敛为 number**（int32/double 混用是常态，判 mixed 只会产生噪音）、跨族不一致才报 `mixed(...)`、稀疏字段写覆盖度、可空同时看"字段缺失"与"出现过 null" |
| **BigQuery** | REST v2 + **自签 RS256 JWT**（不引 google-cloud SDK） | SDK 会带进 gRPC/protobuf/auth 一大串依赖，而只读元数据用 REST 足够。DSN 协议显式校验（`mysql://` 会被拒绝而不是被当成项目名） |
| **Superset** | REST（login → dashboard → chart） | Superset 是**消费端**：产出仪表板实体，并把 `数据集 --consumedBy--> 报表` 接进血缘。**虚拟数据集（SQL 定义）不猜血缘** —— 交由 sqlglot 侧车解析，猜错了会让人在"报表为什么挂了"的排查里走错方向 |

### 14.3 一个被真实使用发现的模型缺陷：血缘边方向

模型里原本只有 `readsFrom`（from = Pipeline/Dashboard，to = Dataset）。按全局约定
（**from = 上游、to = 下游**），这条边是**反的**：报表读表，意味着数据从表流向报表。
用它做血缘的结果是"从表出发找不到报表"，影响分析给出相反结论。

修正：新增 `consumedBy`（from = Dataset/Column，to = Dashboard/Pipeline，`lineage: true`），
并在模型里写清楚 `readsFrom` 是**关联语义边**（表达"谁在读"），血缘必须用方向正确的边。
`ModelRegistryTest` 里"血缘关系类型"的断言当场变红 —— 这正是它该做的事：模型语义变化必须显式确认。

修正后：`public.birth_names` 的下游出现 `bi.superset.births`（USA Births Names 看板），
影响分析直接回答"改这张表哪些看板受影响"。**这就是把 BI 接进血缘的收益。**

### 14.4 BI 资产的护栏与孤儿清理

- **独立快照 scope 与护栏**：BI 资产 churn 率高（人手删报表很常见），
  与数据集共用一个护栏会导致"删几个报表把数据采集整体拦停"（反之亦然）。
  因此仪表板有独立的 `dashboards` scope，护栏结果记在 `dashboard_guard_blocked` / `dashboard_guard_reason`。
- **孤儿清理**（`reconcileOrphans=true`）：快照式护栏只能发现"上次见过、这次不见了"，
  对"从未进入快照的实体"无能为力（例如连接器升级后 URN 规则变化）。本连接器开发过程中
  真的产生过 6 个这样的孤儿（无 slug 的仪表板从 `id` 改成 `uuid`），因此加了显式的清理开关：
  默认**关闭**，因为"自动删除没见过的实体"太危险，应由使用者明确要求且仍受护栏约束。

### 14.5 验证（可复现，全部对真实系统）

```
python tools/java_e2e_verify.py     → 58/58 通过（Batch 2 的 48 项 + 本批 10 项）
  19a. 连接器清单来自服务端单点声明（bigquery=未对真实系统验证，其余=已验证）
  19b. ClickHouse 真实采集：32 表 / 151 列 / 360ms
  19c. ClickHouse 元数据质量：partitionKeys=[PARTITION_BY toYYYYMM(event_date), ORDER_BY event_date,user_id]，
       描述含 total_rows≈（估算）
  20a. MongoDB 真实采集：4 集合 / 27 推断字段
  20b. 推断质量：嵌套 ['address.city','address.zip']；类型不稳定 {'email': 'mixed(string|number)'}；
       稀疏字段 ['address.zip','loyalty_score']
  20c. 可空判定：email nullable=True（"采样中每条文档都有该字段；出现过 null 值"）
  21a. Superset 真实采集：11 个仪表板
  21b. 11 个含图表，7 个已连到数据集
  21c. BI 血缘方向正确：数据集下游含报表，影响分析受影响 2
  22.  BigQuery 缺凭据 → FAILED + "读取 BigQuery 服务账号凭据失败"（不静默返回空数据集）
```

环境（真实服务，非 mock）：ClickHouse `clickhouse-server-1`（8123）、MongoDB `dg-mongo`（27018，
`shop` 库含刻意构造的稀疏字段与类型冲突）、Superset `superset-6.0`（18089，admin/admin，含 examples）。

复现环境（本机 Docker）：

```powershell
# ClickHouse：复用已运行的 clickhouse-server-1（8123）
# MongoDB：起容器并灌入演示数据（含"类型不稳定"与"稀疏字段"两种应被看见的信号）
docker run -d --name dg-mongo -p 27018:27017 mongo:7
docker cp tools/seed_mongo_demo.js dg-mongo:/tmp/seed.js
docker exec dg-mongo mongosh --quiet --file /tmp/seed.js

# Superset：起 6.0 栈（含 examples 数据），健康检查 http://127.0.0.1:18089/health
docker start superset-6.0-postgres superset-6.0-redis superset-6.0
```

另有：`mvn -B test` → Java 单测全绿（本批新增 20 项：Mongo 类型推断 10 + DSN 解析 10）；
`pnpm build` 通过；Python 参考实现测试不受影响。

### 14.6 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 模型里 `readsFrom` 被当成血缘边（方向反了） | 从表出发找不到报表，影响分析给出**相反**结论 | 新增 `consumedBy`（方向正确、lineage: true）；`readsFrom` 明确为关联语义边 |
| 2 | MongoDB 把 `int32` 与 `double` 判成 `mixed(double|long)` | 大量无意义噪音（MongoDB 里数值族混用是常态） | 数值族收敛为 `number`，只在**跨族**不一致时报警 |
| 3 | MongoDB 可空只看"字段是否缺失" | `email: null` 这种真实可空字段被标成不可空 | 可空 = 字段缺失 **或** 出现过 null |
| 4 | MongoDB 空数组让 `tags` 变成 `mixed(array<string>|array<unknown>)` | 噪音；空数组不携带元素类型 | 无类型数组作为通配，有具体类型时以具体类型为准 |
| 5 | Superset 把 `result.charts` 当成对象列表（实际是**图表名字符串**） | 图表数全为 0，仪表板看起来"没有图表" | 批量取 `/api/v1/chart` 建名字索引；未匹配上的如实记为 `resolved: false` |
| 6 | Superset 用 dataset 名猜虚拟数据集的血缘 | 虚拟数据集只是"名字像表"，猜出来的血缘是错的 | 只在 `datasource_type=table`（物理）时解析；虚拟数据集显式标注"交由 sqlglot" |
| 7 | 数据集 URN 解析在全库唯一匹配 | 同一张表被采到多个命名空间时直接退化为歧义 → 血缘断掉 | 先在**本命名空间**内唯一匹配，再退回全库 |
| 8 | 仪表板实体只增不删 | 陈旧仪表板永久留在目录（开发期真的产生了 6 个） | 独立快照 + 护栏 + 显式 `reconcileOrphans` 清理 |
| 9 | `BigQuerySource` 接受任意协议（`mysql://host/db` 被当成项目名） | 连错源却卡在费解的 API 错误上 | 协议显式校验，不匹配直接报错；MongoDB 同样处理 |
| 10 | ClickHouse 引擎/键信息只写进描述文本 | 结构化信息（分区键、排序键）无法被程序使用 | 写入 `datasetSchema.partitionKeys`（模型预留字段） |

### 14.7 本批之后仍然未做（诚实清单）

1. **BigQuery 未对真实项目验证**：无凭据；代码完成但状态标注为"未对真实系统验证"，
   capability 保持 PARTIAL。要真正验证需要服务账号与一个测试项目；
2. **血缘可视化画布**（`lineage.visualization`）：本轮未做，留下一轮；
3. **未实现的连接器**：MySQL / Trino / Hive-HMS / dbt manifest / Airflow / SQLite / DuckDB / Tableau；
4. **BI 图表级血缘**：当前是"报表 → 表"（表级）；图表用的具体列没有接（需要解析图表 form_data 或虚拟数据集 SQL）；
5. **MongoDB 未采集索引/分片信息**，且 schema 推断基于采样（默认 200 条）——采样偏差不可避免，
   已在描述里写明采样条数与覆盖度；
6. **UI 仍无浏览器视觉验证**。

---

## 15. Batch 3（下半）：血缘可视化画布 + 血缘图的两处语义修正

Batch 3 的后半部分。目标是把血缘从"一张表格"变成**可探索、可判断可信度、可人工裁决**的画布，
并在做这件事的过程中修正两处让血缘图失去意义的语义问题。

### 15.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `lineage.visualization` | 未实现 | 🟡 **部分实现** | Cytoscape + dagre 画布；**线型 = 可信度**；节点按类型/分级着色；单击看详情、双击重新聚焦；路径高亮；**每条边可确认/驳回**；服务端裁剪 + 显式回报截断 |

计数：**已实现 24 / 部分 4 / 未实现 13 / 合计 41**。
D3（血缘）域现在 **0 个未实现**：列级血缘图、OpenLineage、血缘质量、影响分析、可视化全部落地
（其中 SQL 静态解析与可视化各留了明确缺口）。

新增的后端能力（都在"血缘"这条主线上）：

1. **`GET /api/v1/lineage/subgraph`**：带属性的节点与边（来源/置信度/转换/解析级别/时效/确认人），
   支持深度、置信度、列级、控制依赖、节点上限五维裁剪，并**回报** `truncated` / `boundaryNodes` /
   `nodeLimitReached` / `notes`；
2. **`POST /api/v1/lineage/edges/{id}/confirm`**：确认血缘 → 置信度升到人工级（≥0.99）并记名；
3. **`POST /api/v1/lineage/edges/{id}/reject`**：驳回血缘 → **标记而非删除**，必须给原因；
4. **`POST /api/v1/lineage/edges/retire`**：按 (edge_type, source) 批量退役边 —— 运维工具，
   必须显式给出 edge_type 与原因。

### 15.2 两处让血缘图失去意义的语义问题（本轮修正）

**问题一：结构包含边被当成血缘。** `contains`（平台→容器→数据集）也是边，但表达的是
"表属于这个库"，不是"数据从这来"。血缘遍历只看 `state='ACTIVE' AND dependency_kind='VALUE'`，
于是**每张表的上游都冒出了它的容器与平台**：实测某表"上游"多达 41 个节点。

修正：血缘遍历统一只沿模型里 `lineage: true` 的关系类型（`derivesFrom`、`consumedBy`）。
"哪些边算血缘"必须有唯一判定，因此抽成 `MetadataService.lineageEdgeTypes()`，
被血缘遍历、影响分析、CI 门禁影响面、契约未登记消费者四处共用。
修正后同一张表的上游从 41 个降到 1 个（即焦点自身，表示确实没有上游）。

**问题二：边的语义变化会留下旧边。** 上一轮把 `readsFrom` 改成 `consumedBy`（方向修正）后，
数据库里同时存在两个方向的边 —— 因为 `edge` 的唯一键含 `edge_type`，换类型是**新增**而不是更新；
而"新版本不再写某类边"并不会让它消失。这会让画布上出现方向矛盾的边。

修正：新增显式的批量退役接口，并真的用它退役了 8 条遗留的 `readsFrom` 边
（e2e 里也验证了"不带 edgeType 时拒绝执行"，避免误伤其它来源的边）。
这条经验写进文档：**边类型迁移必须配一次显式清理**，不能指望它自己消失。

### 15.3 画布的设计要点（都来自 docs/14 §3.3）

- **线型 = 可信度**：实线 = 运行时/人工、虚线 = 静态解析、点线 = AI 推断、灰线 = 过期；
  线宽 ∝ 置信度。用户必须能一眼看出"这条边有多可信"，而不是点开才知道；
- **路径高亮**：单击节点高亮"焦点 → 该节点"的最短路径（无向，因为用户关心的是"这两者怎么连起来的"）；
- **双击重新聚焦**：探索是"从一张表走到另一张表"，不是重新输 URN；
- **裁剪必须可见**：达到深度或节点上限时，画布上方显式说明"图被裁剪过、可达多少、怎么收窄条件"；
- **边可裁决**：确认/驳回直接在边上操作 —— 确认喂养置信度模型，驳回留痕且不会被解析器写回来。

### 15.4 验证（可复现）

```
python tools/java_e2e_verify.py     → 65/65 通过（Batch 3 上半的 58 项 + 本轮 7 项）
  24a. 血缘子图：节点与边都带属性（节点 3 / 边 2，来源 {sql_parse: 2}）
  24b. 只沿「血缘关系类型」遍历：上游节点类型 {Dataset: 1}（不含 Container/Platform）
  24c. 节点上限被裁剪时显式回报：可达 3 → 展示 2（上限 2）
  24d. 确认血缘边：置信度 → 0.99，确认人 admin@local
  24e. 驳回血缘必须说明原因（422）
  24f. 驳回后该边不再参与血缘遍历
  24g. 批量退役边必须显式给出 edgeType（400）
```

另有：`mvn -B test` Java 单测全绿；`pnpm build` 通过（前端引入 Cytoscape，单 chunk 从 1.2MB 增至 1.7MB，
代码分割仍未做）；Python 参考实现测试不受影响。

### 15.5 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 子图边查询的 `formatted()` 少传一个参数（3 个 `%s` 只给了 2 个） | 接口直接 400（`Format specifier '%s'`）—— 手工验证时立刻暴露 | 补齐参数；顺手把"手工验证先行"作为新接口的动作固化下来 |
| 2 | `contains` 等结构边被算作血缘 | 每张表上游都冒出容器/平台，某表"上游"多达 41 个，血缘图失去意义 | 只沿 `lineage: true` 的关系类型遍历，判定单点化 |
| 3 | 边语义变化（`readsFrom` → `consumedBy`）留下方向矛盾的旧边 | 画布上同一条事实出现两个方向的边 | 新增批量退役接口并真的清理了 8 条遗留边 |
| 4 | `reachableBeforeLimit` 不含焦点自身，而 `nodes` 含 | "可达 2 / 展示 3" 这种自相矛盾的计数会让人以为裁剪逻辑坏了 | 计数口径统一为含焦点 |
| 5 | 节点上限下限被硬编码为 10 | 传 `nodeLimit=2` 被静默忽略（"上限 600"），无法表达"只看直接邻居" | 下限改为 1 |

### 15.6 本 batch 之后仍然未做（诚实清单）

1. **时间轴回放**（"三个月前的血缘"）：需要边的 `valid_from` / `valid_to` 版本化，属 Phase 2；
2. **节点数超上限的聚合视图**：当前会显式提示并建议收窄条件（诚实但不优雅）；
3. **列的端到端路径追踪视图**（A.col1 → B.col2 → C.metric 的转换链路）；
4. **血缘健康 / 未确认血缘队列**（docs/14 §2）；
5. **前端代码分割**：引入 Cytoscape 后单 chunk 1.7MB（首屏仍是全量加载）；
6. **BigQuery 仍未对真实项目验证**（沿用上一轮的诚实标注）。

---

## 16. Batch 4：访问治理（申请审批 / 策略生命周期 / 审计复核）

按 `docs/22` 的第四批，也是 `docs/20 §8` 认定的**核心差异化**所在。

### 16.1 定位：执行层是商品，生命周期层才是产品

文档的原话（`docs/20 §8`）值得在这里重抄一遍，因为它决定了本批**不做什么**：

> 本平台的差异化是「数据访问治理的生命周期闭环」：策略建模（业务可读）→ 申请与审批（带 SLA、时限、最小粒度建议）→ 编译与下发（复用 Trino/Spark/Ranger/数仓原生能力，**不自研执行引擎**）→ **覆盖率度量** → 到期回收与定期复核 → 审计取证。开源缺的正是 PAP/PIP 这一层。

因此本批刻意**没有**去写 Trino 插件、没有去对接 Ranger —— 那些是现成件。
本批交付的是它们上面缺的那一层。

### 16.2 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `policy.access-request` | 未实现 | ✅ **已实现** | 申请自动填充分级/路由/SLA/**最小粒度建议** → 审批（禁止自批 + 审批链校验）→ 生成**带到期时间**的授权 → 到期自动回收（常驻任务）→ 人工吊销 → 定期复核 |
| `policy.compiler` | 未实现 | 🟡 **部分实现** | 业务可读建模 → 纯函数编译器（快照可测）→ 四类产物（Trino 谓词/掩码、数仓 GRANT/REVOKE+RAP、BI 可见性、SDK claim）→ 产物归档（内容哈希）→ bundle 下发 + 部署记录 + 回滚 → **覆盖率度量** |
| `policy.audit` | 未实现 | ✅ **已实现** | 访问事件全链路留痕 + 合规审计报告（申请漏斗/审批时延/分级分布/吊销记录/下发记录）+ 最小权限复盘；报告显式声明**审计覆盖范围** |
| `policy.engine-audit-ingest`（新增） | — | ❌ 未实现 | 引擎侧访问审计摄入（真实查询、直连访问）—— 明确标注，因为它是本批最大的诚实缺口 |

计数：**已实现 26 / 部分 5 / 未实现 11 / 合计 42**（Batch 3 后为 24 / 4 / 13 / 41）。

### 16.3 三条设计要点

1. **最小粒度建议是"建议"而不是"限制"**
   整表申请会得到明确提示："建议改为列级申请（只申请真正需要的列）—— 整表授权通常不是业务真实需求，
   而是「不想麻烦」的结果"。平台**不强制**，但会把建议写进申请记录，让审批人看到。
   同样地，超过一年的期限会被提示"这其实是长期岗位职责，应走角色授权"。

2. **没有使用数据时绝不假设"未使用"**
   复核建议在缺少证据时返回 `NEED_MORE_INFO` 并说明原因（未接入查询日志/引擎审计）。
   这是刻意的：把"没有记录"当成"没有使用"会导致在用的权限被误回收 ——
   而权限误回收造成的生产事故，比多留一条权限严重得多。

3. **覆盖率必须和盲区一起呈现**
   `POST /api/v1/policies/coverage` 给出的不只是百分比，还有：
   高分级但**没有任何策略覆盖**的资产清单、以及三条已知盲区
   （直连 JDBC 绕过执行点、BI 不是安全边界、SDK claim 可被忽略）。
   `docs/09 §9.7` 的原话：**宣称"策略已下发"而不度量覆盖率，是最危险的产品表述。**

### 16.4 验证（可复现）

```
python tools/java_e2e_verify.py → 85/85 通过（Batch 3 的 65 项 + 本批 20 项）
  25a. 申请自动填充：分级 L2 · 整表 → STEWARD；SLA 48h；含最小粒度建议与"自批自用"提醒
  25b. 申请人不能审批自己的申请（权限点 + 审批链双重校验）
  25c. 审批通过生成带到期时间的授权（30 天）
  25d. 拒绝申请必须说明原因（422）
  25e. 授权列表可读（数组列正确序列化 —— 见缺陷 2）
  25f. 治理概览：待办 / 超期 / 即将到期
  26a. 复核：缺使用数据 → NEED_MORE_INFO（不假设未使用）
  26b. 复核 REVOKE 真正回收授权
  26c. 吊销必须说明原因
  26d. 到期回收可执行并回报条数
  27a. 编译预览：产物带出处注释，谓词 region = 'CN'
  27b. 建模即编译（编译不过不入库）
  27c. 非法运算符被拒绝（自定义表达式是注入面，不是灵活性）
  27d. 编译全部 ACTIVE 策略并归档产物（内容哈希）
  27e. 下发产出 bundle：3 条产物 → policy-bundle-trino-*.txt
  27f. 覆盖率：覆盖 29/326（8.9%）、高分级未覆盖清单、已知盲区含"直连"
  27g. 回滚：记录一次指向旧产物集合的新部署
  28a. 审计报告显式声明覆盖范围（已覆盖 3 项 / 未覆盖 3 项）
  28b. 访问事件留痕（REQUEST_SUBMITTED / GRANT_CREATED / GRANT_REVOKED / REQUEST_APPROVED）
  28c. 最小权限复盘显式标注"无使用数据"
```

另有：`mvn -B test` → **139 项 Java 单测全绿**（本批新增 24 项：策略编译器 14 + 访问路由 10）；
`pnpm build` 通过；Python 参考实现测试不受影响。

### 16.5 本轮修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 覆盖率判定里"只有 `table` 的策略"会跳过所有前缀检查并返回 true | **覆盖率被算成 100%**，而它其实只覆盖一张物理表 —— 假的好数字比没有数字更危险 | 只有 table 的策略不参与目录覆盖率；必须给出 prefix 或分级/域选择器 |
| 2 | `text[]` 列（permissions/approvers）经 JdbcTemplate 返回 `PgArray`，Jackson 无法序列化 | 授权列表/复核/最小权限三个接口 500，且把 JDBC 连接对象交给序列化器 | **全局**注册 `java.sql.Array` 序列化器（`JdbcJsonConfig`）—— 这个坑 Batch 1 出现过一次，第二次出现说明逐处手工转换不是解法 |
| 3 | JdbcTemplate 把 `String[]` 直接 setObject 到 `text[]` 列 | `SQLFeatureNotSupportedException`（pgjdbc 未实现 setObjectArray） | 改用 `createArrayOf` + PreparedStatementCreator |
| 4 | `(? IS NULL OR target = ?)` 未 cast | 编译全部策略时 PostgreSQL 无法推断参数类型 → 500 | 显式 `CAST(? AS text)`（与 Batch 2 的 `ci-history` 同一类问题） |
| 5 | `expiresAt(now, 100000)` 的硬上限测试断言写成了"恰好 10 年" | 测试自身错误（3650 天 ≠ 10 个日历年，闰年） | 改为断言"被截断到上限"这一行为本身 |

### 16.6 本批之后仍然未做（诚实清单）

1. **引擎侧访问审计摄入**（`policy.engine-audit-ingest`）：没有真实查询日志，
   因此"批准了但 90 天未使用"只能靠人工判断（当前已显式标注证据不足）；
2. **策略不直接推送到执行引擎**：产物是 bundle（落盘，可接 GitOps），
   Trino AccessControl 插件/Ranger 的部署不在范围内 —— 这是 `docs/20 §8` 的有意选择；
3. **BI 目标未对接 Superset/Tableau 的下发 API**：只产出配置片段；
4. **直连绕过无法检测**：需要引擎审计日志或网络层数据（未接入），覆盖率数字不含这一风险；
5. **审批链是单级**（按分级决定角色），没有多级会签与代理审批；
6. **界面无浏览器视觉验证**（Batch 5 已用无头 Chrome 补上"逐标签页渲染检查"，见 §17.4）。


## 17. Batch 5：AI 原生能力、可观测性与 Edge Agent（收尾批）

按 `docs/22` 的第五批。这一批刻意**不新增能力项**，而是把前四批留下的
「未实现 / 部分实现」尽量补掉，并且把每个补掉的地方都做成可复现的验证。

### 17.1 三条主线

| 主线 | 文档依据 | 一句话 |
|---|---|---|
| AI 原生能力 | `docs/13` | 建议引擎 / 混合检索 / MCP 出口 / 语义层指标 |
| 可观测性 | `docs/09 §9.4` | 异常检测（MAD / 季节性 MAD）→ SLO 达成 → 事故闭环 |
| Edge Agent | ADR-012 | 数据不出域场景的**推模式**接入点（控制面侧） |

### 17.2 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `ai.suggestion` | 未实现 | ✅ **已实现** | 三个确定性生成器（无主资产 / 缺描述 / 敏感列未分级）→ 建议收件箱（含 rationale / confidence / generator_ref / evidence）→ 人工采纳才写 aspect（来源 `AI_GENERATED`）→ 驳回必须给理由 → 采纳率统计；**7 天冷却**防重复刷屏 |
| `ai.mcp` | 未实现 | ✅ **已实现** | MCP JSON-RPC 子集（initialize / tools/list / tools/call / ping）+ 11 个工具 + **按调用者权限裁剪** + 每次调用写 `access_event(source=mcp)`；Agent 的唯一写路径是 `propose_aspect`（提建议），**不存在直写元数据的工具** |
| `ai.semantic-layer` | 未实现 | ✅ **已实现** | dbt / Cube / 平台自有 YAML → Metric 实体 + 口径血缘（指标 ← 列）→ 指标详情可回溯到物理列与上游数据集 |
| `ai.semantic-search` | 未实现 | 🟡 **部分实现** | 两路召回（词法 + 术语表同义扩展）+ RRF（k=60）融合 + 授权分级前置过滤；**向量路未实现**（无 embedding 服务），因此不称"语义相似度" |
| `quality.anomaly` | 未实现 | ✅ **已实现** | L2 MAD 稳健 Z + L3 季节性 MAD（按周期内位置分组）；样本不足**显式跳过**；上游异常**抑制**下游（防告警风暴） |
| `quality.slo-incident` | 未实现 | ✅ **已实现** | 四类 SLO（新鲜度 / 通过率 / 可用性 / 结构稳定性）+ 错误预算 + 无数据时回报 `no_data`；事故带血缘影响面与时间线；**解决必须二选一**（沉淀规则 / 说明为何不需要） |
| `ingestion.edge-agent` | 未实现 | ✅ **已实现**（控制面侧） | Agent 注册（凭据只下发一次、库里只存哈希）→ 心跳 → 数据集+列上报（同一套 URN 与来源保护）→ 凭据吊销（保留历史）→ 上报记录可查；**超限拒绝而不截断** |
| `core.search-chinese` | 未实现 | 🟡 **部分实现** | 中文 **bigram** 切分（写入与查询两侧同构）+ 标点当分隔符 + **描述也参与切分**；**未做词典分词**（pg_jieba/zhparser/IK） |
| `core.codegen` | 部分实现 | 🟡 部分实现（说明更新） | 生成器与 `--check` 门禁可用并已用于本批（新增 `termSpec`/`Metric` 后重新生成）；Java 侧不生成代码、仓库内无 CI 流水线 |

计数：**已实现 32 / 部分 7 / 未实现 3 / 合计 42**（Batch 4 后为 26 / 5 / 11 / 42）。
未实现的三项：`core.index-opensearch`、`quality.ci-plugin`、`policy.engine-audit-ingest`。

### 17.3 五条设计要点

1. **AI 的诚实出口比 AI 能力本身更重要**
   `GET /api/v1/ai/status` 明确写清：规则型生成器可用、大模型**未配置**、向量检索**未实现**。
   未配置时 `POST /api/v1/ai/suggestions/llm` 返回 **502 + 怎么配置**，而不是退回模板拼一段
   "看起来像 AI"的文字。没有 provenance 的 AI 输出在治理场景是负资产：使用者无法判断该不该信。

2. **Agent 不走后门**
   MCP 工具清单**按调用者权限裁剪**（没权限的工具不出现，而不是"列出来再拒绝"）；
   每次 `tools/call` 的允许/拒绝/失败都写 `access_event`。
   最关键的是：Agent 的写路径**只有提建议**（`propose_aspect`）—— 让外部 Agent 直接改元数据，
   等于把治理纪律交给一个不受控的进程。

3. **上游抑制下游：治理平台相对纯可观测性产品的独有优势**
   异常检测不只算分数，还用**血缘**判断"这是源侧异常还是被传导过来的"：
   上游同窗口已异常 → 下游标记 `PROPAGATED` 并抑制（保留可追溯，但不进告警）。
   同一主资产只开一个事故；一个故障开 N 个事故会让响应者无从下手。

4. **"没判定"必须与"正常"分开**
   样本少于 7 个点不做统计判定，并出现在 `skipped` 列表里；
   SLO 没有数据时回报 `no_data` 而不是 100%；
   SKIPPED 的质量规则执行从达成率分母中剔除。
   这三处都是同一件事：**宁可说"不知道"，也不给假的好数字**。

5. **闭环的定义是"沉淀出规则"**
   解决事故必须关联一条规则/检测器，或显式说明为什么不需要。
   平台把"已解决但没沉淀规则"的事故数列在概览里 —— 这是判断闭环机制是否真的在起作用的观察点。

### 17.4 验证（可复现）

```
python tools/java_e2e_verify.py → 123/123 通过（Batch 4 的 85 项 + 本批 38 项）
  29a. AI 能力状态把"没配置什么"摆在明面上（向量检索=false、LLM configured=false）
  29b. 未配置大模型时明确失败（502 + 原因），而不是退回模板冒充 AI
  29c. 建议生成器产出候选（每条带 rationale 与 generator_ref）
  29d. 采纳建议才写入 aspect，来源标记 AI_GENERATED（人工仍可覆盖，ADR-005）
  29e. 驳回必须给理由（无理由 422 / 有理由 200）
  29f. 采纳率统计（含 docs/13 §4 的 ≥40% 验收口径）
  29g. 重复生成幂等（不重复创建、不报错）
  30a. 术语同义扩展：用同义词检索命中术语并触发扩展召回
  30b. 中文 bigram 检索可用（描述里的中文也能命中），向量路标注不可用
  31a. MCP 工具清单按调用者权限裁剪（admin 11 个 → reader 10 个）
  31b. tools/call 返回结构化内容 + 文本双份（符合 MCP 约定）
  31c. 越权调用被拒（拒绝结果 + 审计事件）
  31c2. Agent 的写路径只有"提建议"：建议进队列，元数据没有被直接改写
  31d. 未实现的 MCP 方法返回 -32601 并说明实现范围
  31e. MCP 调用全部留痕（MCP_TOOL_CALLED / MCP_TOOL_DENIED / MCP_TOOL_FAILED）
  32a. 语义层接入：dbt 指标落成 Metric 实体 + 指标←列 的 consumedBy 血缘
  32b. 不支持的来源格式被明确拒绝（缺字段不会被当成字符串 "null" 传下去）
  33a. MAD 稳健 Z 检出越界（给出方法/阈值/样本数，误报可回溯到方法）
  33b. 上游抑制下游：下游连锁异常被标记 PROPAGATED 并抑制
  33c. 被抑制的异常默认不出现，但可查（抑制 ≠ 删除）
  33d. 样本不足的序列显式跳过（"没判定"不等于"正常"）
  33e. 未实现的检测方法被明确拒绝并说明分层
  34a. SLO 达成率由真实执行数据算出（无数据时回报 no_data）
  34b. 非法 SLO 定义被拒（类型白名单 + target ∈ (0,1]）
  34c. 开事故自动算血缘影响面
  34d. 闭环强制：解决必须关联沉淀出的规则，或说明为什么不需要
  34e. 事故时间线保留全过程（DETECTED → RESOLVED）
  34f. 运营总览暴露两个真观察点：MTTR 与"解决了但没沉淀规则"的事故数
  35a. Agent 注册一次性下发凭据（库里只存哈希）并声明 Agent 二进制未实现
  35b. Agent 用**自己的凭据**自检（与平台令牌是两套认证）
  35c. 心跳可写（存活判定依据）
  35d. 私有子网推上来的元数据进同一套真相源（可检索、走来源保护）
  35e. 上报记录可查（区分"没推"与"推了但被拒"）
  35f. 超过单次上限时拒绝而不是截断
  35g. 凭据吊销立即生效（历史记录保留）
  35h. 管理面仍需平台令牌（Agent 认证路径没有顺带把管理接口放开）
  23c. 新增入口 /observability、/ai 由 SPA 托管（深链可直达）
  23d. 界面产物与后端同源发布（入口 bundle 确实被服务出去）
```

```
mvn -B -o test            → 142 项 Java 单测全绿（本批新增 3 项：中文切分与写查一致性）
python -m pytest -q       → 250 项 Python 测试全绿（参考实现不受影响）
cd web && pnpm build      → 通过（入口 bundle dist/assets/index-*.js）
python tools/ui_render_check.py → 9/9 标签页渲染通过（无头 Chrome，逐 Tab dump DOM）
```

**关于界面验证**：Batch 4 的诚实清单里写着"界面无浏览器视觉验证"。本批补上了
`tools/ui_render_check.py`：用无头 Chrome 渲染每个页面/标签页并断言关键文案出现在 DOM 里。
它逼出一个真实问题 —— antd 的 `Tabs` 默认**懒挂载**，只 dump 第一个标签页根本验证不到其余标签页；
因此顺手把标签页做成可深链（`?tab=incidents`），既方便分享，也让每个标签页都能被验证。
这只证明"页面渲染出了关键内容、没有渲染期异常"，**不等于视觉与交互评审**（仍未做）。

### 17.5 本轮修掉的真实缺陷

本批的缺陷密度明显高于前几批，原因是：新增的多数功能都是**第一次被真实数据跑**，
而"写下代码"与"跑通一次"之间的差距比想象中大。逐条记录：

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | `INSERT … ON CONFLICT DO NOTHING RETURNING id` 用 `queryForObject` | 冲突时返回**零行**，`EmptyResultDataAccessException` → **500**。也就是说"重复建议静默跳过"这个设计**从未真正生效**，只是没被触发过 | 改用 `queryForList` 取首行；e2e 29g 专门断言幂等 |
| 2 | `String.valueOf(map.get("k"))` 在键缺失时返回**字符串 `"null"`**（两处：`sourceFormat`、驳回 `reason`） | `sourceFormat` 变成非法的 `"null"` → 报错指向"模型校验失败"而不是"你传错了字段"；**驳回理由不填也会被记成 `"null"`** → "驳回必须给理由"形同虚设 | 统一 null 安全取值；e2e 29e / 32b 断言 |
| 3 | `dataset_urn = ANY(?)` 绑 `String[]` | `SQLFeatureNotSupportedException` → **下游扫描直接 500**，也就是"上游抑制下游"这个亮点功能是坏的 | 改 `createArrayOf` + PreparedStatementCreator |
| 4 | `text[]` 列用 `setObject(String[])`（`edge_agent.capabilities`、`metric_definition.physical_columns`） | 注册 Agent、接入语义层指标双双 500。这是**第四次**踩同一个坑 | 两处都改 `createArrayOf`，并在代码注释里写明"这是第四次" |
| 5 | `buildSearchText` 把**描述原样拼入**索引文本，而同义词又走描述通道 | 查询侧按 bigram 切、索引侧是整段 lexeme → **"按中文描述找资产"静默失效**，术语同义扩展也永远匹配不上 | 描述与同义词都走同一套切分；新增单测断言"查询串的每个 token 都在索引文本里"，把这条件变成可回归的硬约束 |
| 6 | 标点未当分隔符 | 切出 `，明细` 这类永远匹配不上的 token | 非字母数字非中文一律按分隔符处理；新增单测 |
| 7 | 事故 `source` / `severity` 未在服务层校验 | 违反 DB CHECK → **500**（用户看到"服务器错误"而不是"source 不合法"） | 服务层白名单校验 → 422 |
| 8 | `EdgeException` 靠**消息文本**判定 HTTP 语义（含"不存在"→401） | "Agent 不存在"被报成"未鉴权"；消息一改语义就错 | 改为显式 `kind`（401 / 404 / 422），并让 e2e 断言状态码 |
| 9 | MCP 宣称"按角色裁剪工具"，`tools/list` 实际返回全部 | **能力声明与行为不一致** —— 这正是本仓库最不能容忍的一类问题 | 真正实现裁剪；并补上 `propose_aspect`（`governance:write`）让"写工具对无权者不可见"可验证 |
| 10 | 建议被采纳/驳回后，下一次生成**立刻重新创建同一条** | 驳回等于没驳回，收件箱被同一诉求刷屏 | 引入 7 天冷却，并在返回里说明 `cooldownDays` |
| 11 | `GET /api/v1/edge/reports` 上挂了一个多余的 `@PathVariable` 参数 | 请求映射会失败（本批新写，未进入线上） | 移除 |
| 12 | Edge Agent 上报只建 `container→dataset` 边，缺少 platform 根节点 | 同一个库被"拉取"和"推送"各采一次会变成图里两个互不相连的资产 | 补齐 `platform→container` 边，与 `CollectionService` 的 URN 形状对齐 |
| 13 | `AiException` 只有一种语义，未配置能力报 422 | "没配大模型"与"请求不合法"混为一谈 | 区分 502（未配置）/ 422（请求不合法） |
| 14 | e2e 脚本里 `results = ...` 覆盖了同名全局检查列表 | 汇总阶段 `KeyError: 1`，脚本崩在最后一步 | 局部改名（顺带说明：这类"测试脚本自身的错"也要当成缺陷记下来） |

### 17.6 本批之后仍然未做（诚实清单）

1. **大模型推理未实现**：`dg.ai.llm-endpoint` 为空时明确报错；即使配置了端点，
   本轮也**没有实现 HTTP 调用与提示词模板**（代码里明说，不返回假文本）。
2. **向量检索 / embedding 未实现**：混合检索只有两路（词法 + 术语扩展），
   因此 `ai.semantic-search` 是"部分实现"，平台不宣称语义相似度。
3. **中文未做词典分词**：只用 bigram。
   代价是多命中（如"订单"与"单明"都会命中），这是精度换可用性的折中，不是最终形态。
4. **Python 参考实现的切分未同步 bigram**：若以 `src/dg` 为基准做对照，
   这一处行为与 Java 控制面不同（Java 侧为准）。
5. **MCP 只实现核心子集**：`resources` / `prompts` / `sampling` / `notifications` / stdio 传输未实现，
   未知方法返回 -32601 并说明范围。
6. **异常检测未实现 PSI / KS 分布漂移与 STL 分解**：季节性用"位置分组基线"近似；
   分布漂移需要分布快照而不是统计量。
7. **告警通道未实现**：没有邮件/IM/on-call 分派，也没有告警状态机（去重/冷却/恢复）——
   本批做的是判定与闭环，外部通知属集成层。
8. **Edge Agent 的 Go 单二进制未实现**：控制面侧的协议与接入点已就绪，
   任何能发 HTTP 的采集器都能用；gRPC 流式上报、Agent 侧缓存与断点续传、自动升级均未做。
9. **指标不反向同步**：语义层是"接进来"，没有从 BI 工具反向拉指标。
10. **界面仍无视觉/交互评审**：只做到"逐标签页渲染出关键内容"。


## 18. NFR-SEC-01：第三方依赖与供应链安全（合规要求落地）

### 18.1 需求与定位

公司合规与安全要求：**所选技术与第三方依赖不能有漏洞，或至少把漏洞压到最低；
真有漏洞要替换，或选择积极修复的 FOSS，保证安全合规与持续性。**
完整需求、验收标准与流程见 **`docs/23`**（本文只记录落实过程与实测数据）。

这一条与其它质量属性的区别值得先写下来：**它由别人的代码随时间变化决定**。
今天我们什么都没改，漏洞也可能因为上游披露而出现。因此它的落地形态不是"一次评审"，
而是"门槛 + 选型标准 + 持续性机制"三件套，缺一条都不成立。

### 18.2 交付

| 交付物 | 说明 |
|---|---|
| `docs/23-dependency-and-supply-chain-nfr.md` | NFR 正文：需求陈述、7 条可判定验收标准、修复时限流程、8 条 FOSS 选型标准、落地机制、实测基线、诚实边界 |
| `docs/12` ADR-015 | 决策记录（含被否决的三个替代方案与理由） |
| `docs/07 §8` | 架构质量属性表新增"供应链安全"一行（与其它 SLO 并列，但注明其性质不同） |
| `docs/10 §4` | 安全设计表的"供应链"一行指向 NFR-SEC-01 与落地工具 |
| `tools/dependency_audit.py` | 清点 + OSV 扫描 + CVSS 自算 + 豁免校验 + 报告输出；**三态退出码**（0 干净 / 1 阻断 / 2 未验证） |
| `tools/dependency_audit.py --self-test` | **反向自检**：用已知含漏洞的版本验证扫描链路有效 |
| `security/dependency-waivers.yaml` | 例外台账（当前为空），要求 owner + 到期日，到期自动重新阻断 |

### 18.3 实测：把"没有漏洞"这句话变成可验证的

**升级前**（Spring Boot 3.1.0，2023-05 发布、OSS 支持早已结束）：

```
依赖清点：Maven 98 · npm 79 · PyPI 8
已知漏洞：95 条（CRITICAL 10 · HIGH 42 · MEDIUM 33 · LOW 10）
集中在框架托管的传递依赖：tomcat-embed-core 10.1.8（25 条，含 7 项 CRITICAL）、
jackson-databind 2.15.0、spring-web/webmvc 6.0.9、spring-security-* 6.1.0 等
```

**修复动作**（按"能修就修、能换就换"的顺序）：

1. Spring Boot 3.1.0 → **3.5.16**（整条版本线的抬升；**零代码改动**，142 项单测全绿）；
2. 框架托管版本仍落后于修复版本的三处 → **显式固定已修复的补丁版本**：
   `tomcat 10.1.60`、`jackson-bom 2.21.7`、`commons-lang3 3.18.0`、`log4j2 2.26.1`；
3. 前端 `react-router-dom 6.30.6 → 7.18.4`（open redirect 与 SSR 构造函数注入）；
4. 删除 `jjwt` 三件套 —— **引入但没有任何代码使用**，不用的依赖同样是供应链负担。

**升级后**：

```
依赖清点：Maven 99 · npm 80 · PyPI 8（合计 187 个依赖）
已知漏洞：0 条（阻断 0 / 豁免 0）
自检：jackson-databind@2.21.4 检出 9 条、react-router@6.30.6 检出 2 条 → 扫描链路有效
回归：142 项 Java 单测 / 123 项 e2e / 9 项界面渲染 / Python 250 项 / pnpm build 全绿
```

### 18.4 本轮值得记住的三个细节

1. **"0 条漏洞"本身不足信，必须配一个反向用例。**
   报告"0"有三种可能：真的干净、扫描器坏了、清单是空的。
   因此加了 `--self-test`（用历史上有名的漏洞版本验证检出能力）与
   **清点失败即 UNVERIFIED** 的判定 —— 只扫到一半依赖的报告，看起来会比真实情况更干净，
   这是供应链安全里最危险的产物。这个教训在开发过程中真实发生过一次：
   升级后 Maven 清点因离线模式静默返回 0 条，工具当时差点打出"0 条漏洞 · 通过"。

2. **OSV 给的修复版本可能并不存在。**
   OSV 说 Tomcat 的修复版本是 `10.1.58`，而 Maven Central 上**没有发布 10.1.58**
   （10.1.57 之后直接是 10.1.59）。按 `fixedIn` 无脑固定版本会让构建直接失败。
   因此工具只把 `fixedIn` 当作**人工判断的输入**，不做自动改版本 —— 供应链里"自动升级"是危险的自动化。

3. **OSV 的严重度要自己算。**
   OSV 记录里给的是 **CVSS 向量**（`CVSS:3.1/AV:N/AC:L/...`）而不是分数，
   直接正则取数字会全部解析失败（第一版就是这样，95 条全成了 UNKNOWN）。
   现在按 CVSS v3 规范实现 base score 计算，并优先采用 GHSA 自带分级；
   两者都没有才标 UNKNOWN，且 UNKNOWN 按 MEDIUM 处理、必须人工复核。

4. **升级回归时又抓出一处"测试脚本自身的错"。**
   依赖升级后重跑 e2e，29g（建议生成幂等）失败：第二次生成新增了 1 条。
   查下来不是产品缺陷 —— 29d/29e 刚刚裁决过建议（采纳后该资产不再"缺描述"），
   候选池会**正常补进**一个新的候选。是断言写得不对（把"池子补位"当成了"幂等失效"）。
   改成"先跑一次吸收池位变化，再连续跑一次断言新增 0"，这才是幂等的正确判定方式。
   —— 这类"测试骗人"的问题和产品缺陷一样要记下来：**一个错误的断言会长期掩盖真实行为**。

### 18.5 仍然未做（诚实清单）

见 `docs/23 §8`：SBOM 生成、制品签名/来源验证、企业私有仓库镜像落地、
`dg-sdk` 的独立扫描、Spring Boot 4.x 迁移（独立项目）。
**扫描只能发现"已知"漏洞** —— "0 条"的含义是"没有已知漏洞"，不是"没有漏洞"。


## 19. Batch 6：把「审计闭环」与「CI 门禁」补完

上一批结束时还剩 3 项「未实现」。本批先做其中两项 —— 它们有个共同点：
**都不是"再加一个功能"，而是把已有的判断能力接上它缺的那一半数据/触点**。

### 19.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `policy.engine-audit-ingest` | ❌ 未实现 | ✅ **已实现** | 推送式摄入 Trino 查询事件 / Ranger 访问审计 / 数仓查询日志 → 字段别名归一 → 解析到平台资产（**解析失败也入库**并说明原因）→ 内容哈希**幂等去重** → 覆盖情况可查；由此得到两个此前答不了的结论：**「批准了但零访问」**与**「被访问但无授权」** |
| `quality.ci-plugin` | ❌ 未实现 | ✅ **已实现** | GitHub 复合 Action（12 输入）+ GitLab CI 组件模板 + PR/MR 评论 **upsert** 回写 + 本仓库自己的 CI 流水线（同时是该插件的活样本） |
| `core.codegen` | 🟡 部分 | 🟡 部分（说明更新） | CI 流水线补上（模型校验、`codegen --check`、单测、前端构建、Python 测试、供应链扫描、端到端）；**Java 侧生成器明确为有意不做**（两份生成器必然漂移） |

计数：**已实现 34 / 部分 7 / 未实现 1 / 合计 42**（Batch 5 后为 32 / 7 / 3 / 42）。
仅剩的未实现项：`core.index-opensearch`（检索后端替换，触发条件未到）。

### 19.2 三条设计要点

1. **判定「未使用」的唯一标准是"观测窗口覆盖了授权的整个生命周期"。**
   这是本批最重要的判断逻辑。窗口比授权晚开始，就只能给"证据不足" ——
   用 7 天日志去否定一条 200 天前的授权，是审计里最常见的错误结论。
   另外加了 30 天最小授权年龄门槛：昨天刚批的授权今天没被用上，不该被标成"可回收"。

2. **审计覆盖面必须"随实际数据变化"，否则会变成谎话。**
   上一批的审计报告里硬编码着"引擎侧真实查询未覆盖"。接入之后如果还这么写就是错的，
   因此改成按当前数据生成：接了 → "已覆盖 + 观测窗口 + 记录数"；没接 → "未覆盖"。
   同时新增了一条以前不可能的结论："被访问了但没有任何授权"（直连绕过治理的信号）。

3. **CI 插件里，"回写失败"和"门禁失败"必须分开。
   回写评论失败不让整步失败、没有凭证就明说跳过 —— 因为 CI 插件被删掉的头号原因
   不是判定不准，而是"因为一个次要环节挂了，整条流水线变红"。
   判定本身仍然严格：破坏性变更退出码 1，平台不可达时默认降级为"不阻断 **+ 报告里写明没检查**"。

### 19.3 顺带修掉的一类老毛病：数组绑定（第 5 次）与它的门禁

引擎审计的 `columns text[]` 又踩了同一个坑：`JdbcTemplate` 直接传 `String[]`
要到执行期才抛 `SQLFeatureNotSupportedException`。这是**第 5 次**
（调度 DSN、访问申请、质量规则、Edge Agent、引擎审计）。

这次不只修它，而是把它变成**不可能再犯**：

- `com.datagovernance.core.sql.TextArrays.of(connection, values)` 作为唯一入口；
- `PgArrayBindingGuardTest`：扫描 `backend/**/src/main/**/*.java`，
  禁止在 `jdbc.*` 调用里直接传数组值；**并带反向自检**（用一条已知的错误写法验证门禁真的会红）。

顺带把 `TableResolver` 从 dg-lineage 移到 dg-core：SQL 解析（血缘）与引擎审计（真实访问）
必须用**同一套**"裸表名 → URN"的解析规则，否则会出现"血缘认出来了、审计没认出来"的不一致 ——
而基于它做判断的正是复核与回收。

### 19.4 验证（可复现）

```
python tools/java_e2e_verify.py → 139/139 通过（Batch 5 的 123 项 + 本批 16 项）
  28a. 审计覆盖范围随实际接入情况变化（接入前后两种状态都判得住）
  28c. 有使用证据时：每条结论都给出依据，并说明「未使用」的判定标准
  36a. 摄入回报四个数字：接受/重复/未解析/被拒（被拒带原因）
  36b. 幂等：日志重放不会把一次访问记成很多次（内容哈希去重）
  36c. 接入情况可查：引擎清单、观测窗口、解析率
  36d. 解析不到资产的记录仍然保留并说明原因
  36e. 使用证据改变最小权限结论：用过的→保留；窗口完整覆盖且零访问→可回收
  36f. 「被访问但从未被批准」可查（有授权的主体不在其中）
  36g. 审计覆盖范围按实际数据动态生成
  36h. 参考采集器 tools/engine_audit_load.py 能把 JSONL 日志推上来
  36i. 不支持的引擎被明确拒绝（不静默归为 other）
  37a. 门禁插件：一条命令产出退出码 + Markdown 报告 + 结论 JSON
  37b. 破坏性变更被阻断（退出码 1，CI 因此变红）
  37c. 严格模式不改变判定，只改变平台不可达时的行为
  37d. PR 回写：GitHub 与 GitLab 各一条 upsert（先创建、再次更新，不刷屏）
  37e. 回写打到各自平台的正确端点（GitHub issues/comments、GitLab MR notes）
  37f. 没有凭证时跳过而不是失败
  37g. 插件包与 CI 流水线都在仓库里（不再是「文档里说可以接」）

mvn -B -o test                  → 142 项 Java 单测全绿（含新增的数组绑定门禁 2 项）
python -m pytest -q             → 250 项全绿
python tools/ui_render_check.py → 10/10 标签页渲染通过（新增「引擎审计」页签）
python tools/dependency_audit.py → 0 条已知漏洞（退出码 0）
cd web && pnpm build            → 通过
```

### 19.5 本批修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | 引擎审计 `columns text[]` 用 `String[]` 绑定 | 摄入接口 500（第 5 次同类问题） | `TextArrays.of` + **源码门禁测试**（带反向自检） |
| 2 | 观测窗口按"整天"计算，刚接入一小时算作 0 天 | `usageDataAvailable` 恒为 false，**证据被当成没有数据** | 窗口同时给天与小时；判定改用"窗口是否覆盖授权生命周期"（`coversGrantLife`） |
| 3 | 最小权限复盘只看"窗口天数 ≥ 授权天数" | 刚批的授权会被标成"窗口内零访问 ⇒ 可回收"（危险的结论） | 加 30 天最小年龄门槛 + 精确的窗口覆盖判定 |
| 4 | e2e 夹具用 `psql -c` 传含中文的 SQL | Windows 控制台 GBK 导致 psql 报"非法 UTF-8"，**夹具静默没造出来**，断言看起来像产品缺陷 | 夹具 SQL 走 UTF-8 临时文件（`psql -f`）+ 失败时打印原因 |
| 5 | 回写脚本在无凭证时把空字符串当 token 传递 | 参数解析报错（usage error），"跳过"变成"失败" | 无凭证时不传该参数，并确保环境变量不残留 |
| 6 | 测试桩按全局计数判断"评论是否已存在" | GitHub 跑完后 GitLab 的首次调用被误判为"已存在" | 按路径分别计数 |
| 7 | 破坏性变更的测试用固定夹具构造 | 被历史状态左右（上次已登记 → 判定变成 BLOCK/WARN 随机） | 从接口读当前契约 → 删列 → 大版本 +1，**相对当前状态**构造破坏性变更 |

### 19.6 本批之后仍然未做（诚实清单）

1. **引擎审计只接受推送**，不主动读日志文件/数据库 —— 各企业落盘格式差异极大，
   属部署侧采集配置；提供了参考采集器但**未在真实 Trino/Ranger 上验证过**；
2. **CI 插件未在真实 GitHub/GitLab 实例上验证回写**（请求形状用本地 stub 验证过），
   也未做 Marketplace/组件仓库发布；
3. **`core.index-opensearch` 仍未实现**（本批未动）：当前 PG tsvector 是可替换实现，
   触发条件是实体规模超 500 万或需要真分词/kNN；
4. **`ai.semantic-search` 的向量路、`core.search-chinese` 的词典分词、
   `lineage.sql-parse` 的 L2 校验层、`lineage.visualization` 的时间轴回放等缺口未动** ——
   与 `ingestion.connectors-more`、`policy.compiler` 的 BI 下发一起构成下一批的内容。


## 20. Batch 7：dbt 连接器（把「最准的血缘」接进来）

`lineage.sql-parse` 的缺口清单里有一句："BI 工具内嵌 SQL 的自动提取（需各 BI 连接器）"；
`ingestion.connectors-more` 的缺口清单里有 "dbt manifest"。本批做 dbt，
因为它是**唯一一个不依赖 SQL 解析就能拿到精确血缘**的源。

### 20.1 交付与状态变化

| 能力 | 原状态 | 现状态 | 落地内容 |
|---|---|---|---|
| `ingestion.connectors-more` | 🟡 部分（5 个连接器） | 🟡 部分（**6 个**） | 新增 **dbt**：读 `target/manifest.json` → model/seed/snapshot/source 成为数据集资产（列、描述、物化方式、tags、路径）→ **编译期确定的血缘**（`depends_on`，置信度 1.0）+「模型 → 物化的物理表」边 |
| 采集框架 | — | 扩展 | `Source.extractEdges()` + `RawModels.RawEdge`：连接器可以**自带血缘**；`CollectionService` 统一落库并记录 `edgesWritten` / `edgeSkipNotes`（`sql/014_collect_edges.sql`） |

计数不变：**已实现 34 / 部分 7 / 未实现 1 / 合计 42**（本批是"部分实现的能力往前推进"，不是新增能力）。

### 20.2 四条设计决定

1. **dbt 模型建为独立资产，不合并进物理表。**
   这是本批最重要的一个决定，也是**在写代码前改掉的**：manifest 的 `columns` 通常只包含
   **被文档化过的列**，拿它去覆盖从数据库采集的真实 schema 是**降级**；
   而且 dbt 模型有自己的 owner、描述、物化方式与测试，合并进物理表会让这些信息无处安放。
   因此：`platform=dbt`、URN 里带项目名；source 声明则**优先解析到已采集的物理表**
   （这正是 source 的意义），解析不到才建 dbt 侧资产。

2. **血缘由连接器交出来，而不是所有源都去猜。**
   dbt 的 `depends_on` 是编译期事实，比事后解析 SQL 准得多（不受方言、宏、动态 SQL 影响）。
   为此在采集框架上加了一条通用能力：连接器可以产出边，落库与护栏仍由 `CollectionService` 统一处理。
   方向仍是全局约定 `上游 → 下游`，于是 dbt 链路读起来就是真实链路：
   `源表 → dbt 模型 → 物化的物理表 → 下一个 dbt 模型 → …`。

3. **解析不到的依赖要记账，不要静默。**
   夹具里故意放了一个平台不存在的 `unknown_table` 和一个不存在的 `missing_model`：
   前者不建边（不猜），后者进 `edgeSkipNotes`（"有 N 条依赖未解析到平台资产，这些边没有写入"）。
   运行记录里同时新增 `edgesWritten` 与 `edgeSkipNotes` 两列 —— 血缘也是采集产出，必须可观测。

4. **被护栏拦截的那一轮不写边。**
   否则会出现"目录没更新、血缘却变了"，两边对不上。

### 20.3 验证（可复现）

```
python tools/java_e2e_verify.py → 147/147 通过（Batch 6 的 139 项 + 本批 8 项）
  38a. dbt 连接器已登记且状态可见（不再是「计划中」，notImplemented 里也没有它）
  38b. 采集 manifest：3 个 dbt 资产 + 4 条血缘边 + 1 条跳过说明（第二次采集新建 1，幂等）
  38c. 解析不到的依赖跳过并记账（宁可缺边也不猜，但不能静默）
  38d. dbt 模型是独立资产（platform=dbt），带列与描述，不去覆盖物理表 schema
  38e. source 解析到已采集的物理表 → dbt 链路与物理血缘接得上（上游出现 event_log）
  38f. 「模型 → 物化的物理表」边存在（下游出现 alert_event / collector_state）
  38g. 血缘带来源与置信度（source=dbt_manifest、confidence=1.0、parseLevel=exact）
  38h. manifest 不存在时给出可操作的错误（400 + "先执行 dbt compile"）

mvn -B -o test                  → 150 项 Java 单测全绿（本批 +5：dbt 连接器纯逻辑 5 项）
python tools/ui_render_check.py → 10/10 标签页渲染通过
python -m pytest -q             → 250 项全绿
python tools/dependency_audit.py → 0 条已知漏洞（退出码 0）
cd web && pnpm build            → 通过
```

夹具：`tools/fixtures/dbt/manifest.json`（真实形态的 manifest：2 个模型 + 1 个测试 + 2 个 source，
其中一个 source 与一个上游依赖故意解析不到）。

### 20.4 本批修掉的真实缺陷

| # | 缺陷 | 后果 | 修正 |
|---|---|---|---|
| 1 | DSN `dbt:///D:/path` 解析出 `/D:/path` | `Path.of` 抛 `Illegal char <:>` —— 使用者完全看不出是自己写错了 DSN | 去掉 URL 形态路径在 Windows 上的前导斜杠 + URL 解码；新增单测 |
| 2 | **source 的键形式不一致**：manifest 的 `sources` 键用点号，`depends_on` 引用用冒号 | source 的上游边**全部解析不到**并被当成"跳过" —— 看起来像"没有血缘"，实际是键对不上 | 两种形式都登记（`registerUrn` 同时写 `source.a.b.c` 与 `source:a.b.c`），并在注释里说明为什么 |
| 3 | **自我引用的解析闭环**：dbt 第一轮建的 `unknown_table` 资产，第二轮被物理表解析器当成 source 的物理表 | 第二轮资产数从 3 掉到 2，血缘指向了连接器**自己产出的影子资产** | `resolveDatasetUrn(..., excludePlatform)`：解析物理表时排除连接器自身平台的资产（Superset 同样适用） |
| 4 | 测试夹具路径写死 `../tools/...` | Maven 从模块目录跑测试 → 找不到夹具，报错**看起来像"连接器坏了"** | 向上查找夹具目录（`locateFixture`），并说明为什么不写死相对层级 |
| 5 | 初版把 dbt 模型合并进物理表 | 会用 manifest 的**部分列**覆盖真实 schema（降级） | 改为独立资产（见 §20.2 第 1 条）——这条是设计评审阶段改掉的，记在这里是因为它本可以成为一个很难发现的线上问题 |

### 20.5 本批之后仍然未做（诚实清单）

1. **dbt 的列级血缘未做**：manifest 只给表级依赖；
   列级要把 `compiled_code` 交给 sqlglot 侧车（`POST /api/v1/lineage/parse` 已可用，但**没有做成批处理入口**）；
2. **未采集 dbt 的 tests / exposures / metrics**：tests 可以映射成质量规则、exposures 可以映射成 BI 资产、
   metrics 可接入语义层 —— 都是有价值但需要单独建模的部分，本轮没做；
3. **连接器缺口仍在**：MySQL / Trino / Hive-HMS / Airflow / SQLite / DuckDB / Tableau；
4. `ai.semantic-search` 向量路、`core.search-chinese` 词典分词、`lineage.sql-parse` 的 L2 校验层、
   `lineage.visualization` 的时间轴回放/聚合视图/列级路径、`policy.compiler` 的 BI 实际下发、
   `core.index-opensearch` 均未动 —— 留给后续批次。



