import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * 让 Tab 可以深链（`?tab=incidents`）。
 *
 * <p>两个理由：
 * <ol>
 *   <li>能直接把「事故与闭环」这一步的链接发给同事，而不是"打开治理页，点第四个标签"；</li>
 *   <li>端到端验证可以逐个 Tab 渲染 —— antd 的 Tabs 默认懒挂载，不指定 activeKey
 *       就只有第一个 Tab 会被渲染，"其余 Tab 是否真的能跑"永远验证不到。</li>
 * </ol>
 */
export function useTabParam(fallback: string): [string, (key: string) => void] {
  const [params, setParams] = useSearchParams()
  const active = params.get('tab') ?? fallback
  const onChange = useCallback(
    (key: string) => {
      const next = new URLSearchParams(params)
      next.set('tab', key)
      setParams(next, { replace: true })
    },
    [params, setParams],
  )
  return [active, onChange]
}
