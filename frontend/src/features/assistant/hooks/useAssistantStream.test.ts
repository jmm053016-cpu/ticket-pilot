import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { assistantStreamUrl } from '../api/client'
import { useAssistantStore } from '../stores/assistantStore'
import { useAssistantStream } from './useAssistantStream'

/** jsdom 没有 EventSource：用它记录"发了什么请求"，顺便验证空输入不会发请求。 */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  closed = false
  onerror: (() => void) | null = null
  private listeners: Record<string, ((event: unknown) => void)[]> = {}

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(name: string, listener: (event: unknown) => void) {
    this.listeners[name] = [...(this.listeners[name] ?? []), listener]
  }

  close() {
    this.closed = true
  }
}

/** 令牌是异步换来的（2026-11-09 鉴权），所以这里要提供一个登录接口的假实现。 */
function stubLogin() {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify({ token: 'tk_test' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
    )
  )
}

describe('assistantStreamUrl', () => {
  beforeEach(stubLogin)
  afterEach(() => vi.unstubAllGlobals())

  it('builds encoded stream url', async () => {
    expect(await assistantStreamUrl('支付成功但订单没生成怎么办')).toContain('%E6%94%AF%E4%BB%98')
  })

  it('keeps sessionId in the stream url so memory works', async () => {
    expect(await assistantStreamUrl('hi', 'CUST-1001', '', 'sess-123')).toContain('sessionId=sess-123')
  })

  it('carries the access token (EventSource cannot send headers)', async () => {
    expect(await assistantStreamUrl('hi')).toContain('token=tk_test')
  })
})

describe('useAssistantStream 发送行为', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    stubLogin()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('发送后输入框立即清空，且请求带的是刚才那句话', async () => {
    useAssistantStore.setState({ userMessage: '黑色连帽卫衣多少钱', sessionId: 'sess-send' })
    const { result } = renderHook(() => useAssistantStream())

    await act(async () => {
      await result.current.run()
    })

    expect(useAssistantStore.getState().userMessage).toBe('')
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(decodeURIComponent(FakeEventSource.instances[0].url)).toContain('message=黑色连帽卫衣多少钱')
  })

  it('空输入（含只有空格）不发请求，也不进入流式状态', async () => {
    useAssistantStore.setState({ userMessage: '   ', streaming: false })
    const { result } = renderHook(() => useAssistantStream())

    await act(async () => {
      await result.current.run()
    })

    expect(FakeEventSource.instances).toHaveLength(0)
    expect(useAssistantStore.getState().streaming).toBe(false)
  })

  it('断线时换一次令牌重连（令牌过期不再"点了没反应"）', async () => {
    useAssistantStore.setState({ userMessage: '你好', sessionId: 'sess-retry' })
    const { result } = renderHook(() => useAssistantStream())

    await act(async () => {
      await result.current.run()
    })
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    // 模拟服务端断开（令牌过期时 EventSource 就是这个表现）
    await act(async () => {
      FakeEventSource.instances[0].onerror?.()
      await Promise.resolve()
    })

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    // 重连前**强制**换过一次令牌（token 是模块级缓存，所以只会有这一次 login 调用）
    const loginCalls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.length
    expect(loginCalls).toBeGreaterThanOrEqual(1)
  })

  it('发送时去掉首尾空白，但保留中间的正文', async () => {
    useAssistantStore.setState({ userMessage: '  我有1000元，最多可以买几件衣服  ', sessionId: 'sess-trim' })
    const { result } = renderHook(() => useAssistantStream())

    await act(async () => {
      await result.current.run()
    })

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(decodeURIComponent(FakeEventSource.instances[0].url)).toContain('message=我有1000元，最多可以买几件衣服&')
  })
})
