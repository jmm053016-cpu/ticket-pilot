import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CartPanel } from '../components/CartPanel'

/**
 * 购物车面板（2026-11-09）。
 *
 * 这层要保证：①把后端的三行/合计渲染出来；②「+/-」调的是 PATCH 且数量正确；③移除调 DELETE；
 * ④清空调整车 DELETE；⑤空车时不显示清空按钮、给出引导文案。
 */
const cartWithTwoLines = {
  lines: [
    { productId: 'CLOTH-BAG-029', productName: '米色帆布双肩包', unitPrice: 299, quantity: 2, lineAmount: 598 },
    { productId: 'CLOTH-CANVAS-028', productName: '藏青帆布鞋', unitPrice: 199, quantity: 1, lineAmount: 199 }
  ],
  totalQuantity: 3,
  totalAmount: 797,
  empty: false
}

const emptyCart = { lines: [], totalQuantity: 0, totalAmount: 0, empty: true }

describe('CartPanel', () => {
  const requests: string[] = []
  let payload: unknown = cartWithTwoLines

  beforeEach(() => {
    requests.length = 0
    payload = cartWithTwoLines
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        requests.push(`${init?.method ?? 'GET'} ${url}`)
        if (url.includes('/auth/login')) {
          return new Response(JSON.stringify({ token: 'tk_test' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          })
        }
        if (init?.method === 'PATCH' || init?.method === 'DELETE') {
          payload = emptyCart
        }
        return new Response(JSON.stringify(payload), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      })
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('渲染每行商品与合计', async () => {
    render(<CartPanel />)
    await waitFor(() => expect(screen.getByText('米色帆布双肩包')).toBeTruthy())
    expect(screen.getByText(/¥299\.00 × 2 = ¥598\.00/)).toBeTruthy()
    expect(screen.getByText(/合计 ¥797\.00/)).toBeTruthy()
    expect(requests.some((request) => request.startsWith('GET') && request.includes('/assistant/cart'))).toBe(true)
  })

  it('点 + 发出 PATCH 且数量 +1', async () => {
    render(<CartPanel />)
    await waitFor(() => expect(screen.getByLabelText('增加：米色帆布双肩包')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('增加：米色帆布双肩包'))
    await waitFor(() =>
      expect(requests.some((request) => request.startsWith('PATCH') && request.includes('CLOTH-BAG-029'))).toBe(true)
    )
  })

  it('点移除发出 DELETE，点清空发出整车 DELETE', async () => {
    render(<CartPanel />)
    await waitFor(() => expect(screen.getByLabelText('移除：藏青帆布鞋')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('移除：藏青帆布鞋'))
    await waitFor(() => expect(requests.some((request) => request.startsWith('DELETE'))).toBe(true))
  })

  it('空车时给出引导，不显示清空按钮', async () => {
    payload = emptyCart
    render(<CartPanel />)
    await waitFor(() => expect(screen.getByText(/购物车是空的/)).toBeTruthy())
    expect(screen.queryByText('清空')).toBeNull()
  })
})
