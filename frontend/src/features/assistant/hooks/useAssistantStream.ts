import { useRef } from 'react'
import { assistantStreamUrl, ensureToken } from '../api/client'
import { useAssistantStore } from '../stores/assistantStore'
import type { AssistantEvent } from '../types'

const EVENT_NAMES = [
  'workflow_started',
  'agent_started',
  'agent_completed',
  'tool_call',
  'tool_result',
  'knowledge_sources',
  'ticket_created',
  'ticket_updated',
  'order_confirmation_required',
  'order_created',
  'trace',
  'final',
  'error'
]

export function useAssistantStream() {
  const sourceRef = useRef<EventSource | null>(null)
  const cancelledRef = useRef(false)
  const { sessionId, start, stop, ingest, consumeMessage } = useAssistantStore()

  /**
   * 建立一次 SSE 连接。
   *
   * `retryOnError`：断线时是否**换一次令牌重连**。
   *
   * 为什么需要（2026-11-11）：`fetch` 路径有"401 → 自动换令牌重试一次"，但 SSE 没有 ——
   * 令牌 24h 过期后，用户点发送会"没有反应"，直到刷新页面（刷新才会重新登录）。
   * `EventSource.onerror` 拿不到状态码，所以这里**统一重连一次**：令牌过期 → 换了就能连上；
   * 真的是服务端故障 → 第二次还会失败，第二次就不再重试（不会刷屏）。
   */
  const open = async (message: string, session: string, retryOnError: boolean) => {
    const url = await assistantStreamUrl(message, 'CUST-1001', '', session)
    if (cancelledRef.current) return
    const source = new EventSource(url)
    sourceRef.current = source

    const handle = (event: MessageEvent<string>) => {
      if (!event.data) return
      try {
        ingest(JSON.parse(event.data) as AssistantEvent)
      } catch (error) {
        console.error('Invalid SSE payload', error)
      }
    }
    EVENT_NAMES.forEach((name) => source.addEventListener(name, handle as EventListener))

    source.onerror = () => {
      stop()
      source.close()
      if (!retryOnError || cancelledRef.current) return
      console.warn('SSE 连接中断，换一次令牌后重连')
      void ensureToken(true)
        .then(() => {
          if (cancelledRef.current) return
          start()
          return open(message, session, false)
        })
        .catch(() => {
          // 换令牌也失败（后端不可达）：保持停止态，不再重试
          stop()
        })
    }
  }

  const run = async () => {
    // 空白输入不发请求；发送即取走内容，输入框立刻变空（见 store.consumeMessage）
    const message = consumeMessage()
    if (!message) return
    sourceRef.current?.close()
    cancelledRef.current = false
    start()
    // 2026-11-09：URL 里要带访问令牌（EventSource 不能加请求头），所以这里是异步取
    await open(message, sessionId, true)
  }

  const cancel = () => {
    cancelledRef.current = true
    sourceRef.current?.close()
    stop()
  }

  return { run, cancel }
}
