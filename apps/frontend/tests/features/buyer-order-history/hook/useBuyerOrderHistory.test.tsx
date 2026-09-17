import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'

import {
  buyerOrderHistoryQueryKey,
  useBuyerOrderHistory,
} from '../../../../src/features/buyer-order-history/hook/useBuyerOrderHistory.ts'

const firstOrder = {
  orderId: 1000,
  status: 'PENDING_PAYMENT',
  productName: '한정판 상품',
  quantity: 2,
  unitPrice: 35000,
  totalPrice: 70000,
  createdAt: '2026-09-11T06:00:00Z',
  expiresAt: '2026-09-11T06:03:00Z',
} as const

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

afterEach(() => vi.unstubAllGlobals())

test('인증된 사용자의 첫 페이지를 조회하고 서버 cursor로 중복 없이 다음 페이지를 이어 붙인다', async () => {
  const requestedUrls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    requestedUrls.push(url)
    return url.includes('cursor=next-page')
      ? Response.json({ items: [firstOrder, { ...firstOrder, orderId: 999, productName: '다음 상품' }], nextCursor: null })
      : Response.json({ items: [firstOrder], nextCursor: 'next-page' })
  }))
  const queryClient = createQueryClient()
  const { result } = renderHook(() => useBuyerOrderHistory(7), { wrapper: createWrapper(queryClient) })

  await waitFor(() => expect(result.current.isSuccess).toBe(true))
  expect(result.current.orders.map((order) => order.orderId)).toEqual([1000])
  expect(result.current.hasNextPage).toBe(true)

  await act(async () => { await result.current.loadMore() })

  expect(requestedUrls).toEqual(['/api/orders?size=20', '/api/orders?size=20&cursor=next-page'])
  await waitFor(() => expect(result.current.orders.map((order) => order.orderId)).toEqual([1000, 999]))
  expect(result.current.hasNextPage).toBe(false)
})

test('추가 조회 중에 중복 요청을 보내지 않는다', async () => {
  let resolveNextPage: ((response: Response) => void) | undefined
  const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).includes('cursor=next-page')
    ? new Promise<Response>((resolve) => { resolveNextPage = resolve })
    : Response.json({ items: [firstOrder], nextCursor: 'next-page' }))
  vi.stubGlobal('fetch', fetcher)
  const queryClient = createQueryClient()
  const { result } = renderHook(() => useBuyerOrderHistory(7), { wrapper: createWrapper(queryClient) })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))

  act(() => {
    void result.current.loadMore()
    void result.current.loadMore()
  })
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  expect(fetcher).toHaveBeenCalledTimes(2)

  resolveNextPage?.(Response.json({ items: [{ ...firstOrder, orderId: 999 }], nextCursor: null }))
  await waitFor(() => expect(result.current.orders).toHaveLength(2))
})

test('추가 조회가 실패해도 기존 주문을 유지하고 다시 추가 조회한다', async () => {
  let nextAttempt = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (!String(input).includes('cursor=next-page')) return Response.json({ items: [firstOrder], nextCursor: 'next-page' })
    nextAttempt += 1
    if (nextAttempt === 1) return Response.json({ code: 'COMMON_INTERNAL_SERVER_ERROR' }, { status: 500 })
    return Response.json({ items: [{ ...firstOrder, orderId: 999 }], nextCursor: null })
  }))
  const queryClient = createQueryClient()
  const { result } = renderHook(() => useBuyerOrderHistory(7), { wrapper: createWrapper(queryClient) })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))

  await act(async () => { await result.current.loadMore() })
  await waitFor(() => expect(result.current.isLoadMoreError).toBe(true))
  expect(result.current.orders.map((order) => order.orderId)).toEqual([1000])

  await act(async () => { await result.current.retryLoadMore() })
  await waitFor(() => expect(result.current.orders).toHaveLength(2))
  expect(result.current.isLoadMoreError).toBe(false)
})

test('비인증 상태에서는 조회하지 않고 모든 사용자의 주문 cache를 제거한다', async () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  const queryClient = createQueryClient()
  queryClient.setQueryData(buyerOrderHistoryQueryKey(7), { pages: [{ items: [firstOrder], nextCursor: null }], pageParams: [undefined] })

  const { result } = renderHook(() => useBuyerOrderHistory(undefined, true), { wrapper: createWrapper(queryClient) })

  await waitFor(() => expect(queryClient.getQueryData(buyerOrderHistoryQueryKey(7))).toBeUndefined())
  expect(result.current.orders).toEqual([])
  expect(fetcher).not.toHaveBeenCalled()
})

test('사용자가 달라지면 서로 다른 cache key로 조회한다', async () => {
  const fetcher = vi.fn(async () => Response.json({ items: [firstOrder], nextCursor: null }))
  vi.stubGlobal('fetch', fetcher)
  const queryClient = createQueryClient()
  const { result, rerender } = renderHook(({ userId }) => useBuyerOrderHistory(userId), {
    initialProps: { userId: 7 },
    wrapper: createWrapper(queryClient),
  })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))

  rerender({ userId: 8 })

  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  expect(queryClient.getQueryData(buyerOrderHistoryQueryKey(7))).toBeDefined()
  expect(queryClient.getQueryData(buyerOrderHistoryQueryKey(8))).toBeDefined()
})
