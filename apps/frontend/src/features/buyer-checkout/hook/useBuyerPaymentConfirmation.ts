import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'

import { ApiError } from '../../../shared/api/apiClient.ts'
import { apiBaseUrl } from '../../../shared/config/env.ts'
import { confirmBuyerPayment } from '../api/buyerPaymentConfirmationApi.ts'
import type { BuyerPaymentConfirmation } from '../model/buyerPaymentConfirmation.ts'
import type { TossSuccessParameters } from '../model/tossPaymentResult.ts'

export type BuyerPaymentConfirmationState =
  | { kind: 'confirming' | 'processing' | 'expired' }
  | { kind: 'paid'; payment: BuyerPaymentConfirmation }
  | { kind: 'failed'; reason: 'rejected' }
  | { kind: 'review'; reason: 'timeout' | 'unavailable' | 'manual' }
  | { kind: 'invalid'; reason: 'invalid' | 'rejected' }

function classifyConfirmationError(error: unknown): BuyerPaymentConfirmationState {
  if (!(error instanceof ApiError)) return { kind: 'review', reason: 'unavailable' }
  if (error.isNetworkError) return { kind: 'review', reason: 'unavailable' }

  switch (error.code) {
    case 'PAYMENT_CONFIRMATION_IN_PROGRESS':
      return { kind: 'processing' }
    case 'PAYMENT_CONFIRMATION_FAILED':
      return { kind: 'failed', reason: 'rejected' }
    case 'PAYMENT_ORDER_EXPIRED':
      return { kind: 'expired' }
    case 'PAYMENT_REVIEW_REQUIRED':
      return { kind: 'review', reason: 'manual' }
    case 'PAYMENT_CONFIRMATION_UNAVAILABLE':
      return { kind: 'review', reason: 'unavailable' }
    case 'PAYMENT_ORDER_NOT_FOUND':
    case 'PAYMENT_AMOUNT_MISMATCH':
    case 'PAYMENT_KEY_CONFLICT':
      return { kind: 'invalid', reason: 'rejected' }
  }

  if (error.code && error.status && error.status >= 400 && error.status < 500) {
    return { kind: 'invalid', reason: 'rejected' }
  }
  return { kind: 'review', reason: 'unavailable' }
}

export function useBuyerPaymentConfirmation(params: TossSuccessParameters | null, buyerId: number | null): BuyerPaymentConfirmationState {
  const paymentKey = params?.paymentKey
  const orderId = params?.orderId
  const amount = params?.amount
  const valid = params !== null && buyerId !== null && Number.isSafeInteger(buyerId) && buyerId > 0
  const key = JSON.stringify([paymentKey, orderId, amount, buyerId])
  const initialState: BuyerPaymentConfirmationState = valid ? { kind: 'confirming' } : { kind: 'invalid', reason: 'invalid' }
  const [tracked, setTracked] = useState<{ key: string; value: BuyerPaymentConfirmationState }>(() => ({ key, value: initialState }))
  const { mutateAsync } = useMutation({
    mutationFn: ({ body, id }: { body: TossSuccessParameters; id: number }) => confirmBuyerPayment(body, id, { baseUrl: apiBaseUrl }),
    retry: false,
  })

  useEffect(() => {
    if (!valid || paymentKey === undefined || orderId === undefined || amount === undefined || buyerId === null) return
    let disposed = false
    let timer: ReturnType<typeof setTimeout> | null = null
    const body = { paymentKey, orderId, amount }
    const id = buyerId

    function update(value: BuyerPaymentConfirmationState) {
      if (!disposed) setTracked({ key, value })
    }

    async function attempt(additionalCount: number) {
      if (disposed) return
      try {
        const payment = await mutateAsync({ body, id })
        update({ kind: 'paid', payment })
      } catch (error) {
        if (disposed) return
        const next = classifyConfirmationError(error)
        if (next.kind !== 'processing') {
          update(next)
          return
        }
        if (additionalCount >= 5) {
          update({ kind: 'review', reason: 'timeout' })
          return
        }
        update(next)
        timer = setTimeout(() => {
          timer = null
          void attempt(additionalCount + 1)
        }, 15_000)
      }
    }

    update({ kind: 'confirming' })
    void Promise.resolve().then(() => { if (!disposed) void attempt(0) })

    return () => {
      disposed = true
      if (timer !== null) clearTimeout(timer)
    }
  }, [paymentKey, orderId, amount, buyerId, key, valid, mutateAsync])

  return tracked.key === key ? tracked.value : initialState
}
