import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { OrderConfirmationCard } from '../components/OrderConfirmationCard'
import { useAssistantStore } from '../stores/assistantStore'
import type { OrderConfirmationPayload } from '../types'

/**
 * 多商品下单的卡片渲染：**一张卡、多行明细**（PROJECT_OVERVIEW §5.3）。
 *
 * 这一层是纯前端契约：后端把 items[] 推过来，卡片就必须逐行显示，
 * 否则用户又会以为"少下了一件"（2026-11-05 的真实反馈）。
 */
const twoItemConfirmation: OrderConfirmationPayload = {
  confirmationId: 'OCF-TEST-1',
  sessionId: 's-1',
  customerId: 'CUST-1001',
  message: '确认购买 米色帆布双肩包 × 1、藏青帆布鞋 × 1（合计 ¥498）吗？',
  items: [
    {
      productId: 'CLOTH-BAG-029',
      productName: '米色帆布双肩包',
      quantity: 1,
      unitPrice: 299,
      lineAmount: 299
    },
    {
      productId: 'CLOTH-CANVAS-028',
      productName: '藏青帆布鞋',
      quantity: 2,
      unitPrice: 199,
      lineAmount: 398
    }
  ],
  totalQuantity: 3,
  totalAmount: 697,
  address: { id: 'ADDR-1', display: '上海市浦东新区世纪大道 100 号' },
  paymentMethod: { id: 'PAY-1', display: '招商银行信用卡 **** 1001' },
  expiresAt: '2026-11-05T10:30:00Z'
}

describe('OrderConfirmationCard', () => {
  beforeEach(() => {
    useAssistantStore.setState({
      pendingOrderConfirmation: undefined,
      orderCreationResult: undefined,
      orderConfirmationError: undefined
    })
  })

  it('多商品确认单逐行渲染明细与合计', () => {
    useAssistantStore.setState({ pendingOrderConfirmation: twoItemConfirmation })

    render(<OrderConfirmationCard />)

    expect(screen.getByText('高风险操作确认')).toBeDefined()
    // 两行明细都在（旧版只显示第一件的 productName，第二件直接消失）
    expect(screen.getByText('米色帆布双肩包')).toBeDefined()
    expect(screen.getByText('藏青帆布鞋')).toBeDefined()
    expect(screen.getByText('共 3 件，总价 ¥697.00')).toBeDefined()
    // 行小计用后端给的 lineAmount，不在前端重算
    expect(screen.getByText('¥398.00')).toBeDefined()
  })

  it('没有待确认单且无结果时不渲染任何东西', () => {
    const { container } = render(<OrderConfirmationCard />)
    expect(container.innerHTML).toBe('')
  })
})
