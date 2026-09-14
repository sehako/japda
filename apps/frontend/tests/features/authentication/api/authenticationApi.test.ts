import { describe, expect, test } from 'vitest'

import { fetchCurrentUser } from '../../../../src/features/authentication/api/authenticationApi.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const user = { id: 12, email: 'buyer@example.com', roles: ['ADMIN', 'BUYER'] }

describe('현재 사용자 API', () => {
  test('자격 증명과 AbortSignal을 포함해 현재 사용자를 조회한다', async () => {
    let request: Request | undefined
    const controller = new AbortController()
    const fetcher: typeof fetch = async (input, init) => {
      request = new Request(input, init)
      return Response.json(user)
    }

    await expect(fetchCurrentUser(controller.signal, { baseUrl: 'http://localhost:8080', fetcher }))
      .resolves.toEqual(user)

    expect(request?.url).toBe('http://localhost:8080/api/auth/me')
    expect(request?.method).toBe('GET')
    expect(request?.credentials).toBe('include')
    controller.abort()
    expect(request?.signal.aborted).toBe(true)
  })

  test.each([
    { ...user, id: 0 },
    { ...user, id: Number.MAX_SAFE_INTEGER + 1 },
    { ...user, email: null },
    { ...user, roles: ['BUYER', 'ADMIN'] },
    { ...user, roles: ['BUYER', 'BUYER'] },
    { ...user, roles: ['SELLER'] },
    { ...user, roles: [] as unknown, extra: true, id: 1.5 },
  ])('계약을 벗어난 응답 %#을 조회 오류로 처리한다', async (body) => {
    const fetcher: typeof fetch = async () => Response.json(body)
    await expect(fetchCurrentUser(undefined, { fetcher })).rejects.toMatchObject({
      name: 'ApiError',
      message: '로그인 상태를 확인하지 못했습니다.',
    })
  })

  test('401 인증 실패의 상태와 코드를 보존한다', async () => {
    const fetcher: typeof fetch = async () => Response.json(
      { status: 401, code: 'AUTH_UNAUTHENTICATED', detail: '인증이 필요합니다.' },
      { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
    )
    await expect(fetchCurrentUser(undefined, { fetcher })).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_UNAUTHENTICATED',
    })
  })

  test.each([
    { name: '서버 오류', fetcher: async () => new Response(null, { status: 500 }), network: false },
    { name: '네트워크 오류', fetcher: async () => { throw new TypeError('offline') }, network: true },
  ])('$name에 인증 전용 오류 문구를 사용한다', async ({ fetcher, network }) => {
    await expect(fetchCurrentUser(undefined, { fetcher })).rejects.toMatchObject({
      name: 'ApiError',
      message: '로그인 상태를 확인하지 못했습니다.',
      isNetworkError: network,
    })
  })

  test('요청 취소는 조회 오류로 바꾸지 않는다', async () => {
    const abort = new DOMException('취소', 'AbortError')
    const fetcher: typeof fetch = async () => { throw abort }
    await expect(fetchCurrentUser(undefined, { fetcher })).rejects.toBe(abort)
  })

  test('오류는 ApiError로 전달한다', async () => {
    const fetcher: typeof fetch = async () => new Response(null, { status: 503 })
    await expect(fetchCurrentUser(undefined, { fetcher })).rejects.toBeInstanceOf(ApiError)
  })
})
