import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  api,
  type HybridSearchResult,
  type McpTools,
  type SemanticMetricRow,
  type SuggestionRow,
} from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'
import { useTabParam } from '../hooks/useTabParam'

const { Title, Text, Paragraph } = Typography

/**
 * AI 原生能力（docs/13）。
 *
 * <p>页面第一件事是**如实说明哪些 AI 能力真的可用**：没有向量检索就说没有，
 * 没配大模型就说没配。没有 provenance 的 AI 输出在治理场景是负资产 ——
 * 使用者无法判断该不该信。
 */
export default function AiPage() {
  const capabilities = useCapabilities()
  const status = useQuery({ queryKey: ['ai-status'], queryFn: () => api.aiStatus() })
  const [tab, setTab] = useTabParam('inbox')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            AI 与 Agent
          </Title>
          <CapabilityBadge status={capabilities.get('ai.suggestion')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('ai.semantic-search')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('ai.mcp')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('ai.semantic-layer')?.status ?? 'NOT_IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          纪律：<Text strong>AI 只写「建议」，绝不直接改元数据</Text>
          ；每条建议都带依据（rationale）、置信度与来源规则，人工采纳后才落 aspect。
        </Text>
      </div>

      {status.data && (
        <Row gutter={16}>
          <Col span={6}>
            <Card size="small">
              <Statistic
                title="规则型建议生成器"
                value={status.data.suggestionDeterministic.available ? '可用' : '不可用'}
                valueStyle={{ fontSize: 18 }}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>
                {status.data.suggestionDeterministic.note}
              </Text>
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic
                title="大模型生成"
                value={status.data.suggestionLlm.configured ? '已配置' : '未配置'}
                valueStyle={{ fontSize: 18, color: status.data.suggestionLlm.configured ? undefined : '#d46b08' }}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>
                {status.data.suggestionLlm.configured
                  ? '已配置端点，可调用 /suggestions/llm'
                  : status.data.suggestionLlm.howToEnable}
              </Text>
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic
                title="向量召回"
                value={status.data.semanticSearch.vector ? '可用' : '未实现'}
                valueStyle={{ fontSize: 18, color: '#8c8c8c' }}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>
                {status.data.semanticSearch.note}
              </Text>
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic title="MCP 子集" value={status.data.mcp.available ? '可用' : '不可用'} valueStyle={{ fontSize: 18 }} />
              <Text type="secondary" style={{ fontSize: 12 }}>
                {status.data.mcp.note}
              </Text>
            </Card>
          </Col>
        </Row>
      )}

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'inbox', label: '建议收件箱', children: <SuggestionTab /> },
          { key: 'search', label: '混合检索', children: <HybridSearchTab /> },
          { key: 'metrics', label: '语义层指标', children: <MetricTab /> },
          { key: 'mcp', label: 'MCP 工具（Agent 出口）', children: <McpTab /> },
        ]}
      />
    </Space>
  )
}

// ---------------------------------------------------------------- 建议收件箱

