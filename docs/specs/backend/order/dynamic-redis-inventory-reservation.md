# 동적 Redis 재고 선점 설계

## 목적과 완료 조건

특정 판매 일정에 주문이 집중될 때만 Redis 재고를 동적으로 활성화해 품절 요청을 DB 접근 전에 빠르게 거절한다. Redis는 짧은 수명의 보조 선점 계층으로 사용하고, 기존 PostgreSQL 주문 예약 집계와 판매 일정 비관적 잠금은 최종 재고 정합성 기준으로 유지한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- `POST /api/orders`의 신규 주문 가능성이 있는 요청만 Redis 재고 선점을 시도한다.
- 같은 `saleId`의 캐시 초기화가 여러 애플리케이션 인스턴스에서 몰려도 Redis 분산 lock 획득자만 DB 재고 snapshot을 조회한다.
- Redis Lua script가 재고 확인, 차감과 예약 토큰 생성을 원자적으로 수행한다.
- Redis가 품절을 반환하면 DB transaction을 시작하지 않고 `ORDER_QUANTITY_UNAVAILABLE`을 반환한다.
- Redis 예약에 성공한 요청도 기존 DB 잠금과 예약 집계를 거쳐 최종 검증한다.
- Redis 장애나 초기화 실패는 주문 생성을 실패시키지 않고 기존 DB 경로로 우회한다.
- DB에서 새 주문이 만들어지지 않으면 Redis 예약을 멱등하게 복원한다.
- Redis 재고는 초기화 후 고정 30초 동안만 유지하고 요청으로 TTL을 연장하지 않는다.
- 기존 HTTP 요청·응답 및 오류 계약을 변경하지 않는다.

## 기존 구조와 결정

[백엔드 아키텍처](../../../architecture/backend.md), [주문 행 기반 재고 예약과 판매 일정 잠금 결정](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md), [동적 Redis 재고 선점 결정](../../../architecture/decisions/ADR-026-dynamic-redis-inventory-reservation.md)을 따른다.

현재 주문 생성은 대상 `sales` 행에 비관적 쓰기 잠금을 획득한 뒤 유효한 주문 예약과 결제 상태를 집계한다. 이번 단계는 모든 판매 재고를 Redis에 상시 적재하지 않는다. 실제 주문 요청이 발생한 `saleId`만 짧게 활성화하고, Redis가 명백한 품절로 판단한 요청을 빠르게 거절한다. 별도 DB 예약 테이블과 복구 배치가 없는 동안 Redis는 최종 재고 원천이나 영구 예약 기록이 아니다.

## 범위

포함 범위는 Redis 연결과 주문 재고 전용 설정, `saleId` 단위 동적 재고 캐시, Redis 분산 lock 기반 Single-flight, Lua 기반 원자적 재고 초기화·선점·복원·폐기, Redis 품절의 즉시 응답, Redis 장애 시 DB 우회, 기존 DB 최종 검증 유지 및 관련 테스트다.

다음은 제외한다.

- 별도 DB 재고 예약 테이블과 예약 상태 모델
- 주문 만료·취소·결제 실패에 따른 Redis 즉시 복구
- 정합성 복구 배치와 운영자용 수동 복구
- DB 비관적 잠금 제거
- Redis를 영구적인 재고 원천으로 사용하는 구조
- Redis 장애로 유실된 미완료 예약의 영속 재처리

## 주문 처리 흐름

`OrderService`가 기존 선행 멱등성 조회를 완료한 뒤 Redis 선점과 DB 주문 transaction을 조율한다.

1. `(buyerId, idempotencyKey)`에 해당하는 기존 주문을 조회한다.
2. 기존 주문이 동일 요청이면 즉시 반환하고 Redis를 호출하지 않는다.
3. Redis 재고 캐시가 없으면 모든 요청이 해당 `saleId`의 초기화 lock 획득을 시도한다.
4. lock 획득자는 DB 재고 snapshot을 읽어 캐시를 초기화하고, 미획득자는 제한된 시간 동안 캐시 생성을 polling한다.
5. 초기화가 성공하거나 polling 중 캐시가 생성되면 Lua script로 요청 수량을 선점한다.
6. Lua가 품절을 반환하면 `ORDER_QUANTITY_UNAVAILABLE`을 즉시 반환한다.
7. Redis 장애, polling timeout 또는 초기화 실패이면 Redis 없이 기존 DB 주문 transaction을 실행한다.
8. Redis 선점에 성공하면 기존 DB 주문 transaction을 실행한다.
9. DB에서 신규 주문이 commit되면 Redis 차감을 유지한다.
10. DB가 요청을 거절·rollback하거나 잠금 후 기존 멱등 주문을 반환하면 Redis 예약을 복원한다.
11. Redis 복원이 실패해도 DB 결과를 변경하지 않고 오류와 지표를 남긴다.

