import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'

import { apiBaseUrl } from '../../../shared/config/env.ts'
import { fetchBuyerSaleDetail } from '../api/buyerSaleApi.ts'

const BUYER_SALE_DETAIL_QUERY_KEY = 'buyer-sale-detail'
const MAX_TIMEOUT_DELAY = 2_147_483_647

export function useBuyerSaleDetail(saleId: number | null) {
  const queryClient = useQueryClient()
  const queryKey = [BUYER_SALE_DETAIL_QUERY_KEY, saleId] as const
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => {
      if (saleId === null) throw new Error('유효한 판매 상품 식별자가 필요합니다.')
      return fetchBuyerSaleDetail(saleId, signal, { baseUrl: apiBaseUrl })
    },
    enabled: saleId !== null,
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  useEffect(() => {
    const detail = query.data
    if (!detail || (detail.status !== 'UPCOMING' && detail.status !== 'ON_SALE')) return
    const boundary = Date.parse(detail.status === 'UPCOMING' ? detail.startsAt : detail.endsAt)
    if (boundary <= Date.now()) return

    let timer: number | undefined
    const scheduleRefresh = () => {
      const remaining = boundary - Date.now()
      if (remaining <= 0) {
        void queryClient.invalidateQueries({ queryKey: [BUYER_SALE_DETAIL_QUERY_KEY, saleId], exact: true })
        return
      }
      timer = window.setTimeout(scheduleRefresh, Math.min(remaining + 50, MAX_TIMEOUT_DELAY))
    }
    scheduleRefresh()
    return () => window.clearTimeout(timer)
  }, [query.data, queryClient, saleId])

  return { ...query, retry: query.refetch }
}
