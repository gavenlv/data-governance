package com.datagovernance.ingestion;

import java.util.stream.Stream;

/**
 * 连接器基类（docs/09 §9.1）。
 *
 * <p>框架与连接器的边界是刻意的：新增一个源系统只需要实现本接口，
 * 采集编排、护栏、状态快照、运行记录、事件发布全部由
 * {@link CollectionService} 复用 —— 这就是「框架而不是堆代码」的含义。
 *
 * <p>连接器分两类输出：
 * <ul>
 *   <li>{@link #extract()} —— <b>数据集</b>（表 / 集合 / 外部表）：参与护栏与快照；</li>
 *   <li>{@link #extractDashboards()} —— <b>BI 资产</b>（仪表板 / 看板）：不参与数据集护栏
 *       （数量骤降的含义不同），但会建 readsFrom 血缘、写 ownership 与 dashboardSpec。</li>
 * </ul>
 * 已实现的连接器见 {@code ConnectorRegistry}；未实现的源在
 * {@code /api/v1/capabilities} 与界面中标注为「未实现」。
 */
public interface Source {

    /** 连接器名（出现在采集运行记录里）。 */
    String name();

    /** 平台标识（进入 URN）。 */
    String platform();

    /** 产出原始元数据。实现方只读不写。 */
    Stream<RawModels.RawDataset> extract();

    /** 该连接器是否也产出 BI 资产。 */
    default boolean supportsDashboards() {
        return false;
    }

    /** 产出 BI 资产（默认无）。 */
    default Stream<RawModels.RawDashboard> extractDashboards() {
        return Stream.empty();
    }
}
