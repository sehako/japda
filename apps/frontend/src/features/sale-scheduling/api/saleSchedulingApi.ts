import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { CreateSaleRequest, CreateSaleResponse, ProductSort, ReadyProduct } from '../model/saleScheduling.ts'

export interface ReadyProductPage {
  items: ReadyProduct[]
  nextCursor: string | null
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isReadyProductPage(value: unknown): value is ReadyProductPage {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return Array.isArray(response.items)
    && (response.nextCursor === null || typeof response.nextCursor === 'string')
    && response.items.every((item) => typeof item === 'object' && item !== null
      && isPositiveInteger((item as Record<string, unknown>).id)
      && typeof (item as Record<string, unknown>).name === 'string')
}

function isCreateSaleResponse(value: unknown, request: CreateSaleRequest): value is CreateSaleResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return isPositiveInteger(response.id) && isPositiveInteger(response.sellerId)
    && response.productId === request.productId && response.saleDate === request.saleDate
    && response.price === request.price && response.quantity === request.quantity
    && typeof response.startsAt === 'string' && typeof response.endsAt === 'string' && typeof response.createdAt === 'string'
}

export async function fetchReadyProducts(sort: ProductSort, cursor: string | null, signal?: AbortSignal, options?: ApiClientOptions): Promise<ReadyProductPage> {
  const query = new URLSearchParams({ sort })
  if (cursor !== null) query.set('cursor', cursor)
  query.set('size', '20')
  const response = await requestApi<unknown>(`/api/products/ready?${query}`, { signal }, { ...options, protected: true })
  if (!isReadyProductPage(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}

export async function createSale(body: CreateSaleRequest, signal?: AbortSignal, options?: ApiClientOptions): Promise<CreateSaleResponse> {
  const response = await requestApi<unknown>('/api/sales', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal,
  }, { ...options, protected: true })
  if (!isCreateSaleResponse(response, body)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
