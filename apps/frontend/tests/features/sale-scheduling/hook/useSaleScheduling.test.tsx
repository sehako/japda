import { act, renderHook } from '@testing-library/react'
import { expect, test } from 'vitest'

import { useSaleScheduling } from '../../../../src/features/sale-scheduling/hook/useSaleScheduling.ts'

test('전달받은 상품을 초기 선택하고 자동 선택 안내를 표시한다', () => {
  const { result } = renderHook(() => useSaleScheduling({ id: 42, name: '방금 등록한 상품' }))

  expect(result.current.selectedProduct).toEqual({ id: 42, name: '방금 등록한 상품' })
  expect(result.current.isPreselectedProduct).toBe(true)
})

test('다른 상품을 선택하면 선택값을 교체하고 자동 선택 안내를 제거한다', () => {
  const { result } = renderHook(() => useSaleScheduling({ id: 42, name: '방금 등록한 상품' }))

  act(() => result.current.toggleProduct({ id: 7, name: '기존 상품' }))

  expect(result.current.selectedProduct).toEqual({ id: 7, name: '기존 상품' })
  expect(result.current.isPreselectedProduct).toBe(false)
})

test('초기 상품이 없으면 미선택 상태로 시작한다', () => {
  const { result } = renderHook(() => useSaleScheduling())

  expect(result.current.selectedProduct).toBeNull()
  expect(result.current.isPreselectedProduct).toBe(false)
})
