# Redis 품절 마커 설계

## 목적과 완료 조건

PostgreSQL 조건부 재고 갱신을 최종 정합성 기준으로 유지하면서 Redis의 역할을 재고 선점에서 짧은 수명의 품절 마커로 축소한다. 재고가 있는 주문은 Redis 조회 한 번 뒤 바로 DB transaction을 실행하고, DB가 실제 잔여 재고 `0`을 확인한 뒤에는 후속 품절 요청을 Redis에서 빠르게 거절한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 선행 멱등성 조회에서 기존 주문을 반환하면 Redis와 재고 카운터를 호출하지 않는다.
- Redis 품절 마커가 있으면 DB transaction을 시작하지 않고 `ORDER_QUANTITY_UNAVAILABLE`을 반환한다.
- Redis 마커가 없거나 Redis 조회가 실패하면 기존 PostgreSQL 주문 생성 경로를 실행한다.
- DB 조건부 UPDATE가 재고 부족으로 실패한 경우 기존 실패 후 조회를 이용해 실제 잔여 재고를 확인한다.
- 실제 잔여 재고가 `0`일 때만 Redis 품절 마커를 기록한다.
- 요청 수량보다 재고가 적더라도 실제 잔여 재고가 양수이면 품절 마커를 기록하지 않는다.
- Redis 장애와 마커 기록 실패가 DB 주문 결과를 변경하지 않는다.
- 품절 마커는 생성 시점부터 고정 30초 동안 유지하고 요청마다 TTL을 연장하지 않는다.
- 기존 Redis 재고 snapshot, 초기화 lock과 polling, Lua 재고 선점, generation, 주문별 Redis 예약 및 보상 경로를 제거한다.
- 기존 주문 HTTP 요청·응답과 오류 계약을 변경하지 않는다.
- 같은 조건의 성공 주문 부하 테스트에서 p95가 Redis 비활성화 기준 대비 20% 이내의 차이를 보인다.

## 배경과 변경 이유

현재 재고 정합성은 [DB 조건부 재고 확보와 주문별 예약 관리 설계](database-inventory-reservation.md)에 따라 PostgreSQL의 `sale_inventory_counters` 조건부 UPDATE와 `inventory_reservations`로 보장한다. Redis는 그 앞에서 가용 재고 snapshot을 초기화하고 Lua script로 수량을 선점하지만, Redis 결과와 관계없이 성공 주문은 다시 DB 조건부 UPDATE를 통과한다.

500 RPS 품절 시나리오의 현재 단일 측정에서는 성공 주문 1,000건의 p95가 Redis 비활성화 시 약 `395.4ms`, Redis 선점 활성화 시 약 `762.7ms`로 약 1.93배 차이를 보였다. 반면 품절 주문 9,001건의 p95는 약 `3.85ms`에서 `2.30ms`로 개선됐다. 이 결과는 Redis 선점이 품절 요청을 줄이는 효과는 있지만 성공 주문에도 선점, 예약 key 기록과 보상 준비 비용을 부과한다는 문제를 보여준다.

측정은 품절 시나리오 초반의 성공 주문을 표본으로 사용한 단일 결과이므로 원인을 확정하는 근거로 사용하지 않는다. 변경 후에는 성공 전용 고재고 시나리오와 품절 시나리오를 분리해 반복 측정한다.

품절 요청을 줄이기 위해 Redis에 실제 가용 수량을 복제할 필요는 없다. PostgreSQL이 이미 조건부 UPDATE로 초과 판매를 차단하므로 Redis에는 DB가 완전 품절을 확인했다는 짧은 수명의 부정 정보만 저장한다. 이 방식은 품절 이후의 DB 접근 감소를 유지하면서 성공 주문의 Redis 쓰기와 분산 보상 경로를 제거한다.

