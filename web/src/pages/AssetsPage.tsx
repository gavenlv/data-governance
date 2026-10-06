import { useState } from 'react'
import { Alert, Card, Col, Empty, Input, Row, Select, Space, Table, Tag, Tooltip, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, type Facet } from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'

const { Title, Text } = Typography

/**
 * 资产列表（docs/14 §2）。
 *
 * 两种模式，同一个列表：
 *  - **有检索词** → 走真实全文索引（事件流派生的 search_doc），带类型 / 平台分面。
 *    命中数、分面计数与结果共用同一套可见性过滤，因此不会泄露"有多少资产你无权看"。
 *  - **无检索词** → 直接浏览真相源实体（按类型筛选）。
 *
 * 这里刻意不再用"按 URN 前缀过滤"：URN 前缀要求用户先知道 URN 长什么样，
 * 输 `event_log` 这种"表名/列名"会得到 0 条，看起来像搜索坏了 —— 而它其实只是拼不对前缀。
 */
export default function AssetsPage() {
  const capabilities = useCapabilities()
  const [keyword, setKeyword] = useState('')
  const [submitted, setSubmitted] = useState('')
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined)
  const [platformFilter, setPlatformFilter] = useState<string | undefined>(undefined)

  const searching = submitted.length > 0

  const browse = useQuery({
    queryKey: ['assets', 'browse', typeFilter],
    queryFn: () => api.assets({ entityType: typeFilter, limit: 200 }),
    enabled: !searching,
  })

  const search = useQuery({
    queryKey: ['assets', 'search', submitted, typeFilter, platformFilter],
    queryFn: () =>
      api.search({ q: submitted, type: typeFilter, platform: platformFilter, limit: 50 }),
    enabled: searching,
    placeholderData: keepPreviousData,
    retry: false,
  })

  const active = searching ? search : browse
  const count = searching ? (search.data?.count ?? 0) : (browse.data?.count ?? 0)

  const rows: ViewRow[] = searching
    ? (search.data?.results ?? []).map((hit) => ({
        urn: hit.urn,
        entityType: hit.entityType,
        displayName: hit.displayName,
        namespace: hit.namespace,
        platform: hit.platform,
        classification: hit.classification,
        tags: hit.tags,
        owners: hit.owners,
        lifecycle: undefined,
        updatedAt: hit.indexedAt,
      }))
    : (browse.data?.assets ?? []).map((row) => ({
        urn: row.urn,
        entityType: row.entity_type,
        displayName: row.display_name,
        namespace: row.namespace,
        platform: null,
        classification: null,
        tags: [],
        owners: [],
        lifecycle: row.lifecycle,
        updatedAt: row.updated_at,
      }))

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            资产
          </Title>
          <CapabilityBadge status={capabilities.get('core.entity-aspect')?.status ?? 'IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          统一资产模型（docs/08 §3）：11 种实体类型共用同一套 URN / Aspect / 关系模型。输入表名、
          列名、术语或 URN 片段都能检索。
        </Text>
      </div>

      <Card size="small">
        <Space wrap>
          <Input.Search
            placeholder="搜索资产、列、术语…（如 event_log；标识符按 _ / 驼峰切分）"
            enterButton="搜索"
            style={{ width: 460 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={(value) => {
              setPlatformFilter(undefined)
              setSubmitted(value.trim())
            }}
            loading={search.isFetching}
            allowClear
          />
          <Select
            placeholder="实体类型"
            style={{ width: 170 }}
            allowClear
            value={typeFilter}
            onChange={setTypeFilter}
            options={ENTITY_TYPES.map((value) => ({ value, label: value }))}
          />
          {searching && (
            <Select
              placeholder="平台"
              style={{ width: 160 }}
              allowClear
              value={platformFilter}
              onChange={setPlatformFilter}
              options={(search.data?.facets?.platform ?? [])
                .filter((facet) => facet.key)
                .map((facet) => ({ value: facet.key as string, label: `${facet.key} (${facet.count})` }))}
            />
          )}
          <Tag>共 {count} 条</Tag>
          {searching && (
            <>
              <Tooltip title="索引水位 = 最新事件 seq − 已消费 seq。滞后说明元数据已更新但检索索引还没跟上。">
                <Tag color={(search.data?.indexLag ?? 0) > 0 ? 'orange' : 'green'}>
                  索引水位 {search.data?.indexLag ?? 0}
                  {(search.data?.indexLag ?? 0) > 0 ? '（同步中）' : '（已同步）'}
                </Tag>
              </Tooltip>
              <Text type="secondary" style={{ fontSize: 12 }}>
                可见分级 {(search.data?.visibleLevels ?? []).join(' / ')}
              </Text>
              <a
                onClick={() => {
                  setKeyword('')
                  setSubmitted('')
                  setPlatformFilter(undefined)
                }}
              >
                返回浏览
              </a>
            </>
          )}
        </Space>
      </Card>

      {active.isError && (
        <Alert type="error" showIcon message="加载失败" description={(active.error as Error).message} />
      )}

      {searching && search.data && (
        <Row gutter={16}>
          <Col span={6}>
            <FacetCard
              title="按类型"
              facets={search.data.facets?.entityType ?? []}
              active={typeFilter}
              onPick={setTypeFilter}
            />
            <div style={{ height: 12 }} />
            <FacetCard
              title="按平台"
              facets={search.data.facets?.platform ?? []}
              active={platformFilter}
              onPick={setPlatformFilter}
            />
          </Col>
          <Col span={18}>
            {search.data.count === 0 && !search.isFetching ? (
              <Card size="small">
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
                  </Text>
                </Space>
              </Card>
            ) : (
              <AssetTable rows={rows} loading={search.isLoading} />
            )}
          </Col>
        </Row>
      )}

      {!searching && <AssetTable rows={rows} loading={browse.isLoading} />}
    </Space>
  )
}

const ENTITY_TYPES = [
  'Platform',
  'Container',
  'Dataset',
  'Column',
  'Pipeline',
  'Dashboard',
  'GlossaryTerm',
  'Tag',
  'Team',
  'User',
  'Domain',
]

/** 浏览与检索共用的展示行：把两种后端形状归一，避免列表两套渲染逻辑。 */
interface ViewRow {
  urn: string
  entityType: string
  displayName: string | null
  namespace: string | null
  platform: string | null
  classification: string | null
  tags: string[]
  owners: string[]
  lifecycle?: string
  updatedAt?: string
}

function AssetTable({ rows, loading }: { rows: ViewRow[]; loading: boolean }) {
  return (
    <Table<ViewRow>
      size="small"
      rowKey="urn"
      loading={loading}
      dataSource={rows}
      pagination={{ pageSize: 20, showSizeChanger: false }}
      locale={{ emptyText: <Empty description="没有匹配的实体" /> }}
      columns={[
        {
          title: '资产',
          dataIndex: 'displayName',
          render: (value: string | null, row) => (
            <Space direction="vertical" size={0}>
              <Link to={`/assets/${encodeURIComponent(row.urn)}`}>
                {value ?? row.urn.split('.').pop()}
              </Link>
              <span className="dg-urn">{row.urn}</span>
            </Space>
          ),
        },
        {
          title: '类型',
          dataIndex: 'entityType',
          width: 110,
          render: (value: string) => <Tag color="blue">{value}</Tag>,
        },
        {
          title: '平台 / 分级',
          width: 150,
          render: (_, row) => (
            <Space direction="vertical" size={0}>
              {row.platform && <Tag color="geekblue">{row.platform}</Tag>}
              {row.classification && (
                <Tag color={row.classification === 'L4' ? 'red' : undefined}>{row.classification}</Tag>
              )}
              {!row.platform && !row.classification && <Text type="secondary">—</Text>}
            </Space>
          ),
        },
        { title: '命名空间', dataIndex: 'namespace', width: 140 },
        {
          title: '标签 / Owner',
          render: (_, row) => (
            <Space direction="vertical" size={0}>
              <span>
                {row.tags.map((tag) => (
                  <Tag key={tag}>{tag}</Tag>
                ))}
                {row.tags.length === 0 && <Text type="secondary">无标签</Text>}
              </span>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {row.owners.length > 0 ? row.owners.join('、') : '无 Owner'}
              </Text>
            </Space>
          ),
        },
        {
          title: '生命周期',
          dataIndex: 'lifecycle',
          width: 130,
          render: (value: string | undefined) =>
            value ? <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value}</Tag> : <Text type="secondary">—</Text>,
        },
        {
          title: '更新时间',
          dataIndex: 'updatedAt',
          width: 170,
          render: (value: string | undefined) => (
            <span className="dg-mono">{value ? value.slice(0, 19) : '—'}</span>
          ),
        },
      ]}
    />
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
  const meaningful = facets.filter((facet) => facet.key)
  return (
    <Card size="small" title={title}>
      {meaningful.length === 0 ? (
        <Text type="secondary">无分面数据</Text>
      ) : (
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {meaningful.map((facet) => (
            <div
              key={String(facet.key)}
              style={{ cursor: 'pointer' }}
              onClick={() => onPick(active === facet.key ? undefined : (facet.key ?? undefined))}
            >
              <Tag color={active === facet.key ? 'blue' : undefined}>{facet.key}</Tag>
              <Text type="secondary">{facet.count}</Text>
            </div>
          ))}
        </Space>
      )}
    </Card>
  )
}