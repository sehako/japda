import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, test } from 'vitest'

import { SellerSaleSchedulingPage } from '../../../src/pages/seller-sale-scheduling/SellerSaleSchedulingPage.tsx'

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