이 설계는 [동적 Redis 재고 선점 결정](../../../architecture/decisions/ADR-026-dynamic-redis-inventory-reservation.md)과 [Redis 분산 lock 기반 초기화 결정](../../../architecture/decisions/ADR-027-redis-only-inventory-initialization-single-flight.md)을 대체할 아키텍처 변경이다. [DB 재고 카운터와 주문별 예약 결정](../../../architecture/decisions/ADR-028-database-inventory-counter-and-order-reservation.md)의 PostgreSQL 정합성 모델은 유지하고 Redis 보조 계층만 변경한다. 구현 전에 새 ADR을 기록하고 [백엔드 아키텍처](../../../architecture/backend.md)의 현재 Redis 재고 규칙을 갱신한다.

## 범위

포함 범위는 다음과 같다.

- `saleId` 단위 Redis 품절 마커 조회와 기록
- Redis 장애 시 DB fail-open
- 조건부 UPDATE 실패 후 실제 잔여 재고 반환
- 실제 잔여 재고 `0`인 경우에만 마커를 기록하는 application 조율
- 기존 Redis 재고 선점, 초기화와 보상 구성 제거
- 품절 마커 동작, DB 정합성과 성능 검증
- 관련 설정, 로그와 Micrometer 지표 정비

다음은 제외한다.

- PostgreSQL 재고 카운터와 주문별 예약 데이터 모델 변경
- 주문, 결제 및 재고 예약 상태 전이 변경
- 주문 취소 API와 별도 예약 만료 scheduler 또는 batch
- 재고 반환 시 Redis 마커의 즉시 삭제
- Redis와 DB 사이의 분산 transaction, Outbox 또는 reconciliation
- JVM local cache나 애플리케이션 인스턴스별 품절 상태
- Redis를 최종 재고 원천으로 사용하는 구조
- 기존 주문 HTTP 계약 변경

## 주문 처리 흐름

`OrderService`는 선행 멱등성 조회, Redis 품절 확인, DB 주문 transaction과 품절 마커 기록을 다음 순서로 조율한다.

1. `(buyerId, idempotencyKey)`에 해당하는 기존 주문을 조회한다.
2. 기존 주문이 동일 요청이면 즉시 반환하고 Redis를 호출하지 않는다.
3. Redis에서 해당 `saleId`의 품절 마커 존재 여부를 한 번 확인한다.
4. 마커가 있으면 DB transaction을 시작하지 않고 `ORDER_QUANTITY_UNAVAILABLE`을 반환한다.
5. 마커가 없거나 Redis 조회가 실패하면 DB 주문 transaction을 실행한다.
6. DB transaction은 판매 일정과 상품 검증, transaction 내부 멱등성 재확인과 만료 예약 지연 반환을 기존과 같이 수행한다.
7. 주문을 저장한 뒤 재고 카운터 조건부 UPDATE를 수행한다.
8. 조건부 UPDATE가 성공하면 주문별 `RESERVED` 예약을 저장하고 transaction을 commit한다.
9. 조건부 UPDATE가 실패하면 기존 실패 후 조회로 카운터 존재 여부와 실제 잔여 재고를 확인하고 transaction을 rollback한다.
10. 실제 잔여 재고가 `0`이면 transaction 종료 후 Redis 품절 마커를 best-effort로 기록한다.
11. 실제 잔여 재고가 양수이면 요청 수량만 부족한 것이므로 마커를 기록하지 않는다.
12. 두 경우 모두 외부에는 기존 `ORDER_QUANTITY_UNAVAILABLE`을 반환한다.

Redis 호출은 DB transaction 밖에서 수행한다. Redis 지연이나 장애가 재고 카운터 row lock 보유 시간을 늘려서는 안 되며, Redis 결과를 DB transaction의 commit 또는 rollback 조건으로 사용하지 않는다.

## 실제 잔여 재고 판정

조건부 UPDATE의 영향받은 행이 `0`이면 판매 일정별 최초 수량과 현재 점유 수량을 이용해 실제 잔여 재고를 계산한다.

