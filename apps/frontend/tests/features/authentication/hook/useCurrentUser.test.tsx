import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'

import { useCurrentUser } from '../../../../src/features/authentication/hook/useCurrentUser.ts'

const user = { id: 12, email: 'buyer@example.com', roles: ['BUYER'] }

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

afterEach(() => vi.unstubAllGlobals())

test('조회 완료 전에는 확인 중이고 성공하면 사용자 정보를 제공한다', async () => {
  let resolve!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
  const { result } = renderHook(() => useCurrentUser(), { wrapper: createWrapper() })

  expect(result.current.status).toBe('checking')
  expect(result.current.user).toBeUndefined()

  await act(async () => resolve(Response.json(user)))
  await waitFor(() => expect(result.current.status).toBe('authenticated'))
  expect(result.current.user).toEqual(user)
})

test('비활성화된 인증 조회는 API를 호출하지 않고 활성화 후 조회한다', async () => {
  const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/api/auth/csrf')
    ? Response.json({ token: 'ready', headerName: 'X-CSRF-TOKEN' })
    : Response.json(user))
  vi.stubGlobal('fetch', fetcher)
  const { result, rerender } = renderHook(({ enabled }) => useCurrentUser(enabled), {
    initialProps: { enabled: false }, wrapper: createWrapper(),
  })

  expect(result.current.status).toBe('checking')
  expect(fetcher).not.toHaveBeenCalled()
  rerender({ enabled: true })
  await waitFor(() => expect(result.current.status).toBe('authenticated'))
  expect(fetcher).toHaveBeenCalled()
})

test('401 응답을 비로그인으로 분류하고 자동 재시도하지 않는다', async () => {
  const fetcher = vi.fn(async () => Response.json(
    { status: 401, code: 'AUTH_UNAUTHENTICATED', detail: '인증이 필요합니다.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  ))
  vi.stubGlobal('fetch', fetcher)
  const { result } = renderHook(() => useCurrentUser(), { wrapper: createWrapper() })

  await waitFor(() => expect(result.current.status).toBe('unauthenticated'))
  expect(result.current.user).toBeUndefined()
  expect(fetcher).toHaveBeenCalledOnce()
})

test.each([
  { status: 401, code: 'OTHER_ERROR' },
  { status: 401, code: undefined },
])('오류 코드가 $code인 401도 비로그인으로 표시한다', async ({ status, code }) => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(
    { status, code, detail: '오류' },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )))
  const { result } = renderHook(() => useCurrentUser(), { wrapper: createWrapper() })

  await waitFor(() => expect(result.current.status).toBe('unauthenticated'))
  expect(result.current.user).toBeUndefined()
})

test('500 응답은 인증 오류 코드가 있어도 조회 오류로 표시한다', async () => {
  const status = 500
  const code = 'AUTH_UNAUTHENTICATED'
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(
    { status, code, detail: '오류' },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )))
  const { result } = renderHook(() => useCurrentUser(), { wrapper: createWrapper() })

  await waitFor(() => expect(result.current.status).toBe('error'))
  expect(result.current.user).toBeUndefined()
})

test('재조회 중과 실패 후에는 이전 사용자 정보를 숨기고 수동 재시도를 제공한다', async () => {
  let fail!: (error: unknown) => void
  let meRequests = 0
  const fetcher = vi.fn<typeof fetch>(async (input) => {
    if (String(input).endsWith('/api/auth/csrf')) return Response.json({ token: 'ready', headerName: 'X-CSRF-TOKEN' })
    meRequests += 1
    if (meRequests === 2) return new Promise<Response>((_, reject) => { fail = reject })
    return Response.json(user)
  })
  vi.stubGlobal('fetch', fetcher)
  const { result } = renderHook(() => useCurrentUser(), { wrapper: createWrapper() })
  await waitFor(() => expect(result.current.status).toBe('authenticated'))

  act(() => result.current.retry())
  await waitFor(() => expect(result.current.status).toBe('checking'))
  expect(result.current.user).toBeUndefined()

  await act(async () => fail(new TypeError('offline')))
  await waitFor(() => expect(result.current.status).toBe('error'))
  expect(result.current.user).toBeUndefined()
  expect(meRequests).toBe(2)

  act(() => result.current.retry())
  await waitFor(() => expect(result.current.status).toBe('authenticated'))
  expect(result.current.user).toEqual(user)
})
