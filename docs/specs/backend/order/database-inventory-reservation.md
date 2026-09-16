# DB 조건부 재고 확보와 주문별 예약 관리 설계

## 목적과 완료 조건

판매 일정 행의 비관적 잠금과 유효 주문 수량 집계를 제거하고, PostgreSQL의 조건부 UPDATE와 주문별 재고 예약 상태로 최종 재고 정합성을 관리한다. Redis는 기존과 같이 품절 요청을 DB 접근 전에 줄이는 보조 선점 계층으로 유지하며 PostgreSQL을 최종 재고 원천으로 사용한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 판매 일정별 최초 판매 수량은 기존 `sales.quantity`를 단일 원천으로 유지한다.
- 판매 일정별 점유 수량은 `sale_inventory_counters.committed_quantity`로 관리한다.
- 주문 생성은 조건부 UPDATE가 성공한 경우에만 주문과 재고 예약을 같은 transaction에서 생성한다.
- 같은 판매 일정에 주문이 동시에 몰려도 확정 및 유효 예약 수량 합계가 `sales.quantity`를 넘지 않는다.
- 주문별 재고 점유는 `inventory_reservations`의 명시적인 상태 전이로 관리한다.
- 결제 진행 중이거나 수동 확인이 필요한 주문은 주문 만료 시각 이후에도 재고를 유지한다.
- 결제 확정 실패와 결제 시작 전 주문 만료는 재고를 정확히 한 번 반환한다.
- 주문 생성과 결제 흐름에서 `sales` 행의 비관적 잠금을 사용하지 않는다.
- Redis 장애 시 PostgreSQL 주문 경로로 우회하고, 기존 HTTP 요청·응답 및 오류 계약을 변경하지 않는다.

## 기존 구조와 변경 이유

현재 주문 생성은 대상 `sales` 행에 `PESSIMISTIC_WRITE` 잠금을 획득한 뒤 유효한 주문과 결제 수량을 집계한다. 같은 판매 일정의 주문 생성과 결제 상태 변경이 하나의 잠금에 직렬화되므로 정합성은 단순하지만 인기 판매 일정의 처리량이 제한되고 주문량이 증가할수록 집계 비용이 커진다.

예약 행만 추가하고 `SUM → INSERT`를 유지하면 여러 transaction이 같은 합계를 읽은 뒤 각각 예약을 생성할 수 있어 초과 판매를 막지 못한다. 반대로 집계 카운터만 추가하면 어떤 주문이 재고를 점유하는지, 만료나 결제 실패로 어떤 수량을 반환해야 하는지와 중복 반환 여부를 판단할 수 없다.

따라서 판매 일정별 집계 카운터와 주문별 예약 행을 함께 둔다. 카운터의 조건부 UPDATE가 재고 확보 경쟁을 해결하고, 예약 상태의 조건부 전이가 재고 점유 생명주기와 반환 멱등성을 관리한다.

이 결정은 [ADR-015](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md)의 주문 행 집계와 판매 일정 잠금을 대체하고, [ADR-019](../../../architecture/decisions/ADR-019-payment-attempt-and-reservation-consistency.md)의 판매 일정 잠금 기반 결제 직렬화를 변경한다. 구현 전 두 결정을 대체하는 새 ADR을 기록하고 [백엔드 아키텍처](../../../architecture/backend.md)의 현재 규칙을 갱신한다.

## 범위

포함 범위는 다음과 같다.

- 판매 일정별 재고 카운터와 주문별 재고 예약 테이블
- 판매 등록 시 재고 카운터 생성
- 기존 판매·주문·결제 데이터의 카운터 및 예약 데이터 이관
- 조건부 UPDATE 기반 재고 확보와 반환
- 주문 생성과 재고 예약 생성의 단일 transaction 처리
- 결제 시작·승인·실패에 따른 예약 상태 전이
- 주문 요청 및 Redis snapshot 재생성 시 판매 일정 단위 만료 예약 지연 반환
- 주문 및 결제 흐름의 `sales` 비관적 잠금 제거
- Redis 재고 snapshot의 카운터 기반 조회
- 관련 domain, persistence, application 및 통합 테스트

