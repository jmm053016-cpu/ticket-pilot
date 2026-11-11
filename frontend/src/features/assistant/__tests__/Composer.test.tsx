import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../components/Composer'
import { useAssistantStore } from '../stores/assistantStore'

/** jsdom 没有 EventSource：Composer → useAssistantStream → new EventSource(url) 需要它存在。 */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  onerror: (() => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener() {}
  close() {}
}

function textarea() {
  return screen.getByPlaceholderText(/输入工单问题/) as HTMLTextAreaElement
}

describe('Composer 输入框', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    // 2026-11-09 起发消息前要先拿令牌（鉴权）：这里给 /auth/login 一个假实现
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
    useAssistantStore.setState({ userMessage: '', streaming: false })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('会话开始时输入框是空的，发送按钮不可点', () => {
    render(<Composer />)

    expect(textarea().value).toBe('')
    expect((screen.getByTitle('发送') as HTMLButtonElement).disabled).toBe(true)
  })

  it('输入后可以发送，发送后文字消失', async () => {
    render(<Composer />)

    fireEvent.change(textarea(), { target: { value: '黑色连帽卫衣多少钱' } })
    expect(textarea().value).toBe('黑色连帽卫衣多少钱')
    expect((screen.getByTitle('发送') as HTMLButtonElement).disabled).toBe(false)

    fireEvent.click(screen.getByTitle('发送'))

    expect(textarea().value).toBe('')
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(decodeURIComponent(FakeEventSource.instances[0].url)).toContain('message=黑色连帽卫衣多少钱')
  })

  it('Enter 发送、Shift+Enter 换行（输入法组词时不抢 Enter）', async () => {
    render(<Composer />)

    fireEvent.change(textarea(), { target: { value: '基础纯棉白色T恤多少钱' } })
    // Shift+Enter：换行，不发送
    fireEvent.keyDown(textarea(), { key: 'Enter', shiftKey: true })
    expect(FakeEventSource.instances).toHaveLength(0)
    // 输入法组词中（isComposing）：也不发送
    fireEvent.keyDown(textarea(), { key: 'Enter', nativeEvent: { isComposing: true } })
    expect(FakeEventSource.instances).toHaveLength(0)

    fireEvent.keyDown(textarea(), { key: 'Enter' })
    expect(textarea().value).toBe('')
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
  })

  it('只有空格时不能发送', () => {
    render(<Composer />)

    fireEvent.change(textarea(), { target: { value: '    ' } })

    expect((screen.getByTitle('发送') as HTMLButtonElement).disabled).toBe(true)
  })
})
