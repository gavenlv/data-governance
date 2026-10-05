import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  api,
  type AnomalyRow,
  type AnomalyScanResult,
  type IncidentRow,
  type SloRow,
} from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'
import { useTabParam } from '../hooks/useTabParam'

const { Title, Text, Paragraph } = Typography

const SEVERITY_COLOR: Record<string, string> = {
  INFO: 'default',
  LOW: 'blue',
  MEDIUM: 'orange',
  HIGH: 'red',
  CRITICAL: 'magenta',
}

/**
 * 可观测性：异常检测 → SLO 达成 → 事故闭环（docs/09 §9.4）。
 *
 * <p>界面上三件事刻意放在一条链里：检测只是起点，SLO 说明"承诺有没有守住"，
 * 事故才是"谁在处理"。三者的关系在页面上直接可见，而不是三个互不相干的看板。
 */
export default function ObservabilityPage() {
  const capabilities = useCapabilities()
  const [tab, setTab] = useTabParam('anomalies')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            可观测性
          </Title>
          <CapabilityBadge status={capabilities.get('quality.anomaly')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('quality.slo-incident')?.status ?? 'NOT_IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          指标越界（异常）→ 达成率受损（SLO）→ 开事故 → 解决时必须沉淀规则。
          三者分开做都会退化成看板，串起来才叫治理运营。
        </Text>
      </div>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'anomalies', label: '异常检测', children: <AnomalyTab /> },
          { key: 'slos', label: 'SLO 与错误预算', children: <SloTab /> },
          { key: 'incidents', label: '事故与闭环', children: <IncidentTab /> },
        ]}
      />
    </Space>
  )
}

// ------------------------------------------------------------------ 异常检测

