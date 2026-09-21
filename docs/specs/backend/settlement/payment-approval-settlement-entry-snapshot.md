# 결제 승인 시 정산 원천 스냅샷 적재

## 목적과 성공 기준

결제 승인과 정산 원천 데이터 적재를 하나의 PostgreSQL transaction으로 처리한다. 일일 정산 배치는 결제·주문·판매·판매자 사용자 연결을 다시 조인해 구매별 정산 상세를 생성하지 않고, 승인 시점에 고정한 불변 `settlement_entries`를 정산 근거로 사용한다.

이 설계는 결제별 추적 가능성, 판매자별 검산, Spring Batch 재시작과 지갑 원장 멱등성을 유지하면서 1억 건 정산의 반복 조회와 쓰기를 줄이는 것을 목적으로 한다. 다음 조건을 모두 만족하면 완료된 것으로 본다.

- 결제의 로컬 승인, 주문 결제 완료, 재고 예약 확정과 정산 원천 생성이 같은 transaction에서 commit 또는 rollback된다.
- 신규 `APPROVED` 결제마다 내용이 일치하는 `settlement_entries` 행이 정확히 하나 존재한다.
- 결제 승인 재호출과 reconcile 재실행이 정산 원천을 중복 생성하지 않는다.
- 정산 원천은 승인 당시 판매자, 지급 대상 사용자, 수량, 단가, 금액, 승인 시각과 정산일을 불변 스냅샷으로 보존한다.
- 일일 정산 배치는 `settlement_entries`만으로 기존 판매자별 정산과 지갑 입금 결과를 만든다.
- 같은 JobInstance 재시작에서 최초에 고정한 대상 상한을 재사용하고 누락이나 중복 없이 완료한다.
- 기준 실행 환경과 자원 설정을 기록한 1억 건 성능 시나리오에서 전체 Job이 30분 이내에 완료된다.
- 취소·환불·부분 환불과 기존 승인 결제의 운영 backfill은 이번 범위에 포함하지 않는다.

## 기존 결정과 대체 범위

다음 결정은 유지한다.

