import assert from 'node:assert/strict'
import { test } from 'vitest'

import { ApiError, prepareCsrfToken, requestApi } from '../../../src/shared/api/apiClient.ts'

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

test('보호 GET은 쿠키를 보내고 CSRF를 조회하지 않는다', async () => {
  const requests: Request[] = []
  const fetcher: typeof fetch = async (input, init) => {
    requests.push(new Request(input, init))
    return Response.json({ id: 1 })
  }

  await requestApi('/api/checkout', { method: 'GET' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })

  assert.equal(requests.length, 1)
  assert.equal(requests[0].credentials, 'include')
  assert.equal(requests[0].headers.has('X-CSRF-TOKEN'), false)
})

test('보호 POST는 서버가 지정한 CSRF 헤더와 기존 요청 헤더를 보낸다', async () => {
  const requests: Request[] = []
  const fetcher: typeof fetch = async (input, init) => {
    const request = new Request(input, init)
    requests.push(request)
    if (request.url.endsWith('/api/auth/csrf')) return Response.json({ token: 'token-1', headerName: 'X-CSRF-TOKEN' })
    return Response.json({ id: 1 })
  }

  await requestApi('/api/orders', { method: 'POST', headers: { 'Idempotency-Key': 'key-1' }, body: '{}' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })

  assert.equal(requests.length, 2)
  assert.equal(requests[0].credentials, 'include')
  assert.equal(requests[0].method, 'GET')
  assert.equal(requests[1].credentials, 'include')
  assert.equal(requests[1].headers.get('X-CSRF-TOKEN'), 'token-1')
  assert.equal(requests[1].headers.get('Idempotency-Key'), 'key-1')
})

test('동시 보호 변경 요청은 하나의 CSRF 조회를 공유한다', async () => {
  let resolve!: (value: Response) => void
  const requests: Request[] = []
  const fetcher: typeof fetch = async (input, init) => {
    const request = new Request(input, init)
    requests.push(request)
    if (request.url.endsWith('/api/auth/csrf')) return new Promise<Response>((done) => { resolve = done })
    return Response.json({ id: 1 })
  }

  const first = requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })
  const second = requestApi('/api/sales', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })
  resolve(Response.json({ token: 'shared', headerName: 'X-CSRF-TOKEN' }))
  await Promise.all([first, second])

  assert.equal(requests.length, 3)
  assert.equal(requests.filter((request) => request.url.endsWith('/api/auth/csrf')).length, 1)
})

test('CSRF 조회 실패 또는 무효 응답이면 보호 변경 요청을 보내지 않는다', async () => {
  for (const csrfResponse of [new Response(null, { status: 503 }), Response.json({ token: '', headerName: 'X-CSRF-TOKEN' })]) {
    const requests: Request[] = []
    const fetcher: typeof fetch = async (input, init) => {
      requests.push(new Request(input, init))
      return csrfResponse
    }
    await assert.rejects(requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher }), ApiError)
    assert.equal(requests.length, 1)
  }
})

test('CSRF 오류 후 변경 요청을 재전송하지 않고 다음 시도에 새 토큰을 조회한다', async () => {
  const requests: Request[] = []
  let tokenNumber = 0
  const fetcher: typeof fetch = async (input, init) => {
    const request = new Request(input, init)
    requests.push(request)
    if (request.url.endsWith('/api/auth/csrf')) return Response.json({ token: `token-${++tokenNumber}`, headerName: 'X-CSRF-TOKEN' })
    if (tokenNumber === 1) return Response.json({ status: 403, code: 'AUTH_CSRF_INVALID' }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } })
    return Response.json({ id: 1 })
  }

  await assert.rejects(requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher }), (error: unknown) => error instanceof ApiError && error.code === 'AUTH_CSRF_INVALID')
  assert.equal(requests.length, 2)
  await requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })
  assert.equal(requests.length, 4)
  assert.equal(requests[3].headers.get('X-CSRF-TOKEN'), 'token-2')
})

test('선조회한 CSRF 토큰을 첫 보호 변경 요청에서 재사용한다', async () => {
  const requests: Request[] = []
  const fetcher: typeof fetch = async (input, init) => {
    requests.push(new Request(input, init))
    return requests.length === 1 ? Response.json({ token: 'ready', headerName: 'X-CUSTOM-CSRF' }) : Response.json({ id: 1 })
  }

  await prepareCsrfToken({ baseUrl: 'http://localhost:8080', fetcher })
  await requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })
  assert.equal(requests.length, 2)
  assert.equal(requests[1].headers.get('X-CUSTOM-CSRF'), 'ready')
})

test('인증 오류를 받은 뒤에는 보관한 CSRF 토큰을 폐기한다', async () => {
  const requests: Request[] = []
  let tokenNumber = 0
  let attempt = 0
  const fetcher: typeof fetch = async (input, init) => {
    const request = new Request(input, init)
    requests.push(request)
    if (request.url.endsWith('/api/auth/csrf')) return Response.json({ token: `token-${++tokenNumber}`, headerName: 'X-CSRF-TOKEN' })
    if (++attempt === 1) return Response.json({ status: 401, code: 'AUTH_UNAUTHENTICATED' }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } })
    return Response.json({ id: 1 })
  }

  await assert.rejects(requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher }), ApiError)
  await requestApi('/api/orders', { method: 'POST' }, { protected: true, baseUrl: 'http://localhost:8080', fetcher })

  assert.equal(requests.length, 4)
  assert.equal(requests[3].headers.get('X-CSRF-TOKEN'), 'token-2')
})
