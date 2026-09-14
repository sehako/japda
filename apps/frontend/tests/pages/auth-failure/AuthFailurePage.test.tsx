import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { AuthFailurePage } from '../../../src/pages/auth-failure/AuthFailurePage.tsx'

const returnPathKey = 'japda.auth.returnPath'

afterEach(() => { window.sessionStorage.clear(); vi.unstubAllGlobals() })

function renderFailure(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><AuthFailurePage /></MemoryRouter>)
}

test('검증되지 않은 이메일 오류를 구분해 안내한다', () => {
  renderFailure('/auth/failure?error=EMAIL_UNVERIFIED')

  expect(screen.getByRole('alert')).toHaveTextContent('검증된 이메일이 필요합니다.')
})

test.each(['GOOGLE_LOGIN_FAILED', 'UNKNOWN'])('실패 코드 %s는 일반 안내를 표시하고 원문을 노출하지 않는다', (error) => {
  renderFailure(`/auth/failure?error=${error}`)

  expect(screen.getByRole('alert')).toHaveTextContent('Google 로그인에 실패했습니다.')
  expect(document.body).not.toHaveTextContent('UNKNOWN')
})

test('재시도 시 원래 복귀 경로를 유지하고 OAuth2 이동을 시작한다', () => {
  window.sessionStorage.setItem(returnPathKey, '/sales/11?tab=detail')
  const assign = vi.fn()
  vi.stubGlobal('window', { ...window, location: { origin: 'https://japda.example', pathname: '/auth/failure', search: '?error=GOOGLE_LOGIN_FAILED', hash: '', assign }, sessionStorage: window.sessionStorage })
  renderFailure('/auth/failure?error=GOOGLE_LOGIN_FAILED')

  screen.getByRole('button', { name: 'Google 로그인 다시 시도' }).click()

  expect(window.sessionStorage.getItem(returnPathKey)).toBe('/sales/11?tab=detail')
  expect(assign).toHaveBeenCalledWith('https://japda.example/oauth2/authorization/google')
})

test('실패 화면에서 메인으로 이동하는 링크를 제공한다', () => {
  renderFailure('/auth/failure')

  expect(screen.getByRole('link', { name: '메인으로 이동' })).toHaveAttribute('href', '/')
})
