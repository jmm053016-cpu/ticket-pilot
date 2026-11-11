export type AssistantEvent = {
  type: string
  message: string
  payload: Record<string, unknown>
  timestamp: string
}

export type AgentCard = {
  name: string
  status: 'idle' | 'running' | 'done' | 'error'
  detail: string
}

export type ToolCard = {
  name: string
  status: 'requested' | 'success' | 'error'
  payload: unknown
}

/** 确认卡片上的一行商品（一张确认单可含多件，见 PROJECT_OVERVIEW §5.3） */
export type OrderConfirmationItemPayload = {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  lineAmount: number
}

export type OrderConfirmationPayload = {
  confirmationId: string
  sessionId: string
  customerId: string
  message: string
  items: OrderConfirmationItemPayload[]
  totalQuantity: number
  totalAmount: number
  address: { id: string; display: string }
  paymentMethod: { id: string; display: string; methodType?: string }
  expiresAt: string
}

export type OrderCreationResult = {
  orderNo: string
  orderStatus: string
  paymentStatus: string
  amount: number
  /** 下单成功后的明细（一单可多件） */
  items?: Array<Record<string, unknown>>
  message: string
}

export type SourceCard = {
  id?: string
  title?: string
  source?: string
  content?: string
  score?: number
  matchReason?: string
}

export type SessionSummary = {
  sessionId: string
  preview: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

export type ConversationTurnView = {
  userMessage: string
  assistantAnswer: string
}