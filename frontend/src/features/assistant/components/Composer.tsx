import { ArrowUp, Square } from 'lucide-react'
import { useAssistantStream } from '../hooks/useAssistantStream'
import { useAssistantStore } from '../stores/assistantStore'

export function Composer() {
  const { run, cancel } = useAssistantStream()
  const { userMessage, setUserMessage, streaming } = useAssistantStore()
  // 输入为空时不发请求：空消息只会白跑一遍工作流（发送后输入框会被清空，见 store.consumeMessage）
  const canSend = userMessage.trim().length > 0
  return (
    <div className="glass rounded-3xl p-4">
      <textarea
        value={userMessage}
        onChange={(event) => setUserMessage(event.target.value)}
        onKeyDown={(event) => {
          // Enter 发送、Shift+Enter 换行（2026-11-11）。中文输入法组词期间不能抢 Enter，
          // 否则选词回车会被当成发送。
          if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
          event.preventDefault()
          if (!streaming && canSend) void run()
        }}
        className="h-24 w-full resize-none bg-transparent text-base text-slate-100 outline-none placeholder:text-slate-500"
        placeholder="输入工单问题，例如：购买 CLOTH-JEANS-003 下单（Enter 发送，Shift+Enter 换行）"
      />
      <div className="flex items-center justify-between border-t border-white/10 pt-3">
        <div className="flex gap-2 text-xs text-slate-400">
          <span className="rounded-full bg-white/5 px-3 py-1">SSE 流式</span>
          <span className="rounded-full bg-white/5 px-3 py-1">Tool Calling</span>
          <span className="rounded-full bg-white/5 px-3 py-1">RAG</span>
        </div>
        <button
          onClick={streaming ? cancel : run}
          disabled={!streaming && !canSend}
          title={streaming ? '停止生成' : '发送'}
          className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-300 to-violet-400 text-slate-950 shadow-glow transition hover:scale-105 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:scale-100"
        >
          {streaming ? <Square size={18} /> : <ArrowUp size={20} />}
        </button>
      </div>
    </div>
  )
}
