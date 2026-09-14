import { apiBaseUrl } from '../../../shared/config/env.ts'

const returnPathKey = 'japda.auth.returnPath'

function safeReturnPath(value: string | null): string {
  if (!value?.startsWith('/') || value.startsWith('//')) return '/'

  try {
    const url = new URL(value, window.location.origin)
    if (url.origin !== window.location.origin) return '/'
    const pathname = decodeURIComponent(url.pathname).toLowerCase()
    if (pathname === '/auth/success' || pathname === '/auth/failure') return '/'
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return '/'
  }
}

export function readReturnPath(): string {
  try {
    return safeReturnPath(window.sessionStorage.getItem(returnPathKey))
  } catch {
    return '/'
  }
}

export function consumeReturnPath(): string {
  try {
    const value = window.sessionStorage.getItem(returnPathKey)
    window.sessionStorage.removeItem(returnPathKey)
    return safeReturnPath(value)
  } catch {
    return '/'
  }
}

export function startGoogleLogin(preserveReturnPath = false): void {
  if (!preserveReturnPath) {
    try {
      const { pathname, search, hash } = window.location
      window.sessionStorage.setItem(returnPathKey, `${pathname}${search}${hash}`)
    } catch {
      // 저장소가 차단되어도 로그인은 시작한다.
    }
  }

  let origin = window.location.origin
  if (apiBaseUrl) {
    try {
      origin = new URL(apiBaseUrl, origin).origin
    } catch {
      // 잘못된 설정에서는 현재 origin의 프록시 경로를 사용한다.
    }
  }
  window.location.assign(`${origin}/oauth2/authorization/google`)
}
