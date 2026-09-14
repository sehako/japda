import { describe, expect, test, vi } from 'vitest'

import { confirmBuyerPayment } from '../../../../src/features/buyer-checkout/api/buyerPaymentConfirmationApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const body = { paymentKey: 'pay_123', orderId: 'order-123', amount: 70000 }
const response = { orderId: 9, paymentOrderId: 'order-123', status: 'PAID', totalAmount: 70000, approvedAt: '2026-09-13T06:00:00Z' }

describe('구매자 결제 승인 API', () => {
  test('구매자 식별자와 토스 결제 정보를 승인 경로에 보내고 서버의 확정 응답을 반환한다', async () => {
    let request: Request | undefined
    const fetcher: typeof fetch = async (input, init) => {
      request = new Request(input, init)
      return Response.json(response)
    }
    await expect(confirmBuyerPayment(body, 42, { baseUrl: 'http://localhost:8080/', fetcher })).resolves.toEqual(response)
    expect(request?.url).toBe('http://localhost:8080/api/payments/confirm')
    expect(request?.method).toBe('POST')
    expect(request?.headers.get('Content-Type')).toBe('application/json')
    expect(request?.headers.get('X-Buyer-Id')).toBe('42')
    expect(request?.headers.has('Idempotency-Key')).toBe(false)
    expect(await request?.json()).toEqual(body)
  })

  test.each([
    null,
    { ...response, orderId: undefined },
    { ...response, orderId: 0 },
    { ...response, orderId: 1.5 },
    { ...response, orderId: Number.MAX_SAFE_INTEGER + 1 },
    { ...response, paymentOrderId: 'other-order' },
    { ...response, paymentOrderId: undefined },
    { ...response, status: 'PENDING_PAYMENT' },
    { ...response, status: undefined },
    { ...response, totalAmount: 0 },
    { ...response, totalAmount: 70001 },
    { ...response, totalAmount: Number.MAX_SAFE_INTEGER + 1 },
    { ...response, approvedAt: 'invalid' },
    { ...response, approvedAt: null },
    { ...response, approvedAt: undefined },
  ])('계약에서 벗어난 승인 응답을 결과로 반환하지 않는다', async (invalid) => {
    try {
      await confirmBuyerPayment(body, 42, { fetcher: async () => Response.json(invalid) })
      throw new Error('잘못된 응답이 반환됨')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).code).toBeUndefined()
    }
  })

  test.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('잘못된 구매자 식별자 %s에서는 요청하지 않는다', async (buyerId) => {
    const fetcher = vi.fn<typeof fetch>()
    await expect(confirmBuyerPayment(body, buyerId, { fetcher })).rejects.toBeInstanceOf(ApiError)
    expect(fetcher).not.toHaveBeenCalled()
  })

  test('서버 오류 코드를 변경하지 않고 전달한다', async () => {
    const fetcher: typeof fetch = async () => Response.json(
      { code: 'PAYMENT_CONFIRMATION_IN_PROGRESS', detail: '처리 중' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )
    await expect(confirmBuyerPayment(body, 42, { fetcher })).rejects.toMatchObject({
      code: 'PAYMENT_CONFIRMATION_IN_PROGRESS', status: 409,
    })
  })
})
