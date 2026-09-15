import { describe, expect, test, vi } from 'vitest'

import { createShippingAddress, fetchBuyerCheckout } from '../../../../src/features/buyer-checkout/api/buyerCheckoutApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const address = { shippingAddressId: 7, addressName: '집', recipientName: '홍길동', phoneNumber: '010-1234-5678', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: null }
const checkout = { saleId: 11, productName: '한정판 후디', representativeImagePath: '/products/21/main.webp', quantity: 3, unitPrice: 120000, totalPrice: 360000, shippingAddresses: [address] }

describe('구매자 체크아웃 API', () => {
  test('쿠키를 포함하고 구매자 식별자 없이 수량과 AbortSignal을 전달한다', async () => {
    let request: Request | undefined
    const controller = new AbortController()
    const fetcher: typeof fetch = async (input, init) => {
      request = new Request(input, init)
      return Response.json(checkout)
    }
    await expect(fetchBuyerCheckout(11, 3, controller.signal, { baseUrl: 'http://localhost:8080', fetcher })).resolves.toEqual(checkout)
    expect(request?.url).toBe('http://localhost:8080/api/checkout?saleId=11&quantity=3')
    expect(request?.headers.has('X-Buyer-Id')).toBe(false)
    expect(request?.credentials).toBe('include')
    controller.abort()
    expect(request?.signal.aborted).toBe(true)
  })

  test.each([
    { ...checkout, saleId: 12 },
    { ...checkout, quantity: 2 },
    { ...checkout, totalPrice: Number.MAX_SAFE_INTEGER + 1 },
    { ...checkout, representativeImagePath: 'products/main.webp' },
    { ...checkout, shippingAddresses: [{ ...address, phoneNumber: 123 }] },
  ])('계약에서 벗어난 조회 응답을 거절한다', async (body) => {
    await expect(fetchBuyerCheckout(11, 3, undefined, { fetcher: async () => Response.json(body) })).rejects.toBeInstanceOf(ApiError)
  })

  test.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('잘못된 판매 식별자 %s에서는 요청하지 않는다', async (saleId) => {
    const fetcher = vi.fn<typeof fetch>()
    await expect(fetchBuyerCheckout(saleId, 3, undefined, { fetcher })).rejects.toBeInstanceOf(ApiError)
    expect(fetcher).not.toHaveBeenCalled()
  })

  test('배송지 등록 요청에 쿠키와 CSRF 토큰 및 필드를 전달한다', async () => {
    let request: Request | undefined
    const fetcher: typeof fetch = async (input, init) => {
      if (String(input).endsWith('/api/auth/csrf')) return Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      request = new Request(input, init)
      return Response.json({ ...address, createdAt: '2026-09-13T00:00:00Z' }, { status: 201 })
    }
    await expect(createShippingAddress({ ...address, shippingAddressId: undefined }, { baseUrl: 'http://localhost', fetcher })).resolves.toMatchObject(address)
    expect(request?.url).toBe('http://localhost/api/shipping-addresses')
    expect(request?.headers.has('X-Buyer-Id')).toBe(false)
    expect(request?.headers.get('X-CSRF-TOKEN')).toBe('csrf-token')
    expect(request?.credentials).toBe('include')
    expect(request?.method).toBe('POST')
    expect(await request?.json()).toMatchObject({ addressName: '집', detailAddress: '101호' })
  })

  test('잘못된 등록 성공 응답을 거절한다', async () => {
    await expect(createShippingAddress({ ...address, shippingAddressId: undefined }, { baseUrl: 'http://localhost', fetcher: async (input) => String(input).endsWith('/api/auth/csrf') ? Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }) : Response.json({ ...address, shippingAddressId: null }, { status: 201 }) })).rejects.toBeInstanceOf(ApiError)
  })
})
