import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { expect, test, vi } from 'vitest'

vi.mock('../../../src/features/product-registration/hook/useProductRegistration.ts', () => ({
  useProductRegistration: () => ({
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
