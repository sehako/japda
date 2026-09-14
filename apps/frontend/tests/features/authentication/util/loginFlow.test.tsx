import { afterEach, expect, test, vi } from 'vitest'

import { consumeReturnPath, readReturnPath, startGoogleLogin } from '../../../../src/features/authentication/util/loginFlow.ts'

const returnPathKey = 'japda.auth.returnPath'

afterEach(() => vi.unstubAllGlobals())

function stubWindow(path = '/checkout/11?quantity=3#payment', stored?: string) {
  const values = new Map<string, string>()
  if (stored !== undefined) values.set(returnPathKey, stored)
  const sessionStorage = {
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => { values.set(key, value) }),
    removeItem: vi.fn((key: string) => { values.delete(key) }),
  }
  const url = new URL(path, 'https://japda.example')
  const assign = vi.fn()
  vi.stubGlobal('window', { location: { origin: url.origin, pathname: url.pathname, search: url.search, hash: url.hash, assign }, sessionStorage })
  return { values, sessionStorage, assign }
}

test('로그인 시작 시 현재 경로와 검색 조건을 저장하고 OAuth2 주소로 전체 이동한다', () => {
  const { values, assign } = stubWindow()

  startGoogleLogin()

  expect(values.get(returnPathKey)).toBe('/checkout/11?quantity=3#payment')
  expect(assign).toHaveBeenCalledWith('https://japda.example/oauth2/authorization/google')
})

test('로그인 실패 후 재시도는 기존 복귀 경로를 유지한다', () => {
  const { values } = stubWindow('/auth/failure?error=GOOGLE_LOGIN_FAILED', '/sales/11?tab=details')

  startGoogleLogin(true)

  expect(values.get(returnPathKey)).toBe('/sales/11?tab=details')
})

test('복귀 경로를 읽은 뒤 소비하면 저장값을 제거한다', () => {
  const { values } = stubWindow('/auth/success', '/checkout/11?quantity=3')

  expect(readReturnPath()).toBe('/checkout/11?quantity=3')
  expect(consumeReturnPath()).toBe('/checkout/11?quantity=3')
  expect(values.has(returnPathKey)).toBe(false)
})

test.each([
  'https://evil.example/path', '//evil.example/path', '/\\evil.example/path',
  'checkout/11', '/auth/success', '/auth/failure?error=x',
  '/auth/%73uccess', '/auth/%66ailure', '/AUTH/SUCCESS',
])('위험하거나 결과 페이지인 복귀 경로 %s를 메인으로 대체한다', (stored) => {
  stubWindow('/auth/success', stored)

  expect(consumeReturnPath()).toBe('/')
})

test('저장소에 접근할 수 없어도 로그인은 시작하고 복귀 경로는 메인으로 처리한다', () => {
  const { sessionStorage, assign } = stubWindow()
  sessionStorage.setItem.mockImplementation(() => { throw new Error('차단됨') })
  sessionStorage.getItem.mockImplementation(() => { throw new Error('차단됨') })

  expect(() => startGoogleLogin()).not.toThrow()
  expect(assign).toHaveBeenCalledOnce()
  expect(readReturnPath()).toBe('/')
  expect(consumeReturnPath()).toBe('/')
})

test('저장 경로를 제거할 수 없으면 메인으로 복귀한다', () => {
  const { sessionStorage } = stubWindow('/auth/success', '/checkout/11?quantity=3')
  sessionStorage.removeItem.mockImplementation(() => { throw new Error('차단됨') })

  expect(consumeReturnPath()).toBe('/')
})
