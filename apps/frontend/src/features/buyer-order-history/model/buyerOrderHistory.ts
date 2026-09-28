export type BuyerOrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'EXPIRED' | 'PAYMENT_FAILED'

export interface BuyerOrder {
  orderId: number
  status: BuyerOrderStatus
  productName: string
  quantity: number
  unitPrice: number
  totalPrice: number
  createdAt: string
  expiresAt: string
}

export interface BuyerOrderPage {
  items: BuyerOrder[]
  nextCursor: string | null
}