function AnomalyTab() {
  const queryClient = useQueryClient()
  const [includeSuppressed, setIncludeSuppressed] = useState(false)
  const [scanForm] = Form.useForm()
  const [scanResult, setScanResult] = useState<AnomalyScanResult | null>(null)

  const overview = useQuery({ queryKey: ['anomaly-overview'], queryFn: () => api.anomalyOverview() })
  const list = useQuery({
    queryKey: ['anomalies', includeSuppressed],
    queryFn: () => api.anomalies(includeSuppressed, 100),
  })

  const scan = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.anomalyScan({
        datasetUrn: values.datasetUrn ? String(values.datasetUrn) : undefined,
        metric: values.metric ? String(values.metric) : undefined,
        method: (values.method as 'mad' | 'seasonal_mad') ?? 'mad',
        zThreshold: values.zThreshold ? Number(values.zThreshold) : undefined,
        lookbackDays: values.lookbackDays ? Number(values.lookbackDays) : undefined,
        seasonalPeriod: values.seasonalPeriod ? Number(values.seasonalPeriod) : undefined,
      }),
    onSuccess: (result) => {
      setScanResult(result)
      void queryClient.invalidateQueries({ queryKey: ['anomalies'] })
      void queryClient.invalidateQueries({ queryKey: ['anomaly-overview'] })
      message.success(`扫描完成：命中 ${result.detectionCount} 项，跳过 ${result.skipped.length} 条序列`)
    },
  })

  const openFromAnomalies = useMutation({
    mutationFn: () => api.incidentFromAnomalies(1440),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['incidents'] })
      Modal.info({
        title: `从最近异常开了 ${result.opened} 个事故`,
        width: 560,
        content: <Text>{String(result.note ?? '')}</Text>,
      })
    },
  })

  const bySeverity = (overview.data?.bySeverity ?? []) as { severity?: string; count?: number }[]
  const byMethod = (overview.data?.byMethod ?? []) as { method?: string; count?: number }[]
  // detectionModes 是一行对象（{mad: n, seasonal_mad: n, static_threshold: n}）：
  // 逐键渲染，避免出现 "?=0" 这种看不懂的占位
  const detectionModes = (overview.data?.detectionModes ?? []) as Record<string, number>[]
  const modes = detectionModes.length > 0 ? Object.entries(detectionModes[0]) : []
  const modeTotal = modes.reduce((sum, [, count]) => sum + Number(count ?? 0), 0)

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="误报率是第一优先级指标"
        description={
          <>
            每条检测都存下「用了什么方法、什么阈值、样本多少」，误报才能被回溯到方法而不是被当成玄学。
            <br />
            <Text strong>样本不足时不做判定</Text>
            ：少于 7 个点的序列会出现在「未判定」列表里 —— 「没判定」不等于「正常」。
          </>
        }
      />

      <Row gutter={16}>
        <Col span={4}>
          <Card size="small">
            <Statistic title="检测方式分布" value={modeTotal} />
            <Space size={4} wrap>
              {modes.length === 0 ? (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  暂无
                </Text>
              ) : (
                modes.map(([name, count]) => (
                  <Tag key={name}>
                    {name} {String(count ?? 0)}
                  </Tag>
                ))
              )}
            </Space>
          </Card>
        </Col>
        <Col span={5}>
          <Card size="small">
            <Statistic title="按严重度" value={bySeverity.reduce((sum, item) => sum + (item.count ?? 0), 0)} />
            <Space size={4} wrap>
              {bySeverity.map((item) => (
                <Tag key={String(item.severity)} color={SEVERITY_COLOR[String(item.severity)] ?? 'default'}>
                  {item.severity} {item.count}
                </Tag>
              ))}
            </Space>
          </Card>
        </Col>
        <Col span={5}>
          <Card size="small">
            <Statistic title="按方法" value={byMethod.reduce((sum, item) => sum + (item.count ?? 0), 0)} />
            <Space size={4} wrap>
              {byMethod.map((item) => (
                <Tag key={String(item.method)}>{item.method}</Tag>
              ))}
            </Space>
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="从这里可以开到事故（检测不是终点）">
            <Space>
              <Button type="primary" loading={openFromAnomalies.isPending} onClick={() => openFromAnomalies.mutate()}>
                从最近 24h 异常开事故
              </Button>
              <Text type="secondary" style={{ fontSize: 12 }}>
                同一主资产只开一个事故：一个故障开 N 个事故会让响应者无从下手
              </Text>
            </Space>
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="运行一次扫描"
        extra={
          <Text type="secondary" style={{ fontSize: 12 }}>
            L2 = MAD 稳健 Z（抗离群点）；L3 = 季节性 MAD（按周期内位置分组，解决周内规律造成的误报）
          </Text>
        }
      >
        <Form
          form={scanForm}
          layout="inline"
          initialValues={{ method: 'mad', lookbackDays: 90, zThreshold: 3 }}
          onFinish={(values) => scan.mutate(values)}
        >
          <Form.Item name="datasetUrn" label="数据集 URN">
            <Input placeholder="留空 = 全部数据集" style={{ width: 300 }} />
          </Form.Item>
          <Form.Item name="metric" label="指标">
            <Input placeholder="如 row_count" style={{ width: 140 }} />
          </Form.Item>
          <Form.Item name="method" label="方法">
            <Select
              style={{ width: 150 }}
              options={[
                { value: 'mad', label: 'mad（L2）' },
                { value: 'seasonal_mad', label: 'seasonal_mad（L3）' },
              ]}
            />
          </Form.Item>
          <Form.Item name="zThreshold" label="Z 阈值">
            <InputNumber min={1} step={0.5} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="lookbackDays" label="回看天数">
            <InputNumber min={1} max={730} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={scan.isPending}>
              扫描
            </Button>
          </Form.Item>
        </Form>

        {scanResult && (
          <div style={{ marginTop: 12 }}>
            <Alert
              type={scanResult.detectionCount > 0 ? 'warning' : 'success'}
              showIcon
              message={`扫描 ${scanResult.seriesScanned} 条序列：命中 ${scanResult.detectionCount} 项，未判定 ${scanResult.skipped.length} 条`}
              description={
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {scanResult.detections.map((item) => (
                    <Text key={item.id}>
                      <Tag color={SEVERITY_COLOR[item.severity] ?? 'default'}>{item.severity}</Tag>
                      {item.dataset.split(':').pop()} · {item.metric}：观测 {item.observed} vs 基线 {item.baseline}
                      （Z={item.score}，样本 {item.samples}）
                      {item.suppressed && <Tag color="default">已抑制：{item.suppressReason}</Tag>}
                    </Text>
                  ))}
                  {scanResult.skipped.length > 0 && (
                    <Text type="secondary">
                      未判定 {scanResult.skipped.length} 条：{scanResult.skipped[0]?.reason}（示例{' '}
                      {scanResult.skipped[0]?.series.split('|')[0].split(':').pop()}）
                    </Text>
                  )}
                </Space>
              }
            />
          </div>
        )}
      </Card>

      <Card
        size="small"
        title="检测记录"
        extra={
          <Space>
            <Tooltip title="被抑制的是由上游传导过来的下游异常：保留可追溯，但不进告警">
              <Select
                size="small"
                style={{ width: 170 }}
                value={includeSuppressed ? 'all' : 'visible'}
                onChange={(value) => setIncludeSuppressed(value === 'all')}
                options={[
                  { value: 'visible', label: '只显示生效的' },
                  { value: 'all', label: '含被抑制的' },
                ]}
              />
            </Tooltip>
            <Button size="small" onClick={() => void list.refetch()}>
              刷新
            </Button>
          </Space>
        }
      >
        <Table<AnomalyRow>
          size="small"
          rowKey="id"
          loading={list.isLoading}
          dataSource={list.data?.anomalies ?? []}
          pagination={{ pageSize: 10 }}
          columns={[
            {
              title: '资产',
              dataIndex: 'dataset_urn',
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{value.split('.').slice(-2).join('.')}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {row.column_name ? `列 ${row.column_name}` : '表级'}
                  </Text>
                </Space>
              ),
            },
            { title: '指标', dataIndex: 'metric', width: 130 },
            {
              title: '观测 / 基线',
              render: (_, row) => (
                <Text style={{ fontSize: 12 }}>
                  {row.observed} / {row.baseline}
                </Text>
              ),
            },
            {
              title: 'Z 分数',
              dataIndex: 'score',
              width: 90,
              render: (value: number | null, row) => (
                <Tooltip title={`方法 ${row.method}，阈值 ${row.threshold}，样本 ${row.samples}`}>
                  <Tag color={SEVERITY_COLOR[row.severity] ?? 'default'}>{value}</Tag>
                </Tooltip>
              ),
            },
            {
              title: '传播判定',
              dataIndex: 'propagation',
              width: 150,
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Tag color={value === 'PROPAGATED' ? 'default' : 'green'}>{value}</Tag>
                  {row.propagated_from && (
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      ← {row.propagated_from.split('.').slice(-2).join('.')}
                    </Text>
                  )}
                </Space>
              ),
            },
            {
              title: '抑制',
              dataIndex: 'suppressed',
              width: 90,
              render: (value: boolean, row) => (
                <Tooltip title={row.suppress_reason ?? '未抑制'}>
                  <Tag color={value ? 'default' : 'blue'}>{value ? '已抑制' : '生效'}</Tag>
                </Tooltip>
              ),
            },
            {
              title: '检出时间',
              dataIndex: 'detected_at',
              width: 170,
              render: (value: string) => <Text style={{ fontSize: 12 }}>{value?.replace('T', ' ').slice(0, 19)}</Text>,
            },
          ]}
        />
      </Card>
    </Space>
  )
}

