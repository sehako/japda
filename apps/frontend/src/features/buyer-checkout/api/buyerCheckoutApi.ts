import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { BuyerCheckout, CreateShippingAddressRequest, ShippingAddress } from '../model/buyerCheckout.ts'

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isShippingAddress(value: unknown): value is ShippingAddress {
  if (typeof value !== 'object' || value === null) return false
  const address = value as Record<string, unknown>
  return isPositiveInteger(address.shippingAddressId)
    && ['addressName', 'recipientName', 'phoneNumber', 'postalCode', 'address', 'detailAddress']
      .every((field) => typeof address[field] === 'string' && (address[field] as string).length > 0)
    && (address.deliveryMessage === null || typeof address.deliveryMessage === 'string')
}

function isBuyerCheckout(value: unknown, saleId: number, quantity: number): value is BuyerCheckout {
  if (typeof value !== 'object' || value === null) return false
  const checkout = value as Record<string, unknown>
  return checkout.saleId === saleId
    && checkout.quantity === quantity
    && typeof checkout.productName === 'string' && checkout.productName.length > 0
    && typeof checkout.representativeImagePath === 'string' && checkout.representativeImagePath.startsWith('/')
    && isPositiveInteger(checkout.unitPrice)
    && isPositiveInteger(checkout.totalPrice)
    && Array.isArray(checkout.shippingAddresses)
    && checkout.shippingAddresses.every(isShippingAddress)
}

export async function fetchBuyerCheckout(
  saleId: number,
  quantity: number,
  buyerId: number,
  signal?: AbortSignal,
  options?: ApiClientOptions,
): Promise<BuyerCheckout> {
  if (![saleId, quantity, buyerId].every(isPositiveInteger) || quantity > 2_147_483_647) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  const query = new URLSearchParams({ saleId: String(saleId), quantity: String(quantity) })
  const response = await requestApi<unknown>(`/api/checkout?${query}`, {
    method: 'GET', headers: { 'X-Buyer-Id': String(buyerId) }, signal,
  }, options)
  if (!isBuyerCheckout(response, saleId, quantity)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}

export async function createShippingAddress(
  body: CreateShippingAddressRequest,
  buyerId: number,
  options?: ApiClientOptions,
): Promise<ShippingAddress> {
  if (!isPositiveInteger(buyerId)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  const response = await requestApi<unknown>('/api/shipping-addresses', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Buyer-Id': String(buyerId) },
    body: JSON.stringify(body),
  }, options)
  if (!isShippingAddress(response)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