function SuggestionTab() {
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState('PENDING')
  const [rejecting, setRejecting] = useState<SuggestionRow | null>(null)
  const [rejectForm] = Form.useForm()

  const inbox = useQuery({
    queryKey: ['ai-suggestions', statusFilter],
    queryFn: () => api.aiSuggestions(statusFilter, 0, 200),
  })
  const metrics = useQuery({ queryKey: ['ai-suggestion-metrics'], queryFn: () => api.aiSuggestionMetrics() })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['ai-suggestions'] })
    void queryClient.invalidateQueries({ queryKey: ['ai-suggestion-metrics'] })
  }

  const generate = useMutation({
    mutationFn: () => api.aiGenerateSuggestions(undefined, 50),
    onSuccess: (result) => {
      invalidate()
      Modal.info({
        title: `生成了 ${result.generated} 条新建议`,
        width: 620,
        content: (
          <Space direction="vertical" size={6}>
            <Text>{String(result.note ?? '')}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              生成器：{(result.generators as string[])?.join('、')}
            </Text>
          </Space>
        ),
      })
    },
  })

  const accept = useMutation({
    mutationFn: (id: number) => api.aiAcceptSuggestion(id),
    onSuccess: (result) => {
      invalidate()
      message.success(`已采纳：${result.aspectType} 以 AI_GENERATED 来源写入（v${result.appliedAspectVersion}）`)
    },
  })

  const reject = useMutation({
    mutationFn: (values: { id: number; reason: string }) => api.aiRejectSuggestion(values.id, values.reason),
    onSuccess: () => {
      invalidate()
      setRejecting(null)
      rejectForm.resetFields()
      message.success('已驳回（理由会作为改进生成器的输入）')
    },
    onError: (error) => message.error(String(error)),
  })

  const tryLlm = useMutation({
    mutationFn: (urn: string) => api.aiGenerateWithLlm(urn),
    onSuccess: () => message.success('大模型建议已生成'),
    onError: (error) =>
      Modal.error({
        title: '大模型能力不可用（这是如实报告，不是故障掩盖）',
        width: 560,
        content: <Text>{String(error)}</Text>,
      }),
  })

  const rates = (metrics.data?.acceptanceRate ?? []) as {
    generator?: string
    accepted?: number
    reviewed?: number
    rate?: number | null
  }[]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="建议收件箱：AI 的产出全部在这里等人判断"
        description={
          <>
            采纳会以 <Text code>AI_GENERATED</Text> 来源写入 aspect —— 该来源优先级低于人工与导入，
            因此后续采集不会覆盖它，但人工再编辑仍可覆盖（ADR-005）。
            <Text strong>驳回必须给理由</Text>：驳回理由是改进生成器的输入，不是走过场。
          </>
        }
      />

      <Row gutter={16}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="待审建议" value={Number(metrics.data?.pending ?? 0)} />
          </Card>
        </Col>
        <Col span={16}>
          <Card size="small" title="采纳率（判断 AI 有没有用的唯一口径）">
            <Space size={12} wrap>
              {rates.length === 0 ? (
                <Text type="secondary">还没有已审记录</Text>
              ) : (
                rates.map((row) => (
                  <Tooltip key={row.generator} title={`已审 ${row.reviewed} 条，采纳 ${row.accepted} 条`}>
                    <Tag color="blue">
                      {row.generator}：{row.rate === null || row.rate === undefined ? '—' : row.rate}
                    </Tag>
                  </Tooltip>
                ))
              )}
              <Text type="secondary" style={{ fontSize: 12 }}>
                {String(metrics.data?.target ?? '')}
              </Text>
            </Space>
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="建议列表"
        extra={
          <Space>
            <Select
              size="small"
              style={{ width: 150 }}
              value={statusFilter}
              onChange={setStatusFilter}
              options={[
                { value: 'PENDING', label: '待审' },
                { value: 'ACCEPTED', label: '已采纳' },
                { value: 'REJECTED', label: '已驳回' },
              ]}
            />
            <Button size="small" type="primary" loading={generate.isPending} onClick={() => generate.mutate()}>
              运行确定性生成器
            </Button>
          </Space>
        }
      >
        <Table<SuggestionRow>
          size="small"
          rowKey="id"
          loading={inbox.isLoading}
          dataSource={inbox.data?.suggestions ?? []}
          pagination={{ pageSize: 10 }}
          columns={[
            {
              title: '资产',
              dataIndex: 'entity_urn',
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{value}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    写入 {row.aspect_type ?? '—'}
                  </Text>
                </Space>
              ),
            },
            {
              title: '建议内容',
              dataIndex: 'proposal',
              render: (value: Record<string, unknown>) => (
                <Text style={{ fontSize: 12 }}>{JSON.stringify(value)}</Text>
              ),
            },
            {
              title: '依据（rationale）',
              dataIndex: 'rationale',
              render: (value: string) => <Text style={{ fontSize: 12 }}>{value}</Text>,
            },
            {
              title: '来源',
              width: 170,
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Tag color={row.generator === 'llm' ? 'purple' : row.generator === 'human' ? 'cyan' : 'blue'}>
                    {row.generator}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {row.generator_ref ?? '—'}
                  </Text>
                </Space>
              ),
            },
            {
              title: '置信度',
              dataIndex: 'confidence',
              width: 90,
              render: (value: number) => <Tag>{value}</Tag>,
            },
            {
              title: '操作',
              width: 170,
              render: (_, row) =>
                row.status === 'PENDING' ? (
                  <Space>
                    <Button size="small" type="primary" loading={accept.isPending} onClick={() => accept.mutate(row.id)}>
                      采纳
                    </Button>
                    <Button size="small" danger onClick={() => setRejecting(row)}>
                      驳回
                    </Button>
                  </Space>
                ) : (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {row.status}
                  </Text>
                ),
            },
          ]}
        />
      </Card>

      <Card size="small" title="大模型生成（未配置时会明确失败，不会退回模板）">
        <Space>
          <Input
            style={{ width: 420 }}
            placeholder="资产 URN"
            onPressEnter={(event) => tryLlm.mutate((event.target as HTMLInputElement).value)}
            id="dg-llm-urn"
          />
          <Button
            loading={tryLlm.isPending}
            onClick={() => {
              const input = document.getElementById('dg-llm-urn') as HTMLInputElement | null
              if (input?.value) tryLlm.mutate(input.value)
            }}
          >
            用大模型生成描述草稿
          </Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            平台不会用模板冒充 AI 输出 —— 没配置就报 502 并说明怎么配
          </Text>
        </Space>
      </Card>

      <Modal
        open={rejecting !== null}
        title={`驳回建议 #${rejecting?.id ?? ''}`}
        onCancel={() => setRejecting(null)}
        onOk={() => rejectForm.submit()}
        confirmLoading={reject.isPending}
      >
        <Form
          form={rejectForm}
          layout="vertical"
          onFinish={(values) => reject.mutate({ id: rejecting?.id as number, reason: String(values.reason) })}
        >
          <Form.Item
            name="reason"
            label="驳回理由（必填）"
            rules={[{ required: true, message: '驳回必须说明理由' }]}
          >
            <Input.TextArea rows={3} placeholder="如：该资产由上游统一命名，不需要单独描述" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

