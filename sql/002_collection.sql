-- ============================================================================
-- 采集运行记录与状态快照（docs/09 §9.1）
--
-- 解决的问题：
--   1. **删除检测**：源端删表后目录必须反映（此前完全没有删除检测）
--   2. **护栏**：实体数骤降 / 删除比例异常时**中止采集并拒绝软删**，
--      防止"配置改错把整批资产标记删除"（09 §9.1 的真实教训）
--   3. **命名空间隔离**：删除检测只在 (source, namespace, scope) 内生效
--   4. **采集健康度**：每次采集的结果留痕，避免"目录悄悄停止更新"
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. collect_run：每次采集运行的记录（健康度与排障依据）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collect_run (
    run_id             TEXT        PRIMARY KEY,
    source             TEXT        NOT NULL,
    namespace          TEXT        NOT NULL,
    scope              TEXT        NOT NULL DEFAULT '',
    -- RUNNING（进行中）| SUCCEEDED | BLOCKED（护栏拦截）| FAILED
    status             TEXT        NOT NULL DEFAULT 'RUNNING',
    block_reason       TEXT,
    datasets_seen      INTEGER     NOT NULL DEFAULT 0,
    datasets_created   INTEGER     NOT NULL DEFAULT 0,
    schemas_written    INTEGER     NOT NULL DEFAULT 0,
    schemas_unchanged  INTEGER     NOT NULL DEFAULT 0,
    deleted_candidates INTEGER     NOT NULL DEFAULT 0,
    deleted            INTEGER     NOT NULL DEFAULT 0,
    columns_seen       INTEGER     NOT NULL DEFAULT 0,
    errors             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    duration_ms        INTEGER,
    CONSTRAINT collect_run_status_chk
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'BLOCKED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_collect_run_source_time
    ON collect_run (source, namespace, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_collect_run_status
    ON collect_run (status, started_at DESC);

-- ---------------------------------------------------------------------------
-- 2. collector_state：每个采集范围的上次快照（删除检测的唯一依据）
--    PK(source, namespace, scope) 天然实现"命名空间隔离防误删"：
--    一个采集器只能看到并删除自己范围内的实体
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collector_state (
    source           TEXT        NOT NULL,
    namespace        TEXT        NOT NULL,
    scope            TEXT        NOT NULL DEFAULT '',
    last_run_id      TEXT,
    -- 上次见到的 Dataset URN 数组（用于 diff 出"本轮消失"的实体）
    last_snapshot    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    entity_count     INTEGER     NOT NULL DEFAULT 0,
    last_success_at  TIMESTAMPTZ,
    last_status      TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, namespace, scope)
);
