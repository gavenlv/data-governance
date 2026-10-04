-- ============================================================================
-- 血缘解析异常样本库（docs/09 §9.2）
--
-- 为什么必须有它：解析失败如果只是"返回空"，用户无法区分
-- 「真的没有血缘」与「解析器没解析出来」——这是开源平台的通病。
-- 把失败样本沉淀下来，才能把**方言覆盖率变成可运营的指标**，
-- 并据此持续扩充语料回归集（tests/corpus/lineage_corpus.yaml）。
--
-- 去重策略：按 (dialect, sql_hash, parse_level) 聚合，occurrences 计数，
-- 避免同一段坏 SQL 把表撑爆。
-- ============================================================================

CREATE TABLE IF NOT EXISTS lineage_parse_sample (
    id             BIGSERIAL   PRIMARY KEY,
    run_id         TEXT,
    dialect        TEXT        NOT NULL,
    statement_type TEXT,
    -- failed | table_level_only | derived（只登记"不够好"的结果）
    parse_level    TEXT        NOT NULL,
    error          TEXT,
    warnings       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    sql_hash       TEXT        NOT NULL,
    sql_excerpt    TEXT        NOT NULL,
    sql_full       TEXT,
    occurrences    INTEGER     NOT NULL DEFAULT 1,
    first_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT lineage_sample_level_chk
        CHECK (parse_level IN ('failed', 'table_level_only', 'derived'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_lineage_sample
    ON lineage_parse_sample (dialect, sql_hash, parse_level);
CREATE INDEX IF NOT EXISTS idx_lineage_sample_level
    ON lineage_parse_sample (parse_level, occurrences DESC);
