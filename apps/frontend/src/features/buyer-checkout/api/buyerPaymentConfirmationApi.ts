import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import { isBuyerPaymentConfirmation } from '../model/buyerPaymentConfirmation.ts'
import type { BuyerPaymentConfirmation } from '../model/buyerPaymentConfirmation.ts'
import type { TossSuccessParameters } from '../model/tossPaymentResult.ts'

export async function confirmBuyerPayment(
  body: TossSuccessParameters,
  buyerId: number,
  options?: ApiClientOptions,
): Promise<BuyerPaymentConfirmation> {
  if (!Number.isSafeInteger(buyerId) || buyerId <= 0) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  const response = await requestApi<unknown>('/api/payments/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Buyer-Id': String(buyerId) },
    body: JSON.stringify(body),
  }, options)
  if (!isBuyerPaymentConfirmation(response, body)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
