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
  return isPositiveInteger(response.id) && isPositiveInteger(response.sellerId)
    && typeof response.name === 'string' && response.status === 'DRAFT'
}

function isRegisterImagesResponse(value: unknown, productId: number): value is RegisterProductImagesResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return response.productId === productId && response.status === 'READY' && Array.isArray(response.images)
}

export async function createProduct(
  body: CreateProductRequest,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<CreateProductResponse> {
  const response = await requestApi<unknown>('/api/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  }, { ...options, protected: true })
  if (!isCreateProductResponse(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}

export async function registerProductImages(
  productId: number,
  files: File[],
  representativeIndex: number,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<RegisterProductImagesResponse> {
  const body = new FormData()
  for (const file of files) body.append('files', file)
  body.append('representativeIndex', String(representativeIndex))
  const response = await requestApi<unknown>(`/api/products/${productId}/images`, {
    method: 'POST',
    body,
    signal,
  }, { ...options, protected: true })
  if (!isRegisterImagesResponse(response, productId)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
