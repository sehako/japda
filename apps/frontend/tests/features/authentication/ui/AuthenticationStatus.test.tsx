import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'

import { AuthenticationStatus } from '../../../../src/features/authentication/ui/AuthenticationStatus.tsx'

const { currentUser, startGoogleLogin } = vi.hoisted(() => ({
  currentUser: vi.fn(),
  startGoogleLogin: vi.fn(),
}))

vi.mock('../../../../src/features/authentication/hook/useCurrentUser.ts', () => ({ useCurrentUser: currentUser }))
vi.mock('../../../../src/features/authentication/util/loginFlow.ts', () => ({ startGoogleLogin }))

beforeEach(() => {
  currentUser.mockReset()
  startGoogleLogin.mockReset()
})

test('비로그인 상태에서 Google 로그인을 시작한다', () => {
  currentUser.mockReturnValue({ status: 'unauthenticated', retry: vi.fn() })
  render(<AuthenticationStatus />)

  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(startGoogleLogin).toHaveBeenCalledOnce()
  expect(startGoogleLogin).toHaveBeenCalledWith()
})

test('확인 중에는 로그인 여부를 표시하지 않는다', () => {
  currentUser.mockReturnValue({ status: 'checking', retry: vi.fn() })
  render(<AuthenticationStatus />)

  expect(screen.getByText('로그인 상태 확인 중')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '로그인' })).not.toBeInTheDocument()
})

test('인증된 상태에는 현재 사용자 이메일만 표시한다', () => {
  currentUser.mockReturnValue({ status: 'authenticated', user: { id: 7, email: 'buyer@example.com', roles: ['BUYER'] }, retry: vi.fn() })
  render(<AuthenticationStatus />)

  expect(screen.getByText('buyer@example.com')).toBeInTheDocument()
  expect(screen.queryByText('BUYER')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '로그인' })).not.toBeInTheDocument()
})

test('조회 오류는 비로그인으로 단정하지 않고 재시도한다', () => {
  const retry = vi.fn()
  currentUser.mockReturnValue({ status: 'error', retry })
  render(<AuthenticationStatus />)

  expect(screen.getByText('로그인 상태를 확인하지 못했습니다')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(retry).toHaveBeenCalledOnce()
  expect(screen.queryByRole('button', { name: '로그인' })).not.toBeInTheDocument()
})
