import { useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  Button,
  message,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type CompiledRulePreview, type ProfileResult, type QualityRuleRow, type RuleRunRow } from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { NotImplementedCard } from '../components/NotImplementedCard'
import { useCapabilities } from '../hooks/useCapabilities'

const { Title, Text, Paragraph } = Typography

/**
 * 质量与契约（docs/09 §9.4–9.5）。
 *
 * Batch 2 已实现：剖析（含精度标注与隐私约束）、规则引擎（三种前端 → 统一 IR → 源库 SQL）、
 * 契约（ODCS + 兼容性 diff + 违约事件 + 消费者）、CI 门禁。
 * 异常检测与 SLO/事故仍为占位 —— 界面按能力清单渲染，不自己维护"做完了哪些"。
 */
export default function QualityPage() {
  const capabilities = useCapabilities()

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            质量与契约
          </Title>
          <CapabilityBadge status={capabilities.get('quality.rules')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('quality.profiling')?.status ?? 'NOT_IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          docs/09 §9.4（质量与可观测）与 §9.5（数据契约）。规则推到源系统执行，平台不搬数据。
        </Text>
      </div>

      <Tabs
        items={[
          { key: 'overview', label: '概览', children: <OverviewTab /> },
          { key: 'profiling', label: '剖析', children: <ProfilingTab /> },
          { key: 'rules', label: '规则', children: <RulesTab /> },
          { key: 'runs', label: '执行记录', children: <RunsTab /> },
        ]}
      />

      <List
        header={<Text strong>仍未实现（Phase 2，Batch 5）</Text>}
        grid={{ gutter: 16, column: 2 }}
        dataSource={['quality.anomaly', 'quality.slo-incident']}
        renderItem={(id) => (
          <List.Item>
            <NotImplementedCard capability={capabilities.get(id)} />
          </List.Item>
        )}
      />
    </Space>
  )
}

