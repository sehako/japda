# DB 조건부 재고 확보와 주문 상태 기반 점유 관리 설계

## 목적과 완료 조건

판매 일정별 재고 점유를 `sales.committed_quantity` 하나로 관리하고 주문 상태와 같은 PostgreSQL transaction에서 변경한다. 기존 `sale_inventory_counters`와 `inventory_reservations`는 사용하지 않는다.

- `sales.committed_quantity`는 `NOT NULL DEFAULT 0`이며 `0 <= committed_quantity <= quantity` 제약을 가진다.
- 주문 생성은 조건부 증가가 성공한 경우에만 `PENDING_PAYMENT` 주문을 저장한다.
- 승인 성공은 수량을 유지하고 주문을 `PAID`로 전환한다.
- 명시적 결제 실패는 주문을 `PAYMENT_FAILED`로 전환하고 수량을 한 번 반환한다.
- 결제 시도가 없는 만료 주문은 30초 scheduler가 `EXPIRED`로 전환하고 수량을 반환한다.
- `CONFIRMING`·`REVIEW_REQUIRED` 결제 주문은 원래 3분 `expires_at` 이후에도 `PENDING_PAYMENT`와 수량을 유지한다.
- 동시 주문에서도 `committed_quantity`가 `quantity`를 초과하지 않는다.

[ADR-036](../../../architecture/decisions/ADR-036-sales-committed-quantity-single-inventory-source.md)이 [ADR-028](../../../architecture/decisions/ADR-028-database-inventory-counter-and-order-reservation.md)의 카운터·예약 이중 모델을 대체한다.

## 데이터 모델

```text
sales
- quantity            INTEGER NOT NULL
- committed_quantity  INTEGER NOT NULL DEFAULT 0

orders.status
- PENDING_PAYMENT
- PAID
- EXPIRED
- PAYMENT_FAILED
```

가용 수량은 `sales.quantity - sales.committed_quantity`로 계산한다. 주문 상태가 `PENDING_PAYMENT` 또는 `PAID`인 주문의 수량은 점유에 포함하고, `EXPIRED` 또는 `PAYMENT_FAILED` 전환에 성공한 경우에만 반환한다.

## 주문 생성

주문 application은 선행 멱등성 조회 후 하나의 transaction에서 판매·상품을 검증하고 다음 조건부 UPDATE를 수행한다.

```sql
UPDATE sales
SET committed_quantity = committed_quantity + :quantity
WHERE id = :saleId
  AND :quantity > 0
  AND committed_quantity <= quantity - :quantity;
```

영향받은 행이 1건이면 수량을 확보하고 `PENDING_PAYMENT` 주문을 저장한다. 0건이면 판매 일정 누락 또는 잔여 수량 부족을 구분해 기존 주문 오류 계약으로 반환한다. 주문 저장 실패 시 수량 증가도 rollback된다. 판매 등록은 별도 카운터를 생성하지 않는다.

## 결제와 만료

결제 준비는 `PENDING_PAYMENT`이고 `expires_at` 이전인 주문만 허용한다. 결제 준비 중 생성된 `Payment`의 상태가 `CONFIRMING` 또는 `REVIEW_REQUIRED`이면 주문 만료 scheduler는 해당 주문을 대상으로 삼지 않는다.

승인은 결제 상태를 `APPROVED`로 바꾸고 주문을 조건부 `PAID`로 전환한다. 수량은 감소시키지 않는다. PG가 명시적으로 실패를 확정하면 결제를 `FAILED`로 바꾸고 주문을 조건부 `PAYMENT_FAILED`로 전환한 경우에만 `sales.committed_quantity`를 감소시킨다.

별도 scheduler는 30초 주기로 `PENDING_PAYMENT`, `expires_at <= now`, 결제 시도가 없는 주문을 조회한다. 주문 상태 조건부 UPDATE가 성공한 경우에만 수량을 반환한다. 경쟁 transaction이 먼저 처리하면 영향받은 행이 0건이므로 재반환하지 않는다.

모든 외부 PG 호출은 DB transaction 밖에서 수행한다. reconcile의 `CONFIRMING` 조회 범위·30초 주기·100건 제한·15분 `REVIEW_REQUIRED` 정책은 유지한다.

## migration과 개발 데이터

V20 후속 migration은 다음 순서로 적용한다.

1. `sales.committed_quantity`와 범위 제약을 추가한다.
2. `orders_status_valid`를 네 가지 상태 제약으로 교체한다.
3. `settlement_details`, `settlement_entries`를 삭제한다.
4. `payments`, `orders` 개발 데이터를 삭제한다.
5. `inventory_reservations`, `sale_inventory_counters` 테이블을 제거한다.

기존 주문·결제·재고 데이터를 백필하지 않는다. 이 migration은 개발 데이터베이스 전환을 전제로 하며 운영 데이터베이스에는 적용하지 않는다.

## 검증

PostgreSQL Testcontainers에서 범위 제약, 상태 허용값, 개발 데이터 정리와 제거된 테이블을 검증한다. 주문·결제·scheduler 통합 테스트에서 동시 주문 상한, 주문 저장 rollback, 승인 수량 유지, 실패·만료의 단일 반환, 결제 진행 중 만료 후 점유 유지와 종료 주문 재결제 거절을 검증한다.
