import { useState } from 'react'
import { Alert, Card, Empty, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, type AssetRow } from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'

const { Title, Text } = Typography

/** 资产列表。按前缀与类型浏览 —— 完整检索待 core.search-index 实现。 */
export default function AssetsPage() {
  const capabilities = useCapabilities()
  const [prefix, setPrefix] = useState('')
  const [entityType, setEntityType] = useState<string | undefined>(undefined)

  const assets = useQuery({
    queryKey: ['assets', prefix, entityType],
    queryFn: () => api.assets({ prefix: prefix || undefined, entityType, limit: 200 }),
  })

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
          统一资产模型（docs/08 §3）：11 种实体类型共用同一套 URN / Aspect / 关系模型。
        </Text>
      </div>

      <Card size="small">
        <Space wrap>
          <Input
            placeholder="按 URN 前缀过滤，如 urn:dg:Dataset:prod.postgresql"
            style={{ width: 420 }}
            value={prefix}
            onChange={(event) => setPrefix(event.target.value)}
            allowClear
          />
          <Select
            placeholder="实体类型"
            style={{ width: 180 }}
            allowClear
            value={entityType}
            onChange={setEntityType}
            options={[
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
            ].map((value) => ({ value, label: value }))}
          />
          <Tag>共 {assets.data?.count ?? 0} 条</Tag>
        </Space>
      </Card>

      {assets.isError && <Alert type="error" showIcon message={(assets.error as Error).message} />}

      <Table<AssetRow>
        size="small"
        rowKey="urn"
        loading={assets.isLoading}
        dataSource={assets.data?.assets ?? []}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        locale={{ emptyText: <Empty description="没有匹配的实体" /> }}
        columns={[
          {
            title: '资产',
            dataIndex: 'display_name',
            render: (_value, row) => (
              <Space direction="vertical" size={0}>
                <Link to={`/assets/${encodeURIComponent(row.urn)}`}>
                  {row.display_name ?? row.urn.split('.').pop()}
                </Link>
                <span className="dg-urn">{row.urn}</span>
              </Space>
            ),
          },
          {
            title: '类型',
            dataIndex: 'entity_type',
            width: 120,
            render: (value: string) => <Tag color="blue">{value}</Tag>,
          },
          { title: '命名空间', dataIndex: 'namespace', width: 140 },
          {
            title: '生命周期',
            dataIndex: 'lifecycle',
            width: 150,
            render: (value: string) => (
              <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value}</Tag>
            ),
          },
          {
            title: '更新时间',
            dataIndex: 'updated_at',
            width: 180,
            render: (value: string) => <span className="dg-mono">{String(value).slice(0, 19)}</span>,
          },
        ]}
      />
    </Space>
  )
}
