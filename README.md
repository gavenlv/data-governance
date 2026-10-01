# 通用数据治理平台（Data Governance Platform）

> **当前阶段：调研与设计（Design Only）。尚未开始实现。**
> 本仓库当前只包含调研报告与方案设计文档。

## 这是什么

一个自建的、通用的数据治理平台方案。目标是覆盖从「元数据底座 → 数据目录与发现 → 血缘与影响分析 → 数据质量与可观测性 → 数据契约 → 分类分级与合规 → 访问治理与策略执行 → 治理运营与 AI」的完整闭环。

设计立场（详见 `docs/00-overview.md`）：

1. 元数据是产品，不是文档（要被 CI / 查询引擎 / BI / AI Agent 消费）
2. 单一真相源 + 可重建派生视图（拒绝多处平级双写）
3. 治理即代码（契约 / 策略 / 规则 / 术语 Git 化 + CI 门禁）
4. 联邦治理（Domain / Data Product 是一等公民）
5. AI 产出必须可审计（建议 + 证据 + 人工确认，绝不静默写入）
6. 开放标准优先（OpenLineage / ODCS / Iceberg REST / MCP）

## 按角色推荐阅读路径

| 角色 | 建议顺序 | 预计用时 |
|---|---|---|
| **决策者 / CTO** | `00-overview.md` → `12-build-vs-extend-and-adr.md`（第一、四部分）→ `17-critical-review.md` | 30–40 分钟 |
| **架构师** | `00` → `07` → `08` → `09` → `10` → `12`（ADR 部分） | 2–3 小时 |
| **平台工程师** | `10`（选型）→ `09`（子系统）→ `08`（模型）→ `research/04` 及其附录（实现细节） | 3–4 小时 |
| **产品 / UX** | `00` → `14` → `11` → `06` | 1 小时 |
| **数据治理负责人** | `18`（制度）→ `11`（采用路径）→ `09` §9.6–9.9 → `research/03` §7 | 1.5 小时 |
| **需要做技术验证的人** | `15-spike-and-validation-plan.md` → `research/04` | 1 小时 |

## 文档导航

**先读这两份**：

- [`docs/00-overview.md`](docs/00-overview.md) — 总体方案与执行摘要（一页纸看懂）
- [`docs/12-build-vs-extend-and-adr.md`](docs/12-build-vs-extend-and-adr.md) — 自研 vs 二开 vs 采购决策框架 + 架构决策记录（ADR）

**调研报告**（`docs/research/`）：

| 文档 | 内容 |
|---|---|
| [`01-openmetadata.md`](docs/research/01-openmetadata.md) | OpenMetadata：模型、架构、优缺点、可借鉴点 |
| [`02-datahub.md`](docs/research/02-datahub.md) | LinkedIn DataHub：Aspect/事件驱动模型、架构与取舍 |
| [`03-commercial-vendors.md`](docs/research/03-commercial-vendors.md) | 商业厂商对比（Collibra / Alation / Informatica / Atlan / Purview / DataZone / 国内厂商） |
| [`04-standards-and-tech.md`](docs/research/04-standards-and-tech.md) | 标准与技术原理（OpenLineage、ODCS、列级血缘、图存储、授权模型、质量算法、AI 应用） |
| [`05-oss-landscape.md`](docs/research/05-oss-landscape.md) | 开源生态盘点与「可直接复用 / 生态空白点」 |

**设计方案**：

| 文档 | 内容 |
|---|---|
| [`06-competitive-synthesis.md`](docs/06-competitive-synthesis.md) | 竞品综合对比矩阵与「取各所长」清单 |
| [`07-platform-architecture.md`](docs/07-platform-architecture.md) | 总体架构、运行时形态、关键数据流、Non-Goals、SLO |
| [`08-metadata-model.md`](docs/08-metadata-model.md) | 元数据模型（URN / Entity / Aspect / Edge、版本审计、扩展机制） |
| [`09-core-subsystems.md`](docs/09-core-subsystems.md) | 九大核心子系统设计 |
| [`10-tech-stack.md`](docs/10-tech-stack.md) | 技术选型、容量规划、安全、测试、反选型 |
| [`11-roadmap-mvp.md`](docs/11-roadmap-mvp.md) | MVP 边界、阶段路线图、验收标准、组织与采用路径 |
| [`12-build-vs-extend-and-adr.md`](docs/12-build-vs-extend-and-adr.md) | Build vs Extend vs Buy + 12 条 ADR + 开放问题 |
| [`13-ai-native-layer.md`](docs/13-ai-native-layer.md) | AI 原生治理层设计 |
| [`14-ux-information-architecture.md`](docs/14-ux-information-architecture.md) | 产品信息架构与交互设计 |
| [`15-spike-and-validation-plan.md`](docs/15-spike-and-validation-plan.md) | 立项前技术验证计划（Spike） |
| [`16-integration-and-migration.md`](docs/16-integration-and-migration.md) | 集成地图与迁移/共存评估 |
| [`17-critical-review.md`](docs/17-critical-review.md) | 方案自我批判与精简建议 |
| [`18-governance-operating-model.md`](docs/18-governance-operating-model.md) | 治理制度与运营模型 |
| [`19-independent-review.md`](docs/19-independent-review.md) | **独立评审报告**（对抗性评审：3 个 Blocker、9 条事实问题、7 项过度设计、8 项遗漏） |
| [`20-commercial-case-and-operations.md`](docs/20-commercial-case-and-operations.md) | 商业论证与运维补充（TCO/ROI、DR、团队、合规，以及对评审的正式回应） |

## 当前状态

- [x] 竞品与生态调研（开源 + 商业 + 标准与技术）
- [x] 总体架构设计与核心技术选型
- [x] 元数据模型设计
- [x] 路线图与 MVP 边界
- [ ] 评审与决策（见 `docs/00-overview.md` §10 待拍板事项）
- [ ] 原型验证（未开始）
- [ ] 实现（未开始）

## 免责说明

调研文档中的产品功能、版本号、社区规模等**易变事实**来自公开资料检索；受检索环境限制，部分数据标注为「待核实」，正式引用前请以官方文档为准。设计文档中的容量与成本数字为量级估算，非承诺。
