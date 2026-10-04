import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Descriptions, Empty, Input, Space, Tag, Tooltip, Typography, message } from 'antd'
import cytoscape, { type Core, type ElementDefinition } from 'cytoscape'
import dagre from 'cytoscape-dagre'
import { api, type LineageEdge, type LineageNode, type LineageSubgraph } from '../api/client'

const { Text } = Typography

// Cytoscape 只注册一次（重复注册会报警告）
if (!(cytoscape as unknown as { __dagreRegistered?: boolean }).__dagreRegistered) {
  cytoscape.use(dagre)
  ;(cytoscape as unknown as { __dagreRegistered?: boolean }).__dagreRegistered = true
}

/**
 * 血缘画布（docs/14 §3.3 血缘探索器）。
 *
 * 三条设计要点，全部来自设计文档：
 *  1. **线型 = 可信度**：实线 = 运行时/人工，虚线 = 静态解析，点线 = 推断，灰线 = 过期；
 *     线宽随置信度变化。用户必须能一眼看出"这条边有多可信"，而不是靠点开看属性。
 *  2. **裁剪必须可见**：节点数达到上限或深度截断时，画布上方显式提示，
 *     绝不能让使用者以为"血缘就这么点"。
 *  3. **每条边可确认/驳回**：确认会提升置信度（喂养置信度模型），驳回是标记而不是删除
 *     （保留原因才能解释这条边为什么消失）。
 */

/** 平台/类型配色：数据集偏蓝、报表偏紫、管道偏青、列偏灰，一眼区分资产类别。 */
const TYPE_COLORS: Record<string, string> = {
  Dataset: '#1677ff',
  Column: '#8c8c8c',
  Dashboard: '#722ed1',
  Pipeline: '#13c2c2',
  Platform: '#fa8c16',
  Container: '#a0d911',
  DataContract: '#eb2f96',
  QualityRule: '#faad14',
}

/** 边的线型按来源：运行时上报/人工 = 实线；静态解析 = 虚线；推断 = 点线。 */
function edgeLineStyle(source: string): string {
  switch (source) {
    case 'openlineage':
    case 'manual':
      return 'solid'
    case 'sql_parse':
    case 'query_log':
    case 'code_static':
      return 'dashed'
    case 'inferred_ai':
      return 'dotted'
    default:
      return 'dashed'
  }
}

function sourceLabel(source: string): string {
  return (
    {
      openlineage: '运行时上报',
      manual: '人工登记',
      sql_parse: 'SQL 静态解析',
      query_log: '查询日志',
      code_static: '代码静态分析',
      inferred_ai: 'AI 推断',
      contract: '契约',
    }[source] ?? source
  )
}

