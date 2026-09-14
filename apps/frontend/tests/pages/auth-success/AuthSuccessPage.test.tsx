import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { AuthSuccessPage } from '../../../src/pages/auth-success/AuthSuccessPage.tsx'

const { currentUser } = vi.hoisted(() => ({ currentUser: vi.fn() }))
vi.mock('../../../src/features/authentication/hook/useCurrentUser.ts', () => ({ useCurrentUser: currentUser }))

const returnPathKey = 'japda.auth.returnPath'

function Location() {
  const location = useLocation()
  const navigationType = useNavigationType()
  return <><p data-testid="location">{`${location.pathname}${location.search}${location.hash}`}</p><p data-testid="navigation-type">{navigationType}</p></>
}

function renderSuccess() {
  return render(<MemoryRouter initialEntries={['/auth/success']}><Routes>
    <Route path="/auth/success" element={<AuthSuccessPage />} />
    <Route path="*" element={<Location />} />
  </Routes></MemoryRouter>)
}

afterEach(() => { window.sessionStorage.clear(); vi.clearAllMocks(); vi.unstubAllGlobals() })

test('사용자 확인 중에는 저장 경로로 이동하지 않는다', () => {
  window.sessionStorage.setItem(returnPathKey, '/checkout/11?quantity=3')
  currentUser.mockReturnValue({ status: 'checking', retry: vi.fn() })

  renderSuccess()

  expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있습니다.')
  expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  expect(window.sessionStorage.getItem(returnPathKey)).toBe('/checkout/11?quantity=3')
})

test('현재 사용자 확인 후 검색 조건을 유지하며 저장 경로로 복귀한다', async () => {
  window.sessionStorage.setItem(returnPathKey, '/checkout/11?quantity=3#order')
  currentUser.mockReturnValue({ status: 'authenticated', user: { id: 7, email: 'buyer@example.com', roles: ['BUYER'] }, retry: vi.fn() })

  renderSuccess()

  expect(await screen.findByTestId('location')).toHaveTextContent('/checkout/11?quantity=3#order')
  expect(screen.getByTestId('navigation-type')).toHaveTextContent('REPLACE')
  expect(window.sessionStorage.getItem(returnPathKey)).toBeNull()
})

test.each([null, '//evil.example/path', '/auth/failure?error=x'])('복귀 경로 %s가 없거나 잘못되면 메인으로 이동한다', async (stored) => {
  if (stored !== null) window.sessionStorage.setItem(returnPathKey, stored)
  currentUser.mockReturnValue({ status: 'authenticated', user: { id: 7, email: 'buyer@example.com', roles: ['BUYER'] }, retry: vi.fn() })

  renderSuccess()

  expect(await screen.findByTestId('location')).toHaveTextContent('/')
  expect(window.sessionStorage.getItem(returnPathKey)).toBeNull()
})

test('인증되지 않은 경우 성공으로 이동하지 않고 다시 로그인을 안내한다', () => {
  currentUser.mockReturnValue({ status: 'unauthenticated', retry: vi.fn() })

  renderSuccess()

  expect(screen.getByRole('alert')).toHaveTextContent('로그인이 필요합니다.')
  expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다시 확인' })).not.toBeInTheDocument()
  expect(screen.queryByTestId('location')).not.toBeInTheDocument()
})

test('인증되지 않은 경우 원래 경로를 유지하며 Google 로그인을 다시 시작한다', () => {
  window.sessionStorage.setItem(returnPathKey, '/checkout/11?quantity=3')
  const assign = vi.fn()
  vi.stubGlobal('window', { ...window, location: { origin: 'https://japda.example', pathname: '/auth/success', search: '', hash: '', assign }, sessionStorage: window.sessionStorage })
  currentUser.mockReturnValue({ status: 'unauthenticated', retry: vi.fn() })

  renderSuccess()
  screen.getByRole('button', { name: '로그인' }).click()

  expect(window.sessionStorage.getItem(returnPathKey)).toBe('/checkout/11?quantity=3')
  expect(assign).toHaveBeenCalledWith('https://japda.example/oauth2/authorization/google')
})

test('조회 오류는 별도로 안내하고 재시도할 수 있다', () => {
  const retry = vi.fn()
  currentUser.mockReturnValue({ status: 'error', retry })

  renderSuccess()
  screen.getByRole('button', { name: '다시 확인' }).click()

  expect(screen.getByRole('alert')).toHaveTextContent('로그인 상태를 확인하지 못했습니다.')
  expect(retry).toHaveBeenCalledOnce()
})