Redis 호출은 DB transaction 밖에서 수행한다. Redis 지연이 판매 일정 row lock 보유 시간을 늘려서는 안 된다.

## 재고 초기화와 Single-flight

초기화할 재고는 `Sale.quantity - 유효한 예약 및 결제완료 수량`이다. 유효 수량은 기존 주문 집계와 같은 의미를 사용한다. 만료되지 않은 `PENDING_PAYMENT`, `PAID`, 결제가 `CONFIRMING` 또는 `REVIEW_REQUIRED`인 주문을 포함하고 실패가 확정된 결제는 제외한다. 계산된 가용 수량은 기존 `Int` 재고 모델에 맞춰 `0..Int.MAX_VALUE` 범위여야 하며, 범위를 벗어나면 초기화를 실패시키고 DB 경로로 우회한다.

JVM 내부 `ConcurrentHashMap`이나 `CompletableFuture`로 초기화 결과를 공유하지 않는다. 단일 인스턴스를 포함한 모든 cache miss 요청은 고유 token으로 Redis 초기화 lock의 `SET NX PX`를 시도하며, lock을 획득한 요청만 DB snapshot을 읽는다. snapshot 조회는 `REPEATABLE_READ`, 읽기 전용 transaction에서 수행하고 최대 2초로 제한한다.

초기화 lock을 얻지 못한 요청은 기본 500ms 동안 25ms 간격으로 재고 캐시 생성 여부를 polling한다. 제한 시간 뒤에도 재고가 없으면 DB 경로로 우회한다. polling 중 interrupt가 발생하면 interrupt flag를 복원하고 Redis 대기를 즉시 중단한 뒤 DB 경로로 우회한다.

lock 획득자는 DB snapshot 조회 뒤 Lua script로 lock token 소유권과 재고 캐시 부재를 원자적으로 확인한 후에만 재고를 생성한다. lock TTL 만료나 token 변경으로 소유권을 잃은 요청은 Redis 재고를 생성하지 않고 DB 경로로 우회한다. lock 해제도 Lua script에서 현재 token이 요청 token과 같을 때만 수행한다.

```text
ABSENT → INITIALIZING → READY
                    └→ DB_FALLBACK
```

## Redis 데이터와 Lua 계약

논리적인 Redis 데이터는 다음 정보를 가진다.

```text
inventory:{saleId}:stock
- generation
- available

inventory:{saleId}:reservation:{reservationId}
- saleId
- generation
- quantity
```

실제 자료구조와 직렬화 방식은 이 의미와 원자적 연산 요구사항을 만족하는 범위에서 구현 시 정한다. key prefix는 환경별 namespace를 포함할 수 있어야 한다. Redis Cluster를 사용해도 하나의 Lua script가 접근하는 재고와 예약 key가 같은 hash slot에 배치되도록 `saleId` hash tag를 공유한다.

`generation`은 초기화마다 생성한다. 만료된 이전 캐시의 늦은 복원이 새 재고를 증가시키지 않도록 예약과 복원에서 현재 세대를 확인한다. `reservationId`는 선점마다 서버가 생성한 UUID다. 구매자 멱등성 키는 구매자 범위에서만 고유하므로 Redis 전역 예약 식별자로 단독 사용하지 않는다.

재고 선점 script는 현재 세대와 중복 예약을 확인하고, 가용 재고가 충분할 때 차감과 예약 토큰 기록을 원자적으로 수행한다. 같은 `reservationId`의 기존 예약이 요청의 `saleId`, `generation`, `quantity`와 모두 같을 때만 `DUPLICATE`로 인정한다. 하나라도 다르면 `CONFLICT`로 구분한다. 결과는 `RESERVED`, `INSUFFICIENT`, `NOT_INITIALIZED`, `DUPLICATE`, `CONFLICT`를 구분한다. `INSUFFICIENT`는 즉시 품절로 변환하고 `NOT_INITIALIZED`, `CONFLICT`와 기술 오류는 DB 경로로 우회한다.

재고 복원 script는 예약 토큰이 존재하고 예약 세대가 현재 재고 세대와 일치할 때만 수량을 증가시키고 토큰을 제거한다. 이미 복원됐거나 세대가 다르면 재고를 변경하지 않는다. DB가 실제 품절을 확인하면 해당 Redis 세대를 폐기하고 다음 요청이 DB snapshot으로 다시 초기화하게 한다.

## DB transaction 결과와 보상

현재 주문 transaction은 판매 일정 잠금을 기다린 뒤 기존 멱등 주문을 발견해 반환할 수 있다. Redis 예약 유지 여부를 판단하도록 내부 결과가 신규 생성 여부를 구분해야 한다.

