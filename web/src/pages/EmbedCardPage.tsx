import { useEffect, useState } from 'react'
import { Alert, Card, Descriptions, Space, Spin, Tag, Typography } from 'antd'
import { useParams, useSearchParams } from 'react-router-dom'

const { Title, Text, Paragraph } = Typography

/**
 * 嵌入式资产卡（被 BI / IDE 用 iframe 引用）。
 *
 * 为什么单独一个页面而不是复用资产详情页：嵌入场景只需要「一屏能读完的治理信息」，
 * 而且**不能**带上导航、搜索、管理入口 —— 那些会把嵌入变成"另一个平台入口"。
 * 数据来自独立只读端点（字段白名单 + 短期令牌），见后端 EmbedController。
 */
export default function EmbedCardPage() {
  const { urn = '' } = useParams()
  const [params] = useSearchParams()
  const token = params.get('t') ?? ''
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: Record<string, unknown> }>({
    loading: true,
  })

  useEffect(() => {
    if (!urn || !token) {
      setState({ loading: false, error: '缺少嵌入令牌：请让平台重新生成嵌入链接（令牌短期有效）' })
      return
    }
    fetch(`/api/v1/embed/assets/${encodeURIComponent(urn)}?t=${encodeURIComponent(token)}`)
      .then(async (response) => {
        const body = (await response.json().catch(() => ({}))) as Record<string, unknown>
        if (!response.ok) {
          setState({ loading: false, error: String(body.message ?? `HTTP ${response.status}`) })
          return
        }
        setState({ loading: false, data: body })
      })
      .catch((error: Error) => setState({ loading: false, error: error.message }))
  }, [urn, token])

  if (state.loading) {
    return <div style={{ padding: 24 }}><Spin /></div>
  }
  if (state.error) {
    return (
      <div style={{ padding: 16 }}>
        <Alert type="warning" showIcon message="无法加载资产卡" description={state.error} />
      </div>
    )
  }

  const data = state.data ?? {}
  const owners = (data.owners as { id?: string; name?: string }[] | undefined) ?? []
  const links = (data.links as Record<string, string> | undefined) ?? {}

  return (
    <div style={{ padding: 12, background: '#fafafa', minHeight: '100vh' }}>
      <Card size="small" title="数据治理 · 资产卡" extra={<Tag color="blue">只读</Tag>}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Title level={5} style={{ margin: 0 }}>{String(data.displayName ?? urn)}</Title>
          <Space size={4} wrap>
            <Tag>{String(data.entityType ?? '—')}</Tag>
            {data.classification ? <Tag color="volcano">{String(data.classification)}</Tag> : null}
            {data.lifecycle ? <Tag color="green">{String(data.lifecycle)}</Tag> : null}
          </Space>
          {data.description ? (
            <Paragraph type="secondary" style={{ marginBottom: 0 }} ellipsis={{ rows: 4, expandable: true }}>
              {String(data.description)}
            </Paragraph>
          ) : (
            <Text type="secondary">（暂无描述）</Text>
          )}
          <Descriptions size="small" column={1} items={[
            { key: 'owners', label: 'Owner', children: owners.length
                ? owners.map((owner) => owner.name ?? owner.id).filter(Boolean).join('、')
                : '未登记' },
            { key: 'updated', label: '最近更新', children: String(data.updatedAt ?? '—').slice(0, 19).replace('T', ' ') },
          ]} />
          <Space size={12}>
            <a href={links.asset ?? '#'} target="_blank" rel="noreferrer">打开完整资产页</a>
            <a href={links.requestAccess ?? '#'} target="_blank" rel="noreferrer">申请访问</a>
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>{String(data.notice ?? '')}</Text>
        </Space>
      </Card>
    </div>
  )
}
