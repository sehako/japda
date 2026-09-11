import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { BuyerSaleProduct, BuyerSaleProductListResponse, BuyerSaleStatus } from '../model/buyerSale.ts'

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const INSTANT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z$/
const STATUSES = new Set<BuyerSaleStatus>(['UPCOMING', 'ON_SALE', 'ENDED'])

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isValidDate(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = DATE_PATTERN.exec(value)
  if (!match) return false
  const [year, month, day] = match.slice(1).map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

function isValidInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = INSTANT_PATTERN.exec(value)
  if (!match || !isValidDate(`${match[1]}-${match[2]}-${match[3]}`)) return false
  const [hour, minute, second] = match.slice(4, 7).map(Number)
  return hour <= 23 && minute <= 59 && second <= 59
}

function isBuyerSaleProduct(value: unknown): value is BuyerSaleProduct {
  if (typeof value !== 'object' || value === null) return false
  const sale = value as Record<string, unknown>
  return isPositiveInteger(sale.saleId)
    && isPositiveInteger(sale.productId)
    && typeof sale.name === 'string'
    && (sale.description === null || typeof sale.description === 'string')
    && isPositiveInteger(sale.price)
    && isPositiveInteger(sale.quantity)
    && isValidDate(sale.saleDate)
    && isValidInstant(sale.startsAt)
    && isValidInstant(sale.endsAt)
    && typeof sale.status === 'string' && STATUSES.has(sale.status as BuyerSaleStatus)
    && typeof sale.representativeImagePath === 'string' && sale.representativeImagePath.startsWith('/')
}

function isBuyerSaleProductListResponse(value: unknown): value is BuyerSaleProductListResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return Array.isArray(response.sales) && response.sales.every(isBuyerSaleProduct)
}

export async function fetchBuyerSales(
  saleDate: string,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<BuyerSaleProductListResponse> {
  const query = new URLSearchParams({ saleDate })
  const response = await requestApi<unknown>(`/api/sales?${query}`, { method: 'GET', signal }, options)
  if (!isBuyerSaleProductListResponse(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
