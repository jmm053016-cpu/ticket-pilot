import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionList } from '../components/SessionList'
import { useAssistantStore } from '../stores/assistantStore'

/**
 * 会话删除按钮（2026-11-05 新增）。
 *
 * 前端这层要保证三件事：①点了要先确认；②调用的是 DELETE 接口；③删完列表要刷新、删的是当前会话就换新会话。
 */
const sessions = [
  {
    sessionId: 'sess-1',
    preview: '米色帆布双肩包多少钱',
    messageCount: 2,
    createdAt: '2026-11-05T08:00:00Z',
    updatedAt: '2026-11-05T08:00:00Z'
  },
  {
    sessionId: 'sess-2',
    preview: '我的订单有哪些',
    messageCount: 4,
    createdAt: '2026-11-05T07:00:00Z',
    updatedAt: '2026-11-05T07:00:00Z'
  }
]

describe('SessionList 删除会话', () => {
  const requests: string[] = []
  let deleted = false

  beforeEach(() => {
    requests.length = 0
    deleted = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        requests.push(`${init?.method ?? 'GET'} ${url}`)
        if (init?.method === 'DELETE') {
          deleted = true
          return new Response(JSON.stringify({ sessionId: 'sess-1', deleted: true }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          })
        }
        // 列表：删掉 sess-1 之后只剩 sess-2
        const body = deleted ? [sessions[1]] : sessions
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      })
    )
    useAssistantStore.setState({ sessions, sessionId: 'sess-1', streaming: false })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('确认后调用 DELETE 接口，并把该会话从列表里去掉', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    render(<SessionList />)

    expect(screen.getByText('米色帆布双肩包多少钱')).toBeDefined()

    screen.getByLabelText('删除会话：米色帆布双肩包多少钱').click()

    await waitFor(() => {
      expect(requests.some((r) => r.startsWith('DELETE ') && r.includes('/assistant/sessions/sess-1'))).toBe(true)
    })
    await waitFor(() => {
      expect(screen.queryByText('米色帆布双肩包多少钱')).toBeNull()
    })
    // 删的是当前会话 → 应该已经开了一个新会话，避免继续往已删的 sessionId 写
    expect(useAssistantStore.getState().sessionId).not.toBe('sess-1')
  })

  it('用户取消确认时不发请求', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    render(<SessionList />)

    screen.getByLabelText('删除会话：米色帆布双肩包多少钱').click()

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(requests.some((r) => r.startsWith('DELETE '))).toBe(false)
    expect(screen.getByText('米色帆布双肩包多少钱')).toBeDefined()
  })
})
