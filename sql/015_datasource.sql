-- ============================================================================
-- 数据源（已保存的连接）：创建一次连接，之后扫描/测试都不必重敲完整 DSN 与口令
--
-- 为什么不复用 collect_run：
--   collect_run 是"一次执行"的记录（append-only、永不修改），数据源是"一个可复用的连接"
--   （会被反复编辑与扫描）。两者生命周期完全不同，混在一张表里会让
--   "这次采到了什么" 与 "这个连接是什么" 互相污染。
--
-- 关于凭据 —— 这是本表存在的最大风险点，因此有三条硬约束：
--   1. 只有 secret_enc 一列存凭据，且**只存密文**（AES-256-GCM，见 SecretCipher）。
--      加密密钥来自环境变量 DG_SECRET_KEY；**未配置密钥时接口直接拒绝保存**，
--      绝不允许明文落库 —— 宁可功能不可用，也不能出现明文口令。
--   2. 其余列（endpoint / databases / schemas / tables）刻意只放**非密**信息。
--      endpoint 在入库前会被脱敏（去掉 user:password@ 与 ?password= 之类的参数），
--      目的：列表页无需解密即可展示，从而缩小明文暴露面。
--   3. 密钥**不在库里**：数据库备份泄漏 ≠ 凭据泄漏，这个不对称是刻意设计的。
-- ============================================================================

CREATE TABLE IF NOT EXISTS data_source (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    connector    TEXT NOT NULL,
    namespace    TEXT,
    -- 非密展示用（已脱敏）：postgresql://localhost:25011/dg、clickhouse://host:8123、dbt 的路径等
    endpoint     TEXT,
    -- 非密：库 / schema / 表的过滤条件（逗号分隔，与 ConnectorRequest 的三个列表一一对应）
    databases    TEXT,
    schemas      TEXT,
    tables       TEXT,
    sample_size  INTEGER,
    -- 密文：仅 {dsn, jdbcUrl, username, password} 这部分，格式 v1:<iv>:<ciphertext>
    secret_enc   TEXT NOT NULL,
    created_by   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_scan_at TIMESTAMPTZ,
    last_scan_run_id TEXT,
    last_scan_status TEXT
);

-- 名称唯一：数据源靠名字被人引用（"用 prod-postgres 扫一次"），重名会让引用变得不可靠
CREATE UNIQUE INDEX IF NOT EXISTS data_source_name_key ON data_source (name);
CREATE INDEX IF NOT EXISTS data_source_updated_idx ON data_source (updated_at DESC);