import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { useSaleScheduling } from '../../../../src/features/sale-scheduling/hook/useSaleScheduling.ts'

afterEach(() => vi.unstubAllGlobals())

test('일반 상품 목록 오류는 기존 재시도 안내를 유지한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ code: 'INTERNAL_ERROR' }, { status: 500, headers: { 'Content-Type': 'application/problem+json' } })))
  const { result } = renderHook(() => useSaleScheduling())
  await waitFor(() => expect(result.current.listStatus).toBe('error'))
  expect(result.current.listError).toBe('상품 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
  expect(result.current.authError).toBeNull()
})

test('판매자 연결 오류는 등록 가능 상품 목록을 중단한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ code: 'AUTH_SELLER_LINK_REQUIRED' }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } })))
  const { result } = renderHook(() => useSaleScheduling())
  await waitFor(() => expect(result.current.authError).toBe('seller-link-required'))
  expect(result.current.listStatus).toBe('error')
})
