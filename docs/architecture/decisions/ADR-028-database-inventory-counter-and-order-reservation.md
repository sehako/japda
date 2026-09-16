# ADR-028: DB 재고 카운터와 주문별 예약으로 재고 관리

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-16

## 결정

PostgreSQL을 최종 재고 원천으로 유지하면서 판매 일정별 `sale_inventory_counters`와 주문별 `inventory_reservations`를 함께 사용한다. 최초 판매 수량은 `sales.quantity`에만 저장하고, 현재 점유 수량은 카운터의 `committed_quantity`로 관리한다. 주문 생성은 판매 일정 행을 잠그거나 주문·결제를 집계하지 않고, 카운터에 대한 단일 조건부 UPDATE로 재고를 확보한 뒤 같은 transaction에서 주문과 `RESERVED` 예약을 생성한다.

예약은 `RESERVED`, `PAYMENT_PENDING`, `CONFIRMED`, `RELEASED` 상태를 가진다. 결제 시작 시 유효한 `RESERVED`를 `PAYMENT_PENDING`으로 전이하고, 승인 성공 시 `CONFIRMED`로 확정한다. 결제 확정 실패와 결제 시작 전 만료는 기대 상태를 조건으로 `RELEASED`로 전이한 경우에만 카운터를 감소시킨다. 승인 중이거나 수동 확인이 필요한 예약은 주문 만료 시각 이후에도 `PAYMENT_PENDING`으로 재고를 유지한다.

주문 요청과 Redis 재고 snapshot 생성 시 해당 판매 일정의 만료된 `RESERVED` 예약을 지연 반환한다. Redis는 품절 요청을 DB 접근 전에 줄이는 보조 선점 계층으로 유지하며, 장애 시 DB 주문 경로로 우회한다. Redis snapshot은 만료 예약을 반환한 뒤 `sales.quantity - sale_inventory_counters.committed_quantity`로 생성하고, 과대 재고는 PostgreSQL 조건부 UPDATE가 최종 차단한다.

카운터 증가·감소, 예약 생성·상태 전이와 관련 주문·결제 변경은 하나의 PostgreSQL transaction에서 처리한다. 예약과 결제 상태 변경은 현재 상태를 조건으로 하는 연산의 영향받은 행 수로 경쟁 결과를 판정한다. `InventoryReservation`은 주문 및 판매 Entity와 JPA 연관관계를 맺지 않고 `orderId`와 `saleId`로 참조한다.

이 결정은 [ADR-015](ADR-015-order-row-reservation-with-sale-lock.md)의 주문 행 집계와 판매 일정 잠금 기반 재고 관리, [ADR-019](ADR-019-payment-attempt-and-reservation-consistency.md)의 판매 일정 잠금 기반 결제 직렬화를 대체한다.

## 이유

판매 일정 행을 잠근 상태에서 유효한 주문과 결제를 매번 집계하면 인기 판매 일정의 주문 생성과 결제 처리가 긴 임계 구역에 직렬화되고, 주문량이 증가할수록 집계 비용도 커진다. 예약 행만 추가한 채 합계 조회 후 INSERT를 수행하면 동시 transaction이 같은 합계를 읽어 초과 판매할 수 있고, 카운터만 두면 어떤 주문이 재고를 점유하는지와 반환 중복 여부를 판단할 수 없다.

카운터의 조건부 UPDATE는 짧은 단일 DB 연산으로 재고 확보 경쟁을 해결한다. 주문별 예약과 기대 상태 기반 전이는 결제 진행 중 재고 유지, 만료와 실패 반환, 중복 반환 방지를 명시적으로 표현한다. 두 데이터를 같은 transaction에서 변경하면 집계와 상세 상태의 일치를 유지하면서 판매 일정 잠금과 반복 집계를 제거할 수 있다.

## 트레이드오프

카운터와 예약은 같은 재고 점유 사실을 집계와 상세 형태로 중복 저장하므로 모든 생성, 전이와 반환 경로가 두 값을 같은 transaction에서 일관되게 변경해야 한다. 별도 정합성 복구 작업은 두지 않으며 불변식 위반 시 자동 보정하지 않고 transaction을 실패시켜 관찰 가능하게 만든다.

같은 판매 일정의 카운터 행은 조건부 UPDATE 동안 PostgreSQL 내부에서 직렬화된다. 판매 일정 잠금을 보유한 채 주문·결제를 조회하고 집계하지 않으므로 임계 구역은 짧아지지만, 하나의 판매 일정에 대한 쓰기 경합 자체는 남는다.

별도 예약 만료 scheduler와 결제 실패 시 Redis 재고 즉시 복원을 두지 않는다. 주문 요청이 없으면 만료 예약 정리가 지연될 수 있고, 품절 Redis cache가 유지되는 동안 반환된 DB 재고가 최대 inventory TTL만큼 늦게 노출될 수 있다. 이 과소 재고는 판매 기회를 줄일 수 있지만, 과대 재고와 초과 판매는 DB 조건부 UPDATE가 차단한다.
