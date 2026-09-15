import { useQuery } from '@tanstack/react-query'

import { ApiError } from '../../../shared/api/apiClient.ts'
import { currentUserQuery } from '../api/authenticationQuery.ts'
import type { CurrentUser } from '../model/currentUser.ts'

export type CurrentUserStatus = 'checking' | 'authenticated' | 'unauthenticated' | 'error'

export interface CurrentUserState {
  status: CurrentUserStatus
  user?: CurrentUser
  retry: () => void
}

export function useCurrentUser(enabled = true): CurrentUserState {
  const query = useQuery({ ...currentUserQuery, enabled })
  const retry = () => { void query.refetch() }

  if (query.isFetching || query.isPending) return { status: 'checking', retry }
  if (query.isError) {
    if (query.error instanceof ApiError && query.error.status === 401) {
      return { status: 'unauthenticated', retry }
    }
    return { status: 'error', retry }
  }
  return { status: 'authenticated', user: query.data, retry }
}
