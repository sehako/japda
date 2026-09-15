import { describe, expect, test } from 'vitest'

import { confirmBuyerPayment } from '../../../../src/features/buyer-checkout/api/buyerPaymentConfirmationApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const body = { paymentKey: 'pay_123', orderId: 'order-123', amount: 70000 }
const response = { orderId: 9, paymentOrderId: 'order-123', status: 'PAID', totalAmount: 70000, approvedAt: '2026-09-13T06:00:00Z' }

describe('구매자 결제 승인 API', () => {
  test('인증 쿠키와 CSRF 토큰으로 토스 결제 정보를 승인 경로에 보내고 확정 응답을 반환한다', async () => {
    let request: Request | undefined
    const fetcher: typeof fetch = async (input, init) => {
      if (String(input).endsWith('/api/auth/csrf')) return Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      request = new Request(input, init)
      return Response.json(response)
    }
    await expect(confirmBuyerPayment(body, { baseUrl: 'http://localhost:8080/', fetcher })).resolves.toEqual(response)
    expect(request?.url).toBe('http://localhost:8080/api/payments/confirm')
    expect(request?.method).toBe('POST')
    expect(request?.headers.get('Content-Type')).toBe('application/json')
    expect(request?.credentials).toBe('include')
    expect(request?.headers.get('X-Buyer-Id')).toBeNull()
    expect(request?.headers.get('X-CSRF-TOKEN')).toBe('csrf-token')
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
      await confirmBuyerPayment(body, { fetcher: async (input) => String(input).endsWith('/api/auth/csrf')
        ? Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }) : Response.json(invalid) })
      throw new Error('잘못된 응답이 반환됨')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).code).toBeUndefined()
    }
  })

  test('서버 오류 코드를 변경하지 않고 전달한다', async () => {
    const fetcher: typeof fetch = async (input) => String(input).endsWith('/api/auth/csrf')
      ? Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }) : Response.json(
      { code: 'PAYMENT_CONFIRMATION_IN_PROGRESS', detail: '처리 중' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )
    await expect(confirmBuyerPayment(body, { fetcher })).rejects.toMatchObject({
      code: 'PAYMENT_CONFIRMATION_IN_PROGRESS', status: 409,
    })
  })
})
