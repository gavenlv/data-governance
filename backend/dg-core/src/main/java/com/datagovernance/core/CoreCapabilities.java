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
                CapabilityDescriptor.implemented("core.event-outbox", "事件流（outbox）", "D1 元数据底座",
                        "ADR-002", "业务写入与 event_log 同事务提交，派生视图可重放重建"),
                CapabilityDescriptor.implemented("core.lineage-graph", "血缘边与图遍历", "D3 血缘",
                        "docs/09 §9.2", "边带来源/置信度/时效；UNION 去环、深度有界、排除控制依赖"),
                CapabilityDescriptor.partial("core.codegen", "模型→代码生成", "D1 元数据底座",
                        "docs/08 §6", "Phase 1",
                        "由模型定义生成 Python/TypeScript/JSON Schema",
                        List.of("Python 参考实现已提供 dgctl codegen；Java 侧生成器未实现",
                                "Java 与 TS 类型目前由构建期脚本从同一份 YAML 派生，尚未纳入 CI 门禁")),
                CapabilityDescriptor.implemented("core.search-index", "搜索索引消费者", "D2 目录与发现",
                        "docs/09 §9.3",
                        "从事件流构建可重放重建的检索视图（search_doc）；标识符切分（_/驼峰）+ 前置授权过滤 + "
                                + "索引水位可见；POST /api/v1/index/rebuild 可证明派生视图确实可丢弃重建"),
                CapabilityDescriptor.notImplemented("core.search-chinese", "中文全文检索", "D2 目录与发现",
                        "docs/09 §9.3", "Phase 2",
                        "中文分词（pg_jieba/zhparser 或 OpenSearch+IK）、同义词（术语表作同义词源）、拼音别名",
                        List.of("当前用 PG simple 配置：标识符切分可用，**中文按字切分不可用**",
                                "已固化为已知限制（docs/21 §6）；补分词器或迁 OpenSearch 后该限制解除",
                                "向量检索与 RRF 融合排序属 ai.semantic-search（Batch 5）")),
                CapabilityDescriptor.notImplemented("core.index-opensearch", "OpenSearch 索引后端", "D2 目录与发现",
                        "docs/10 §3.2", "Phase 2",
                        "把检索索引从 PG tsvector 换成 OpenSearch（alias 零停机切换、IK 分词、kNN 向量）",
                        List.of("当前 PG tsvector 版本是**可替换实现**：消费者与查询接口不变，换后端不影响核心",
                                "触发条件：实体规模超 500 万 或 需要中文分词/向量检索（docs/07 §3.2）")));
    }
}