export default function LineageCanvas({
  urn,
  direction,
  depth,
  minConfidence,
  includeColumns,
  includeControl,
  onRefocus,
}: {
  urn: string
  direction: 'upstream' | 'downstream'
  depth: number
  minConfidence: number
  includeColumns: boolean
  includeControl: boolean
  onRefocus: (urn: string) => void
}) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const cyRef = useRef<Core | null>(null)
  const [graph, setGraph] = useState<LineageSubgraph | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedNode, setSelectedNode] = useState<LineageNode | null>(null)
  const [selectedEdge, setSelectedEdge] = useState<LineageEdge | null>(null)

  // 拉取子图
  useEffect(() => {
    if (!urn) {
      setGraph(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .lineageSubgraph({ urn, direction, depth, minConfidence, includeColumns, includeControl })
      .then((data) => {
        if (!cancelled) {
          setGraph(data)
          setSelectedNode(data.nodes.find((node) => node.urn === urn) ?? null)
          setSelectedEdge(null)
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [urn, direction, depth, minConfidence, includeColumns, includeControl])

  const elements = useMemo<ElementDefinition[]>(() => {
    if (!graph) return []
    const nodes: ElementDefinition[] = graph.nodes.map((node) => ({
      data: {
        id: node.urn,
        label: node.displayName || node.urn.split(':').pop() || node.urn,
        entityType: node.entityType,
        color: TYPE_COLORS[node.entityType] ?? '#595959',
        depth: node.depth,
        focus: node.focus ? 1 : 0,
        classification: node.classification ?? '',
      },
    }))
    const edges: ElementDefinition[] = graph.edges.map((edge) => ({
      data: {
        id: edge.id,
        edgeId: edge.edgeId,
        source: edge.from,
        target: edge.to,
        lineStyle: edgeLineStyle(edge.source),
        width: 1 + Math.max(0, Math.min(1, edge.confidence)) * 3,
        label: edge.transform ? `${edge.transform}` : '',
        stale: edge.state === 'STALE' ? 1 : 0,
        sourceKind: edge.source,
        confidence: edge.confidence,
      },
    }))
    return [...nodes, ...edges]
  }, [graph])

  // 渲染画布
  useEffect(() => {
    if (!containerRef.current) return
    if (cyRef.current) {
      cyRef.current.destroy()
      cyRef.current = null
    }
    if (elements.length === 0) return

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      wheelSensitivity: 0.2,
      style: [
        {
          selector: 'node',
          style: {
            'background-color': 'data(color)',
            label: 'data(label)',
            color: '#1f1f1f',
            'font-size': 11,
            'text-valign': 'bottom',
            'text-margin-y': 4,
            'text-max-width': '120px',
            'text-wrap': 'ellipsis',
            width: 26,
            height: 26,
            'border-width': 2,
            'border-color': '#ffffff',
          },
        },
        {
          selector: 'node[focus = 1]',
          style: { 'border-width': 4, 'border-color': '#fa541c', width: 34, height: 34 },
        },
        {
          selector: 'node[classification = "L4"]',
          style: { 'border-color': '#cf1322', 'border-width': 3 },
        },
        { selector: 'node[classification = "L3"]', style: { 'border-color': '#d46b08', 'border-width': 3 } },
        { selector: 'node:selected', style: { 'border-color': '#000000', 'border-width': 4 } },
        {
          selector: 'edge',
          style: {
            width: 'data(width)',
            // Cytoscape 的类型定义把 line-style 收窄成字面量，用映射函数（cytoscape 支持 data() 映射）
            'line-style': ((ele: cytoscape.EdgeSingular) =>
              ele.data('lineStyle') as 'solid' | 'dotted' | 'dashed') as unknown as 'solid',
            'line-color': '#8c8c8c',
            'target-arrow-color': '#8c8c8c',
            'target-arrow-shape': 'triangle',
            'arrow-scale': 0.8,
            'curve-style': 'bezier',
            label: 'data(label)',
            'font-size': 9,
            color: '#8c8c8c',
            'text-rotation': 'autorotate',
            'text-background-color': '#ffffff',
            'text-background-opacity': 0.85,
          },
        },
        { selector: 'edge[stale = 1]', style: { 'line-color': '#bfbfbf', 'target-arrow-color': '#bfbfbf' } },
        { selector: 'edge:selected', style: { 'line-color': '#fa541c', 'target-arrow-color': '#fa541c', width: 4 } },
        { selector: '.path-highlight', style: { 'line-color': '#fa541c', 'target-arrow-color': '#fa541c' } },
        { selector: '.dimmed', style: { opacity: 0.25 } },
      ],
      layout: {
        name: 'dagre',
        rankDir: direction === 'downstream' ? 'LR' : 'RL',
        nodeSep: 36,
        rankSep: 90,
        animate: false,
      } as cytoscape.LayoutOptions,
    })

    cy.on('tap', 'node', (event) => {
      const id = event.target.id() as string
      const node = graph?.nodes.find((item) => item.urn === id) ?? null
      setSelectedNode(node)
      setSelectedEdge(null)
      highlightPath(cy, urn, id)
    })
    cy.on('dbltap', 'node', (event) => {
      const id = event.target.id() as string
      if (id !== urn) onRefocus(id)
    })
    cy.on('tap', 'edge', (event) => {
      const edgeId = event.target.data('edgeId') as number
      const edge = graph?.edges.find((item) => item.edgeId === edgeId) ?? null
      setSelectedEdge(edge)
      setSelectedNode(null)
    })
    cy.on('tap', (event) => {
      if (event.target === cy) {
        cy.elements().removeClass('dimmed path-highlight')
        setSelectedNode(null)
        setSelectedEdge(null)
      }
    })

    cyRef.current = cy
    return () => {
      cy.destroy()
      cyRef.current = null
    }
  }, [elements, direction, urn, onRefocus, graph])

  const confirmEdge = async (edge: LineageEdge) => {
    try {
      await api.lineageConfirmEdge(edge.edgeId)
      message.success('已确认：置信度提升到人工级（≥0.99）')
      onRefocus(urn) // 触发重取，避免界面与真相源不一致
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const rejectEdge = async (edge: LineageEdge) => {
    const reason = window.prompt('驳回原因（必填，会保留在边上以便解释它为什么消失）', '字段映射不正确')
    if (!reason) return
    try {
      await api.lineageRejectEdge(edge.edgeId, reason)
      message.success('已驳回：该边不再参与血缘遍历')
      onRefocus(urn)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  if (!urn) {
    return <Empty description="先指定一个资产 URN（或从资产页点「查看血缘」进入）" />
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message="血缘查询失败" description={error} />}
      {graph && graph.notes.length > 0 && (
        <Alert
          type={graph.nodeLimitReached || graph.truncated ? 'warning' : 'info'}
          showIcon
          message={
            <Space wrap size={8}>
              <span>
                节点 {graph.counts.nodes} · 边 {graph.counts.edges} · 深度 {graph.maxDepth}
              </span>
              {Object.entries(graph.counts.edgesBySource).map(([source, count]) => (
                <Tag key={source}>{sourceLabel(source)}：{count}</Tag>
              ))}
            </Space>
          }
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {graph.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          }
        />
      )}
      <div
        ref={containerRef}
        style={{
          width: '100%',
          height: 460,
          border: '1px solid #f0f0f0',
          borderRadius: 6,
          background: '#fafafa',
          position: 'relative',
        }}
      >
        {loading && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
            <Text type="secondary">加载血缘…</Text>
          </div>
        )}
        {!loading && graph && graph.counts.nodes <= 1 && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
            <Empty description="闭包为空：可能确实没有血缘，也可能是尚未采集/解析（见下方血缘质量）" />
          </div>
        )}
      </div>

      {/* 图例：线型 = 可信度必须写清楚，否则用户只会看到"一堆线" */}
      <Space wrap size={12}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          图例：
        </Text>
        <Tooltip title="运行时上报或人工登记：实线">
          <Tag>— 运行时/人工（≥0.95）</Tag>
        </Tooltip>
        <Tooltip title="SQL 静态解析：虚线">
          <Tag>┄ 静态解析（0.8）</Tag>
        </Tooltip>
        <Tooltip title="AI 推断：点线">
          <Tag>┈ 推断（需人工确认）</Tag>
        </Tooltip>
        <Tooltip title="解析级别：表级通常只有表级血缘，列级更精确">
          <Tag>线宽 ∝ 置信度</Tag>
        </Tooltip>
        {Object.entries(TYPE_COLORS).map(([type, color]) => (
          <Tag key={type} color={color}>
            {type}
          </Tag>
        ))}
        <Text type="secondary" style={{ fontSize: 12 }}>
          双击节点 = 以它为焦点重新探索；单击节点/边 = 看详情
        </Text>
      </Space>

      {selectedEdge && (
        <Card size="small" title="选中边" extra={<Tag color="orange">仅展示，不修改数据</Tag>}>
          <Descriptions column={2} size="small">
            <Descriptions.Item label="方向">
              <span className="dg-urn">{selectedEdge.from.split(':').pop()}</span> →{' '}
              <span className="dg-urn">{selectedEdge.to.split(':').pop()}</span>
            </Descriptions.Item>
            <Descriptions.Item label="来源 / 置信度">
              {sourceLabel(selectedEdge.source)} · {selectedEdge.confidence}
            </Descriptions.Item>
            <Descriptions.Item label="关系类型">{selectedEdge.edgeType}</Descriptions.Item>
            <Descriptions.Item label="解析级别">{selectedEdge.parseLevel ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="转换">
              {selectedEdge.transform ?? '—'}
              {selectedEdge.transformExpression ? (
                <span className="dg-mono"> {selectedEdge.transformExpression}</span>
              ) : null}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {selectedEdge.state}
              {selectedEdge.confirmedBy ? ` · 已由 ${selectedEdge.confirmedBy} 确认` : ''}
            </Descriptions.Item>
          </Descriptions>
          <Space>
            <Button size="small" onClick={() => confirmEdge(selectedEdge)}>
              确认此血缘
            </Button>
            <Button size="small" danger onClick={() => rejectEdge(selectedEdge)}>
              标记为错误
            </Button>
          </Space>
          <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
            确认会提升置信度并记名；驳回是**标记而不是删除**（保留原因，避免解析器下一轮又写回来）。
          </Text>
        </Card>
      )}

      {selectedNode && !selectedEdge && (
        <Card size="small" title="选中节点">
          <Descriptions column={2} size="small">
            <Descriptions.Item label="展示名">{selectedNode.displayName ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="类型">{selectedNode.entityType}</Descriptions.Item>
            <Descriptions.Item label="平台">{selectedNode.platform ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="分级">{selectedNode.classification ?? 'L2（默认）'}</Descriptions.Item>
            <Descriptions.Item label="Owner">
              {(selectedNode.owners ?? []).length > 0 ? (selectedNode.owners ?? []).join('、') : '无 Owner'}
            </Descriptions.Item>
            <Descriptions.Item label="跳数">{selectedNode.depth}</Descriptions.Item>
            <Descriptions.Item label="URN" span={2}>
              <span className="dg-urn">{selectedNode.urn}</span>
            </Descriptions.Item>
          </Descriptions>
          <Space>
            <Button size="small" onClick={() => onRefocus(selectedNode.urn)}>
              以此为焦点
            </Button>
            <Button
              size="small"
              onClick={() => {
                void navigator.clipboard?.writeText(selectedNode.urn)
                message.success('URN 已复制')
              }}
            >
              复制 URN
            </Button>
          </Space>
        </Card>
      )}
    </Space>
  )
}

/** 路径高亮：从焦点到被点节点的最短路径（docs/14 §3.3 的「路径高亮」）。 */
function highlightPath(cy: Core, focusUrn: string, targetUrn: string): void {
  cy.elements().removeClass('dimmed path-highlight')
  if (!focusUrn || targetUrn === focusUrn) return
  const source = cy.getElementById(focusUrn)
  const target = cy.getElementById(targetUrn)
  if (source.empty() || target.empty()) return

  // 无向最短路径：血缘方向不重要，用户关心的是"这两者之间是怎么连起来的"
  const dijkstra = cy.elements().dijkstra({ root: source, directed: false })
  const path = dijkstra.pathTo(target)
  if (path.empty()) {
    cy.elements().addClass('dimmed')
    source.removeClass('dimmed')
    target.removeClass('dimmed')
    return
  }
  cy.elements().addClass('dimmed')
  path.removeClass('dimmed')
  path.edges().addClass('path-highlight')
  source.removeClass('dimmed')
  target.removeClass('dimmed')
}

/** 供页面复用的搜索框（聚焦到指定 URN）。 */
export function LineageFocusInput({ onSubmit }: { onSubmit: (urn: string) => void }) {
  const [value, setValue] = useState('')
  return (
    <Input.Search
      placeholder="资产 URN，例如 urn:dg:Dataset:prod.postgresql.dg.public.entity"
      enterButton="聚焦"
      value={value}
      onChange={(event) => setValue(event.target.value)}
      onSearch={(text) => onSubmit(text.trim())}
      style={{ maxWidth: 640 }}
    />
  )
}
