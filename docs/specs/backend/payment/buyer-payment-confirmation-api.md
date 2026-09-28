# 구매자 결제 승인 API 설계

## 목적과 완료 조건

`POST /api/payments/confirm`은 주문 소유자·금액·결제 시도를 검증하고 PG 승인 결과를 주문 상태와 함께 반영한다. 재고 점유 원천은 `sales.committed_quantity`이며 결제·주문 상태 변경과 수량 반환은 같은 PostgreSQL transaction에서 처리한다.

- 승인 성공: `Payment.APPROVED`, `Order.PAID`, 수량 유지
- 명시적 승인 실패: `Payment.FAILED`, `Order.PAYMENT_FAILED`, 수량 한 번 반환
- 결과 불명확: `Payment.REVIEW_REQUIRED` 또는 `CONFIRMING`, 주문은 `PENDING_PAYMENT`, 수량 유지
- 기존 reconcile의 `CONFIRMING` 조회 범위·30초 주기·100건 제한·15분 `REVIEW_REQUIRED` 정책 유지
- `EXPIRED` 또는 `PAYMENT_FAILED` 주문은 같은 주문·멱등성 키로 재결제하지 못한다.

[백엔드 아키텍처](../../../architecture/backend.md), [구매자 주문 생성 API](../order/buyer-order-creation-api.md), [판매 일정 committed quantity 단일 재고 원천 결정](../../../architecture/decisions/ADR-036-sales-committed-quantity-single-inventory-source.md)을 따른다.

## HTTP 계약

```http
POST /api/payments/confirm
Content-Type: application/json
X-Buyer-Id: 123
```

```json
{
  "paymentKey": "토스가_발급한_결제_키",
  "orderId": "주문 생성 응답의 paymentOrderId",
  "amount": 70000
}
```

서버는 저장된 주문 금액과 요청 금액을 비교하고, 저장된 결제 시도의 PG 멱등성 키와 금액을 사용한다. 이미 결제 시도가 있으면 같은 결제 키만 허용한다. 완료된 승인의 재호출은 `PAID` 응답을 반환하고, 종료 주문의 새 결제 시도는 거절한다.

## transaction 흐름

PG 호출은 DB transaction 밖에서 수행한다. `prepare()`는 만료 전 `PENDING_PAYMENT` 주문에만 `CONFIRMING` 결제 시도를 생성한다. `approve()`는 결제 승인과 주문 `PENDING_PAYMENT → PAID` 전이를 같은 transaction에서 수행하며 `sales.committed_quantity`를 감소시키지 않는다.

PG가 `ABORTED`, `EXPIRED` 등 명시적 실패를 확정하면 `fail()`은 결제 `CONFIRMING → FAILED`와 주문 `PENDING_PAYMENT → PAYMENT_FAILED`를 조건부 전환한다. 주문 전환에 성공한 경우에만 판매 일정의 점유 수량을 주문 수량만큼 감소시킨다. 이미 다른 흐름에서 처리한 결제는 수량을 다시 반환하지 않는다.

`CONFIRMING` 또는 `REVIEW_REQUIRED` 결과는 주문을 만료시키거나 수량을 반환하지 않는다. 결제 reconcile은 외부 PG 결과를 확인한 뒤 기존 정책에 따라 승인·실패·수동 확인 상태를 갱신한다.

## 상태와 오류

주문 상태는 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`만 허용한다. 결제 상태는 `CONFIRMING`, `APPROVED`, `FAILED`, `REVIEW_REQUIRED`로 제한한다. `PAYMENT_PROCESSING`은 주문 상태로 사용하지 않는다.

금액 불일치·주문 만료·결제 키 충돌·이미 처리된 종료 주문은 기존 `ProblemDetail` 오류 계약으로 반환하고 PG를 호출하지 않는다. PG 연결 장애나 결과 불명확은 실패로 단정하지 않고 결제와 수량 점유를 유지한다.

## 검증

Application·domain test와 PostgreSQL Testcontainers에서 승인 수량 유지, 명시적 실패의 `PAYMENT_FAILED`·단일 반환, `CONFIRMING`·`REVIEW_REQUIRED` 만료 후 점유 유지, reconcile 정책, 종료 주문 재결제 거절을 검증한다. MockMvc REST Docs로 성공·대표 오류 계약을 검증한다.