다음은 제외한다.

- 주문 취소 API
- 별도 예약 만료 scheduler 또는 batch
- 운영자용 재고 조정 및 수동 복구 기능
- DB와 Redis 정합성 복구 batch
- 결제 실패나 만료에 따른 Redis 재고의 즉시 복원
- Redis를 영구적인 재고 원천으로 사용하는 구조
- 여러 판매 일정 또는 여러 상품을 묶는 원자적 주문
- 기존 주문 HTTP 계약 변경

## 데이터 모델

### 판매 일정별 재고 카운터

`sale_inventory_counters`는 판매 일정별로 한 행만 가지며 현재 재고를 점유하는 전체 수량을 저장한다.

```text
sale_inventory_counters
- sale_id              BIGINT PRIMARY KEY, FK → sales.id
- committed_quantity   INTEGER NOT NULL
- created_at           TIMESTAMP WITH TIME ZONE NOT NULL
- updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
```

`committed_quantity`에는 `RESERVED`, `PAYMENT_PENDING`, `CONFIRMED` 예약 수량이 포함되고 `RELEASED` 예약 수량은 포함하지 않는다. 값은 항상 0 이상이어야 하며 DB CHECK 제약으로 음수를 금지한다. 최초 판매 수량은 중복 저장하지 않고 `sales.quantity`를 사용한다.

가용 재고는 다음과 같다.

```text
available_quantity = sales.quantity - sale_inventory_counters.committed_quantity
```

### 주문별 재고 예약

`inventory_reservations`는 한 주문이 점유한 재고와 그 생명주기를 저장한다.

```text
inventory_reservations
- id          UUID PRIMARY KEY
- sale_id     BIGINT NOT NULL, FK → sales.id
- order_id    BIGINT NOT NULL, FK → orders.id, UNIQUE
- quantity    INTEGER NOT NULL
- status      VARCHAR(30) NOT NULL
- expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
- created_at  TIMESTAMP WITH TIME ZONE NOT NULL
- updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
```

`quantity`는 양수여야 한다. `status`는 `RESERVED`, `PAYMENT_PENDING`, `CONFIRMED`, `RELEASED`만 허용한다. `order_id` unique 제약으로 한 주문에 둘 이상의 예약이 생기지 않게 한다. 만료 예약 조회를 위해 `(sale_id, status, expires_at)` index를 둔다.

`InventoryReservation`은 주문 Entity와 양방향 JPA 연관관계를 맺지 않고 `orderId`와 `saleId`로 참조한다. 상태 변경은 domain 규칙 또는 그 규칙을 표현하는 repository의 기대 상태 기반 연산으로만 수행하며 공개 setter를 두지 않는다.

### 예약 상태 전이

```text
RESERVED
├─ 결제 시작 ─────────→ PAYMENT_PENDING
└─ 결제 시작 전 만료 ─→ RELEASED

PAYMENT_PENDING
├─ 승인 성공 ─────────→ CONFIRMED
├─ 확정 실패 ─────────→ RELEASED
└─ 결과 불명확 ───────→ PAYMENT_PENDING
```

`PAYMENT_PENDING`에는 기존 `Payment.CONFIRMING`과 `Payment.REVIEW_REQUIRED`가 재고를 점유하는 의미가 포함된다. 결제 재확인 대기나 수동 확인 전환만으로 예약을 반환하지 않는다. `CONFIRMED`와 `RELEASED`는 terminal 상태다.

## 판매 등록과 기존 데이터 이관

판매 등록 transaction은 `Sale` 저장 후 같은 transaction에서 `committed_quantity = 0`인 카운터를 생성한다. 판매 등록이 rollback되면 카운터 생성도 함께 rollback된다.

Flyway migration은 기존 데이터를 다음 순서로 이관한다.

