import { MessagesSquare, RefreshCw, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useAssistantStore } from '../stores/assistantStore'

function relativeTime(iso: string): string {
  if (!iso) return '-'
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) return '-'
  const diffMinutes = Math.round((Date.now() - time) / 60000)
  if (diffMinutes < 1) return '刚刚'
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`
  const diffHours = Math.round(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours} 小时前`
  return new Date(time).toLocaleDateString('zh-CN')
}

export function SessionList() {
  const { sessions, sessionId, streaming, loadSessions, openSession, deleteSession } = useAssistantStore()
  const [deleting, setDeleting] = useState<string | undefined>(undefined)
  const [error, setError] = useState<string | undefined>(undefined)

  // 首屏加载；每轮对话结束（streaming 由 true → false）后刷新，保证侧栏里的会话是最新的
  useEffect(() => {
    if (!streaming) void loadSessions()
  }, [streaming, sessionId, loadSessions])

  const onDelete = async (targetSessionId: string, preview: string) => {
    if (!window.confirm(`删除这个会话？\n\n「${preview}」\n\n消息会一起删除，无法恢复（审计日志不受影响）。`)) {
      return
    }
    setDeleting(targetSessionId)
    setError(undefined)
    try {
      await deleteSession(targetSessionId)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '删除会话失败')
    } finally {
      setDeleting(undefined)
    }
  }

  return (
    <section className="glass rounded-[2rem] p-5">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <MessagesSquare size={18} className="text-cyan-200" />
          <h3 className="font-semibold text-white">会话</h3>
        </div>
        <button
          onClick={() => void loadSessions()}
          title="刷新会话列表"
          className="flex items-center gap-1 rounded-full bg-white/5 px-2 py-1 text-xs text-slate-400 hover:text-cyan-100"
        >
          <RefreshCw size={12} />刷新
        </button>
      </div>

      {error && <p className="mb-3 rounded-2xl bg-rose-400/10 p-2 text-xs text-rose-100">{error}</p>}

      <div className="max-h-64 space-y-2 overflow-auto pr-1">
        {sessions.length === 0 && <p className="text-sm text-slate-500">还没有历史会话，问一句就会生成。</p>}
        {sessions.map((session) => {
          const active = session.sessionId === sessionId
          return (
            <div
              key={session.sessionId}
              className={
                'group relative rounded-2xl border transition ' +
                (active
                  ? 'border-cyan-300/40 bg-cyan-300/10'
                  : 'border-white/10 bg-white/[0.03] hover:border-cyan-300/20 hover:bg-white/[0.06]')
              }
            >
              <button
                onClick={() => void openSession(session.sessionId)}
                disabled={streaming}
                className="w-full p-3 pr-10 text-left disabled:opacity-50"
              >
                <p className={'truncate text-sm ' + (active ? 'text-cyan-100' : 'text-slate-200')}>{session.preview}</p>
                <p className="mt-1 flex items-center justify-between text-[11px] text-slate-500">
                  <span>{session.messageCount} 条消息</span>
                  <span>{relativeTime(session.updatedAt)}</span>
                </p>
                <p className="mt-1 truncate text-[10px] text-slate-600">{session.sessionId}</p>
              </button>
              <button
                onClick={() => void onDelete(session.sessionId, session.preview)}
                disabled={streaming || deleting === session.sessionId}
                title="删除这个会话"
                aria-label={`删除会话：${session.preview}`}
                className="absolute right-2 top-2 rounded-full p-1.5 text-slate-500 opacity-0 transition hover:bg-rose-400/15 hover:text-rose-200 focus:opacity-100 group-hover:opacity-100 disabled:opacity-40"
              >
                <Trash2 size={14} />
              </button>
            </div>
          )
        })}
      </div>
    </section>
  )
}
