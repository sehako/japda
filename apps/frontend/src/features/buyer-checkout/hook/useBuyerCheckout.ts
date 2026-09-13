import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { apiBaseUrl } from '../../../shared/config/env.ts'
import { createShippingAddress, fetchBuyerCheckout } from '../api/buyerCheckoutApi.ts'
import type { CreateShippingAddressRequest } from '../model/buyerCheckout.ts'

export function useBuyerCheckout(saleId: number, quantity: number, buyerId: number) {
  const queryClient = useQueryClient()
  const queryKey = ['buyer-checkout', buyerId, saleId, quantity] as const
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchBuyerCheckout(saleId, quantity, buyerId, signal, { baseUrl: apiBaseUrl }),
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const registration = useMutation({
    mutationFn: (body: CreateShippingAddressRequest) => createShippingAddress(body, buyerId, { baseUrl: apiBaseUrl }),
  })
  const refresh = () => queryClient.invalidateQueries({ queryKey, exact: true })
  return { query, registration, refresh }
}
