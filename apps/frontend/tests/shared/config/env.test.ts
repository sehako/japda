import assert from 'node:assert/strict'
import { test } from 'vitest'

import { parseBuyerId, parseSellerId, parseTossPaymentPreview } from '../../../src/shared/config/env.ts'

test('양의 정수 판매자 식별자를 반환한다', () => {
  assert.deepEqual(parseSellerId('42'), { valid: true, value: 42 })
})

test('미리보기 활성화와 테스트 클라이언트 키가 함께 있을 때만 결제를 허용한다', () => {
  assert.deepEqual(parseTossPaymentPreview('true', 'test_gck_example'), { valid: true, clientKey: 'test_gck_example' })
  for (const [enabled, key] of [[undefined, 'test_gck_example'], ['false', 'test_gck_example'], ['true', undefined], ['true', 'live_gck_example'], ['TRUE', 'test_gck_example']]) {
    assert.deepEqual(parseTossPaymentPreview(enabled, key), { valid: false, error: '테스트 결제 설정을 확인해 주세요.' })
  }
})

test('누락, 소수, 0 이하 판매자 식별자는 설정 오류로 반환한다', () => {
  for (const value of [undefined, '', '1.5', '0', '-1', 'seller']) {
    assert.deepEqual(parseSellerId(value), {
      valid: false,
      error: '개발용 판매자 식별자 설정을 확인해 주세요.',
    })
  }
})

test('양의 안전한 구매자 식별자만 허용한다', () => {
  assert.deepEqual(parseBuyerId('42'), { valid: true, value: 42 })
  for (const value of [undefined, '', '1.5', '0', '-1', 'buyer', '9007199254740992']) {
    assert.deepEqual(parseBuyerId(value), { valid: false, error: '개발용 구매자 식별자 설정을 확인해 주세요.' })
  }
})
