import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import { useBuyerSales } from '../../../../src/features/buyer-sale/hook/useBuyerSales.ts'

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-10T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

test('최초에는 한국의 오늘을 선택해 조회하고 날짜 변경 시 이전 요청을 취소한다', async () => {
  const requests: Array<{ url: string; signal?: AbortSignal }> = []
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), signal: init?.signal ?? undefined })
    return new Promise<Response>(() => undefined)
  }))
  const { result, unmount } = renderHook(() => useBuyerSales(), { wrapper: createWrapper() })

  await waitFor(() => expect(requests).toHaveLength(1))
  expect(result.current.selectedDate).toBe('2026-09-10')
  expect(requests[0]?.url).toBe('/api/sales?saleDate=2026-09-10')

  act(() => result.current.selectDate('2026-09-09'))
  await waitFor(() => expect(requests).toHaveLength(2))
  expect(requests[0]?.signal?.aborted).toBe(true)
  expect(requests[1]?.url).toBe('/api/sales?saleDate=2026-09-09')

  unmount()
  expect(requests[1]?.signal?.aborted).toBe(true)
})

test('이미 선택한 날짜와 모레 이후 날짜는 다시 조회하지 않는다', async () => {
  const fetcher = vi.fn(async () => Response.json({ sales: [] }))
  vi.stubGlobal('fetch', fetcher)
  const { result } = renderHook(() => useBuyerSales(), { wrapper: createWrapper() })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))

  act(() => result.current.selectDate('2026-09-10'))
  act(() => result.current.selectDate('2026-09-12'))
  expect(fetcher).toHaveBeenCalledOnce()
  expect(result.current.selectedDate).toBe('2026-09-10')
})
