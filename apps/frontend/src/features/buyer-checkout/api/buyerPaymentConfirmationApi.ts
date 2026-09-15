import { ApiError, DEFAULT_API_ERROR_MESSAGE, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import { isBuyerPaymentConfirmation } from '../model/buyerPaymentConfirmation.ts'
import type { BuyerPaymentConfirmation } from '../model/buyerPaymentConfirmation.ts'
import type { TossSuccessParameters } from '../model/tossPaymentResult.ts'

export async function confirmBuyerPayment(
  body: TossSuccessParameters,
  options?: ApiClientOptions,
): Promise<BuyerPaymentConfirmation> {
  const response = await requestApi<unknown>('/api/payments/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, { ...options, protected: true })
  if (!isBuyerPaymentConfirmation(response, body)) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
  return response
}
