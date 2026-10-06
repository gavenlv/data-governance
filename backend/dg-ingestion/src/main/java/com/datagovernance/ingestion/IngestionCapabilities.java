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
                CapabilityDescriptor.implemented("ingestion.datasource-registry", "数据源管理（连接复用 + 凭据加密）",
                        "D1 元数据底座",
                        "docs/09 §9.1",
                        "数据源连接可保存、编辑、测试与一键扫描（POST /api/v1/datasources/{id}/scan）："
                                + "连接只录一次，之后扫描/测试都不必重敲 DSN 与口令；"
                                + "编辑时未填写的凭据字段保持原值",
                        List.of("凭据以 **AES-256-GCM** 加密后单独存一列（sql/015_datasource.sql），"
                                        + "密钥来自环境变量 DG_SECRET_KEY 且**不在库内**："
                                        + "数据库备份泄漏不等于凭据泄漏",
                                "**未配置 DG_SECRET_KEY 时保存连接返回 502 并说明如何生成密钥**，"
                                        + "刻意不退化成明文存储或内置弱密钥 —— "
                                        + "静默降级会让人以为凭据已加密而其实没有",
                                "接口永不回显凭据：只返回 hasCredentials 与**已脱敏**的 endpoint"
                                        + "（去掉 user:password@ 与 ?password= 一类参数）",
                                "扫描完全复用既有采集链路（连接器注册表 + 护栏 + 快照 + 运行记录 + 事件流），"
                                        + "不存在第二条采集路径",
                                "权限独立于 asset:read：连接信息属采集运维面，"
                                        + "READER 不可见、STEWARD 只读、EDITOR/ADMIN 可写",
                                "未实现：连接凭据的外部密钥管理（KMS/Vault）与轮换审计，"
                                        + "当前是单密钥静态配置")),
                CapabilityDescriptor.implemented("ingestion.postgres", "PostgreSQL 连接器", "D1 元数据底座",
                        "docs/09 §9.1", "information_schema + obj_description/col_description，含主键与注释"),
                CapabilityDescriptor.implemented("ingestion.guard", "采集护栏", "D1 元数据底座",
                        "docs/09 §9.1", "删除检测 + 实体数骤降中止 + 命名空间隔离；拦截时不更新基线"),
                CapabilityDescriptor.implemented("ingestion.health", "采集健康度", "D8 治理运营",
                        "docs/09 §9.1", "collect_run / collector_state：运行历史、连续失败、陈旧度"),
                CapabilityDescriptor.partial("ingestion.connectors-more", "更多连接器", "D1 元数据底座",
                        "docs/11 §1.1", "Phase 1",
                        "已实现 6 个连接器：PostgreSQL、ClickHouse（HTTP 接口 + 分区/排序键）、"
                                + "MongoDB（无 schema → 采样推断）、BigQuery（REST + 自签 JWT）、"
                                + "Superset（BI 资产：仪表板 + 图表 + readsFrom 血缘）、"
                                + "**dbt**（读 manifest.json：model/seed/snapshot/source 成为独立资产，"
                                + "并产出**编译期确定的血缘** depends_on，同时建立「模型 → 物化的物理表」边）。"
                                + "数据源连接器产出数据集，Superset 产出 BI 资产，dbt 两者兼有",
                        List.of("已对真实系统验证：PostgreSQL / ClickHouse / MongoDB / Superset / **dbt**"
                                        + "（dbt 用真实形态的 manifest.json 夹具 + 端到端验证；见 tools/java_e2e_verify.py 38a–38h）",
                                "**未对真实系统验证：BigQuery**（无项目凭据；代码按 REST v2 + RS256 JWT 实现，"
                                        + "缺凭据时给出可读错误而不是静默返回空数据集）",
                                "未实现：MySQL / Trino / Hive-HMS / Airflow / SQLite / DuckDB / Tableau",
                                "dbt 的**列级**血缘未做：manifest 只给表级依赖，列级需把 compiled_code 交给 "
                                        + "sqlglot 侧车（POST /api/v1/lineage/parse 已可用）",
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
