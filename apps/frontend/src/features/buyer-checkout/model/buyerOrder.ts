import type { BuyerCheckout, ShippingAddress } from './buyerCheckout.ts'

export interface CreateBuyerOrderRequest {
  saleId: number
  quantity: number
  shippingAddress: Pick<ShippingAddress, 'recipientName' | 'phoneNumber' | 'postalCode' | 'address' | 'detailAddress' | 'deliveryMessage'>
}

export interface BuyerOrder {
  orderId: number
  paymentOrderId: string
  status: BuyerOrderStatus
  productName: string
  quantity: number
  unitPrice: number
  totalPrice: number
  expiresAt: string
}

export type BuyerOrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'EXPIRED' | 'PAYMENT_FAILED'

export type BuyerOrderCheck = 'valid' | 'changed' | 'expired' | 'invalid'

export function createBuyerOrderRequest(checkout: BuyerCheckout, selectedAddress: ShippingAddress): CreateBuyerOrderRequest {
  const { recipientName, phoneNumber, postalCode, address, detailAddress, deliveryMessage } = selectedAddress
  return {
    saleId: checkout.saleId,
    quantity: checkout.quantity,
    shippingAddress: { recipientName, phoneNumber, postalCode, address, detailAddress, deliveryMessage },
  }
}

export function checkBuyerOrder(order: BuyerOrder, checkout: BuyerCheckout, now = Date.now()): BuyerOrderCheck {
  if (order.status === 'PAID') return 'invalid'
  if (order.status === 'EXPIRED' || order.status === 'PAYMENT_FAILED') return 'expired'
  if (order.productName.length > 100 || !Number.isSafeInteger(order.totalPrice) || order.totalPrice <= 0) return 'invalid'
  if (Date.parse(order.expiresAt) <= now) return 'expired'
  if (order.quantity !== checkout.quantity || order.productName !== checkout.productName || order.totalPrice !== checkout.totalPrice) return 'changed'
  return 'valid'
}
