import assert from 'node:assert/strict'
import test from 'node:test'

import { createSale, fetchReadyProducts } from '../../../../src/features/sale-scheduling/api/saleSchedulingApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

test('READY 상품 첫 페이지에 정렬, 크기와 판매자 헤더를 전달한다', async () => {
  let request: Request | null = null
  const controller = new AbortController()
  const fetcher: typeof fetch = async (input, init) => {
    request = new Request(input, init)
    return Response.json({ items: [{ id: 10, name: '후디' }], nextCursor: null })
  }
  const result = await fetchReadyProducts('latest', null, 7, controller.signal, { baseUrl: 'http://localhost', fetcher })
  const sent = request as unknown as Request
  assert.equal(sent.url, 'http://localhost/api/products/ready?sort=latest&size=20')
  assert.equal(sent.headers.get('X-Seller-Id'), '7')
  controller.abort()
  assert.equal(sent.signal.aborted, true)
  assert.equal(result.items[0]?.name, '후디')
})

test('READY 상품 다음 페이지 커서를 해석하지 않고 URL에 인코딩한다', async () => {
  let url = ''
  const fetcher: typeof fetch = async (input) => { url = String(input); return Response.json({ items: [], nextCursor: null }) }
  await fetchReadyProducts('name-asc', 'cursor+/=', 7, undefined, { baseUrl: 'http://localhost', fetcher })
  assert.equal(url, 'http://localhost/api/products/ready?sort=name-asc&cursor=cursor%2B%2F%3D&size=20')
})

test('READY 상품 응답의 상품명이 문자열이 아니면 실패한다', async () => {
  const fetcher: typeof fetch = async () => Response.json({ items: [{ id: 10, name: 42 }], nextCursor: null })
  await assert.rejects(fetchReadyProducts('latest', null, 7, undefined, { fetcher }), ApiError)
})

test('판매 일정 등록 요청과 응답 계약을 검증한다', async () => {
  let request: Request | null = null
  const body = { productId: 10, saleDate: '2026-09-12', price: 35000, quantity: 100 }
  const fetcher: typeof fetch = async (input, init) => {
    request = new Request(input, init)
    return Response.json({ id: 20, sellerId: 7, ...body, startsAt: '2026-09-11T15:00:00Z', endsAt: '2026-09-12T15:00:00Z', createdAt: '2026-09-11T01:00:00Z' }, { status: 201 })
  }
  const result = await createSale(body, 7, undefined, { baseUrl: 'http://localhost', fetcher })
  const sent = request as unknown as Request
  assert.equal(sent.method, 'POST')
  assert.equal(sent.headers.get('X-Seller-Id'), '7')
  assert.deepEqual(JSON.parse(await sent.text()), body)
  assert.equal(result.id, 20)
})

test('판매 일정 응답이 요청 값과 다르면 성공으로 처리하지 않는다', async () => {
  const body = { productId: 10, saleDate: '2026-09-12', price: 35000, quantity: 100 }
  const fetcher: typeof fetch = async () => Response.json({ id: 20, sellerId: 7, ...body, quantity: 99, startsAt: 'a', endsAt: 'b', createdAt: 'c' }, { status: 201 })
  await assert.rejects(createSale(body, 7, undefined, { fetcher }), ApiError)
})
