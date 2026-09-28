# 구매자 주문 생성 API 설계

## 목적과 완료 조건

`POST /api/orders`는 단일 판매 일정의 상품·수량·배송 스냅샷을 저장하고 `sales.committed_quantity`를 조건부로 증가시킨다. 주문 생성과 수량 증가는 같은 PostgreSQL transaction에서 처리한다.

- 성공 주문은 `PENDING_PAYMENT`, 원래 만료 시간은 생성 시각 + 3분이다.
- `sales.committed_quantity` 조건부 UPDATE가 성공한 경우에만 주문을 저장한다.
- 동일 구매자·멱등성 키의 동일 요청은 기존 주문을 반환하고, 다른 요청은 충돌로 거절한다.
- `sales.quantity - sales.committed_quantity`가 가용 수량이며 동시 주문은 판매 수량을 초과할 수 없다.
- 주문 응답은 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED` 상태를 직렬화한다.

[백엔드 아키텍처](../../../architecture/backend.md), [상품 원본과 판매 일정의 도메인 경계 결정](../../../architecture/decisions/ADR-009-product-and-sale-domain-boundaries.md), [판매 일정 committed quantity 단일 재고 원천 결정](../../../architecture/decisions/ADR-036-sales-committed-quantity-single-inventory-source.md)을 따른다.

## HTTP 계약

```http
POST /api/orders
Content-Type: application/json
X-Buyer-Id: 123
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "saleId": 100,
  "quantity": 2,
  "shippingAddress": {
    "recipientName": "홍길동",
    "phoneNumber": "010-1234-5678",
    "postalCode": "06236",
    "address": "서울특별시 강남구 테헤란로 123",
    "detailAddress": "101동 1001호",
    "deliveryMessage": "문 앞에 놓아주세요"
  }
}
```

서버는 상품명·단가·총액·`paymentOrderId`·상태·`expiresAt`을 생성한다. 요청 수량은 양의 `Int`이고 배송 스냅샷은 주문에 저장한다. 응답의 `paymentOrderId`는 결제 PG의 `orderId`로 사용한다.

## 재고와 동시성

주문 transaction은 멱등성 조회, 판매·상품 검증, 다음 조건부 UPDATE, 주문 저장 순서로 수행한다.

```sql
UPDATE sales
SET committed_quantity = committed_quantity + :quantity
WHERE id = :saleId
  AND :quantity > 0
  AND committed_quantity <= quantity - :quantity;
```

영향받은 행이 없으면 판매 일정 누락 또는 수량 부족을 기존 오류 계약으로 구분한다. 주문 저장이 실패하면 수량 증가도 rollback된다. `sale_inventory_counters`, `inventory_reservations`, Redis 선점과 판매 일정 비관적 잠금은 사용하지 않는다.

결제 승인 시 수량은 유지된다. 명시적 결제 실패 또는 결제 시도 없는 만료 주문은 각각 `PAYMENT_FAILED` 또는 `EXPIRED`로 조건부 전환한 경우에만 수량을 반환한다. `CONFIRMING`·`REVIEW_REQUIRED` 결제 주문은 3분 이후에도 수량을 유지한다.

## 만료 scheduler

별도 scheduler가 30초마다 `PENDING_PAYMENT`, `expires_at <= now`, 연결된 결제 시도가 없는 주문을 조회한다. 주문을 조건부 `EXPIRED`로 전환한 transaction만 `sales.committed_quantity`를 감소시킨다. 결제 시도가 있는 주문은 Payment 상태가 `CONFIRMING`, `REVIEW_REQUIRED`, `FAILED`, `APPROVED` 중 무엇인지 기존 결제 흐름에서 처리한다.

## 상태·오류·검증

주문 상태는 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`만 허용한다. `PAYMENT_PROCESSING`은 사용하지 않는다. 종료 주문의 재결제는 허용하지 않으며 새 주문·새 멱등성 키가 필요하다.

MockMvc REST Docs와 PostgreSQL Testcontainers에서 성공·멱등성·수량 부족·동시 주문·상태 응답·만료 scheduler·종료 주문 재결제를 검증한다.
