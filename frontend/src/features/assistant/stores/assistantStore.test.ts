import { describe, expect, it } from 'vitest'
import { useAssistantStore } from './assistantStore'

/** 会话开始时的输入框内容（模块加载时的初始值，等价于"刚打开页面"）。 */
const initialUserMessage = useAssistantStore.getState().userMessage

describe('assistantStore', () => {
  it('appends final deltas', () => {
    useAssistantStore.getState().reset()
    useAssistantStore.getState().ingest({ type: 'final', message: 'hi', payload: { delta: 'hi', done: false }, timestamp: new Date().toISOString() })
    expect(useAssistantStore.getState().answer).toBe('hi')
  })

  it('会话开始时输入框是空的（不预填示例问题）', () => {
    expect(initialUserMessage).toBe('')
  })

  it('consumeMessage 取走内容并清空输入框', () => {
    useAssistantStore.getState().setUserMessage('  黑色连帽卫衣多少钱 ')
    expect(useAssistantStore.getState().consumeMessage()).toBe('黑色连帽卫衣多少钱')
    expect(useAssistantStore.getState().userMessage).toBe('')
    // 输入框清空后，回答区仍要能看到这一轮问了什么
    expect(useAssistantStore.getState().lastAskedMessage).toBe('黑色连帽卫衣多少钱')
  })

  it('空白输入不返回内容，也不清空（用户自己敲的空格不替他抹掉）', () => {
    useAssistantStore.getState().setUserMessage('   ')
    expect(useAssistantStore.getState().consumeMessage()).toBeNull()
    expect(useAssistantStore.getState().userMessage).toBe('   ')
  })

  it('新会话会清空输入框与历史', () => {
    useAssistantStore.getState().setUserMessage('上一条没发出去的话')
    useAssistantStore.getState().consumeMessage()
    useAssistantStore.getState().newSession()
    expect(useAssistantStore.getState().userMessage).toBe('')
    expect(useAssistantStore.getState().lastAskedMessage).toBe('')
    expect(useAssistantStore.getState().history).toEqual([])
  })
})
