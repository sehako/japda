import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'

import { ProductRegistrationForm } from '../../../../src/features/product-registration/ui/ProductRegistrationForm.tsx'

test('상품 등록 완료 후 판매 일정 등록과 새 상품 등록 동작을 제공한다', () => {
  const onScheduleSale = vi.fn()
  const onStartNew = vi.fn()

  render(<ProductRegistrationForm
    name="한정판 후디"
    description=""
    images={[]}
    representativeIndex={null}
    submissionStage="success"
    createdProductId={42}
    onNameChange={vi.fn()}
    onDescriptionChange={vi.fn()}
    onImagesAdd={vi.fn()}
    onImageRemove={vi.fn()}
    onRepresentativeSelect={vi.fn()}
    onSubmit={vi.fn()}
    onCancel={vi.fn()}
    onScheduleSale={onScheduleSale}
    onStartNew={onStartNew}
  />)

  const buttons = screen.getAllByRole('button')
  expect(buttons).toHaveLength(2)
  fireEvent.click(screen.getByRole('button', { name: '판매 일정 등록' }))
  fireEvent.click(screen.getByRole('button', { name: '새 상품 등록' }))
  expect(onScheduleSale).toHaveBeenCalledOnce()
  expect(onStartNew).toHaveBeenCalledOnce()
  expect(screen.queryByText('다음 작업을 선택해 주세요.')).not.toBeInTheDocument()
})
