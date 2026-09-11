import assert from 'node:assert/strict'
import { test } from 'vitest'

import {
  getRegistrationWindow,
  mapApiErrorToSaleErrors,
  validateSaleScheduling,
} from '../../../../src/features/sale-scheduling/model/saleScheduling.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

test('KST 09시 전에는 등록을 닫고 정각부터 다음 날 판매일을 연다', () => {
  assert.deepEqual(getRegistrationWindow(new Date('2026-09-10T23:59:59Z')), {
    isOpen: false,
    saleDate: null,
    title: '오늘 09:00부터 등록할 수 있습니다.',
    description: '한국 표준시 기준 09:00부터 다음 날 판매 일정을 등록할 수 있습니다.',
  })
  assert.deepEqual(getRegistrationWindow(new Date('2026-09-11T00:00:00Z')), {
    isOpen: true,
    saleDate: '2026-09-12',
    title: '2026년 9월 12일 판매 일정을 등록할 수 있습니다.',
    description: '오늘 09:00부터 24:00 전까지 등록할 수 있습니다.',
  })
})

test('KST 자정에는 새 날짜의 등록 가능 상태를 다시 계산한다', () => {
  assert.equal(getRegistrationWindow(new Date('2026-09-11T14:59:59Z')).saleDate, '2026-09-12')
  assert.equal(getRegistrationWindow(new Date('2026-09-11T15:00:00Z')).isOpen, false)
})

test('정상 입력은 정수 요청 값으로 변환한다', () => {
  const result = validateSaleScheduling({ productId: 10, saleDate: '2026-09-12', price: '35000', quantity: '100' }, '2026-09-12')
  assert.deepEqual(result, { valid: true, value: { productId: 10, saleDate: '2026-09-12', price: 35000, quantity: 100 } })
})

test('부호, 공백, 소수, 지수와 정수 상한 초과를 거부한다', () => {
  for (const price of ['+1', ' 1', '1 ', '1.0', '1e3', '9007199254740992']) {
    const result = validateSaleScheduling({ productId: 10, saleDate: '2026-09-12', price, quantity: '1' }, '2026-09-12')
    assert.equal(result.valid, false, price)
    if (!result.valid) assert.ok(result.errors.price)
  }
  const result = validateSaleScheduling({ productId: 10, saleDate: '2026-09-12', price: '1', quantity: '2147483648' }, '2026-09-12')
  assert.equal(result.valid, false)
  if (!result.valid) assert.ok(result.errors.quantity)
})

test('상품과 판매일 오류를 해당 필드에 반환한다', () => {
  const result = validateSaleScheduling({ productId: null, saleDate: '2026-09-13', price: '1', quantity: '1' }, '2026-09-12')
  assert.deepEqual(result, { valid: false, errors: { productId: '판매할 상품을 선택해 주세요.', saleDate: '현재 등록 가능한 판매일을 확인해 주세요.' } })
})

test('서버 오류 코드와 속성을 필드 및 폼 오류로 구분한다', () => {
  const fieldError = new ApiError('실패', { code: 'SALE_CAPACITY_EXCEEDED', detail: '판매 일정 정원이 가득 찼습니다.' })
  assert.deepEqual(mapApiErrorToSaleErrors(fieldError), { fieldErrors: { saleDate: '판매 일정 정원이 가득 찼습니다.' }, formError: null, refreshProducts: false })

  const duplicate = new ApiError('실패', { code: 'SALE_SELLER_ALREADY_REGISTERED' })
  assert.deepEqual(mapApiErrorToSaleErrors(duplicate), { fieldErrors: {}, formError: '같은 판매일에는 이미 등록한 일정이 있습니다.', refreshProducts: false })
})
