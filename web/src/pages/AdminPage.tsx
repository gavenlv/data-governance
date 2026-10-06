import { useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  Button,
  message,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type CollectRun, type DataSourceRow } from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'
import { useTabParam } from '../hooks/useTabParam'

const { Title, Text, Paragraph } = Typography

/** 管理：采集（已实现）、健康度（已实现）、模型（已实现）、能力清单（已实现）、调度与告警（未实现）。 */
export default function AdminPage() {
  const capabilities = useCapabilities()
  const [tab, setTab] = useTabParam('collect')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            管理
          </Title>
        </div>
        <Text type="secondary">
          连接与采集、采集运行、调度、告警、权限、审计、模型扩展（docs/14 §2 的「管理」入口）。
        </Text>
      </div>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'collect', label: '采集', children: <CollectTab /> },
          { key: 'health', label: '采集健康度', children: <HealthTab /> },
          { key: 'edge', label: 'Edge Agent（推模式）', children: <EdgeTab /> },
          { key: 'schedule', label: '采集调度', children: <ScheduleTab /> },
          { key: 'index', label: '检索索引', children: <IndexTab /> },
          { key: 'model', label: '元数据模型', children: <ModelTab /> },
          { key: 'capabilities', label: '能力清单', children: <CapabilitiesTab /> },
          {
            key: 'runtime',
            label: '控制面',
            children: <RuntimeTab capabilitiesReady={Boolean(capabilities.data)} />,
          },
        ]}
      />
    </Space>
  )
}

/**
 * Edge Agent（ADR-012 的推模式）。
 *
 * <p>为什么需要它：企业环境的硬约束是数据不出域（私有子网、专有云），主动拉取在这些环境里连不上，
 * 只能由数据侧的 Agent 把元数据推上来。
 *
 * <p>页面上如实标注：<b>控制面侧的协议与接入点已实现，Go 单二进制 Agent 本体未实现</b>。
 */
function EdgeTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const [credentials, setCredentials] = useState<Record<string, unknown> | null>(null)
  const [revoking, setRevoking] = useState<string | null>(null)

  const agents = useQuery({ queryKey: ['edge-agents'], queryFn: () => api.edgeAgents() })
  const reports = useQuery({ queryKey: ['edge-reports'], queryFn: () => api.edgeReports() })

  const register = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.edgeRegisterAgent({
        agentId: String(values.agentId),
        displayName: values.displayName ? String(values.displayName) : undefined,
        namespace: values.namespace ? String(values.namespace) : 'prod',
        capabilities: values.capabilities
          ? String(values.capabilities)
              .split(',')
              .map((item) => item.trim())
              .filter(Boolean)
          : [],
        version: values.version ? String(values.version) : undefined,
      }),
    onSuccess: (result) => {
      setCredentials(result)
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['edge-agents'] })
    },
  })

  const revoke = useMutation({
    mutationFn: (values: { agentId: string; reason: string }) => api.edgeRevokeAgent(values.agentId, values.reason),
    onSuccess: () => {
      setRevoking(null)
      void queryClient.invalidateQueries({ queryKey: ['edge-agents'] })
      void queryClient.invalidateQueries({ queryKey: ['edge-reports'] })
      message.success('凭据已吊销（历史上报记录保留，可追溯）')
    },
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="推模式的协议与控制面已实现；Agent 本体（Go 单二进制）未实现"
        description={
          <>
            任何能发 HTTP 的采集器（脚本 / cron / k8s Job）现在就能用：
            <Text code>POST /api/v1/edge/agent/heartbeat</Text>、
            <Text code>POST /api/v1/edge/agent/report</Text>，凭据放{' '}
            <Text code>Authorization: Bearer dgagent_…</Text>。
            上报的元数据走同一套 URN 形状与来源保护（AUTO_COLLECTED），会进入检索索引与血缘图 —— 推模式不是侧路。
          </>
        }
      />

      <Row gutter={16}>
        <Col span={14}>
          <Card
            size="small"
            title="已注册的 Agent"
            extra={<CapabilityBadge status={capabilities.get('ingestion.edge-agent')?.status ?? 'NOT_IMPLEMENTED'} />}
          >
            <Table
              size="small"
              rowKey="agent_id"
              loading={agents.isLoading}
              dataSource={agents.data?.agents ?? []}
              pagination={false}
              columns={[
                {
                  title: 'Agent',
                  dataIndex: 'agent_id',
                  render: (value: string, row) => (
                    <Space direction="vertical" size={0}>
                      <Text strong>{row.display_name ?? value}</Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {value} · {row.namespace} · v{row.version ?? '—'}
                      </Text>
                    </Space>
                  ),
                },
                {
                  title: '能力',
                  dataIndex: 'capabilities',
                  width: 140,
                  render: (value: string[]) => (
                    <Space size={4} wrap>
                      {(value ?? []).map((item) => (
                        <Tag key={item}>{item}</Tag>
                      ))}
                    </Space>
                  ),
                },
                {
                  title: '心跳',
                  dataIndex: 'seconds_since_heartbeat',
                  width: 120,
                  render: (value: number | null, row) => {
                    if (row.status === 'REVOKED') return <Tag>已吊销</Tag>
                    if (value === null || value === undefined) return <Tag color="warning">从未心跳</Tag>
                    const minutes = Math.round(value / 60)
                    return (
                      <Tag color={minutes > 30 ? 'error' : 'success'}>
                        {minutes < 1 ? '刚刚' : `${minutes} 分钟前`}
                      </Tag>
                    )
                  },
                },
                {
                  title: '操作',
                  width: 100,
                  render: (_, row) =>
                    row.status === 'REVOKED' ? (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        —
                      </Text>
                    ) : (
                      <Button size="small" danger onClick={() => setRevoking(row.agent_id)}>
                        吊销
                      </Button>
                    ),
                },
              ]}
            />
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="注册 Agent（凭据只返回一次）">
            <Form form={form} layout="vertical" initialValues={{ namespace: 'prod', capabilities: 'postgres' }} onFinish={(values) => register.mutate(values)}>
              <Form.Item name="agentId" label="Agent ID" rules={[{ required: true }]}>
                <Input placeholder="如 edge-shanghai-01" />
              </Form.Item>
              <Form.Item name="displayName" label="显示名">
                <Input placeholder="上海机房采集器" />
              </Form.Item>
              <Form.Item name="namespace" label="命名空间">
                <Input />
              </Form.Item>
              <Form.Item name="capabilities" label="支持的连接器（逗号分隔）">
                <Input placeholder="postgres,clickhouse" />
              </Form.Item>
              <Form.Item name="version" label="Agent 版本">
                <Input placeholder="0.1.0" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={register.isPending}>
                注册并下发凭据
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="上报记录（区分「没推」与「推了但被拒」）">
        <Table
          size="small"
          rowKey="id"
          loading={reports.isLoading}
          dataSource={reports.data?.reports ?? []}
          pagination={{ pageSize: 8 }}
          columns={[
            { title: 'Agent', dataIndex: 'agent_id', width: 180 },
            { title: '类型', dataIndex: 'report_type', width: 110 },
            { title: '实体数', dataIndex: 'entity_count', width: 90 },
            {
              title: '结果',
              dataIndex: 'accepted',
              width: 100,
              render: (value: boolean) => <Tag color={value ? 'success' : 'error'}>{value ? '接受' : '拒绝'}</Tag>,
            },
            {
              title: '拒绝原因',
              dataIndex: 'reject_reason',
              render: (value: string | null) => <Text style={{ fontSize: 12 }}>{value ?? '—'}</Text>,
            },
            {
              title: '时间',
              dataIndex: 'received_at',
              width: 170,
              render: (value: string) => <Text style={{ fontSize: 12 }}>{value?.replace('T', ' ').slice(0, 19)}</Text>,
            },
          ]}
        />
      </Card>

      <Modal
        open={credentials !== null}
        title="凭据只在这里显示一次"
        width={620}
        onCancel={() => setCredentials(null)}
        onOk={() => setCredentials(null)}
        okText="我已保存"
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="库里只存哈希"
            description="请让 Agent 从环境变量读取，不要写进配置文件或仓库。"
          />
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Agent ID">{String(credentials?.agentId ?? '')}</Descriptions.Item>
            <Descriptions.Item label="凭据">
              <Text code copyable>
                {String(credentials?.token ?? '')}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label="命名空间">{String(credentials?.namespace ?? '')}</Descriptions.Item>
          </Descriptions>
          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            {String(credentials?.agentBinary ?? '')}
          </Paragraph>
        </Space>
      </Modal>

      <Modal
        open={revoking !== null}
        title={`吊销 Agent「${revoking ?? ''}」的凭据`}
        onCancel={() => setRevoking(null)}
        onOk={() => revoke.mutate({ agentId: revoking as string, reason: '管理员在界面吊销' })}
        confirmLoading={revoke.isPending}
        okText="确认吊销"
        okButtonProps={{ danger: true }}
      >
        <Text>
          吊销后该 Agent 的下一次心跳/上报会因凭据无效被拒（401）。
          <Text strong>历史上报记录会保留</Text>，以便回答「这条元数据是谁在什么时候推上来的」。
        </Text>
      </Modal>
    </Space>
  )
}

function CollectTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const sources = useQuery({ queryKey: ['collect-sources'], queryFn: () => api.collectSources() })

  const runs = useQuery({ queryKey: ['collect-runs'], queryFn: () => api.collectRuns(20) })

  const mutation = useMutation({
    mutationFn: (values: Record<string, unknown>) => api.collectRun(values),
    onSuccess: (run) => {
      const status = run.status ?? 'SUCCEEDED'
      const extra =
        run.dashboardsSeen && run.dashboardsSeen > 0
          ? `（数据集 ${run.datasetsSeen ?? 0} / BI 资产 ${run.dashboardsSeen}${run.dashboardsDeleted ? `，清理 ${run.dashboardsDeleted}` : ''}）`
          : ''
      if (status === 'BLOCKED') {
        message.warning('采集被护栏拦截：目录保持不变，请人工确认源端变化')
      } else if (status === 'FAILED') {
        message.error(`采集失败：${(run.errors ?? [])[0] ?? '未知原因'}`)
      } else {
        message.success(`采集完成：${status}${extra}`)
      }
      void queryClient.invalidateQueries({ queryKey: ['collect-runs'] })
      void queryClient.invalidateQueries({ queryKey: ['collect-health'] })
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const implemented = sources.data?.implemented ?? []
  const missing = sources.data?.notImplemented ?? {}

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <DataSourceCard />

      <Card
        size="small"
        title="触发一次采集"
        extra={<CapabilityBadge status={capabilities.get('ingestion.framework')?.status ?? 'IMPLEMENTED'} />}
      >
        <Form
          form={form}
          layout="inline"
          initialValues={{
            source: 'postgres',
            dsn: 'postgresql://postgres:root@localhost:25011/dg',
            namespace: 'prod',
            databases: 'public',
            samplePercent: 100,
          }}
          onFinish={(values) =>
            mutation.mutate({
              source: values.source,
              dsn: values.dsn,
              namespace: values.namespace,
              databases: values.databases
                ? String(values.databases)
                    .split(',')
                    .map((s: string) => s.trim())
                    .filter(Boolean)
                : undefined,
              sampleSize: Number(values.sampleSize) || undefined,
              reconcileOrphans: Boolean(values.reconcileOrphans),
            })
          }
        >
          <Form.Item name="source" label="数据源" rules={[{ required: true }]}>
            <Select
              style={{ width: 170 }}
              options={implemented.map((item) => ({
                value: item.id,
                label: `${item.displayName}${item.verifiedAgainstRealSystem ? '' : '（未对真实系统验证）'}`,
              }))}
              // 选中源就把该连接器的 DSN 示例填进 DSN 输入框：6 个连接器的 DSN 形态各不相同
              // （jdbc:// / clickhouse:// / mongodb:// / bigquery:// / superset:// / dbt:///path），
              // 让人去下面表格里抄是没必要的摩擦
              onChange={(value: string) => {
                const picked = implemented.find((item) => item.id === value)
                if (picked?.dsnExample) {
                  form.setFieldValue('dsn', picked.dsnExample)
                }
              }}
            />
          </Form.Item>
          <Form.Item name="dsn" label="DSN" rules={[{ required: true }]}>
            <Input style={{ width: 380 }} placeholder="见下方各连接器的 DSN 示例" />
          </Form.Item>
          <Form.Item name="namespace" label="命名空间">
            <Input style={{ width: 110 }} />
          </Form.Item>
          <Form.Item name="databases" label="库/Schema 过滤">
            <Input style={{ width: 170 }} placeholder="逗号分隔" />
          </Form.Item>
          <Form.Item name="sampleSize" label="采样条数">
            <Input type="number" style={{ width: 100 }} placeholder="MongoDB 用" />
          </Form.Item>
          <Form.Item name="reconcileOrphans" label="清理孤儿">
            <Select
              style={{ width: 90 }}
              options={[
                { value: false, label: '否' },
                { value: true, label: '是' },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={mutation.isPending}>
              开始采集
            </Button>
          </Form.Item>
        </Form>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          护栏默认：单次删除上限 200、删除比例 ≤ 30%、保留率 ≥ 70%；被拦截时不删除任何实体且不更新基线。
          BI 资产（仪表板）有**独立**的快照与护栏，删几个报表不会拦停数据采集。
          「清理孤儿」用于连接器升级后清理 URN 规则变化留下的旧实体，默认关闭。
        </Paragraph>
      </Card>

      <Card size="small" title="最近采集运行">
        {runs.isError && <Alert type="error" showIcon message={(runs.error as Error).message} />}
        <Table<CollectRun>
          size="small"
          rowKey={(row) => String(row.run_id ?? row.runId)}
          loading={runs.isLoading}
          dataSource={runs.data?.runs ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有采集记录" /> }}
          columns={[
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (value: string) => (
                <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'BLOCKED' ? 'orange' : 'red'}>
                  {value}
                </Tag>
              ),
            },
            { title: '源', dataIndex: 'source', width: 100 },
            { title: '命名空间', dataIndex: 'namespace', width: 100 },
            {
              title: '数据集',
              dataIndex: 'datasets_seen',
              width: 90,
              render: (value: number, row) =>
                `${value ?? 0}${row.datasets_created ? ` (+${row.datasets_created})` : ''}`,
            },
            {
              title: 'BI 资产',
              width: 110,
              render: (_, row) =>
                row.dashboards_seen
                  ? `${row.dashboards_seen}${row.dashboards_deleted ? ` (-${row.dashboards_deleted})` : ''}`
                  : '—',
            },
            {
              title: '列',
              dataIndex: 'columns_seen',
              width: 80,
              render: (value: number) => value ?? '—',
            },
            {
              title: '耗时(ms)',
              dataIndex: 'duration_ms',
              width: 100,
              render: (value: number) => value ?? '—',
            },
            {
              title: '拦截/错误',
              render: (_, row) =>
                row.block_reason ? (
                  <Text type="warning">{row.block_reason}</Text>
                ) : (row.errors ?? []).length > 0 ? (
                  <Text type="danger">{(row.errors ?? [])[0]}</Text>
                ) : (
                  '—'
                ),
            },
          ]}
        />
      </Card>

      <Card
        size="small"
        title="连接器清单（状态来自控制面单点声明，界面不自己维护）"
        extra={<CapabilityBadge status={capabilities.get('ingestion.connectors-more')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Table
          size="small"
          rowKey="id"
          loading={sources.isLoading}
          dataSource={implemented}
          pagination={false}
          columns={[
            { title: '数据源', dataIndex: 'displayName', width: 150 },
            { title: 'ID', dataIndex: 'id', width: 110 },
            {
              title: '资产类别',
              dataIndex: 'assetKind',
              width: 100,
              render: (value: string) => <Tag>{value === 'dashboard' ? 'BI 资产' : '数据集'}</Tag>,
            },
            {
              title: '真实系统验证',
              dataIndex: 'verifiedAgainstRealSystem',
              width: 130,
              render: (value: boolean) =>
                value ? <Tag color="green">已验证</Tag> : <Tag color="orange">未验证</Tag>,
            },
            {
              title: 'DSN 示例',
              dataIndex: 'dsnExample',
              width: 300,
              render: (value: string) => <span className="dg-mono">{value}</span>,
            },
            { title: '说明', dataIndex: 'note' },
          ]}
        />
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {String(sources.data?.note ?? '')}
        </Paragraph>
      </Card>

      <Card size="small" title="未实现的连接器（按 docs/11 §1.1 白名单，按需共建）">
        <Table
          size="small"
          rowKey="id"
          pagination={false}
          dataSource={Object.entries(missing).map(([id, info]) => ({ id, note: info.note }))}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 120 },
            { title: '说明', dataIndex: 'note' },
          ]}
        />
      </Card>
    </Space>
  )
}

/**
 * 数据源管理（连接录一次、反复复用）。
 *
 * <p>为什么要有它：以前每次采集/测试都要把完整 DSN 与口令重敲一遍，既容易出错，
 * 口令也散落在聊天记录与终端历史里。这里把连接收敛成一条**加密存储**的记录 ——
 * 之后的测试连接与扫描只传 id。
 *
 * <p>三条硬约束在界面上如实呈现，不粉饰：
 * <ul>
 *   <li>凭据用 AES-256-GCM 加密，密钥来自环境变量 <Text code>DG_SECRET_KEY</Text>，<b>不在库内</b>；</li>
 *   <li>未配置密钥时<b>保存连接会被拒绝（502）</b>，而不是"先存明文以后再说"；</li>
 *   <li>接口<b>永不回显凭据</b>，端点已去掉 user:password@ 与 password= 之类参数。</li>
 * </ul>
 */
function DataSourceCard() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const [editing, setEditing] = useState<DataSourceRow | null>(null)
  const [open, setOpen] = useState(false)

  const sources = useQuery({ queryKey: ['collect-sources'], queryFn: () => api.collectSources() })
  const dataSources = useQuery({ queryKey: ['data-sources'], queryFn: () => api.dataSources() })
  const implemented = sources.data?.implemented ?? []
  const cipher = dataSources.data?.cipher

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['data-sources'] })
  }

  const save = useMutation({
    mutationFn: (values: Record<string, unknown>) => {
      const body = {
        name: String(values.name),
        connector: String(values.connector),
        namespace: values.namespace ? String(values.namespace) : undefined,
        dsn: values.dsn ? String(values.dsn) : undefined,
        jdbcUrl: values.jdbcUrl ? String(values.jdbcUrl) : undefined,
        username: values.username ? String(values.username) : undefined,
        password: values.password ? String(values.password) : undefined,
        databases: splitList(values.databases),
        schemas: splitList(values.schemas),
        tables: splitList(values.tables),
        sampleSize: values.sampleSize ? Number(values.sampleSize) : undefined,
      }
      return editing ? api.dataSourceUpdate(editing.id, body) : api.dataSourceCreate(body)
    },
    onSuccess: (row) => {
      setOpen(false)
      setEditing(null)
      form.resetFields()
      invalidate()
      message.success(editing ? `已更新数据源「${row.name}」` : `已创建数据源「${row.name}」`)
    },
    onError: (error: Error) => message.error(error.message),
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.dataSourceDelete(id),
    onSuccess: () => {
      invalidate()
      message.success('已删除数据源')
    },
    onError: (error: Error) => message.error(error.message),
  })

  const test = useMutation({
    mutationFn: (id: string) => api.dataSourceTest(id),
    onSuccess: (result) => {
      if (result.ok) {
        message.success(`连接可用（${result.platform ?? result.connector}，${result.durationMs ?? 0}ms）`)
      } else {
        message.warning(`测试已执行，但连不通：${result.note ?? '请检查端点与账号'}`)
      }
    },
    onError: (error: Error) => message.error(error.message),
  })

  const scan = useMutation({
    mutationFn: (row: DataSourceRow) => api.dataSourceScan(row.id),
    onSuccess: (result) => {
      const status = result.status ?? 'SUCCEEDED'
      if (status === 'BLOCKED') {
        message.warning('扫描被护栏拦截：目录保持不变，请人工确认源端变化')
      } else if (status === 'FAILED') {
        message.error(`扫描失败：${(result.errors ?? [])[0] ?? '未知原因'}`)
      } else {
        message.success(`扫描完成：${status}（数据集 ${result.datasetsSeen ?? 0}）`)
      }
      invalidate()
      void queryClient.invalidateQueries({ queryKey: ['collect-runs'] })
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ connector: 'postgres', namespace: 'prod' })
    setOpen(true)
  }

  const openEdit = (row: DataSourceRow) => {
    setEditing(row)
    form.resetFields()
    form.setFieldsValue({
      name: row.name,
      connector: row.connector,
      namespace: row.namespace ?? undefined,
      databases: row.databases.join(','),
      schemas: row.schemas.join(','),
      tables: row.tables.join(','),
      sampleSize: row.sampleSize ?? undefined,
    })
    setOpen(true)
  }

  const busy = test.isPending || scan.isPending

  return (
    <Card
      size="small"
      title="数据源（连接录一次，之后扫描免重输）"
      extra={
        <Space>
          <CapabilityBadge status={capabilities.get('ingestion.datasource-registry')?.status ?? 'IMPLEMENTED'} />
          <Button size="small" type="primary" onClick={openCreate}>
            新建数据源
          </Button>
        </Space>
      }
    >
      {cipher && !cipher.configured && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="凭据加密未启用：保存连接会被拒绝（502）"
          description={
            <>
              {cipher.reason ?? '未配置加密密钥'}。生成密钥：
              <Text code>openssl rand -base64 32</Text>，设 <Text code>DG_SECRET_KEY=&lt;该值&gt;</Text>{' '}
              后重启控制面。{cipher.hint ?? ''}
              <br />
              <Text type="secondary">
                刻意不设默认密钥：宁可拒绝保存，也不让凭据在"以为已加密"的情况下明文落库。
              </Text>
            </>
          }
        />
      )}

      {dataSources.isError && (
        <Alert type="error" showIcon message={(dataSources.error as Error).message} style={{ marginBottom: 12 }} />
      )}

      <Table<DataSourceRow>
        size="small"
        rowKey="id"
        loading={dataSources.isLoading}
        dataSource={dataSources.data?.dataSources ?? []}
        pagination={false}
        locale={{ emptyText: <Empty description="还没有数据源。新建一个，之后扫描就不必再输连接串。" /> }}
        columns={[
          {
            title: '名称',
            dataIndex: 'name',
            render: (value: string, row) => (
              <Space direction="vertical" size={0}>
                <Text strong>{value}</Text>
                <span className="dg-mono" style={{ fontSize: 12 }}>
                  {row.id}
                </span>
              </Space>
            ),
          },
          {
            title: '连接器',
            dataIndex: 'connector',
            width: 120,
            render: (value: string) => <Tag color="blue">{value}</Tag>,
          },
          { title: '命名空间', dataIndex: 'namespace', width: 120 },
          {
            title: '端点（已脱敏）',
            dataIndex: 'endpoint',
            render: (value: string | null) =>
              value ? <span className="dg-mono">{value}</span> : <Text type="secondary">—</Text>,
          },
          {
            title: '凭据',
            dataIndex: 'hasCredentials',
            width: 90,
            render: (value: boolean) =>
              value ? <Tag color="green">已加密</Tag> : <Tag>无</Tag>,
          },
          {
            title: '最近扫描',
            width: 200,
            render: (_, row) =>
              row.lastScanAt ? (
                <Space direction="vertical" size={0}>
                  <Tag
                    color={
                      row.lastScanStatus === 'SUCCEEDED'
                        ? 'green'
                        : row.lastScanStatus === 'BLOCKED'
                          ? 'orange'
                          : 'red'
                    }
                  >
                    {row.lastScanStatus ?? '—'}
                  </Tag>
                  <span className="dg-mono" style={{ fontSize: 12 }}>
                    {row.lastScanAt.slice(0, 19)}
                  </span>
                </Space>
              ) : (
                <Text type="secondary">未扫描</Text>
              ),
          },
          {
            title: '操作',
            width: 250,
            render: (_, row) => (
              <Space size={4}>
                <Button
                  size="small"
                  type="link"
                  disabled={busy}
                  loading={test.isPending && test.variables === row.id}
                  onClick={() => test.mutate(row.id)}
                >
                  测试连接
                </Button>
                <Button
                  size="small"
                  type="link"
                  disabled={busy}
                  loading={scan.isPending && scan.variables?.id === row.id}
                  onClick={() => scan.mutate(row)}
                >
                  扫描
                </Button>
                <Button size="small" type="link" onClick={() => openEdit(row)}>
                  编辑
                </Button>
                <Popconfirm
                  title={`删除数据源「${row.name}」？`}
                  description="已采集的元数据不受影响；仅删除这条连接记录。"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => remove.mutate(row.id)}
                >
                  <Button size="small" type="link" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        open={open}
        title={editing ? `编辑数据源「${editing.name}」` : '新建数据源'}
        okText="保存"
        cancelText="取消"
        confirmLoading={save.isPending}
        width={640}
        onOk={() => form.submit()}
        onCancel={() => {
          setOpen(false)
          setEditing(null)
        }}
      >
        <Form form={form} layout="vertical" onFinish={(values) => save.mutate(values)}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '给这条连接起个可检索的名字' }]}>
            <Input placeholder="如 生产数仓-PostgreSQL" />
          </Form.Item>
          <Form.Item name="connector" label="连接器" rules={[{ required: true }]}>
            <Select
              options={implemented.map((item) => ({
                value: item.id,
                label: `${item.displayName}${item.verifiedAgainstRealSystem ? '' : '（未对真实系统验证）'}`,
              }))}
              onChange={(value: string) => {
                const picked = implemented.find((item) => item.id === value)
                if (picked?.dsnExample && !editing) {
                  form.setFieldValue('dsn', picked.dsnExample)
                }
              }}
            />
          </Form.Item>
          <Form.Item name="namespace" label="命名空间">
            <Input placeholder="如 prod" />
          </Form.Item>
          <Form.Item
            name="dsn"
            label="DSN"
            extra={
              editing
                ? '留空表示保持原值 —— 不必重敲完整连接细节。'
                : '含账号口令，保存后即以 AES-256-GCM 加密存储，接口不会回显。'
            }
          >
            <Input placeholder={editing ? '留空 = 不修改' : '如 postgresql://user:password@host:5432/db'} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="username" label="用户名">
                <Input placeholder={editing ? '留空 = 不修改' : ''} autoComplete="off" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="password" label="口令">
                <Input.Password placeholder={editing ? '留空 = 不修改' : ''} autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="databases" label="库 / Schema 过滤">
                <Input placeholder="逗号分隔" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="schemas" label="Schema 过滤">
                <Input placeholder="逗号分隔" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="tables" label="表过滤">
                <Input placeholder="逗号分隔，留空 = 全部" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sampleSize" label="采样条数">
                <Input type="number" placeholder="MongoDB 用" />
              </Form.Item>
            </Col>
          </Row>
          <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
            端点会去掉 <Text code>user:password@</Text> 与 <Text code>?password=</Text> 之类参数后才入库；
            口令本体只以密文保存在 <Text code>secret_enc</Text> 列，密钥在环境变量里、不在库内。
          </Paragraph>
        </Form>
      </Modal>
    </Card>
  )
}

