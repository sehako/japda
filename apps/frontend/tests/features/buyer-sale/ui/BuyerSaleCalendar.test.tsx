import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'

import { BuyerSaleCalendar } from '../../../../src/features/buyer-sale/ui/BuyerSaleCalendar.tsx'

test('선택 날짜와 오늘 및 미래 제한을 버튼 상태로 전달한다', () => {
  const selectDate = vi.fn()
  render(<BuyerSaleCalendar
    month={{ year: 2026, month: 9 }}
    selectedDate="2026-09-10"
    today="2026-09-10"
    onPreviousMonth={vi.fn()}
    onNextMonth={vi.fn()}
    onSelectDate={selectDate}
  />)

  expect(screen.getByRole('heading', { name: '2026년 9월' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '10일, 오늘' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: '11일' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '12일' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '9일' }))
  expect(selectDate).toHaveBeenCalledWith('2026-09-09')
})

test('다음 달에 선택 가능한 날짜가 없으면 다음 달 이동을 비활성화한다', () => {
  render(<BuyerSaleCalendar
    month={{ year: 2026, month: 9 }}
    selectedDate="2026-09-10"
    today="2026-09-10"
    onPreviousMonth={vi.fn()}
    onNextMonth={vi.fn()}
    onSelectDate={vi.fn()}
  />)
  expect(screen.getByRole('button', { name: '이전 달' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '다음 달' })).toBeDisabled()
})
