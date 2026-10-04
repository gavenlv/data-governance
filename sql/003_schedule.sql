-- ============================================================================
-- 采集调度定义（docs/09 §9.1、docs/11「内置调度」）
--
-- 设计取舍：
--   - 调度定义可来自 YAML（Git 管理，符合"治理即代码"），apply 后落本表；
--   - 运行状态（上次/下次执行、上次结果）留在本表，便于 API 与看板查询；
--   - **DSN 不落明文**：支持 `env:VAR_NAME` 形式引用环境变量，生产应改为
--     Vault/KMS 密钥引用（09 §9.1「凭证管理」）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS collect_schedule (
    name          TEXT        PRIMARY KEY,
    source        TEXT        NOT NULL,          -- postgres | sqlite | ...
    dsn           TEXT        NOT NULL,          -- 支持 env:VAR_NAME
    namespace     TEXT        NOT NULL DEFAULT 'prod',
    database_name TEXT,
    schemas       TEXT[],
    tables        TEXT[],
    cron          TEXT        NOT NULL DEFAULT '0 3 * * *',
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 护栏配置覆盖：{maxDeletions, maxDeleteRatio, minRetentionRatio}
    guard_config  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    timezone      TEXT        NOT NULL DEFAULT 'Asia/Shanghai',
    last_run_id   TEXT,
    last_run_at   TIMESTAMPTZ,
    last_status   TEXT,
    next_run_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_collect_schedule_enabled
    ON collect_schedule (enabled, source);
