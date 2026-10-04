-- ============================================================================
-- 质量子系统（docs/09 §9.4，Batch 2）
--
-- 三条设计约束落在这里：
--   1. **精度来源必须随值一起存**（precision / precision_source）：
--      元数据级统计（MySQL TABLE_ROWS、Oracle NUM_ROWS、Iceberg 统计）都是估算值，
--      与 profiling 实算可能有量级差异。不标注精度就会有人拿估算值做决策，
--      或者基于估算值触发误报（research/04 补充 26）。
--   2. **结果存时序表**：唯一键 (dataset, column, metric, window)，趋势、健康分、SLO 共用同一份数据。
--   3. **规则执行必须留痕**（rule_run）：观察值、期望、编译出的 SQL 都要存，
--      否则"规则失败了"无法复盘到底是数据变了还是规则写错了。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. profile_metric：剖析指标（时序）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS profile_metric (
    id               BIGSERIAL   PRIMARY KEY,
    dataset_urn      TEXT        NOT NULL,
    column_name      TEXT,                      -- NULL 表示表级指标（如 row_count）
    metric           TEXT        NOT NULL,      -- row_count | null_count | distinct_count | min | max | p50 ...
    window_start     TIMESTAMPTZ NOT NULL,
    value_num        DOUBLE PRECISION,
    value_json       JSONB,
    -- 精度：EXACT（profiling 实算）| ESTIMATED（来自源系统元数据/湖仓统计）
    precision        TEXT        NOT NULL DEFAULT 'EXACT',
    precision_source TEXT,
    -- 采样可追溯：方法 + 比例（"优先哈希取模，TABLESAMPLE 次之，LIMIT 头部采样不用"）
    sampling_method  TEXT,
    sample_ratio     REAL,
    row_count        BIGINT,
    run_id           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT profile_metric_precision_chk CHECK (precision IN ('EXACT', 'ESTIMATED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_profile_metric
    ON profile_metric (dataset_urn, COALESCE(column_name, ''), metric, window_start);
CREATE INDEX IF NOT EXISTS idx_profile_metric_lookup
    ON profile_metric (dataset_urn, metric, window_start DESC);

-- ---------------------------------------------------------------------------
-- 2. quality_rule_state：规则的调度状态
--    规则定义本身是**实体 + ruleSpec aspect**（进检索、有 Owner、有版本历史）；
--    这里只放"运行态"，避免把调度状态混进元数据真相源。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_rule_state (
    rule_urn     TEXT        PRIMARY KEY,
    -- 执行目标连接：支持 env:VAR 间接引用（与采集调度同一套凭证纪律，不落明文）
    dsn          TEXT        NOT NULL,
    cron         TEXT        NOT NULL DEFAULT '0 3 * * *',
    timezone     TEXT        NOT NULL DEFAULT 'Asia/Shanghai',
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    next_run_at  TIMESTAMPTZ,
    last_run_at  TIMESTAMPTZ,
    last_status  TEXT,
    last_run_id  TEXT,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_quality_rule_due
    ON quality_rule_state (enabled, next_run_at);

-- ---------------------------------------------------------------------------
-- 3. rule_run：规则执行留痕
--
--    状态里刻意区分 PASS / FAIL / ERROR / **SKIPPED**：
--    "没跑成"（如窗口内还没有基线）既不是通过也不是失败，
--    如果把它算成 PASS，规则会在悄悄失效的同时让面板保持绿色。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rule_run (
    run_id        TEXT        PRIMARY KEY,
    rule_urn      TEXT        NOT NULL,
    dataset_urn   TEXT,
    status        TEXT        NOT NULL,        -- PASS | FAIL | ERROR | SKIPPED
    observed      DOUBLE PRECISION,
    observed_json JSONB,
    expected      TEXT,
    metric        TEXT,
    severity      TEXT,
    dimension     TEXT,
    compiled_sql  TEXT,
    error         TEXT,
    duration_ms   INTEGER,
    window_start  TIMESTAMPTZ,
    executed_by   TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT rule_run_status_chk CHECK (status IN ('PASS', 'FAIL', 'ERROR', 'SKIPPED'))
);
CREATE INDEX IF NOT EXISTS idx_rule_run_rule ON rule_run (rule_urn, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_rule_run_fail ON rule_run (status, started_at DESC);
