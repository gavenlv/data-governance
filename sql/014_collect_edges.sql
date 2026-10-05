-- ============================================================================
-- 采集运行记录补两列：连接器自带血缘的产出情况（dbt manifest 等）
--
-- 为什么要单独记：
--   1. **血缘也是采集产出**，不记就答不了"这次采集到底新增/更新了多少条边"；
--   2. 更重要的是 skip_notes：连接器解析不到 URN 的边会被**跳过**，
--      而"跳过了多少、为什么"必须留在运行记录里 —— 静默跳过会让人以为血缘已经全了
--      （与解析失败要看得到是同一条纪律）。
-- ============================================================================

ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS edges_written INTEGER NOT NULL DEFAULT 0;
ALTER TABLE collect_run ADD COLUMN IF NOT EXISTS edge_skip_notes JSONB NOT NULL DEFAULT '[]'::jsonb;