// ---------------------------------------------------------------- 混合检索

function HybridSearchTab() {
  const [query, setQuery] = useState('')
  const [submitted, setSubmitted] = useState('')
  const result = useQuery({
    queryKey: ['ai-search', submitted],
    queryFn: () => api.aiSearch(submitted, undefined, 20),
    enabled: submitted.length > 0,
  })
  const signal = useQuery({ queryKey: ['ai-search-signal'], queryFn: () => api.aiSearchSignal() })

  const data: HybridSearchResult | undefined = result.data

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="两路召回 + RRF 融合；向量路未实现（如实标注）"
        description={
          <>
            词法召回（PG tsvector + 标识符切分 + 中文二元切分）与术语表同义扩展两路结果用 RRF（k=60）融合 ——
            RRF 不需要把不同量纲的分数归一化，这是混合检索能用起来的关键。
            <Text strong>向量召回未实现</Text>（没有 embedding 服务），因此平台不宣称"语义相似度"。
          </>
        }
      />

      <Card size="small">
        <Space>
          <Input.Search
            style={{ width: 460 }}
            placeholder="如：GMV / 成交额 / order_detail（术语同义词会自动扩展）"
            enterButton="检索"
            loading={result.isFetching}
            onSearch={(value) => setSubmitted(value.trim())}
            onChange={(event) => setQuery(event.target.value)}
            value={query}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            索引文档 {String(signal.data?.indexedDocs ?? '—')} · 术语 {String(signal.data?.glossaryTerms ?? '—')} ·
            待审建议 {String(signal.data?.pendingSuggestions ?? '—')}
          </Text>
        </Space>
      </Card>

      {data && (
        <>
          <Card size="small" title={`召回路径（查询「${data.query}」）`}>
            <Space wrap>
              {data.retrievers.map((item) => (
                <Tooltip key={item.name} title={item.note}>
                  <Tag color={item.available ? 'blue' : 'default'}>
                    {item.name} {item.available ? `权重 ${item.weight}` : '不可用'}
                  </Tag>
                </Tooltip>
              ))}
              <Text type="secondary" style={{ fontSize: 12 }}>
                融合 {data.fusion.method}（k={data.fusion.k}）：{data.fusion.why}
              </Text>
            </Space>
          </Card>

          <Card size="small" title={`结果 ${data.count} 条`}>
            <List
              size="small"
              dataSource={data.results}
              locale={{ emptyText: <Empty description="没有命中：可以换关键词，或用术语同义词再试" /> }}
              renderItem={(item) => (
                <List.Item>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space size={6} wrap>
                      <Tag>{item.entityType}</Tag>
                      <Text strong>{item.displayName ?? item.urn.split('.').pop()}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {item.namespace} · RRF {item.rrfScore}
                      </Text>
                      {(item.retrievers ?? []).map((retriever) => (
                        <Tag key={retriever} color={retriever === 'glossary_expansion' ? 'purple' : 'blue'}>
                          {retriever}
                        </Tag>
                      ))}
                      {item.expandedFrom && <Tag color="purple">由术语「{item.expandedFrom}」扩展</Tag>}
                    </Space>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {item.urn}
                    </Text>
                  </Space>
                </List.Item>
              )}
            />
          </Card>
        </>
      )}
    </Space>
  )
}

