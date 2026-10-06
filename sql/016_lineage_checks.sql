-- ============================================================================
-- 血缘 L2 校验的产出记录（lineage.sql-parse 的缺口之一）
--
-- 为什么需要单独一张表：SQL 静态解析有**两类失败**，而它们对使用者的含义完全不同：
--   1. **解析不出来**（方言不支持、语句太复杂）→ 这是解析器能力问题；
--   2. **信息不足**（SELECT * 缺 schema、无表限定的列在多个源表里都存在）→ 这是**输入不足**，
--      而输入恰恰是平台自己可以补的（平台有 datasetSchema）。
-- 以前两类都只留在 parse 的 warnings 字符串里，于是"血缘覆盖率为什么低"这个问题
-- 只能回答"因为解析不了"——等于没回答。
--
-- 记录纪律（与解析失败必须可见一致）：
--   * 每条 finding 都带 **type + severity + evidence**：能复核、能统计、能追踪趋势；
--   * L2 **补出来的边**也要记账（kind=RESOLVED_BY_L2）：置信度低于 exact，
--     使用者有权知道"这条边不是 SQL 直接告诉我们的，是拿平台 schema 推出来的"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS lineage_check_finding (
    id             BIGSERIAL   PRIMARY KEY,
    -- 检查类型：select_star_expanded | select_star_unresolved | ambiguous_column_resolved
    --          | ambiguous_column_unresolved | missing_schema | parse_failed | target_unresolved
    check_type     TEXT        NOT NULL,
    severity       TEXT        NOT NULL DEFAULT 'INFO',   -- INFO | WARN | BLOCK
    statement_hash TEXT,                                  -- 同一条 SQL 的稳定指纹（便于去重与追踪）
    target_urn     TEXT,
    resource       TEXT,                                  -- 涉及的列/表（具体到列名）
    message        TEXT        NOT NULL,
    evidence       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    source         TEXT        NOT NULL DEFAULT 'sql_parse_l2',
    actor          TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT lineage_check_type_chk CHECK (check_type IN
        ('select_star_expanded', 'select_star_unresolved', 'ambiguous_column_resolved',
         'ambiguous_column_unresolved', 'missing_schema', 'parse_failed', 'target_unresolved')),
    CONSTRAINT lineage_check_severity_chk CHECK (severity IN ('INFO', 'WARN', 'BLOCK'))
);
CREATE INDEX IF NOT EXISTS idx_lineage_check_recent ON lineage_check_finding (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_lineage_check_type ON lineage_check_finding (check_type, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_lineage_check_target ON lineage_check_finding (target_urn);
