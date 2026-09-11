import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import { useBuyerSaleDetail } from '../../../../src/features/buyer-sale/hook/useBuyerSaleDetail.ts'

const detail = {
  saleId: 11,
  productId: 21,
  name: '한정판 후디',
  description: null,
  price: 120000,
  quantity: 10,
  saleDate: '2026-09-10',
  startsAt: '2026-09-10T15:00:00Z',
  endsAt: '2026-09-11T15:00:00Z',
  status: 'UPCOMING',
  images: [{ path: '/products/21/main.webp', displayOrder: 0, isRepresentative: true }],
} as const

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-10T14:59:59Z'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

test('saleId별로 상세를 조회하고 대상 변경 시 이전 요청을 취소한다', async () => {
  const requests: Array<{ url: string; signal?: AbortSignal }> = []
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), signal: init?.signal ?? undefined })
    return new Promise<Response>(() => undefined)
  }))
  const { rerender, unmount } = renderHook(({ saleId }) => useBuyerSaleDetail(saleId), {
    initialProps: { saleId: 11 as number | null },
    wrapper: createWrapper(),
  })

  await waitFor(() => expect(requests).toHaveLength(1))
  expect(requests[0]?.url).toBe('/api/sales/11')

  rerender({ saleId: 12 })
  await waitFor(() => expect(requests).toHaveLength(2))
  expect(requests[0]?.signal?.aborted).toBe(true)
  expect(requests[1]?.url).toBe('/api/sales/12')

  unmount()
  expect(requests[1]?.signal?.aborted).toBe(true)
})

test('미확정 saleId는 조회하지 않고 실패한 요청을 자동 재시도하지 않는다', async () => {
  const fetcher = vi.fn(async () => new Response(null, { status: 500 }))
  vi.stubGlobal('fetch', fetcher)
  const { result, rerender } = renderHook(({ saleId }) => useBuyerSaleDetail(saleId), {
    initialProps: { saleId: null as number | null },
    wrapper: createWrapper(),
  })
  expect(fetcher).not.toHaveBeenCalled()

  rerender({ saleId: 11 })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(fetcher).toHaveBeenCalledOnce()
})

test.each([
  { status: 'UPCOMING', startsAt: '2026-09-10T15:00:00Z', endsAt: '2026-09-11T15:00:00Z' },
  { status: 'ON_SALE', startsAt: '2026-09-09T15:00:00Z', endsAt: '2026-09-10T15:00:00Z' },
] as const)('$status의 다음 판매 경계에서 서버 상태를 다시 조회한다', async (boundaryDetail) => {
  const fetcher = vi.fn(async () => Response.json({ ...detail, ...boundaryDetail }))
  vi.stubGlobal('fetch', fetcher)
  const { result } = renderHook(() => useBuyerSaleDetail(11), { wrapper: createWrapper() })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))
  expect(fetcher).toHaveBeenCalledOnce()

  await act(() => vi.advanceTimersByTimeAsync(1050))
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
})

test('unmount 시 판매 경계 timer를 정리한다', async () => {
  const fetcher = vi.fn(async () => Response.json(detail))
  vi.stubGlobal('fetch', fetcher)
  const { result, unmount } = renderHook(() => useBuyerSaleDetail(11), { wrapper: createWrapper() })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))

  unmount()
  await act(() => vi.advanceTimersByTimeAsync(1050))
  expect(fetcher).toHaveBeenCalledOnce()
})
