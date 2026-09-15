import { describe, expect, test, vi } from 'vitest'

import { createBuyerOrder } from '../../../../src/features/buyer-checkout/api/buyerOrderApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const body = { saleId: 11, quantity: 3, shippingAddress: { recipientName: '홍길동', phoneNumber: '010-1234-5678', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: null } }
const response = { orderId: 9, paymentOrderId: '550e8400-e29b-41d4-a716-446655440000', status: 'PENDING_PAYMENT', productName: '한정판 후디', quantity: 3, unitPrice: 120000, totalPrice: 360000, expiresAt: '2026-09-13T12:03:00Z' }
const key = '660e8400-e29b-41d4-a716-446655440000'

describe('구매자 주문 API', () => {
  test('주문 스냅샷과 멱등성 키를 POST 요청으로 전달한다', async () => {
    let request: Request | undefined
    const fetcher: typeof fetch = async (input, init) => {
      if (String(input).endsWith('/api/auth/csrf')) return Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      request = new Request(input, init)
      return Response.json(response, { status: 201 })
    }
    await expect(createBuyerOrder(body, key, { baseUrl: 'http://localhost:8080', fetcher })).resolves.toEqual(response)
    expect(request?.url).toBe('http://localhost:8080/api/orders')
    expect(request?.method).toBe('POST')
    expect(request?.headers.has('X-Buyer-Id')).toBe(false)
    expect(request?.headers.get('X-CSRF-TOKEN')).toBe('csrf-token')
    expect(request?.credentials).toBe('include')
    expect(request?.headers.get('Idempotency-Key')).toBe(key)
    expect(request?.headers.get('Content-Type')).toBe('application/json')
    expect(await request?.json()).toEqual(body)
  })

  test.each([
    { ...response, status: 'PAID' },
    { ...response, paymentOrderId: 'legacy_9' },
    { ...response, paymentOrderId: '550e8400-e29b-41d4-a716-446655440001'.replace('-41d4-', '-31d4-') },
    { ...response, totalPrice: Number.MAX_SAFE_INTEGER + 1 },
    { ...response, totalPrice: 0 },
    { ...response, quantity: 0 },
    { ...response, productName: '' },
    { ...response, expiresAt: 'invalid' },
  ])('결제에 필요한 주문 응답 계약 오류를 거절한다', async (invalid) => {
    await expect(createBuyerOrder(body, key, { fetcher: async (input) => String(input).endsWith('/api/auth/csrf') ? Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }) : Response.json(invalid, { status: 201 }) })).rejects.toBeInstanceOf(ApiError)
  })

  test.each(['', 'invalid', '660e8400-e29b-31d4-a716-446655440000'])('잘못된 멱등성 키 %s에서는 주문을 보내지 않는다', async (invalidKey) => {
    const fetcher = vi.fn<typeof fetch>()
    await expect(createBuyerOrder(body, invalidKey, { fetcher })).rejects.toBeInstanceOf(ApiError)
    expect(fetcher).not.toHaveBeenCalled()
  })
})
