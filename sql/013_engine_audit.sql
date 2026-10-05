-- ============================================================================
-- 引擎侧访问审计摄入（docs/09 §9.7、docs/23 无关；本文件对应能力 policy.engine-audit-ingest）
--
-- 为什么这张表是"审计闭环"的最后一块拼图：
--   平台自身的 access_event 只记录**平台的决策**（申请/审批/授权/吊销）。
--   没有引擎侧的真实访问记录，就永远回答不了两个审计问题：
--     1. 「批准了但从来没被用过」—— 权限该不该回收？（此前只能靠人工判断）
--     2. 「被访问了但从来没被批准过」—— 有没有绕过治理的直连访问？
--
-- 设计要点（都很"讲道理"，但每条都有具体理由）：
--   * **原始记录必须留着**（raw jsonb）：解析规则会变，原始日志不会。没有原始记录就无法重放；
--   * **解析失败也要入库**（resolved=false + note）：直接丢弃等于"这次访问不存在"，
--     这正是审计里最危险的静默行为；
--   * **去重靠内容哈希**（engine + record_hash 唯一）：日志采集常被重启重放，
--     没有幂等键就会把"一次访问"记成很多次，把统计数字弄脏；
--   * **列级访问单独存**（columns text[]）：最小权限判断需要"到底读了哪些列"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS engine_audit_record (
    id             BIGSERIAL   PRIMARY KEY,
    engine         TEXT        NOT NULL,          -- trino | ranger | warehouse | superset | other
    record_hash    TEXT        NOT NULL,          -- 去重键（引擎+规范化后的关键字段）
    external_id    TEXT,                          -- 引擎侧查询 ID（有就存，便于与引擎对账）
    event_time     TIMESTAMPTZ NOT NULL,          -- 访问发生时间（引擎时间，不是摄入时间）
    actor          TEXT        NOT NULL,          -- 引擎侧用户
    operation      TEXT,                          -- SELECT | INSERT | CREATE | DROP | ...
    resource_raw   TEXT        NOT NULL,          -- 引擎侧原始资源名（catalog.schema.table）
    resource_urn   TEXT,                          -- 解析出的平台 URN（解析不出为 NULL）
    columns        TEXT[]      NOT NULL DEFAULT '{}',
    rows_scanned   BIGINT,
    bytes_scanned  BIGINT,
    succeeded      BOOLEAN     NOT NULL DEFAULT TRUE,
    source_ip      TEXT,
    resolved       BOOLEAN     NOT NULL DEFAULT FALSE,
    resolve_note   TEXT,                          -- 解析失败/多义时说明原因（不猜）
    raw            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ingested_by    TEXT,
    ingested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT engine_audit_engine_chk CHECK (engine IN
        ('trino', 'ranger', 'warehouse', 'superset', 'other'))
);
-- 幂等键：同一个引擎的同一个内容哈希只存一次（日志重放不污染统计）
CREATE UNIQUE INDEX IF NOT EXISTS uq_engine_audit_record
    ON engine_audit_record (engine, record_hash);
CREATE INDEX IF NOT EXISTS idx_engine_audit_resource
    ON engine_audit_record (resource_urn, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_engine_audit_actor
    ON engine_audit_record (actor, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_engine_audit_time
    ON engine_audit_record (event_time DESC);

-- 摄入批次：每次调用记一条，"接入有没有在跑"必须可观测（否则静默停采没人知道）
CREATE TABLE IF NOT EXISTS engine_audit_ingest (
    id             BIGSERIAL   PRIMARY KEY,
    engine         TEXT        NOT NULL,
    received       INTEGER     NOT NULL DEFAULT 0,
    accepted       INTEGER     NOT NULL DEFAULT 0,
    duplicated     INTEGER     NOT NULL DEFAULT 0,
    unresolved     INTEGER     NOT NULL DEFAULT 0,
    rejected       INTEGER     NOT NULL DEFAULT 0,
    window_from    TIMESTAMPTZ,                   -- 本次批次覆盖的时间范围（用于判断观测窗口）
    window_to      TIMESTAMPTZ,
    note           TEXT,
    ingested_by    TEXT,
    ingested_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_engine_audit_ingest_time
    ON engine_audit_ingest (ingested_at DESC);
