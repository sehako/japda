import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ProductRegistrationApiError,
  createProduct,
  createSale,
  uploadProductImages,
} from './productRegistrationApi'

afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('productRegistrationApi', () => {
  it('상품 생성 요청에 상대 URL, 판매자 헤더와 JSON body를 사용한다', async () => {
    const response = {
      id: 7,
      sellerId: 1,
      name: '상품',
      description: '설명',
      status: 'DRAFT',
      createdAt: '2026-09-09T00:00:00Z',
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response, 201))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      createProduct({ name: '상품', description: '설명' }),
    ).resolves.toEqual(response)
    expect(fetchMock).toHaveBeenCalledWith('/api/products', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Seller-Id': '1',
      },
      body: JSON.stringify({ name: '상품', description: '설명' }),
    })
  })

  it('이미지 순서와 대표 index를 multipart body에 보존한다', async () => {
    const first = new File(['first'], 'first.jpg', { type: 'image/jpeg' })
    const second = new File(['second'], 'second.png', { type: 'image/png' })
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ productId: 7, status: 'READY', images: [] }, 201),
    )
    vi.stubGlobal('fetch', fetchMock)

    await uploadProductImages(7, {
      files: [first, second],
      representativeIndex: 1,
    })

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    const body = options.body as FormData
    expect(url).toBe('/api/products/7/images')
    expect(options.method).toBe('POST')
    expect(options.headers).toEqual({ 'X-Seller-Id': '1' })
    expect(body.getAll('files')).toEqual([first, second])
    expect(body.get('representativeIndex')).toBe('1')
  })

  it('판매 등록 요청에 productId와 offset 포함 시각을 전달한다', async () => {
    const request = {
      price: 120000,
      quantity: 10,
      startsAt: '2026-09-09T01:00:00.000Z',
      endsAt: '2026-09-10T01:00:00.000Z',
    }
    const response = {
      id: 3,
      productId: 7,
      price: 120000,
      initialQuantity: 10,
      remainingQuantity: 10,
      startsAt: request.startsAt,
      endsAt: request.endsAt,
      status: 'UPCOMING',
      createdAt: '2026-09-09T00:00:00Z',
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response, 201))
    vi.stubGlobal('fetch', fetchMock)

    await expect(createSale(7, request)).resolves.toEqual(response)
    expect(fetchMock).toHaveBeenCalledWith('/api/products/7/sales', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Seller-Id': '1',
      },
      body: JSON.stringify(request),
    })
  })

  it('ProblemDetail 응답을 구조화된 API 오류로 보존한다', async () => {
    const problem = {
      title: 'Bad Request',
      status: 400,
      detail: '입력값을 확인해 주세요.',
      instance: '/api/products',
      errors: { name: '상품명은 필수입니다.' },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(problem, 400)))

    const error = await createProduct({ name: '', description: '설명' }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ProductRegistrationApiError)
    expect(error).toMatchObject({ kind: 'http', problem })
  })

  it.each([
    ['비정상 JSON', new Response('not-json', { status: 500 })],
    ['빈 응답', new Response(null, { status: 503 })],
  ])('%s 오류 응답을 안전한 API 오류로 변환한다', async (_, response) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    const error = await createProduct({ name: '상품', description: '설명' }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toMatchObject({
      kind: 'http',
      problem: { status: response.status },
    })
  })

  it('네트워크 실패를 재시도 가능한 API 오류로 변환한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed')))

    const error = await createProduct({ name: '상품', description: '설명' }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toMatchObject({ kind: 'network' })
  })
})