```text
remaining_quantity = sales.quantity - sale_inventory_counters.committed_quantity
```

현재 persistence 구현도 조건부 UPDATE 실패 시 카운터 누락과 재고 부족을 구분하기 위해 `committed_quantity`를 조회한다. 이 실패 후 조회를 판매 수량과 현재 점유 수량 또는 계산된 잔여 재고를 함께 반환하도록 변경한다. 따라서 재고 부족 판정을 위해 DB 왕복을 추가하지 않는다.

repository 결과는 최소한 다음 의미를 구분한다.

```kotlin
sealed interface SaleInventoryReserveResult {
    data object Acquired : SaleInventoryReserveResult
    data class Insufficient(val remainingQuantity: Int) : SaleInventoryReserveResult
    data object MissingCounter : SaleInventoryReserveResult
}
```

- `Acquired`: 조건부 UPDATE 성공
- `Insufficient(0)`: 완전 품절이며 마커 기록 대상
- `Insufficient(positive)`: 요청 수량만 부족하며 마커 기록 대상이 아님
- `MissingCounter`: 기존과 같은 데이터 불변식 위반

`remainingQuantity`가 음수이거나 `Int` 범위를 벗어나면 품절로 축약하지 않고 데이터 불변식 위반으로 transaction을 실패시키고 오류를 기록한다.

주문 transaction은 조건부 UPDATE 전에 저장한 주문과 만료 예약 반환을 재고 부족 시 함께 rollback해야 한다. 따라서 재고 부족을 정상 반환으로 commit하지 않고, 잔여 수량을 포함한 application 내부 실패로 transaction 경계 밖에 전달한다. 외부 `OrderService`가 transaction rollback 완료 후 품절 마커 기록 여부를 결정하고 기존 `OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)`으로 변환한다.

## Redis 품절 마커

논리적인 Redis 데이터는 다음과 같다.

```text
inventory:<saleId>:sold-out = "1"
TTL = 30초
```

실제 key에는 기존과 같이 환경별 namespace를 포함한다. 값은 존재 여부만 의미하며 남은 수량, 주문 ID, 구매자 정보나 예약 정보를 저장하지 않는다. 단일 key만 접근하므로 Lua script와 Redis Cluster hash tag는 필요하지 않다.

마커 조회는 주문당 하나의 Redis 존재 확인 명령으로 제한한다. 마커 기록은 실제 잔여 재고 `0`을 확인한 실패 요청에서만 수행하며 고정 TTL을 설정한다. 이미 마커가 있더라도 값을 덮어쓰거나 TTL을 연장하지 않도록 `SET NX`와 만료 시간을 함께 사용한다.

재고를 모두 소진한 마지막 성공 주문에서는 마커를 기록하지 않는다. 성공 경로에 Redis 쓰기를 추가하지 않고, 이후 처음 도착한 재고 부족 요청이 DB에서 잔여 재고 `0`을 확인해 마커를 생성한다. 이 요청 한 건의 DB 실패 비용은 성공 주문 지연을 줄이기 위한 트레이드오프로 수용한다.

## TTL과 허용하는 불일치

품절 마커의 기본 TTL은 30초이며 생성 이후 요청으로 연장하지 않는다. 마커가 만료되면 다음 요청이 다시 DB 조건부 UPDATE를 시도하고, DB가 계속 품절이면 새 마커를 기록한다. 만료 직후 동시에 들어온 여러 요청이 마커 기록 전까지 DB에 도달하는 짧은 stampede는 수용한다. PostgreSQL 조건부 UPDATE가 최종 정합성을 보장하므로 초과 판매로 이어지지 않는다.

