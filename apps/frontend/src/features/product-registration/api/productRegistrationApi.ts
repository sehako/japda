import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'

export interface CreateProductRequest {
  name: string
  description: string | null
}

export interface CreateProductResponse {
  id: number
  sellerId: number
  name: string
  description: string | null
  status: 'DRAFT'
  createdAt: string
}

export interface ProductImageResponse {
  id: number
  displayOrder: number
  isRepresentative: boolean
}

export interface RegisterProductImagesResponse {
  productId: number
  status: 'READY'
  images: ProductImageResponse[]
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isCreateProductResponse(value: unknown): value is CreateProductResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return isPositiveInteger(response.id) && response.status === 'DRAFT'
}

function isRegisterImagesResponse(value: unknown, productId: number): value is RegisterProductImagesResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return response.productId === productId && response.status === 'READY' && Array.isArray(response.images)
}

export async function createProduct(
  body: CreateProductRequest,
  sellerId: number,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<CreateProductResponse> {
  const response = await requestApi<unknown>('/api/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Seller-Id': String(sellerId) },
    body: JSON.stringify(body),
    signal,
  }, options)
  if (!isCreateProductResponse(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}

export async function registerProductImages(
  productId: number,
  files: File[],
  representativeIndex: number,
  sellerId: number,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<RegisterProductImagesResponse> {
  const body = new FormData()
  for (const file of files) body.append('files', file)
  body.append('representativeIndex', String(representativeIndex))
  const response = await requestApi<unknown>(`/api/products/${productId}/images`, {
    method: 'POST',
    headers: { 'X-Seller-Id': String(sellerId) },
    body,
    signal,
  }, options)
  if (!isRegisterImagesResponse(response, productId)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
