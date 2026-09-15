export const DEFAULT_API_ERROR_MESSAGE = '상품을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.'

export interface ProblemDetailData {
  status?: number
  code?: string
  detail?: string
  errors?: Record<string, string>
}

export class ApiError extends Error {
  readonly status?: number
  readonly code?: string
  readonly detail?: string
  readonly fieldErrors: Record<string, string>
  readonly isNetworkError: boolean

  constructor(message: string, problem: ProblemDetailData = {}, isNetworkError = false) {
    super(message)
    this.name = 'ApiError'
    this.status = problem.status
    this.code = problem.code
    this.detail = problem.detail
    this.fieldErrors = problem.errors ?? {}
    this.isNetworkError = isNetworkError
  }
}

export interface ApiClientOptions {
  baseUrl?: string
  fetcher?: typeof fetch
  protected?: boolean
}

interface CsrfToken {
  token: string
  headerName: string
}

interface CsrfState {
  token?: CsrfToken
  pending?: Promise<CsrfToken>
  generation: number
}

const csrfStates = new WeakMap<typeof fetch, Map<string, CsrfState>>()

function csrfState(options: ApiClientOptions): CsrfState {
  const fetcher = options.fetcher ?? fetch
  let byBaseUrl = csrfStates.get(fetcher)
  if (!byBaseUrl) {
    byBaseUrl = new Map()
    csrfStates.set(fetcher, byBaseUrl)
  }
  const baseUrl = options.baseUrl ?? ''
  let state = byBaseUrl.get(baseUrl)
  if (!state) {
    state = { generation: 0 }
    byBaseUrl.set(baseUrl, state)
  }
  return state
}

function invalidateCsrfToken(options: ApiClientOptions): void {
  const state = csrfState(options)
  state.generation += 1
  state.token = undefined
  state.pending = undefined
}

async function csrfToken(options: ApiClientOptions): Promise<CsrfToken> {
  const state = csrfState(options)
  if (state.token) return state.token
  if (state.pending) return state.pending
  const generation = state.generation
  const pending = requestApi<unknown>('/api/auth/csrf', { method: 'GET', credentials: 'include' }, options)
    .then((value) => {
      if (typeof value !== 'object' || value === null) throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
      const response = value as Record<string, unknown>
      if (typeof response.token !== 'string' || !response.token.trim()
        || typeof response.headerName !== 'string' || !response.headerName.trim()) {
        throw new ApiError(DEFAULT_API_ERROR_MESSAGE)
      }
      const token = { token: response.token, headerName: response.headerName }
      if (state.generation === generation) state.token = token
      return token
    })
    .finally(() => { if (state.pending === pending) state.pending = undefined })
  state.pending = pending
  return pending
}

export async function prepareCsrfToken(options: ApiClientOptions = {}): Promise<void> {
  await csrfToken(options)
}

function buildUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return typeof value === 'object' && value !== null
    && Object.values(value).every((item) => typeof item === 'string')
}

function parseProblemDetail(value: unknown, status: number): ProblemDetailData | null {
  if (typeof value !== 'object' || value === null) return null
  const problem = value as Record<string, unknown>
  if (typeof problem.code !== 'string' && typeof problem.detail !== 'string') return null
  return {
    status,
    code: typeof problem.code === 'string' ? problem.code : undefined,
    detail: typeof problem.detail === 'string' ? problem.detail : undefined,
    errors: isStringRecord(problem.errors) ? problem.errors : undefined,
  }
}

export async function requestApi<T>(
  path: string,
  init: RequestInit,
  options: ApiClientOptions = {},
): Promise<T> {
  const fetcher = options.fetcher ?? fetch
  const isProtected = options.protected === true
  const method = init.method?.toUpperCase() ?? 'GET'
  const isChanging = !['GET', 'HEAD', 'OPTIONS'].includes(method)
  let requestInit = init
  if (isProtected) {
    const headers = new Headers(init.headers)
    if (isChanging) {
      const csrf = await csrfToken(options)
      headers.set(csrf.headerName, csrf.token)
    }
    requestInit = { ...init, credentials: 'include', headers }
  }
  let response: Response
  try {
    response = await fetcher(buildUrl(options.baseUrl ?? '', path), requestInit)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(DEFAULT_API_ERROR_MESSAGE, {}, true)
  }

  if (!response.ok) {
    const contentType = response.headers.get('Content-Type') ?? ''
    if (contentType.includes('application/problem+json')) {
      try {
        const problem = parseProblemDetail(await response.json(), response.status)
        if (problem) {
          if ((response.status === 401 && problem.code === 'AUTH_UNAUTHENTICATED')
            || (response.status === 403 && problem.code === 'AUTH_CSRF_INVALID')) invalidateCsrfToken(options)
          throw new ApiError(problem.detail ?? DEFAULT_API_ERROR_MESSAGE, problem)
        }
      } catch (error) {
        if (error instanceof ApiError) throw error
      }
    }
    throw new ApiError(DEFAULT_API_ERROR_MESSAGE, { status: response.status })
  }

  try {
    return await response.json() as T
  } catch {
    throw new ApiError(DEFAULT_API_ERROR_MESSAGE, { status: response.status })
  }
}
