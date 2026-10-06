package com.datagovernance.core;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/** dg-core 的能力声明。 */
@Component
public class CoreCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("core.model-registry", "元数据模型注册表", "D1 元数据底座",
                        "docs/08 §6", "加载 model/**.yaml，提供实体/aspect/关系的校验与查询"),
                CapabilityDescriptor.implemented("core.model-compat", "模型兼容性检查", "D1 元数据底座",
                        "docs/08 §6", "新旧模型对比，阻断删除属性/改类型/收紧枚举等破坏性变更（CI 强制）"),
                CapabilityDescriptor.implemented("core.entity-aspect", "实体与 Aspect 读写", "D1 元数据底座",
                        "docs/08 §4", "Aspect 为变更最小单位；版本历史 + 乐观锁 + 审计"),
                CapabilityDescriptor.implemented("core.source-protection", "采集不覆盖人工内容", "D1 元数据底座",
                        "ADR-005", "字段级来源优先级 MANUAL>IMPORTED>AI>COLLECTED，人工内容受保护"),
                CapabilityDescriptor.implemented("core.run-rollback", "采集批次回滚", "D1 元数据底座",
                        "ADR-005", "按 runId 整体回退：恢复被更新的 aspect、删除该批次新建的 aspect"),
                CapabilityDescriptor.implemented("core.asset-versioning", "资产版本管理与回滚", "D1 元数据底座",
                        "docs/08 §4",
                        "aspect 每次实质变更都归档到 aspect_history：资产级版本时间线（GET "
                                + "/api/v1/assets/{urn}/versions）、查看任一历史版本、与当前版本 diff、"
                                + "按版本回滚（POST .../aspects/{type}/rollback?version=N）",
                        List.of("版本链只增不减：回滚是**追加新版本**（内容等于目标版本），"
                                        + "历史版本永不被删除或覆盖，因此审计轨迹始终完整",
                                "回滚直接还原 data 与 field_sources，**不走字段级来源合并** —— "
                                        + "使用者要求'回到第 N 版'时，得到半新半旧的混合体比不回滚更难理解",
                                "无实质变化的写入不产生新版本（内容指纹幂等），"
                                        + "因此版本链上不会出现一串'什么都没改'的空版本")),
                CapabilityDescriptor.implemented("core.event-outbox", "事件流（outbox）", "D1 元数据底座",
                        "ADR-002", "业务写入与 event_log 同事务提交，派生视图可重放重建"),
                CapabilityDescriptor.implemented("core.lineage-graph", "血缘边与图遍历", "D3 血缘",
                        "docs/09 §9.2", "边带来源/置信度/时效；UNION 去环、深度有界、排除控制依赖"),
                CapabilityDescriptor.partial("core.codegen", "模型→代码生成", "D1 元数据底座",
                        "docs/08 §6", "Phase 1",
                        "由同一份 model/**.yaml 生成 Python 数据类 / TypeScript 类型 / JSON Schema；"
                                + "生成物带「勿手改」头；本批次新增的 Metric 实体、metricSpec 等 4 个 aspect、"
                                + "consumedBy 关系边都由它重新生成，未手写类型",
                        List.of("已实现：dgctl codegen（生成）+ dgctl codegen --check（校验生成物是否为最新）",
                                "已实现：**CI 流水线**（.github/workflows/ci.yml）—— 模型校验、codegen --check、"
                                        + "Java 单测、前端构建、Python 测试、供应链扫描、端到端，全部作为门禁步骤",
                                "**未实现：Java 侧生成器** —— Java 只做运行期模型校验（ModelRegistry），不生成代码。"
                                        + "生成只有一个来源（Python 的模型工具链），避免两套生成器漂移。"
                                        + "这是**有意的取舍**而不是缺口：两份生成器一定会漂移，而漂移的生成物比不生成更危险",
                                "验收：CI 里 codegen --check 是门禁步骤（模型改了没重新生成 → 流水线红）")),
                CapabilityDescriptor.implemented("core.search-index", "搜索索引消费者", "D2 目录与发现",
                        "docs/09 §9.3",
                        "从事件流构建可重放重建的检索视图（search_doc）；标识符切分（_/驼峰）+ 前置授权过滤 + "
                                + "索引水位可见；POST /api/v1/index/rebuild 可证明派生视图确实可丢弃重建"),
                CapabilityDescriptor.partial("core.search-chinese", "中文检索", "D2 目录与发现",
                        "docs/09 §9.3", "Phase 2",
                        "中文走 **bigram（二元切分）** 而非按字切分：多字中文标识符（如「客户订单明细」）"
                                + "被切成重叠的二元组写入索引，查询侧同样切分，因此连续中文串能命中；"
                                + "英文标识符仍按 _ / 驼峰切分",
                        List.of("已实现：bigram 切分 + 查询侧同构切分（e2e 覆盖中文串能召回）",
                                "**未实现：词典分词**（pg_jieba / zhparser / IK）—— bigram 的正确率低于真分词，"
                                        + "会带来部分误召回（如「订单」与「单明」）；这是精度换可用性的折中，不是最终形态",
                                "未实现：拼音别名、繁简归一、词典同义词（术语表扩展目前只在 ai.semantic-search 里做）",
                                "升级路径：装 pg_jieba/zhparser 后把切分函数换成词典分词，索引与查询两侧同改即可")),
                CapabilityDescriptor.notImplemented("core.index-opensearch", "OpenSearch 索引后端", "D2 目录与发现",
                        "docs/10 §3.2", "Phase 2",
                        "把检索索引从 PG tsvector 换成 OpenSearch（alias 零停机切换、IK 分词、kNN 向量）",
                        List.of("当前 PG tsvector 版本是**可替换实现**：消费者与查询接口不变，换后端不影响核心",
                                "触发条件：实体规模超 500 万 或 需要中文分词/向量检索（docs/07 §3.2）")));
    }
}
