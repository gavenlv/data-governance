-- ============================================================================
-- 数据契约（docs/09 §9.5，Batch 2）
--
-- 契约定义本体是**实体 + contractSpec aspect**（版本历史由 aspect_history 提供，
-- 治理属性复用 ownership/tags/lifecycle）。本文件只放契约"关系与事件"：
--   - contract_consumer  谁在用我（消费者显式订阅）
--   - contract_violation 违约事件（运行时校验的结果，按内容去重计数）
--   - contract_ci_check  CI 门禁的判定留痕（谁在哪个版本上被拦下、为什么）
--
-- 设计要点：违约**必须可豁免但不可永久豁免** —— 所以豁免带到期时间，
-- 到期后自动回到 OPEN（否则"临时豁免"会变成事实上的永久关闭）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. contract_consumer：消费者订阅（"让生产者看到真实消费者"）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_consumer (
    contract_urn  TEXT        NOT NULL,
    consumer_urn  TEXT        NOT NULL,
    registered_by TEXT,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          TEXT,
    PRIMARY KEY (contract_urn, consumer_urn)
);
CREATE INDEX IF NOT EXISTS idx_contract_consumer_urn ON contract_consumer (consumer_urn);

-- ---------------------------------------------------------------------------
-- 2. contract_violation：违约事件（按 (契约, 类型, 数据集, 详情哈希) 去重计数）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_violation (
    id              BIGSERIAL   PRIMARY KEY,
    contract_urn    TEXT        NOT NULL,
    dataset_urn     TEXT,
    kind            TEXT        NOT NULL,   -- missing_column | type_mismatch | nullability | unexpected_column | sla_breach
    severity        TEXT        NOT NULL,   -- BLOCK | ALERT | RECORD
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    detected_by     TEXT,
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    occurrences     INTEGER     NOT NULL DEFAULT 1,
    -- 豁免必须带到期时间：永久豁免等于没有契约
    exempted_until  TIMESTAMPTZ,
    exempted_by     TEXT,
    exempt_reason   TEXT,
    CONSTRAINT contract_violation_severity_chk CHECK (severity IN ('BLOCK', 'ALERT', 'RECORD')),
    CONSTRAINT contract_violation_status_chk CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'EXEMPTED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_violation
    ON contract_violation (contract_urn, kind, COALESCE(dataset_urn, ''), md5(detail::text));
CREATE INDEX IF NOT EXISTS idx_contract_violation_open
    ON contract_violation (status, severity, last_seen DESC);

-- ---------------------------------------------------------------------------
-- 3. contract_ci_check：CI 门禁判定留痕
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_ci_check (
    id                BIGSERIAL   PRIMARY KEY,
    contract_urn      TEXT        NOT NULL,
    requested_version TEXT,
    verdict           TEXT        NOT NULL,   -- PASS | WARN | BLOCK
    payload           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    requested_by      TEXT,
    source            TEXT,                   -- github | gitlab | cli | api
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT contract_ci_verdict_chk CHECK (verdict IN ('PASS', 'WARN', 'BLOCK'))
);
CREATE INDEX IF NOT EXISTS idx_contract_ci_check ON contract_ci_check (contract_urn, created_at DESC);
