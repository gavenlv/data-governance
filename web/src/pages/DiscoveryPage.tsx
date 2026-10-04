import { useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Empty,
  Input,
  List,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { Link } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, type Facet, type SearchHit } from '../api/client'
import { useCapabilities } from '../hooks/useCapabilities'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { NotImplementedCard } from '../components/NotImplementedCard'

const { Title, Paragraph, Text } = Typography

/**
 * 发现（docs/14 §2）。
 *
 * 检索已接真实索引（事件流派生的 search_doc，可重放重建）：
 *  - 结果、**总数与分面统计**都按调用者的可见分级**前置过滤** —— 后过滤会泄露
 *    "有多少资产你无权查看"，这本身就是泄露（docs/09 §9.3）；
 *  - 索引水位（lag）直接展示：让"元数据已更新、索引还没同步"这件事可见，而不是让用户猜。
 *
 * 仍**未实现**的部分（正面标注，不装作有）：中文分词、向量/语义检索与 RRF 融合排序、
 * 行为元数据（热度/推荐）。状态见能力清单。
 */
export default function DiscoveryPage() {
  const capabilities = useCapabilities()
  const [keyword, setKeyword] = useState('')
  const [submitted, setSubmitted] = useState('')
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined)

  const searchCapability = capabilities.get('core.search-index')

  const assets = useQuery({
    queryKey: ['assets', 'discovery'],
    queryFn: () => api.assets({ limit: 12 }),
  })

  const search = useQuery({
    queryKey: ['search', submitted, typeFilter],
    queryFn: () => api.search({ q: submitted, type: typeFilter, limit: 20 }),
    enabled: submitted.length > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            发现
          </Title>
          <CapabilityBadge status={searchCapability?.status ?? 'NOT_IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          搜索优先的入口（docs/14 §1）：目标是在 3 秒内回答「这是什么 / 谁负责 / 能不能用 / 数据好不好」。
        </Text>
      </div>

      <Card size="small">
        <Space.Compact style={{ width: '100%' }}>
          <Input.Search
            placeholder="搜索资产、列、术语…（标识符会按 _ / 驼峰切分）"
            enterButton="搜索"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={(value) => {
              setTypeFilter(undefined)
              setSubmitted(value.trim())
            }}
            loading={search.isFetching}
            allowClear
          />
        </Space.Compact>
        {search.isError && (
          <Alert
            type="error"
            showIcon
            style={{ marginTop: 12 }}
            message="检索失败"
            description={(search.error as Error).message}
          />
        )}
        {search.data && (
          <div style={{ marginTop: 12 }}>
            <Space wrap size={12}>
              <Text type="secondary">
                命中 {search.data.count} 条 · 可见分级 {(search.data.visibleLevels ?? []).join(' / ')}
              </Text>
              <Tooltip title="索引水位 = 最新事件 seq − 已消费 seq。滞后说明元数据已更新但检索索引还没跟上。">
                <Tag color={search.data.indexLag > 0 ? 'orange' : 'green'}>
                  索引水位 {search.data.indexLag}
                  {search.data.indexLag > 0 ? '（同步中）' : '（已同步）'}
                </Tag>
              </Tooltip>
              <Text type="secondary" style={{ fontSize: 12 }}>
                索引文档 {search.data.index?.indexedDocs ?? 0} 篇
              </Text>
            </Space>
          </div>
        )}
      </Card>

      {search.data && (
        <Row gutter={16}>
          <Col span={6}>
            <FacetCard
              title="按类型"
              facets={search.data.facets?.entityType ?? []}
              active={typeFilter}
              onPick={(key) => setTypeFilter(key)}
            />
          </Col>
          <Col span={18}>
            <Card
              size="small"
              title={typeFilter ? `检索结果（类型：${typeFilter}）` : '检索结果'}
              extra={
                typeFilter ? (
                  <a onClick={() => setTypeFilter(undefined)}>清除类型筛选</a>
                ) : undefined
              }
            >
              {search.data.count === 0 ? (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Empty description={`没有匹配「${search.data.query}」的资产`} />
                  {(search.data.suggestions ?? []).length > 0 && (
                    <div>
                      <Text type="secondary">最接近的候选：</Text>{' '}
                      {(search.data.suggestions ?? []).map((s) => (
                        <Tag
                          key={s}
                          style={{ cursor: 'pointer' }}
                          onClick={() => {
                            setKeyword(s)
                            setSubmitted(s)
                          }}
                        >
                          {s}
                        </Tag>
                      ))}
                    </div>
                  )}
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    空结果不等于"没有这个资产"：也可能是还没被采集，或超出你的可见分级。
                    当前可见分级 {(search.data.visibleLevels ?? []).join(' / ')}。
                  </Text>
                </Space>
              ) : (
                <Table<SearchHit>
                  size="small"
                  rowKey="urn"
                  dataSource={search.data.results}
                  pagination={{ pageSize: 10, showSizeChanger: false }}
                  columns={[
                    {
                      title: '资产',
                      dataIndex: 'displayName',
                      render: (value: string | null, row) => (
                        <Space direction="vertical" size={0}>
                          <Link to={`/assets/${encodeURIComponent(row.urn)}`}>{value ?? row.urn}</Link>
                          <span className="dg-urn">{row.urn}</span>
                        </Space>
                      ),
                    },
                    {
                      title: '类型',
                      dataIndex: 'entityType',
                      width: 100,
                      render: (value: string) => <Tag>{value}</Tag>,
                    },
                    {
                      title: '平台 / 分级',
                      width: 150,
                      render: (_, row) => (
                        <Space direction="vertical" size={0}>
                          <Tag color="blue">{row.platform ?? '—'}</Tag>
                          <Tag color={row.classification === 'L4' ? 'red' : undefined}>
                            {row.classification ?? 'L2（默认）'}
                          </Tag>
                        </Space>
                      ),
                    },
                    {
                      title: '标签 / Owner',
                      render: (_, row) => (
                        <Space direction="vertical" size={0}>
                          <span>
                            {(row.tags ?? []).map((t) => (
                              <Tag key={t}>{t}</Tag>
                            ))}
                            {(row.tags ?? []).length === 0 && <Text type="secondary">无标签</Text>}
                          </span>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {(row.owners ?? []).length > 0 ? row.owners.join('、') : '无 Owner'}
                          </Text>
                        </Space>
                      ),
                    },
                  ]}
                />
              )}
            </Card>
          </Col>
        </Row>
      )}

      <Row gutter={16}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="目录中的实体" value={assets.data?.count ?? 0} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              来自真相源 PostgreSQL（可浏览，详见「资产」）
            </Text>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic
              title="检索索引文档"
              value={search.data?.index?.indexedDocs ?? '—'}
              suffix={<CapabilityBadge status={searchCapability?.status ?? 'NOT_IMPLEMENTED'} small />}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              事件流派生视图，可丢弃重建（ADR-002）
            </Text>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="数据产品 / 货架" value="—" suffix={<Tag>未实现</Tag>} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              docs/07 §2 中标记为 v1 不设计
            </Text>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="最近更新的资产" extra={<Link to="/assets">查看全部 →</Link>}>
        {assets.isLoading && <Empty description="加载中…" />}
        {assets.isError && (
          <Alert type="error" showIcon message={(assets.error as Error).message} />
        )}
        {assets.data && assets.data.count === 0 && (
          <Empty description="目录里还没有实体。可在「管理 › 采集」里跑一次采集。" />
        )}
        <List
          size="small"
          dataSource={assets.data?.assets ?? []}
          renderItem={(item) => (
            <List.Item>
              <Space direction="vertical" size={0} style={{ width: '100%' }}>
                <Space>
                  <Link to={`/assets/${encodeURIComponent(item.urn)}`}>
                    {item.display_name ?? item.urn}
                  </Link>
                  <Tag>{item.entity_type}</Tag>
                  {item.namespace && <Tag color="blue">{item.namespace}</Tag>}
                </Space>
                <span className="dg-urn">{item.urn}</span>
              </Space>
            </List.Item>
          )}
        />
      </Card>

      <Paragraph type="secondary" style={{ marginBottom: 0 }}>
        按 docs/14 §2，本入口还应包含：中文与语义检索、行为元数据驱动的热门/推荐、按域浏览、
        术语表入口、数据产品货架、全局命令面板（⌘K）。这些都还没有，状态见「管理 › 能力清单」——
        界面不维护自己的"做完了哪些"清单。
      </Paragraph>

      {['core.search-chinese', 'ai.semantic-search', 'ai.semantic-layer'].map((id) => (
        <NotImplementedCard key={id} capability={capabilities.get(id)} />
      ))}
    </Space>
  )
}

/** 分面卡：计数与结果用同一套 WHERE，因此不会泄露无权资产的存在性。 */
function FacetCard({
  title,
  facets,
  active,
  onPick,
}: {
  title: string
  facets: Facet[]
  active?: string
  onPick: (key: string | undefined) => void
}) {
  return (
    <Card size="small" title={title}>
      {facets.length === 0 ? (
        <Text type="secondary">无分面数据</Text>
      ) : (
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {facets.map((facet) => (
            <div
              key={String(facet.key)}
              style={{ cursor: 'pointer' }}
              onClick={() => onPick(active === facet.key ? undefined : (facet.key ?? undefined))}
            >
              <Tag color={active === facet.key ? 'blue' : undefined}>{facet.key ?? '(空)'}</Tag>
              <Text type="secondary">{facet.count}</Text>
            </div>
          ))}
        </Space>
      )}
    </Card>
  )
}
