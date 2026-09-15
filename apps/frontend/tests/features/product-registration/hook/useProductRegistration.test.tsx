import { act, renderHook } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

const { createProduct, registerProductImages } = vi.hoisted(() => ({
  createProduct: vi.fn(),
  registerProductImages: vi.fn(),
}))

vi.mock('../../../../src/features/product-registration/api/productRegistrationApi.ts', () => ({
  createProduct,
  registerProductImages,
}))
vi.mock('../../../../src/shared/config/env.ts', () => ({
  apiBaseUrl: '',
}))

import { useProductRegistration } from '../../../../src/features/product-registration/hook/useProductRegistration.ts'

afterEach(() => vi.unstubAllGlobals())

test('상품 등록이 완료되면 서버가 반환한 상품 식별자와 이름을 보관한다', async () => {
  vi.stubGlobal('URL', Object.assign(class extends URL {}, {
    createObjectURL: vi.fn(() => 'blob:product'),
    revokeObjectURL: vi.fn(),
  }))
  createProduct.mockResolvedValue({
    id: 42,
    sellerId: 7,
    name: '서버 상품명',
    description: null,
    status: 'DRAFT',
    createdAt: '2026-09-12T00:00:00Z',
  })
  registerProductImages.mockResolvedValue({ productId: 42, status: 'READY', images: [] })
  const { result } = renderHook(() => useProductRegistration())

  act(() => {
    result.current.changeName('입력 상품명')
    result.current.addImages([new File(['image'], 'product.jpg', { type: 'image/jpeg' })])
  })
  await act(() => result.current.submit())

  expect(result.current.submissionStage).toBe('success')
  expect(result.current.registeredProduct).toEqual({ id: 42, name: '서버 상품명' })

  act(() => result.current.startNew())

  expect(result.current.submissionStage).toBe('input')
  expect(result.current.registeredProduct).toBeNull()
})
