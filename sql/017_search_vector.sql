-- ---------------------------------------------------------------------------
-- 017_search_vector：检索的向量表示（docs/13 §1.1「语义检索」）
--
-- 为什么用 JSONB 存向量而不是 pgvector：
--   本机/默认部署没有 pgvector 扩展（docs/21 §26 记录了这个取舍）。
--   向量检索的两条路：
--     a) 有 pgvector：vector 列 + HNSW/IVFFlat 索引，SQL 里做 kNN；
--     b) 无 pgvector：JSONB 存数组，取候选后在 Java 里算余弦（O(n) 但有上限）。
--   现在落地的是 (b)，并把"扫描上限与截断"显式回报 —— 绝不假装有 ANN 索引。
--   升级路径：装 pgvector 后新增 vector 列与索引，读写两侧切换即可（表结构不变）。
--
-- provider/dimension 必须落库：换了 embedding 模型后旧向量与新查询向量**不可比较**，
-- 这里用 (provider, dimension) 判断兼容性，避免拿 768 维模型去查 256 维向量。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS search_vector (
    urn         TEXT        PRIMARY KEY,
    provider    TEXT        NOT NULL,
    dimension   INTEGER     NOT NULL,
    vector      JSONB       NOT NULL,
    doc_hash    TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_search_vector_provider ON search_vector (provider, dimension);
