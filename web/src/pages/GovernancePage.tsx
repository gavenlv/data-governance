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
  Button,
  message,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  api,
  type AccessEventRow,
  type AccessGrantRow,
  type AccessRequestRow,
  type AccessReviewItem,
  type CiGateResult,
  type ContractDiffResult,
  type ContractRow,
  type ContractVersionRow,
  type ContractViolationRow,
  type ContractConsumers,
  type EngineAuditRecord,
  type PolicyDeploymentRow,
  type PolicyRow,
  type UnapprovedAccessRow,
} from '../api/client'
import { CapabilityBadge } from '../components/CapabilityBadge'
import { useCapabilities } from '../hooks/useCapabilities'
import { useTabParam } from '../hooks/useTabParam'

const { Title, Text, Paragraph } = Typography

/**
 * 治理入口（docs/09 §9.5 契约 + §9.7 访问治理）。
 *
 * Batch 2 已实现「数据契约」与「CI 门禁」；访问治理（申请审批 / 策略编译下发 / 审计复核）
 * 仍未实现，按能力清单渲染占位卡 —— 不假装有。
 */
export default function GovernancePage() {
  const capabilities = useCapabilities()
  const [tab, setTab] = useTabParam('access')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <div className="dg-page-title">
          <Title level={4} style={{ margin: 0 }}>
            治理
          </Title>
          <CapabilityBadge status={capabilities.get('quality.contract')?.status ?? 'NOT_IMPLEMENTED'} />
          <CapabilityBadge status={capabilities.get('policy.rbac')?.status ?? 'NOT_IMPLEMENTED'} />
        </div>
        <Text type="secondary">
          数据契约与 CI 门禁（已实现）· 访问治理生命周期（未实现，见下方占位卡）。
        </Text>
      </div>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'access', label: '访问申请与授权', children: <AccessTab /> },
          { key: 'review', label: '复核与最小权限', children: <ReviewTab /> },
          { key: 'engine', label: '引擎审计（真实访问）', children: <EngineAuditTab /> },
          { key: 'policy', label: '策略与覆盖率', children: <PolicyTab /> },
          { key: 'audit', label: '审计取证', children: <AuditTab /> },
          { key: 'contracts', label: '数据契约', children: <ContractsTab /> },
          { key: 'violations', label: '违约事件', children: <ViolationsTab /> },
        ]}
      />
    </Space>
  )
}

/**
 * 引擎审计（真实访问）。
 *
 * <p>这一页回答两个此前答不了的问题：
 * 「批准了但从来没被用过」与「被访问了但从来没被批准过」。
 * 采集侧是**推送**（引擎事件 / 日志采集器），因此页面同时给出接入情况与可复制的推送格式。
 */
function EngineAuditTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const [recordsText, setRecordsText] = useState('')
  const [ingestResult, setIngestResult] = useState<Record<string, unknown> | null>(null)

  const coverage = useQuery({ queryKey: ['engine-audit-coverage'], queryFn: () => api.engineAuditCoverage() })
  const records = useQuery({ queryKey: ['engine-audit-records'], queryFn: () => api.engineAuditRecords({ limit: 50 }) })
  const unapproved = useQuery({ queryKey: ['unapproved-access'], queryFn: () => api.unapprovedAccess(3650, 50) })

  const ingest = useMutation({
    mutationFn: (values: Record<string, unknown>) => {
      let parsed: Record<string, unknown>[] = []
      try {
        const raw = JSON.parse(String(values.records ?? '[]')) as unknown
        parsed = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [raw as Record<string, unknown>]
      } catch {
        throw new Error('记录必须是 JSON 数组（每条形如 {"user":"alice","timestamp":"...","table":"db.schema.tbl"}）')
      }
      return api.engineAuditIngest({
        engine: String(values.engine),
        namespace: values.namespace ? String(values.namespace) : undefined,
        records: parsed,
      })
    },
    onSuccess: (result) => {
      setIngestResult(result as unknown as Record<string, unknown>)
      void queryClient.invalidateQueries({ queryKey: ['engine-audit-coverage'] })
      void queryClient.invalidateQueries({ queryKey: ['engine-audit-records'] })
      void queryClient.invalidateQueries({ queryKey: ['unapproved-access'] })
      message.success(`摄入完成：接受 ${result.accepted}，去重 ${result.duplicated}，未解析 ${result.unresolved}，被拒 ${result.rejected}`)
    },
    onError: (error) => message.error(String(error)),
  })

  const totalRecords = coverage.data?.totalRecords ?? 0
  const sample = JSON.stringify([
    { user: 'alice', queryId: 'q-1', timestamp: '2026-10-04T10:00:00Z',
      table: 'dg.public.event_log', columns: ['event_id'], operation: 'SELECT' },
  ], null, 2)

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type={totalRecords > 0 ? 'success' : 'warning'}
        showIcon
        message={
          totalRecords > 0
            ? `已接入引擎审计：${totalRecords} 条记录（${coverage.data?.byEngine?.map((row) => row.engine).join(' / ') || '—'}）`
            : '尚未接入引擎审计：目前无法判断「批准了但没被用过」，复核里会明确标注证据不足'
        }
        description={
          <>
            平台接受**推送**（Trino 查询事件 / Ranger 访问审计 / 数仓查询日志）。
            记录按内容哈希**幂等去重**；解析不到平台资产的记录**仍然保留**并说明原因
            （丢弃等于宣称这次访问没有发生）。
            <br />
            采集侧参考实现：<Text code>python tools/engine_audit_load.py --engine trino --file queries.jsonl</Text>
          </>
        }
      />

      <Row gutter={16}>
        <Col span={10}>
          <Card
            size="small"
            title="接入情况"
            extra={<CapabilityBadge status={capabilities.get('policy.engine-audit-ingest')?.status ?? 'NOT_IMPLEMENTED'} />}
          >
            {coverage.data?.byEngine?.length ? (
              <Table
                size="small"
                rowKey="engine"
                pagination={false}
                dataSource={coverage.data.byEngine}
                columns={[
                  { title: '引擎', dataIndex: 'engine', width: 100 },
                  { title: '记录', dataIndex: 'records', width: 80 },
                  {
                    title: '解析 / 未解析',
                    render: (_, row) => (
                      <Space size={4}>
                        <Tag color="success">{row.resolved}</Tag>
                        <Tag color={row.unresolved > 0 ? 'warning' : 'default'}>{row.unresolved}</Tag>
                      </Space>
                    ),
                  },
                  {
                    title: '观测窗口',
                    render: (_, row) => (
                      <Text style={{ fontSize: 12 }}>
                        {String(row.window_from ?? '').slice(0, 19).replace('T', ' ')} 起
                      </Text>
                    ),
                  },
                ]}
              />
            ) : (
              <Empty description="还没有任何引擎审计记录" />
            )}
            {coverage.data && (
              <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                {coverage.data.resolutionNote}
              </Paragraph>
            )}
          </Card>
        </Col>
        <Col span={14}>
          <Card size="small" title="推送一批记录（试推 / 接入前的连通性验证）">
            <Form
              form={form}
              layout="inline"
              initialValues={{ engine: 'trino', namespace: 'prod', records: sample }}
              onFinish={(values) => ingest.mutate(values)}
            >
              <Form.Item name="engine" label="引擎">
                <Select
                  style={{ width: 130 }}
                  options={['trino', 'ranger', 'warehouse', 'superset', 'other'].map((value) => ({ value, label: value }))}
                />
              </Form.Item>
              <Form.Item name="namespace" label="命名空间">
                <Input style={{ width: 130 }} />
              </Form.Item>
              <Form.Item name="records" label="记录（JSON 数组）" style={{ width: '100%', marginTop: 8 }}>
                <Input.TextArea rows={7} value={recordsText} onChange={(event) => setRecordsText(event.target.value)} />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={ingest.isPending}>
                推送
              </Button>
            </Form>
            {ingestResult && (
              <Alert
                style={{ marginTop: 8 }}
                type={Number(ingestResult.rejected) > 0 ? 'warning' : 'info'}
                message={`接受 ${ingestResult.accepted} / 去重 ${ingestResult.duplicated} / 未解析 ${ingestResult.unresolved} / 被拒 ${ingestResult.rejected}`}
                description={
                  Array.isArray(ingestResult.rejectedSamples) && (ingestResult.rejectedSamples as string[]).length > 0
                    ? `被拒原因：${(ingestResult.rejectedSamples as string[]).join('；')}`
                    : String(ingestResult.note ?? '')
                }
              />
            )}
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="被访问但从未被批准（绕过治理的访问信号）"
        extra={
          <Text type="secondary" style={{ fontSize: 12 }}>
            接入引擎审计后新增的能力：直连访问会留下记录
          </Text>
        }
      >
        <Table<UnapprovedAccessRow>
          size="small"
          rowKey={(row) => `${row.actor}|${row.resource_urn}|${row.engine}`}
          loading={unapproved.isLoading}
          dataSource={unapproved.data?.unapproved ?? []}
          pagination={{ pageSize: 8 }}
          locale={{ emptyText: <Empty description="没有发现「无授权但有访问」的记录" /> }}
          columns={[
            { title: '主体', dataIndex: 'actor', width: 200 },
            {
              title: '资源',
              dataIndex: 'resource_urn',
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{value}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    引擎侧原名 {row.resource_raw}（{row.engine}）
                  </Text>
                </Space>
              ),
            },
            { title: '次数', dataIndex: 'queries', width: 80 },
            {
              title: '最近访问',
              dataIndex: 'last_access_at',
              width: 170,
              render: (value: string) => <Text style={{ fontSize: 12 }}>{String(value).slice(0, 19).replace('T', ' ')}</Text>,
            },
          ]}
        />
        <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
          {String(unapproved.data?.note ?? '')}
        </Paragraph>
      </Card>

      <Card size="small" title="原始记录（取证用）">
        <Table<EngineAuditRecord>
          size="small"
          rowKey="id"
          loading={records.isLoading}
          dataSource={records.data?.records ?? []}
          pagination={{ pageSize: 10 }}
          columns={[
            {
              title: '时间',
              dataIndex: 'event_time',
              width: 170,
              render: (value: string) => <Text style={{ fontSize: 12 }}>{String(value).slice(0, 19).replace('T', ' ')}</Text>,
            },
            { title: '主体', dataIndex: 'actor', width: 180 },
            { title: '操作', dataIndex: 'operation', width: 90 },
            {
              title: '资源',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{row.resource_raw}</Text>
                  {row.resolved ? (
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      → {row.resource_urn}
                    </Text>
                  ) : (
                    <Text type="warning" style={{ fontSize: 11 }}>
                      未解析：{row.resolve_note}
                    </Text>
                  )}
                </Space>
              ),
            },
            {
              title: '列',
              dataIndex: 'columns',
              width: 160,
              render: (value: string[]) => (
                <Space size={4} wrap>
                  {(value ?? []).slice(0, 4).map((column) => (
                    <Tag key={column}>{column}</Tag>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}

// -------------------------------------------------------------- 访问申请与授权

function AccessTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()

  const overview = useQuery({ queryKey: ['access-overview'], queryFn: () => api.accessOverview() })
  const requests = useQuery({ queryKey: ['access-requests'], queryFn: () => api.accessRequests() })
  const grants = useQuery({ queryKey: ['access-grants'], queryFn: () => api.accessGrants() })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['access-requests'] })
    void queryClient.invalidateQueries({ queryKey: ['access-grants'] })
    void queryClient.invalidateQueries({ queryKey: ['access-overview'] })
  }

  const submit = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.accessSubmit({
        resourceUrn: String(values.resourceUrn),
        columnName: values.columnName ? String(values.columnName) : undefined,
        granularity: values.granularity === 'COLUMN' ? 'COLUMN' : 'DATASET',
        permissions: String(values.permissions ?? 'SELECT')
          .split(',')
          .map((item) => item.trim().toUpperCase())
          .filter(Boolean),
        purpose: String(values.purpose ?? ''),
        durationDays: Number(values.durationDays ?? 90),
      }),
    onSuccess: (result) => {
      message.success('申请已提交')
      Modal.info({
        title: '申请已提交（自动填充的结果就是审批依据）',
        width: 640,
        content: (
          <Space direction="vertical" size={4}>
            <Text>
              路由：<Text strong>{String(result.route)}</Text>
            </Text>
            <Text>SLA 截止：{String(result.slaDueAt).slice(0, 19).replace('T', ' ')}</Text>
            <Text>最小粒度建议：{String(result.granularitySuggestion)}</Text>
            {((result.notes as string[]) ?? []).map((note) => (
              <Text key={note} type="secondary" style={{ fontSize: 12 }}>
                · {note}
              </Text>
            ))}
          </Space>
        ),
      })
      invalidate()
    },
    onError: (error: Error) => message.error(error.message),
  })

  const decide = useMutation({
    mutationFn: ({ id, decision }: { id: number; decision: 'APPROVED' | 'REJECTED' }) => {
      const note =
        decision === 'REJECTED'
          ? window.prompt('拒绝原因（必填，申请人有权知道理由）', '用途描述不足，请补充') ?? ''
          : window.prompt('审批意见（可选）', '用途明确，批准') ?? ''
      return api.accessDecide(id, decision, note)
    },
    onSuccess: (result) => {
      if (result.grantId) {
        message.success(`已批准，授权 #${result.grantId} 到期时间 ${String(result.expiresAt).slice(0, 10)}`)
      } else {
        message.info('已拒绝')
      }
      invalidate()
    },
    onError: (error: Error) => message.error(error.message),
  })

  const revoke = useMutation({
    mutationFn: (id: number) => {
      const reason = window.prompt('吊销原因（必填）', '复核未通过')
      if (!reason) throw new Error('已取消')
      return api.accessRevoke(id, reason)
    },
    onSuccess: () => {
      message.success('已吊销（执行层需重新编译下发才会真正生效）')
      invalidate()
    },
    onError: (error: Error) => message.error(error.message),
  })

  const sweep = useMutation({
    mutationFn: () => api.accessExpireSweep(),
    onSuccess: (result) => {
      message.info(`到期回收：授权 ${String(result.expiredGrants)} 条，僵尸申请 ${String(result.expiredRequests)} 条`)
      invalidate()
    },
  })

  const data = overview.data as
    | {
        overdueRequests?: number
        activeGrants?: number
        pendingRequests?: { status: string; count: number }[]
        expiringSoon?: { id: number; subject: string; resource_urn: string; days_left: number }[]
      }
    | undefined

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="待审批" value={(data?.pendingRequests ?? []).reduce((sum, item) => sum + item.count, 0)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="超期未处理"
              value={data?.overdueRequests ?? 0}
              valueStyle={{ color: (data?.overdueRequests ?? 0) > 0 ? '#ff4d4f' : undefined }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="生效中的授权" value={data?.activeGrants ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="30 天内到期" value={(data?.expiringSoon ?? []).length} />
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="提交访问申请（自动填充分级 / 路由 / SLA / 最小粒度建议）"
        extra={<CapabilityBadge status={capabilities.get('policy.access-request')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Form
          form={form}
          layout="inline"
          initialValues={{ granularity: 'COLUMN', permissions: 'SELECT', durationDays: 30 }}
          onFinish={(values) => submit.mutate(values)}
        >
          <Form.Item name="resourceUrn" label="资产 URN" rules={[{ required: true }]}>
            <Input style={{ width: 380 }} placeholder="urn:dg:Dataset:ns.platform.db.schema.table" />
          </Form.Item>
          <Form.Item name="granularity" label="粒度">
            <Select
              style={{ width: 110 }}
              options={[
                { value: 'COLUMN', label: '列级' },
                { value: 'DATASET', label: '整表' },
              ]}
            />
          </Form.Item>
          <Form.Item name="columnName" label="列名">
            <Input style={{ width: 140 }} placeholder="列级申请必填" />
          </Form.Item>
          <Form.Item name="permissions" label="权限">
            <Input style={{ width: 130 }} />
          </Form.Item>
          <Form.Item name="durationDays" label="期限(天)">
            <InputNumber min={1} max={3650} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="purpose" label="用途" rules={[{ required: true }]}>
            <Input style={{ width: 320 }} placeholder="用途是审批依据，不能只写「看看」" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={submit.isPending}>
              提交申请
            </Button>
          </Form.Item>
        </Form>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          平台会自动给出路由与 SLA：分级 L3/L4 需要数据管家会签；列级申请 SLA 更宽、整表更紧；
          <Text strong>最小粒度建议</Text>会明确提示"给列不给表"。申请人不能审批自己的申请。
        </Paragraph>
      </Card>

      <Card size="small" title="申请列表">
        {requests.isError && <Alert type="error" showIcon message={(requests.error as Error).message} />}
        <Table<AccessRequestRow>
          size="small"
          rowKey="id"
          loading={requests.isLoading}
          dataSource={requests.data?.requests ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有申请" /> }}
          columns={[
            { title: '#', dataIndex: 'id', width: 60 },
            { title: '申请人', dataIndex: 'requester', width: 150 },
            {
              title: '资源',
              dataIndex: 'resource_urn',
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <span className="dg-urn">{value}</span>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {row.granularity === 'COLUMN' ? `列级 · ${row.column_name}` : '整表'} · {(row.permissions ?? []).join(',')}
                  </Text>
                </Space>
              ),
            },
            { title: '用途', dataIndex: 'purpose', width: 200, ellipsis: true },
            { title: '分级', dataIndex: 'classification', width: 80 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (value: string, row) => (
                <Space size={4}>
                  <Tag color={value === 'APPROVED' ? 'green' : value === 'REJECTED' ? 'red' : 'orange'}>{value}</Tag>
                  {row.overdue && <Tag color="red">超期</Tag>}
                </Space>
              ),
            },
            {
              title: '审批链 / SLA',
              width: 190,
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{(row.approvers ?? []).join(' + ')}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {row.sla_due_at ? String(row.sla_due_at).slice(0, 16).replace('T', ' ') : '—'}
                  </Text>
                </Space>
              ),
            },
            {
              title: '操作',
              width: 130,
              render: (_, row) =>
                ['SUBMITTED', 'IN_REVIEW'].includes(row.status) ? (
                  <Space>
                    <a onClick={() => decide.mutate({ id: row.id, decision: 'APPROVED' })}>批准</a>
                    <a onClick={() => decide.mutate({ id: row.id, decision: 'REJECTED' })}>拒绝</a>
                  </Space>
                ) : (
                  <Text type="secondary">{row.decided_by ?? '—'}</Text>
                ),
            },
          ]}
        />
      </Card>

      <Card
        size="small"
        title="生效中的授权"
        extra={
          <Button size="small" onClick={() => sweep.mutate()} loading={sweep.isPending}>
            手动执行到期回收
          </Button>
        }
      >
        <Table<AccessGrantRow>
          size="small"
          rowKey="id"
          loading={grants.isLoading}
          dataSource={grants.data?.grants ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有授权" /> }}
          columns={[
            { title: '#', dataIndex: 'id', width: 60 },
            { title: '被授权人', dataIndex: 'subject', width: 150 },
            { title: '资源', dataIndex: 'resource_urn', render: (value: string) => <span className="dg-urn">{value}</span> },
            { title: '粒度', dataIndex: 'granularity', width: 80 },
            { title: '权限', dataIndex: 'permissions', width: 100, render: (value: string[]) => (value ?? []).join(',') },
            { title: '已存在(天)', dataIndex: 'age_days', width: 100 },
            {
              title: '剩余(天)',
              dataIndex: 'days_to_expiry',
              width: 100,
              render: (value: number) => (
                <Tag color={value <= 7 ? 'red' : value <= 30 ? 'orange' : undefined}>{value}</Tag>
              ),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (value: string) => (
                <Tag color={value === 'ACTIVE' ? 'green' : value === 'REVOKED' ? 'red' : 'default'}>{value}</Tag>
              ),
            },
            {
              title: '操作',
              width: 90,
              render: (_, row) =>
                row.status === 'ACTIVE' ? <a onClick={() => revoke.mutate(row.id)}>吊销</a> : '—',
            },
          ]}
        />
      </Card>
    </Space>
  )
}

// ------------------------------------------------------------ 复核与最小权限

function ReviewTab() {
  const queryClient = useQueryClient()
  const [campaign, setCampaign] = useState('2026-Q1')
  const review = useQuery({
    queryKey: ['access-review', campaign],
    queryFn: () => api.accessReviewCampaign(campaign),
  })
  const least = useQuery({ queryKey: ['access-least'], queryFn: () => api.accessLeastPrivilege() })

  const decide = useMutation({
    mutationFn: ({ grantId, decision }: { grantId: number; decision: string }) => {
      const reason =
        decision === 'REVOKE'
          ? window.prompt('回收原因（必填）', '授权已长期未复核') ?? ''
          : window.prompt('复核意见（可选）', '业务仍在使用') ?? ''
      return api.accessReviewDecide(campaign, grantId, decision, reason)
    },
    onSuccess: () => {
      message.success('复核已记录')
      void queryClient.invalidateQueries({ queryKey: ['access-review'] })
      void queryClient.invalidateQueries({ queryKey: ['access-grants'] })
      void queryClient.invalidateQueries({ queryKey: ['access-least'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const leastData = least.data as
    | { activeGrants?: number; neverReviewed?: unknown[]; granularityCandidates?: unknown[]; note?: string; usageDataAvailable?: boolean }
    | undefined

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="诚实边界：本平台尚未接入查询日志，无法判断「授权是否在用」"
        description="因此复核建议只基于授权时长与复核状态，不基于使用情况；缺少证据时会明确给出「需更多信息」而不是假设「未使用」。把「没有记录」当成「没有使用」是权限误回收最常见的原因。"
      />
      <Card
        size="small"
        title="定期复核（access review）"
        extra={
          <Space>
            <Input
              addonBefore="批次"
              style={{ width: 180 }}
              value={campaign}
              onChange={(event) => setCampaign(event.target.value)}
            />
            <CapabilityBadge status="IMPLEMENTED" />
          </Space>
        }
      >
        <Table<AccessReviewItem>
          size="small"
          rowKey="id"
          loading={review.isLoading}
          dataSource={review.data?.items ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="该批次没有待复核的授权" /> }}
          columns={[
            { title: '#', dataIndex: 'id', width: 60 },
            { title: '被授权人', dataIndex: 'subject', width: 140 },
            { title: '资源', dataIndex: 'resource_urn', render: (value: string) => <span className="dg-urn">{value}</span> },
            { title: '已存在(天)', dataIndex: 'age_days', width: 100 },
            {
              title: '建议',
              dataIndex: 'suggestedDecision',
              width: 120,
              render: (value: string) => (
                <Tag color={value === 'REVOKE' ? 'red' : value === 'KEEP' ? 'green' : 'orange'}>{value}</Tag>
              ),
            },
            { title: '建议依据', dataIndex: 'suggestionReason' },
            {
              title: '操作',
              width: 150,
              render: (_, row) => (
                <Space>
                  <a onClick={() => decide.mutate({ grantId: row.id, decision: 'KEEP' })}>保留</a>
                  <a onClick={() => decide.mutate({ grantId: row.id, decision: 'REVOKE' })}>回收</a>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" title="最小权限复盘">
        <Row gutter={16}>
          <Col span={8}>
            <Statistic title="生效授权" value={leastData?.activeGrants ?? 0} />
          </Col>
          <Col span={8}>
            <Statistic title="长期未复核" value={(leastData?.neverReviewed ?? []).length} />
          </Col>
          <Col span={8}>
            <Statistic title="整表授权（可收窄）" value={(leastData?.granularityCandidates ?? []).length} />
          </Col>
        </Row>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {leastData?.note}
        </Paragraph>
      </Card>
    </Space>
  )
}

// ------------------------------------------------------------- 策略与覆盖率

const SAMPLE_POLICY = `{
  "name": "region_row_filter",
  "description": "按区域限制行（业务可读的建模）",
  "target": "trino",
  "effect": "ROW_FILTER",
  "priority": 10,
  "resourceScope": { "prefixes": ["urn:dg:Dataset:prod."], "classification": ["L3", "L4"] },
  "subjectScope": { "roles": ["ANALYST_CN"] },
  "condition": { "column": "region", "operator": "=", "value": "CN" }
}`

function PolicyTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [text, setText] = useState(SAMPLE_POLICY)
  const [target, setTarget] = useState('trino')

  const policies = useQuery({ queryKey: ['policies'], queryFn: () => api.policies() })
  const deployments = useQuery({ queryKey: ['policy-deployments'], queryFn: () => api.policyDeployments() })
  const history = useQuery({ queryKey: ['policy-coverage-history'], queryFn: () => api.policyCoverageHistory() })

  const preview = useMutation({
    mutationFn: () => api.policyPreview(JSON.parse(text)),
    onError: (error: Error) => message.error(error.message),
  })

  const save = useMutation({
    mutationFn: () => api.policyUpsert(JSON.parse(text)),
    onSuccess: (result) => {
      message.success(`策略 ${String(result.name)} v${String(result.version)} 已保存（建模即编译）`)
      void queryClient.invalidateQueries({ queryKey: ['policies'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const compile = useMutation({
    mutationFn: () => api.policyCompile(),
    onSuccess: (result) => {
      message.success(`编译完成：${result.count} 条（失败 ${result.failed.length}）`)
      void queryClient.invalidateQueries({ queryKey: ['policies'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const deploy = useMutation({
    mutationFn: () => api.policyDeploy(target),
    onSuccess: (result) => {
      message.success(`bundle 已生成（${String(result.artifactCount)} 条产物）`)
      void queryClient.invalidateQueries({ queryKey: ['policy-deployments'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const rollback = useMutation({
    mutationFn: (id: number) => {
      const reason = window.prompt('回滚原因（会记入审计）', '产物有误') ?? ''
      return api.policyRollback(id, reason)
    },
    onSuccess: () => {
      message.success('已回滚')
      void queryClient.invalidateQueries({ queryKey: ['policy-deployments'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const coverage = useMutation({
    mutationFn: () => api.policyCoverage(),
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="定位（docs/20 §8）：执行层是商品，生命周期层才是产品"
        description="本平台不实现执行引擎（Trino SystemAccessControl / Ranger / 数仓原生策略都是现成件）。平台负责：业务可读的建模 → 可快照测试的编译 → 产物版本归档 → bundle 下发与回滚 → 覆盖率度量。"
      />
      <Card
        size="small"
        title="策略建模（编译预览后再保存）"
        extra={<CapabilityBadge status={capabilities.get('policy.compiler')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Input.TextArea rows={10} className="dg-mono" value={text} onChange={(event) => setText(event.target.value)} />
          <Space>
            <Button onClick={() => preview.mutate()} loading={preview.isPending}>
              编译预览
            </Button>
            <Button type="primary" onClick={() => save.mutate()} loading={save.isPending}>
              保存策略
            </Button>
            <Button onClick={() => compile.mutate()} loading={compile.isPending}>
              编译全部
            </Button>
            <Select
              value={target}
              style={{ width: 150 }}
              onChange={setTarget}
              options={(policies.data?.targets ?? []).map((item) => ({ value: item.id, label: item.id }))}
            />
            <Button onClick={() => deploy.mutate()} loading={deploy.isPending}>
              生成并下发 bundle
            </Button>
          </Space>
          {preview.data && (
            <Card size="small" type="inner" title={`编译产物（${preview.data.target} / ${preview.data.effect}）`}>
              {(preview.data.errors ?? []).length > 0 && (
                <Alert type="warning" showIcon message={preview.data.errors.join('；')} style={{ marginBottom: 8 }} />
              )}
              <pre className="dg-mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {preview.data.artifact}
              </pre>
            </Card>
          )}
        </Space>
      </Card>

      <Card
        size="small"
        title="覆盖率度量（最关键的数：宣称已下发而不度量覆盖率是最危险的表述）"
        extra={
          <Button onClick={() => coverage.mutate()} loading={coverage.isPending}>
            重新度量
          </Button>
        }
      >
        {coverage.data ? (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Row gutter={16}>
              <Col span={8}>
                <Statistic title="覆盖率" value={(coverage.data.coverageRatio * 100).toFixed(1)} suffix="%" />
              </Col>
              <Col span={8}>
                <Statistic title="已覆盖 / 总数" value={coverage.data.coveredAssets} suffix={`/ ${coverage.data.totalAssets}`} />
              </Col>
              <Col span={8}>
                <Statistic
                  title="高分级却无策略覆盖"
                  value={coverage.data.uncoveredHighClassification.length}
                  valueStyle={{
                    color: coverage.data.uncoveredHighClassification.length > 0 ? '#ff4d4f' : '#52c41a',
                  }}
                />
              </Col>
            </Row>
            {coverage.data.uncoveredHighClassification.length > 0 && (
              <Table
                size="small"
                rowKey="urn"
                pagination={{ pageSize: 5, showSizeChanger: false }}
                dataSource={coverage.data.uncoveredHighClassification}
                columns={[
                  { title: '资产', dataIndex: 'displayName', width: 220 },
                  { title: '分级', dataIndex: 'classification', width: 80 },
                  { title: '风险', dataIndex: 'risk' },
                ]}
              />
            )}
            <Alert
              type="warning"
              showIcon
              message="已知盲区（必须一起看，否则覆盖率数字会误导）"
              description={
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {coverage.data.knownBlindSpots.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              }
            />
          </Space>
        ) : (
          <Text type="secondary">点「重新度量」计算覆盖率（会落库形成趋势）</Text>
        )}
        {(history.data?.history ?? []).length > 0 && (
          <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
            历史度量 {history.data?.count} 次（趋势见接口 /api/v1/policies/coverage）
          </Paragraph>
        )}
      </Card>

      <Card size="small" title="策略清单">
        {policies.isError && <Alert type="error" showIcon message={(policies.error as Error).message} />}
        <Table<PolicyRow>
          size="small"
          rowKey="name"
          loading={policies.isLoading}
          dataSource={policies.data?.policies ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有策略" /> }}
          columns={[
            { title: '策略', dataIndex: 'name', width: 180 },
            { title: '目标', dataIndex: 'target', width: 100 },
            { title: '效果', dataIndex: 'effect', width: 130 },
            { title: '优先级', dataIndex: 'priority', width: 90 },
            { title: '版本', dataIndex: 'version', width: 70 },
            {
              title: '资源范围',
              dataIndex: 'resource_scope',
              render: (value: Record<string, unknown>) => <span className="dg-mono">{JSON.stringify(value)}</span>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (value: string) => <Tag color={value === 'ACTIVE' ? 'green' : undefined}>{value}</Tag>,
            },
          ]}
        />
      </Card>

      <Card size="small" title="下发记录与回滚">
        <Table<PolicyDeploymentRow>
          size="small"
          rowKey="id"
          loading={deployments.isLoading}
          dataSource={deployments.data?.deployments ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有下发记录" /> }}
          columns={[
            { title: '#', dataIndex: 'id', width: 60 },
            { title: '目标', dataIndex: 'target', width: 100 },
            { title: '产物数', dataIndex: 'artifact_count', width: 90 },
            {
              title: 'bundle 哈希',
              dataIndex: 'bundle_hash',
              width: 200,
              render: (value: string) => <span className="dg-mono">{String(value).slice(0, 16)}…</span>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 120,
              render: (value: string) => (
                <Tag color={value === 'APPLIED' ? 'green' : value === 'ROLLED_BACK' ? 'orange' : 'red'}>{value}</Tag>
              ),
            },
            { title: '下发人', dataIndex: 'deployed_by', width: 140 },
            {
              title: '时间',
              dataIndex: 'created_at',
              width: 170,
              render: (value: string) => <span className="dg-mono">{String(value).slice(0, 19).replace('T', ' ')}</span>,
            },
            {
              title: '操作',
              width: 90,
              render: (_, row) =>
                row.status === 'APPLIED' ? <a onClick={() => rollback.mutate(row.id)}>回滚到此版本</a> : '—',
            },
          ]}
        />
      </Card>
    </Space>
  )
}

// ---------------------------------------------------------------- 审计取证

function AuditTab() {
  const [days, setDays] = useState(90)
  const report = useQuery({ queryKey: ['access-audit-report', days], queryFn: () => api.accessAuditReport(days) })
  const events = useQuery({ queryKey: ['access-events'], queryFn: () => api.accessEvents({ limit: 100 }) })

  const reportData = report.data as
    | {
        requestFunnel?: { status: string; count: number }[]
        decisionLatency?: Record<string, unknown>[]
        grantsByStatus?: { status: string; count: number }[]
        grantsByClassification?: { classification: string; count: number }[]
        revokedGrants?: Record<string, unknown>[]
        recentPolicyDeployments?: Record<string, unknown>[]
        auditLogEntries?: number
        coverageNote?: { covered: string[]; notCovered: string[]; implication: string }
      }
    | undefined

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="合规审计报告"
        extra={
          <Space>
            <InputNumber
              addonBefore="窗口(天)"
              min={1}
              max={730}
              value={days}
              onChange={(value) => setDays(Number(value) || 90)}
            />
            <CapabilityBadge status="IMPLEMENTED" />
          </Space>
        }
      >
        {report.isError && <Alert type="error" showIcon message={(report.error as Error).message} />}
        <Row gutter={16}>
          <Col span={8}>
            <Statistic title="申请（窗口内）" value={(reportData?.requestFunnel ?? []).reduce((sum, item) => sum + item.count, 0)} />
          </Col>
          <Col span={8}>
            <Statistic title="生效授权" value={(reportData?.grantsByStatus ?? []).find((item) => item.status === 'ACTIVE')?.count ?? 0} />
          </Col>
          <Col span={8}>
            <Statistic title="元数据审计条目" value={reportData?.auditLogEntries ?? 0} />
          </Col>
        </Row>
        <Descriptions column={2} size="small" style={{ marginTop: 12 }}>
          <Descriptions.Item label="审批时延（平均 / 最长，小时）">
            {String((reportData?.decisionLatency ?? [{}])[0]?.avg_hours ?? '—')} /{' '}
            {String((reportData?.decisionLatency ?? [{}])[0]?.max_hours ?? '—')}
          </Descriptions.Item>
          <Descriptions.Item label="按分级分布">
            {(reportData?.grantsByClassification ?? [])
              .map((item) => `${item.classification}:${item.count}`)
              .join('  ')}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {reportData?.coverageNote && (
        <Alert
          type="warning"
          showIcon
          message="审计覆盖范围（一份不说明覆盖范围的报告比没有报告更危险）"
          description={
            <Space direction="vertical" size={4}>
              <div>
                <Text strong>已覆盖：</Text>
                {reportData.coverageNote.covered.map((item) => (
                  <div key={item}>· {item}</div>
                ))}
              </div>
              <div>
                <Text strong type="danger">
                  未覆盖：
                </Text>
                {reportData.coverageNote.notCovered.map((item) => (
                  <div key={item}>· {item}</div>
                ))}
              </div>
              <Text type="secondary">{reportData.coverageNote.implication}</Text>
            </Space>
          }
        />
      )}

      <Card size="small" title="访问事件（平台侧决策，含 allow/deny）">
        <Table<AccessEventRow>
          size="small"
          rowKey="id"
          loading={events.isLoading}
          dataSource={events.data?.events ?? []}
          pagination={{ pageSize: 15, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有访问事件" /> }}
          columns={[
            {
              title: '时间',
              dataIndex: 'occurred_at',
              width: 170,
              render: (value: string) => <span className="dg-mono">{String(value).slice(0, 19).replace('T', ' ')}</span>,
            },
            { title: '主体', dataIndex: 'subject', width: 150 },
            { title: '动作', dataIndex: 'action', width: 170 },
            {
              title: '判定',
              dataIndex: 'decision',
              width: 90,
              render: (value: string | null) =>
                value ? <Tag color={value === 'ALLOW' ? 'green' : 'red'}>{value}</Tag> : <Text type="secondary">—</Text>,
            },
            { title: '资源', dataIndex: 'resource_urn', render: (value: string) => <span className="dg-urn">{value}</span> },
            { title: '来源', dataIndex: 'source', width: 100 },
          ]}
        />
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          来源 <span className="dg-mono">platform</span> 表示平台自身的决策；
          <span className="dg-mono">engine</span>（引擎侧真实查询）当前**没有数据源** —— 未接入。
        </Paragraph>
      </Card>
    </Space>
  )
}

// ---------------------------------------------------------------------- 契约

function ContractsTab() {
  const capabilities = useCapabilities()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<string | null>(null)
  const [namespace, setNamespace] = useState('java_e2e')
  const [allowBreaking, setAllowBreaking] = useState(false)
  const [justification, setJustification] = useState('')
  const [documentText, setDocumentText] = useState(SAMPLE_CONTRACT)
  const [diffResult, setDiffResult] = useState<ContractDiffResult | null>(null)
  const [gate, setGate] = useState<CiGateResult | null>(null)

  const contracts = useQuery({ queryKey: ['contracts'], queryFn: () => api.contracts() })
  const versions = useQuery({
    queryKey: ['contract-versions', selected],
    queryFn: () => api.contractVersions(selected as string),
    enabled: Boolean(selected),
  })
  const consumers = useQuery({
    queryKey: ['contract-consumers', selected],
    queryFn: () => api.contractConsumers(selected as string),
    enabled: Boolean(selected),
  })

  const register = useMutation({
    mutationFn: () =>
      api.contractRegister({
        document: JSON.parse(toJsonDocument(documentText)),
        namespace,
        allowBreaking,
        breakingJustification: justification || undefined,
      }),
    onSuccess: (result) => {
      if (result.registered) {
        setDiffResult(result.diff ?? null)
        message.success(`契约已注册：${result.version}${result.generatedRules ? `（生成 ${result.generatedRules} 条质量规则）` : ''}`)
      } else {
        message.warning(result.rejected ?? '未注册')
        setDiffResult(result.diff ?? null)
      }
      void queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const runGate = useMutation({
    mutationFn: () =>
      api.contractCiCheck({
        document: JSON.parse(toJsonDocument(documentText)),
        namespace,
        allowBreaking,
        source: 'gui',
      }),
    onSuccess: (result) => setGate(result),
    onError: (error: Error) => message.error(error.message),
  })

  const validate = useMutation({
    mutationFn: (urn: string) => api.contractValidate(urn),
    onSuccess: (result) => {
      message.info(`校验完成：${result.violationCount} 条违约（约定 ${result.promisedFields} 列 / 实际 ${result.actualFields} 列）`)
      void queryClient.invalidateQueries({ queryKey: ['contracts'] })
      void queryClient.invalidateQueries({ queryKey: ['contract-violations'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const diffVersions = useMutation({
    mutationFn: ({ from, to }: { from: number; to: number }) => api.contractDiff(selected as string, from, to),
    onSuccess: (result) => setDiffResult(result),
    onError: (error: Error) => message.error(error.message),
  })

  const subscribe = useMutation({
    mutationFn: (consumerUrn: string) => api.contractAddConsumer(selected as string, consumerUrn, 'GUI 登记'),
    onSuccess: () => {
      message.success('已登记消费者')
      void queryClient.invalidateQueries({ queryKey: ['contract-consumers', selected] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="注册 / 发布契约（ODCS 兼容）"
        extra={<CapabilityBadge status={capabilities.get('quality.contract')?.status ?? 'NOT_IMPLEMENTED'} />}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Space wrap>
            <Input addonBefore="命名空间" style={{ width: 200 }} value={namespace} onChange={(e) => setNamespace(e.target.value)} />
            <Select
              style={{ width: 220 }}
              value={allowBreaking}
              onChange={setAllowBreaking}
              options={[
                { value: false, label: '破坏性变更：拒绝注册' },
                { value: true, label: '破坏性变更：允许（需理由）' },
              ]}
            />
            <Input
              placeholder="破坏性变更理由（必填）"
              style={{ width: 320 }}
              value={justification}
              onChange={(event) => setJustification(event.target.value)}
            />
            <Button type="primary" onClick={() => register.mutate()} loading={register.isPending}>
              注册为 propose
            </Button>
            <Button onClick={() => runGate.mutate()} loading={runGate.isPending}>
              跑一次 CI 门禁
            </Button>
          </Space>
          <Input.TextArea
            rows={12}
            className="dg-mono"
            value={documentText}
            onChange={(event) => setDocumentText(event.target.value)}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            契约的 schema 段是可执行的：删列 / 收窄类型 / 可选改必填 / 改语义 / 改主键都会被判为破坏性，
            并要求版本号如实升级（版本号骗人时门禁会拦住）。
          </Text>
        </Space>
      </Card>

      {gate && <GateResultCard result={gate} />}
      {diffResult && <DiffCard result={diffResult} />}

      <Card size="small" title="契约清单">
        {contracts.isError && <Alert type="error" showIcon message={(contracts.error as Error).message} />}
        <Table<ContractRow>
          size="small"
          rowKey="urn"
          loading={contracts.isLoading}
          dataSource={contracts.data?.contracts ?? []}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="还没有契约" /> }}
          onRow={(row) => ({ onClick: () => setSelected(row.urn) })}
          columns={[
            {
              title: '契约',
              dataIndex: 'id',
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Text strong={selected === row.urn}>{value}</Text>
                  <span className="dg-urn">{row.urn}</span>
                </Space>
              ),
            },
            { title: '版本', dataIndex: 'contractVersion', width: 100 },
            { title: 'ODCS', dataIndex: 'apiVersion', width: 100 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (value: string) => <Tag color={value === 'ACTIVE' ? 'green' : undefined}>{value}</Tag>,
            },
            { title: '列数', dataIndex: 'fieldCount', width: 70 },
            { title: '质量约束', dataIndex: 'checkCount', width: 90 },
            {
              title: '违约',
              dataIndex: 'openViolations',
              width: 80,
              render: (value: number) => (value > 0 ? <Tag color="red">{value}</Tag> : <Tag color="green">0</Tag>),
            },
            { title: '消费者', dataIndex: 'consumers', width: 90 },
            {
              title: '操作',
              width: 110,
              render: (_, row) => (
                <a
                  onClick={(event) => {
                    event.stopPropagation()
                    validate.mutate(row.urn)
                  }}
                >
                  校验违约
                </a>
              ),
            },
          ]}
        />
      </Card>

      {selected && (
        <Row gutter={16}>
          <Col span={12}>
            <Card size="small" title="版本历史（含当前版本）">
              {versions.isError && <Alert type="error" showIcon message={(versions.error as Error).message} />}
              <Table<ContractVersionRow>
                size="small"
                rowKey={(row) => `${row.version}`}
                loading={versions.isLoading}
                dataSource={versions.data?.versions ?? []}
                pagination={false}
                columns={[
                  { title: '版本', dataIndex: 'version', width: 70 },
                  { title: '契约版本', dataIndex: 'contract_version', width: 100 },
                  { title: '状态', dataIndex: 'status', width: 90 },
                  { title: '列数', dataIndex: 'field_count', width: 70 },
                  {
                    title: '当前',
                    dataIndex: 'is_current',
                    width: 70,
                    render: (value: boolean) => (value ? <Tag color="blue">当前</Tag> : ''),
                  },
                  {
                    title: '对比',
                    width: 110,
                    render: (_, row, index) => {
                      const list = versions.data?.versions ?? []
                      const previous = list[index + 1]
                      if (!previous) return null
                      return (
                        <a onClick={() => diffVersions.mutate({ from: previous.version, to: row.version })}>
                          vs v{previous.version}
                        </a>
                      )
                    },
                  },
                ]}
              />
              <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8, fontSize: 12 }}>
                {versions.data?.note}
              </Paragraph>
            </Card>
          </Col>
          <Col span={12}>
            <Card size="small" title="消费者（谁在真的用它）">
              {consumers.isError && (
                <Alert
                  type="warning"
                  showIcon
                  message="读取消费者失败"
                  description={(consumers.error as Error).message}
                />
              )}
              {consumers.data && <ConsumersPanel data={consumers.data} onSubscribe={(urn) => subscribe.mutate(urn)} />}
            </Card>
          </Col>
        </Row>
      )}
    </Space>
  )
}

function GateResultCard({ result }: { result: CiGateResult }) {
  const color = result.verdict === 'PASS' ? 'success' : result.verdict === 'WARN' ? 'warning' : 'error'
  return (
    <Card
      size="small"
      title={`CI 门禁判定：${result.verdict}`}
      extra={<Tag color={result.verdict === 'BLOCK' ? 'red' : result.verdict === 'WARN' ? 'orange' : 'green'}>
        退出码 {result.exitCode}
      </Tag>}
    >
      <Alert
        type={color}
        showIcon
        message={`${result.fromVersion ?? '(新契约)'} → ${result.toVersion}`}
        description={
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            {result.blocking.map((item) => (
              <Text key={item} type="danger">
                阻断：{item}
              </Text>
            ))}
            {result.warnings.map((item) => (
              <Text key={item} type="warning">
                警告：{item}
              </Text>
            ))}
            {result.passed.map((item) => (
              <Text key={item} type="secondary">
                通过：{item}
              </Text>
            ))}
          </Space>
        }
      />
      <Row gutter={16} style={{ marginTop: 12 }}>
        <Col span={8}>
          <Statistic title="血缘下游" value={result.impact.downstreamCount} suffix={`关键 ${result.impact.criticalCount}`} />
          <Space direction="vertical" size={0} style={{ marginTop: 4 }}>
            {result.impact.downstream.slice(0, 4).map((item) => (
              <Text key={item} type="secondary" style={{ fontSize: 12 }}>
                {item}
              </Text>
            ))}
          </Space>
        </Col>
        <Col span={8}>
          <Statistic title="治理属性缺失" value={result.governance.missing.length} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {result.governance.missing.length > 0 ? result.governance.missing.join('、') : '齐备'}
          </Text>
        </Col>
        <Col span={8}>
          <Statistic title="未登记消费者" value={result.consumers?.undetected.length ?? 0} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            血缘上实际在用但未登记契约的资产
          </Text>
        </Col>
      </Row>
    </Card>
  )
}

function DiffCard({ result }: { result: ContractDiffResult }) {
  return (
    <Card
      size="small"
      title={`兼容性 diff：${result.verdict}`}
      extra={
        <Space>
          <Tag color={result.breaking ? 'red' : 'green'}>{result.breaking ? '破坏性' : '兼容'}</Tag>
          <Tag>应为 {result.requiredVersionBump} 级</Tag>
          {!result.versionBumpSufficient && <Tag color="red">版本号不诚实</Tag>}
        </Space>
      }
    >
      {result.changes.length === 0 ? (
        <Text type="secondary">没有结构变更</Text>
      ) : (
        <Table
          size="small"
          rowKey={(row) => `${row.kind}-${row.column}-${row.message}`}
          dataSource={result.changes}
          pagination={false}
          columns={[
            {
              title: '级别',
              dataIndex: 'severity',
              width: 90,
              render: (value: string) => <Tag color={value === 'MAJOR' ? 'red' : 'orange'}>{value}</Tag>,
            },
            { title: '类型', dataIndex: 'kind', width: 180 },
            { title: '列', dataIndex: 'column', width: 120 },
            { title: '说明', dataIndex: 'message' },
          ]}
        />
      )}
      {result.notes.length > 0 && (
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {result.notes.join('；')}
        </Paragraph>
      )}
    </Card>
  )
}

function ConsumersPanel({ data, onSubscribe }: { data: ContractConsumers; onSubscribe: (urn: string) => void }) {
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Alert
        type={data.undetected.length > 0 ? 'warning' : 'success'}
        showIcon
        message={data.note}
      />
      <div>
        <Text strong>已登记消费者（{data.registered.length}）</Text>
        <div>
          {data.registered.length === 0 ? (
            <Text type="secondary">无</Text>
          ) : (
            data.registered.map((urn) => <div key={urn} className="dg-urn">{urn}</div>)
          )}
        </div>
      </div>
      <div>
        <Text strong>血缘上的实际下游（{data.external.length}）</Text>
        <List
          size="small"
          dataSource={data.external}
          locale={{ emptyText: '血缘上没有下游' }}
          renderItem={(urn) => (
            <List.Item
              actions={
                data.registered.includes(urn)
                  ? [<Tag key="ok" color="green">已登记</Tag>]
                  : [<a key="sub" onClick={() => onSubscribe(urn)}>登记为消费者</a>]
              }
            >
              <span className="dg-urn">{urn}</span>
            </List.Item>
          )}
        />
      </div>
      {data.staleRegistrations.length > 0 && (
        <div>
          <Text strong type="warning">登记了但血缘上没在用（{data.staleRegistrations.length}）</Text>
          <div>
            {data.staleRegistrations.map((urn) => (
              <div key={urn} className="dg-urn">{urn}</div>
            ))}
          </div>
        </div>
      )}
    </Space>
  )
}

// ------------------------------------------------------------------ 违约事件

function ViolationsTab() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<string | undefined>('OPEN')

  const violations = useQuery({
    queryKey: ['contract-violations', status],
    queryFn: () => api.contractViolations({ status }),
  })
  const overview = useQuery({
    queryKey: ['contract-violation-overview'],
    queryFn: () => api.contractViolationOverview(),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['contract-violations'] })
    void queryClient.invalidateQueries({ queryKey: ['contract-violation-overview'] })
    void queryClient.invalidateQueries({ queryKey: ['contracts'] })
  }

  const ack = useMutation({
    mutationFn: (id: number) => api.contractAckViolation(id),
    onSuccess: () => {
      message.success('已确认（确认≠修复，重新校验仍会累计次数）')
      invalidate()
    },
    onError: (error: Error) => message.error(error.message),
  })

  const exempt = useMutation({
    mutationFn: (id: number) => {
      const until = new Date(Date.now() + 14 * 24 * 3600 * 1000).toISOString()
      return api.contractExemptViolation(id, until, 'GUI 临时豁免（14 天）')
    },
    onSuccess: () => {
      message.success('已豁免（到期后自动回到 OPEN）')
      invalidate()
    },
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="涉及契约" value={(overview.data?.contracts as number) ?? 0} />
          </Card>
        </Col>
        <Col span={16}>
          <Card size="small" title="豁免策略">
            <Text type="secondary">{String(overview.data?.exemptionPolicy ?? '')}</Text>
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="违约事件"
        extra={
          <Select
            value={status}
            style={{ width: 160 }}
            onChange={setStatus}
            options={[
              { value: 'OPEN', label: 'OPEN' },
              { value: 'ACKNOWLEDGED', label: 'ACKNOWLEDGED' },
              { value: 'EXEMPTED', label: 'EXEMPTED' },
              { value: undefined, label: '全部' },
            ]}
          />
        }
      >
        {violations.isError && <Alert type="error" showIcon message={(violations.error as Error).message} />}
        <Table<ContractViolationRow>
          size="small"
          rowKey="id"
          loading={violations.isLoading}
          dataSource={violations.data?.violations ?? []}
          pagination={{ pageSize: 15, showSizeChanger: false }}
          locale={{ emptyText: <Empty description="没有违约事件" /> }}
          columns={[
            {
              title: '级别',
              dataIndex: 'severity',
              width: 90,
              render: (value: string) => (
                <Tag color={value === 'BLOCK' ? 'red' : value === 'ALERT' ? 'orange' : undefined}>{value}</Tag>
              ),
            },
            { title: '类型', dataIndex: 'kind', width: 160 },
            {
              title: '详情',
              dataIndex: 'detail',
              render: (detail: Record<string, unknown>) => (
                <Tooltip title={<pre style={{ margin: 0 }}>{JSON.stringify(detail, null, 2)}</pre>}>
                  <span className="dg-mono">{JSON.stringify(detail).slice(0, 70)}</span>
                </Tooltip>
              ),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (value: string, row) => (
                <Space direction="vertical" size={0}>
                  <Tag color={value === 'OPEN' ? 'red' : value === 'EXEMPTED' ? 'orange' : 'blue'}>{value}</Tag>
                  {row.exempted_until && (
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      至 {String(row.exempted_until).slice(0, 10)}
                    </Text>
                  )}
                </Space>
              ),
            },
            { title: '次数', dataIndex: 'occurrences', width: 70 },
            {
              title: '首次 / 最近',
              width: 170,
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>{String(row.first_seen).slice(0, 19).replace('T', ' ')}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {String(row.last_seen).slice(0, 19).replace('T', ' ')}
                  </Text>
                </Space>
              ),
            },
            {
              title: '操作',
              width: 150,
              render: (_, row) => (
                <Space>
                  <a onClick={() => ack.mutate(row.id)}>确认</a>
                  <a onClick={() => exempt.mutate(row.id)}>豁免 14 天</a>
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}

// ------------------------------------------------------------------------ 工具

/**
 * 把简化 YAML 转成 JSON 文档。
 *
 * 前端只支持"够用且可预期"的 YAML 子集：顶层键值 + 缩进列表（`- key: value` 或 `- {…}`）。
 * 解析失败会抛出明确错误（由调用处展示），不会静默产生一个空契约。
 */
function toJsonDocument(text: string): string {
  const trimmed = text.trim()
  if (trimmed.startsWith('{')) return trimmed

  const root: Record<string, unknown> = {}
  let listKey: string | null = null
  let currentItem: Record<string, unknown> | null = null

  const closeItem = () => {
    if (listKey && currentItem) (root[listKey] as Record<string, unknown>[]).push(currentItem)
    currentItem = null
  }

  for (const rawLine of trimmed.split('\n')) {
    if (!rawLine.trim() || rawLine.trim().startsWith('#')) continue
    const indent = rawLine.length - rawLine.trimStart().length
    const line = rawLine.trim()

    if (indent === 0) {
      closeItem()
      const header = line.match(/^([A-Za-z_][\w]*)\s*:\s*$/)
      if (header) {
        listKey = header[1]
        root[listKey] = []
        continue
      }
      const pair = line.match(/^([A-Za-z_][\w]*)\s*:\s*(.*)$/)
      if (pair) {
        listKey = null
        root[pair[1]] = pair[2] === '' ? null : coerceScalar(pair[2])
      }
      continue
    }

    const flow = line.match(/^-\s*(\{.*\})$/)
    if (flow && listKey) {
      closeItem()
      root[listKey] = root[listKey] as Record<string, unknown>[]
      ;(root[listKey] as Record<string, unknown>[]).push(
        JSON.parse(flow[1].replace(/([{,]\s*)([A-Za-z_][\w]*)\s*:/g, '$1"$2":').replace(/'/g, '"')),
      )
      continue
    }

    const itemStart = line.match(/^-\s*([A-Za-z_][\w]*)\s*:\s*(.*)$/)
    if (itemStart && listKey) {
      closeItem()
      currentItem = { [itemStart[1]]: itemStart[2] === '' ? null : coerceScalar(itemStart[2]) }
      continue
    }

    const nested = line.match(/^([A-Za-z_][\w]*)\s*:\s*(.*)$/)
    if (nested) {
      if (currentItem) {
        currentItem[nested[1]] = nested[2] === '' ? null : coerceScalar(nested[2])
      } else if (listKey) {
        // 列表项下的嵌套映射（如 sla: {freshness: PT24H} 的展开写法）
        const last = (root[listKey] as Record<string, unknown>[]).at(-1)
        if (last) last[nested[1]] = nested[2] === '' ? null : coerceScalar(nested[2])
      }
    }
  }
  closeItem()
  return JSON.stringify(root)
}

function coerceScalar(value: string): unknown {
  const trimmed = value.trim()
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) return Number(trimmed)
  if (trimmed === 'true') return true
  if (trimmed === 'false') return false
  if (trimmed.startsWith('[')) {
    return trimmed
      .slice(1, -1)
      .split(',')
      .map((item) => item.trim().replace(/^["']|["']$/g, ''))
      .filter((item) => item !== '')
  }
  return trimmed.replace(/^["']|["']$/g, '')
}

const SAMPLE_CONTRACT = `apiVersion: v3.0.2
kind: DataContract
id: event_log_contract
version: 1.0.0
status: ACTIVE
dataset: urn:dg:Dataset:java_e2e.postgresql.dg.public.event_log
compatibility: BACKWARD
primaryKey: [seq]
schema:
  - {name: seq, type: bigint, required: true}
  - {name: event_type, type: text, required: true}
  - {name: urn, type: text, required: true}
  - {name: created_at, type: timestamp with time zone, required: true}
quality:
  - {type: notNull, column: event_type}
  - {type: uniqueness, columns: [seq], threshold: 0.999}
sla: {freshness: PT24H}
connection: {dsn: env:DG_SOURCE_DSN}`
