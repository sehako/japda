# 재고 관리 단순화 구현 계획

## 목적

백엔드의 재고 점유 원천을 판매 일정별 `sale_inventory_counters`와 주문별 `inventory_reservations`의 조합에서 `sales.committed_quantity`와 `orders` 상태의 조합으로 단순화한다. 주문·결제·만료 흐름이 같은 PostgreSQL transaction에서 `sales.committed_quantity`와 주문 상태를 일관되게 변경하도록 하여, 현재 재고 점유 수량과 주문 생명주기를 하나의 모델로 관리한다.

이 문서는 구현을 위한 ExecPlan이다. 계획 작성 중에는 코드, migration, 테스트 또는 실행 설정을 수정하지 않는다.

## 완료 조건

- `sales.committed_quantity`가 `NOT NULL DEFAULT 0`과 `0 <= committed_quantity <= quantity` 제약을 가진다.
- `inventory_reservations`와 `sale_inventory_counters`가 제거되고 백엔드 런타임 코드가 두 테이블을 참조하지 않는다.
- 개발용 기존 `orders`, `payments` 및 재고 관련 데이터가 새 모델과 충돌하지 않도록 migration에서 폐기된다.
- 주문 생성은 `sales.committed_quantity`를 요청 수량만큼 조건부 증가시키는 데 성공한 경우에만 주문을 저장한다.
- 주문 생성·만료·명시적 결제 실패·결제 승인 흐름에서 상태와 수량 변경이 같은 transaction으로 처리된다.
- 같은 판매 일정에 대한 동시 주문에서도 `committed_quantity`가 `sales.quantity`를 초과하지 않는다.
- 주문 상태가 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`로 제한된다.
- `PAYMENT_PROCESSING`은 주문 상태로 사용하지 않으며 결제 진행은 기존 `PaymentStatus`가 담당한다.
- `Payment.CONFIRMING` 및 `Payment.REVIEW_REQUIRED` 주문은 주문의 원래 3분 `expires_at`이 지나도 재고를 점유한다.
- 결제 시도가 없는 만료 주문은 30초 주기의 scheduler가 `EXPIRED`로 변경하고 수량을 반환한다.
- 결제의 명시적 실패가 확정되면 주문을 `PAYMENT_FAILED`로 변경하고 수량을 정확히 한 번 반환한다.
- 승인 완료는 수량을 감소시키지 않고 주문을 `PAID`로 변경한다.
- `EXPIRED`, `PAYMENT_FAILED` 주문은 같은 주문·멱등성 키로 재결제할 수 없다.
- 구매자 주문 조회 API가 `EXPIRED`, `PAYMENT_FAILED` 상태를 반환한다.
- 기존 PG reconcile의 조회 범위·주기·15분 `REVIEW_REQUIRED` 정책과 PG 취소·환불 범위는 변경하지 않는다.
- 관련 domain, persistence, scheduler, migration, 통합 테스트와 API 계약 검증이 통과한다.

## 배경과 현재 구조

현재 구현은 다음 두 테이블을 함께 사용한다.

- `sale_inventory_counters`: `Sale` 등록 시 생성하고, 주문 생성·결제 실패·만료 처리에서 `committed_quantity`를 증감한다.
- `inventory_reservations`: 주문별 예약 상태를 `RESERVED`, `PAYMENT_PENDING`, `CONFIRMED`, `RELEASED`로 관리한다.

주요 진입점은 다음과 같다.

- `apps/backend/src/main/kotlin/io/github/sehako/japda/order/application/service/OrderCreationTransactionService.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/order/application/inventory/ExpiredInventoryReservationReleaseService.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/order/infrastructure/persistence/SaleInventoryCounterRepositoryImpl.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/order/infrastructure/persistence/InventoryReservationRepositoryImpl.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/sale/application/service/SaleRegistrationTransactionService.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/payment/application/service/PaymentTransactionService.kt`
- `apps/backend/src/main/kotlin/io/github/sehako/japda/payment/application/service/PaymentService.kt`

현재 주문 만료 시간은 `Order.RESERVATION_DURATION`으로 생성 후 3분이다. 결제 reconcile은 `CONFIRMING` 결제를 30초 주기로 최대 100건씩 조회하고 최초 요청 후 15분이 지나면 `REVIEW_REQUIRED`로 남긴다. 그러므로 만료 scheduler는 연결된 결제 시도가 없는 주문만 반환해야 하며, 결제 진행 또는 수동 확인 주문의 수량은 유지해야 한다.

## 확정된 범위와 정책

### 데이터 모델

`sales`에 다음 컬럼과 제약을 추가한다.

```sql
committed_quantity INTEGER NOT NULL DEFAULT 0
CHECK (committed_quantity >= 0 AND committed_quantity <= quantity)
```

`committed_quantity`는 선점·결정된 판매 수량이며, `sales.quantity - sales.committed_quantity`가 가용 수량이다. `inventory_reservations`와 `sale_inventory_counters`는 제거한다.

개발 데이터베이스 전환이므로 기존 주문·결제·재고 데이터를 백필하지 않는다. `settlement_details`와 `settlement_entries`처럼 `orders`·`payments`를 참조하는 개발 데이터도 FK를 위반하지 않는 순서로 정리해야 한다.

### 주문 상태

`orders.status`는 다음 네 값만 허용한다.

```text
PENDING_PAYMENT
PAID
EXPIRED
PAYMENT_FAILED
```

결제 진행 상태는 `payments.status`의 `CONFIRMING`, `APPROVED`, `FAILED`, `REVIEW_REQUIRED`로 관리한다. 결제 진행 중이거나 결과가 불명확한 주문은 주문 상태를 `PENDING_PAYMENT`로 유지한다.

### 수량 변경 규칙

- 주문 생성: `sales` 조건부 UPDATE로 수량을 증가시키고, 성공한 경우에만 `PENDING_PAYMENT` 주문을 저장한다.
- 결제 승인: 주문을 `PENDING_PAYMENT → PAID`로 전환하며 수량은 유지한다.
- 명시적 결제 실패: `PENDING_PAYMENT → PAYMENT_FAILED`로 조건부 전환하고, 전환된 경우에만 수량을 감소시킨다.
- 결제 시도가 없는 주문 만료: `PENDING_PAYMENT → EXPIRED`로 조건부 전환하고, 전환된 경우에만 수량을 감소시킨다.
- 이미 종료된 주문의 재결제는 허용하지 않고 새 주문과 새 멱등성 키를 요구한다.

모든 상태 전환과 수량 증감은 하나의 transaction에서 수행한다. 조건부 UPDATE의 영향받은 행 수를 멱등성·경합 판정에 사용하며, 실패한 반환 UPDATE는 이미 다른 흐름이 처리했는지 확인할 수 있어야 한다.

### 만료 scheduler

기존 payment reconcile과 별도의 scheduler를 추가한다.

- 실행 주기: 30초
- 대상: `PENDING_PAYMENT`, `expires_at <= now`, 연결된 결제 시도가 없는 주문
- 처리: 주문 상태를 조건부로 `EXPIRED`로 전환하고 해당 주문 수량만 `sales.committed_quantity`에서 감소
- 결제 시도가 이미 생성된 주문은 `CONFIRMING`, `REVIEW_REQUIRED`, `FAILED`, `APPROVED` 상태를 확인해 기존 결제 흐름과 충돌하지 않게 한다.
- scheduler transaction 실패 시 다음 주기에 다시 처리할 수 있도록 조건부 전환을 멱등적으로 구현한다.

만료 전 시작한 결제의 PG 호출을 강제로 중단하거나 PG 취소·환불 API를 추가하지 않는다. 만료 후 새 결제 시도는 기존 `prepare()`의 만료 검증으로 차단한다.

## 구현 접근

### 1. 아키텍처 기록과 문서 기준 갱신

기존 ADR-028의 “카운터와 예약 테이블” 결정을 대체하는 새 backend ADR을 구현 전에 작성한다. ADR에는 `sales.committed_quantity` 단일 카운터, 주문 상태 기반 반환, 결제 진행 중 수량 유지, 30초 만료 scheduler, 개발 데이터 폐기와 그 트레이드오프를 기록한다. `docs/architecture/decisions/README.md`의 목록을 갱신하고, 승인된 결정이 기존 backend 규칙을 바꾸므로 `docs/architecture/backend.md`의 재고 규칙도 갱신한다.

기존 `docs/specs/backend/order/database-inventory-reservation.md`, `buyer-order-creation-api.md`, `docs/specs/backend/payment/buyer-payment-confirmation-api.md`에서 제거된 예약 테이블·카운터와 변경된 주문 상태·만료 정책을 새 설계와 일치시킨다. 기존 ADR-028과 관련 문서는 삭제하지 않고 대체 관계를 남긴다.

### 2. Flyway migration과 개발 데이터 초기화

현재 V15가 생성하는 두 테이블을 제거하는 후속 migration을 추가한다. `sales.committed_quantity`와 범위 제약을 추가하고 `orders_status_valid`를 네 가지 상태 제약으로 교체한다.

migration은 개발 데이터 폐기 정책을 지켜야 한다. `orders`·`payments`를 삭제하기 전에 `settlement_details`, `settlement_entries` 등 관련 FK 자식 데이터를 정리하고, 그 뒤 재고 테이블을 제거한다. `sales`의 기존 행은 새 컬럼 기본값 0으로 시작한다. migration 테스트는 컬럼, 제약, 상태 허용값, 제거된 테이블, 개발 데이터 정리 결과를 검증한다.

### 3. 주문·판매 등록 영속성 변경

판매 등록에서 `SaleInventoryCounterRepository.create()` 호출을 제거한다. 주문 생성 경로는 `SaleInventoryCounterRepository` 대신 판매 일정 ID·요청 수량을 받아 `sales.committed_quantity`를 조건부 증가시키는 repository 경계를 사용한다. 저장 순서는 멱등성 조회, 재고 확보, 주문 저장을 동일 transaction에서 처리하고 주문 저장 실패 시 수량 변경도 rollback되도록 한다.

`Sales` row의 범위 제약과 조건부 증가 조건을 함께 사용해 동시 요청의 초과 판매를 방지한다. 누락된 판매 일정과 수량 부족을 기존 주문 오류 계약에 맞게 구분한다.

### 4. 주문 상태와 만료 처리

`OrderStatus`와 `Order`의 전이 메서드를 네 상태에 맞게 확장한다. 주문 조회 projection과 response는 새 상태를 직렬화한다. 주문 생성 시 기존 3분 만료 시각은 유지한다.

`ExpiredInventoryReservationReleaseService`와 예약 Repository를 제거하거나 주문 만료 전용 application/infrastructure 경계로 대체한다. 새 scheduler는 만료 대상 주문을 조회하고, 결제 시도가 없는 주문을 조건부 `EXPIRED`로 전환한 뒤 판매 수량을 반환한다. 처리량이 많은 경우에도 한 판매 일정의 상태·수량 변경 순서를 일관되게 유지해 scheduler와 신규 주문의 교착·중복 반환을 방지한다.

### 5. 결제 승인·실패 연계

`PaymentTransactionService`에서 예약 Repository와 카운터 Repository 의존성을 제거한다.

- `prepare()`는 주문의 `PENDING_PAYMENT`와 `expiresAt`을 확인하고 결제 시도를 생성한다.
- `approve()`는 기존 정산 원천 생성 transaction에서 주문을 `PAID`로 조건부 전환하되 수량을 감소시키지 않는다.
- `fail()`은 PG 실패 확정 시 주문을 `PAYMENT_FAILED`로 조건부 전환하고 성공한 경우에만 판매 수량을 반환한다.
- `CONFIRMING`·`REVIEW_REQUIRED`의 기존 reconcile 범위와 15분 정책은 유지한다.

승인과 만료 scheduler가 경쟁할 때는 결제·주문 상태를 조건부로 확인해 이미 승인된 주문을 만료시키지 않도록 한다. 외부 PG 호출은 DB transaction 밖에 유지한다.

### 6. 테스트와 계약 갱신

기존 예약·카운터 Repository 테스트는 새 판매 재고 Repository와 주문 만료 scheduler 테스트로 대체한다. 주문 생성, 판매 등록, 결제 승인·실패, reconcile, 주문 조회 통합 테스트에서 다음을 검증한다.

- 동시 주문의 총 점유 수량이 판매 수량을 넘지 않음
- 주문 생성 실패 시 `committed_quantity` rollback
- 승인 시 수량 유지 및 `PAID` 전환
- 명시적 실패 시 한 번만 `PAYMENT_FAILED` 전환 및 수량 반환
- 결제 시도 없는 만료 주문의 scheduler 반환
- `CONFIRMING`·`REVIEW_REQUIRED` 주문의 만료 후 점유 유지
- scheduler 중복 실행의 멱등성
- `EXPIRED`·`PAYMENT_FAILED` 조회 응답과 종료 주문 재결제 거절
- migration에서 새 제약과 제거 대상 테이블 검증

## 인터페이스와 의존성 영향

- `OrderStatus` 공개 상태 계약이 `EXPIRED`, `PAYMENT_FAILED`를 포함하도록 확장된다.
- 주문 조회 API 응답이 새 상태를 반환할 수 있다.
- 내부 order application은 재고 예약 Entity·Repository 대신 판매 수량 변경과 만료 처리 계약을 사용한다.
- payment application은 결제 결과와 주문 상태·판매 수량 반환을 조정하지만 `Payment`가 order domain Entity에 직접 의존하지 않는 기존 경계를 유지한다.
- 새로운 외부 dependency는 추가하지 않는다.
- PG 취소·환불 API, 프런트엔드 변경, 배치 재고 기능은 범위에 포함하지 않는다.

## 검증 계획

작업 디렉터리는 `/home/sehako/workspace/japda/apps/backend`로 한다.

1. 전체 백엔드 테스트: `./gradlew test`
2. 주문·재고 관련 테스트: `./gradlew test --tests '*order*' --tests '*sale*'`
3. 결제 관련 테스트: `./gradlew test --tests '*payment*'`
4. migration·persistence 관련 테스트: `./gradlew test --tests '*MigrationTest' --tests '*RepositoryTest'`
5. 빌드 및 REST Docs 검증: `./gradlew build`

성공 기준은 각 명령이 0으로 종료되고, 새 migration이 Testcontainers PostgreSQL에서 적용되며, 재고 수량·주문 상태·결제 상태의 통합 시나리오가 모두 통과하는 것이다. 실행하지 못한 명령이나 실패한 검증은 구현 완료 기록에 원인과 함께 남긴다.

## 위험과 복구

- 개발 데이터 폐기는 복구 불가능하다. migration 적용 전 대상 환경이 개발용인지 확인하고, 운영 데이터베이스에는 동일 migration을 적용하지 않는다.
- `settlement_details`·`settlement_entries`를 잘못 정리하면 정산 테스트 데이터가 함께 사라질 수 있으므로 FK 의존성과 정리 순서를 migration 테스트로 검증한다.
- `committed_quantity`와 주문 상태의 변경 순서가 흐름마다 다르면 교착 또는 중복 반환이 발생할 수 있다. 모든 경로에서 조건부 전환과 단일 transaction 원칙을 유지한다.
- scheduler가 결제 진행 주문을 만료시키면 PG 승인과 주문 상태가 충돌한다. 결제 시도 상태를 확인하는 대상 조건과 경쟁 테스트를 필수 검증한다.
- 새 주문 상태를 반환하면 현재 프런트엔드의 상태 허용 목록과 불일치할 수 있다. 이번 계획은 프런트엔드 변경을 포함하지 않으므로 API 소비자 영향은 별도 작업으로 기록한다.

## 진행 상황

- [x] 기존 재고·주문·결제·scheduler 구조 조사
- [x] 재고 단순화 Decision Brief 확정
- [x] 백엔드 전용 범위와 상태·만료·수량 정책 확정
- [x] 구현 계획과 검증 범위 작성
- [ ] 대체 ADR 작성 및 승인
- [ ] `docs/architecture/backend.md`와 관련 spec 갱신
- [ ] Flyway migration 및 개발 데이터 정리 구현
- [ ] 주문·판매·결제 runtime 변경
- [ ] 만료 scheduler 구현
- [ ] 테스트·REST Docs·build 검증

## 발견 사항

- 기존 V15 migration은 `sale_inventory_counters`와 `inventory_reservations`를 함께 생성하고 기존 주문·결제 상태에서 예약 데이터를 backfill한다.
- 현재 판매 등록은 `SaleInventoryCounterRepository.create()`를 호출하므로 테이블 제거만으로는 동작하지 않는다.
- 현재 결제 실패는 예약 상태와 카운터를 함께 반환하고 주문 상태는 `PENDING_PAYMENT`에 남긴다. 새 정책에서는 주문을 `PAYMENT_FAILED`로 함께 전환해야 한다.
- 현재 payment reconcile은 이미 구현되어 있지만 `CONFIRMING`만 조회하며, 15분 후 `REVIEW_REQUIRED`로 남긴다. 이번 계획에서는 이 범위를 확장하지 않는다.
- 주문 만료 기간은 3분이고 reconcile 최대 기간은 15분이므로 결제 시도가 없는 주문과 결제 진행 중인 주문을 scheduler가 구분해야 한다.
- `orders`·`payments`를 참조하는 정산 자식 테이블이 있어 개발 데이터 초기화는 단순한 두 테이블 삭제로 끝나지 않는다.

## 결정 로그

| 결정 | 근거 |
| --- | --- |
| 재고 점유 원천을 `sales.committed_quantity`로 단일화 | 예약·카운터 두 테이블의 중복 모델을 제거하고 판매 일정의 범위 제약으로 불변식을 표현하기 위해서다. |
| 기존 개발 주문·결제·재고 데이터 폐기 | 현재 데이터베이스는 개발용이며 기존 카운터 백필보다 새 모델의 초기 상태를 단순하게 유지하기 위해서다. |
| 주문 상태에서 `PAYMENT_PROCESSING` 제거 | 결제 진행 상태는 이미 `PaymentStatus`가 관리하고 주문 상태에 중복 저장할 필요가 없기 때문이다. |
| `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED` 공개 | 주문 생명주기와 주문 조회 API의 종료 상태를 표현하기 위해서다. |
| 결제 진행·수동 확인 주문의 재고 유지 | PG 결과 불명확 상태에서 재고를 반환하면 늦은 승인과 재고 확정이 분리될 수 있기 때문이다. |
| 결제 시도 없는 만료 주문에 30초 scheduler 적용 | 요청이 추가로 오지 않아도 만료 재고를 자동 반환하기 위해서다. 기존 reconcile과는 별도 책임이다. |
| PG 취소·환불 연동 제외 | 포트폴리오 범위에서 결제 결과 정리보다 재고 저장 모델 단순화를 우선하기 위해서다. |
| 관련 상태·수량 변경을 조건부 UPDATE와 단일 transaction으로 처리 | 중복 반환·중복 승인·동시 주문 초과 판매를 방지하기 위해서다. |

## 미해결 질문

제품·도메인·범위·완료 조건에 관한 blocking question은 없다. 다만 대체 ADR 승인 전에는 구현을 시작하지 않는다.

## 결과 및 회고

구현 후 실제 변경 파일, 실행한 검증 명령, 실패·미실행 검증, migration 적용 결과와 남은 운영 리스크를 이 절에 추가한다. 특히 개발 데이터 폐기 migration이 의도한 환경에만 적용되었는지와 scheduler·결제 경합 테스트 결과를 기록한다.
