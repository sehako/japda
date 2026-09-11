import { describe, expect, test } from 'vitest'

import { fetchBuyerSales } from '../../../../src/features/buyer-sale/api/buyerSaleApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const firstSale = {
  saleId: 11,
  productId: 21,
  name: '첫 번째 상품',
  description: null,
  price: 120000,
  quantity: 10,
  saleDate: '2026-09-10',
  startsAt: '2026-09-09T15:00:00Z',
  endsAt: '2026-09-10T15:00:00Z',
  status: 'ON_SALE',
  representativeImagePath: '/products/21/main.webp',
} as const

describe('구매자 판매 상품 API', () => {
  test('선택한 날짜와 AbortSignal만 전달하고 응답 순서를 유지한다', async () => {
    let sentRequest: Request | null = null
    const controller = new AbortController()
    const secondSale = { ...firstSale, saleId: 12, productId: 22, name: '두 번째 상품' }
    const fetcher: typeof fetch = async (input, init) => {
      sentRequest = new Request(input, init)
      return Response.json({ sales: [firstSale, secondSale] })
    }

    const response = await fetchBuyerSales('2026-09-10', controller.signal, {
      baseUrl: 'http://localhost:8080',
      fetcher,
    })

    const request = sentRequest as unknown as Request
    expect(request.url).toBe('http://localhost:8080/api/sales?saleDate=2026-09-10')
    expect(request.method).toBe('GET')
    expect(request.headers.get('X-Seller-Id')).toBeNull()
    controller.abort()
    expect(request.signal.aborted).toBe(true)
    expect(response.sales.map(({ saleId }) => saleId)).toEqual([11, 12])
  })

  test('빈 sales 배열을 정상 결과로 반환한다', async () => {
    const fetcher: typeof fetch = async () => Response.json({ sales: [] })
    await expect(fetchBuyerSales('2026-09-10', undefined, { fetcher })).resolves.toEqual({ sales: [] })
  })

  test('개발 환경의 루트 기준 대표 이미지 경로가 있는 상품을 정상 결과로 반환한다', async () => {
    const sale = { ...firstSale, representativeImagePath: '/local-dev/product-placeholder-3.png' }
    const fetcher: typeof fetch = async () => Response.json({ sales: [sale] })

    await expect(fetchBuyerSales('2026-09-10', undefined, { fetcher }))
      .resolves.toEqual({ sales: [sale] })
  })

  test.each([
    { sales: [{ ...firstSale, saleId: 0 }] },
    { sales: [{ ...firstSale, quantity: 1.5 }] },
    { sales: [{ ...firstSale, description: 42 }] },
    { sales: [{ ...firstSale, saleDate: '2026/09/10' }] },
    { sales: [{ ...firstSale, saleDate: '2026-02-30' }] },
    { sales: [{ ...firstSale, startsAt: '' }] },
    { sales: [{ ...firstSale, startsAt: '2026-99-09T15:00:00Z' }] },
    { sales: [{ ...firstSale, status: 'SOLD_OUT' }] },
    { sales: [{ ...firstSale, representativeImagePath: 'products/21/main.webp' }] },
    { items: [] },
  ])('전체 응답 계약을 벗어난 성공 응답 %#을 오류로 처리한다', async (body) => {
    const fetcher: typeof fetch = async () => Response.json(body)
    await expect(fetchBuyerSales('2026-09-10', undefined, { fetcher })).rejects.toBeInstanceOf(ApiError)
  })
})