1. `sale_inventory_counters`와 `inventory_reservations`를 생성한다.
2. 모든 기존 주문에 예약 행을 한 건씩 생성한다.
3. 주문과 결제 상태를 기준으로 예약 상태를 결정한다.
4. `RELEASED`가 아닌 예약 수량을 판매 일정별로 합산해 카운터를 생성한다.
5. 주문이 없는 판매 일정도 `committed_quantity = 0`인 카운터를 생성한다.

기존 데이터의 예약 상태는 migration 실행 시각을 기준으로 다음과 같이 변환한다.

- `Order.PAID` 또는 `Payment.APPROVED`: `CONFIRMED`
- `Payment.CONFIRMING` 또는 `Payment.REVIEW_REQUIRED`: `PAYMENT_PENDING`
- `Payment.FAILED`: `RELEASED`
- 결제 시도가 없고 아직 만료되지 않은 `Order.PENDING_PAYMENT`: `RESERVED`
- 결제 시도가 없고 이미 만료된 `Order.PENDING_PAYMENT`: `RELEASED`

한 주문에 하나의 결제만 허용하는 기존 unique 제약을 전제로 한다. 이관 결과에서 판매 일정별 비반환 예약 합계가 `sales.quantity`를 초과하거나 음수 카운터가 발생하면 migration을 실패시킨다.

## 조건부 재고 확보

재고 확보는 카운터를 읽은 뒤 application에서 비교하지 않고 단일 조건부 UPDATE로 수행한다.

```sql
UPDATE sale_inventory_counters inventory
SET committed_quantity = inventory.committed_quantity + :quantity,
    updated_at = :now
FROM sales sale
WHERE inventory.sale_id = sale.id
  AND inventory.sale_id = :saleId
  AND :quantity > 0
  AND inventory.committed_quantity <= sale.quantity - :quantity
```

영향받은 행이 한 건이면 재고 확보 성공이고 0건이면 재고가 부족한 것이다. 요청 수량은 domain 입력 검증에서 양수와 `Int` 범위를 보장한다. 덧셈 결과를 먼저 비교하지 않고 `committed_quantity <= sale.quantity - quantity`를 사용해 정수 덧셈 overflow를 피한다.

이미 일반 조회로 존재를 확인한 판매 일정의 카운터가 없으면 품절로 처리하지 않고 데이터 불변식 위반으로 transaction을 실패시키고 오류를 기록한다.

## 주문 생성 흐름

`OrderService`는 기존과 같이 선행 멱등성 확인, Redis 선점, DB transaction과 Redis 보상을 조율한다.

1. `(buyerId, idempotencyKey)`에 해당하는 기존 주문을 조회한다.
2. 동일 요청의 기존 주문이면 Redis와 재고 카운터를 호출하지 않고 반환한다.
3. Redis가 활성화되어 있으면 기존 Lua 선점을 수행한다. Redis 장애나 초기화 실패는 DB 경로로 우회한다.
4. DB transaction에서 판매 일정과 상품을 일반 조회하고 판매 가능 여부를 검증한다.
5. transaction 내부에서 멱등 주문을 다시 확인한다.
6. 해당 판매 일정의 만료된 `RESERVED` 예약을 지연 반환한다.
7. 주문을 저장한다. identity ID 발급을 위한 INSERT는 아직 외부에 commit되지 않는다.
8. 카운터 조건부 UPDATE로 재고를 확보한다. 실패하면 `ORDER_QUANTITY_UNAVAILABLE`로 transaction 전체를 rollback한다.
9. 주문 ID, 판매 일정 ID, 수량과 주문 만료 시각으로 `RESERVED` 예약을 저장한다.
10. 주문, 카운터와 예약을 함께 commit한다.

조건부 재고 확보 이후에는 예약 INSERT와 commit만 남기며, 같은 판매 일정의 카운터 행을 점유하는 시간을 짧게 유지한다. 주문 unique 충돌, 예약 저장 실패 또는 commit 실패 시 카운터 증가와 주문 INSERT도 함께 rollback된다.

Redis 선점에서 생성한 `reservationId`를 DB 예약 ID로 사용한다. Redis 없이 DB로 우회한 요청은 application에서 새 UUID를 생성한다. Redis와 DB 사이에 외래 키나 분산 transaction을 두지 않는다.

