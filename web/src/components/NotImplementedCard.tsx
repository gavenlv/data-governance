import { Alert, Card, Descriptions, List, Space, Tag, Typography } from 'antd'
import { FileTextOutlined } from '@ant-design/icons'
import type { CapabilityDescriptor } from '../api/client'
import { CapabilityBadge } from './CapabilityBadge'

const { Paragraph, Text } = Typography

/**
 * 未实现功能的占位卡。
 *
 * 刻意不显示「暂无数据」：那会让人以为是「数据为空」而不是「功能没做」。
 * 卡片明确给出：设计出处、计划阶段、将提供什么、以及**具体缺什么**。
 */
export function NotImplementedCard({
  capability,
  fallback,
}: {
  capability?: CapabilityDescriptor
  fallback?: { title: string; doc: string; phase: string; summary: string; notes?: string[] }
}) {
  const title = capability?.name ?? fallback?.title ?? '未实现'
  const doc = capability?.doc ?? fallback?.doc ?? '-'
  const phase = capability?.phase ?? fallback?.phase ?? '-'
  const summary = capability?.summary ?? fallback?.summary ?? ''
  const notes = capability?.notes ?? fallback?.notes ?? []
  const status = capability?.status ?? 'NOT_IMPLEMENTED'

  return (
    <Card
      title={
        <Space>
          <span>{title}</span>
          <CapabilityBadge status={status} />
        </Space>
      }
      style={{ borderStyle: 'dashed' }}
    >
      <Alert
        type="info"
        showIcon
        message="该功能尚未实现，此处为占位与设计说明"
        description="控制面的 /api/v1/capabilities 同样声明了该状态，界面与接口保持一致，不做假数据。"
        style={{ marginBottom: 16 }}
      />
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="设计出处">
          <Tag icon={<FileTextOutlined />}>{doc}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="计划阶段">
          <Tag color="blue">{phase}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="将提供">{summary}</Descriptions.Item>
      </Descriptions>
      {notes.length > 0 && (
        <>
          <Paragraph style={{ marginTop: 16, marginBottom: 4 }}>
            <Text strong>缺口与设计要点</Text>
          </Paragraph>
          <List
            size="small"
            dataSource={notes}
            renderItem={(item) => <List.Item style={{ paddingInline: 0 }}>• {item}</List.Item>}
          />
        </>
      )}
    </Card>
  )
}