// ------------------------------------------------------------------------ SLO

function SloTab() {
  const queryClient = useQueryClient()
  const [form] = Form.useForm()

  const slos = useQuery({ queryKey: ['slos'], queryFn: () => api.slos() })

  const upsert = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.sloUpsert({
        name: String(values.name),
        description: values.description ? String(values.description) : undefined,
        sloType: values.sloType,
        target: Number(values.target),
        thresholdSeconds: values.thresholdSeconds ? Number(values.thresholdSeconds) : undefined,
        windowDays: Number(values.windowDays ?? 30),
        resourceScope: { prefixes: values.prefix ? [String(values.prefix)] : [] },
        owner: values.owner ? String(values.owner) : undefined,
      }),
    onSuccess: () => {
      message.success('SLO 已保存')
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['slos'] })
    },
  })

  const measure = useMutation({
    mutationFn: (name: string) => api.sloMeasure(name),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['slos'] })
      Modal.info({
        title: `度量完成：${result.slo}`,
        width: 560,
        content: (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="达成率">
              {result.noData ? <Tag color="warning">无数据</Tag> : String(result.attainment)}
            </Descriptions.Item>
            <Descriptions.Item label="是否达标">{String(result.met)}</Descriptions.Item>
            <Descriptions.Item label="错误预算剩余">
              {result.errorBudgetRemaining === null || result.errorBudgetRemaining === undefined
                ? '—'
                : String(result.errorBudgetRemaining)}
            </Descriptions.Item>
            <Descriptions.Item label="说明">{String(result.note ?? result.budgetNote ?? '')}</Descriptions.Item>
          </Descriptions>
        ),
      })
    },
    onError: (error) => message.error(String(error)),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="达成率来自平台的实际数据，不是自报"
        description={
          <>
            新鲜度取剖析时间、通过率取规则执行（<Text code>rule_run</Text>）、可用性取采集运行（
            <Text code>collect_run</Text>）、稳定性取 schema 变更天数。
            <Text strong>没有数据时会明确回报 no_data</Text>，不给假的 100%。
            错误预算 = 1 − target，剩余为负说明已经超支。
          </>
        }
      />

      <Row gutter={16}>
        <Col span={14}>
          <Card size="small" title="SLO 列表">
            <Table<SloRow>
              size="small"
              rowKey="name"
              loading={slos.isLoading}
              dataSource={slos.data?.slos ?? []}
              pagination={false}
              columns={[
                {
                  title: 'SLO',
                  dataIndex: 'name',
                  render: (value: string, row) => {
                    const scope = row.resource_scope as Record<string, unknown> | undefined
                    const prefixes = (scope?.prefixes ?? []) as string[]
                    return (
                      <Space direction="vertical" size={0}>
                        <Text strong>{value}</Text>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {row.description ?? (prefixes.length > 0 ? `范围 ${prefixes[0]}` : '未限定范围')}
                        </Text>
                      </Space>
                    )
                  },
                },
                { title: '类型', dataIndex: 'slo_type', width: 140 },
                {
                  title: '目标',
                  dataIndex: 'target',
                  width: 90,
                  render: (value: number, row) => (
                    <Tooltip title={row.threshold_seconds ? `阈值 ${row.threshold_seconds}s` : '窗口 ' + row.window_days + ' 天'}>
                      <Text>{value}</Text>
                    </Tooltip>
                  ),
                },
                {
                  title: '最近达成率',
                  dataIndex: 'last_attainment',
                  width: 130,
                  render: (value: number | null, row) =>
                    value === null || value === undefined ? (
                      <Tag color="warning">未度量</Tag>
                    ) : (
                      <Space size={4}>
                        <Tag color={row.last_met ? 'success' : 'error'}>{value}</Tag>
                      </Space>
                    ),
                },
                {
                  title: '错误预算',
                  dataIndex: 'error_budget_remaining',
                  width: 110,
                  render: (value: number | null) =>
                    value === null || value === undefined ? (
                      '—'
                    ) : (
                      <Tag color={value < 0 ? 'error' : 'blue'}>{value}</Tag>
                    ),
                },
                {
                  title: '操作',
                  width: 110,
                  render: (_, row) => (
                    <Button size="small" loading={measure.isPending} onClick={() => measure.mutate(row.name)}>
                      度量
                    </Button>
                  ),
                },
              ]}
            />
          </Card>
        </Col>

        <Col span={10}>
          <Card size="small" title="定义 / 更新 SLO">
            <Form form={form} layout="vertical" initialValues={{ sloType: 'freshness', target: 0.95, windowDays: 30 }} onFinish={(values) => upsert.mutate(values)}>
              <Form.Item name="name" label="名称" rules={[{ required: true }]}>
                <Input placeholder="如 orders_freshness" />
              </Form.Item>
              <Form.Item name="sloType" label="类型" rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 'freshness', label: 'freshness（新鲜度，需阈值秒数）' },
                    { value: 'quality_pass_rate', label: 'quality_pass_rate（质量通过率）' },
                    { value: 'availability', label: 'availability（采集可用性）' },
                    { value: 'schema_stability', label: 'schema_stability（结构稳定性）' },
                  ]}
                />
              </Form.Item>
              <Form.Item name="target" label="目标（0–1）" rules={[{ required: true }]}>
                <InputNumber min={0.01} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="thresholdSeconds" label="新鲜度阈值（秒，仅 freshness 需要）">
                <InputNumber min={60} step={3600} style={{ width: '100%' }} placeholder="86400 = 1 天" />
              </Form.Item>
              <Form.Item name="windowDays" label="度量窗口（天）">
                <InputNumber min={1} max={365} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="prefix" label="资源范围前缀">
                <Input placeholder="urn:dg:Dataset:prod." />
              </Form.Item>
              <Form.Item name="owner" label="Owner">
                <Input placeholder="data-steward" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={upsert.isPending}>
                保存
              </Button>
            </Form>
            <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
              {String(slos.data?.note ?? '')}
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </Space>
  )
}