## Redis와 DB 결과 조율

Redis는 보조 선점 계층이고 DB 카운터와 예약이 최종 정합성 기준이다.

- DB 신규 주문 commit 성공: Redis 차감을 유지한다.
- DB 조건부 UPDATE가 재고 부족을 반환: 현재 Redis generation과 예약 token을 폐기한다.
- DB 검증, INSERT, rollback 또는 commit 실패: Redis 예약을 멱등하게 복원한다.
- DB transaction이 잠금 대기 후 기존 멱등 주문을 반환하는 기존 분기는 더 이상 판매 일정 잠금에 의존하지 않지만, unique 충돌 복구로 기존 주문을 반환하면 Redis 예약을 복원한다.
- Redis 복원 또는 폐기 실패는 DB 결과를 변경하지 않는다.

Redis inventory snapshot은 주문과 결제 행을 집계하지 않고 다음 값을 조회한다.

```sql
SELECT sale.quantity - inventory.committed_quantity
FROM sales sale
JOIN sale_inventory_counters inventory ON inventory.sale_id = sale.id
WHERE sale.id = :saleId
```

snapshot transaction은 먼저 해당 판매 일정의 만료 예약을 지연 반환한 뒤 가용 재고를 읽는다. 따라서 기존 읽기 전용 transaction은 쓰기 transaction으로 변경하되 기존 2초 timeout을 유지한다. Redis cache가 이미 존재하고 품절을 반환하면 DB 만료 정리가 실행되지 않을 수 있으므로 최대 기존 inventory TTL 30초 동안 과소 재고를 허용한다. TTL 만료 뒤 snapshot을 재생성할 때 만료 예약을 반환하며, 과대 재고는 DB 조건부 UPDATE가 최종 차단한다.

## 예약 반환과 만료 처리

예약 반환은 기대 상태를 조건으로 `RELEASED` 전이를 먼저 시도하고, 실제 상태 변경에 성공한 경우에만 같은 transaction에서 카운터를 감소시킨다.

```sql
UPDATE inventory_reservations
SET status = 'RELEASED',
    updated_at = :now
WHERE id = :reservationId
  AND status = :expectedStatus
```

```sql
UPDATE sale_inventory_counters
SET committed_quantity = committed_quantity - :quantity,
    updated_at = :now
WHERE sale_id = :saleId
  AND committed_quantity >= :quantity
```

만료 반환은 `expectedStatus = RESERVED`, 결제 실패 반환은 `expectedStatus = PAYMENT_PENDING`을 사용한다. 첫 UPDATE가 0건이면 카운터를 변경하지 않고 현재 예약 상태를 조회한다. 이미 `RELEASED`이면 멱등 성공으로 처리하고, `CONFIRMED`이거나 호출 원인과 맞지 않는 활성 상태이면 경쟁 결과 또는 불변식 위반으로 처리한다. 첫 UPDATE가 성공했지만 두 번째 UPDATE가 0건이면 데이터 불변식 위반으로 transaction을 rollback하고 오류를 기록한다.

만료 지연 반환은 `status = RESERVED AND expires_at <= now`인 같은 판매 일정의 예약만 대상으로 한다. 여러 요청이 같은 만료 예약을 발견해도 기대 상태 조건을 만족한 한 transaction만 `RELEASED`로 변경하고 수량을 반환한다. `PAYMENT_PENDING`은 만료 시각이 지나도 자동 반환하지 않는다.

별도 scheduler가 없으므로 주문 요청이 없는 판매 일정의 만료 예약 행은 `RESERVED` 상태로 남을 수 있다. 이는 이후 주문 요청 또는 Redis snapshot 재생성 시 정리되며, 외부에 노출되는 가용 재고와 초과 판매 방지는 그 시점에 보정된다.

## 결제 상태와 예약 상태의 정합성

판매 일정 잠금을 제거한 뒤 결제 상태의 읽기 후 변경을 그대로 두면 승인, 실패와 재확인 흐름이 서로 덮어쓸 수 있다. 결제와 예약 상태 전이는 현재 상태를 조건으로 하는 repository 연산으로 수행하고 영향받은 행 수로 경쟁 결과를 판정한다.

