import { apiBaseUrl } from '../../../shared/config/env.ts'
import { fetchCurrentUser } from './authenticationApi.ts'

export const currentUserQueryKey = ['authentication', 'current-user'] as const

export const currentUserQuery = {
  queryKey: currentUserQueryKey,
  queryFn: ({ signal }: { signal: AbortSignal }) => fetchCurrentUser(signal, { baseUrl: apiBaseUrl }),
  staleTime: 0,
  retry: false,
  refetchOnMount: true,
  refetchOnWindowFocus: true,
  refetchOnReconnect: true,
} as const
