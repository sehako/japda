import assert from 'node:assert/strict'
import { test } from 'vitest'

import { ApiError, requestApi } from '../../../src/shared/api/apiClient.ts'

test('application/problem+json 응답을 ApiError로 변환한다', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    type: 'about:blank', title: '잘못된 요청', status: 400,
    detail: '요청 값이 올바르지 않습니다.', code: 'PRODUCT_NAME_REQUIRED',
    errors: { name: '상품명은 필수입니다.' },
  }), { status: 400, headers: { 'Content-Type': 'application/problem+json' } })

  await assert.rejects(
    requestApi('/api/products', {}, { baseUrl: 'http://localhost:8080/', fetcher }),
    (error: unknown) => error instanceof ApiError
      && error.code === 'PRODUCT_NAME_REQUIRED'
      && error.fieldErrors.name === '상품명은 필수입니다.',
  )
})

test('해석할 수 없는 오류 응답은 내부 원문 없이 일반 오류로 변환한다', async () => {
  const fetcher: typeof fetch = async () => new Response('secret server trace', { status: 500 })

  await assert.rejects(
    requestApi('/api/products', {}, { fetcher }),
    (error: unknown) => error instanceof ApiError
      && error.message === '상품을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.'
      && !error.message.includes('secret'),
  )
})

test('정상 JSON 응답을 반환하고 기준 URL의 슬래시를 중복하지 않는다', async () => {
  let requestedUrl = ''
  const fetcher: typeof fetch = async (input) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({ id: 42 }), { status: 201, headers: { 'Content-Type': 'application/json' } })
  }

  const result = await requestApi<{ id: number }>('/api/products', {}, { baseUrl: 'http://localhost:8080/', fetcher })

  assert.deepEqual(result, { id: 42 })
  assert.equal(requestedUrl, 'http://localhost:8080/api/products')
})

test('요청이 서버에 도달했는지 알 수 없는 네트워크 오류를 구분한다', async () => {
  const fetcher: typeof fetch = async () => { throw new TypeError('network down') }
  await assert.rejects(requestApi('/api/sales', { method: 'POST' }, { fetcher }), (error: unknown) => error instanceof ApiError && error.isNetworkError)
})
