import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { BuyerOrder, BuyerOrderPage, BuyerOrderStatus } from '../model/buyerOrderHistory.ts'

export const BUYER_ORDER_HISTORY_PAGE_SIZE = 20

const BUYER_ORDER_STATUSES = new Set<BuyerOrderStatus>(['PENDING_PAYMENT', 'PAID'])

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isParseableInstant(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function isBuyerOrder(value: unknown): value is BuyerOrder {
  if (typeof value !== 'object' || value === null) return false
  const order = value as Record<string, unknown>
  return isPositiveSafeInteger(order.orderId)
    && typeof order.status === 'string' && BUYER_ORDER_STATUSES.has(order.status as BuyerOrderStatus)
    && typeof order.productName === 'string'
    && isPositiveSafeInteger(order.quantity)
    && isPositiveSafeInteger(order.unitPrice)
    && isPositiveSafeInteger(order.totalPrice)
    && isParseableInstant(order.createdAt)
    && isParseableInstant(order.expiresAt)
}

function isBuyerOrderPage(value: unknown): value is BuyerOrderPage {
  if (typeof value !== 'object' || value === null) return false
  const page = value as Record<string, unknown>
  if (!Array.isArray(page.items) || !page.items.every(isBuyerOrder)) return false
  if (page.nextCursor !== null && typeof page.nextCursor !== 'string') return false
  return page.items.length > 0 || page.nextCursor === null
}

export async function getBuyerOrderHistoryPage(
  cursor?: string,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<BuyerOrderPage> {
  const query = new URLSearchParams({ size: String(BUYER_ORDER_HISTORY_PAGE_SIZE) })
  if (cursor !== undefined) query.set('cursor', cursor)
  const response = await requestApi<unknown>(`/api/orders?${query}`, {
    method: 'GET',
    signal,
  }, { ...options, protected: true })
  if (!isBuyerOrderPage(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
