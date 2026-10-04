import { Descriptions, Alert, Card, Empty, Space, Spin, Table, Tag, Typography } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

const { Title, Text } = Typography

interface SchemaField {
  name: string
  type?: string
  nativeType?: string
  nullable?: boolean
  ordinal?: number
  description?: string | null
}

/**
 * 资产详情（docs/14 §3.2）：首屏回答四个问题 —— 这是什么 / 谁负责 / 能不能用 / 数据好不好。
 */
export default function AssetDetailPage() {
  const params = useParams<{ urn: string }>()
  const urn = params.urn ?? ''

  const asset = useQuery({
    queryKey: ['asset', urn],
    queryFn: () => api.asset(urn),
    enabled: Boolean(urn),
  })

  if (asset.isLoading) {
    return (
      <Space direction="vertical" align="center" style={{ width: '100%', paddingTop: 80 }}>
        <Spin />
        <Text type="secondary">加载中…</Text>
      </Space>
    )
  }

  if (asset.isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="读取资产失败"
        description={(asset.error as Error).message}
      />
    )
  }

  const detail = asset.data!
  const aspects = detail.aspects ?? {}
  const schema = aspects['datasetSchema'] as { fields?: SchemaField[]; primaryKey?: string[]; schemaHash?: string } | undefined
  const descriptions = aspects['descriptions'] as { text?: string } | undefined
  const ownership = aspects['ownership'] as { owners?: { urn?: string; name?: string }[] } | undefined
  const classification = aspects['classification'] as { level?: string } | undefined
  const lifecycle = aspects['lifecycle'] as { stage?: string } | undefined
  const trust = aspects['trustLevel'] as { level?: string; reason?: string } | undefined

  const fields = schema?.fields ?? []
  const missingDescriptions = fields.filter((field) => !field.description).length

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            {detail.displayName ?? urn.split('.').pop()}
          </Title>
          <Tag color="blue">{detail.entityType}</Tag>
          {classification?.level && <Tag color="orange">分级 {classification.level}</Tag>}
          {trust?.level && <Tag>可信状态 {trust.level}</Tag>}
          <Tag color={detail.lifecycle === 'ACTIVE' ? 'green' : 'default'}>{detail.lifecycle}</Tag>
        </div>
        <div className="dg-urn">{urn}</div>
      </div>

      <Card size="small" title="概览（首屏四问）">
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="这是什么">
            {descriptions?.text ?? <Text type="warning">尚无描述 —— 需人工补充或由采集写入表注释</Text>}
          </Descriptions.Item>
          <Descriptions.Item label="谁负责">
            {ownership?.owners?.length
              ? ownership.owners.map((owner) => owner.urn ?? owner.name).join('、')
              : <Text type="warning">无 Owner（v1 尚未实现 Owner 治理界面，可直接写 ownership aspect）</Text>}
          </Descriptions.Item>
          <Descriptions.Item label="能不能用">
            <Space>
              <span>分级 {classification?.level ?? '未分级'}</span>
              <span>·</span>
              <span>生命周期 {lifecycle?.stage ?? '—'}</span>
              <span>·</span>
              <span>
                访问审批 <Tag>未实现</Tag>
              </span>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="数据好不好">
            <Space>
              <span>
                质量规则 <Tag>未实现</Tag>
              </span>
              <span>
                契约 <Tag>未实现</Tag>
              </span>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="结构">
            {fields.length} 列
            {schema?.primaryKey?.length ? ` · 主键 ${schema.primaryKey.join(',')}` : ' · 无主键'}
          </Descriptions.Item>
          <Descriptions.Item label="结构指纹">
            <span className="dg-mono">{schema?.schemaHash ?? '—'}</span>
          </Descriptions.Item>
        </Descriptions>
        <div style={{ marginTop: 12 }}>
          <Link to={`/lineage?urn=${encodeURIComponent(urn)}`}>查看血缘 →</Link>
        </div>
      </Card>

      <Card
        size="small"
        title={`结构（${fields.length} 列）`}
        extra={
          missingDescriptions > 0 ? (
            <Text type="warning">{missingDescriptions} 列缺描述</Text>
          ) : (
            <Text type="success">全部列有描述</Text>
          )
        }
      >
        {fields.length === 0 ? (
          <Empty description="该资产没有 datasetSchema（可能不是数据集，或尚未采集结构）" />
        ) : (
          <Table<SchemaField>
            size="small"
            rowKey="name"
            dataSource={fields}
            pagination={{ pageSize: 25, showSizeChanger: false }}
            columns={[
              { title: '列名', dataIndex: 'name', width: 220, render: (value: string) => <span className="dg-mono">{value}</span> },
              { title: '类型', dataIndex: 'type', width: 160 },
              {
                title: '可空',
                dataIndex: 'nullable',
                width: 80,
                render: (value: boolean | undefined) => (value === false ? '否' : '是'),
              },
              {
                title: '描述',
                dataIndex: 'description',
                render: (value: string | null | undefined) =>
                  value ? value : <Text type="secondary">缺失</Text>,
              },
            ]}
          />
        )}
      </Card>

      <Card size="small" title="全部 Aspect（原始数据）">
        <pre className="dg-mono" style={{ maxHeight: 360, overflow: 'auto', margin: 0 }}>
          {JSON.stringify(aspects, null, 2)}
        </pre>
      </Card>
    </Space>
  )
}
