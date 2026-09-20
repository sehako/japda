# Redis 품절 마커 제거 설계

## 목적과 완료 조건

주문 생성 성공 경로와 품절 경로에 공통으로 부과되는 Redis 품절 마커 왕복을 제거하고, PostgreSQL 재고 카운터와 주문별 예약만으로 주문 가능 여부를 판정한다. PostgreSQL은 이미 조건부 UPDATE로 초과 판매를 막고 `inventory_reservations`로 점유·반환을 관리하므로, Redis를 제거해도 재고 정합성의 최종 원천은 변하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 신규 주문은 선행 멱등성 조회 뒤 Redis 호출 없이 PostgreSQL 주문 transaction을 실행한다.
- 같은 멱등성 키의 기존 주문은 기존 응답을 반환하며 재고 카운터를 변경하지 않는다.
- 재고 카운터 조건부 UPDATE는 동시 요청에서도 `Sale.quantity`를 넘는 점유를 허용하지 않는다.
- 완전 품절과 요청 수량만 부족한 경우 모두 기존 `ORDER_QUANTITY_UNAVAILABLE` 오류 계약을 유지한다.
- 예약 만료나 결제 확정 실패로 반환된 재고는 기존 PostgreSQL 경로에서 다시 주문할 수 있다.
- 주문 application, 설정, 의존성, 관측과 테스트에 Redis 품절 마커 흔적이 남지 않는다.
- Redis가 없거나 연결할 수 없는 환경에서도 주문 애플리케이션이 기동하고 주문 생성이 동작한다.

## 배경과 변경 이유

[Redis 품절 마커 결정](../../../architecture/decisions/ADR-029-redis-sold-out-marker.md)은 Redis에 완전 품절이라는 짧은 수명의 부정 정보를 저장해 후속 요청의 DB 접근을 줄였다. 그러나 마커가 없는 모든 성공 주문은 Redis 조회 뒤 PostgreSQL transaction을 실행하고, 완전 품절 요청도 조회와 기록을 수행한다. 따라서 성공 경로와 품절 경로 모두 Redis 네트워크 왕복 및 장애 우회 비용을 가진다.

[DB 재고 카운터와 주문별 예약 결정](../../../architecture/decisions/ADR-028-database-inventory-counter-and-order-reservation.md)의 `sale_inventory_counters` 조건부 UPDATE와 `inventory_reservations`는 Redis와 무관하게 최종 정합성을 보장한다. Redis 마커는 성능 최적화 계층일 뿐이므로, 이를 제거하면 품절 요청의 DB 접근 감소는 포기하지만 성공 경로의 추가 의존성과 과소 재고 가능성을 함께 없앨 수 있다.

이 설계는 ADR-029를 대체하는 아키텍처 변경이다. 구현 전에 새 ADR을 추가하고 현재 [백엔드 아키텍처](../../../architecture/backend.md)의 재고 규칙을 PostgreSQL 단일 경로로 갱신한다.

## 범위

포함 범위는 다음과 같다.

- `OrderService`의 품절 마커 확인·기록 조율 제거
- `SoldOutInventoryMarker`와 Redis 구현, 설정, key 생성, 관련 지표·로그 제거
- 주문 재고용 Redis 의존성 및 Redis 자동 구성 제외 설정 제거
- Redis 품절 마커 단위·통합 테스트 제거 및 PostgreSQL 재고 정합성 테스트 보강
- Redis 환경 변수와 인프라를 단계적으로 제거하는 배포 절차 정의
- 성공 주문 및 품절 주문을 분리한 성능·운영 검증

다음은 제외한다.

- `sale_inventory_counters`, `inventory_reservations` 또는 주문 상태 데이터 모델 변경
- 주문·결제·재고 예약 상태 전이 변경
- 주문 HTTP 요청·응답 및 오류 코드 변경
- 품절 요청을 위한 다른 cache, local cache, 메시지 큐 또는 분산 lock 도입
- PostgreSQL 재고 카운터를 대체하는 별도 재고 저장소 도입
- 기존 Redis key의 강제 삭제 또는 Redis 데이터베이스 초기화

## 주문 처리 흐름

`OrderService`는 다음 순서로 주문 생성을 조율한다.

1. `(buyerId, idempotencyKey)`로 기존 주문을 조회한다.
2. 동일한 요청의 기존 주문이면 기존 응답을 즉시 반환하고, 다른 요청이면 기존 멱등성 충돌을 반환한다.
3. 기존 주문이 없으면 `OrderCreationTransactionService`의 PostgreSQL transaction을 실행한다.
4. transaction 내부에서 판매 일정과 상품을 확인하고, 동일 멱등성 요청을 다시 확인하며, 만료된 `RESERVED` 예약을 반환한다.
5. 주문을 저장한 뒤 `sale_inventory_counters`에 수량 조건을 포함한 UPDATE를 수행한다.
6. UPDATE가 성공하면 같은 transaction에서 `RESERVED` 예약을 저장하고 commit한다.
7. UPDATE가 실패하면 주문 저장과 예약 반환을 rollback하고, 실제 잔여 수량을 담은 내부 재고 부족 예외를 기존 `ORDER_QUANTITY_UNAVAILABLE` 오류로 변환한다.
8. 멱등성 unique 충돌이면 별도 transaction에서 기존 주문을 복구한다.

Redis 조회, Redis 기록, Redis 장애 우회와 Redis 결과에 따른 분기는 없다. 마지막 재고를 확보한 성공 주문 뒤의 품절 요청도 PostgreSQL transaction을 시도하며, 이는 Redis 의존성을 제거하기 위해 수용하는 비용이다.

