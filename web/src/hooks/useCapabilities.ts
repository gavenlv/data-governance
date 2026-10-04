import { useQuery } from '@tanstack/react-query'
import { api, type CapabilityDescriptor, type CapabilityReport } from '../api/client'

/**
 * 能力清单（界面与接口共用的实现状态来源）。
 *
 * 设计意图：能力状态**只有一处真相**（控制面的 CapabilityProvider 实现），
 * 界面不维护自己的「哪些做完了」清单 —— 否则两者必然漂移。
 */
export function useCapabilities() {
  const query = useQuery<CapabilityReport>({
    queryKey: ['capabilities'],
    queryFn: () => api.capabilities(),
    staleTime: 30_000,
  })

  const all: CapabilityDescriptor[] = query.data
    ? Object.values(query.data.domains).flat()
    : []

  const byId = new Map(all.map((item) => [item.id, item]))

  return {
    ...query,
    all,
    byId,
    /** 按 id 取能力（用于页面渲染状态徽标）。 */
    get: (id: string): CapabilityDescriptor | undefined => byId.get(id),
    /** 判断某能力是否已实现（决定渲染真实内容还是占位卡）。 */
    isImplemented: (id: string): boolean => byId.get(id)?.status === 'IMPLEMENTED',
    isPartial: (id: string): boolean => byId.get(id)?.status === 'PARTIAL',
  }
}
