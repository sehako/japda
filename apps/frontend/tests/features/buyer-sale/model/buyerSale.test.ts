import { describe, expect, test } from 'vitest'

import {
  buildCalendarDays,
  buildProductImageUrl,
  canMoveToNextMonth,
  formatKoreanPrice,
  getKoreanToday,
  getSaleStatusLabel,
  isSelectableSaleDate,
  millisecondsUntilNextKoreanMidnight,
} from '../../../../src/features/buyer-sale/model/buyerSale.ts'

describe('구매자 판매일 계산', () => {
  test('브라우저 시간대와 관계없이 한국 자정에 오늘 날짜가 바뀐다', () => {
    expect(getKoreanToday(new Date('2026-09-10T14:59:59Z'))).toBe('2026-09-10')
    expect(getKoreanToday(new Date('2026-09-10T15:00:00Z'))).toBe('2026-09-11')
  })

  test('과거와 오늘 및 내일은 선택하고 모레부터 선택하지 못한다', () => {
    expect(isSelectableSaleDate('2020-01-01', '2026-09-10')).toBe(true)
    expect(isSelectableSaleDate('2026-09-10', '2026-09-10')).toBe(true)
    expect(isSelectableSaleDate('2026-09-11', '2026-09-10')).toBe(true)
    expect(isSelectableSaleDate('2026-09-12', '2026-09-10')).toBe(false)
  })

  test('윤년 2월의 첫 요일과 날짜 수를 달력 빈칸에 반영한다', () => {
    const days = buildCalendarDays(2024, 2)
    expect(days).toHaveLength(35)
    expect(days.slice(0, 4)).toEqual([null, null, null, null])
    expect(days[4]).toBe('2024-02-01')
    expect(days[32]).toBe('2024-02-29')
    expect(days.slice(-2)).toEqual([null, null])
  })

  test('다음 달에 선택 가능한 날이 하나라도 있을 때만 이동한다', () => {
    expect(canMoveToNextMonth(2026, 8, '2026-09-01')).toBe(true)
    expect(canMoveToNextMonth(2026, 9, '2026-09-01')).toBe(false)
    expect(canMoveToNextMonth(2026, 12, '2027-01-01')).toBe(true)
  })

  test('한국 자정까지 남은 시간을 계산한다', () => {
    expect(millisecondsUntilNextKoreanMidnight(new Date('2026-09-10T14:59:59Z'))).toBe(1000)
  })
})

describe('구매자 판매 상품 표시 변환', () => {
  test('가격과 판매 상태를 화면 계약으로 변환한다', () => {
    expect(formatKoreanPrice(120000)).toBe('120,000원')
    expect(getSaleStatusLabel('UPCOMING')).toBe('UPCOMING')
    expect(getSaleStatusLabel('ON_SALE')).toBe('LIVE')
    expect(getSaleStatusLabel('ENDED')).toBe('ENDED')
  })

  test('유효한 이미지 기준 URL과 상대 경로의 슬래시를 하나로 결합한다', () => {
    expect(buildProductImageUrl('https://images.example.com/', '/products/10/main.webp'))
      .toBe('https://images.example.com/products/10/main.webp')
  })

  test('이미지 기준 URL이 없거나 http URL이 아니면 대체 영역을 사용한다', () => {
    expect(buildProductImageUrl('', '/products/10/main.webp')).toBeNull()
    expect(buildProductImageUrl('not-a-url', '/products/10/main.webp')).toBeNull()
    expect(buildProductImageUrl('ftp://images.example.com', '/products/10/main.webp')).toBeNull()
  })
})
