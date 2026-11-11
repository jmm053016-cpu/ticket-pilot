import { create } from 'zustand'
import { deleteSession as deleteSessionApi, fetchSessionTurns, fetchSessions } from '../api/client'
import type { AgentCard, AssistantEvent, ConversationTurnView, OrderConfirmationPayload, OrderCreationResult, SessionSummary, SourceCard, ToolCard } from '../types'

type AssistantState = {
  connected: boolean
  streaming: boolean
  userMessage: string
  /** 本轮刚发出去的那句话（输入框会被清空，所以回答区要留一行"你：…"，否则用户看不到自己问了什么）。 */
  lastAskedMessage: string
  sessionId: string
  sessions: SessionSummary[]
  history: ConversationTurnView[]
  answer: string
  events: AssistantEvent[]
  agents: AgentCard[]
  tools: ToolCard[]
  sources: SourceCard[]
  ticket?: unknown
  pendingOrderConfirmation?: OrderConfirmationPayload
  orderCreationResult?: OrderCreationResult
  orderConfirmationError?: string
  setUserMessage: (value: string) => void
  /**
   * 取出「这一轮要发的话」并把输入框清空。
   *
   * <p>为什么放在 store 而不是组件里：清空输入框这件事有两条规则要一起成立 ——
   * ① 发送后输入框必须变空（否则用户会以为没发出去、又点一次）；
   * ② 空白（只有空格/换行）不能发出请求（空消息会白跑一遍工作流）。
   * 放在 store 里就能直接单测，不必为了验证它去渲染组件 + mock EventSource。
   *
   * @return 去空格后的消息；空白输入返回 null（调用方应直接 return，不要发请求）。
   */
  consumeMessage: () => string | null
  start: () => void
  stop: () => void
  reset: () => void
  newSession: () => void
  loadSessions: () => Promise<void>
  openSession: (sessionId: string) => Promise<void>
  deleteSession: (sessionId: string) => Promise<void>
  setOrderCreationResult: (result: OrderCreationResult) => void
  setOrderConfirmationError: (message?: string) => void
  clearOrderConfirmation: () => void
  ingest: (event: AssistantEvent) => void
}

const SESSION_STORAGE_KEY = 'tp.assistant.sessionId'

// 多轮记忆的锚点：刷新页面也要复用同一个 sessionId，所以落 localStorage
function initialSessionId(): string {
  try {
    const stored = window.localStorage.getItem(SESSION_STORAGE_KEY)
    if (stored) return stored
    const created = crypto.randomUUID()
    window.localStorage.setItem(SESSION_STORAGE_KEY, created)
    return created
  } catch {
    return crypto.randomUUID()
  }
}

function persistSessionId(sessionId: string) {
  try {
    window.localStorage.setItem(SESSION_STORAGE_KEY, sessionId)
  } catch {
    /* 隐私模式下写不了就不写，本次会话内仍然可用 */
  }
}
const initialAgents: AgentCard[] = [
  { name: 'Intent Agent', status: 'idle', detail: '意图识别 / 参数抽取 / Tool Routing' },
  { name: 'Order Agent', status: 'idle', detail: '订单与工单业务决策' },
  { name: 'Knowledge Agent', status: 'idle', detail: 'RAG 混合检索与知识汇总' }
]

function toSourceCard(value: unknown): SourceCard {
  if (!value || typeof value !== 'object') return {}
  const source = value as Record<string, unknown>
  const chunk = source.chunk && typeof source.chunk === 'object' ? source.chunk as Record<string, unknown> : source
  return {
    id: typeof chunk.id === 'string' ? chunk.id : undefined,
    title: typeof chunk.title === 'string' ? chunk.title : undefined,
    source: typeof chunk.source === 'string' ? chunk.source : undefined,
    content: typeof chunk.content === 'string' ? chunk.content : undefined,
    score: typeof source.finalScore === 'number' ? source.finalScore : typeof source.score === 'number' ? source.score : undefined,
    matchReason: typeof source.matchReason === 'string' ? source.matchReason : undefined
  }
}

