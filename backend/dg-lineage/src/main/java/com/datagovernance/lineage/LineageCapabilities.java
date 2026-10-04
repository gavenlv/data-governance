package com.datagovernance.lineage;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/** dg-lineage 的能力声明。 */
@Component
public class LineageCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("lineage.openlineage", "OpenLineage 事件接收", "D3 血缘",
                        "docs/08 §7.1", "接收作业运行时事件；columnLineage facet 做方向反转（输出列→输入列）"),
                CapabilityDescriptor.implemented("lineage.column-graph", "列级血缘图", "D3 血缘",
                        "docs/09 §9.2", "数据集级与列级边，带来源/置信度/转换类型/控制依赖标记"),
                CapabilityDescriptor.implemented("lineage.quality-report", "血缘质量报告", "D3 血缘",
                        "docs/09 §9.2", "回答「覆盖率为什么低」：区分「没采集」与「解析不出来」"),
                CapabilityDescriptor.partial("lineage.sql-parse", "SQL 静态解析（sqlglot 侧车）", "D3 血缘",
                        "docs/10 §2", "Phase 1",
                        "Python 侧车（sqlglot，20 方言）解析 SQL → Java 控制面解析 URN 后写入表级/列级血缘；"
                                + "解析不够好的结果落 lineage_parse_sample；侧车不可用返回 502 而非空血缘",
                        List.of("已实现：侧车部署（python -m dg.cli sidecar，127.0.0.1:8099）+ Java 真实调用 + 入库 + 样本登记",
                                "未实现：L2 二次校验层（Calcite）—— SELECT * 展开、JOIN 列歧义、隐式类型转换的复核",
                                "未实现：BI 工具内嵌 SQL 的自动提取（需各 BI 连接器，属 ingestion.connectors-more）")),
                CapabilityDescriptor.implemented("lineage.impact-analysis", "影响分析 / 爆炸半径", "D3 血缘",
                        "docs/09 §9.2",
                        "给定变更对象返回受影响资产清单，按 score(v)=w(v)·α^depth(v) 排序；"
                                + "评分含分级/关键标签/Owner/下游数四项，显式排除使用热度（无查询日志接入，故不静默按 0 计）；"
                                + "深度边界处仍有下游时会显式标注「真实影响面更大」"),
                CapabilityDescriptor.partial("lineage.visualization", "血缘可视化画布", "D3 血缘",
                        "docs/14 §3.3", "Phase 1",
                        "交互式血缘探索器（Cytoscape + dagre）：**线型 = 可信度**"
                                + "（实线=运行时/人工、虚线=静态解析、点线=推断、灰线=过期），线宽 ∝ 置信度；"
                                + "节点按实体类型/分级着色；单击看详情、双击重新聚焦；**路径高亮**；"
                                + "每条边可**确认**（置信度升到人工级）或**驳回**（标记而非删除）；"
                                + "服务端裁剪（深度/置信度/列级/控制依赖/节点上限）并**显式回报**截断情况",
                        List.of("未实现：时间轴回放（\"三个月前的血缘\"）—— 需要边的 valid_from/valid_to 版本化",
                                "未实现：节点数超上限时的聚合视图（当前会显式提示并建议收窄条件，而不是画出一团糊）",
                                "未实现：列的端到端路径追踪视图（A.col1 → B.col2 → C.metric 的转换链路）",
                                "未实现：血缘健康 / 未确认血缘队列（docs/14 §2）")));
    }
}
