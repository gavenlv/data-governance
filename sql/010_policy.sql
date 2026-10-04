-- ============================================================================
-- 策略建模、编译产物与下发记录（docs/09 §9.7、ADR-008、docs/20 §8）
--
-- 定位（docs/20 §8 的修正）：**执行层是商品，生命周期层才是产品。**
-- 因此本文件的表只承载"建模 → 编译 → 版本 → 下发 → 回滚 → 覆盖率"这条链路，
-- 真正的执行件（Trino SystemAccessControl、Ranger、Lake Formation、数仓原生策略）
-- 由现成产品承担，本平台**不重造执行引擎**。
--
-- 编译产物必须落库（artifact）：策略编译错误的后果是数据泄露或大面积不可用，
-- 因此每一次编译的产物都要能事后对账（"当时下发的是什么"），而不是每次现算。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. policy_definition：策略定义（业务可读的建模）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_definition (
    id            BIGSERIAL   PRIMARY KEY,
    name          TEXT        NOT NULL UNIQUE,
    description   TEXT,
    -- 目标引擎：编译器的分发目标（trino 为首选集成点，见 docs/09 §9.7 的核实）
    target        TEXT        NOT NULL DEFAULT 'trino',
    -- 效果：行过滤 / 列掩码 / 授权 / 拒绝
    effect        TEXT        NOT NULL DEFAULT 'ROW_FILTER',
    -- 资源范围：按资产 URN 前缀或分级（二选一或组合），业务人员可读
    resource_scope JSONB      NOT NULL DEFAULT '{}'::jsonb,
    -- 主体范围：角色 / 用户 / 团队 + 是否"仅这些主体"（allowlist）或"除此之外"（denylist）
    subject_scope JSONB       NOT NULL DEFAULT '{}'::jsonb,
    condition     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    priority      INTEGER     NOT NULL DEFAULT 100,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',
    version       INTEGER     NOT NULL DEFAULT 1,
    created_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT policy_definition_target_chk CHECK (target IN ('trino', 'warehouse', 'bi', 'sdk')),
    CONSTRAINT policy_definition_effect_chk CHECK (
        effect IN ('ROW_FILTER', 'COLUMN_MASK', 'GRANT', 'DENY')),
    CONSTRAINT policy_definition_status_chk CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);
CREATE INDEX IF NOT EXISTS idx_policy_definition_active ON policy_definition (status, target, priority);

-- ---------------------------------------------------------------------------
-- 2. policy_artifact：编译产物（按策略 + 目标 + 版本 + 内容哈希去重）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_artifact (
    id            BIGSERIAL   PRIMARY KEY,
    policy_name   TEXT        NOT NULL,
    policy_version INTEGER    NOT NULL,
    target        TEXT        NOT NULL,
    content_hash  TEXT        NOT NULL,
    artifact      TEXT        NOT NULL,          -- 编译产物（SQL / 谓词 / 配置片段）
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_name, policy_version, target, content_hash)
);
CREATE INDEX IF NOT EXISTS idx_policy_artifact_policy ON policy_artifact (policy_name, policy_version DESC);

-- ---------------------------------------------------------------------------
-- 3. policy_deployment：下发记录（快照清单 + 状态 + 回滚依据）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_deployment (
    id            BIGSERIAL   PRIMARY KEY,
    target        TEXT        NOT NULL,
    bundle_hash   TEXT        NOT NULL,          -- 本次下发的"产物集合"指纹（快照测试与回滚的对账依据）
    status        TEXT        NOT NULL DEFAULT 'PENDING',
    dispatch_mode TEXT        NOT NULL DEFAULT 'artifact_bundle',
    artifact_count INTEGER    NOT NULL DEFAULT 0,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    deployed_by   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at    TIMESTAMPTZ,
    rolled_back_at TIMESTAMPTZ,
    CONSTRAINT policy_deployment_status_chk CHECK (
        status IN ('PENDING', 'APPLIED', 'FAILED', 'ROLLED_BACK'))
);
CREATE INDEX IF NOT EXISTS idx_policy_deployment_target ON policy_deployment (target, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. policy_coverage：覆盖率度量（**最关键的一张表**）
--
-- docs/09 §9.7 的原话：宣称"策略已下发"而不度量覆盖率，是最危险的产品表述。
-- 因此覆盖率必须落库、必须能回答"哪些高分级资产没有任何策略覆盖"，
-- 并且必须显式记录"直连绕过"这一**已知缺口**（其真实检测依赖引擎审计日志，本平台目前拿不到）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_coverage (
    id            BIGSERIAL   PRIMARY KEY,
    measured_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    scope         TEXT        NOT NULL,          -- 度量口径（如 all-datasets）
    total_assets  INTEGER     NOT NULL DEFAULT 0,
    covered_assets INTEGER    NOT NULL DEFAULT 0,
    uncovered_high_class INTEGER NOT NULL DEFAULT 0,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_policy_coverage_time ON policy_coverage (measured_at DESC);