```kotlin
data class OrderCreationResult(
    val response: OrderResponse,
    val created: Boolean,
)
```

판매 일정 부재·판매 기간 오류·DB 재고 부족·잠금 후 멱등 주문 발견·멱등 unique 충돌 복구·저장 또는 commit 실패처럼 신규 주문이 생성되지 않은 모든 경우에 Redis 예약을 복원한다. 신규 주문 commit이 확인된 경우에만 차감을 유지하며 복원 실패는 DB 결과를 덮지 않는다.

## TTL과 허용하는 불일치

재고 캐시는 초기화 시점부터 고정 30초 동안 유지한다. 주문 요청이나 선점 성공 때 TTL을 연장하지 않는다. 초기화 lock의 기본 TTL은 3초로 하되 두 값 모두 설정으로 관리한다. 예약 토큰은 자신이 속한 재고 세대보다 오래 유효하지 않아야 한다. 복원·폐기 실패에 대한 별도 재시도, Outbox 또는 reconciliation은 두지 않으며, 고정 TTL로 과소 재고 영향을 최대 30초로 제한한다.

1단계에서는 다음 제한을 수용한다.

- 선점 후 DB 결과 반영 전에 프로세스가 종료되거나 주문 상태 변화가 즉시 반영되지 않으면 실제보다 적은 재고가 최대 30초 동안 유지될 수 있다.
- Redis 예약 후 DB commit 전에 캐시가 만료되면 새 snapshot에 처리 중 주문이 보이지 않아 Redis가 재고를 과대 계산할 수 있다. 기존 DB 잠금과 최종 집계가 초과 판매를 방지한다.
- Redis 과소 재고는 제한된 판매 기회 손실을 만들 수 있지만 과대 재고는 DB 검증 때문에 초과 판매로 이어지지 않는다.

## 구현 확정 사항

Redis 연동은 Spring Data Redis의 Lettuce client를 사용한다. 기존 환경에서 Redis 미구성으로 주문 지연이 발생하지 않도록 `order.inventory.redis.enabled`의 기본값은 `false`로 두고, 운영 환경에서 명시적으로 활성화한다. Redis 기능이 비활성화되면 기존 DB 주문 경로만 실행한다.

Redis 연결 timeout은 기본 500ms, 명령 timeout은 기본 200ms로 설정한다. 다른 인스턴스가 진행 중인 초기화를 기다리는 최대 시간은 기본 500ms, 재고 생성 여부 확인 간격은 기본 25ms로 설정한다. 모든 값은 환경별 설정으로 변경할 수 있으며, timeout을 초과하면 DB 경로로 우회한다.

초기 재고 snapshot의 `Sale.quantity`와 유효한 예약 및 결제완료 수량은 최대 2초의 `REPEATABLE_READ` 읽기 전용 transaction에서 조회해 동일한 DB snapshot을 사용한다. snapshot 생성은 판매 일정 행을 잠그지 않으며, Redis 선점 뒤의 기존 DB transaction이 최종 정합성을 검증한다. 계산 결과가 `0..Int.MAX_VALUE` 범위를 벗어나면 Redis 재고를 생성하지 않는다.

동일한 `reservationId`의 예약 토큰이 이미 존재하고 `saleId`, `generation`, `quantity`가 모두 같아 선점 script가 `DUPLICATE`를 반환하면 기존 예약 성공으로 간주하고 DB 주문 transaction을 진행한다. Redis 응답 유실 뒤 동일한 예약을 재실행하더라도 별도 차감 없이 기존 예약을 보상에 사용할 수 있어야 한다. 세 값 중 하나라도 다른 `CONFLICT`는 예약 성공으로 간주하지 않고 DB 경로로 우회한다.

Redis 선점 뒤 DB가 실제 품절을 확인하면 generation을 확인하는 Lua script로 현재 재고 세대와 해당 예약 토큰을 폐기하고 예약 수량은 복원하지 않는다. 폐기 실패도 DB 품절 결과를 변경하지 않으며 오류와 지표를 남긴다. DB 품절 이외의 사유로 신규 주문이 만들어지지 않으면 기존 복원 규칙을 적용한다.

관측에는 Spring Boot Actuator와 Micrometer의 counter 및 timer를 사용한다. `saleId`와 `reservationId`는 높은 cardinality를 만들 수 있으므로 metric tag에 넣지 않고 필요한 오류 로그의 문맥에만 포함한다. 외부 metric exporter와 추가 Actuator Web endpoint 노출은 이번 범위에 포함하지 않는다.