- [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 API·배치·원장 모듈과 migration 소유권
- [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 결제별 근거, 판매자별 집계, 사용자 지갑과 원장 불변식
- [ADR-028](../../../architecture/decisions/ADR-028-database-inventory-counter-and-order-reservation.md)의 결제 승인·주문·재고 예약 상태 전이
- [ADR-030](../../../architecture/decisions/ADR-030-daily-seller-settlement-local-partitioning.md)의 결정론적 파티션 계획, worker별 checkpoint와 지갑 입금 재시작 계약

[ADR-035](../../../architecture/decisions/ADR-035-payment-approval-settlement-entry-snapshot.md)는 ADR-025의 결제별 근거 생성 시점과 ADR-030의 collection 입력을 변경한다. 결제별 근거를 일일 배치가 생성하던 방식은 결제 승인 transaction에서 생성하는 방식으로 대체한다. 판매자별 확정과 지갑 입금 계약은 유지한다.

다음 기존 명세는 구현 당시의 설계 기록으로 보존하되, 원본 결제 조인과 `settlement_details` 생성에 관한 부분은 이 명세가 대체한다.

- [대상 수집 단계](../batch/daily-seller-settlement-job-collection-stage.md)
- [대용량 성능 최적화](../batch/daily-seller-settlement-job-large-scale-performance-optimization.md)
- [파티션 병렬 처리](../batch/daily-seller-settlement-job-partitioned-processing.md)

## 선택한 구조

### 결제 승인 흐름

PG 승인 HTTP 호출은 기존처럼 DB transaction 밖에서 수행한다. PG가 성공을 반환한 뒤 `PaymentTransactionService.apply`가 시작하는 단일 transaction에서 다음 순서로 로컬 상태를 확정한다.

```text
Payment CONFIRMING → APPROVED
→ Order PAYMENT_PENDING → PAID
→ InventoryReservation PAYMENT_PENDING → CONFIRMED
→ SettlementEntry INSERT
→ commit
```

정산 원천 생성까지 성공하기 전에 API가 로컬 승인 성공을 반환하지 않는다. 어느 작업이든 실패하면 transaction 전체를 rollback한다. PG는 성공했지만 로컬 transaction이 실패하면 `Payment`는 재처리 가능한 `CONFIRMING` 상태로 남고 기존 reconcile 흐름이 PG 상태를 확인한 뒤 같은 `apply`를 다시 실행한다.

### 일일 정산 흐름

```text
정산 실행 준비와 대상 상한 고정
→ settlement_entries 기반 collection 또는 집계
→ 수집 건수·금액 검산
→ 판매자별 정산 확정
→ 판매자 지갑 입금
→ 원장·지갑 최종 검산
```

배치는 더 이상 `payments → orders → sales → seller_principal_identities`를 조인해 결제별 정산 근거를 만들지 않는다. Spring Batch는 정산 실행과 파티션·재시작을 조율하며, 이미 적재된 정산 원천을 읽어 판매자별 결과를 만든다.

## 데이터 모델

API root project가 다음 구조의 `settlement_entries` migration을 소유한다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `payment_id` | `BIGINT` | not null, `payments.id` foreign key, unique |
| `order_id` | `BIGINT` | not null, `orders.id` foreign key |
| `sale_id` | `BIGINT` | not null, `sales.id` foreign key |
| `seller_id` | `BIGINT` | not null, 양수 |
| `recipient_user_id` | `BIGINT` | not null, `users.id` foreign key |
| `quantity` | `INTEGER` | not null, 양수 |
| `unit_price` | `BIGINT` | not null, 양수 |
| `gross_amount` | `BIGINT` | not null, 양수 |
| `payment_approved_at` | `TIMESTAMP WITH TIME ZONE` | not null |
| `settlement_date` | `DATE` | not null |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |

`gross_amount`는 주문의 구매 시점 스냅샷인 `total_price`이며 `quantity × unit_price`와 같아야 한다. `settlement_date`는 `payment_approved_at`을 고정된 `Asia/Seoul` 시간대로 변환한 날짜다. 이후 시간대 정책이나 판매자 사용자 연결이 변경돼도 저장된 정산 귀속은 바꾸지 않는다.

`settlement_entries`는 생성 후 수정하거나 삭제하지 않는다. 승인 결제만 다루는 현재 범위에서 미래의 환불 모델을 위한 `source_type`, 상태 또는 nullable 확장 컬럼을 미리 추가하지 않는다.

다음 접근 경로를 지원한다.

- `payment_id` unique 제약: 승인 재호출과 동시 처리의 최종 중복 방어선
- `(settlement_date, id)` 인덱스: 일일 정산의 범위 조회와 keyset paging
- `(settlement_date, seller_id)` 인덱스는 판매자별 집계 실행계획이 쓰기 비용을 상쇄할 때만 추가

새 인덱스의 필요성은 대표 데이터에서 `EXPLAIN (ANALYZE, BUFFERS, WAL)`로 검증하며 추측만으로 covering index를 추가하지 않는다.

## 승인 시점 스냅샷 생성

### 입력과 검증

승인 transaction은 다음 값을 사용한다.

- `Payment`: `id`, `requestedAmount`, `approvedAt`
- `Order`: `id`, `saleId`, `quantity`, `unitPrice`, `totalPrice`
- `Sale`: `sellerId`
- `seller_principal_identities`: `sellerId`에 연결된 `userId`

다음 조건을 모두 검증한다.

- 주문과 판매가 존재한다.
- 결제 요청 금액과 주문 총액이 같다.
- 주문 총액이 수량과 단가의 곱과 같다.
- 판매자와 지급 대상 사용자가 각각 존재한다.
- 한 판매자에는 하나의 지급 대상 사용자만 연결된다.
- PG 승인 시각과 서울 기준 정산일을 계산할 수 있다.

판매자 사용자 연결은 결제 승인 전에 존재해야 하는 업무 불변식으로 둔다. 영구적으로 누락된 연결은 재시도로 복구할 수 없으므로 결제 로컬 확정을 실패시키고 운영 경고 대상으로 노출한다. 정산 원천 없이 `Payment`만 `APPROVED`로 강제 보정하지 않는다.

### 멱등성과 동시성

조건부 상태 전이에 성공한 승인 요청이 정산 원천을 생성한다. 이미 `APPROVED`인 결제의 재호출은 기존 성공 응답을 반환하기 전에 같은 `payment_id`의 정산 원천이 존재하는지 확인한다.

같은 `payment_id`의 행이 이미 존재하면 저장된 `order_id`, `sale_id`, `seller_id`, `recipient_user_id`, 수량, 단가, 금액, 승인 시각과 정산일이 새로 계산한 값과 모두 같을 때만 멱등 성공으로 처리한다. 값이 다르면 `ON CONFLICT DO NOTHING`으로 숨기지 않고 정합성 오류로 실패한다.

동시 승인 요청의 패자는 승자의 transaction이 완료된 뒤 `APPROVED` 결제와 정산 원천을 함께 확인하여 같은 성공 결과를 반환한다.

### 기존 승인 데이터 전환

신규 불변식은 cutover 이후 승인되는 결제부터 적용한다. 구현 배포 전에 존재하는 승인 결제를 운영 중 자동으로 backfill하지 않는다. 운영 전환이 필요하면 다음 작업을 별도 계획으로 수행한다.

1. cutover 상한을 고정한다.
2. 기존 승인 결제를 현재 데이터로 검산한다.
3. 정산되지 않은 결제만 `settlement_entries`에 적재한다.
4. 결제 수와 금액 합계 및 중복 부재를 확인한다.
5. 신규 승인 동시 적재 기능을 활성화한다.

현재 포트폴리오 성능 환경에서는 각 iteration의 합성 데이터 생성기가 결제와 정산 원천을 함께 준비하므로 운영 backfill을 실행하지 않는다.

## 배치 처리와 재시작

### 대상 경계

준비 Step은 `settlement_date`로 대상 `settlement_entries.id`의 최소값과 최대값을 조회하고 Job `ExecutionContext`에 고정한다. 같은 JobInstance 재시작에서는 저장한 경계를 사용하며 현재 테이블 상태나 변경된 파티션 설정으로 다시 계산하지 않는다.

정산 실행 중 과거 날짜의 entry가 늦게 생성돼 최초 상한 밖에 놓이면 현재 실행에 끼워 넣지 않는다. 해당 결제는 대사에서 탐지하고 후속 정산 정책으로 처리한다. 늦은 승인과 보정 정산 자체는 이번 범위에서 구현하지 않는다.

### Collection과 확정

첫 구현은 기존 local partitioning과 worker별 ID keyset paging을 유지하되 입력을 `settlement_entries`로 변경한다. reader는 자신의 상호 배타적인 ID 범위와 정산일 안에서 필요한 snapshot을 읽고 전체 원본 관계를 다시 조회하지 않는다.

기존 `settlement_details`에 같은 내용을 다시 복제하지 않는다. `settlement_entries`가 결제별 불변 정산 근거를 담당하고, `settlement_runs`는 정산일별 실행과 수수료율, 집계 상태를 담당한다. 판매자별 확정은 대상 entry의 건수와 총액을 검산한 뒤 `seller_settlements`를 생성한다.

1억 건 기준에서 단일 판매자별 집계가 단계 예산을 넘으면 파티션별 영구 부분 집계와 최종 merge를 후속 설계로 추가한다. 첫 구현에서 측정 없이 부분 집계 테이블을 도입하지 않는다.

### 지갑 입금

현재 판매자별 정산 단위의 입금 계약을 유지한다. 개별 판매자 정산과 지갑을 잠그고 잔액 증가, 불변 원장 생성과 `CREDITED` 전이를 같은 transaction에서 처리한다. 원장 `(source_type, source_id)` unique 제약과 모든 worker 완료 뒤 전체 검산을 유지한다.

지갑 입금 단계가 1억 건 시나리오의 예산을 넘을 때만 chunk 단위 집합 처리를 별도 설계한다. 이번 설계에서 원장 transaction 경계를 변경하지 않는다.

## 검산과 오류 처리

결제 승인 경로는 다음 불변식을 만족해야 한다.

```text
신규 APPROVED Payment 수 = 신규 SettlementEntry 수
Payment.requestedAmount = SettlementEntry.grossAmount
Payment.approvedAt = SettlementEntry.paymentApprovedAt
```

일일 배치는 단계별로 다음을 검산한다.

```text
대상 SettlementEntry 건수·총액
= SettlementRun 수집 건수·총액

SellerSettlement 상세 건수·총액 합계
= SettlementEntry 건수·총액

CREDITED SellerSettlement 순액 합계
= SELLER_SETTLEMENT Ledger 입금액 합계
```

다음 오류는 자동 보정하거나 skip하지 않는다.

- 지급 대상 사용자 또는 판매 관계 누락
- 주문과 결제 금액 불일치
- 동일 결제의 서로 다른 정산 snapshot
- 승인 결제와 정산 원천의 누락 또는 금액 불일치
- 저장된 파티션 경계와 cursor 손상
- 판매자별 집계와 수집 결과 불일치
- 기존 원장과 입금 대상·금액 불일치

DB connection 단절이나 lock timeout 같은 일시적 오류는 transaction rollback 후 기존 결제 reconcile 또는 Spring Batch 재시작으로 복구한다. 자동 retry는 안전한 대상과 횟수를 별도 측정해 승인하기 전에는 추가하지 않는다.

## 성능 목표와 측정

1억 건 30분은 기준 환경이 명시된 성능 목표이며 모든 운영 환경의 보장이 아니다. 성능 보고서에는 CPU, 메모리, storage 종류와 여유 공간, PostgreSQL 버전과 주요 설정, JVM 설정, worker·partition·connection 수를 기록한다.

단계별 상한은 다음과 같다.

| 단계 | 상한 |
| --- | ---: |
| 정산 실행과 대상 준비 | 1분 |
| entry 조회·수집 검산·판매자별 확정 | 20분 |
| 지갑 입금과 원장 생성 | 7분 |
| 최종 검산과 여유 | 2분 |
| 전체 | 30분 |

1M, 10M, 30M, 100M 순서로 실행하고 각 단계에서 다음을 기록한다.

- 전체와 Step·파티션별 시간 및 처리량
- PostgreSQL CPU, buffer read/hit, storage I/O, WAL과 checkpoint
- temp file과 aggregate spill
- lock wait와 deadlock
- Hikari active·idle·pending
- executor active worker와 queue
- JVM heap, GC pause와 thread 수
- entry, 판매자별 정산, 지갑과 원장의 건수·금액 검산 결과

1억 건에서 30분을 넘으면 worker 수를 근거 없이 늘리지 않는다. 실행계획과 포화 자원을 근거로 부분 집계, 지갑 집합 처리 또는 정산 workload 격리를 별도 결정한다.

## 검증 전략

### 결제 승인 통합 테스트

1. 정상 승인 시 결제, 주문, 재고 예약과 정산 원천이 함께 commit된다.
2. 정산 원천 저장 실패 시 모든 로컬 상태 변경이 rollback된다.
3. PG 성공 후 로컬 실패를 reconcile하면 정산 원천까지 복구된다.
4. 동일 승인 API 재호출은 정산 원천을 중복 생성하지 않는다.
5. 동시 승인 요청에서도 한 결제에 정산 원천이 하나만 존재한다.
6. 기존 행과 새 snapshot이 다르면 정합성 오류로 실패한다.
7. 판매자 사용자 연결이나 금액 검증 실패가 로컬 승인을 실패시킨다.
8. 서울 날짜 경계 전후의 승인 시각을 올바른 정산일로 저장한다.

### 배치 통합 테스트

1. 배치가 원본 결제·주문·판매 조인 없이 해당 날짜 entry만 읽는다.
2. 저장된 최소·최대 ID와 파티션 계획을 재시작에서 그대로 복원한다.
3. 파티션 경계의 entry가 정확히 한 worker에 포함된다.
4. 완료 worker는 재시작하지 않고 실패 worker만 checkpoint부터 재개한다.
5. 대상 건수와 금액 불일치 시 판매자별 확정으로 진행하지 않는다.
6. 기존과 동일한 수수료, 판매자별 정산, 지갑과 원장 결과를 만든다.
7. 대상이 없는 정산일도 0건·0원으로 완료한다.

### 회귀와 성능 테스트

- 결제 승인 API의 기존 성공·거절·중복 요청·reconcile 시나리오를 모두 실행한다.
- `:batch` 단위·통합 테스트와 backend 전체 build를 실행한다.
- 일반 `test`와 `build`는 대용량 성능 테스트를 자동 실행하지 않는다.
- 1억 건 성능 테스트는 디스크 사전 검사와 명시적 실행 설정이 있을 때만 수행한다.

## 예상 변경 범위

- 결제 승인 transaction 조율과 멱등 결과 확인
- 정산 entry 도메인 모델, repository 계약과 영속성 구현
- seller ID로 지급 대상 user ID를 조회하는 명시적 계약
- `settlement_entries` Flyway migration
- 결제 승인·동시성·reconcile 통합 테스트와 fixture
- 배치 collection reader, 파티션 계획과 확정 집계
- 성능 합성 데이터 생성, 검산, 자원 측정과 SLA 판정
- 관련 ADR, 백엔드 아키텍처 지침과 배치 명세

새 메시지 브로커, 캐시 또는 영속성 framework dependency는 추가하지 않는다.

## 범위 제외

- 취소, 전체·부분 환불과 음수 조정 정산
- 실제 계좌 지급과 출금
- Kafka, Outbox, CDC와 별도 정산 서비스
- 기존 승인 결제의 운영 backfill 실행
- 늦은 승인과 완료된 정산의 재개방
- 원본 결제 삭제와 정산 데이터 보존 기간
- 지갑·원장의 집합 기반 transaction 전환
