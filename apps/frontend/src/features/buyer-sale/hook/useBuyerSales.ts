import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'

import { apiBaseUrl } from '../../../shared/config/env.ts'
import { fetchBuyerSales } from '../api/buyerSaleApi.ts'
import {
  addDays,
  getKoreanToday,
  getMonthFromDate,
  isSelectableSaleDate,
  millisecondsUntilNextKoreanMidnight,
  moveMonth,
} from '../model/buyerSale.ts'

const BUYER_SALES_QUERY_KEY = 'buyer-sales'

export function useBuyerSales() {
  const initialToday = getKoreanToday()
  const [today, setToday] = useState(initialToday)
  const [selectedDate, setSelectedDate] = useState(initialToday)
  const [month, setMonth] = useState(() => getMonthFromDate(initialToday))
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: [BUYER_SALES_QUERY_KEY, selectedDate],
    queryFn: ({ signal }) => fetchBuyerSales(selectedDate, signal, { baseUrl: apiBaseUrl }),
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const nextToday = getKoreanToday()
      setToday(nextToday)
      void queryClient.invalidateQueries({ queryKey: [BUYER_SALES_QUERY_KEY, selectedDate], exact: true })
    }, millisecondsUntilNextKoreanMidnight() + 50)
    return () => window.clearTimeout(timer)
  }, [queryClient, selectedDate, today])

  const selectDate = useCallback((date: string) => {
    if (date === selectedDate || !isSelectableSaleDate(date, today)) return
    setSelectedDate(date)
  }, [selectedDate, today])

  const showPreviousMonth = useCallback(() => setMonth((current) => moveMonth(current, -1)), [])
  const showNextMonth = useCallback(() => setMonth((current) => moveMonth(current, 1)), [])

  return {
    ...query,
    today,
    tomorrow: addDays(today, 1),
    selectedDate,
    month,
    selectDate,
    showPreviousMonth,
    showNextMonth,
    retry: query.refetch,
  }
}
