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
                CapabilityDescriptor.partial("lineage.sql-parse", "SQL 静态解析（sqlglot 侧车 + L2 校验层）",
                        "D3 血缘", "docs/10 §2", "Phase 1",
                        "Python 侧车（sqlglot，20 方言）解析 SQL → Java 控制面解析 URN 后写入表级/列级血缘；"
                                + "解析不够好的结果落 lineage_parse_sample；侧车不可用返回 502 而非空血缘；"
                                + "**L2 校验层**用平台已采集的 schema 补出解析器做不到的部分"
                                + "（SELECT * 展开、无表限定列消歧），推不出来的登记为血缘检查发现",
                        List.of("已实现：侧车部署（python -m dg.cli sidecar，127.0.0.1:8099）+ Java 真实调用 + 入库 + 样本登记",
                                "已实现：**L2 校验层** —— SELECT * 用平台 schema 展开成列级边（来源 sql_parse_l2、"
                                        + "parseLevel=derived、置信度 0.65，**与 exact 边可区分**）；"
                                        + "无表限定列只在唯一命中时消歧；多义则记账不猜；"
                                        + "「缺 schema」与「解析失败」分开记账（前者能靠采集补上，后者不能）",
                                "已实现：检查发现可查（GET /api/v1/lineage/checks）并按类型统计，"
                                        + "区分「能补的」（缺 schema → 采集即可）与「需改 SQL 的」（列有歧义）",
                                "**未实现：BI 工具内嵌 SQL 的自动提取** —— 需要各 BI 连接器把图表 SQL 交出来"
                                        + "（Superset 已有连接器但只采数据集与看板，未提取图表 SQL）",
                                "**未实现：Calcite 式的完整语义校验**（隐式类型转换、视图展开的深层复核）——"
                                        + "当前 L2 只做「用平台 schema 补输入」这一类，不做 SQL 语义求解")),
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
