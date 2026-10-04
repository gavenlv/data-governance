/**
 * API 客户端。
 *
 * 纪律（docs/09 §9.3）：界面**不做专用后门**，所有数据都通过与控制面同一套 REST API 获取，
 * 并携带调用者令牌 —— 这样界面上看不到的东西，接口同样拿不到。
 */

export type CapabilityStatus = 'IMPLEMENTED' | 'PARTIAL' | 'NOT_IMPLEMENTED'

export interface CapabilityDescriptor {
  id: string
  name: string
  domain: string
  status: CapabilityStatus
  doc: string
  phase: string
  summary: string
  notes: string[]
}

export interface CapabilityReport {
  controlPlane: { language: string; note: string }
  summary: { implemented: number; partial: number; notImplemented: number; total: number }
  domains: Record<string, CapabilityDescriptor[]>
  statusLegend: Record<string, string>
}

export interface Principal {
  authenticated: boolean
  id?: string
  name?: string
  roles?: string[]
  authMethod?: string
}

const TOKEN_KEY = 'dg_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? 'dev-admin-token'
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 请求失败时保留 HTTP 状态码与后端给的说明（未实现能力会返回 501 + 设计说明）。 */
export class ApiError extends Error {
  readonly status: number
  readonly payload: unknown

  constructor(status: number, message: string, payload: unknown) {
    super(message)
    this.status = status
    this.payload = payload
  }

