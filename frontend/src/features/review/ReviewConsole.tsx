import { useCallback, useEffect, useMemo, useState } from 'react'
import { CheckCircle2, ClipboardList, RotateCw, XCircle } from 'lucide-react'
import {
  approveRefund,
  approveReturn,
  fetchRefundRequests,
  fetchReturnRequests,
  rejectRefund,
  rejectReturn
} from '../assistant/api/client'
import type { ReviewItem } from '../assistant/api/client'

type Kind = 'refunds' | 'returns'
type Status = 'REQUESTED' | 'APPROVED' | 'REJECTED'

const STATUS_LABEL: Record<string, string> = {
  REQUESTED: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/**
 * 售后审核台（2026-11-09，V13）。
 *
 * 为什么要有：退款/退货申请单以前停在 `REQUESTED`，审核只能人肉翻库。
 * 这里给出「按状态筛选 → 逐条通过/驳回（驳回必须填原因）→ 结果写回申请单并推进工单」。
 *
 * 关键约定：驳回**必须**填原因（用户会看到这句话）；通过可以不填。
 */
export function ReviewConsole() {
  const [kind, setKind] = useState<Kind>('refunds')
  const [status, setStatus] = useState<Status>('REQUESTED')
  const [items, setItems] = useState<ReviewItem[]>([])
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = kind === 'refunds' ? await fetchRefundRequests(status) : await fetchReturnRequests(status)
      setItems(data)
      setError('')
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [kind, status])

  useEffect(() => {
    void load()
  }, [load])

  const pending = useMemo(() => items.filter((item) => item.status === 'REQUESTED'), [items])

  const decide = async (item: ReviewItem, action: 'approve' | 'reject') => {
    const note = (notes[item.id] ?? '').trim()
    if (action === 'reject' && note === '') {
      setError('驳回必须填写原因（用户会看到这句话）')
      return
    }
    setBusy(`${item.id}-${action}`)
    try {
      if (kind === 'refunds') {
        await (action === 'approve' ? approveRefund(item.id, note) : rejectRefund(item.id, note))
      } else {
        await (action === 'approve' ? approveReturn(item.id, note) : rejectReturn(item.id, note))
      }
      setNotes((prev) => ({ ...prev, [item.id]: '' }))
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '审核失败')
    } finally {
      setBusy('')
    }
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-6 md:px-8">
      <header className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-300 via-orange-300 to-rose-400 text-slate-950 shadow-glow">
            <ClipboardList size={24} />
          </div>
          <div>
            <p className="text-xs text-amber-200">After-sales review</p>
            <h1 className="text-xl font-semibold text-white md:text-2xl">售后审核台</h1>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs text-slate-300">
            {kind === 'refunds' ? '退款申请' : '退货申请'} · {STATUS_LABEL[status]}
            {loading ? ' · 加载中…' : ` · ${items.length} 条`}
          </span>
          <button
            onClick={() => void load()}
            disabled={loading}
            className="flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs text-slate-300 transition hover:border-amber-300/40 hover:text-amber-100 disabled:opacity-50"
          >
            <RotateCw size={13} /> 刷新
          </button>
        </div>
      </header>

      <div className="mb-4 flex flex-wrap gap-2">
        {(['refunds', 'returns'] as Kind[]).map((value) => (
          <button
            key={value}
            onClick={() => setKind(value)}
            className={`rounded-full px-4 py-1.5 text-sm transition ${
              kind === value
                ? 'bg-amber-300/20 text-amber-100'
                : 'border border-white/10 bg-white/5 text-slate-400 hover:text-slate-200'
            }`}
          >
            {value === 'refunds' ? '退款' : '退货'}
          </button>
        ))}
        <span className="mx-2 w-px bg-white/10" />
        {(['REQUESTED', 'APPROVED', 'REJECTED'] as Status[]).map((value) => (
          <button
            key={value}
            onClick={() => setStatus(value)}
            className={`rounded-full px-4 py-1.5 text-sm transition ${
              status === value
                ? 'bg-cyan-300/20 text-cyan-100'
                : 'border border-white/10 bg-white/5 text-slate-400 hover:text-slate-200'
            }`}
          >
            {STATUS_LABEL[value]}
          </button>
        ))}
      </div>

      {error && (
        <p className="mb-3 rounded-xl border border-rose-400/30 bg-rose-400/10 px-3 py-2 text-sm text-rose-200">
          {error}
        </p>
      )}

      {items.length === 0 ? (
        <p className="glass rounded-3xl p-6 text-sm text-slate-400">
          {loading ? '加载中…' : `没有${STATUS_LABEL[status]}的${kind === 'refunds' ? '退款' : '退货'}申请。`}
        </p>
      ) : (
        <ul className="space-y-3">
          {items.map((item) => (
            <li key={item.id} className="glass rounded-3xl p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-sm text-slate-100">
                    <span className="font-mono text-cyan-200">{item.id}</span>
                    <span className="mx-2 text-slate-600">|</span>
                    订单 <span className="font-mono text-slate-300">{item.orderNo}</span>
                  </p>
                  <p className="mt-1 text-xs text-slate-500">
                    {item.ticketId ? `工单 ${item.ticketId}` : '无关联工单'} · 提交 {item.createdAt.replace('T', ' ').slice(0, 16)}
                    {item.reviewedBy ? ` · 审核人 ${item.reviewedBy}` : ''}
                  </p>
                  {item.reviewNote && (
                    <p className="mt-2 rounded-lg bg-slate-950/50 px-3 py-2 text-xs text-slate-300">
                      审核备注：{item.reviewNote}
                    </p>
                  )}
                </div>
                <span
                  className={`rounded-full px-3 py-1 text-xs ${
                    item.status === 'APPROVED'
                      ? 'bg-emerald-400/15 text-emerald-200'
                      : item.status === 'REJECTED'
                        ? 'bg-rose-400/15 text-rose-200'
                        : 'bg-amber-300/15 text-amber-100'
                  }`}
                >
                  {STATUS_LABEL[item.status] ?? item.status}
                </span>
              </div>

              {item.status === 'REQUESTED' && (
                <div className="mt-3 flex flex-col gap-2 md:flex-row md:items-center">
                  <input
                    value={notes[item.id] ?? ''}
                    onChange={(event) => setNotes((prev) => ({ ...prev, [item.id]: event.target.value }))}
                    placeholder="审核备注（驳回时必填，用户会看到）"
                    aria-label={`审核备注：${item.id}`}
                    className="flex-1 rounded-xl border border-white/10 bg-slate-950/50 px-3 py-2 text-sm text-slate-200 outline-none focus:border-cyan-300/40"
                  />
                  <div className="flex gap-2">
                    <button
                      onClick={() => void decide(item, 'approve')}
                      disabled={busy !== ''}
                      aria-label={`通过：${item.id}`}
                      className="flex items-center gap-1 rounded-xl bg-emerald-400/15 px-3 py-2 text-sm text-emerald-200 transition hover:bg-emerald-400/25 disabled:opacity-50"
                    >
                      <CheckCircle2 size={15} /> 通过
                    </button>
                    <button
                      onClick={() => void decide(item, 'reject')}
                      disabled={busy !== ''}
                      aria-label={`驳回：${item.id}`}
                      className="flex items-center gap-1 rounded-xl bg-rose-400/15 px-3 py-2 text-sm text-rose-200 transition hover:bg-rose-400/25 disabled:opacity-50"
                    >
                      <XCircle size={15} /> 驳回
                    </button>
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <p className="mt-4 text-xs text-slate-500">
        共 {items.length} 条，其中待审 {pending.length} 条。审核通过只代表"同意退款/同意寄回"，
        本项目**不动钱** —— 真实打款在支付系统完成；驳回必须写原因，用户会在对话里看到。
      </p>
    </div>
  )
}