// -------------------------------------------------------------- 语义层指标

function MetricTab() {
  const queryClient = useQueryClient()
  const [yaml, setYaml] = useState('')
  const [form] = Form.useForm()

  const metrics = useQuery({ queryKey: ['semantic-metrics'], queryFn: () => api.semanticLayerMetrics() })

  const ingest = useMutation({
    mutationFn: (values: { yaml: string; namespace?: string; sourceFormat?: string }) =>
      api.semanticLayerIngest(values),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['semantic-metrics'] })
      Modal.info({
        title: `接入了 ${result.ingested} 个指标`,
        width: 640,
        content: (
          <Space direction="vertical" size={6}>
            <Text>来源格式：{String(result.sourceFormat)}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {String(result.howToTrace ?? '')}
            </Text>
            {Array.isArray(result.unresolvedColumns) && (result.unresolvedColumns as string[]).length > 0 && (
              <Text type="warning" style={{ fontSize: 12 }}>
                未解析到平台 URN 的列：{(result.unresolvedColumns as string[]).join('、')} ——
                宁可缺边也不猜
              </Text>
            )}
          </Space>
        ),
      })
      setYaml('')
    },
    onError: (error) => message.error(String(error)),
  })

  const sample = `# dbt 语义层（也支持 Cube 的 cubes 与平台自有 metrics 格式）
version: 2
models:
  - name: orders
    columns:
      - name: order_id
      - name: amount
    metrics:
      - name: order_total
        description: 订单总额
        type: SIMPLE
        expr: sum(amount)
`

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="指标是「口径」的载体：必须能追溯到物理列"
        description="每条指标落成 Metric 实体，并建立 指标 ← 依赖列 的 consumedBy 血缘（from=上游）。列解析不到时明确报告，不猜。"
      />

      <Row gutter={16}>
        <Col span={14}>
          <Card size="small" title="指标清单">
            <Table<SemanticMetricRow>
              size="small"
              rowKey="name"
              loading={metrics.isLoading}
              dataSource={metrics.data?.metrics ?? []}
              pagination={false}
              columns={[
                {
                  title: '指标',
                  dataIndex: 'name',
                  render: (value: string, row) => (
                    <Space direction="vertical" size={0}>
                      <Text strong>{row.display_name ?? value}</Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {row.description ?? '无描述'}
                      </Text>
                    </Space>
                  ),
                },
                { title: '类型', dataIndex: 'metric_type', width: 100 },
                {
                  title: '口径',
                  dataIndex: 'expression',
                  render: (value: string | null) => <Text code>{value ?? '—'}</Text>,
                },
                {
                  title: '依赖列',
                  dataIndex: 'physical_columns',
                  width: 90,
                  render: (value: string[]) => <Tag>{Array.isArray(value) ? value.length : 0}</Tag>,
                },
                { title: '来源', dataIndex: 'source_format', width: 90 },
              ]}
            />
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="接入语义层定义">
            <Form
              form={form}
              layout="vertical"
              initialValues={{ namespace: 'prod', sourceFormat: 'dbt' }}
              onFinish={(values) => ingest.mutate({ yaml: String(values.yaml), namespace: String(values.namespace), sourceFormat: String(values.sourceFormat) })}
            >
              <Form.Item name="namespace" label="命名空间">
                <Input />
              </Form.Item>
              <Form.Item name="sourceFormat" label="来源格式">
                <Select
                  options={[
                    { value: 'dbt', label: 'dbt semantic models' },
                    { value: 'cube', label: 'Cube' },
                    { value: 'dg', label: '平台自有格式' },
                  ]}
                />
              </Form.Item>
              <Form.Item name="yaml" label="YAML 定义" rules={[{ required: true, message: '请粘贴语义层 YAML' }]}>
                <Input.TextArea rows={12} value={yaml} onChange={(event) => setYaml(event.target.value)} />
              </Form.Item>
              <Space>
                <Button type="primary" htmlType="submit" loading={ingest.isPending}>
                  接入
                </Button>
                <Button onClick={() => form.setFieldValue('yaml', sample)}>填入示例</Button>
              </Space>
            </Form>
          </Card>
        </Col>
      </Row>
    </Space>
  )
}

