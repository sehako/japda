import { describe, expect, test } from 'vitest'

import { hasTossSuccessParameters, parseTossSuccessParameters } from '../../../../src/features/buyer-checkout/model/tossPaymentResult.ts'

describe('토스 성공 리다이렉트', () => {
  test('검증한 결제 키, 주문 식별자와 금액을 승인 요청 값으로 반환한다', () => {
    const params = new URLSearchParams('paymentKey=pay_123&orderId=order-123&amount=70000&saleId=8')
    expect(parseTossSuccessParameters(params)).toEqual({ paymentKey: 'pay_123', orderId: 'order-123', amount: 70000 })
    expect(hasTossSuccessParameters(params)).toBe(true)
  })

  test('허용 범위의 끝값도 승인 요청 값으로 반환한다', () => {
    const paymentKey = 'x'.repeat(200)
    const orderId = 'o'.repeat(64)
    const params = new URLSearchParams({ paymentKey, orderId, amount: String(Number.MAX_SAFE_INTEGER) })
    expect(parseTossSuccessParameters(params)).toEqual({ paymentKey, orderId, amount: Number.MAX_SAFE_INTEGER })
  })

  test.each([
    'orderId=order-123&amount=70000',
    'paymentKey=a&paymentKey=b&orderId=order-123&amount=70000',
    'paymentKey=pay_123&orderId=order-123&orderId=order-456&amount=70000',
    'paymentKey=pay_123&orderId=order-123&amount=70000&amount=70000',
    'paymentKey=%20%20&orderId=order-123&amount=70000',
    `paymentKey=${'x'.repeat(201)}&orderId=order-123&amount=70000`,
    'paymentKey=pay_123&orderId=short&amount=70000',
    'paymentKey=pay_123&orderId=order%2F123&amount=70000',
    'paymentKey=pay_123&orderId=order-123&amount=0',
    'paymentKey=pay_123&orderId=order-123&amount=-1',
    'paymentKey=pay_123&orderId=order-123&amount=1.5',
    'paymentKey=pay_123&orderId=order-123&amount=9007199254740992',
    'paymentKey=pay_123&orderId=order-123&amount=1e3',
    'paymentKey=pay_123&orderId=order-123&amount=01',
  ])('누락·중복·범위 밖 결제 매개변수를 거절한다: %s', (query) => {
    const params = new URLSearchParams(query)
    expect(parseTossSuccessParameters(params)).toBeNull()
    expect(hasTossSuccessParameters(params)).toBe(false)
  })
})
