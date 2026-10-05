package com.datagovernance.ingestion;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/** dg-ingestion 的能力声明。 */
@Component
public class IngestionCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("ingestion.framework", "采集框架", "D1 元数据底座",
                        "docs/09 §9.1", "Source→Normalizer→Sink 流水线；连接器只读不写，编排统一复用"),
                CapabilityDescriptor.implemented("ingestion.postgres", "PostgreSQL 连接器", "D1 元数据底座",
                        "docs/09 §9.1", "information_schema + obj_description/col_description，含主键与注释"),
                CapabilityDescriptor.implemented("ingestion.guard", "采集护栏", "D1 元数据底座",
                        "docs/09 §9.1", "删除检测 + 实体数骤降中止 + 命名空间隔离；拦截时不更新基线"),
                CapabilityDescriptor.implemented("ingestion.health", "采集健康度", "D8 治理运营",
                        "docs/09 §9.1", "collect_run / collector_state：运行历史、连续失败、陈旧度"),
                CapabilityDescriptor.partial("ingestion.connectors-more", "更多连接器", "D1 元数据底座",
                        "docs/11 §1.1", "Phase 1",
                        "已实现 5 个连接器：PostgreSQL、ClickHouse（HTTP 接口 + 分区/排序键）、"
                                + "MongoDB（无 schema → 采样推断）、BigQuery（REST + 自签 JWT）、"
                                + "Superset（BI 资产：仪表板 + 图表 + readsFrom 血缘）。"
                                + "前四个产出数据集，Superset 产出 BI 资产",
                        List.of("已对真实系统验证：PostgreSQL / ClickHouse / MongoDB / Superset（见 tools/java_e2e_verify.py）",
                                "**未对真实系统验证：BigQuery**（无项目凭据；代码按 REST v2 + RS256 JWT 实现，"
                                        + "缺凭据时给出可读错误而不是静默返回空数据集）",
                                "未实现：MySQL / Trino / Hive-HMS / dbt manifest / Airflow / SQLite / DuckDB / Tableau",
                                "连接器状态由 ConnectorRegistry 单点声明，界面与接口共用（避免'界面上有、实际不支持'）")),
                CapabilityDescriptor.implemented("ingestion.edge-agent", "Edge Agent（推模式）", "D1 元数据底座",
                        "ADR-012",
                        "**控制面侧已实现**：Agent 注册（一次性下发凭据，库里只存哈希）、心跳、"
                                + "数据集+列上报（走同一套 URN 形状与来源保护 AUTO_COLLECTED，自动进入检索索引与血缘图）、"
                                + "凭据吊销（立即生效且保留历史上报记录）、上报记录可查（被拒必带原因，"
                                + "**超限拒绝而不截断** —— 截断会让人误以为推成功了）",
                        List.of("已实现：推模式的**协议与控制面**（e2e 覆盖 注册→心跳→上报→吊销→拒收超限）",
                                "**未实现：ADR-012 选型的 Go 单二进制 Agent 本体** —— 它需要独立的构建与发布流水线，"
                                        + "不在本仓库范围内；任何能发 HTTP 的采集器（脚本 / cron / k8s Job）现在就能用",
                                "未实现：gRPC 流式上报、Agent 侧本地缓存与断点续传、Agent 自动升级",
                                "安全设计：Agent 凭据只在下发时返回一次；吊销只改状态、不删历史上报（可追溯）")),
                CapabilityDescriptor.implemented("ingestion.bi-assets", "BI 资产采集（仪表板）", "D1 元数据底座",
                        "docs/09 §9.1",
                        "BI 资产作为一等实体（Dashboard + dashboardSpec）：图表清单、URL、依赖数据集、Owner；"
                                + "血缘方向为 数据集 --consumedBy--> 报表（from=上游、to=下游），"
                                + "因此「改这张表哪些看板受影响」直接复用影响分析；"
                                + "仪表板有独立的快照 scope 与护栏（BI churn 高，删报表不该拦停数据采集）；"
                                + "虚拟数据集不猜血缘（交由 sqlglot 解析 SQL）"),
                CapabilityDescriptor.implemented("ingestion.scheduler", "内置调度器", "D1 元数据底座",
                        "docs/09 §9.1",
                        "cron 调度采集任务（5 段/6 段均可，自动归一化）；互斥用 pg_try_advisory_lock"
                                + "（随连接释放、崩溃不留死锁，多副本安全）；每次执行写 collect_run 可被观测；"
                                + "凭证不落明文（env: 引用，secret: 显式拒绝）"));
    }
}
