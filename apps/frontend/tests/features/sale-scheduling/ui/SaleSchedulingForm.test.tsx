import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, test } from 'vitest'

import { useSaleScheduling } from '../../../../src/features/sale-scheduling/hook/useSaleScheduling.ts'
import { SaleSchedulingForm } from '../../../../src/features/sale-scheduling/ui/SaleSchedulingForm.tsx'

const preselectedProduct = { id: 42, name: '방금 등록한 상품' }
const otherProduct = { id: 7, name: '기존 상품' }

function SaleSchedulingHarness() {
  const scheduling = useSaleScheduling(preselectedProduct)
  return <SaleSchedulingForm scheduling={{
    ...scheduling,
    currentPage: { cursor: null, items: [preselectedProduct, otherProduct], nextCursor: null },
    listStatus: 'ready',
  }} />
}

test('목록의 등록 상품을 선택 표시하고 다른 상품을 누르면 교체한다', () => {
  render(<MemoryRouter><SaleSchedulingHarness /></MemoryRouter>)
  const preselectedCheckbox = screen.getByRole('checkbox', { name: '방금 등록한 상품 선택' })
  const otherCheckbox = screen.getByRole('checkbox', { name: '기존 상품 선택' })

  expect(preselectedCheckbox).toBeChecked()
  expect(screen.getByText('방금 등록한 상품이 선택되었습니다. 다른 상품을 선택하면 변경됩니다.')).toBeInTheDocument()

  fireEvent.click(otherCheckbox)

  expect(preselectedCheckbox).not.toBeChecked()
  expect(otherCheckbox).toBeChecked()
  const selectedSummary = screen.getByText('SELECTED PRODUCT').parentElement
  expect(selectedSummary).not.toBeNull()
  expect(within(selectedSummary as HTMLElement).getByText('기존 상품')).toBeInTheDocument()
  expect(screen.queryByText('방금 등록한 상품이 선택되었습니다. 다른 상품을 선택하면 변경됩니다.')).not.toBeInTheDocument()
})
