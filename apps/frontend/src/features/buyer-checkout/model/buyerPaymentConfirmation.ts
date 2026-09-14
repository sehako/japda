import type { TossSuccessParameters } from './tossPaymentResult.ts'

export interface BuyerPaymentConfirmation {
  orderId: number
  paymentOrderId: string
  status: 'PAID'
  totalAmount: number
  approvedAt: string
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

export function isBuyerPaymentConfirmation(value: unknown, request: TossSuccessParameters): value is BuyerPaymentConfirmation {
  if (typeof value !== 'object' || value === null) return false
  const confirmation = value as Record<string, unknown>
  return isPositiveSafeInteger(confirmation.orderId)
    && confirmation.paymentOrderId === request.orderId
    && confirmation.status === 'PAID'
    && isPositiveSafeInteger(confirmation.totalAmount)
    && confirmation.totalAmount === request.amount
    && typeof confirmation.approvedAt === 'string'
    && Number.isFinite(Date.parse(confirmation.approvedAt))
}
