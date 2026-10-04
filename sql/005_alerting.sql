-- ============================================================================
-- 采集失败告警（docs/09 §9.1「采集自身的可观测性」）
--
-- 为什么必须有它：目录"悄悄停止更新"是治理平台最典型的隐性失效 ——
-- 采集连续失败但没人知道，用户看到的是"这个平台的信息是旧的"，然后就不再来了。
-- 健康度看板只能被主动查看，**告警才能主动找到人**。
--
-- 告警疲劳治理（09 §9.1 明确要求）：
--   - 分级：INFO / WARNING / CRITICAL
--   - 去重：同一问题（dedup_key）只保留一个活跃告警，不重复打扰
--   - 冷却：仍在告警中的问题按 remind_interval 才再提醒一次
--   - 恢复：条件消失自动转为 RESOLVED，并（可选）发恢复通知
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. alert_channel：通知通道
--    config 里放 webhook url 等；**不要在代码里内置任何真实地址**
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_channel (
    name         TEXT        PRIMARY KEY,
    kind         TEXT        NOT NULL,      -- webhook | log
    format       TEXT        NOT NULL DEFAULT 'generic',  -- generic|slack|feishu|dingtalk|teams
    config       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    min_severity TEXT        NOT NULL DEFAULT 'WARNING',  -- 低于该级别不发
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT alert_channel_kind_chk CHECK (kind IN ('webhook', 'log')),
    CONSTRAINT alert_channel_severity_chk
        CHECK (min_severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

-- ---------------------------------------------------------------------------
-- 2. alert_event：告警事件（状态机 FIRING → RESOLVED）
--    部分唯一索引保证同一 dedup_key 只有一个活跃告警（去重的落点）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_event (
    id              BIGSERIAL   PRIMARY KEY,
    dedup_key       TEXT        NOT NULL,
    rule            TEXT        NOT NULL,
    severity        TEXT        NOT NULL,
    state           TEXT        NOT NULL DEFAULT 'FIRING',
    subject         TEXT        NOT NULL,   -- 如 sqlite@prod
    scope           TEXT,
    title           TEXT        NOT NULL,
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    first_fired_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_fired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    fire_count      INTEGER     NOT NULL DEFAULT 1,
    notified_at     TIMESTAMPTZ,             -- 上次真正发出通知的时间（冷却判定）
    notify_count    INTEGER     NOT NULL DEFAULT 0,
    resolved_at     TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by TEXT,
    CONSTRAINT alert_event_state_chk CHECK (state IN ('FIRING', 'RESOLVED')),
    CONSTRAINT alert_event_severity_chk
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);
-- 同一问题只允许一个活跃告警（RESOLVED 的历史可以有多条）
CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_active
    ON alert_event (dedup_key) WHERE state = 'FIRING';
CREATE INDEX IF NOT EXISTS idx_alert_state_severity
    ON alert_event (state, severity, last_fired_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_subject
    ON alert_event (subject, last_fired_at DESC);

-- ---------------------------------------------------------------------------
-- 3. alert_dispatch_log：每次通知投递的结果（排障：为什么没收到告警）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_dispatch_log (
    id          BIGSERIAL   PRIMARY KEY,
    alert_id    BIGINT      NOT NULL REFERENCES alert_event(id) ON DELETE CASCADE,
    channel     TEXT        NOT NULL,
    ok          BOOLEAN     NOT NULL,
    status_code INTEGER,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dispatch_alert ON alert_dispatch_log (alert_id, created_at DESC);
