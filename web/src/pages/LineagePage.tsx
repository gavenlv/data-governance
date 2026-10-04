import { useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Descriptions,
  Input,
  List,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { useLocation, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api, type ImpactNode } from '../api/client'
import LineageCanvas from '../components/LineageCanvas'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { NotImplementedCard } from '../components/NotImplementedCard'
import { useCapabilities } from '../hooks/useCapabilities'

const { Title, Text, Paragraph } = Typography

/** 血缘：交互式探索画布、影响分析、SQL 解析入库（已实现）。 */
export default function LineagePage() {
  const capabilities = useCapabilities()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  // 从资产页「查看血缘」带过来的 urn 直接作为焦点，省掉一次手工粘贴
  const initialUrn = searchParams.get('urn') ?? (location.state as { urn?: string } | null)?.urn ?? ''
  const [urn, setUrn] = useState(initialUrn)
  const [direction, setDirection] = useState<'upstream' | 'downstream'>('downstream')
  const [depth, setDepth] = useState(3)
  const [minConfidence, setMinConfidence] = useState(0)
  const [includeColumns, setIncludeColumns] = useState(false)
  const [includeControl, setIncludeControl] = useState(false)
  const [tableUrn, setTableUrn] = useState('')

  const impact = useQuery({
    queryKey: ['impact', tableUrn, direction, depth],
    queryFn: () => api.impact(tableUrn, direction, depth, true),
    enabled: false,
    retry: false,
  })

  const quality = useQuery({
    queryKey: ['lineage-quality'],
    queryFn: () => api.lineageQuality(),
  })

  const sidecar = useQuery({
    queryKey: ['lineage-sidecar'],
    queryFn: () => api.lineageSidecar(),
    retry: false,
  })

  const qualityData = quality.data as
    | {
        activeLineageEdges?: number
        activeColumnEdges?: number
        sqlglotSidecarReady?: boolean
        edgeSources?: unknown[]
        parseSamples?: unknown[]
      }
    | undefined

  const sidecarHealth = (sidecar.data?.health ?? {}) as {
    sqlglotVersion?: string
    dialects?: string[]
  }

  const focus = (next: string) => {
    setUrn(next)
    setSearchParams(next ? { urn: next } : {})
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            血缘
          </Title>
          <CapabilityBadge status={capabilities.get('lineage.column-graph')?.status ?? 'IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('lineage.visualization')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('lineage.impact-analysis')?.status ?? 'IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          交互式探索（docs/14 §3.3）：**线型 = 可信度**，双击节点重新聚焦，单击边可确认或驳回。
        </Text>
      </div>

      <Card
        size="small"
        title="血缘探索器"
        extra={
          <Space wrap size={4}>
            <Tag.CheckableTag checked={direction === 'downstream'} onChange={() => setDirection('downstream')}>
              下游（影响了谁）
            </Tag.CheckableTag>
            <Tag.CheckableTag checked={direction === 'upstream'} onChange={() => setDirection('upstream')}>
              上游（从哪来）
            </Tag.CheckableTag>
            <Input
              type="number"
              addonBefore="深度"
              style={{ width: 120 }}
              value={depth}
              min={1}
              max={5}
              onChange={(event) => setDepth(Number(event.target.value) || 3)}
            />
            <Input
              type="number"
              addonBefore="置信度≥"
              step="0.05"
              style={{ width: 150 }}
              value={minConfidence}
              min={0}
              max={1}
              onChange={(event) => setMinConfidence(Number(event.target.value) || 0)}
            />
            <Tag.CheckableTag checked={includeColumns} onChange={setIncludeColumns}>
              含列级
            </Tag.CheckableTag>
            <Tooltip title="控制依赖（过滤/分区键产生的边）会让图又大又难读，默认排除">
              <Tag.CheckableTag checked={includeControl} onChange={setIncludeControl}>
                含控制依赖
              </Tag.CheckableTag>
            </Tooltip>
          </Space>
        }
      >
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Space wrap>
            <Input
              placeholder="资产 URN，例如 urn:dg:Dataset:prod.postgresql.dg.public.entity"
              style={{ width: 520 }}
              value={urn}
              onChange={(event) => setUrn(event.target.value)}
              onPressEnter={() => focus(urn)}
            />
            <a onClick={() => focus(urn)}>加载血缘</a>
            <Text type="secondary" style={{ fontSize: 12 }}>
              也可以从「资产」页点「查看血缘」直接带 URN 过来
            </Text>
          </Space>
          <LineageCanvas
            urn={urn}
            direction={direction}
            depth={depth}
            minConfidence={minConfidence}
            includeColumns={includeColumns}
            includeControl={includeControl}
            onRefocus={focus}
          />
        </Space>
      </Card>

      <Tabs
        items={[
          { key: 'impact', label: '影响分析', children: <ImpactTab direction={direction} depth={depth} onUrn={setTableUrn} impact={impact} /> },
          { key: 'quality', label: '血缘质量', children: <QualityTab qualityData={qualityData} sidecarHealth={sidecarHealth} /> },
          { key: 'parse', label: 'SQL 解析入库', children: <SqlParseCard /> },
        ]}
      />

      <List
        grid={{ gutter: 16, column: 2 }}
        dataSource={['lineage.visualization']}
        renderItem={(id) => (
          <List.Item>
            <NotImplementedCard capability={capabilities.get(id)} />
          </List.Item>
        )}
      />
    </Space>
  )
}

function ImpactTab({
  direction,
  depth,
  onUrn,
  impact,
}: {
  direction: 'upstream' | 'downstream'
  depth: number
  onUrn: (urn: string) => void
  impact: ReturnType<typeof useQuery<Awaited<ReturnType<typeof api.impact>>, Error>>
}) {
  const [urn, setUrn] = useState('')
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Space wrap>
        <Input
          placeholder="要变更的资产 URN"
          style={{ width: 520 }}
          value={urn}
          onChange={(event) => setUrn(event.target.value)}
        />
        <a
          onClick={() => {
            onUrn(urn)
            void impact.refetch()
          }}
        >
          分析影响面
        </a>
      </Space>

      {impact.data?.caveat && <Alert type="info" showIcon message="闭包为空" description={impact.data.caveat} />}

      {impact.data && impact.data.affectedCount > 0 && (
        <>
          <Space wrap>
            <Tag>受影响 {impact.data.affectedCount}</Tag>
            <Tag color="red">关键 {impact.data.criticalCount}</Tag>
            <Tag>闭包内边 {impact.data.edgesInClosure}</Tag>
          </Space>
          {impact.data.reachedMaxDepth && (
            <Alert type="warning" showIcon message="结果被深度上限截断" description={impact.data.truncationNote} />
          )}
          <Table<ImpactNode>
            size="small"
            rowKey="urn"
            pagination={{ pageSize: 10, showSizeChanger: false }}
            dataSource={impact.data.nodes}
            columns={[
              { title: '影响度', dataIndex: 'score', width: 100 },
              { title: '跳数', dataIndex: 'depth', width: 70 },
              {
                title: '资产',
                dataIndex: 'displayName',
                render: (value: string | null, row) => (
                  <Space direction="vertical" size={0}>
                    <span>{value ?? row.urn}</span>
                    <span className="dg-urn">{row.urn}</span>
                  </Space>
                ),
              },
              {
                title: '分级 / Owner',
                width: 180,
                render: (_, row) => (
                  <Space direction="vertical" size={0}>
                    <Tag color={row.classification === 'L4' ? 'red' : undefined}>
                      {row.classification ?? 'L2（默认）'}
                    </Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {row.owners.length > 0 ? row.owners.join('、') : '无 Owner'}
                    </Text>
                  </Space>
                ),
              },
              {
                title: '为什么关键',
                dataIndex: 'reasons',
                render: (reasons: string[]) => (
                  <Space wrap size={4}>
                    {reasons.map((reason) => (
                      <Tag key={reason} color="orange">
                        {reason}
                      </Tag>
                    ))}
                  </Space>
                ),
              },
            ]}
          />
          {impact.data.scoring && (
            <Card size="small" type="inner" title="评分口径（可解释，不是黑盒）">
              <Paragraph style={{ marginBottom: 4 }}>
                <span className="dg-mono">{impact.data.scoring.formula}</span>
              </Paragraph>
              <Paragraph style={{ marginBottom: 4 }} type="secondary">
                权重构成：{impact.data.scoring.wComponents.join('；')}。
              </Paragraph>
              <Paragraph style={{ marginBottom: 0 }} type="warning">
                未计入：{impact.data.scoring.excluded}。
              </Paragraph>
            </Card>
          )}
        </>
      )}
      <Text type="secondary" style={{ fontSize: 12 }}>
        当前方向 {direction === 'downstream' ? '下游' : '上游'} · 深度 {depth}（在「血缘探索器」页签里调整）
      </Text>
    </Space>
  )
}

function QualityTab({
  qualityData,
  sidecarHealth,
}: {
  qualityData:
    | {
        activeLineageEdges?: number
        activeColumnEdges?: number
        sqlglotSidecarReady?: boolean
        edgeSources?: unknown[]
        parseSamples?: unknown[]
      }
    | undefined
  sidecarHealth: { sqlglotVersion?: string; dialects?: string[] }
}) {
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="血缘边（活跃）" value={qualityData?.activeLineageEdges ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="其中列级边" value={qualityData?.activeColumnEdges ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="解析异常样本" value={(qualityData?.parseSamples as unknown[])?.length ?? 0} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              区分「没有血缘」与「解析不出来」
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="SQL 解析侧车"
              value={qualityData?.sqlglotSidecarReady ? '就绪' : '不可用'}
              valueStyle={{ color: qualityData?.sqlglotSidecarReady ? '#52c41a' : '#ff4d4f' }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {sidecarHealth.sqlglotVersion
                ? `sqlglot ${sidecarHealth.sqlglotVersion} · ${sidecarHealth.dialects?.length ?? 0} 方言`
                : 'Python/sqlglot 侧车（docs/10 §2）'}
            </Text>
          </Card>
        </Col>
      </Row>
      <Card size="small" title="血缘质量（回答：覆盖率为什么低）">
        <Descriptions column={1} size="small">
          <Descriptions.Item label="边来源分布">
            {qualityData?.edgeSources?.length ? (
              <pre className="dg-mono" style={{ margin: 0 }}>
                {JSON.stringify(qualityData.edgeSources, null, 2)}
              </pre>
            ) : (
              <Text type="secondary">暂无血缘边</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="解析异常样本（按方言/级别聚合）">
            {qualityData?.parseSamples?.length ? (
              <pre className="dg-mono" style={{ margin: 0 }}>
                {JSON.stringify(qualityData.parseSamples, null, 2)}
              </pre>
            ) : (
              <Text type="secondary">暂无样本（说明还没遇到解析不出来的 SQL）</Text>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </Space>
  )
}

/**
 * SQL 解析入库：真的调用 Python sqlglot 侧车并把列级血缘写进图。
 *
 * 侧车不可用时返回 502 并给出启动命令 —— **不返回空血缘**（空血缘与"解析没跑"必须可区分）。
 */
function SqlParseCard() {
  const [sql, setSql] = useState(
    "INSERT INTO alert_event\nSELECT e.seq, e.event_type, e.urn\n  FROM event_log e\n WHERE e.event_type = 'ENTITY_CREATED'",
  )
  const [dialect, setDialect] = useState('postgres')
  const [namespace, setNamespace] = useState('prod')

  const parse = useMutation({
    mutationFn: (dryRun: boolean) =>
      api.lineageParse({ sql, dialect, namespace, dryRun, recordSamples: true }),
  })

  return (
    <Card
      size="small"
      title="用 SQL 静态解析补全列级血缘"
      extra={<CapabilityBadge status="PARTIAL" small />}
    >
      <Space direction="vertical" style={{ width: '100%' }}>
        <Space wrap>
          <Input
            addonBefore="方言"
            style={{ width: 180 }}
            value={dialect}
            onChange={(event) => setDialect(event.target.value)}
          />
          <Input
            addonBefore="命名空间"
            style={{ width: 200 }}
            value={namespace}
            onChange={(event) => setNamespace(event.target.value)}
          />
          <Tooltip title="只解析不写库：先看方言解析效果，再决定是否入库">
            <a onClick={() => parse.mutate(true)}>预览解析</a>
          </Tooltip>
          <a onClick={() => parse.mutate(false)}>解析并入库</a>
        </Space>
        <Input.TextArea rows={5} value={sql} onChange={(event) => setSql(event.target.value)} className="dg-mono" />

        {parse.isError && (
          <Alert type="error" showIcon message="解析失败" description={(parse.error as Error).message} />
        )}

        {parse.data && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Space wrap>
              <Tag>语句 {parse.data.statements}</Tag>
              <Tag color={parse.data.failed > 0 ? 'red' : 'green'}>失败 {parse.data.failed}</Tag>
              <Tag color={parse.data.downgraded > 0 ? 'orange' : undefined}>降级 {parse.data.downgraded}</Tag>
              <Tag>表级边 {parse.data.tableEdges}</Tag>
              <Tag>列级边 {parse.data.columnEdges}</Tag>
              <Tag>样本 {parse.data.samplesRecorded}</Tag>
              <Tag className="dg-mono">sqlglot {parse.data.sqlglotVersion}</Tag>
            </Space>
            {parse.data.unresolvedTables.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`${parse.data.unresolvedTables.length} 个表名未解析到平台 URN（未写边）`}
                description={
                  <Space direction="vertical" size={2}>
                    <span className="dg-mono">{parse.data.unresolvedTables.join(', ')}</span>
                    <Text type="secondary">{parse.data.unresolvedNote}</Text>
                  </Space>
                }
              />
            )}
            {parse.data.note && <Text type="secondary">{parse.data.note}</Text>}
          </Space>
        )}
      </Space>
    </Card>
  )
}
