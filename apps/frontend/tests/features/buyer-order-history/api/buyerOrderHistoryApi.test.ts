import { describe, expect, test } from 'vitest'

import { getBuyerOrderHistoryPage } from '../../../../src/features/buyer-order-history/api/buyerOrderHistoryApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const pendingOrder = {
  orderId: 1000,
  status: 'PENDING_PAYMENT',
  productName: '한정판 상품',
  quantity: 2,
  unitPrice: 35000,
  totalPrice: 70000,
  createdAt: '2026-09-11T06:00:00Z',
  expiresAt: '2026-09-11T06:03:00Z',
} as const

const paidOrder = {
  ...pendingOrder,
  orderId: 999,
  status: 'PAID',
  productName: '결제 완료 상품',
} as const

describe('구매자 주문 내역 API', () => {
  test('보호 요청으로 최초 20개를 조회하고 AbortSignal을 전달한다', async () => {
    let sentRequest: Request | null = null
    const controller = new AbortController()
    const fetcher: typeof fetch = async (input, init) => {
      sentRequest = new Request(input, init)
      return Response.json({ items: [pendingOrder, paidOrder], nextCursor: 'next-page' })
    }

    await expect(getBuyerOrderHistoryPage(undefined, controller.signal, {
      baseUrl: 'http://localhost:8080/',
      fetcher,
    })).resolves.toEqual({ items: [pendingOrder, paidOrder], nextCursor: 'next-page' })

    const request = sentRequest as unknown as Request
    expect(request.url).toBe('http://localhost:8080/api/orders?size=20')
    expect(request.method).toBe('GET')
    expect(request.credentials).toBe('include')
    controller.abort()
    expect(request.signal.aborted).toBe(true)
  })

  test('서버가 발급한 cursor를 해석하거나 변경하지 않고 다음 요청에 전달한다', async () => {
    let requestedUrl = ''
    const cursor = 'opaque+cursor/한글=='
    const fetcher: typeof fetch = async (input) => {
      requestedUrl = String(input)
      return Response.json({ items: [paidOrder], nextCursor: null })
    }

    await getBuyerOrderHistoryPage(cursor, undefined, {
      baseUrl: 'http://localhost:8080',
      fetcher,
    })

    const requestUrl = new URL(requestedUrl)
    expect(requestUrl.pathname).toBe('/api/orders')
    expect(requestUrl.searchParams.get('size')).toBe('20')
    expect(requestUrl.searchParams.get('cursor')).toBe(cursor)
  })

  test.each([
    { items: [], nextCursor: null },
    { items: [paidOrder], nextCursor: null },
  ])('빈 페이지와 마지막 페이지 %#을 정상 결과로 반환한다', async (page) => {
    const fetcher: typeof fetch = async () => Response.json(page)
    await expect(getBuyerOrderHistoryPage(undefined, undefined, { fetcher })).resolves.toEqual(page)
  })

  test.each([
    null,
    {},
    { items: 'orders', nextCursor: null },
    { items: [pendingOrder] },
    { items: [], nextCursor: 'unexpected-next-page' },
    { items: [{ ...pendingOrder, orderId: 0 }], nextCursor: null },
    { items: [{ ...pendingOrder, orderId: Number.MAX_SAFE_INTEGER + 1 }], nextCursor: null },
    { items: [{ ...pendingOrder, quantity: 1.5 }], nextCursor: null },
    { items: [{ ...pendingOrder, unitPrice: -1 }], nextCursor: null },
    { items: [{ ...pendingOrder, totalPrice: 0 }], nextCursor: null },
    { items: [{ ...pendingOrder, status: 'CANCELLED' }], nextCursor: null },
    { items: [{ ...pendingOrder, productName: 42 }], nextCursor: null },
    { items: [{ ...pendingOrder, createdAt: 'not-a-date' }], nextCursor: null },
    { items: [{ ...pendingOrder, expiresAt: '' }], nextCursor: null },
    { items: [pendingOrder], nextCursor: 10 },
  ])('응답 계약을 벗어난 성공 응답 %#을 오류로 처리한다', async (body) => {
    const fetcher: typeof fetch = async () => Response.json(body)
    await expect(getBuyerOrderHistoryPage(undefined, undefined, { fetcher })).rejects.toBeInstanceOf(ApiError)
  })
})