// ---------------------------------------------------------------------- 事故

function IncidentTab() {
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const [resolveForm] = Form.useForm()
  const [selected, setSelected] = useState<number | null>(null)
  const [resolving, setResolving] = useState<IncidentRow | null>(null)

  const incidents = useQuery({ queryKey: ['incidents'], queryFn: () => api.incidents(undefined, 100) })
  const detail = useQuery({
    queryKey: ['incident', selected],
    queryFn: () => api.incident(selected as number),
    enabled: selected !== null,
  })
  const overview = useQuery({ queryKey: ['observability-overview'], queryFn: () => api.observabilityOverview() })

  const open = useMutation({
    mutationFn: (values: Record<string, unknown>) => api.incidentOpen(values),
    onSuccess: (result) => {
      message.success(`事故 #${result.incidentId} 已创建，影响 ${result.affectedCount} 个下游`)
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['incidents'] })
    },
  })

  const resolve = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.incidentResolve(resolving?.id as number, {
        closedLoopRuleUrn: values.closedLoopRuleUrn ? String(values.closedLoopRuleUrn) : undefined,
        noRuleNeededReason: values.noRuleNeededReason ? String(values.noRuleNeededReason) : undefined,
        postmortem: values.postmortem ? { summary: String(values.postmortem) } : undefined,
      }),
    onSuccess: () => {
      message.success('事故已解决，闭环已记录')
      setResolving(null)
      resolveForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['incidents'] })
      void queryClient.invalidateQueries({ queryKey: ['observability-overview'] })
    },
    onError: (error) => message.error(String(error)),
  })

  const mttr = (overview.data?.mttrHours ?? []) as { avg_hours?: number; resolved_count?: number }[]
  const withoutRule = (overview.data?.withoutRule ?? []) as { id: number; title: string }[]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="平均 MTTR（小时）" value={mttr[0]?.avg_hours ?? '—'} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              已解决 {mttr[0]?.resolved_count ?? 0} 个
            </Text>
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="已解决但没有沉淀规则的事故">
            {withoutRule.length === 0 ? (
              <Text type="secondary">无 —— 这是闭环机制真正在起作用的信号</Text>
            ) : (
              <Space direction="vertical" size={2}>
                {withoutRule.map((item) => (
                  <Text key={item.id} type="warning" style={{ fontSize: 12 }}>
                    #{item.id} {item.title}
                  </Text>
                ))}
              </Space>
            )}
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" title="开新事故（影响面自动算）">
            <Form form={form} layout="vertical" initialValues={{ severity: 'MEDIUM', source: 'manual' }} onFinish={(values) => open.mutate(values)}>
              <Form.Item name="title" label="标题" rules={[{ required: true }]}>
                <Input placeholder="如 orders 表行数异常" />
              </Form.Item>
              <Form.Item name="primaryUrn" label="主资产 URN（用于算影响面）">
                <Input placeholder="urn:dg:Dataset:..." />
              </Form.Item>
              <Space>
                <Form.Item name="severity" label="严重度">
                  <Select
                    style={{ width: 130 }}
                    options={['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((value) => ({ value, label: value }))}
                  />
                </Form.Item>
                <Form.Item name="source" label="来源">
                  <Select
                    style={{ width: 150 }}
                    options={[
                      { value: 'manual', label: 'manual' },
                      { value: 'anomaly', label: 'anomaly' },
                      { value: 'slo', label: 'slo' },
                      { value: 'contract_violation', label: 'contract_violation' },
                    ]}
                  />
                </Form.Item>
              </Space>
              <Form.Item name="owner" label="处理人">
                <Input placeholder="on-call" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={open.isPending}>
                开事故
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="事故列表">
        <Table<IncidentRow>
          size="small"
          rowKey="id"
          loading={incidents.isLoading}
          dataSource={incidents.data?.incidents ?? []}
          pagination={{ pageSize: 10 }}
          columns={[
            { title: '#', dataIndex: 'id', width: 70 },
            {
              title: '标题',
              dataIndex: 'title',
              render: (value: string, row) => (
                <Button type="link" style={{ padding: 0 }} onClick={() => setSelected(row.id)}>
                  {value}
                </Button>
              ),
            },
            {
              title: '严重度',
              dataIndex: 'severity',
              width: 100,
              render: (value: string) => <Tag color={SEVERITY_COLOR[value] ?? 'default'}>{value}</Tag>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (value: string) => <Tag color={value === 'RESOLVED' ? 'success' : 'processing'}>{value}</Tag>,
            },
            {
              title: '影响资产',
              dataIndex: 'affected_urns',
              width: 100,
              render: (value: string[]) => <Tag>{Array.isArray(value) ? value.length : 0}</Tag>,
            },
            {
              title: '闭环规则',
              dataIndex: 'closed_loop_rule_urn',
              width: 180,
              render: (value: string | null, row) =>
                value ? (
                  <Text style={{ fontSize: 12 }}>{value.split(':').pop()}</Text>
                ) : row.status === 'RESOLVED' ? (
                  <Tag color="warning">未沉淀规则</Tag>
                ) : (
                  <Text type="secondary">—</Text>
                ),
            },
            {
              title: '操作',
              width: 100,
              render: (_, row) =>
                row.status === 'RESOLVED' ? (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    已闭环
                  </Text>
                ) : (
                  <Button size="small" type="primary" onClick={() => setResolving(row)}>
                    解决
                  </Button>
                ),
            },
          ]}
        />
      </Card>

      <Drawer
        width={620}
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={detail.data ? `事故 #${detail.data.id}：${detail.data.title}` : '事故详情'}
      >
        {detail.data && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="状态">{detail.data.status}</Descriptions.Item>
              <Descriptions.Item label="严重度">
                <Tag color={SEVERITY_COLOR[detail.data.severity] ?? 'default'}>{detail.data.severity}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="主资产">{detail.data.primary_urn ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="影响面">
                {Array.isArray(detail.data.affected_urns) && detail.data.affected_urns.length > 0 ? (
                  <Space direction="vertical" size={2}>
                    {detail.data.affected_urns.slice(0, 12).map((urn) => (
                      <Text key={urn} style={{ fontSize: 12 }}>
                        {urn}
                      </Text>
                    ))}
                    {detail.data.affected_urns.length > 12 && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        其余 {detail.data.affected_urns.length - 12} 个略
                      </Text>
                    )}
                  </Space>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="闭环规则">{detail.data.closed_loop_rule_urn ?? '（未沉淀）'}</Descriptions.Item>
            </Descriptions>

            <div>
              <Title level={5}>时间线</Title>
              <Timeline
                items={(detail.data.timeline ?? []).map((event) => ({
                  children: (
                    <Space direction="vertical" size={2}>
                      <Space size={6}>
                        <Tag>{event.event_type}</Tag>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {event.occurred_at?.replace('T', ' ').slice(0, 19)} · {event.actor ?? '—'}
                        </Text>
                      </Space>
                      <Text style={{ fontSize: 13 }}>{event.message}</Text>
                    </Space>
                  ),
                }))}
              />
            </div>
          </Space>
        )}
      </Drawer>

      <Modal
        open={resolving !== null}
        title={`解决事故 #${resolving?.id ?? ''}`}
        onCancel={() => setResolving(null)}
        onOk={() => resolveForm.submit()}
        confirmLoading={resolve.isPending}
        okText="提交闭环"
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="闭环要求：二选一"
          description="关联一条沉淀出来的规则/检测器，或说明为什么这次不需要新规则 —— 没有这一步，事故处理完什么都不会改进。"
        />
        <Form form={resolveForm} layout="vertical" onFinish={(values) => resolve.mutate(values)}>
          <Form.Item name="closedLoopRuleUrn" label="沉淀出的规则 URN">
            <Input placeholder="urn:dg:QualityRule:..." />
          </Form.Item>
          <Form.Item name="noRuleNeededReason" label="或：为什么不需要新规则">
            <Input placeholder="如：本次为上游系统发布导致，已由对方流程覆盖" />
          </Form.Item>
          <Form.Item name="postmortem" label="复盘摘要">
            <Input.TextArea rows={3} placeholder="根因、影响、后续动作" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
