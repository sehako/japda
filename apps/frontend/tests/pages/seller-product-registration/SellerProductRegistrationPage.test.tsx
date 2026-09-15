import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, expect, test, vi } from 'vitest'

const { currentUser, startGoogleLogin, registrationState } = vi.hoisted(() => ({
  currentUser: vi.fn(),
  startGoogleLogin: vi.fn(),
  registrationState: vi.fn(),
}))
vi.mock('../../../src/features/authentication/hook/useCurrentUser.ts', () => ({ useCurrentUser: currentUser }))
vi.mock('../../../src/features/authentication/util/loginFlow.ts', () => ({ startGoogleLogin }))

vi.mock('../../../src/features/product-registration/hook/useProductRegistration.ts', () => ({
  useProductRegistration: () => registrationState({
    name: '방금 등록한 상품',
    description: '',
    images: [],
    representativeIndex: null,
    fieldErrors: {},
    formError: null,
    submissionStage: 'success',
    createdProductId: 42,
    registeredProduct: { id: 42, name: '방금 등록한 상품' },
    changeName: vi.fn(),
    changeDescription: vi.fn(),
    addImages: vi.fn(),
    removeImage: vi.fn(),
    selectRepresentative: vi.fn(),
    submit: vi.fn(),
    cancel: vi.fn(),
    startNew: vi.fn(),
  }),
}))

import { SellerProductRegistrationPage } from '../../../src/pages/seller-product-registration/SellerProductRegistrationPage.tsx'

beforeEach(() => {
  currentUser.mockReturnValue({ status: 'authenticated', retry: vi.fn() })
  startGoogleLogin.mockReset()
  registrationState.mockImplementation((state) => state)
})

function LocationState() {
  const location = useLocation()
  return <output aria-label="이동 정보">{JSON.stringify({ pathname: location.pathname, state: location.state })}</output>
}

test('판매 일정 등록을 선택하면 등록 상품을 navigation state로 전달한다', () => {
  render(<MemoryRouter initialEntries={['/seller/products/new']}><Routes>
    <Route path="/seller/products/new" element={<SellerProductRegistrationPage />} />
    <Route path="/seller/sales/new" element={<LocationState />} />
  </Routes></MemoryRouter>)

  fireEvent.click(screen.getByRole('button', { name: '판매 일정 등록' }))

  expect(screen.getByRole('status', { name: '이동 정보' })).toHaveTextContent(JSON.stringify({
    pathname: '/seller/sales/new',
    state: { preselectedProduct: { id: 42, name: '방금 등록한 상품' } },
  }))
})

test('로그인 확인 중에는 상품 등록 폼을 표시하지 않는다', () => {
  currentUser.mockReturnValue({ status: 'checking', retry: vi.fn() })
  render(<MemoryRouter><SellerProductRegistrationPage /></MemoryRouter>)
  expect(screen.queryByRole('button', { name: '판매 일정 등록' })).not.toBeInTheDocument()
  expect(screen.getByText('로그인 상태를 확인하는 중입니다.')).toBeInTheDocument()
})

test('로그인이 필요할 때 현재 경로를 보존하며 로그인을 시작한다', () => {
  currentUser.mockReturnValue({ status: 'unauthenticated', retry: vi.fn() })
  render(<MemoryRouter><SellerProductRegistrationPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }))
  expect(startGoogleLogin).toHaveBeenCalledWith()
})

test('인증 조회 오류는 재확인을 제공하고 등록 폼을 차단한다', () => {
  const retry = vi.fn()
  currentUser.mockReturnValue({ status: 'error', retry })
  render(<MemoryRouter><SellerProductRegistrationPage /></MemoryRouter>)
  expect(screen.queryByRole('button', { name: '판매 일정 등록' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))
  expect(retry).toHaveBeenCalledOnce()
})

test('상품 등록 중 인증 만료가 확인되면 재로그인을 제공하고 등록 폼을 차단한다', () => {
  registrationState.mockImplementation((state) => ({ ...state, authError: 'unauthenticated' }))
  render(<MemoryRouter><SellerProductRegistrationPage /></MemoryRouter>)
  expect(screen.queryByRole('button', { name: '판매 일정 등록' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }))
  expect(startGoogleLogin).toHaveBeenCalledWith()
})

test('판매자 연결이 없으면 등록 폼을 중단한다', () => {
  registrationState.mockImplementation((state) => ({ ...state, authError: 'seller-link-required' }))
  render(<MemoryRouter><SellerProductRegistrationPage /></MemoryRouter>)
  expect(screen.getByText('판매자 계정 연결이 필요합니다.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '판매 일정 등록' })).not.toBeInTheDocument()
})
