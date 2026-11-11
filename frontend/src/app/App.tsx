import { useState } from 'react'
import { AssistantPage } from '../features/assistant/AssistantPage'
import { ReviewConsole } from '../features/review/ReviewConsole'

type View = 'assistant' | 'review'

/**
 * 两个视图：客服对话工作台 / 售后审核台（2026-11-09）。
 *
 * 项目没有引入路由库（单页够用），所以用一个极简的视图切换 —— 审核台走 `/admin/**` 后端接口，
 * 令牌由 `client.ts` 自动携带。
 */
export default function App() {
  const [view, setView] = useState<View>('assistant')

  const views: { key: View; label: string }[] = [
    { key: 'assistant', label: '客服工作台' },
    { key: 'review', label: '售后审核台' }
  ]

  return (
    <div className="min-h-screen">
      <nav className="sticky top-0 z-10 flex items-center justify-center gap-2 border-b border-white/5 bg-slate-950/70 px-4 py-2 backdrop-blur">
        {views.map((item) => (
          <button
            key={item.key}
            onClick={() => setView(item.key)}
            className={`rounded-full px-4 py-1.5 text-sm transition ${
              view === item.key ? 'bg-cyan-300/20 text-cyan-100' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {item.label}
          </button>
        ))}
      </nav>
      {view === 'assistant' ? <AssistantPage /> : <ReviewConsole />}
    </div>
  )
}
