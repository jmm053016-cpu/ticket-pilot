import type { ConversationTurnView, SessionSummary } from '../types'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

/** 演示客户：真实项目这里来自登录态。 */
export const CUSTOMER_ID = 'CUST-1001'
const TOKEN_KEY = 'ticketpilot.token'

let token: string | null = typeof localStorage === 'undefined' ? null : localStorage.getItem(TOKEN_KEY)

/**
 * 换取/复用访问令牌（2026-11-09）。
 *
 * 后端现在**只信令牌里的客户**：没有令牌，`/assistant/**` 一律 401。
 * 演示环境没有密码，`POST /auth/login` 只校验客户 ID 存在（见 README「鉴权」一节）。
 */
export async function ensureToken(force = false): Promise<string> {
  if (token && !force) return token
  const response = await fetch(`${API_BASE_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customerId: CUSTOMER_ID })
  })
  if (!response.ok) throw new Error('登录失败：无法获取访问令牌')
  const payload = await response.json()
  token = String(payload.token)
  if (typeof localStorage !== 'undefined') localStorage.setItem(TOKEN_KEY, token)
  return token
}

export function currentToken(): string | null {
  return token
}

/** 带令牌的请求；遇到 401 自动换一次令牌（令牌过期/被注销的场景）。 */
async function authorizedFetch(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const bearer = await ensureToken()
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${bearer}`)
  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  if (response.status === 401 && retry) {
    await ensureToken(true)
    return authorizedFetch(path, init, false)
  }
  return response
}

/** SSE 用：EventSource 不能自定义请求头，令牌只能进 URL。 */
export async function assistantStreamUrl(message: string, customerId = CUSTOMER_ID, orderNo = '', sessionId = '') {
  const bearer = await ensureToken()
  const params = new URLSearchParams({ message, customerId, token: bearer })
  if (orderNo) params.set('orderNo', orderNo)
  // sessionId 是多轮记忆的锚点：必须由前端固定复用，否则后端每次都当新会话
  if (sessionId) params.set('sessionId', sessionId)
  return `${API_BASE_URL}/assistant/stream?${params.toString()}`
}

export async function confirmOrderConfirmation(confirmationId: string, customerId: string, sessionId: string) {
  const response = await authorizedFetch(`/assistant/order-confirmations/${confirmationId}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customerId, sessionId })
  })
  if (!response.ok) throw new Error((await response.json()).message || '确认下单失败')
  return response.json()
}

export async function cancelOrderConfirmation(confirmationId: string, customerId: string, sessionId: string) {
  const response = await authorizedFetch(`/assistant/order-confirmations/${confirmationId}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customerId, sessionId })
  })
  if (!response.ok) throw new Error((await response.json()).message || '取消下单失败')
  return response.json()
}

export async function fetchSessions(customerId = CUSTOMER_ID): Promise<SessionSummary[]> {
  const response = await authorizedFetch(`/assistant/sessions?customerId=${encodeURIComponent(customerId)}`)
  if (!response.ok) throw new Error('加载会话列表失败')
  return response.json()
}

/** 删除一个会话及其消息（会话侧栏的删除按钮）。 */
export async function deleteSession(sessionId: string, customerId = CUSTOMER_ID): Promise<void> {
  const response = await authorizedFetch(
    `/assistant/sessions/${encodeURIComponent(sessionId)}?customerId=${encodeURIComponent(customerId)}`,
    { method: 'DELETE' }
  )
  if (!response.ok) throw new Error((await response.json()).message || '删除会话失败')
}

export async function fetchSessionTurns(sessionId: string): Promise<ConversationTurnView[]> {
  const response = await authorizedFetch(
    `/assistant/sessions/${encodeURIComponent(sessionId)}/messages?limit=50&customerId=${encodeURIComponent(CUSTOMER_ID)}`
  )
  if (!response.ok) throw new Error('加载会话历史失败')
  const payload = await response.json()
  return Array.isArray(payload.turns) ? payload.turns : []
}

// ===================== 购物车（2026-11-09，页面侧） =====================

export interface CartLine {
  productId: string
  productName: string
  unitPrice: number
  quantity: number
  lineAmount: number
}

export interface CartView {
  lines: CartLine[]
  totalQuantity: number
  totalAmount: number
  empty: boolean
}

export async function fetchCart(): Promise<CartView> {
  const response = await authorizedFetch(`/assistant/cart?customerId=${encodeURIComponent(CUSTOMER_ID)}`)
  if (!response.ok) throw new Error('加载购物车失败')
  return response.json()
}

export async function updateCartQuantity(productId: string, quantity: number): Promise<CartView> {
  const response = await authorizedFetch(`/assistant/cart/items/${encodeURIComponent(productId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ quantity })
  })
  if (!response.ok) throw new Error((await response.json()).message || '修改数量失败')
  return response.json()
}

export async function removeCartItem(productId: string): Promise<CartView> {
  const response = await authorizedFetch(`/assistant/cart/items/${encodeURIComponent(productId)}`, { method: 'DELETE' })
  if (!response.ok) throw new Error('移除商品失败')
  return response.json()
}

export async function clearCart(): Promise<CartView> {
  const response = await authorizedFetch(`/assistant/cart?customerId=${encodeURIComponent(CUSTOMER_ID)}`, {
    method: 'DELETE'
  })
  if (!response.ok) throw new Error('清空购物车失败')
  return response.json()
}

// ===================== 售后审核台（2026-11-09，V13） =====================

export interface ReviewItem {
  id: string
  orderNo: string
  customerId: string
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED' | string
  ticketId: string | null
  reviewNote: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  createdAt: string
}

export interface ReviewSummary {
  pendingRefunds: number
  pendingReturns: number
  approvedRefunds: number
  rejectedRefunds: number
}

async function reviewFetch(path: string, init: RequestInit = {}): Promise<Response> {
  return authorizedFetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) } })
}

export async function fetchReviewSummary(): Promise<ReviewSummary> {
  const response = await reviewFetch('/admin/after-sales/summary')
  if (!response.ok) throw new Error('加载审核概览失败')
  return response.json()
}

export async function fetchRefundRequests(status = 'REQUESTED'): Promise<ReviewItem[]> {
  const response = await reviewFetch(`/admin/after-sales/refunds?status=${encodeURIComponent(status)}`)
  if (!response.ok) throw new Error('加载退款申请失败')
  return response.json()
}

export async function fetchReturnRequests(status = 'REQUESTED'): Promise<ReviewItem[]> {
  const response = await reviewFetch(`/admin/after-sales/returns?status=${encodeURIComponent(status)}`)
  if (!response.ok) throw new Error('加载退货申请失败')
  return response.json()
}

async function decide(kind: 'refunds' | 'returns', id: string, action: 'approve' | 'reject', note: string) {
  const response = await reviewFetch(`/admin/after-sales/${kind}/${encodeURIComponent(id)}/${action}`, {
    method: 'POST',
    body: JSON.stringify({ note })
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    throw new Error(payload.message || (action === 'approve' ? '审核通过失败' : '驳回失败'))
  }
  return response.json() as Promise<ReviewItem>
}

export async function approveRefund(id: string, note = ''): Promise<ReviewItem> {
  return decide('refunds', id, 'approve', note)
}

export async function rejectRefund(id: string, note: string): Promise<ReviewItem> {
  return decide('refunds', id, 'reject', note)
}

export async function approveReturn(id: string, note = ''): Promise<ReviewItem> {
  return decide('returns', id, 'approve', note)
}

export async function rejectReturn(id: string, note: string): Promise<ReviewItem> {
  return decide('returns', id, 'reject', note)
}