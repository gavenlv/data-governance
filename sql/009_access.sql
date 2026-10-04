-- ============================================================================
-- 访问治理：申请 → 审批 → 授权 → 到期回收 → 定期复核（docs/09 §9.7、docs/20 §8）
--
-- 为什么这些是独立表而不是往 entity/aspect 里塞：
--   申请与授权是**流程状态**（提交/审批/过期/回收），不是资产属性；
--   它们的生命周期由工作流驱动，混进元数据真相源会让"资产是什么"与"谁正在申请它"纠缠不清。
--   资产侧只通过 resource_urn 引用，两者各自的演进互不影响。
--
-- 设计要点（都来自设计文档）：
--   1. **最小粒度建议**：授权按列（column）而不是整表 —— 字段粒度在表里显式记录；
--   2. **到期必回收**：授权必须有 expires_at（业务上"永久授权"是治理失败的主要原因之一）；
--   3. **SLA 与升级**：审批有 sla_due_at，超时会被标记 escalated，便于运营发现卡点；
--   4. **复核留痕**：每次 access review 的判定都写入 access_review，用于回答
--      "这条授权是谁在什么时候确认还需要保留的"。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. access_request：访问申请
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_request (
    id             BIGSERIAL   PRIMARY KEY,
    requester      TEXT        NOT NULL,
    resource_urn   TEXT        NOT NULL,          -- 目标资产（表或列）
    column_name    TEXT,                          -- 列级申请时给出列名（最小粒度建议的落点）
    granularity    TEXT        NOT NULL DEFAULT 'DATASET',  -- DATASET | COLUMN
    permissions    TEXT[]      NOT NULL DEFAULT '{}',       -- SELECT | INSERT | ...
    purpose        TEXT        NOT NULL,          -- 用途（必填：没有用途的申请无法审批）
    duration_days  INTEGER     NOT NULL DEFAULT 90,
    classification TEXT,                          -- 提交时快照的分级（用于路由与 SLA，事后改分级不影响历史判定）
    domain         TEXT,
    status         TEXT        NOT NULL DEFAULT 'SUBMITTED',
    route          TEXT,                          -- 审批路由（谁批、为什么是他）
    approvers      TEXT[]      NOT NULL DEFAULT '{}',
    sla_due_at     TIMESTAMPTZ,
    escalated      BOOLEAN     NOT NULL DEFAULT FALSE,
    decided_by     TEXT,
    decided_at     TIMESTAMPTZ,
    decision_note  TEXT,
    -- 最小粒度建议：平台给出的建议与理由（"给列不给表"），审批人可以看到并采纳
    granularity_suggestion TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT access_request_status_chk CHECK (
        status IN ('SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT access_request_granularity_chk CHECK (granularity IN ('DATASET', 'COLUMN')),
    CONSTRAINT access_request_duration_chk CHECK (duration_days > 0 AND duration_days <= 3650)
);
CREATE INDEX IF NOT EXISTS idx_access_request_status ON access_request (status, sla_due_at);
CREATE INDEX IF NOT EXISTS idx_access_request_resource ON access_request (resource_urn);
CREATE INDEX IF NOT EXISTS idx_access_request_requester ON access_request (requester, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. access_grant：批准后产生的授权记录（有效期、用途、粒度）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_grant (
    id             BIGSERIAL   PRIMARY KEY,
    request_id     BIGINT      REFERENCES access_request(id) ON DELETE SET NULL,
    subject        TEXT        NOT NULL,          -- 被授权的主体（用户/团队/服务账号）
    resource_urn   TEXT        NOT NULL,
    column_name    TEXT,
    granularity    TEXT        NOT NULL DEFAULT 'DATASET',
    permissions    TEXT[]      NOT NULL DEFAULT '{}',
    purpose        TEXT,
    granted_by     TEXT        NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,         -- 必须有到期时间
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    revoked_by     TEXT,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  TEXT,
    last_reviewed_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT access_grant_status_chk CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'PENDING_REVIEW')),
    CONSTRAINT access_grant_granularity_chk CHECK (granularity IN ('DATASET', 'COLUMN'))
);
CREATE INDEX IF NOT EXISTS idx_access_grant_active ON access_grant (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_access_grant_resource ON access_grant (resource_urn);
CREATE INDEX IF NOT EXISTS idx_access_grant_subject ON access_grant (subject);

-- ---------------------------------------------------------------------------
-- 3. access_review：定期复核（access review）的判定留痕
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_review (
    id            BIGSERIAL   PRIMARY KEY,
    campaign      TEXT        NOT NULL,           -- 复核批次（如 2026-Q1）
    grant_id      BIGINT      NOT NULL REFERENCES access_grant(id) ON DELETE CASCADE,
    reviewer      TEXT        NOT NULL,
    decision      TEXT        NOT NULL,           -- KEEP | REVOKE | NEED_MORE_INFO
    reason        TEXT,
    -- 复核依据快照：授权已存在多久、是否有使用数据。**必须显式记录"没有使用数据"**，
    -- 否则复核人会误以为"没显示使用记录 = 没被使用"
    evidence      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    decided_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT access_review_decision_chk CHECK (decision IN ('KEEP', 'REVOKE', 'NEED_MORE_INFO'))
);
CREATE INDEX IF NOT EXISTS idx_access_review_campaign ON access_review (campaign, decided_at DESC);

-- ---------------------------------------------------------------------------
-- 4. access_event：访问决策事件（审计取证的原料）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_event (
    id           BIGSERIAL   PRIMARY KEY,
    subject      TEXT        NOT NULL,
    action       TEXT        NOT NULL,            -- REQUEST_SUBMITTED | REQUEST_APPROVED | GRANT_CREATED | ...
    resource_urn TEXT,
    decision     TEXT,                            -- ALLOW | DENY | null（不对资源做判定的动作）
    reason       TEXT,
    detail       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    source       TEXT,                            -- platform（平台自身判定）| engine（引擎上报，未实现）
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_access_event_time ON access_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_event_subject ON access_event (subject, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_event_resource ON access_event (resource_urn, occurred_at DESC);