  /** 该能力尚未实现（控制面显式声明，而不是返回空数据）。 */
  get notImplemented(): boolean {
    return this.status === 501
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`,
      ...(init?.headers ?? {}),
    },
  })

  const text = await response.text()
  let payload: unknown = text
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    /* 保持纯文本 */
  }

  if (!response.ok) {
    const detail =
      payload && typeof payload === 'object'
        ? ((payload as Record<string, unknown>).message ??
            (payload as Record<string, unknown>).detail ??
            JSON.stringify(payload))
        : String(payload ?? '')
    throw new ApiError(response.status, `${response.status} ${detail}`, payload)
  }
  return payload as T
}

export const api = {
  health: () => request<{ status: string; controlPlane: string }>('/healthz'),
  me: () => request<Principal>('/api/v1/me'),
  capabilities: () => request<CapabilityReport>('/api/v1/capabilities'),
  model: () => request<Record<string, unknown>>('/api/v1/model'),

  assets: (params: { prefix?: string; entityType?: string; limit?: number }) => {
    const query = new URLSearchParams()
    if (params.prefix) query.set('prefix', params.prefix)
    if (params.entityType) query.set('entityType', params.entityType)
    query.set('limit', String(params.limit ?? 100))
    return request<{ count: number; assets: AssetRow[] }>(`/api/v1/assets?${query}`)
  },
  asset: (urn: string) => request<AssetDetail>(`/api/v1/assets/${encodeURIComponent(urn)}`),
  assetHistory: (urn: string, aspectType: string) =>
    request<{ count: number; history: unknown[] }>(
      `/api/v1/assets/${encodeURIComponent(urn)}/aspects/${aspectType}/history`,
    ),

  search: (params: { q: string; type?: string; platform?: string; limit?: number }) => {
    const query = new URLSearchParams()
    query.set('q', params.q)
    if (params.type) query.set('type', params.type)
    if (params.platform) query.set('platform', params.platform)
    query.set('limit', String(params.limit ?? 20))
    return request<SearchResponse>(`/api/v1/search?${query}`)
  },

  indexLag: () => request<IndexLag>('/api/v1/index/lag'),
  indexConsume: (batchSize = 500) =>
    request<{ stats: Record<string, number>; lag: IndexLag }>(
      `/api/v1/index/consume?batchSize=${batchSize}`,
      { method: 'POST' },
    ),
  indexRebuild: (batchSize = 500) =>
    request<{ stats: Record<string, number>; lag: IndexLag }>(
      `/api/v1/index/rebuild?batchSize=${batchSize}`,
      { method: 'POST' },
    ),

  lineageGraph: (urn: string, direction: 'upstream' | 'downstream', depth = 3) =>
    request<LineageGraph>(
      `/api/v1/lineage/graph?urn=${encodeURIComponent(urn)}&direction=${direction}&depth=${depth}`,
    ),
  /** 血缘子图：带属性的节点与边（界面据此"线型 = 可信度"），裁剪由服务端做且显式回报。 */
  lineageSubgraph: (params: {
    urn: string
    direction: 'upstream' | 'downstream'
    depth?: number
    minConfidence?: number
    includeColumns?: boolean
    includeControl?: boolean
    nodeLimit?: number
  }) => {
    const query = new URLSearchParams()
    query.set('urn', params.urn)
    query.set('direction', params.direction)
    query.set('depth', String(params.depth ?? 3))
    query.set('minConfidence', String(params.minConfidence ?? 0))
    query.set('includeColumns', String(params.includeColumns ?? false))
    query.set('includeControl', String(params.includeControl ?? false))
    if (params.nodeLimit) query.set('nodeLimit', String(params.nodeLimit))
    return request<LineageSubgraph>(`/api/v1/lineage/subgraph?${query}`)
  },
  lineageConfirmEdge: (edgeId: number) =>
    request<Record<string, unknown>>(`/api/v1/lineage/edges/${edgeId}/confirm`, { method: 'POST' }),
  lineageRejectEdge: (edgeId: number, reason: string) =>
    request<Record<string, unknown>>(`/api/v1/lineage/edges/${edgeId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  lineageQuality: () => request<Record<string, unknown>>('/api/v1/lineage/quality'),
  lineageSidecar: () => request<Record<string, unknown>>('/api/v1/lineage/parse/sidecar'),
  lineageParse: (body: {
    sql: string
    dialect: string
    namespace: string
    dryRun?: boolean
    recordSamples?: boolean
  }) => request<ParseResult>('/api/v1/lineage/parse', { method: 'POST', body: JSON.stringify(body) }),
  impact: (urn: string, direction: 'upstream' | 'downstream', depth = 3, includeColumns = false) =>
    request<ImpactReport>(
      `/api/v1/lineage/impact/${encodeURIComponent(urn)}?direction=${direction}` +
        `&depth=${depth}&includeColumns=${includeColumns}`,
    ),

  collectRuns: (limit = 20) =>
    request<{ count: number; runs: CollectRun[] }>(`/api/v1/collect/runs?limit=${limit}`),
  collectHealth: () => request<CollectHealth>('/api/v1/collect/health'),
  collectSources: () =>
    request<{
      implemented: ConnectorInfo[]
      notImplemented: Record<string, { note: string }>
      note: string
    }>('/api/v1/collect/sources'),
  collectPostgres: (body: Record<string, unknown>) =>
    request<CollectRun>('/api/v1/collect/postgres', { method: 'POST', body: JSON.stringify(body) }),
  /** 通用采集入口：连接器由控制面的注册表决定，界面不维护"支持哪些源"的清单。 */
  collectRun: (body: Record<string, unknown>) =>
    request<CollectRun>('/api/v1/collect/run', { method: 'POST', body: JSON.stringify(body) }),
  schedules: () => request<ScheduleList>('/api/v1/schedules'),
  applySchedules: (document: { schedules: Record<string, unknown>[] }) =>
    request<ScheduleApplyResult>('/api/v1/schedules', {
      method: 'POST',
      body: JSON.stringify(document),
    }),
  applySchedulesFile: (path: string) =>
    request<ScheduleApplyResult>(`/api/v1/schedules/apply-file?path=${encodeURIComponent(path)}`, {
      method: 'POST',
    }),
  runSchedule: (name: string) =>
    request<Record<string, unknown>>(`/api/v1/schedules/${encodeURIComponent(name)}/run`, {
      method: 'POST',
    }),
  alerts: () => request<unknown>('/api/v1/collect/alerts'),

  // ---------------------------------------------------------------- 质量（Batch 2）
  qualityOverview: () => request<QualityOverview>('/api/v1/quality/overview'),
  qualityProfile: (body: {
    jdbcUrl: string
    username?: string
    password?: string
    datasetUrns: string[]
    samplePercent?: number
    includeTopK?: boolean
  }) => request<ProfileResult>('/api/v1/quality/profile', { method: 'POST', body: JSON.stringify(body) }),
  qualityProfiles: (urn: string) =>
    request<ProfileReadback>(`/api/v1/quality/profiles/${encodeURIComponent(urn)}`),
  qualityRules: () => request<{ count: number; rules: QualityRuleRow[] }>('/api/v1/quality/rules'),
  qualityCompile: (body: {
    frontend: 'yaml' | 'sql_assertion' | 'dbt_test'
    document?: Record<string, unknown>
    datasetUrn?: string
    ruleId?: string
    sql?: string
    expect?: number
    severity?: string
    dimension?: string
    modelName?: string
    tests?: Record<string, unknown>[]
  }) =>
    request<{ rules: CompiledRulePreview[]; count: number; note: string }>('/api/v1/quality/rules/compile', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  qualityRegisterRules: (body: Record<string, unknown>) =>
    request<{ registered: Record<string, unknown>[]; rejected: { ruleId?: string; error: string }[]; note: string }>(
      '/api/v1/quality/rules',
      { method: 'POST', body: JSON.stringify(body) },
    ),
  qualityRunRule: (urn: string) =>
    request<RuleRunResult>(`/api/v1/quality/rules/${encodeURIComponent(urn)}/run`, { method: 'POST' }),
  qualityRuns: (limit = 50) =>
    request<{ count: number; runs: RuleRunRow[] }>(`/api/v1/quality/runs?limit=${limit}`),

  // -------------------------------------------------------------- 契约（Batch 2）
  contracts: () => request<{ count: number; contracts: ContractRow[] }>('/api/v1/contracts'),
  contract: (urn: string) => request<ContractDetail>(`/api/v1/contracts/${encodeURIComponent(urn)}`),
  contractRegister: (body: {
    document: Record<string, unknown>
    namespace?: string
    allowBreaking?: boolean
    breakingJustification?: string
  }) => request<ContractRegisterResult>('/api/v1/contracts', { method: 'POST', body: JSON.stringify(body) }),
  contractVersions: (urn: string) =>
    request<{ count: number; versions: ContractVersionRow[]; note: string }>(
      `/api/v1/contracts/${encodeURIComponent(urn)}/versions`,
    ),
  contractDiff: (urn: string, from: number, to: number) =>
    request<ContractDiffResult>(
      `/api/v1/contracts/${encodeURIComponent(urn)}/diff?from=${from}&to=${to}`,
    ),
  contractValidate: (urn: string) =>
    request<ContractValidateResult>(`/api/v1/contracts/${encodeURIComponent(urn)}/validate`, { method: 'POST' }),
  contractViolations: (params: { status?: string; severity?: string }) => {
    const query = new URLSearchParams()
    if (params.status) query.set('status', params.status)
    if (params.severity) query.set('severity', params.severity)
    return request<{ count: number; violations: ContractViolationRow[] }>(
      `/api/v1/contracts/violations?${query}`,
    )
  },
  contractViolationOverview: () => request<Record<string, unknown>>('/api/v1/contracts/violations/overview'),
  contractAckViolation: (id: number) =>
    request<Record<string, unknown>>(`/api/v1/contracts/violations/${id}/ack`, { method: 'POST' }),
  contractExemptViolation: (id: number, until: string, reason: string) =>
    request<Record<string, unknown>>(`/api/v1/contracts/violations/${id}/exempt`, {
      method: 'POST',
      body: JSON.stringify({ until, reason }),
    }),
  contractConsumers: (urn: string) =>
    request<ContractConsumers>(`/api/v1/contracts/${encodeURIComponent(urn)}/consumers`),
  contractAddConsumer: (urn: string, consumerUrn: string, note?: string) =>
    request<Record<string, unknown>>(`/api/v1/contracts/${encodeURIComponent(urn)}/consumers`, {
      method: 'POST',
      body: JSON.stringify({ consumerUrn, note }),
    }),
  contractCiCheck: (body: {
    document: Record<string, unknown>
    namespace?: string
    allowBreaking?: boolean
    source?: string
    policy?: Record<string, unknown>
  }) => request<CiGateResult>('/api/v1/contracts/ci-check', { method: 'POST', body: JSON.stringify(body) }),
  contractCiHistory: (urn?: string) =>
    request<{ count: number; checks: CiHistoryRow[] }>(
      `/api/v1/contracts/ci-history${urn ? `?urn=${encodeURIComponent(urn)}` : ''}`,
    ),

  // ---------------------------------------------------------- 访问治理（Batch 4）
  accessOverview: () => request<Record<string, unknown>>('/api/v1/access/overview'),
  accessRequests: (status?: string) =>
    request<{ count: number; requests: AccessRequestRow[] }>(
      `/api/v1/access/requests${status ? `?status=${status}` : ''}`,
    ),
  accessSubmit: (body: {
    resourceUrn: string
    columnName?: string
    granularity: 'DATASET' | 'COLUMN'
    permissions: string[]
    purpose: string
    durationDays: number
  }) => request<Record<string, unknown>>('/api/v1/access/requests', { method: 'POST', body: JSON.stringify(body) }),
  accessDecide: (id: number, decision: 'APPROVED' | 'REJECTED', note?: string) =>
    request<Record<string, unknown>>(`/api/v1/access/requests/${id}/decide`, {
      method: 'POST',
      body: JSON.stringify({ decision, note }),
    }),
  accessGrants: (status?: string) =>
    request<{ count: number; grants: AccessGrantRow[] }>(
      `/api/v1/access/grants${status ? `?status=${status}` : ''}`,
    ),
  accessRevoke: (id: number, reason: string) =>
    request<Record<string, unknown>>(`/api/v1/access/grants/${id}/revoke`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  accessExpireSweep: () => request<Record<string, unknown>>('/api/v1/access/grants/expire-sweep', { method: 'POST' }),
  accessReviewCampaign: (campaign: string) =>
    request<{ campaign: string; count: number; items: AccessReviewItem[]; suggestionLegend: Record<string, string> }>(
      `/api/v1/access/reviews/${encodeURIComponent(campaign)}`,
    ),
  accessReviewDecide: (campaign: string, grantId: number, decision: string, reason?: string) =>
    request<Record<string, unknown>>(`/api/v1/access/reviews/${encodeURIComponent(campaign)}/decide`, {
      method: 'POST',
      body: JSON.stringify({ grantId, decision, reason }),
    }),
  accessEvents: (params: { subject?: string; resourceUrn?: string; limit?: number }) => {
    const query = new URLSearchParams()
    if (params.subject) query.set('subject', params.subject)
    if (params.resourceUrn) query.set('resourceUrn', params.resourceUrn)
    query.set('limit', String(params.limit ?? 200))
    return request<{ count: number; events: AccessEventRow[] }>(`/api/v1/access/events?${query}`)
  },
  accessAuditReport: (days = 90) => request<Record<string, unknown>>(`/api/v1/access/audit-report?days=${days}`),
  accessLeastPrivilege: () => request<Record<string, unknown>>('/api/v1/access/least-privilege'),

  // -------------------------------------------------------------- 策略（Batch 4）
  policies: () =>
    request<{ count: number; policies: PolicyRow[]; targets: { id: string; note: string }[] }>('/api/v1/policies'),
  policyPreview: (document: Record<string, unknown>) =>
    request<{ target: string; effect: string; artifact: string; metadata: Record<string, unknown>; errors: string[]; note: string }>(
      '/api/v1/policies/compile-preview',
      { method: 'POST', body: JSON.stringify(document) },
    ),
  policyUpsert: (document: Record<string, unknown>) =>
    request<Record<string, unknown>>('/api/v1/policies', { method: 'POST', body: JSON.stringify(document) }),
  policyCompile: (target?: string) =>
    request<{ compiled: Record<string, unknown>[]; failed: Record<string, unknown>[]; count: number; note: string }>(
      `/api/v1/policies/compile${target ? `?target=${target}` : ''}`,
      { method: 'POST' },
    ),
  policyDeploy: (target: string) =>
    request<Record<string, unknown>>(`/api/v1/policies/deploy?target=${target}`, { method: 'POST' }),
  policyDeployments: () =>
    request<{ count: number; deployments: PolicyDeploymentRow[] }>('/api/v1/policies/deployments'),
  policyRollback: (id: number, reason: string) =>
    request<Record<string, unknown>>(`/api/v1/policies/deployments/${id}/rollback`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  policyCoverage: () => request<PolicyCoverage>('/api/v1/policies/coverage', { method: 'POST' }),
  policyCoverageHistory: () =>
    request<{ count: number; history: Record<string, unknown>[] }>('/api/v1/policies/coverage'),
}

export interface AssetRow {
  urn: string
  entity_type: string
  namespace: string
  display_name: string | null
  lifecycle: string
  updated_at: string
}

export interface AssetDetail {
  urn: string
  entityType: string
  namespace: string
  displayName: string | null
  lifecycle: string
  createdAt: string
  updatedAt: string
  aspects: Record<string, Record<string, unknown>>
}

export interface LineageGraph {
  urn: string
  direction: string
  maxDepth: number
  nodes: { urn: string; depth: number }[]
}

/** 血缘子图节点（含分级/Owner/平台，用于着色与详情面板）。 */
export interface LineageNode {
  urn: string
  entityType: string
  displayName?: string
  namespace?: string
  platform?: string
  container?: string
  lifecycle?: string
  classification?: string
  owners?: string[]
  depth: number
  focus?: boolean
}

/** 血缘子图边：来源/置信度/解析级别/转换/时效 —— 界面据此画线型与宽度。 */
export interface LineageEdge {
  edgeId: number
  id: string
  from: string
  to: string
  edgeType: string
  source: string
  confidence: number
  transform?: string
  transformExpression?: string
  cardinality?: string
  dependencyKind: string
  parseLevel?: string
  state: string
  observedCount?: number
  lastSeen?: string
  confirmedBy?: string
  confirmedAt?: string
}

export interface LineageSubgraph {
  urn: string
  direction: string
  maxDepth: number
  minConfidence: number
  includeColumns: boolean
  includeControl: boolean
  nodes: LineageNode[]
  edges: LineageEdge[]
  counts: {
    nodes: number
    edges: number
    nodesByType: Record<string, number>
    edgesBySource: Record<string, number>
    reachableBeforeLimit: number
  }
  nodeLimit: number
  nodeLimitReached: boolean
  truncated: boolean
  boundaryNodes: string[]
  notes: string[]
}

export interface CollectRun {
  run_id?: string
  runId?: string
  source: string
  namespace: string
  status: string
  block_reason?: string | null
  blockReason?: string | null
  datasets_seen?: number
  datasetsSeen?: number
  datasets_created?: number
  datasetsCreated?: number
  dashboards_seen?: number
  dashboardsSeen?: number
  dashboards_created?: number
  dashboardsCreated?: number
  dashboards_deleted?: number
  dashboardsDeleted?: number
  dashboard_guard_blocked?: boolean
  dashboard_guard_reason?: string | null
  columns_seen?: number
  columnsSeen?: number
  deleted?: number
  duration_ms?: number
  durationMs?: number
  errors?: string[]
  started_at?: string
  startedAt?: string
}

/** 连接器信息（服务端单点声明；verifiedAgainstRealSystem 区分"实现了"与"对真实系统验证过"）。 */
export interface ConnectorInfo {
  id: string
  displayName: string
  assetKind: string
  verifiedAgainstRealSystem: boolean
  note: string
  dsnExample: string
}

export interface CollectHealth {
  health: string
  sources: {
    source: string
    namespace: string
    scope: string
    entity_count: number
    last_status: string
    consecutive_failures: number
    last_success_at: string | null
  }[]
}

/** 检索结果项（来自派生视图 search_doc，字段已按可见范围前置过滤）。 */
export interface SearchHit {
  urn: string
  entityType: string
  displayName: string | null
  namespace: string | null
  platform: string | null
  container: string | null
  description: string | null
  tags: string[]
  owners: string[]
  classification: string | null
  indexedAt: string
  indexedWatermark: number
}

export interface Facet {
  key: string | null
  count: number
}

export interface IndexLag {
  consumer: string
  lastConsumedSeq: number
  latestEventSeq: number
  lag: number
  indexedDocs: number
  status: string
}

export interface SearchResponse {
  query: string
  count: number
  results: SearchHit[]
  facets: { entityType: Facet[]; platform: Facet[] }
  indexLag: number
  index: IndexLag
  visibleLevels: string[]
  suggestions?: string[]
  note?: string
}

/** 影响分析节点：按 score 排序，score = w(v)·α^depth。 */
export interface ImpactNode {
  urn: string
  displayName: string | null
  entityType: string
  depth: number
  score: number
  weight: number
  classification: string | null
  owners: string[]
  tags: string[]
  downstreamCount: number
  reasons: string[]
}

export interface ImpactReport {
  urn: string
  direction: string
  maxDepth: number
  affectedCount: number
  criticalCount: number
  byEntityType: Record<string, number>
  reachedMaxDepth: boolean
  boundaryNodes: string[]
  truncationNote?: string | null
  edgesInClosure: number
  nodes: ImpactNode[]
  scoring?: { formula: string; wComponents: string[]; excluded: string }
  caveat?: string
}

/** SQL 解析入库结果。 */
export interface ParseResult {
  dialect: string
  sqlglotVersion: string
  statements: number
  failed: number
  downgraded: number
  tableEdges: number
  columnEdges: number
  samplesRecorded: number
  unresolvedTables: string[]
  unresolvedNote?: string | null
  dryRun: boolean
  note?: string
}

export interface ScheduleRow {
  name: string
  source: string
  dsn: string
  namespace: string
  database: string | null
  schemas: string[]
  tables: string[]
  cron: string
  enabled: boolean
  guard: Record<string, unknown>
  timezone: string
  lastRunId: string | null
  lastRunAt: string | null
  lastStatus: string | null
  nextRunAt: string | null
}

export interface ScheduleList {
  count: number
  schedules: ScheduleRow[]
  scheduler: { mode: string; mutex: string; note: string }
}

export interface ScheduleApplyResult {
  applied: Record<string, unknown>[]
  rejected: { name?: string; error: string }[]
  note: string
  source?: string
}

// ---------------------------------------------------------------------- 质量

export interface QualityOverview {
  rules: number
  enabledRules: number
  byLastStatus: { status: string; count: number }[]
  recentRuns: { status: string; count: number }[]
  recentFailures: {
    run_id: string
    rule_urn: string
    observed: number | null
    expected: string | null
    error: string | null
    started_at: string
  }[]
  note: string
}

export interface ProfileColumnResult {
  name: string
  type: string
  classification: string | null
  sensitive: boolean
  metrics: Record<string, number>
  minValue?: string | null
  maxValue?: string | null
  topK?: { value: string; count: number }[]
  valueSuppressed?: string
  precision: 'EXACT' | 'ESTIMATED'
}

export interface ProfileResult {
  datasets: {
    urn: string
    table: string
    sampling: { method: string; ratio: number; precision: string; note: string }
    rowCount: number
    rowCountEstimate?: number
    rowCountEstimateSource?: string
    columns: ProfileColumnResult[]
    columnCount: number
  }[]
  samplePercent: number
  windowStart: string
  durationMs: number
  precisionNote: string
}

export interface ProfileReadback {
  urn: string
  columns: Record<string, Record<string, unknown>[]>
  columnCount: number
  trend: Record<string, unknown>[]
  precisionLegend: Record<string, string>
}

export interface QualityRuleRow {
  urn: string
  ruleId: string | null
  dataset: string | null
  metric: string | null
  operator: string | null
  threshold: number | null
  column: string | null
  severity: string | null
  dimension: string | null
  onFail: string | null
  sourceFrontend: string | null
  dsn: string
  cron: string
  timezone: string
  enabled: boolean
  nextRunAt: string | null
  lastRunAt: string | null
  lastStatus: string | null
}

export interface CompiledRulePreview {
  ruleId: string
  dataset: string
  metric: string
  operator: string
  severity: string
  dimension: string
  expected: string
  sourceFrontend: string
  engineHints: Record<string, unknown>
  sql?: string
  needsBaseline?: boolean
  compileError?: string
}

export interface RuleRunResult {
  runId: string
  rule: string
  status: 'PASS' | 'FAIL' | 'ERROR' | 'SKIPPED'
  observed: number | null
  expected: string | null
  compiledSql: string | null
  error: string | null
  baseline: number | null
  durationMs: number
  note?: string
}

export interface RuleRunRow {
  run_id: string
  rule_urn: string
  status: string
  observed: number | null
  expected: string | null
  metric: string | null
  severity: string | null
  duration_ms: number | null
  error: string | null
  started_at: string
  executed_by: string | null
}

// ---------------------------------------------------------------------- 契约

export interface ContractRow {
  urn: string
  id: string
  dataset: string | null
  contractVersion: string
  apiVersion: string
  status: string
  compatibility: string
  aspectVersion: number
  updatedAt: string
  fieldCount: number
  checkCount: number
  openViolations: number
  consumers: number
}

export interface ContractDetail {
  urn: string
  id: string
  spec: Record<string, unknown>
  dataset: string | null
  fieldCount: number
}

export interface ContractVersionRow {
  version: number
  updated_by: string | null
  updated_at: string
  contract_version: string
  status: string
  field_count: number
  is_current: boolean
}

export interface ContractChange {
  kind: string
  column: string | null
  severity: string
  message: string
  before: unknown
  after: unknown
}

export interface ContractDiffResult {
  verdict: string
  breaking: boolean
  requiredVersionBump: string
  versionBumpSufficient: boolean
  notes: string[]
  changes: ContractChange[]
  urn?: string
  fromVersion?: number
  toVersion?: number
}

export interface ContractRegisterResult {
  contract?: string
  version?: string
  previousVersion?: string | null
  dataset?: string
  registered?: boolean
  rejected?: string
  dryRun?: boolean
  generatedRules?: number
  ruleRegistration?: Record<string, unknown>
  breakingJustification?: string
  warning?: string
  diff?: ContractDiffResult
}

export interface ContractViolationRow {
  id: number
  contract_urn: string
  dataset_urn: string | null
  kind: string
  severity: string
  status: string
  detail: Record<string, unknown>
  first_seen: string
  last_seen: string
  occurrences: number
  exempted_until: string | null
  exempt_reason: string | null
}

export interface ContractValidateResult {
  contract: string
  dataset: string
  promisedFields: number
  actualFields: number
  violations: Record<string, unknown>[]
  violationCount: number
  note?: string
}

export interface ContractConsumers {
  contract: string
  dataset: string | null
  registered: string[]
  external: string[]
  undetected: string[]
  staleRegistrations: string[]
  note: string
}

export interface CiGateResult {
  contract: string
  dataset: string
  fromVersion: string | null
  toVersion: string
  verdict: 'PASS' | 'WARN' | 'BLOCK'
  exitCode: number
  blocking: string[]
  warnings: string[]
  passed: string[]
  diff: ContractDiffResult
  impact: { downstreamCount: number; criticalCount: number; maxDepth: number; downstream: string[] }
  governance: { hasOwner: boolean; hasDescription: boolean; hasClassification: boolean; missing: string[] }
  consumers?: { registered: string[]; undetected: string[]; staleRegistrations: string[] }
  policy: Record<string, unknown>
}

export interface CiHistoryRow {
  id: number
  contract_urn: string
  requested_version: string
  verdict: string
  requested_by: string | null
  source: string | null
  created_at: string
  blocking: string[] | null
}

// ------------------------------------------------------------------ 访问治理

export interface AccessRequestRow {
  id: number
  requester: string
  resource_urn: string
  column_name: string | null
  granularity: 'DATASET' | 'COLUMN'
  permissions: string[]
  purpose: string
  duration_days: number
  classification: string | null
  status: string
  route: string | null
  approvers: string[]
  sla_due_at: string | null
  overdue: boolean
  decided_by: string | null
  decided_at: string | null
  decision_note: string | null
  granularity_suggestion: string | null
  created_at: string
}

export interface AccessGrantRow {
  id: number
  subject: string
  resource_urn: string
  column_name: string | null
  granularity: string
  permissions: string[]
  purpose: string | null
  granted_by: string
  granted_at: string
  expires_at: string
  status: string
  age_days: number
  days_to_expiry: number
  last_reviewed_at: string | null
}

export interface AccessReviewItem extends AccessGrantRow {
  suggestedDecision: 'KEEP' | 'REVOKE' | 'NEED_MORE_INFO'
  suggestionReason: string
  evidence: Record<string, unknown>
}

export interface AccessEventRow {
  id: number
  subject: string
  action: string
  resource_urn: string | null
  decision: string | null
  reason: string | null
  detail: Record<string, unknown>
  source: string
  occurred_at: string
}

// ---------------------------------------------------------------------- 策略

export interface PolicyRow {
  name: string
  description: string | null
  target: string
  effect: string
  resource_scope: Record<string, unknown>
  subject_scope: Record<string, unknown>
  condition: Record<string, unknown>
  priority: number
  status: string
  version: number
  updated_at: string
}

export interface PolicyDeploymentRow {
  id: number
  target: string
  bundle_hash: string
  status: string
  dispatch_mode: string
  artifact_count: number
  deployed_by: string | null
  created_at: string
  applied_at: string | null
  rolled_back_at: string | null
  detail: Record<string, unknown>
}

export interface PolicyCoverage {
  scope: string
  totalAssets: number
  coveredAssets: number
  coverageRatio: number
  uncoveredHighClassification: { urn: string; displayName: string; classification: string; risk: string }[]
  knownBlindSpots: string[]
  note: string
}
