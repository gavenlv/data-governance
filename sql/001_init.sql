-- ============================================================================
-- 通用数据治理平台 · 真相源 schema（v1 / Phase 0）
--
-- 设计依据：docs/08-metadata-model.md §5、docs/10-tech-stack.md §3.1
-- 核心原则（ADR-002）：PostgreSQL 是唯一真相源；所有变更在**同一事务**内
--   写入 event_log(outbox)；搜索索引等派生视图由消费者从事件流构建，可重放重建。
-- 核心原则（ADR-005）：aspect 保存字段级来源(field_sources)，采集不得覆盖人工内容。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. entity：实体（URN 为主键，永不复用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS entity (
    urn          TEXT        PRIMARY KEY,
    entity_type  TEXT        NOT NULL,
    namespace    TEXT        NOT NULL,
    tenant       TEXT        NOT NULL DEFAULT 'default',
    display_name TEXT,
    lifecycle    TEXT        NOT NULL DEFAULT 'ACTIVE',
    properties   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    run_id       TEXT,
    CONSTRAINT entity_lifecycle_chk CHECK (lifecycle IN ('ACTIVE','DEPRECATED','DELETED_AT_SOURCE','TOMBSTONE'))
);
CREATE INDEX IF NOT EXISTS idx_entity_type      ON entity (entity_type) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_entity_ns_type   ON entity (tenant, namespace, entity_type);
CREATE INDEX IF NOT EXISTS idx_entity_updated   ON entity (updated_at DESC);

-- ---------------------------------------------------------------------------
-- 2. aspect：实体的"方面"（变更的最小单位，可独立版本化）
--    PK(urn, aspect_type) —— 并发写用 version 做乐观锁
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aspect (
    urn           TEXT        NOT NULL REFERENCES entity(urn) ON DELETE CASCADE,
    aspect_type   TEXT        NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 1,
    data          JSONB       NOT NULL,
    -- 字段级来源：{"description":"MANUAL","tags":"AUTO_COLLECTED"}  MANUAL > IMPORTED > AUTO_COLLECTED
    field_sources JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_by    TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    run_id        TEXT,
    PRIMARY KEY (urn, aspect_type)
);
CREATE INDEX IF NOT EXISTS idx_aspect_type ON aspect (aspect_type);

-- ---------------------------------------------------------------------------
-- 3. aspect_history：版本历史（审计与时间旅行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aspect_history (
    urn         TEXT        NOT NULL,
    aspect_type TEXT        NOT NULL,
    version     BIGINT      NOT NULL,
    data        JSONB       NOT NULL,
    field_sources JSONB     NOT NULL DEFAULT '{}'::jsonb,
    updated_by  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL,
    run_id      TEXT,
    PRIMARY KEY (urn, aspect_type, version)
);

-- ---------------------------------------------------------------------------
-- 4. edge：关系边（血缘是一等公民，带来源/置信度/时效）
--    设计依据 docs/08 §4.4、docs/09 §9.2
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edge (
    id                   BIGSERIAL   PRIMARY KEY,
    from_urn             TEXT        NOT NULL,
    to_urn               TEXT        NOT NULL,
    edge_type            TEXT        NOT NULL,
    source               TEXT        NOT NULL,   -- sql_parse|openlineage|query_log|code_static|manual|inferred_ai
    confidence           REAL        NOT NULL DEFAULT 1.0,
    transform            TEXT,                   -- DIRECT|INDIRECT|AGGREGATED|MASKED|FILTER
    transform_expression TEXT,
    cardinality          TEXT,                   -- ONE_TO_ONE|ONE_TO_MANY|MANY_TO_ONE
    dependency_kind      TEXT        NOT NULL DEFAULT 'VALUE',  -- VALUE|CONTROL
    parse_level          TEXT,                   -- exact|derived|table_level_only
    state                TEXT        NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|STALE|PENDING_REVIEW|REJECTED
    via_job              TEXT,
    first_seen           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen            TIMESTAMPTZ NOT NULL DEFAULT now(),
    observed_count       INTEGER     NOT NULL DEFAULT 1,
    properties           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT edge_confidence_chk CHECK (confidence >= 0 AND confidence <= 1),
    UNIQUE (from_urn, to_urn, edge_type, source, dependency_kind)
);
CREATE INDEX IF NOT EXISTS idx_edge_from ON edge (from_urn, edge_type) WHERE state = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_edge_to   ON edge (to_urn,   edge_type) WHERE state = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 5. event_log：append-only 变更日志（outbox）—— 派生视图的唯一输入
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_log (
    seq         BIGSERIAL   PRIMARY KEY,
    event_id    UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_type  TEXT        NOT NULL,   -- ENTITY_CREATED|ASPECT_UPSERTED|ASPECT_DELETED|EDGE_UPSERTED|EDGE_DELETED
    urn         TEXT        NOT NULL,
    aspect_type TEXT,
    version     BIGINT,
    payload     JSONB       NOT NULL,
    actor       TEXT,
    run_id      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_seq  ON event_log (seq);
CREATE INDEX IF NOT EXISTS idx_event_type ON event_log (event_type, seq);
CREATE INDEX IF NOT EXISTS idx_event_urn  ON event_log (urn, seq);

-- ---------------------------------------------------------------------------
-- 6. consumer_offset：消费者进度（可重放、可重建派生视图）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consumer_offset (
    consumer   TEXT        PRIMARY KEY,
    last_seq   BIGINT      NOT NULL DEFAULT 0,
    status     TEXT        NOT NULL DEFAULT 'IDLE',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 7. audit_log：审计（append-only + 哈希链，防篡改）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    seq         BIGSERIAL   PRIMARY KEY,
    actor       TEXT        NOT NULL DEFAULT 'system',
    action      TEXT        NOT NULL,
    urn         TEXT,
    aspect_type TEXT,
    before      JSONB,
    after       JSONB,
    request_id  TEXT,
    source_ip   TEXT,
    prev_hash   TEXT,
    hash        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_urn ON audit_log (urn, seq DESC);

-- ---------------------------------------------------------------------------
-- 8. search_doc：派生视图（可丢弃、可重建）—— 轻量模式下的检索索引
--    生产可换成 OpenSearch；此处用 PG + tsvector 验证"可重放重建"这一架构主张
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS search_doc (
    urn              TEXT        PRIMARY KEY,
    entity_type      TEXT        NOT NULL,
    display_name     TEXT,
    namespace        TEXT,
    platform         TEXT,
    container        TEXT,
    description      TEXT,
    tags             TEXT[]      NOT NULL DEFAULT '{}',
    owners           TEXT[]      NOT NULL DEFAULT '{}',
    classification   TEXT,
    tsv              TSVECTOR,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    indexed_watermark BIGINT     NOT NULL   -- 构建该文档时的 event_log.seq（用于对账与"索引同步中"提示）
);
CREATE INDEX IF NOT EXISTS idx_search_tsv  ON search_doc USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_search_type ON search_doc (entity_type);
CREATE INDEX IF NOT EXISTS idx_search_tags ON search_doc USING GIN (tags);
