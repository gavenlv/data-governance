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
  Spin,
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
import { useTabParam } from '../hooks/useTabParam'

const { Title, Text, Paragraph } = Typography

/** 血缘：交互式探索画布、影响分析、SQL 解析入库（已实现）。 */
export default function LineagePage() {
  const capabilities = useCapabilities()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  // 页签可深链（?tab=quality）：既能分享"血缘质量"这一步，也让逐标签页的渲染检查可覆盖到它
  const [tab, setTab] = useTabParam('impact')
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
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'impact', label: '影响分析', children: <ImpactTab direction={direction} depth={depth} onUrn={setTableUrn} impact={impact} /> },
          { key: 'quality', label: '血缘质量', children: <QualityTab qualityData={qualityData} sidecarHealth={sidecarHealth} /> },
          { key: 'parse', label: 'SQL 解析入库', children: <SqlParseCard /> },
          { key: 'review', label: '审查与健康', children: <ReviewTab /> },
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

/**
 * 审查与健康：回答「这张图里有多少条边没人确认过」。
 *
 * 为什么单独一个页签：血缘的主要风险不是"画不出来"，而是"画出来了但没人核实"。
 * 推断出来的边会越用越像真的，最后用来做变更决策 —— 这里让"未核实"始终可见。
 */
function ReviewTab() {
  const review = useQuery({ queryKey: ['lineage-review'], queryFn: () => api.lineageReview({ limit: 100 }) })
  const health = useQuery({ queryKey: ['lineage-health'], queryFn: () => api.lineageHealth(30) })
  const [pathFrom, setPathFrom] = useState('')
  const [pathTo, setPathTo] = useState('')
  const pathQuery = useQuery({ queryKey: ['lineage-path', pathFrom, pathTo], queryFn: () => api.lineagePath(pathFrom, pathTo, 6), enabled: false })
  const unconfirmed = (health.data?.unconfirmed ?? {}) as {
    pendingReview?: number
    lowConfidenceUnconfirmed?: number
    oldestWaitingDays?: number | null
  }
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="未确认 ≠ 错误，但「未确认」必须一直可见"
        description="低置信度边来自静态解析或推断（虚线/点线）；确认后置信度升到人工级，驳回只做标记、不删除记录 —— 否则同一条错误边会被反复采集回来。"
      />
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="待复核（显式标记）" value={unconfirmed.pendingReview ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="低置信度且从未确认" value={unconfirmed.lowConfidenceUnconfirmed ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="最老一条等待（天）"
              value={unconfirmed.oldestWaitingDays ?? '—'}
              valueStyle={{ color: (unconfirmed.oldestWaitingDays ?? 0) > 14 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="窗口内检查发现" value={Number(health.data?.checksOpen ?? 0)} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="列级路径追踪（这个指标从哪个字段算出来的）">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Text type="secondary">
            只沿 ACTIVE 边向前追踪；整条链路的可信度取**最弱的一环**。找不到时会区分「深度内没有路径」与「这条路径更深」。
          </Text>
          <Input placeholder="起点 URN（上游，例如 urn:dg:Column:ns.platform.db.schema.table.column）" value={pathFrom} onChange={(e) => setPathFrom(e.target.value)} />
          <Input placeholder="终点 URN（下游）" value={pathTo} onChange={(e) => setPathTo(e.target.value)} />
          <Input.Search
            enterButton="追踪路径"
            loading={pathQuery.isFetching}
            onSearch={() => { if (pathFrom && pathTo) pathQuery.refetch() }}
          />
          {pathQuery.data && (
            <Alert
              type={pathQuery.data.found ? 'success' : 'warning'}
              showIcon
              message={pathQuery.data.found
                ? `找到 ${pathQuery.data.hops} 跳，最弱一环可信度 ${pathQuery.data.minConfidence}`
                : '深度内没有找到路径'}
              description={pathQuery.data.found
                ? String((pathQuery.data.path as { from: string; to: string }[]).map((hop) => hop.to.split(':').pop()).join(' → '))
                : String(pathQuery.data.note ?? '')}
            />
          )}
        </Space>
      </Card>      <Card size="small" title="未确认血缘队列">
        <Table
          size="small"
          rowKey={(row) => String(row.edgeId)}
          loading={review.isLoading}
          dataSource={review.data?.edges ?? []}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: '队列为空：当前没有需要人工判断的边（这本身是个好信号）' }}
          columns={[
            { title: '原因', dataIndex: 'reason', width: 190, render: (value: string, row) => (
              <Tooltip title={row.reasonText}>
                <Tag color={value === 'pending_review' ? 'orange' : 'blue'}>
                  {value === 'pending_review' ? '待复核' : '低置信度未确认'}
                </Tag>
              </Tooltip>
            ) },
            { title: '来源', dataIndex: 'source', width: 120 },
            { title: '置信度', dataIndex: 'confidence', width: 90,
              render: (value: number) => value.toFixed(2) },
            { title: '粒度', dataIndex: 'parseLevel', width: 110, render: (v: string | null) => v ?? '—' },
            { title: '边', render: (_: unknown, row) => (
              <Text type="secondary" ellipsis={{ tooltip: `${row.fromUrn} → ${row.toUrn}` }}>
                {row.fromUrn.split(':').pop()} → {row.toUrn.split(':').pop()}
              </Text>
            ) },
            { title: '首次观测', dataIndex: 'firstSeen', width: 170,
              render: (v: string | null) => (v ? v.slice(0, 19).replace('T', ' ') : '—') },
          ]}
        />
      </Card>
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
      <Card
        size="small"
        title="血缘 L2 检查（把「覆盖率为什么低」分成能补的和改不了的）"
        extra={
          <Text type="secondary" style={{ fontSize: 12 }}>
            用平台已采集的 schema 补出解析器做不到的部分；推不出来就记账，不猜
          </Text>
        }
      >
        <L2ChecksPanel />
      </Card>

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

/**
 * 血缘 L2 检查面板。
 *
 * <p>刻意把"能补的"（缺 schema → 采集即可自动消失）与"需改 SQL 的"（列在多个上游表里都有）
 * 分成两栏：只有分开，这份清单才能被排期；混在一起就只是一堆数字。
 */
function L2ChecksPanel() {
  const checks = useQuery({
    queryKey: ['lineage-checks'],
    queryFn: () => api.lineageChecks({ days: 30, limit: 50 }),
  })

  if (checks.isLoading) {
    return <Spin size="small" />
  }
  const summary = checks.data?.summary
  if (!summary || (checks.data?.count ?? 0) === 0) {
    return (
      <Text type="secondary">
        近 30 天没有 L2 检查发现：解析器给出的结果都是 exact，或还没解析过 SQL。
      </Text>
    )
  }

  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      <Space size={8} wrap>
        {summary.byType.map((row) => (
          <Tooltip key={row.check_type} title={summary.legend?.[row.check_type] ?? row.check_type}>
            <Tag color={row.check_type.endsWith('_unresolved') ? 'warning' : 'blue'}>
              {row.check_type} × {row.count}
            </Tag>
          </Tooltip>
        ))}
      </Space>

      <Row gutter={12}>
        <Col span={12}>
          <Card size="small" type="inner" title={`能靠采集补上（${summary.actionable.length}）`}>
            {summary.actionable.length === 0 ? (
              <Text type="secondary">无</Text>
            ) : (
              <List
                size="small"
                dataSource={summary.actionable}
                renderItem={(item) => (
                  <List.Item>
                    <Text style={{ fontSize: 12 }}>
                      {String(item.target_urn ?? '').split('.').slice(-2).join('.')}
                      {item.resource ? ` · ${item.resource}` : ''} × {item.count}
                    </Text>
                  </List.Item>
                )}
              />
            )}
            <Text type="secondary" style={{ fontSize: 12 }}>
              这些发现会在对应上游表被采集后自动消失
            </Text>
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small" type="inner" title={`需要改 SQL（${summary.needsSqlFix.length}）`}>
            {summary.needsSqlFix.length === 0 ? (
              <Text type="secondary">无</Text>
            ) : (
              <List
                size="small"
                dataSource={summary.needsSqlFix}
                renderItem={(item) => (
                  <List.Item>
                    <Text style={{ fontSize: 12 }}>
                      {String(item.target_urn ?? '').split('.').slice(-2).join('.')}
                      {item.resource ? ` · 列 ${item.resource}` : ''} × {item.count}
                    </Text>
                  </List.Item>
                )}
              />
            )}
            <Text type="secondary" style={{ fontSize: 12 }}>
              列在多个上游表里都存在：平台**不猜**（猜错的边比缺失的边更难发现），需补列限定
            </Text>
          </Card>
        </Col>
      </Row>

      <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
        {summary.note}
      </Paragraph>
    </Space>
  )
}