예약 만료 또는 결제 실패로 DB 재고가 반환돼도 Redis 마커를 즉시 삭제하지 않는다. Redis 삭제와 DB commit 사이의 순서 경쟁 및 추가 보상 경로를 만들지 않고, 현재 아키텍처가 이미 허용하는 최대 30초의 과소 재고를 유지한다. 이 기간에는 판매 가능한 재고가 있어도 품절로 응답할 수 있다.

DB가 실제 잔여 재고 `0`을 조회한 직후 다른 transaction이 재고를 반환하는 경쟁도 가능하다. 이 경우 늦게 기록된 마커가 반환된 재고를 TTL 동안 가릴 수 있지만 동일한 과소 재고 한도 안에서 허용한다. Redis 마커는 판매 가능성을 보수적으로 줄일 수 있을 뿐 PostgreSQL 재고를 증가시키거나 초과 판매를 허용하지 않는다.

TTL은 `order.inventory.redis.sold-out-ttl` 설정으로 관리한다. 초기값은 기존 재고 캐시와 같은 30초로 두며, 성능 시험과 판매 기회 손실 관측 결과에 따라 별도 결정 없이 환경별로 조정할 수 있다. 요청마다 TTL을 갱신하는 방식은 품절 상태가 오래 지속될 때 반환 재고를 무기한 가릴 수 있으므로 사용하지 않는다.

## Redis 장애 처리

품절 마커는 성능 최적화 계층이며 주문 가능 여부의 최종 원천이 아니다.

- 마커 조회 연결 실패, timeout 또는 명령 오류: 마커가 없는 것으로 취급하고 DB 주문 transaction 실행
- 마커 기록 연결 실패, timeout 또는 명령 오류: DB 품절 결과 유지
- Redis 기능 비활성화: 모든 요청이 기존 DB 주문 경로 실행
- thread interrupt: interrupt flag를 복원하고 DB 경로로 우회

Redis 장애 때문에 주문을 실패시키거나 DB 성공 결과를 되돌리지 않는다. 오류 로그에는 `saleId`와 연산을 포함할 수 있지만 배송 정보, Redis credential과 명령 인자를 포함하지 않는다.

## 설정과 관측

Spring Data Redis와 Lettuce client, 기존 주문 재고용 connection 설정은 유지한다. 다음 설정은 유지하거나 품절 마커 의미로 변경한다.

- `order.inventory.redis.enabled`: 기본값 `false`, 명시적으로 활성화된 환경에서만 마커 사용
- `order.inventory.redis.namespace`: 환경별 key namespace
- `order.inventory.redis.connect-timeout`: Redis 연결 timeout
- `order.inventory.redis.command-timeout`: Redis 명령 timeout
- `order.inventory.redis.sold-out-ttl`: 품절 마커 고정 TTL, 기본 30초

기존 `stock-ttl`, 초기화 lock TTL, polling timeout과 polling interval 설정은 제거한다.

Micrometer 관측은 낮은 cardinality의 연산과 결과만 사용한다.

```text
operation = check | mark
result = hit | miss | marked | duplicate | fallback | error
```

마커 확인과 기록의 counter 및 timer를 남긴다. `saleId`, 주문 ID와 예외 종류는 metric tag로 사용하지 않는다. Redis 오류는 stack trace와 `saleId`, 연산을 로그에 남긴다.

## 계층과 변경 경계

- `presentation`: 기존 주문 HTTP 계약과 오류 변환을 유지하고 Redis 기술을 알지 않는다.
- `application`: 선행 멱등성 조회, 품절 마커 확인, DB transaction 실행과 transaction 종료 후 마커 기록을 조율한다.
- `domain`: 주문, 재고 예약과 판매 규칙을 유지하며 Redis 타입에 의존하지 않는다. 재고 확보 결과에는 실제 잔여 재고 의미를 표현한다.
- `infrastructure/persistence`: 조건부 UPDATE와 기존 실패 후 조회로 확보 결과와 실제 잔여 재고를 반환한다.
- `infrastructure/inventory`: Redis 품절 마커 조회·기록, 설정, key 생성과 관측을 구현한다.