Redis Cluster 호환성은 같은 `saleId`의 key가 동일한 hash tag를 사용하도록 보장한다. 자동화 테스트는 단일 Redis Testcontainer에서 Lua와 동시성 동작을 검증하고, key 생성 테스트로 같은 판매 일정 key의 hash tag 규칙을 확인한다. 실제 다중 node Redis Cluster 통합 테스트는 이번 범위에 포함하지 않는다.

## 장애 처리와 관측

Redis 연결 실패, timeout, script 오류와 초기화 실패를 포함한 Redis 경로의 비-interrupt 예외는 보조 계층의 fail-open 정책에 따라 기존 DB 주문 경로로 우회한다. 예외 stack trace를 로그에 남기고 낮은 cardinality 지표를 기록한다. polling interrupt는 interrupt flag를 복원하고 Redis 대기를 중단한 뒤 DB 경로로 우회한다. Redis의 업무 결과인 `INSUFFICIENT`만 fail-fast 품절 응답으로 사용한다.

metric의 `operation`은 `initialize`, `reserve`, `restore`, `invalidate`로 통일하고 `result`는 `reserved`, `duplicate`, `conflict`, `insufficient`, `fallback`, `error`를 구분한다. `saleId`, `reservationId`, 예외 종류는 metric tag로 사용하지 않는다. 캐시 초기화 결과와 polling timeout, 선점 성공·품절·우회, DB 최종 거절에 따른 세대 폐기, 복원 성공·실패·멱등 no-op, Redis 호출 지연을 이 분류 안에서 관찰할 수 있어야 한다. 로그에는 필요한 식별 문맥과 예외 stack trace를 남기되 배송 정보, Redis credential과 script 내용을 포함하지 않는다.

## 계층과 변경 경계

- `presentation`: 기존 HTTP 계약과 오류 변환을 유지하고 Redis 기술을 알지 않는다.
- `application`: 선행 멱등성 조회, Redis 선점, DB transaction과 결과 보상을 조율한다.
- `domain`: 기존 주문과 판매 규칙을 유지하며 Redis 타입에 의존하지 않는다.
- `infrastructure`: Redis 연결, 자료구조, Lua script 실행과 결과 매핑을 구현한다.

Redis 구현이 하나뿐인 1단계에서는 불필요한 domain repository를 만들지 않는다. 다만 application 코드가 Redis client와 script 타입에 직접 의존하지 않도록 재고 선점의 업무 결과를 표현하는 경계를 둔다.

## 검증 전략

- 선행 멱등성 조회가 기존 주문을 반환하면 Redis를 호출하지 않는지 확인한다.
- Redis 선점 성공과 신규 주문 생성 시 복원하지 않는지 확인한다.
- DB 거절, rollback과 기존 멱등 주문 반환 시 정확히 한 번 복원하는지 확인한다.
- Redis 장애와 초기화 timeout에서 기존 DB 흐름을 실행하는지 확인한다.
- 여러 애플리케이션 인스턴스의 동시 cache miss에서도 Redis lock 획득자만 DB snapshot을 읽는지 확인한다.
- lock TTL 만료 또는 token 변경 뒤 이전 소유자의 초기화와 lock 해제가 거절되는지 확인한다.
- lock 미획득 timeout, polling interrupt와 Redis 장애가 DB fallback으로 이어지고 interrupt flag가 복원되는지 확인한다.
- Redis 품절에서는 DB transaction을 호출하지 않는지 확인한다.
- 병렬 선점 성공 수량 합이 초기 Redis 재고를 넘지 않는지 확인한다.
- 동일 예약 재시도는 `DUPLICATE`로 처리하고 수량·세대 또는 판매 일정이 다른 중복 예약은 `CONFLICT`로 구분하는지 확인한다.
- 중복 복원과 이전 세대의 늦은 복원이 현재 재고를 증가시키지 않는지 확인한다.
- 복원·폐기 실패가 DB 결과를 덮지 않고 고정 30초 TTL 이후 snapshot으로 재초기화되는 정책과 지표를 확인한다.
- metric의 `operation`과 `result` 명칭 및 분류가 모든 Redis 경로에서 일관되는지 확인한다.
- PostgreSQL과 Redis Testcontainers로 초과 판매 방지, 멱등 요청, Redis 장애 우회와 DB 실패 보상을 통합 검증한다.
- 기존 주문 생성 application, persistence, HTTP와 REST Docs 테스트를 유지한다.

## 후속 단계

DB 예약 테이블과 상태 전이, 주문 취소·만료 시 즉시 복구, 정합성 복구 배치와 비관적 잠금 제거는 별도 설계로 진행한다. 이 기능들이 완료되기 전에는 Redis 재고를 영구적인 재고 원천으로 승격하지 않는다.