/** 逗号分隔 → 数组；空串得到 undefined（表示"不过滤"）。 */
function splitList(value: unknown): string[] | undefined {
  if (value === undefined || value === null || String(value).trim() === '') {
    return undefined
  }
  return String(value)
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function HealthTab() {
  const health = useQuery({ queryKey: ['collect-health'], queryFn: () => api.collectHealth() })
  const status = health.data?.health ?? 'UNKNOWN'

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title="总体健康度" extra={<CapabilityBadge status="IMPLEMENTED" />}>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title="状态"
              value={status}
              valueStyle={{
                color: status === 'HEALTHY' ? '#52c41a' : status === 'DEGRADED' ? '#faad14' : '#ff4d4f',
              }}
            />
          </Col>
          <Col span={6}>
            <Statistic title="采集源数量" value={health.data?.sources?.length ?? 0} />
          </Col>
          <Col span={12}>
            <Paragraph type="secondary" style={{ margin: 0, fontSize: 12 }}>
              健康度看板必须**被主动查看**，而告警才会**主动找到人**。Java 控制面的采集告警尚未实现
              （Python 参考实现已有分级/去重/冷却/恢复状态机），见「调度与告警」页签。
            </Paragraph>
          </Col>
        </Row>
      </Card>

      <Card size="small" title="按源明细">
        <Table
          size="small"
          rowKey={(row) => `${row.source}@${row.namespace}:${row.scope}`}
          loading={health.isLoading}
          dataSource={health.data?.sources ?? []}
          pagination={false}
          locale={{ emptyText: <Empty description="还没有采集状态快照" /> }}
          columns={[
            { title: '源', dataIndex: 'source', width: 110 },
            { title: '命名空间', dataIndex: 'namespace', width: 120 },
            { title: '范围', dataIndex: 'scope', width: 80 },
            { title: '实体数', dataIndex: 'entity_count', width: 100 },
            {
              title: '最后状态',
              dataIndex: 'last_status',
              width: 120,
              render: (value: string) => (
                <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'BLOCKED' ? 'orange' : 'red'}>
                  {value ?? '—'}
                </Tag>
              ),
            },
            {
              title: '连续失败',
              dataIndex: 'consecutive_failures',
              width: 110,
              render: (value: number) => (value >= 3 ? <Text type="danger">{value}</Text> : value),
            },
            {
              title: '最后成功',
              dataIndex: 'last_success_at',
              render: (value: string | null) => (
                <span className="dg-mono">{value ? String(value).slice(0, 19) : '—'}</span>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}

function ModelTab() {
  const model = useQuery({ queryKey: ['model'], queryFn: () => api.model() })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title="模型摘要" extra={<CapabilityBadge status="IMPLEMENTED" />}>
        {model.isError && <Alert type="error" showIcon message={(model.error as Error).message} />}
        <Descriptions column={4} size="small" bordered>
          <Descriptions.Item label="实体类型">{String(model.data?.entityTypes ?? '—')}</Descriptions.Item>
          <Descriptions.Item label="Aspect 类型">{String(model.data?.aspectTypes ?? '—')}</Descriptions.Item>
          <Descriptions.Item label="关系类型">{String(model.data?.relationshipTypes ?? '—')}</Descriptions.Item>
          <Descriptions.Item label="血缘关系">
            {String((model.data?.lineageRelationships as string[])?.join(', ') ?? '—')}
          </Descriptions.Item>
        </Descriptions>
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          模型定义位于仓库 <span className="dg-mono">model/**.yaml</span>，是唯一事实源：
          Java 控制面、Python 侧车与前端生成物都从它派生。兼容性检查会在 CI 阻断破坏性变更
          （删除属性 / 改类型 / 收紧枚举）。
        </Paragraph>
      </Card>

      <Card size="small" title="实体与允许的 Aspect">
        <pre className="dg-mono" style={{ maxHeight: 420, overflow: 'auto', margin: 0 }}>
          {JSON.stringify(model.data?.entityAspects ?? {}, null, 2)}
        </pre>
      </Card>
    </Space>
  )
}

function CapabilitiesTab() {
  const capabilities = useCapabilities()

  if (capabilities.isLoading) {
    return <Empty description="加载中…" />
  }
  if (capabilities.isError) {
    return <Alert type="error" showIcon message={(capabilities.error as Error).message} />
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="能力清单是「未实现必须显式标注」的对外出口"
        description="界面与接口共用同一份状态（控制面的 CapabilityProvider 实现），因此不会出现「界面以为有、接口其实没有」的漂移。"
      />
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="已实现" value={capabilities.data?.summary.implemented ?? 0} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="部分实现" value={capabilities.data?.summary.partial ?? 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="未实现" value={capabilities.data?.summary.notImplemented ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="合计" value={capabilities.data?.summary.total ?? 0} />
          </Card>
        </Col>
      </Row>

      {Object.entries(capabilities.data?.domains ?? {}).map(([domain, items]) => (
        <Card key={domain} size="small" title={domain}>
          <Table
            size="small"
            rowKey="id"
            dataSource={items}
            pagination={false}
            columns={[
              {
                title: '状态',
                dataIndex: 'status',
                width: 110,
                render: (value) => <CapabilityBadge status={value} small />,
              },
              { title: '能力', dataIndex: 'name', width: 220 },
              {
                title: '设计出处',
                dataIndex: 'doc',
                width: 130,
                render: (value: string) => <span className="dg-mono">{value}</span>,
              },
              { title: '阶段', dataIndex: 'phase', width: 90 },
              {
                title: '说明 / 缺口',
                dataIndex: 'summary',
                render: (value: string, row) => (
                  <Space direction="vertical" size={2}>
                    <span>{value}</span>
                    {row.notes?.length > 0 && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        缺口：{row.notes.join('；')}
                      </Text>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      ))}
    </Space>
  )
}

function ScheduleTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [yamlPath, setYamlPath] = useState('config/schedules.example.yaml')

  const schedules = useQuery({ queryKey: ['schedules'], queryFn: () => api.schedules(), retry: false })
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: () => api.alerts(), retry: false })

  const applyFile = useMutation({
    mutationFn: () => api.applySchedulesFile(yamlPath),
    onSuccess: (result) => {
      if (result.rejected.length > 0) {
        message.warning(`应用完成：${result.applied.length} 条生效，${result.rejected.length} 条被拒绝`)
      } else {
        message.success(`已应用 ${result.applied.length} 条调度`)
      }
      void queryClient.invalidateQueries({ queryKey: ['schedules'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const runNow = useMutation({
    mutationFn: (name: string) => api.runSchedule(name),
    onSuccess: (result) => {
      if (result.skipped) {
        message.warning(`跳过：${String(result.reason)}`)
      } else if (result.failed) {
        message.error(`执行失败：${String(result.error)}`)
      } else {
        message.success('调度执行完成')
      }
      void queryClient.invalidateQueries({ queryKey: ['schedules'] })
      void queryClient.invalidateQueries({ queryKey: ['collect-runs'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="调度定义（治理即代码：YAML 进 Git，apply 落库）"
        extra={<CapabilityBadge status={capabilities.get('ingestion.scheduler')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Space wrap>
          <Input
            addonBefore="调度文件"
            style={{ width: 380 }}
            value={yamlPath}
            onChange={(event) => setYamlPath(event.target.value)}
          />
          <Button onClick={() => applyFile.mutate()} loading={applyFile.isPending}>
            应用调度文件
          </Button>
        </Space>
        {schedules.data?.scheduler && (
          <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 12 }}>
            互斥：<span className="dg-mono">{schedules.data.scheduler.mutex}</span>；{schedules.data.scheduler.note}
          </Paragraph>
        )}
      </Card>

      {applyFile.data && (
        <Card size="small" title="上次应用结果">
          <Space direction="vertical" style={{ width: '100%' }}>
            <Text>生效 {applyFile.data.applied.length} 条</Text>
            {applyFile.data.rejected.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`${applyFile.data.rejected.length} 条被拒绝（不会落库）`}
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {applyFile.data.rejected.map((item) => (
                      <li key={String(item.name)}>
                        <span className="dg-mono">{item.name}</span>：{item.error}
                      </li>
                    ))}
                  </ul>
                }
              />
            )}
            <Text type="secondary" style={{ fontSize: 12 }}>
              {applyFile.data.note}
            </Text>
          </Space>
        </Card>
      )}

      <Card size="small" title="调度清单">
        {schedules.isError && (
          <Alert type="error" showIcon message={(schedules.error as Error).message} />
        )}
        <Table
          size="small"
          rowKey="name"
          loading={schedules.isLoading}
          dataSource={schedules.data?.schedules ?? []}
          pagination={false}
          locale={{ emptyText: <Empty description="还没有调度定义" /> }}
          columns={[
            { title: '名称', dataIndex: 'name', width: 180 },
            { title: '源', dataIndex: 'source', width: 90 },
            { title: '命名空间', dataIndex: 'namespace', width: 100 },
            {
              title: 'cron',
              dataIndex: 'cron',
              width: 130,
              render: (value: string) => <span className="dg-mono">{value}</span>,
            },
            {
              title: '启用',
              dataIndex: 'enabled',
              width: 80,
              render: (value: boolean) => <Tag color={value ? 'green' : undefined}>{value ? '是' : '否'}</Tag>,
            },
            {
              title: '下次执行',
              dataIndex: 'nextRunAt',
              width: 170,
              render: (value: string | null) => (
                <span className="dg-mono">{value ? String(value).slice(0, 19).replace('T', ' ') : '—'}</span>
              ),
            },
            {
              title: '上次结果',
              dataIndex: 'lastStatus',
              width: 110,
              render: (value: string | null) =>
                value ? (
                  <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'BLOCKED' ? 'orange' : 'red'}>
                    {value}
                  </Tag>
                ) : (
                  <Text type="secondary">从未执行</Text>
                ),
            },
            {
              title: 'DSN',
              dataIndex: 'dsn',
              render: (value: string) => <span className="dg-mono">{value}</span>,
            },
            {
              title: '操作',
              width: 90,
              render: (_, row) => (
                <a onClick={() => runNow.mutate(row.name)}>立即执行</a>
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" title="告警接口返回">
        {alerts.isError ? (
          <Alert
            type="warning"
            showIcon
            message="未实现（501）"
            description={(alerts.error as Error).message}
          />
        ) : (
          <pre className="dg-mono">{JSON.stringify(alerts.data, null, 2)}</pre>
        )}
      </Card>
    </Space>
  )
}

/** 检索索引维护：派生视图的消费者水位、手动推进与重建（ADR-002 的可执行证明）。 */
function IndexTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()

  const lag = useQuery({ queryKey: ['index-lag'], queryFn: () => api.indexLag(), retry: false })

  const consume = useMutation({
    mutationFn: () => api.indexConsume(500),
    onSuccess: (result) => {
      message.success(`消费完成：处理 ${result.stats.processed ?? 0} 事件，写入 ${result.stats.indexed ?? 0} 文档`)
      void queryClient.invalidateQueries({ queryKey: ['index-lag'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const rebuild = useMutation({
    mutationFn: () => api.indexRebuild(500),
    onSuccess: (result) => {
      message.success(
        `索引已重建：重放 ${result.stats.processed ?? 0} 事件 → ${result.lag.indexedDocs} 篇文档`,
      )
      void queryClient.invalidateQueries({ queryKey: ['index-lag'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const data = lag.data

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="检索索引水位"
        extra={<CapabilityBadge status={capabilities.get('core.search-index')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        {lag.isError && <Alert type="error" showIcon message={(lag.error as Error).message} />}
        <Row gutter={16}>
          <Col span={6}>
            <Statistic title="已消费到 seq" value={data?.lastConsumedSeq ?? 0} />
          </Col>
          <Col span={6}>
            <Statistic title="最新事件 seq" value={data?.latestEventSeq ?? 0} />
          </Col>
          <Col span={6}>
            <Statistic
              title="滞后（lag）"
              value={data?.lag ?? 0}
              valueStyle={{ color: (data?.lag ?? 0) > 0 ? '#faad14' : '#52c41a' }}
            />
          </Col>
          <Col span={6}>
            <Statistic title="索引文档" value={data?.indexedDocs ?? 0} />
          </Col>
        </Row>
        <Space style={{ marginTop: 16 }}>
          <Button onClick={() => consume.mutate()} loading={consume.isPending}>
            手动增量消费
          </Button>
          <Button danger onClick={() => rebuild.mutate()} loading={rebuild.isPending}>
            重建索引（清空后从 seq=0 重放）
          </Button>
        </Space>
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 12 }}>
          重建是这个架构主张的可执行证明：<span className="dg-mono">search_doc</span> 是派生视图，
          可以随时丢弃并从事件流重放重建（ADR-002）。重建期间检索结果会短暂变少。
        </Paragraph>
      </Card>

      <Card size="small" title="为什么索引是派生视图">
        <Paragraph style={{ marginBottom: 8 }}>
          真相源只有 PostgreSQL（<span className="dg-mono">entity / aspect / edge</span>）；
          检索索引从 <span className="dg-mono">event_log</span> 消费而来，消费者进度记录在
          <span className="dg-mono"> consumer_offset</span>。
        </Paragraph>
        <Paragraph style={{ marginBottom: 0 }} type="secondary">
          这样做的代价是"最终一致"（所以界面必须显示 lag），换来的是：
          索引结构可以随时演进、损坏可以随时重建，而不会动到真相源。
        </Paragraph>
      </Card>
    </Space>
  )
}

function RuntimeTab({ capabilitiesReady }: { capabilitiesReady: boolean }) {
  const health = useQuery({ queryKey: ['healthz'], queryFn: () => api.health(), retry: false })
  const me = useQuery({ queryKey: ['me-runtime'], queryFn: () => api.me(), retry: false })
  const [token, setLocalToken] = useState(localStorage.getItem('dg_token') ?? '')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title="控制面状态">
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="/healthz">
            {health.isError ? <Text type="danger">{(health.error as Error).message}</Text> : health.data?.status ?? '—'}
          </Descriptions.Item>
          <Descriptions.Item label="实现">
            {health.data?.controlPlane ?? '—'}
          </Descriptions.Item>
          <Descriptions.Item label="当前身份">
            {me.data?.authenticated ? `${me.data.name}（${(me.data.roles ?? []).join('/')}）` : '未认证'}
          </Descriptions.Item>
          <Descriptions.Item label="可见分级">
            {(() => {
              const levels = (me.data as Record<string, unknown> | undefined)?.visibleLevels
              return Array.isArray(levels) ? levels.join(' / ') : '—'
            })()}
          </Descriptions.Item>
          <Descriptions.Item label="权限点" span={2}>
            {(() => {
              const permissions = (me.data as Record<string, unknown> | undefined)?.permissions
              return Array.isArray(permissions) ? permissions.join(', ') : '—'
            })()}
          </Descriptions.Item>
          <Descriptions.Item label="能力清单">
            {capabilitiesReady ? '已加载' : '未加载'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card size="small" title="访问令牌">
        <Space>
          <Input.Password
            style={{ width: 260 }}
            value={token}
            onChange={(event) => setLocalToken(event.target.value)}
            placeholder="dev-admin-token / dev-steward-token / dev-reader-token"
          />
          <Select
            style={{ width: 200 }}
            placeholder="开发令牌"
            onChange={(value) => {
              setLocalToken(value)
              localStorage.setItem('dg_token', value)
              window.location.reload()
            }}
            options={[
              { value: 'dev-admin-token', label: 'dev-admin-token（ADMIN）' },
              { value: 'dev-steward-token', label: 'dev-steward-token（STEWARD）' },
              { value: 'dev-reader-token', label: 'dev-reader-token（READER）' },
            ]}
          />
        </Space>
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 12 }}>
          生产必须替换为 OIDC/JWKS：Java 控制面的对齐尚未实现（见「能力清单 › D7」）。
        </Paragraph>
      </Card>

      <Card size="small" title="控制面架构说明">
        <Paragraph style={{ marginBottom: 8 }}>
          Java 21 + Spring Boot 3 多模块：<span className="dg-mono">dg-model / dg-core / dg-ingestion /
          dg-lineage / dg-quality / dg-policy / dg-ai / dg-api / dg-sdk</span>。
        </Paragraph>
        <Paragraph style={{ marginBottom: 0 }} type="secondary">
          血缘的 SQL 静态解析按选型放在 Python(sqlglot) 侧车（<span className="dg-mono">python -m dg.cli
          sidecar</span>，默认 127.0.0.1:8099），控制面通过内部 HTTP 调用并把结果写入血缘图；
          侧车不可用时接口返回 502 并给出启动命令，**不**静默返回空血缘。
        </Paragraph>
      </Card>
    </Space>
  )
}
