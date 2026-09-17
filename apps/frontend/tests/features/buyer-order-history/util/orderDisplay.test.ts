import { describe, expect, test } from 'vitest'

import { formatOrderDateTime, formatWon } from '../../../../src/features/buyer-order-history/util/orderDisplay.ts'

describe('주문 표시값 변환', () => {
  test('원화 금액에 한국어 천 단위 구분자와 원 단위를 붙인다', () => {
    expect(formatWon(1234567)).toBe('1,234,567원')
  })

  test('ISO 시각을 사용자 로컬 시간대의 한국어 날짜와 시각으로 표시한다', () => {
    const localDateTime = new Date(2026, 8, 14, 14, 32)
    expect(formatOrderDateTime(localDateTime.toISOString())).toBe('2026. 09. 14. 14:32')
  })
})