### 결제 준비

결제 시도 생성 transaction에서 다음을 함께 처리한다.

1. 기존 결제 시도의 멱등 결과를 먼저 확인한다.
2. 예약을 `status = RESERVED AND expires_at > now` 조건으로 `PAYMENT_PENDING`으로 변경한다.
3. 조건이 맞지 않으면 기존 `PaymentErrorCode.ORDER_EXPIRED` (`PAYMENT_ORDER_EXPIRED`) 오류를 반환한다.
4. `Payment.CONFIRMING` 생성과 예약 전이를 함께 commit한다.

### 결제 승인

검증된 `DONE` 결과는 같은 transaction에서 다음 상태 전이를 수행한다.

- `Payment.CONFIRMING → Payment.APPROVED`
- `Order.PENDING_PAYMENT → Order.PAID`
- `InventoryReservation.PAYMENT_PENDING → InventoryReservation.CONFIRMED`

카운터 수량은 유지한다. 기대 상태가 이미 변경된 경우 현재 상태를 다시 조회해 기존 멱등 응답 또는 처리 종료 규칙을 적용한다.

### 결제 실패

확정 실패는 같은 transaction에서 다음을 수행한다.

- `Payment.CONFIRMING → Payment.FAILED`
- `InventoryReservation.PAYMENT_PENDING → InventoryReservation.RELEASED`
- 예약 상태 변경에 성공한 수량만 카운터에서 감소

Redis 재고는 즉시 증가시키지 않는다. 최대 30초의 기존 과소 재고 허용 정책을 유지하고 다음 snapshot에서 DB 가용 재고를 반영한다.

### 불명확한 결제

재확인 대기는 `Payment.CONFIRMING`과 `InventoryReservation.PAYMENT_PENDING`을 유지한다. 수동 확인 전환은 `Payment.REVIEW_REQUIRED`로 변경하되 예약은 `PAYMENT_PENDING`을 유지한다. 결제 claim과 수동 확인 전환도 기대 Payment 상태 및 재확인 시각을 조건으로 갱신해 한 처리자만 상태를 변경하게 한다.

## 계층과 변경 경계

- `sale`: 판매 등록 transaction에서 판매 일정과 재고 카운터를 함께 생성한다. 최초 판매 수량은 계속 `Sale`이 관리한다.
- `order/domain`: `InventoryReservation`과 상태 전이 규칙을 관리한다.
- `order/application`: Redis 선점, 만료 지연 반환, 조건부 재고 확보, 주문 및 예약 생성과 보상을 조율한다.
- `order/infrastructure`: 카운터 조건부 UPDATE, 예약 기대 상태 UPDATE와 snapshot 조회를 구현한다.
- `payment/application`: 결제와 예약 상태 전이 및 재고 반환을 한 transaction으로 조율한다.
- `presentation`: 기존 주문 및 결제 HTTP 계약을 유지하며 재고 카운터와 예약 구현을 알지 않는다.

application은 Spring Data JpaRepository나 native query 구현에 직접 의존하지 않는다. 카운터와 예약의 업무 결과를 표현하는 domain repository 경계를 사용한다. 단일 구현을 위한 불필요한 mapper나 facade는 추가하지 않는다.

판매 등록의 `SaleDay` 잠금, 상품 이미지 처리의 Product 잠금 등 주문 재고와 무관한 비관적 잠금은 변경하지 않는다.

## 오류 처리와 불변식

- 조건부 재고 확보 0건: 기존 `ORDER_QUANTITY_UNAVAILABLE`
- 결제 준비 시 유효한 `RESERVED` 예약 없음: 기존 `PaymentErrorCode.ORDER_EXPIRED` (`PAYMENT_ORDER_EXPIRED`)
- 판매 일정 없음과 판매 기간 오류: 기존 오류 계약 유지
- 판매 일정은 존재하지만 카운터가 없음: 내부 데이터 불변식 위반으로 transaction 실패 및 오류 로그
- 예약 반환 성공 후 카운터 감소 실패: 내부 데이터 불변식 위반으로 transaction rollback 및 오류 로그
- 예약 상태 경쟁: 현재 DB 상태를 재조회해 멱등 응답 또는 기존 업무 오류로 변환
- Redis 장애와 timeout: 기존 DB fail-open 정책 유지

