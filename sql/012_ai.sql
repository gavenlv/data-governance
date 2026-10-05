-- ============================================================================
-- AI 原生能力与语义层（docs/13、docs/09 §9.3）
--
-- 设计立场（docs/13 §4）：**无据不答、全部带 provenance**。
-- 因此本文件里的表都围绕"建议从哪来、依据是什么、谁采纳了"来设计，
-- 而不是围绕"模型说了什么"。没有 provenance 的 AI 输出在治理场景里是负资产：
-- 它会让人无法判断该不该信。
--
-- 另外：**没有配置 LLM 时必须显式报错**（ai_config 里记录配置状态），
-- 而不是静默退回一个"看起来像 AI"的模板 —— 那会让人以为真的接了大模型。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. suggestion / suggestion_decision：建议与采纳闭环
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS suggestion (
    id             BIGSERIAL   PRIMARY KEY,
    entity_urn     TEXT        NOT NULL,
    aspect_type    TEXT,                      -- 建议写入的 aspect（descriptions / tags / ownership / classification）
    field          TEXT,                      -- 具体字段
    kind           TEXT        NOT NULL,      -- describe | tag | term_map | classify | owner | lineage_hint | contract | rule
    proposal       JSONB       NOT NULL,      -- 建议内容（结构化）
    rationale      TEXT        NOT NULL,      -- **必须写清楚依据**（哪条规则/哪个信号）
    confidence     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    -- 来源： deterministic（平台内置规则）| llm（大模型）| human（人工标注作为样本）
    generator      TEXT        NOT NULL DEFAULT 'deterministic',
    generator_ref  TEXT,                      -- 规则名或模型名（provenance 的一部分）
    evidence       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status         TEXT        NOT NULL DEFAULT 'PENDING',
    reviewed_by    TEXT,
    reviewed_at    TIMESTAMPTZ,
    review_note    TEXT,
    applied_aspect_version BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT suggestion_kind_chk CHECK (kind IN
        ('describe', 'tag', 'term_map', 'classify', 'owner', 'lineage_hint', 'contract', 'rule')),
    CONSTRAINT suggestion_status_chk CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT suggestion_generator_chk CHECK (generator IN ('deterministic', 'llm', 'human')),
    CONSTRAINT suggestion_confidence_chk CHECK (confidence >= 0 AND confidence <= 1)
);
-- 同一实体同一字段同一来源只保留一条待审建议（避免收件箱被重复建议淹没）
CREATE UNIQUE INDEX IF NOT EXISTS uq_suggestion_pending
    ON suggestion (entity_urn, kind, COALESCE(field, ''), generator)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_suggestion_status ON suggestion (status, confidence DESC, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. metric_definition：语义层指标（dbt Semantic Layer / Cube 风格的接入）
--
-- 指标是"口径"的载体：它必须能追溯到物理列，否则"GMV 到底怎么算的"永远说不清。
-- 因此每条指标都带 physical_columns（列级 URN），并在血缘图里写 Metric → Column 边。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS metric_definition (
    id             BIGSERIAL   PRIMARY KEY,
    name           TEXT        NOT NULL UNIQUE,
    display_name   TEXT,
    description    TEXT,
    metric_type    TEXT        NOT NULL DEFAULT 'SIMPLE',   -- SIMPLE | RATIO | DERIVED | CUMULATIVE
    expression     TEXT,
    physical_columns TEXT[]    NOT NULL DEFAULT '{}',
    dimensions     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    source_format  TEXT        NOT NULL DEFAULT 'dg',       -- dg | dbt | cube
    source_ref     TEXT,
    owner          TEXT,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT metric_type_chk CHECK (metric_type IN ('SIMPLE', 'RATIO', 'DERIVED', 'CUMULATIVE')),
    CONSTRAINT metric_status_chk CHECK (status IN ('ACTIVE', 'DEPRECATED', 'RETIRED'))
);
CREATE INDEX IF NOT EXISTS idx_metric_definition_active ON metric_definition (status, name);

-- ---------------------------------------------------------------------------
-- 3. ai_config：AI 能力的配置状态（**必须可查**：让"没接大模型"这件事可见）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_config (
    id             BIGSERIAL   PRIMARY KEY,
    capability     TEXT        NOT NULL UNIQUE,   -- suggestion_llm | embedding | nl_answer
    provider       TEXT,
    model          TEXT,
    configured     BOOLEAN     NOT NULL DEFAULT FALSE,
    endpoint       TEXT,
    last_checked_at TIMESTAMPTZ,
    last_error     TEXT,
    note           TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 4. edge_agent / edge_report：Edge Agent（推模式）注册与上报
--
-- ADR-012 的选型是 Go 单二进制；**本仓库不实现那个二进制**（明确标注）。
-- 但控制面侧必须先把"推模式"接住：注册、心跳、带上报（含护栏），
-- 否则私有子网场景永远无法接入。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edge_agent (
    id             BIGSERIAL   PRIMARY KEY,
    agent_id       TEXT        NOT NULL UNIQUE,
    display_name   TEXT,
    namespace      TEXT        NOT NULL DEFAULT 'prod',
    token_hash     TEXT        NOT NULL,       -- 只存哈希：上报凭据不落明文
    capabilities   TEXT[]      NOT NULL DEFAULT '{}',  -- 该 agent 支持的连接器
    version        TEXT,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    last_heartbeat_at TIMESTAMPTZ,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by  TEXT,
    CONSTRAINT edge_agent_status_chk CHECK (status IN ('ACTIVE', 'PAUSED', 'REVOKED'))
);

CREATE TABLE IF NOT EXISTS edge_report (
    id             BIGSERIAL   PRIMARY KEY,
    agent_id       TEXT        NOT NULL,
    report_type    TEXT        NOT NULL,       -- heartbeat | datasets
    namespace      TEXT,
    entity_count   INTEGER     NOT NULL DEFAULT 0,
    accepted       BOOLEAN     NOT NULL DEFAULT TRUE,
    reject_reason  TEXT,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT edge_report_type_chk CHECK (report_type IN ('heartbeat', 'datasets'))
);
CREATE INDEX IF NOT EXISTS idx_edge_report_agent ON edge_report (agent_id, received_at DESC);
