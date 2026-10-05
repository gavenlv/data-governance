import { useState } from 'react'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Input, Layout, Menu, Space, Tag, Tooltip, Typography } from 'antd'
import {
  ApartmentOutlined,
  AuditOutlined,
  CompassOutlined,
  DatabaseOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { api, getToken, setToken } from './api/client'
import { useCapabilities } from './hooks/useCapabilities'
import DiscoveryPage from './pages/DiscoveryPage'
import AssetsPage from './pages/AssetsPage'
import AssetDetailPage from './pages/AssetDetailPage'
import LineagePage from './pages/LineagePage'
import QualityPage from './pages/QualityPage'
import GovernancePage from './pages/GovernancePage'
import ObservabilityPage from './pages/ObservabilityPage'
import AiPage from './pages/AiPage'
import AdminPage from './pages/AdminPage'

const { Header, Sider, Content } = Layout
const { Text } = Typography

/** 六入口信息架构（docs/14 §2）。 */
const NAV = [
  { key: '/discovery', icon: <CompassOutlined />, label: '发现' },
  { key: '/assets', icon: <DatabaseOutlined />, label: '资产' },
  { key: '/lineage', icon: <ApartmentOutlined />, label: '血缘' },
  { key: '/quality', icon: <SafetyCertificateOutlined />, label: '质量' },
  { key: '/observability', icon: <WarningOutlined />, label: '可观测' },
  { key: '/governance', icon: <AuditOutlined />, label: '治理' },
  { key: '/ai', icon: <RobotOutlined />, label: 'AI 与 Agent' },
  { key: '/admin', icon: <SettingOutlined />, label: '管理' },
]

export default function App() {
  const location = useLocation()
  const [token, setTokenState] = useState(getToken())
  const capabilities = useCapabilities()

  const me = useQuery({
    queryKey: ['me', token],
    queryFn: () => api.me(),
    retry: false,
  })

  const selectedKey = '/' + (location.pathname.split('/')[1] || 'discovery')

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header className="dg-header" style={{ height: 56, lineHeight: '56px' }}>
        <span className="dg-header-title">数据治理平台</span>
        <Tag color="blue">Java 控制面</Tag>
        <Tag>Phase 0</Tag>
        {capabilities.data && (
          <Tooltip title="已实现 / 部分实现 / 未实现 的能力数量，明细见「管理 › 能力清单」">
            <Space size={4}>
              <Badge status="success" text={`${capabilities.data.summary.implemented} 已实现`} />
              <Badge status="warning" text={`${capabilities.data.summary.partial} 部分`} />
              <Badge status="default" text={`${capabilities.data.summary.notImplemented} 未实现`} />
            </Space>
          </Tooltip>
        )}
        <span className="dg-header-spacer" />
        <Input.Password
          size="small"
          style={{ width: 190 }}
          value={token}
          onChange={(event) => setTokenState(event.target.value)}
          onBlur={() => {
            setToken(token)
            void me.refetch()
          }}
          placeholder="访问令牌"
        />
        {me.data?.authenticated ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {me.data.name} · {(me.data.roles ?? []).join('/') || '无角色'}
          </Text>
        ) : (
          <Text type="danger" style={{ fontSize: 12 }}>
            未认证
          </Text>
        )}
        <Button size="small" onClick={() => void me.refetch()}>
          校验
        </Button>
      </Header>

      <Layout>
        <Sider width={176} theme="light" breakpoint="lg" collapsedWidth={64}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            style={{ height: '100%', borderInlineEnd: 0 }}
            items={NAV.map((item) => ({
              key: item.key,
              icon: item.icon,
              label: <Link to={item.key}>{item.label}</Link>,
            }))}
          />
        </Sider>

        <Content className="dg-content">
          <Routes>
            <Route path="/" element={<Navigate to="/discovery" replace />} />
            <Route path="/discovery" element={<DiscoveryPage />} />
            <Route path="/assets" element={<AssetsPage />} />
            <Route path="/assets/:urn" element={<AssetDetailPage />} />
            <Route path="/lineage" element={<LineagePage />} />
            <Route path="/quality" element={<QualityPage />} />
            <Route path="/observability" element={<ObservabilityPage />} />
            <Route path="/governance" element={<GovernancePage />} />
            <Route path="/ai" element={<AiPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="*" element={<Navigate to="/discovery" replace />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  )
}
