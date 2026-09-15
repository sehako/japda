import { ApiError, prepareCsrfToken, requestApi } from '../../../shared/api/apiClient.ts'
import type { ApiClientOptions } from '../../../shared/api/apiClient.ts'
import type { CurrentUser, CurrentUserRole } from '../model/currentUser.ts'

const CURRENT_USER_ERROR_MESSAGE = '로그인 상태를 확인하지 못했습니다.'
const ROLE_ORDER: CurrentUserRole[] = ['ADMIN', 'BUYER']

function isCurrentUser(value: unknown): value is CurrentUser {
  if (typeof value !== 'object' || value === null) return false
  const user = value as Record<string, unknown>
  if (typeof user.id !== 'number' || !Number.isSafeInteger(user.id) || user.id <= 0
    || typeof user.email !== 'string' || !Array.isArray(user.roles)) return false

  const roles = user.roles as unknown[]
  return roles.every((role) => ROLE_ORDER.includes(role as CurrentUserRole))
    && roles.every((role, index) => index === 0
      || ROLE_ORDER.indexOf(roles[index - 1] as CurrentUserRole) < ROLE_ORDER.indexOf(role as CurrentUserRole))
}

export async function fetchCurrentUser(signal?: AbortSignal, options?: ApiClientOptions): Promise<CurrentUser> {
  let response: unknown
  try {
    response = await requestApi<unknown>('/api/auth/me', { method: 'GET', credentials: 'include', signal }, options)
  } catch (error) {
    if (error instanceof ApiError) {
      throw new ApiError(CURRENT_USER_ERROR_MESSAGE, {
        status: error.status,
        code: error.code,
        detail: error.detail,
        errors: error.fieldErrors,
      }, error.isNetworkError)
    }
    throw error
  }

  if (!isCurrentUser(response)) throw new ApiError(CURRENT_USER_ERROR_MESSAGE)
  void prepareCsrfToken(options).catch(() => {})
  return response
}
