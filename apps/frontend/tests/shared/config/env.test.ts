import assert from 'node:assert/strict'
import { test } from 'vitest'

import { parseTossPaymentPreview } from '../../../src/shared/config/env.ts'

test('미리보기 활성화와 테스트 클라이언트 키가 함께 있을 때만 결제를 허용한다', () => {
  assert.deepEqual(parseTossPaymentPreview('true', 'test_gck_example'), { valid: true, clientKey: 'test_gck_example' })
  for (const [enabled, key] of [[undefined, 'test_gck_example'], ['false', 'test_gck_example'], ['true', undefined], ['true', 'live_gck_example'], ['TRUE', 'test_gck_example']]) {
    assert.deepEqual(parseTossPaymentPreview(enabled, key), { valid: false, error: '테스트 결제 설정을 확인해 주세요.' })
  }
})
