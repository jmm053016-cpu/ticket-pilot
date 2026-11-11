import { useCallback, useEffect, useState } from 'react'
import { Minus, Plus, ShoppingCart, Trash2 } from 'lucide-react'
import { clearCart, fetchCart, removeCartItem, updateCartQuantity } from '../api/client'
import type { CartView } from '../api/client'
import { useAssistantStore } from '../stores/assistantStore'

const EMPTY: CartView = { lines: [], totalQuantity: 0, totalAmount: 0, empty: true }

/**
 * 购物车面板（2026-11-09）。
 *
 * 为什么要有：对话里只支持"加购 / 查看 / 结算"，移除与改数量会被引导到"购物车页面" ——
 * 这个面板就是那个页面：真的能改数量、能移除、能清空。
 *
 * 刷新时机：挂载时 + 每轮回答结束后（用户可能在对话里刚加购/刚下单清车）。
 */
export function CartPanel() {
  const [cart, setCart] = useState<CartView>(EMPTY)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  // 每轮回答结束（events 增长且 streaming 结束）就刷新一次：用户可能在对话里刚加购/刚下单清车
  const { streaming, events } = useAssistantStore()
  const refreshKey = events.length

  const load = useCallback(async () => {
    try {
      setCart(await fetchCart())
      setError('')
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载购物车失败')
    }
  }, [])

  useEffect(() => {
    if (streaming) return
    void load()
  }, [refreshKey, streaming, load])

  const act = async (key: string, action: () => Promise<CartView>) => {
    setBusy(key)
    try {
      setCart(await action())
      setError('')
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setBusy('')
    }
  }

  return (
    <div className="glass rounded-[2rem] p-5">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm text-amber-200">
          <ShoppingCart size={16} /> 购物车
          {cart.totalQuantity > 0 && (
            <span className="rounded-full bg-amber-300/15 px-2 py-0.5 text-xs text-amber-100">
              {cart.totalQuantity} 件
            </span>
          )}
        </div>
        {!cart.empty && (
          <button
            onClick={() => void act('clear', clearCart)}
            disabled={busy !== ''}
            className="text-xs text-slate-500 transition hover:text-rose-200 disabled:opacity-40"
          >
            清空
          </button>
        )}
      </div>

      {error && <p className="mb-2 text-xs text-rose-300">{error}</p>}

      {cart.empty ? (
        <p className="text-xs leading-6 text-slate-500">
          购物车是空的。在左侧说「把米色帆布双肩包加入购物车」就能放进来。
        </p>
      ) : (
        <ul className="space-y-3">
          {cart.lines.map((line) => (
            <li key={line.productId} className="rounded-2xl border border-white/5 bg-slate-950/40 p-3">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm text-slate-200">{line.productName}</p>
                  <p className="text-xs text-slate-500">
                    ¥{line.unitPrice.toFixed(2)} × {line.quantity} = ¥{line.lineAmount.toFixed(2)}
                  </p>
                </div>
                <button
                  onClick={() => void act(`remove-${line.productId}`, () => removeCartItem(line.productId))}
                  disabled={busy !== ''}
                  aria-label={`移除：${line.productName}`}
                  className="rounded-lg p-1 text-slate-500 transition hover:text-rose-300 disabled:opacity-40"
                >
                  <Trash2 size={14} />
                </button>
              </div>
              <div className="mt-2 flex items-center gap-2">
                <button
                  onClick={() =>
                    void act(`dec-${line.productId}`, () => updateCartQuantity(line.productId, line.quantity - 1))
                  }
                  disabled={busy !== ''}
                  aria-label={`减少：${line.productName}`}
                  className="rounded-lg border border-white/10 p-1 text-slate-400 transition hover:text-cyan-200 disabled:opacity-40"
                >
                  <Minus size={12} />
                </button>
                <span className="text-xs text-slate-300">{line.quantity}</span>
                <button
                  onClick={() =>
                    void act(`inc-${line.productId}`, () => updateCartQuantity(line.productId, line.quantity + 1))
                  }
                  disabled={busy !== ''}
                  aria-label={`增加：${line.productName}`}
                  className="rounded-lg border border-white/10 p-1 text-slate-400 transition hover:text-cyan-200 disabled:opacity-40"
                >
                  <Plus size={12} />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {!cart.empty && (
        <p className="mt-3 border-t border-white/5 pt-3 text-xs text-slate-400">
          合计 ¥{cart.totalAmount.toFixed(2)} · 说「把购物车里的都下单」生成待确认单
        </p>
      )}
    </div>
  )
}
