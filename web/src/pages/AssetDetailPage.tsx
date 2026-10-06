import { useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Descriptions,
  Empty,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type AssetVersionRow, type AspectVersionDetail } from '../api/client'

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

      <VersionHistoryCard urn={urn} />

      <Card size="small" title="全部 Aspect（原始数据）">
        <pre className="dg-mono" style={{ maxHeight: 360, overflow: 'auto', margin: 0 }}>
          {JSON.stringify(aspects, null, 2)}
        </pre>
      </Card>
    </Space>
  )
}

/**
 * 版本历史（时间线 / 查看 / 与当前对比 / 回滚）。
 *
 * 语义要点：版本链**只增不减** —— 回滚不是"退回旧版本"，而是把旧版本的内容
 * 追加为一个新版本；历史版本一个都不会被改写或删除，审计链因此保持完整。
 */
function VersionHistoryCard({ urn }: { urn: string }) {
  const queryClient = useQueryClient()
  const [viewing, setViewing] = useState<AspectVersionDetail | null>(null)
  const [diffState, setDiffState] = useState<{
    old: AspectVersionDetail
    current: AspectVersionDetail
    row: AssetVersionRow
  } | null>(null)

  const versions = useQuery({
    queryKey: ['assetVersions', urn],
    queryFn: () => api.assetVersions(urn),
    enabled: Boolean(urn),
  })

  const rollback = useMutation({
    mutationFn: (row: AssetVersionRow) => api.assetRollback(urn, row.aspect_type, row.version),
    onSuccess: (result) => {
      message.success(
        `已回滚：用 v${result.restoredFrom} 的内容生成新版本 v${result.version}（历史版本均保留）`,
      )
      queryClient.invalidateQueries({ queryKey: ['assetVersions', urn] })
      queryClient.invalidateQueries({ queryKey: ['asset', urn] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const openVersion = async (row: AssetVersionRow) => {
    try {
      const detail = await api.assetAspectVersion(urn, row.aspect_type, row.version)
      setViewing(detail)
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const openDiff = async (row: AssetVersionRow) => {
    try {
      const currentVersion = (versions.data?.versions ?? []).find(
        (item) => item.aspect_type === row.aspect_type && item.is_current,
      )
      if (!currentVersion) {
        message.warning('找不到该 aspect 的当前版本，无法对比')
        return
      }
      const [oldDetail, currentDetail] = await Promise.all([
        api.assetAspectVersion(urn, row.aspect_type, row.version),
        api.assetAspectVersion(urn, row.aspect_type, currentVersion.version),
      ])
      setViewing(null)
      setDiffState({ old: oldDetail, current: currentDetail, row })
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const changes = diffState ? diffObjects(diffState.old.data ?? {}, diffState.current.data ?? {}) : []

  return (
    <Card
      size="small"
      title="版本历史"
      extra={
        versions.data ? <Text type="secondary">共 {versions.data.count} 个版本</Text> : undefined
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="版本链只增不减"
        description={
          versions.data?.note ??
          '回滚 = 用旧版本内容生成新版本；历史版本不会被改写或删除，可随时查看与对比。'
        }
      />
      {versions.isError && (
        <Alert type="error" showIcon message="读取版本历史失败" description={(versions.error as Error).message} />
      )}
      <Table<AssetVersionRow>
        size="small"
        rowKey={(row) => `${row.aspect_type}#${row.version}`}
        loading={versions.isLoading}
        dataSource={versions.data?.versions ?? []}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        locale={{ emptyText: <Empty description="尚无版本记录" /> }}
        columns={[
          {
            title: '版本',
            dataIndex: 'version',
            width: 130,
            render: (value: number, row) => (
              <Space size={4}>
                <span className="dg-mono">v{value}</span>
                {row.is_current && <Badge status="processing" text="当前" />}
              </Space>
            ),
          },
          {
            title: 'Aspect',
            dataIndex: 'aspect_type',
            width: 150,
            render: (value: string) => <Tag color="blue">{value}</Tag>,
          },
          {
            title: '来源',
            dataIndex: 'updated_by',
            width: 150,
            render: (value: string | null) =>
              value ? <span className="dg-mono">{value}</span> : <Text type="secondary">—</Text>,
          },
          {
            title: '采集运行',
            dataIndex: 'run_id',
            render: (value: string | null) =>
              value ? <span className="dg-mono">{value}</span> : <Text type="secondary">—</Text>,
          },
          {
            title: '时间',
            dataIndex: 'updated_at',
            width: 180,
            render: (value: string) => <span className="dg-mono">{value.slice(0, 19)}</span>,
          },
          {
            title: '操作',
            width: 210,
            render: (_, row) => (
              <Space size={4}>
                <Button size="small" type="link" onClick={() => openVersion(row)}>
                  查看
                </Button>
                {!row.is_current && (
                  <Button size="small" type="link" onClick={() => openDiff(row)}>
                    与当前对比
                  </Button>
                )}
                {!row.is_current && (
                  <Popconfirm
                    title={`回滚到 v${row.version}？`}
                    description="会用该版本的内容生成一个新版本，历史版本全部保留。"
                    okText="确认回滚"
                    cancelText="取消"
                    onConfirm={() => rollback.mutate(row)}
                  >
                    <Button size="small" type="link" danger>
                      回滚
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        open={Boolean(viewing)}
        title={viewing ? `${viewing.aspectType} · v${viewing.version}` : ''}
        footer={null}
        width={720}
        onCancel={() => setViewing(null)}
      >
        <pre className="dg-mono" style={{ maxHeight: 480, overflow: 'auto', margin: 0 }}>
          {JSON.stringify(viewing?.data ?? {}, null, 2)}
        </pre>
      </Modal>

      <Modal
        open={Boolean(diffState)}
        title={
          diffState
            ? `${diffState.row.aspect_type}：v${diffState.row.version} → v${diffState.current.version}（当前）`
            : ''
        }
        footer={null}
        width={900}
        onCancel={() => {
          setDiffState(null)
        }}
      >
        {changes.length === 0 ? (
          <Empty description="两个版本内容一致（无字段级差异）" />
        ) : (
          <Table<DiffRow>
            size="small"
            rowKey="path"
            dataSource={changes}
            pagination={{ pageSize: 20, showSizeChanger: false }}
            columns={[
              {
                title: '路径',
                dataIndex: 'path',
                width: 260,
                render: (value: string) => <span className="dg-mono">{value}</span>,
              },
              {
                title: '类型',
                dataIndex: 'kind',
                width: 90,
                render: (value: DiffRow['kind']) => (
                  <Tag color={value === 'added' ? 'green' : value === 'removed' ? 'red' : 'orange'}>
                    {value === 'added' ? '新增' : value === 'removed' ? '删除' : '变更'}
                  </Tag>
                ),
              },
              {
                title: `旧值（v${diffState?.row.version}）`,
                dataIndex: 'before',
                render: (value: string | undefined) =>
                  value === undefined ? <Text type="secondary">—</Text> : <span className="dg-mono">{value}</span>,
              },
              {
                title: '新值（当前）',
                dataIndex: 'after',
                render: (value: string | undefined) =>
                  value === undefined ? <Text type="secondary">—</Text> : <span className="dg-mono">{value}</span>,
              },
            ]}
          />
        )}
      </Modal>
    </Card>
  )
}

interface DiffRow {
  path: string
  kind: 'added' | 'removed' | 'changed'
  before?: string
  after?: string
}

/** 把嵌套对象/数组压成 key path → 标量文本，这样 diff 能定位到"哪一列变了"。 */
function flatten(value: unknown, prefix = '', out: Record<string, string> = {}): Record<string, string> {
  if (value === null || typeof value !== 'object') {
    out[prefix] = JSON.stringify(value)
    return out
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      out[prefix] = '[]'
      return out
    }
    value.forEach((item, index) => flatten(item, prefix ? `${prefix}[${index}]` : `[${index}]`, out))
    return out
  }
  const entries = Object.entries(value as Record<string, unknown>)
  if (entries.length === 0) {
    out[prefix] = '{}'
    return out
  }
  for (const [key, item] of entries) {
    flatten(item, prefix ? `${prefix}.${key}` : key, out)
  }
  return out
}

function diffObjects(before: Record<string, unknown>, after: Record<string, unknown>): DiffRow[] {
  const left = flatten(before)
  const right = flatten(after)
  const paths = Array.from(new Set([...Object.keys(left), ...Object.keys(right)])).sort()
  const rows: DiffRow[] = []
  for (const path of paths) {
    const oldValue = left[path]
    const newValue = right[path]
    if (oldValue === newValue) {
      continue
    }
    if (oldValue === undefined) {
      rows.push({ path, kind: 'added', after: newValue })
    } else if (newValue === undefined) {
      rows.push({ path, kind: 'removed', before: oldValue })
    } else {
      rows.push({ path, kind: 'changed', before: oldValue, after: newValue })
    }
  }
  return rows
}