function OverviewTab() {
  const overview = useQuery({ queryKey: ['quality-overview'], queryFn: () => api.qualityOverview() })
  const data = overview.data

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {overview.isError && <Alert type="error" showIcon message={(overview.error as Error).message} />}
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="规则总数" value={data?.rules ?? 0} suffix={`/ 启用 ${data?.enabledRules ?? 0}`} />
          </Card>
        </Col>
        {(['PASS', 'FAIL', 'SKIPPED', 'ERROR'] as const).map((status) => (
          <Col span={4} key={status}>
            <Card size="small">
              <Statistic
                title={`近 7 天 ${status}`}
                value={(data?.recentRuns ?? []).find((item) => item.status === status)?.count ?? 0}
                valueStyle={{
                  color: status === 'PASS' ? '#52c41a' : status === 'FAIL' ? '#ff4d4f' : undefined,
                }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      <Card size="small" title="规则最近状态分布">
        <Space wrap>
          {(data?.byLastStatus ?? []).map((item) => (
            <Tag key={item.status} color={item.status === 'PASS' ? 'green' : item.status === 'FAIL' ? 'red' : 'orange'}>
              {item.status}：{item.count}
            </Tag>
          ))}
        </Space>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {data?.note}
        </Paragraph>
      </Card>

      <Card size="small" title="最近失败 / 无法判定">
        <Table<RuleRunRow>
          size="small"
          rowKey="run_id"
          loading={overview.isLoading}
          dataSource={(data?.recentFailures ?? []) as unknown as RuleRunRow[]}
          pagination={false}
          locale={{ emptyText: <Empty description="最近没有失败" /> }}
          columns={[
            { title: '规则', dataIndex: 'rule_urn', render: (value: string) => <span className="dg-urn">{value}</span> },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (value: string) => <Tag color={value === 'FAIL' ? 'red' : 'orange'}>{value}</Tag>,
            },
            { title: '观察值', dataIndex: 'observed', width: 100 },
            { title: '期望', dataIndex: 'expected', width: 160 },
            { title: '原因', dataIndex: 'error' },
          ]}
        />
      </Card>
    </Space>
  )
}

function ProfilingTab() {
  const capabilities = useCapabilities()
  const [form] = Form.useForm()
  const [result, setResult] = useState<ProfileResult | null>(null)

  const profile = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.qualityProfile({
        jdbcUrl: String(values.jdbcUrl),
        username: values.username ? String(values.username) : undefined,
        password: values.password ? String(values.password) : undefined,
        datasetUrns: String(values.datasetUrn)
          .split(/[\n,]/)
          .map((item) => item.trim())
          .filter(Boolean),
        samplePercent: Number(values.samplePercent ?? 100),
        includeTopK: Boolean(values.includeTopK),
      }),
    onSuccess: (data) => {
      setResult(data)
      message.success(`剖析完成：${data.datasets.length} 个数据集，${data.durationMs}ms`)
    },
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="触发剖析"
        extra={<CapabilityBadge status={capabilities.get('quality.profiling')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Form
          form={form}
          layout="inline"
          initialValues={{
            jdbcUrl: 'jdbc:postgresql://localhost:25011/dg',
            username: 'postgres',
            password: 'root',
            samplePercent: 100,
            includeTopK: true,
          }}
          onFinish={(values) => profile.mutate(values)}
        >
          <Form.Item name="jdbcUrl" label="JDBC URL" rules={[{ required: true }]}>
            <Input style={{ width: 340 }} />
          </Form.Item>
          <Form.Item name="username" label="用户">
            <Input style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="password" label="口令">
            <Input.Password style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="samplePercent" label="采样 %">
            <InputNumber min={1} max={100} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="includeTopK" label="Top-K">
            <Select
              style={{ width: 90 }}
              options={[
                { value: true, label: '是' },
                { value: false, label: '否' },
              ]}
            />
          </Form.Item>
          <Form.Item name="datasetUrn" label="数据集 URN" rules={[{ required: true }]}>
            <Input.TextArea rows={1} style={{ width: 460 }} placeholder="每行一个 URN" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={profile.isPending}>
              开始剖析
            </Button>
          </Form.Item>
        </Form>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          采样优先哈希取模（可重复、可分片），TABLESAMPLE 次之，<Text strong>不用 LIMIT 头部采样</Text>（有偏）。
          采样结果一律标 ESTIMATED，且<Text strong>不做总体外推</Text>。
        </Paragraph>
      </Card>

      {result && (
        <>
          <Alert
            type="info"
            showIcon
            message="精度口径"
            description={result.precisionNote}
          />
          {result.datasets.map((dataset) => (
            <Card
              key={dataset.urn}
              size="small"
              title={<span className="dg-urn">{dataset.urn}</span>}
              extra={
                <Space>
                  <Tooltip title={dataset.sampling.note}>
                    <Tag color={dataset.sampling.precision === 'EXACT' ? 'green' : 'orange'}>
                      {dataset.sampling.precision} · {dataset.sampling.method}
                    </Tag>
                  </Tooltip>
                  <Tag>行数 {dataset.rowCount}</Tag>
                  {dataset.rowCountEstimate !== undefined && (
                    <Tooltip title={dataset.rowCountEstimateSource}>
                      <Tag color="default">估算 {dataset.rowCountEstimate}</Tag>
                    </Tooltip>
                  )}
                </Space>
              }
            >
              <Table<ProfileResult['datasets'][number]['columns'][number]>
                size="small"
                rowKey="name"
                dataSource={dataset.columns}
                pagination={{ pageSize: 15, showSizeChanger: false }}
                columns={[
                  { title: '列', dataIndex: 'name', width: 180 },
                  { title: '类型', dataIndex: 'type', width: 150 },
                  {
                    title: '分级',
                    dataIndex: 'classification',
                    width: 80,
                    render: (value: string | null) =>
                      value ? <Tag color={value === 'L4' ? 'red' : undefined}>{value}</Tag> : <Text type="secondary">L2（默认）</Text>,
                  },
                  {
                    title: '空值率',
                    width: 100,
                    render: (_, row) =>
                      row.metrics.null_ratio !== undefined
                        ? `${(row.metrics.null_ratio * 100).toFixed(2)}%`
                        : '—',
                  },
                  {
                    title: '唯一值',
                    width: 100,
                    render: (_, row) => row.metrics.distinct_count ?? '—',
                  },
                  {
                    title: 'min / max',
                    render: (_, row) =>
                      row.valueSuppressed ? (
                        <Tooltip title={row.valueSuppressed}>
                          <Text type="secondary">按类型/隐私约束未输出</Text>
                        </Tooltip>
                      ) : (
                        <span className="dg-mono">
                          {String(row.minValue ?? '—')} ~ {String(row.maxValue ?? '—')}
                        </span>
                      ),
                  },
                  {
                    title: 'Top-K',
                    render: (_, row) =>
                      row.topK ? (
                        <Space direction="vertical" size={0}>
                          {row.topK.slice(0, 3).map((item) => (
                            <Text key={item.value} style={{ fontSize: 12 }}>
                              {item.value} × {item.count}
                            </Text>
                          ))}
                        </Space>
                      ) : (
                        <Text type="secondary">—</Text>
                      ),
                  },
                ]}
              />
            </Card>
          ))}
        </>
      )}
    </Space>
  )
}

function RulesTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [frontend, setFrontend] = useState<'yaml' | 'sql_assertion' | 'dbt_test'>('yaml')
  const [preview, setPreview] = useState<CompiledRulePreview[]>([])
  const [yamlText, setYamlText] = useState(
    'rule: event_log_quality\nchecks:\n  - {type: notNull, column: event_type}\n  - {type: uniqueness, columns: [seq], threshold: 0.999}\n  - {type: freshness, column: created_at, maxLag: PT24H}',
  )
  const [datasetUrn, setDatasetUrn] = useState('')
  const [namespace, setNamespace] = useState('java_e2e')
  const [dsn, setDsn] = useState('env:DG_SOURCE_DSN')

  const rules = useQuery({ queryKey: ['quality-rules'], queryFn: () => api.qualityRules() })

  const compile = useMutation({
    mutationFn: async () => {
      if (frontend === 'yaml') {
        const document = parseLooseYaml(yamlText)
        return api.qualityCompile({ frontend: 'yaml', document, datasetUrn })
      }
      if (frontend === 'sql_assertion') {
        return api.qualityCompile({
          frontend: 'sql_assertion',
          ruleId: 'sql_assertion_rule',
          datasetUrn,
          sql: yamlText,
          expect: 0,
          severity: 'HIGH',
        })
      }
      return api.qualityCompile({
        frontend: 'dbt_test',
        modelName: 'model',
        datasetUrn,
        tests: parseLooseYaml(yamlText) as unknown as Record<string, unknown>[],
      })
    },
    onSuccess: (data) => setPreview(data.rules),
    onError: (error: Error) => message.error(error.message),
  })

  const register = useMutation({
    mutationFn: async () => {
      const document = frontend === 'yaml' ? parseLooseYaml(yamlText) : undefined
      return api.qualityRegisterRules(
        frontend === 'yaml'
          ? { frontend, document, datasetUrn, namespace, dsn }
          : frontend === 'sql_assertion'
            ? { frontend, ruleId: 'sql_assertion_rule', datasetUrn, namespace, dsn, sql: yamlText, expect: 0, severity: 'HIGH' }
            : { frontend, modelName: 'model', datasetUrn, namespace, dsn, tests: parseLooseYaml(yamlText) },
      )
    },
    onSuccess: (data) => {
      if (data.rejected.length > 0) {
        message.warning(`注册 ${data.registered.length} 条，被拒 ${data.rejected.length} 条`)
      } else {
        message.success(`已注册 ${data.registered.length} 条规则`)
      }
      void queryClient.invalidateQueries({ queryKey: ['quality-rules'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const runNow = useMutation({
    mutationFn: (urn: string) => api.qualityRunRule(urn),
    onSuccess: (result) => {
      message.info(`规则执行：${result.status}（观察值 ${result.observed ?? '—'}）`)
      void queryClient.invalidateQueries({ queryKey: ['quality-rules'] })
      void queryClient.invalidateQueries({ queryKey: ['quality-overview'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="注册规则（三种前端语法都编译到统一 IR）"
        extra={<CapabilityBadge status={capabilities.get('quality.rules')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Space wrap>
            <Select
              value={frontend}
              style={{ width: 190 }}
              onChange={setFrontend}
              options={[
                { value: 'yaml', label: 'YAML 声明式' },
                { value: 'sql_assertion', label: 'SQL 断言' },
                { value: 'dbt_test', label: 'dbt tests' },
              ]}
            />
            <Input
              addonBefore="数据集 URN"
              style={{ width: 520 }}
              value={datasetUrn}
              onChange={(event) => setDatasetUrn(event.target.value)}
              placeholder="urn:dg:Dataset:ns.platform.db.schema.table"
            />
            <Input addonBefore="命名空间" style={{ width: 200 }} value={namespace} onChange={(e) => setNamespace(e.target.value)} />
            <Input addonBefore="执行目标 DSN" style={{ width: 380 }} value={dsn} onChange={(e) => setDsn(e.target.value)} />
          </Space>
          <Input.TextArea
            rows={frontend === 'yaml' ? 8 : 4}
            className="dg-mono"
            value={yamlText}
            onChange={(event) => setYamlText(event.target.value)}
          />
          <Space>
            <Button onClick={() => compile.mutate()} loading={compile.isPending}>
              编译预览
            </Button>
            <Button type="primary" onClick={() => register.mutate()} loading={register.isPending}>
              注册并排期
            </Button>
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>
            YAML 与 dbt tests 用简化的 YAML/JSON 语法。规则会先在平台侧编译一次：编译不过的规则不会落库
            （否则会在调度时才炸）。
          </Text>
        </Space>
      </Card>

      {preview.length > 0 && (
        <Card size="small" title="编译产物（这就是实际会推到源库执行的 SQL）">
          {preview.map((item) => (
            <Card key={item.ruleId} size="small" type="inner" title={item.ruleId} style={{ marginBottom: 8 }}>
              <Descriptions column={3} size="small">
                <Descriptions.Item label="指标">{item.metric}</Descriptions.Item>
                <Descriptions.Item label="期望">{item.expected}</Descriptions.Item>
                <Descriptions.Item label="来源前端">{item.sourceFrontend}</Descriptions.Item>
              </Descriptions>
              {item.needsBaseline && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 8 }}
                  message="相对判定：需要窗口基线"
                  description="首次执行或历史不足时会记为 SKIPPED（不是 PASS）——「没跑成」必须显式暴露。"
                />
              )}
              <pre className="dg-mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {item.sql ?? item.compileError}
              </pre>
            </Card>
          ))}
        </Card>
      )}

      <Card size="small" title="已注册规则">
        {rules.isError && <Alert type="error" showIcon message={(rules.error as Error).message} />}
        <Table<QualityRuleRow>
          size="small"
          rowKey="urn"
          loading={rules.isLoading}
          dataSource={rules.data?.rules ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有规则" /> }}
          columns={[
            { title: '规则', dataIndex: 'ruleId', width: 180 },
            { title: '指标', dataIndex: 'metric', width: 140 },
            {
              title: '期望',
              width: 130,
              render: (_, row) => `${row.operator ?? ''} ${row.threshold ?? '—'}`,
            },
            {
              title: '级别 / 动作',
              width: 150,
              render: (_, row) => (
                <Space size={4}>
                  <Tag>{row.severity}</Tag>
                  <Tag color={row.onFail === 'BLOCK' ? 'red' : row.onFail === 'ALERT' ? 'orange' : undefined}>
                    {row.onFail}
                  </Tag>
                </Space>
              ),
            },
            { title: '来源', dataIndex: 'sourceFrontend', width: 120 },
            { title: 'cron', dataIndex: 'cron', width: 120 },
            {
              title: '上次',
              dataIndex: 'lastStatus',
              width: 100,
              render: (value: string | null) =>
                value ? (
                  <Tag color={value === 'PASS' ? 'green' : value === 'FAIL' ? 'red' : 'orange'}>{value}</Tag>
                ) : (
                  <Text type="secondary">从未执行</Text>
                ),
            },
            {
              title: '操作',
              width: 90,
              render: (_, row) => <a onClick={() => runNow.mutate(row.urn)}>立即执行</a>,
            },
          ]}
        />
      </Card>
    </Space>
  )
}

function RunsTab() {
  const runs = useQuery({ queryKey: ['quality-runs'], queryFn: () => api.qualityRuns(100) })

  return (
    <Card size="small" title="规则执行记录（含编译产物与观察值）">
      {runs.isError && <Alert type="error" showIcon message={(runs.error as Error).message} />}
      <Table<RuleRunRow>
        size="small"
        rowKey="run_id"
        loading={runs.isLoading}
        dataSource={runs.data?.runs ?? []}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        locale={{ emptyText: <Empty description="还没有执行记录" /> }}
        columns={[
          {
            title: '时间',
            dataIndex: 'started_at',
            width: 170,
            render: (value: string) => <span className="dg-mono">{String(value).slice(0, 19).replace('T', ' ')}</span>,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (value: string) => (
              <Tag color={value === 'PASS' ? 'green' : value === 'FAIL' ? 'red' : 'orange'}>{value}</Tag>
            ),
          },
          { title: '规则', dataIndex: 'rule_urn', render: (value: string) => <span className="dg-urn">{value}</span> },
          { title: '指标', dataIndex: 'metric', width: 130 },
          { title: '观察值', dataIndex: 'observed', width: 100 },
          { title: '期望', dataIndex: 'expected', width: 150 },
          { title: '耗时(ms)', dataIndex: 'duration_ms', width: 100 },
        ]}
      />
    </Card>
  )
}

/**
 * 简化的 YAML/JSON 解析：支持以 `key: value`、缩进列表与 `- {…}` 流式映射写的规则片段。
 *
 * 为什么不用成熟的 YAML 库：前端引入 yaml 解析器会让本已 1.1MB 的包再涨一截，
 * 而这里只需要"够用且可预期"的子集；解析失败会给出明确报错，不会静默变成空规则。
 */
function parseLooseYaml(text: string): Record<string, unknown> {
  const trimmed = text.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    return JSON.parse(trimmed)
  }
  const out: Record<string, unknown> = {}
  let currentList: string | null = null
  for (const rawLine of trimmed.split('\n')) {
    const line = rawLine.replace(/\s+#.*$/, '')
    if (!line.trim()) continue
    const listMatch = line.match(/^\s*-\s*(\{.*\})\s*$/)
    if (listMatch && currentList) {
      ;(out[currentList] as Record<string, unknown>[]).push(
        JSON.parse(listMatch[1].replace(/([{,]\s*)([A-Za-z_][\w]*)\s*:/g, '$1"$2":').replace(/'/g, '"')),
      )
      continue
    }
    const listHeader = line.match(/^([A-Za-z_][\w]*)\s*:\s*$/)
    if (listHeader) {
      currentList = listHeader[1]
      out[currentList] = []
      continue
    }
    const pair = line.match(/^([A-Za-z_][\w]*)\s*:\s*(.+)$/)
    if (pair) {
      currentList = null
      out[pair[1]] = coerce(pair[2].trim())
    }
  }
  return out
}

function coerce(value: string): unknown {
  if (/^-?\d+(\.\d+)?$/.test(value)) return Number(value)
  if (value === 'true') return true
  if (value === 'false') return false
  if (value.startsWith('[')) {
    return value
      .slice(1, -1)
      .split(',')
      .map((item) => coerce(item.trim().replace(/^["']|["']$/g, '')))
      .filter((item) => item !== '')
  }
  return value.replace(/^["']|["']$/g, '')
}
