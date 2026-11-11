import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReviewConsole } from '../ReviewConsole'

/**
 * 售后审核台（2026-11-09）。
 *
 * 这层要保证：①列出待审申请并按状态筛选；②通过调用 POST …/approve；
 * ③驳回**必须填原因**（不填不发请求）；④审核后列表刷新。
 */
const pendingRefunds = [
  {
    id: 'RFD-AAAA1111',
    orderNo: 'ORD-AI-1111',
    customerId: 'CUST-1001',
    status: 'REQUESTED',
    ticketId: 'TCK-11111111',
    reviewNote: null,
    reviewedBy: null,
    reviewedAt: null,
    createdAt: '2026-11-09T13:00:00Z'
  }
]

const approvedRefunds = [
  {
    ...pendingRefunds[0],
    status: 'APPROVED',
    reviewNote: '同意退款',
    reviewedBy: 'staff',
    reviewedAt: '2026-11-09T13:10:00Z'
  }
]

describe('ReviewConsole 售后审核台', () => {
  const requests: string[] = []
  let refundPayload: unknown = pendingRefunds

  beforeEach(() => {
    requests.length = 0
    refundPayload = pendingRefunds
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
        if (init?.method === 'POST' && url.includes('/approve')) {
          refundPayload = approvedRefunds
          return new Response(JSON.stringify(approvedRefunds[0]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          })
        }
        if (init?.method === 'POST' && url.includes('/reject')) {
          refundPayload = []
          return new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        if (url.includes('/admin/after-sales/refunds')) {
          return new Response(JSON.stringify(refundPayload), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          })
        }
        return new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      })
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('列出待审退款申请', async () => {
    render(<ReviewConsole />)
    await waitFor(() => expect(screen.getByText('RFD-AAAA1111')).toBeTruthy())
    expect(screen.getByText(/ORD-AI-1111/)).toBeTruthy()
    expect(screen.getByText(/TCK-11111111/)).toBeTruthy()
    // 筛选按钮与行内徽标都会出现「待审核」，所以用 getAllByText 断言至少两处
    expect(screen.getAllByText('待审核').length).toBeGreaterThanOrEqual(2)
  })

  it('点通过会调用 approve，并在刷新后显示已通过', async () => {
    render(<ReviewConsole />)
    await waitFor(() => expect(screen.getByLabelText('通过：RFD-AAAA1111')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('通过：RFD-AAAA1111'))

    await waitFor(() =>
      expect(requests.some((request) => request.startsWith('POST') && request.includes('/approve'))).toBe(true)
    )
    await waitFor(() => expect(screen.getAllByText('已通过').length).toBeGreaterThanOrEqual(2))
  })

  it('驳回没填原因时不发请求，并提示必须填', async () => {
    render(<ReviewConsole />)
    await waitFor(() => expect(screen.getByLabelText('驳回：RFD-AAAA1111')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('驳回：RFD-AAAA1111'))

    await waitFor(() => expect(screen.getByText(/驳回必须填写原因/)).toBeTruthy())
    expect(requests.some((request) => request.startsWith('POST') && request.includes('/reject'))).toBe(false)
  })

  it('填了原因再驳回，会发 POST 到 reject', async () => {
    render(<ReviewConsole />)
    await waitFor(() => expect(screen.getByLabelText('审核备注：RFD-AAAA1111')).toBeTruthy())
    fireEvent.change(screen.getByLabelText('审核备注：RFD-AAAA1111'), {
      target: { value: '已超过退货时效' }
    })
    fireEvent.click(screen.getByLabelText('驳回：RFD-AAAA1111'))

    await waitFor(() =>
      expect(requests.some((request) => request.startsWith('POST') && request.includes('/reject'))).toBe(true)
    )
  })
})
