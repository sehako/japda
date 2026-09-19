# ADR-029: Redis 재고 선점을 짧은 수명의 품절 마커로 대체

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-19

## 결정

Redis의 역할을 가용 재고 snapshot과 원자적 선점에서 `saleId` 단위의 품절 마커로 축소한다. 주문 application은 선행 멱등성 조회에서 기존 주문을 반환하지 않은 경우에만 Redis 마커를 확인한다. 마커가 있으면 DB transaction을 시작하지 않고 기존 품절 오류를 반환하며, 마커가 없거나 Redis 조회가 실패하면 PostgreSQL 주문 transaction을 실행한다.

PostgreSQL 조건부 UPDATE가 실패하면 기존 실패 후 조회에서 실제 잔여 재고를 함께 판정한다. 실제 잔여 재고가 `0`인 경우에만 transaction rollback 이후 Redis 마커를 best-effort로 기록한다. 잔여 재고가 양수인 수량 부족에는 마커를 기록하지 않는다. 마커는 `SET NX`로 생성해 이미 존재하는 마커의 TTL을 연장하지 않고, 생성 시점부터 기본 30초 동안 유지한다. 재고가 반환되어도 마커를 즉시 삭제하지 않는다.

Redis 조회와 기록은 DB transaction 밖에서 수행하며 Redis 장애는 PostgreSQL 경로로 우회한다. `order.inventory.redis.enabled`가 `false`이면 Redis를 사용하지 않고 기존 PostgreSQL 주문 경로만 실행한다. Redis 마커는 성능 최적화 계층이며 주문 가능 여부의 최종 원천으로 사용하지 않는다.

기존 Redis 재고 snapshot, 초기화 분산 lock과 polling, Lua 재고 선점·복원·세대 폐기, generation, 주문별 Redis 예약 key와 보상 조율은 제거한다. 이 결정은 [ADR-026](ADR-026-dynamic-redis-inventory-reservation.md)과 [ADR-027](ADR-027-redis-only-inventory-initialization-single-flight.md)을 대체한다. [ADR-028](ADR-028-database-inventory-counter-and-order-reservation.md)의 PostgreSQL 조건부 재고 카운터와 주문별 예약 정합성 모델은 변경하지 않는다.

## 이유

PostgreSQL은 이미 조건부 UPDATE로 초과 판매를 차단하고 주문별 예약으로 재고 점유와 반환을 관리한다. Redis에 가용 수량을 복제해 모든 성공 주문에서 선점과 보상 경로를 수행하면 최종 DB 검증과 중복되는 처리 비용과 장애 지점이 생긴다.

Redis에 DB가 확인한 완전 품절이라는 부정 정보만 짧게 저장하면 품절 안정 구간의 불필요한 DB 접근을 줄이면서 성공 주문의 Redis 쓰기, 분산 초기화와 보상 경로를 제거할 수 있다. Redis 장애와 비활성화 시에도 PostgreSQL 정합성 경로가 그대로 동작한다.

## 트레이드오프

성공 주문도 Redis가 활성화된 경우 마커 조회 한 번을 수행한다. 성공 주문 지연과 품절 요청 처리 성능은 별도의 수동 성능 측정에서 검증한다.

마지막 재고를 확보한 성공 주문은 마커를 기록하지 않으므로 이후 첫 품절 요청은 DB transaction을 시도한다. 마커 만료 직후 동시에 도착한 요청도 새 마커가 기록되기 전까지 DB에 도달할 수 있다.

예약 만료나 결제 실패로 PostgreSQL 재고가 반환되어도 마커를 즉시 삭제하지 않으므로 판매 가능한 재고가 최대 마커 TTL 동안 가려질 수 있다. 이는 판매 기회를 줄일 수 있지만 PostgreSQL 재고를 늘리거나 초과 판매를 허용하지는 않는다.
