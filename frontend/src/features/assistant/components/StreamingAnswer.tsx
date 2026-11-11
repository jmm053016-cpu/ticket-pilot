import { Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { parseTableBlock, type TableModel } from './markdownTable'
import { useAssistantStore } from '../stores/assistantStore'

type InlineSegment = {
  text: string
  strong: boolean
}

function inlineMarkdown(text: string): InlineSegment[] {
  return text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean).map((part) => {
    const strong = part.startsWith('**') && part.endsWith('**')
    return { text: strong ? part.slice(2, -2) : part, strong }
  })
}

function InlineSegments({ text }: { text: string }) {
  return (
    <>
      {inlineMarkdown(text).map((segment, index) =>
        segment.strong ? <strong key={index}>{segment.text}</strong> : <span key={index}>{segment.text}</span>,
      )}
    </>
  )
}

/** 回答里的 Markdown 表格：暗色玻璃卡片风格，窄屏可横向滚动。 */
function AnswerTable({ table }: { table: TableModel }) {
  return (
    <div className="my-3 overflow-x-auto rounded-2xl border border-white/10">
      <table className="w-full border-collapse text-sm">
        <thead className="bg-white/[0.06]">
          <tr>
            {table.headers.map((header, index) => (
              <th key={index} className="px-3 py-2 text-left font-semibold text-cyan-100">
                <InlineSegments text={header} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, rowIndex) => (
            <tr key={rowIndex} className="border-t border-white/5">
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} className="px-3 py-2 text-slate-200">
                  <InlineSegments text={cell} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function MarkdownLine({ line }: { line: string }) {
  const trimmed = line.trim()
  const listMatch = /^[-✅]\s*(.*)$/.exec(trimmed)
  const headingMatch = /^\*\*(.+?)\*\*：?\s*(.*)$/.exec(trimmed)
  const text = listMatch?.[1] ?? headingMatch?.[2] ?? line
  const segments = inlineMarkdown(text)

  if (headingMatch) {
    return (
      <div className="mt-4 first:mt-0">
        <p className="font-semibold text-cyan-100">{headingMatch[1]}</p>
        {headingMatch[2] && <p className="mt-1 text-slate-100">{segments.map((segment, index) => segment.strong ? <strong key={index}>{segment.text}</strong> : <span key={index}>{segment.text}</span>)}</p>}
      </div>
    )
  }

  if (listMatch) {
    return (
      <li className="ml-5 list-disc text-slate-200">
        {segments.map((segment, index) => segment.strong ? <strong key={index}>{segment.text}</strong> : <span key={index}>{segment.text}</span>)}
      </li>
    )
  }

  return (
    <p className="text-slate-100">
      {segments.map((segment, index) => segment.strong ? <strong key={index}>{segment.text}</strong> : <span key={index}>{segment.text}</span>)}
    </p>
  )
}

/**
 * 回答渲染器：支持 **加粗**、-  列表、**标题**：内容，以及标准 Markdown 表格。
 *
 * <p>表格为什么单独处理：模型很爱用表格列价格组合，以前会原样显示成裸管道符。
 * 流式输出时表格可能还没写完（缺分隔行），此时 parseTableBlock 返回 null，先按普通文本显示，
 * 补全后自动变成表格。
 */
export function MarkdownAnswer({ text }: { text: string }) {
  const lines = text.split('\n')
  const blocks: ReactNode[] = []
  for (let index = 0; index < lines.length; index += 1) {
    const table = parseTableBlock(lines, index)
    if (table) {
      blocks.push(<AnswerTable key={'table-' + index} table={table} />)
      index = table.endIndex
      continue
    }
    const line = lines[index]
    blocks.push(
      line.trim() ? <MarkdownLine key={index} line={line} /> : <div key={index} className="h-2" />,
    )
  }
  return <div className="space-y-2 leading-8">{blocks}</div>
}

export function StreamingAnswer() {
  const { answer, streaming, history, lastAskedMessage } = useAssistantStore()
  return (
    <section className="glass min-h-[420px] rounded-[2rem] p-6">
      <div className="mb-5 flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-cyan-300/15 text-cyan-200">
          <Sparkles size={20} />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-white">AI Ticket Assistant</h2>
          <p className="text-sm text-slate-400">多 Agent 协同响应</p>
        </div>
      </div>
      {history.length > 0 && (
        <div className="mb-5 max-h-64 space-y-3 overflow-auto border-b border-white/10 pb-4">
          <p className="text-xs uppercase tracking-wider text-slate-500">历史对话（{history.length} 轮）</p>
          {history.map((turn, index) => (
            <div key={index} className="space-y-1">
              <p className="text-sm text-slate-400">你：{turn.userMessage}</p>
              <p className="text-sm text-slate-300">助手：{turn.assistantAnswer}</p>
            </div>
          ))}
        </div>
      )}
      {lastAskedMessage && (
        // 发送后输入框会被清空，这里留一行"你：…"，让当前这轮的问题看得见
        <p className="mb-3 rounded-2xl border border-white/10 bg-white/[0.04] px-4 py-2 text-sm text-slate-300">
          你：{lastAskedMessage}
        </p>
      )}
      <div className={streaming ? 'typing-caret' : ''}>
        {answer ? <MarkdownAnswer text={answer} /> : <p className="leading-8 text-slate-400">你好，我是客服助手，等待你的问题。系统会展示 Intent Agent、Order Agent、Knowledge Agent、Tool Calling 与 RAG 检索过程。</p>}
      </div>
    </section>
  )
}
