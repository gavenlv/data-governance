-- ============================================================================
-- Batch 3：BI 资产（仪表板）采集的计数列（docs/09 §9.1）
--
-- 为什么单独两列而不是复用 datasets_seen：**护栏语义不同**。
-- "表被删了 30%"与"仪表板被删了 30%"是完全不同的风险（前者可能是数据丢失，
-- 后者更可能是有人在整理报表），把两种计数混在一起会让护栏与健康度都失去意义。
-- ============================================================================

ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS dashboards_seen    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS dashboards_created INTEGER NOT NULL DEFAULT 0;
ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS dashboards_deleted INTEGER NOT NULL DEFAULT 0;
-- 仪表板有**独立的**护栏判定与快照 scope：BI 资产churn 率高（人手删报表很常见），
-- 把它和数据集混在一个护栏里，会让人删几个报表就把数据采集整体拦停。
ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS dashboard_guard_blocked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS dashboard_guard_reason  TEXT;
