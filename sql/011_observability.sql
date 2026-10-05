-- ============================================================================
-- 数据可观测性：异常检测、SLO、事故（docs/09 §9.4）
--
-- 三张表分别回答三个不同的问题，刻意不合并：
--   anomaly_detection —— "这个指标现在不正常吗"（统计判定，带方法与阈值口径）
--   slo_definition / slo_snapshot —— "我们承诺的服务水平守住了吗"（达成率时序）
--   incident / incident_event —— "出事了，谁在处理、影响谁、怎么复盘的"（协作与闭环）
--
-- 设计要点（来自 docs/09 §9.4）：
--   1. **误报率是第一优先级指标**：因此每条检测都要存"用了什么方法、什么阈值、样本多少"，
--      误报才能被回溯到方法而不是被当成玄学；
--   2. **上游告警抑制下游**：这是治理平台相对纯可观测性产品的独有优势（同时掌握血缘），
--      因此 detection 里有 propagation 字段记录"这条是源侧还是被传播的"；
--   3. **每次故障必须产出一条规则或检测器**：incident 里有 closed_loop_rule_urn 强制这个闭环。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. anomaly_detection：异常检测结果
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS anomaly_detection (
    id             BIGSERIAL   PRIMARY KEY,
    dataset_urn    TEXT        NOT NULL,
    column_name    TEXT,
    metric         TEXT        NOT NULL,
    window_start   TIMESTAMPTZ NOT NULL,
    observed       DOUBLE PRECISION,
    baseline       DOUBLE PRECISION,
    score          DOUBLE PRECISION,          -- 稳健 Z 分数（MAD）或相对偏差
    method         TEXT        NOT NULL,      -- static_threshold | mad | seasonal_mad
    threshold      DOUBLE PRECISION,
    severity       TEXT        NOT NULL DEFAULT 'MEDIUM',
    -- 传播判定：SOURCE（源侧异常）| PROPAGATED（由上游传播）| ISOLATED（无上游异常）
    propagation    TEXT        NOT NULL DEFAULT 'ISOLATED',
    propagated_from TEXT,                     -- 传播来源的 dataset URN（仅 PROPAGATED 时）
    samples        INTEGER     NOT NULL DEFAULT 0,
    suppressed     BOOLEAN     NOT NULL DEFAULT FALSE,
    suppress_reason TEXT,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT anomaly_method_chk CHECK (method IN ('static_threshold', 'mad', 'seasonal_mad')),
    CONSTRAINT anomaly_propagation_chk CHECK (propagation IN ('SOURCE', 'PROPAGATED', 'ISOLATED')),
    CONSTRAINT anomaly_severity_chk CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_anomaly_detection
    ON anomaly_detection (dataset_urn, COALESCE(column_name, ''), metric, window_start, method);
CREATE INDEX IF NOT EXISTS idx_anomaly_detection_recent ON anomaly_detection (detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_anomaly_detection_open
    ON anomaly_detection (suppressed, severity, detected_at DESC);

-- ---------------------------------------------------------------------------
-- 2. slo_definition / slo_snapshot：服务级目标与达成率
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS slo_definition (
    id             BIGSERIAL   PRIMARY KEY,
    name           TEXT        NOT NULL UNIQUE,
    description    TEXT,
    slo_type       TEXT        NOT NULL,      -- freshness | quality_pass_rate | availability | schema_stability
    target         DOUBLE PRECISION NOT NULL, -- 目标（0–1 之间；freshness 用秒数上限时另存 threshold_seconds）
    threshold_seconds INTEGER,                -- freshness 类目标（秒）
    window_days    INTEGER     NOT NULL DEFAULT 30,
    resource_scope JSONB       NOT NULL DEFAULT '{}'::jsonb,
    owner          TEXT,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT slo_type_chk CHECK (slo_type IN ('freshness', 'quality_pass_rate', 'availability', 'schema_stability')),
    CONSTRAINT slo_target_chk CHECK (target > 0 AND target <= 1),
    CONSTRAINT slo_status_chk CHECK (status IN ('ACTIVE', 'PAUSED', 'RETIRED'))
);

CREATE TABLE IF NOT EXISTS slo_snapshot (
    id             BIGSERIAL   PRIMARY KEY,
    slo_name       TEXT        NOT NULL,
    measured_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    window_start   TIMESTAMPTZ NOT NULL,
    total_events   INTEGER     NOT NULL DEFAULT 0,
    good_events    INTEGER     NOT NULL DEFAULT 0,
    attainment     DOUBLE PRECISION NOT NULL DEFAULT 0,
    met            BOOLEAN     NOT NULL DEFAULT FALSE,
    error_budget_remaining DOUBLE PRECISION,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_slo_snapshot_name ON slo_snapshot (slo_name, measured_at DESC);

-- ---------------------------------------------------------------------------
-- 3. incident / incident_event：事故与时间线
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS incident (
    id             BIGSERIAL   PRIMARY KEY,
    title          TEXT        NOT NULL,
    severity       TEXT        NOT NULL DEFAULT 'MEDIUM',
    status         TEXT        NOT NULL DEFAULT 'OPEN',
    primary_urn    TEXT,                      -- 主资产（源侧）
    affected_urns  TEXT[]      NOT NULL DEFAULT '{}',
    source         TEXT        NOT NULL DEFAULT 'manual',  -- manual | anomaly | slo | contract_violation
    source_ref     TEXT,                      -- 关联的检测/违约/SLO 标识
    owner          TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    detected_at    TIMESTAMPTZ,
    resolved_at    TIMESTAMPTZ,
    -- 闭环：每次事故必须沉淀出一条规则/检测器（docs/09 §9.4）
    closed_loop_rule_urn TEXT,
    postmortem     JSONB,
    created_by     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT incident_severity_chk CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT incident_status_chk CHECK (status IN ('OPEN', 'MITIGATING', 'RESOLVED', 'POSTMORTEM_DONE')),
    CONSTRAINT incident_source_chk CHECK (source IN ('manual', 'anomaly', 'slo', 'contract_violation'))
);
CREATE INDEX IF NOT EXISTS idx_incident_status ON incident (status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_incident_primary ON incident (primary_urn);

CREATE TABLE IF NOT EXISTS incident_event (
    id             BIGSERIAL   PRIMARY KEY,
    incident_id    BIGINT      NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    event_type     TEXT        NOT NULL,      -- DETECTED | ACKNOWLEDGED | UPDATE | MITIGATED | RESOLVED | POSTMORTEM | RULE_CREATED
    message        TEXT        NOT NULL,
    actor          TEXT,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_incident_event ON incident_event (incident_id, occurred_at);
