import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, test, vi } from 'vitest'

const { currentUser, startGoogleLogin } = vi.hoisted(() => ({ currentUser: vi.fn(), startGoogleLogin: vi.fn() }))
vi.mock('../../../src/features/authentication/hook/useCurrentUser.ts', () => ({ useCurrentUser: currentUser }))
vi.mock('../../../src/features/authentication/util/loginFlow.ts', () => ({ startGoogleLogin }))

import { SellerSaleSchedulingPage } from '../../../src/pages/seller-sale-scheduling/SellerSaleSchedulingPage.tsx'

beforeEach(() => {
  currentUser.mockReturnValue({ status: 'authenticated', retry: vi.fn() })
  startGoogleLogin.mockReset()
})

function renderPage(state?: unknown) {
  return render(<MemoryRouter initialEntries={[{ pathname: '/seller/sales/new', state }]}><SellerSaleSchedulingPage /></MemoryRouter>)
}

test('유효한 navigation state의 상품을 선택하고 변경 안내를 표시한다', () => {
  renderPage({ preselectedProduct: { id: 42, name: '방금 등록한 상품' } })

  expect(screen.getByText('방금 등록한 상품')).toBeInTheDocument()
  expect(screen.getByText('방금 등록한 상품이 선택되었습니다. 다른 상품을 선택하면 변경됩니다.')).toBeInTheDocument()
})

test.each([
  undefined,
  null,
  {},
  { preselectedProduct: { id: 0, name: '상품' } },
  { preselectedProduct: { id: 1.5, name: '상품' } },
  { preselectedProduct: { id: Number.MAX_SAFE_INTEGER + 1, name: '상품' } },
  { preselectedProduct: { id: 1, name: '   ' } },
  { preselectedProduct: { id: 1, name: 7 } },
])('없거나 잘못된 navigation state는 미선택 상태로 시작한다', (state) => {
  renderPage(state)

  expect(screen.getByText('선택된 상품이 없습니다.')).toBeInTheDocument()
  expect(screen.queryByText('방금 등록한 상품이 선택되었습니다. 다른 상품을 선택하면 변경됩니다.')).not.toBeInTheDocument()
})

test('로그인 확인 중에는 등록 가능 상품을 조회하거나 일정을 등록할 수 없다', () => {
  currentUser.mockReturnValue({ status: 'checking', retry: vi.fn() })
  const fetcher = vi.spyOn(globalThis, 'fetch')
  renderPage()
  expect(fetcher).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: '판매 일정 등록' })).not.toBeInTheDocument()
  fetcher.mockRestore()
})

test('로그인이 필요하면 현재 경로를 보존하는 로그인 동작을 제공한다', () => {
  currentUser.mockReturnValue({ status: 'unauthenticated', retry: vi.fn() })
  renderPage()
  fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }))
  expect(startGoogleLogin).toHaveBeenCalledWith()
})

test('인증 조회 오류는 비로그인으로 단정하지 않고 재확인을 제공한다', () => {
  const retry = vi.fn()
  currentUser.mockReturnValue({ status: 'error', retry })
  renderPage()
  fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))
  expect(retry).toHaveBeenCalledOnce()
})
