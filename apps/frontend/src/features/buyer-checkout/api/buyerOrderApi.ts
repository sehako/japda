import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { BuyerOrder, CreateBuyerOrderRequest } from '../model/buyerOrder.ts'

const canonicalUuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isBuyerOrder(value: unknown): value is BuyerOrder {
  if (typeof value !== 'object' || value === null) return false
  const order = value as Record<string, unknown>
  return isPositiveSafeInteger(order.orderId)
    && typeof order.paymentOrderId === 'string' && canonicalUuidV4.test(order.paymentOrderId)
    && order.status === 'PENDING_PAYMENT'
    && typeof order.productName === 'string' && order.productName.trim().length > 0
    && isPositiveSafeInteger(order.quantity) && order.quantity <= 2_147_483_647
    && isPositiveSafeInteger(order.unitPrice)
    && isPositiveSafeInteger(order.totalPrice)
    && typeof order.expiresAt === 'string' && Number.isFinite(Date.parse(order.expiresAt))
}

export async function createBuyerOrder(
  body: CreateBuyerOrderRequest,
  idempotencyKey: string,
  options?: ApiClientOptions,
): Promise<BuyerOrder> {
  if (!canonicalUuidV4.test(idempotencyKey)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  const response = await requestApi<unknown>('/api/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  }, { ...options, protected: true })
  if (!isBuyerOrder(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
