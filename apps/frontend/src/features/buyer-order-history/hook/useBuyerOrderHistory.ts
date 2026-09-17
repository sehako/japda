import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo } from 'react'

import { apiBaseUrl } from '../../../shared/config/env.ts'
import {
  BUYER_ORDER_HISTORY_PAGE_SIZE,
  getBuyerOrderHistoryPage,
} from '../api/buyerOrderHistoryApi.ts'

const BUYER_ORDER_HISTORY_QUERY_KEY = 'buyer-order-history'

export function buyerOrderHistoryQueryKey(userId: number) {
  return [BUYER_ORDER_HISTORY_QUERY_KEY, userId, BUYER_ORDER_HISTORY_PAGE_SIZE] as const
}

export function useBuyerOrderHistory(userId?: number, clearCache = false) {
  const queryClient = useQueryClient()
  const queryKey = userId === undefined
    ? [BUYER_ORDER_HISTORY_QUERY_KEY, 'disabled', BUYER_ORDER_HISTORY_PAGE_SIZE] as const
    : buyerOrderHistoryQueryKey(userId)
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) => getBuyerOrderHistoryPage(pageParam, signal, { baseUrl: apiBaseUrl }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: userId !== undefined,
    staleTime: Infinity,
    retry: false,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  useEffect(() => {
    if (clearCache) {
      queryClient.removeQueries({ queryKey: [BUYER_ORDER_HISTORY_QUERY_KEY] })
    }
  }, [clearCache, queryClient])

  const orders = useMemo(() => {
    const seenOrderIds = new Set<number>()
    return query.data?.pages.flatMap((page) => page.items.filter((order) => {
      if (seenOrderIds.has(order.orderId)) return false
      seenOrderIds.add(order.orderId)
      return true
    })) ?? []
  }, [query.data])

  const { fetchNextPage, hasNextPage } = query
  const loadMore = useCallback(async () => {
    if (!hasNextPage) return
    await fetchNextPage({ cancelRefetch: false })
  }, [fetchNextPage, hasNextPage])

  const restart = useCallback(async () => {
    if (userId === undefined) return
    await queryClient.resetQueries({ queryKey: buyerOrderHistoryQueryKey(userId), exact: true })
  }, [queryClient, userId])

  return {
    ...query,
    orders,
    loadMore,
    retryInitial: query.refetch,
    retryLoadMore: loadMore,
    restart,
    isLoadMoreError: query.isFetchNextPageError,
  }
}
