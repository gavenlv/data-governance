import { useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
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
import { api, type CollectRun } from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'

const { Title, Text, Paragraph } = Typography

/** 管理：采集（已实现）、健康度（已实现）、模型（已实现）、能力清单（已实现）、调度与告警（未实现）。 */
export default function AdminPage() {
  const capabilities = useCapabilities()

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
        items={[
          { key: 'collect', label: '采集', children: <CollectTab /> },
          { key: 'health', label: '采集健康度', children: <HealthTab /> },
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