// ------------------------------------------------------------------- MCP 工具

function McpTab() {
  const [callResult, setCallResult] = useState<Record<string, unknown> | null>(null)
  const [toolForm] = Form.useForm()
  const tools = useQuery({ queryKey: ['mcp-tools'], queryFn: () => api.aiMcpTools() })

  const call = useMutation({
    mutationFn: (values: { name: string; args: string }) => {
      let args: Record<string, unknown> = {}
      if (values.args?.trim()) {
        try {
          args = JSON.parse(values.args) as Record<string, unknown>
        } catch {
          throw new Error('arguments 必须是合法 JSON')
        }
      }
      return api.aiMcp({
        jsonrpc: '2.0',
        id: Date.now(),
        method: 'tools/call',
        params: { name: values.name, arguments: args },
      })
    },
    onSuccess: (result) => setCallResult(result),
    onError: (error) => message.error(String(error)),
  })

  const data: McpTools | undefined = tools.data

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="Agent 不走后门：工具清单与每次调用都按调用者角色判定权限"
        description={
          <>
            <Text strong>Agent 唯一的写路径是「提建议」</Text>（propose_aspect）——
            不存在直写元数据的工具；拒绝与失败都写入 access_event(source=mcp)，与人工调用同样可审计。
          </>
        }
      />

      <Row gutter={16}>
        <Col span={14}>
          <Card
            size="small"
            title="我当前可用的工具"
            extra={
              <Tooltip title="没有权限的工具不会出现在清单里（避免把权限模型泄露给无权者）">
                <Tag>按权限隐藏 {data?.hiddenByPermission ?? 0} 个</Tag>
              </Tooltip>
            }
          >
            <List
              size="small"
              loading={tools.isLoading}
              dataSource={data?.tools ?? []}
              renderItem={(tool) => (
                <List.Item>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space size={6}>
                      <Text strong>{tool.name}</Text>
                      <Tag color="blue">{tool.requiredPermission}</Tag>
                    </Space>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {tool.description}
                    </Text>
                  </Space>
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="试调一个工具">
            <Form form={toolForm} layout="vertical" initialValues={{ name: 'search_assets', args: '{"query": "orders"}' }} onFinish={(values) => call.mutate(values)}>
              <Form.Item name="name" label="工具名">
                <Select
                  showSearch
                  options={(data?.tools ?? []).map((tool) => ({ value: tool.name, label: tool.name }))}
                />
              </Form.Item>
              <Form.Item name="args" label="arguments（JSON）">
                <Input.TextArea rows={4} />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={call.isPending}>
                调用
              </Button>
            </Form>

            {callResult && (
              <Descriptions column={1} size="small" style={{ marginTop: 12 }}>
                <Descriptions.Item label="JSON-RPC 响应">
                  <Input.TextArea
                    rows={10}
                    readOnly
                    value={JSON.stringify(callResult, null, 2)}
                    style={{ fontFamily: 'monospace', fontSize: 11 }}
                  />
                </Descriptions.Item>
              </Descriptions>
            )}

            <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
              {data?.permissionModel}
              <br />
              <Text strong>未实现：</Text>
              {(data?.notImplemented ?? []).join('、')}
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </Space>
  )
}
