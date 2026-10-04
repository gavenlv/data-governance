import { Tag, Tooltip } from 'antd'
import { CheckCircleTwoTone, ClockCircleTwoTone, StopTwoTone } from '@ant-design/icons'
import type { CapabilityStatus } from '../api/client'

/**
 * 实现状态徽标。
 *
 * 这是「未实现必须显式标注」在界面上的落点：每个功能入口都带状态，
 * 使用者不需要靠试错发现哪些是占位（docs/21 §3 的标注纪律）。
 */
export function CapabilityBadge({ status, small }: { status: CapabilityStatus; small?: boolean }) {
  const config: Record<CapabilityStatus, { color: string; icon: JSX.Element; text: string; tip: string }> = {
    IMPLEMENTED: {
      color: 'success',
      icon: <CheckCircleTwoTone twoToneColor="#52c41a" />,
      text: '已实现',
      tip: '已实现并有验证（单元测试或端到端）',
    },
    PARTIAL: {
      color: 'warning',
      icon: <ClockCircleTwoTone twoToneColor="#faad14" />,
      text: '部分实现',
      tip: '主路径可用，缺口见该功能的说明',
    },
    NOT_IMPLEMENTED: {
      color: 'default',
      icon: <StopTwoTone twoToneColor="#bfbfbf" />,
      text: '未实现',
      tip: '仅有设计与接口占位，见该功能的设计说明',
    },
  }
  const item = config[status]
  return (
    <Tooltip title={item.tip}>
      <Tag color={item.color} icon={item.icon} style={{ marginInlineEnd: 0, fontSize: small ? 11 : 12 }}>
        {item.text}
      </Tag>
    </Tooltip>
  )
}