export const useAssistantStore = create<AssistantState>((set, get) => ({
  connected: false,
  streaming: false,
  // 输入框初始为空：预填示例问题会让人分不清"这是提示"还是"我要发的字"
  userMessage: '',
  lastAskedMessage: '',
  sessionId: initialSessionId(),
  sessions: [],
  history: [],
  answer: '',
  events: [],
  agents: initialAgents,
  tools: [],
  sources: [],
  setUserMessage: (value) => set({ userMessage: value }),
  consumeMessage: () => {
    const message = get().userMessage.trim()
    if (!message) return null
    set({ userMessage: '', lastAskedMessage: message })
    return message
  },
  start: () => set({ connected: true, streaming: true, answer: '', events: [], tools: [], sources: [], pendingOrderConfirmation: undefined, orderCreationResult: undefined, orderConfirmationError: undefined, agents: initialAgents }),
  stop: () => set({ connected: false, streaming: false }),
  reset: () => set({ history: [], answer: '', events: [], tools: [], sources: [], ticket: undefined, lastAskedMessage: '', pendingOrderConfirmation: undefined, orderCreationResult: undefined, orderConfirmationError: undefined, agents: initialAgents }),
  newSession: () => set(() => {
    const sessionId = crypto.randomUUID()
    persistSessionId(sessionId)
    return { sessionId, userMessage: '', lastAskedMessage: '', history: [], answer: '', events: [], tools: [], sources: [], ticket: undefined, pendingOrderConfirmation: undefined, orderCreationResult: undefined, orderConfirmationError: undefined, agents: initialAgents }
  }),
  loadSessions: async () => {
    try {
      const sessions = await fetchSessions()
      set({ sessions })
    } catch (error) {
      console.error('加载会话列表失败', error)
    }
  },
  openSession: async (sessionId) => {
    persistSessionId(sessionId)
    let history: ConversationTurnView[] = []
    try {
      history = await fetchSessionTurns(sessionId)
    } catch (error) {
      console.error('加载会话历史失败', error)
    }
    set({ sessionId, userMessage: '', lastAskedMessage: '', history, answer: '', events: [], tools: [], sources: [], ticket: undefined, pendingOrderConfirmation: undefined, orderCreationResult: undefined, orderConfirmationError: undefined, agents: initialAgents })
  },
  deleteSession: async (targetSessionId) => {
    await deleteSessionApi(targetSessionId)
    // 删的是当前会话：换一个新的，避免继续往已删除的 sessionId 里写
    if (get().sessionId === targetSessionId) {
      get().newSession()
    }
    const sessions = await fetchSessions()
    set({ sessions })
  },
  setOrderCreationResult: (result) => set({ orderCreationResult: result, pendingOrderConfirmation: undefined, orderConfirmationError: undefined }),
  setOrderConfirmationError: (message) => set({ orderConfirmationError: message }),
  clearOrderConfirmation: () => set({ pendingOrderConfirmation: undefined, orderConfirmationError: undefined }),
  ingest: (event) => set((state) => {
    const next = { ...state, events: [...state.events, event] }
    if (event.type === 'agent_started') {
      next.agents = state.agents.map((agent) => event.message.includes(agent.name.split(' ')[0]) || event.message.includes('并行') ? { ...agent, status: 'running' } : agent)
    }
    if (event.type === 'agent_completed') {
      next.agents = state.agents.map((agent) => ({ ...agent, status: agent.status === 'running' ? 'done' : agent.status }))
    }
    if (event.type === 'tool_call') {
      next.tools = [...state.tools, { name: 'ToolDispatcher', status: 'requested', payload: event.payload }]
    }
    if (event.type === 'tool_result') {
      next.tools = [...state.tools, { name: 'Tool Result', status: 'success', payload: event.payload }]
    }
    if (event.type === 'ticket_created' || event.type === 'ticket_updated') {
      next.ticket = event.payload.ticket
    }
    if (event.type === 'order_confirmation_required') {
      const confirmation = event.payload.confirmation
      if (confirmation && typeof confirmation === 'object') {
        next.pendingOrderConfirmation = confirmation as OrderConfirmationPayload
      }
    }
    if (event.type === 'knowledge_sources') {
      const raw = event.payload.sources
      next.sources = Array.isArray(raw) ? raw.map(toSourceCard) : []
    }
    if (event.type === 'final') {
      const delta = event.payload.delta
      const done = event.payload.done
      if (typeof delta === 'string') next.answer = state.answer + delta
      if (done === true) next.streaming = false
    }
    if (event.type === 'error') {
      next.streaming = false
      next.agents = state.agents.map((agent) => agent.status === 'running' ? { ...agent, status: 'error' } : agent)
    }
    return next
  })
}))