application은 `StringRedisTemplate`이나 Redis 명령 결과에 직접 의존하지 않는다. 품절 마커의 업무 의미를 표현하는 application 경계를 사용한다. 기존 `InventoryReservation` 경계와 예약 token 결과는 제거하고, 품절 여부 확인과 기록만 노출하는 좁은 경계로 교체한다.

제거 대상은 다음과 같다.

- Redis 재고 snapshot 조회와 초기화 service
- Redis 초기화 분산 lock과 polling
- 재고 선점·복원·세대 폐기 Lua script
- generation과 주문별 Redis reservation key
- Redis 예약 token 생성과 DB 예약 ID 연결
- DB 실패와 멱등 복구에 따른 Redis 복원·폐기 조율

DB `inventory_reservations.id`는 Redis token과 분리하고 application에서 생성한 UUID를 사용한다.

## 오류 처리와 불변식

- Redis 마커 hit: 기존 `ORDER_QUANTITY_UNAVAILABLE`
- DB 조건부 UPDATE 실패 및 양수 잔여 재고: 기존 `ORDER_QUANTITY_UNAVAILABLE`, 마커 기록 안 함
- DB 조건부 UPDATE 실패 및 잔여 재고 `0`: 기존 `ORDER_QUANTITY_UNAVAILABLE`, rollback 후 마커 기록
- 판매 일정 없음과 판매 기간 오류: 기존 오류 계약 유지, 마커 기록 안 함
- 판매 일정은 존재하지만 재고 카운터가 없음: 내부 데이터 불변식 위반으로 transaction 실패 및 오류 로그
- 잔여 재고가 유효 범위를 벗어남: 내부 데이터 불변식 위반으로 transaction 실패 및 오류 로그
- 멱등 unique 충돌 복구: 기존 주문을 반환하고 마커 기록 안 함
- Redis 장애: DB 경로로 우회하고 DB 결과 유지

마커 hit은 DB의 현재 상태를 재검증하지 않으므로 TTL 동안 과소 재고를 허용한다. 반대로 마커 miss는 재고가 있다는 보장이 아니며 모든 신규 주문은 DB 조건부 UPDATE를 통과해야 한다.

## 검증 전략

### 기능과 정합성

- 선행 멱등성 조회가 기존 주문을 반환하면 Redis를 호출하지 않는지 확인한다.
- 마커 hit이면 DB transaction을 호출하지 않고 기존 품절 오류를 반환하는지 확인한다.
- 마커 miss와 Redis 조회 오류에서 DB transaction을 실행하는지 확인한다.
- 조건부 UPDATE 실패 후 기존 한 번의 후속 조회로 카운터 누락과 실제 잔여 재고를 구분하는지 확인한다.
- 실제 잔여 재고가 `0`일 때만 transaction rollback 후 마커를 기록하는지 확인한다.
- 잔여 재고가 양수인데 더 큰 수량을 요청한 경우 마커를 기록하지 않아 더 작은 후속 주문이 성공할 수 있는지 확인한다.
- 마지막 재고를 확보한 성공 주문은 마커를 기록하지 않고 정상 commit하는지 확인한다.
- 마커 기록 실패가 기존 DB 품절 결과를 변경하지 않는지 확인한다.
- 마커가 이미 있으면 중복 기록이 TTL을 연장하지 않는지 확인한다.
- 마커 만료 뒤 DB 재고가 반환된 주문이 성공할 수 있는지 확인한다.
- 마커 만료 뒤에도 DB가 품절이면 첫 실패 요청이 마커를 다시 생성하는지 확인한다.
- Redis 비활성화와 장애 상황에서도 PostgreSQL 조건부 UPDATE가 초과 판매를 방지하는지 확인한다.
- PostgreSQL 동시 주문에서 성공한 예약 수량 합계가 최초 판매 수량을 넘지 않는지 확인한다.
- 기존 주문, 결제와 재고 예약 상태 전이 테스트가 유지되는지 확인한다.
- 기존 snapshot, 초기화 lock, polling, Lua 선점·복원·폐기와 예약 token 코드 및 설정이 남지 않는지 확인한다.