## 정합성·오류 처리

재고 확보 결과는 기존 `SaleInventoryReserveResult`와 `OrderInventoryInsufficientException.remainingQuantity` 의미를 유지한다.

- `Acquired`: 카운터 점유와 예약 저장을 같은 transaction에서 확정한다.
- `Insufficient(0)`: 완전 품절이다. rollback 후 `ORDER_QUANTITY_UNAVAILABLE`을 반환한다.
- `Insufficient(positive)`: 요청 수량만 부족하다. rollback 후 같은 오류를 반환한다.
- `MissingCounter`: 판매 일정과 재고 카운터 사이의 데이터 불변식 오류로 기록하고 실패한다.

재고 부족은 정상 응답으로 commit하지 않는다. 따라서 조건부 UPDATE 전에 저장된 주문, 만료 예약 반환, 카운터 점유와 예약 저장은 모두 transaction의 원자성을 따른다. Redis key가 남아 있거나 Redis가 중단돼도 신규 애플리케이션은 이를 읽거나 쓰지 않으므로 주문 결과에 영향을 주지 않는다.

## 변경 경계

- `presentation`: 기존 HTTP 계약과 오류 변환을 유지하며 변경하지 않는다.
- `application`: `OrderService`에서 `SoldOutInventoryMarker` 의존성과 관련 예외 처리·로그를 제거하고, 멱등성 조회와 DB transaction 조율만 남긴다.
- `domain`: 주문, 예약, 카운터 repository 계약과 재고 부족 결과 의미를 유지한다.
- `infrastructure/persistence`: 기존 조건부 UPDATE, 실패 후 잔여 수량 조회, 예약 영속화를 유지한다.
- `infrastructure/inventory`: Redis 품절 마커 전용 구성 전체를 제거한다.
- build·설정: `spring-boot-starter-data-redis`, `order.inventory.redis.*`, Redis 자동 구성 제외를 제거한다. 저장소 전체 검색 결과 Redis는 이 기능에서만 사용된다.

## 배포와 운영 제거 절차

1. Redis 없이 동작하는 애플리케이션 변경을 롤링 배포한다. 신버전은 Redis key를 읽거나 쓰지 않아 구버전과 공존해도 PostgreSQL 정합성에 영향을 주지 않는다.
2. 배포 중에는 구버전이 Redis 마커를 계속 사용할 수 있으므로 Redis 인프라와 `ORDER_INVENTORY_REDIS_*` 환경 변수는 유지한다.
3. 모든 구버전 인스턴스가 종료되고 요청 처리에서 제외됐음을 확인한다.
4. 마지막 구버전이 기록한 마커의 최대 TTL인 30초이 지난 뒤 Redis 연결 지표와 로그가 더 이상 발생하지 않는지 확인한다. 남은 key는 TTL로 만료되므로 key 삭제나 `FLUSHDB`는 수행하지 않는다.
5. Redis 환경 변수와 이 기능 전용 Redis 인프라를 제거한다. 다른 시스템의 Redis 사용 여부는 인프라 변경 직전에 별도로 확인한다.

신버전은 Redis 미가용 상태에서도 기동해야 하므로, Redis 제거는 주문 기능의 선행 조건이 아니다. 배포 후 이상이 발생하면 이전 애플리케이션 버전으로 되돌릴 수 있지만, 새 주문의 최종 정합성은 두 버전 모두 PostgreSQL이 보장한다.

## 검증 전략

- 단위 테스트: 기존 멱등 요청이 transaction을 호출하지 않는지, 신규 주문이 PostgreSQL transaction을 호출하는지, 재고 부족이 기존 오류로 변환되는지 확인한다.
- 통합 테스트: Redis 컨테이너와 설정 없이 주문 애플리케이션을 기동하고, 성공 주문·완전 품절·부분 재고 부족·예약 반환 후 재주문을 검증한다.
- 동시성 테스트: 같은 `saleId`에 대한 병렬 주문의 성공 수량 합계와 `committed_quantity`가 `Sale.quantity`를 넘지 않는지 검증한다.
- 회귀 테스트: 멱등성 unique 충돌 복구, 판매 기간 검증, 결제 및 예약 전이 관련 기존 테스트를 실행한다.
- 성능 측정: 고재고 성공 전용과 완전 품절 전용 시나리오를 분리해 p50·p95·p99, 처리량, PostgreSQL connection pool 사용량, 카운터 UPDATE 대기와 오류율을 기록한다. Redis 마커 활성 버전의 과거 수치는 참고값으로만 사용하며, 동일 실행 환경과 데이터 분포에서 반복 측정한다.

## 트레이드오프

완전 품절 상태에서 모든 요청이 PostgreSQL로 도달하므로 Redis 마커 hit에 의한 빠른 거절과 관련 지표는 사라진다. 반면 모든 성공 주문의 Redis 조회와 품절 요청의 Redis 기록이 제거되고, Redis timeout·연결·설정·배포 순서에 따른 운영 복잡성과 마커 TTL 동안 판매 가능한 재고를 가리는 과소 재고도 없어진다.

품절 트래픽이 PostgreSQL 병목을 실제로 만들면 그 문제는 재고 정합성 모델을 바꾸지 않는 범위에서 측정 후 별도 설계한다. 이 변경에 대체 캐시나 사전 품절 판정을 포함하지 않는다.
