import { describe, expect, test } from 'vitest'

import { checkBuyerOrder, createBuyerOrderRequest } from '../../../../src/features/buyer-checkout/model/buyerOrder.ts'

const address = { shippingAddressId: 7, addressName: '집', recipientName: '홍길동', phoneNumber: '010-1234-5678', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: null }
const checkout = { saleId: 11, productName: '한정판 후디', representativeImagePath: '/products/21/main.webp', quantity: 3, unitPrice: 120000, totalPrice: 360000, shippingAddresses: [address] }
const order = { orderId: 9, paymentOrderId: '550e8400-e29b-41d4-a716-446655440000', status: 'PENDING_PAYMENT' as const, productName: '한정판 후디', quantity: 3, unitPrice: 120000, totalPrice: 360000, expiresAt: '2026-09-13T12:03:00Z' }

describe('구매자 주문 모델', () => {
  test('선택한 배송지의 주소 스냅샷만 주문 요청에 복사한다', () => {
    expect(createBuyerOrderRequest(checkout, address)).toEqual({ saleId: 11, quantity: 3, shippingAddress: { recipientName: '홍길동', phoneNumber: '010-1234-5678', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: null } })
  })

  test('미래 만료의 일치하는 주문을 결제 가능으로 판단한다', () => {
    expect(checkBuyerOrder(order, checkout, Date.parse('2026-09-13T12:00:00Z'))).toBe('valid')
  })

  test.each([
    { ...order, quantity: 2 },
    { ...order, productName: '다른 상품' },
    { ...order, totalPrice: 350000 },
  ])('조회 당시의 정보와 확정 주문이 다르면 갱신이 필요하다', (response) => {
    expect(checkBuyerOrder(response, checkout, Date.parse('2026-09-13T12:00:00Z'))).toBe('changed')
  })

  test('만료 시각과 같거나 지난 주문은 결제할 수 없다', () => {
    expect(checkBuyerOrder(order, checkout, Date.parse(order.expiresAt))).toBe('expired')
  })

  test('토스 상품명 제한을 넘으면 결제할 수 없다', () => {
    expect(checkBuyerOrder({ ...order, productName: '가'.repeat(101) }, { ...checkout, productName: '가'.repeat(101) }, Date.parse('2026-09-13T12:00:00Z'))).toBe('invalid')
  })
})