PostgreSQL 동시성과 transaction rollback은 PostgreSQL Testcontainers로 검증하고, Redis 마커 TTL과 장애 우회는 PostgreSQL 및 Redis Testcontainers 통합 테스트로 검증한다.

### 성능

성공 주문과 품절 주문을 같은 표본에 섞지 않고 다음 시나리오를 분리한다.

1. 재고가 충분한 성공 전용 시나리오
2. 소량 재고가 소진된 뒤 품절 요청이 지속되는 시나리오
3. Redis 비활성화, 기존 Redis 선점 기준 결과와 품절 마커 활성화 결과 비교

각 시나리오는 동일한 애플리케이션, PostgreSQL, Redis, connection pool, RPS, duration과 데이터 분포에서 warm-up 후 최소 5회 반복한다. 성공·품절별 요청 수, 처리량, p50·p90·p95·p99, 최대 지연, 오류율, dropped iteration, PostgreSQL connection과 lock 대기, Redis 명령 지연 및 애플리케이션 CPU를 함께 기록한다.

완료 기준은 다음과 같다.

- 성공 주문 p95: Redis 비활성화 기준 대비 20% 이내
- 성공 주문 오류율과 dropped iteration: Redis 비활성화 기준보다 유의미하게 악화되지 않음
- 품절 마커 hit p95: 기존 Redis 선점 품절 p95보다 유의미하게 악화되지 않음
- 품절 안정 구간의 신규 DB 주문 transaction: 마커 hit 요청에서는 발생하지 않음
- 모든 시나리오: 초과 판매 없음, 성공 주문 수량과 DB 예약·카운터 일치

단일 실행 결과만으로 채택 여부를 확정하지 않는다. 반복 결과의 중앙값과 변동 범위를 함께 비교하고, 성공 p95 기준을 충족하지 못하면 Redis 조회 자체의 비용과 connection 구성을 분석한 뒤 DB 전용 방식도 다시 비교한다.

## 트레이드오프

성공 주문도 품절 마커를 확인하기 위한 Redis 왕복 한 번을 수행하므로 Redis 비활성화 경로와 같은 지연을 보장할 수 없다. 대신 현재 Lua 선점, Redis 쓰기와 주문별 예약 key 생성 비용을 제거한다.

마커가 생성되기 전 첫 품절 요청과 TTL 만료 직후의 동시 요청은 DB에 도달한다. 이를 막기 위한 Single-flight나 lock은 성공 경로와 구현 복잡도를 다시 늘리므로 사용하지 않는다.

재고 반환을 마커에 즉시 반영하지 않아 최대 TTL 동안 판매 기회를 잃을 수 있다. 이는 현재 Redis 선점 설계와 같은 과소 재고 허용 범위이며, 단순한 보조 계층과 DB 최종 정합성을 유지하기 위한 선택이다.

실제 잔여 재고 확인은 조건부 UPDATE 실패 시에만 수행한다. 성공 경로에는 추가 DB 조회를 넣지 않지만, 완전 품절이 아닌 큰 수량 요청도 실패 후 조회 비용을 부담한다. 현재 구현이 카운터 누락 판정을 위해 이미 수행하는 조회를 확장하므로 DB 왕복 수는 증가하지 않는다.

## 후속 단계

설계 승인 후 구현 전에 새 ADR로 Redis 품절 마커 선택을 기록하고 ADR-026과 ADR-027의 상태, ADR 목록 및 백엔드 아키텍처의 Redis 재고 규칙을 갱신한다. 구현과 검증은 별도 계획 또는 명시적인 구현 요청으로 진행한다.
