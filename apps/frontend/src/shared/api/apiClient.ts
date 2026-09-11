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
  let response: Response
  try {
    response = await fetcher(buildUrl(options.baseUrl ?? '', path), init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(DEFAULT_API_ERROR_MESSAGE, {}, true)
  }

  if (!response.ok) {
    const contentType = response.headers.get('Content-Type') ?? ''
    if (contentType.includes('application/problem+json')) {
      try {
        const problem = parseProblemDetail(await response.json(), response.status)
        if (problem) throw new ApiError(problem.detail ?? DEFAULT_API_ERROR_MESSAGE, problem)
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
