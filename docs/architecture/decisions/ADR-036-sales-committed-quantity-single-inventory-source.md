# ADR-036: 판매 일정 committed quantity 단일 재고 원천

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-28

## 결정

판매 일정별 재고 점유 수량을 `sales.committed_quantity` 하나로 관리하고, `inventory_reservations`와 `sale_inventory_counters`를 제거한다. `sales.committed_quantity`는 `0 <= committed_quantity <= quantity` 제약을 가지며, 주문 생성은 조건부 증가가 성공한 경우에만 `PENDING_PAYMENT` 주문을 저장한다.

주문 상태는 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`로 제한한다. 결제 진행 상태는 `PaymentStatus`가 관리하며, `CONFIRMING` 또는 `REVIEW_REQUIRED` 결제에 연결된 주문은 주문의 3분 만료 시각이 지나도 재고를 유지한다. 결제 시도가 없는 만료 주문은 30초 주기의 scheduler가 `EXPIRED`로 전환하면서 수량을 반환하고, 명시적 결제 실패는 `PAYMENT_FAILED` 전환과 함께 수량을 반환한다. 승인 완료는 수량을 유지한 채 `PAID`로 전환한다.

주문 상태 전환과 `sales.committed_quantity` 변경은 조건부 UPDATE와 하나의 PostgreSQL transaction으로 처리한다. 개발 데이터베이스 전환이므로 기존 `orders`, `payments`, 정산 자식 데이터와 재고 데이터를 migration에서 폐기하고, `sales` 행은 `committed_quantity` 기본값 0으로 시작한다.

이 결정은 [ADR-028](ADR-028-database-inventory-counter-and-order-reservation.md)의 카운터·예약 이중 모델을 대체한다.

## 이유

카운터와 주문별 예약을 함께 유지하는 대신 판매 일정의 점유 수량과 주문 상태를 단일 모델로 만들면 재고 점유·반환의 원천과 불변식을 명확히 할 수 있다. `sales` 행의 범위 제약과 조건부 UPDATE는 동시 주문의 초과 판매를 차단하고, 주문 상태의 조건부 전이는 만료·실패 반환의 중복을 방지한다.

결제 진행 또는 수동 확인 상태에서 재고를 유지하면 PG 결과가 늦게 확정될 때 주문 상태와 재고 점유가 분리되는 것을 막을 수 있다. 기존 reconcile의 조회 범위·주기·15분 `REVIEW_REQUIRED` 정책과 PG 취소·환불 범위는 변경하지 않는다.

## 트레이드오프

주문 상태만으로 재고 반환 여부를 판단하므로 결제 시도 상태와 주문 상태의 경쟁 조건을 조건부 전이와 transaction 순서로 엄격히 관리해야 한다. 결제 진행 중인 주문은 3분 이후에도 재고를 점유할 수 있어 재고 반환이 지연될 수 있다.

개발 데이터 폐기는 migration 적용 전 데이터 복구가 필요하며 운영 데이터베이스에 동일 migration을 적용할 수 없다. 기존 예약 상세를 제거하므로 주문별 예약 상태를 직접 조회하던 기능은 새 주문 상태와 결제 상태 조합으로 대체해야 한다.