로그에는 `saleId`, `orderId`, `reservationId`와 실패 연산을 남길 수 있지만 배송 정보, 결제 credential과 Redis credential은 포함하지 않는다. 고유 식별자는 metric tag로 사용하지 않는다.

## 검증 전략

- 판매 등록 시 판매 일정과 0인 카운터가 함께 저장되고 실패 시 함께 rollback되는지 확인한다.
- 기존 주문과 결제 조합이 migration에서 올바른 예약 상태와 카운터로 변환되는지 확인한다.
- migration 결과가 최초 판매 수량을 초과하면 실패하는지 확인한다.
- 조건부 재고 확보가 재고 이내에서만 성공하고 0, 경계값과 큰 수량에서도 overflow 없이 동작하는지 확인한다.
- PostgreSQL 동시 요청에서 성공한 예약 수량 합계가 최초 판매 수량을 넘지 않는지 확인한다.
- 주문, 카운터 증가와 예약 생성 중 하나가 실패하면 모두 rollback되는지 확인한다.
- 멱등 요청과 unique 충돌 복구가 카운터를 중복 증가시키지 않는지 확인한다.
- 만료된 `RESERVED`만 정확히 한 번 반환하고 `PAYMENT_PENDING`은 만료 시각 이후에도 유지하는지 확인한다.
- 결제 시작과 만료 반환이 경쟁할 때 하나의 상태 전이만 성공하는지 확인한다.
- 결제 승인과 실패가 경쟁해도 예약 확정과 반환이 동시에 발생하지 않는지 확인한다.
- 결제 실패 재처리가 카운터를 중복 감소시키지 않는지 확인한다.
- Redis snapshot이 만료 반환 후 `sales.quantity - committed_quantity`를 사용해 초기화되는지 확인한다.
- Redis 선점 성공 뒤 DB 품절이면 generation을 폐기하고, 다른 DB 실패이면 예약을 복원하는지 확인한다.
- Redis 비활성화와 장애 fallback에서도 DB 조건부 UPDATE가 초과 판매를 방지하는지 확인한다.
- 주문 및 결제 경로가 `SaleRepository.findByIdForUpdate`와 `OrderRepository.sumCommittedQuantity`에 의존하지 않는지 확인한다.
- 기존 주문·결제 HTTP와 REST Docs 테스트가 변경 없이 통과하는지 확인한다.

PostgreSQL 고유 UPDATE 동작과 동시성을 검증하는 persistence 및 통합 테스트는 PostgreSQL Testcontainers를 사용한다. Redis 조율 통합 테스트는 PostgreSQL과 Redis Testcontainers를 함께 사용한다.

## 트레이드오프와 허용하는 불일치

카운터 행의 조건부 UPDATE도 같은 판매 일정에 대해서는 DB 내부에서 짧게 직렬화된다. 다만 application이 판매 일정 잠금을 보유한 채 주문·결제 집계와 검증을 수행하지 않으므로 임계 구역과 조회 비용이 줄어든다.

카운터와 예약 행은 같은 재고 점유 사실을 집계와 상세 형태로 중복 저장한다. 모든 생성·반환을 같은 DB transaction으로 묶고 기대 상태 조건을 사용해 둘의 일치를 유지한다. 이번 범위에는 별도 reconciliation을 두지 않으므로 불변식 위반은 자동 보정하지 않고 transaction을 실패시켜 관찰 가능하게 만든다.

별도 만료 scheduler와 Redis 즉시 복원을 두지 않으므로 품절 Redis cache가 살아 있는 동안 반환된 DB 재고가 최대 30초 늦게 노출될 수 있다. 이는 판매 기회 손실을 만들 수 있지만 과대 재고와 초과 판매는 DB 조건부 UPDATE가 차단한다.
