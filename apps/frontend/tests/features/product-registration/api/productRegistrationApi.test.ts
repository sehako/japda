import assert from 'node:assert/strict'
import test from 'node:test'

import { createProduct, registerProductImages } from '../../../../src/features/product-registration/api/productRegistrationApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

test('상품 생성은 정규화된 JSON과 판매자 헤더를 전송한다', async () => {
  let request: Request | null = null
  const fetcher: typeof fetch = async (input, init) => {
    request = new Request(input, init)
    return Response.json({ id: 42, sellerId: 7, name: '상품', description: null, status: 'DRAFT', createdAt: '2026-09-10T00:00:00Z' }, { status: 201 })
  }

  const result = await createProduct({ name: '상품', description: null }, 7, undefined, { baseUrl: 'http://localhost', fetcher })

  assert.equal(result.id, 42)
  assert.equal(request && (request as Request).headers.get('X-Seller-Id'), '7')
  assert.deepEqual(JSON.parse(await (request as unknown as Request).text()), { name: '상품', description: null })
})

test('상품 생성 성공 응답이 DRAFT 계약과 다르면 실패한다', async () => {
  const fetcher: typeof fetch = async () => Response.json({ id: 42, status: 'READY' }, { status: 201 })
  await assert.rejects(createProduct({ name: '상품', description: null }, 7, undefined, { fetcher }), ApiError)
})

test('이미지 등록은 파일 순서와 대표 인덱스를 multipart로 전송하고 Content-Type은 직접 지정하지 않는다', async () => {
  let request: Request | null = null
  const first = new File(['first'], 'first.jpg', { type: 'image/jpeg' })
  const second = new File(['second'], 'second.png', { type: 'image/png' })
  const fetcher: typeof fetch = async (input, init) => {
    request = new Request(input, init)
    return Response.json({ productId: 42, status: 'READY', images: [] }, { status: 201 })
  }

  await registerProductImages(42, [first, second], 1, 7, undefined, { baseUrl: 'http://localhost', fetcher })

  const sent = request as unknown as Request
  assert.equal(sent.headers.get('X-Seller-Id'), '7')
  assert.match(sent.headers.get('Content-Type') ?? '', /^multipart\/form-data; boundary=/)
  const formData = await sent.formData()
  assert.deepEqual(formData.getAll('files').map((value) => (value as File).name), ['first.jpg', 'second.png'])
  assert.equal(formData.get('representativeIndex'), '1')
})

test('이미지 등록 성공 응답의 상품 식별자가 요청과 다르면 실패한다', async () => {
  const fetcher: typeof fetch = async () => Response.json({ productId: 99, status: 'READY', images: [] }, { status: 201 })
  await assert.rejects(registerProductImages(42, [new File(['a'], 'a.jpg')], 0, 7, undefined, { fetcher }), ApiError)
})
