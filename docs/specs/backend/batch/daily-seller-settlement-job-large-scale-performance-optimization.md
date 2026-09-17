# 판매자 일일 정산 Job의 대용량 성능 최적화

## 목적과 완료 조건

`dailySellerSettlementJob`이 주문 1,000만 건 규모에서도 정산 정합성과 재시작 계약을 유지하면서 1시간 이내에 전체 처리를 완료하도록 수집과 검산·확정 단계를 최적화한다. 현재 성능 측정에서 1,000만 건 실행은 상세 수집에 약 2시간 31분이 걸렸고, 이후 확정 단계에서 정산 상세 ID 전체를 조회하여 잠그는 과정에서 `OutOfMemoryError`가 발생했다. 이번 작업은 두 문제를 함께 해결하되 기존 단일 partition·단일 thread 구조는 유지한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 주문 1,000만 건과 판매자 10만 명을 생성하고 모든 결제 승인 시각을 동일하게 설정한 기준 시나리오에서 전체 Job이 60분 이내에 `COMPLETED`된다.
- 기준 시나리오에서 `collectSettlementDetailsStep`은 50분, `confirmSellerSettlementsStep`은 5분, 지갑 입금과 완료 단계의 합은 5분 이내에 완료된다.
- 100만 건 기준 시나리오의 전체 Job은 4분 이내에 완료되어 기존 약 3분 결과에서 유의미하게 퇴행하지 않는다.
- 확정 단계가 정산 상세 ID나 정산 상세 객체를 건수에 비례하여 JVM 메모리에 적재하지 않으며 `OutOfMemoryError` 없이 완료된다.
- 상세 건수와 금액, 판매자별 정산, 지갑 잔액과 원장 불변식에 대한 기존 검산을 모두 통과한다.
- 업무 데이터 오류를 skip하지 않고 실패시키며, 실패한 동일 JobInstance를 마지막으로 commit된 chunk 다음부터 재시작하는 계약을 유지한다.
- 동일 승인 시각에 데이터가 집중된 분포와 정산일 전체에 승인 시각이 분산된 분포에서 누락과 중복 없이 같은 논리 결과를 만든다.

단계별 시간은 병목 위치를 명확히 하기 위한 개별 상한이며 전체 60분 상한을 대체하지 않는다. 성능 판정에는 데이터 준비, migration, 애플리케이션 context 시작 시간을 포함하지 않고 기존 성능 테스트 명세와 동일하게 Job 실행 시간만 사용한다.

## 기존 결정과 명세 관계

이 설계는 다음 결정을 유지한다.

- [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 API·배치·원장 모듈 경계와 migration 소유권
- [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 `수집 → 검산·확정 → 지갑 입금` 흐름, 단일 partition·단일 thread, 정산 및 원장 불변식
- [백엔드 아키텍처 지침](../../../architecture/backend.md)의 배치 transaction, checkpoint와 성능 결과 기록 원칙
- [성능 테스트 환경 명세](daily-seller-settlement-job-performance-test-environment.md)의 격리된 PostgreSQL Testcontainer, 결과 검산과 보고서 계약

이번 설계는 [수집 단계 명세](daily-seller-settlement-job-collection-stage.md)의 Spring Batch 생성 페이징 SQL과 [검산·확정 단계 명세](daily-seller-settlement-job-confirmation-stage.md)의 정산 상세 전체 행 잠금 방식을 대체한다. 그 밖의 데이터 선택 조건, 업무 검증, 상태 전이, transaction과 재시작 계약은 기존 명세를 유지한다.

새 실행 애플리케이션, 모듈 경계, 데이터 소유권 또는 병렬 처리 모델을 추가하지 않으므로 새로운 ADR은 작성하지 않는다. 멀티 partition이나 멀티 thread를 후속 도입할 때는 ADR-025의 초기 실행 모델을 변경하므로 별도 설계와 ADR 승인을 선행한다.

## 관찰된 병목과 설계 기준

동일한 `chunk-size`, `page-size`, `fetch-size` 1,000과 connection pool 5 조건에서 기존 측정 결과는 다음과 같다.

| 주문 수 | 전체 Job | 상세 수집 | 수집 처리량 | 결과 |
| ---: | ---: | ---: | ---: | --- |
| 100만 | 168,884ms | 161,117ms | 약 6,207건/초 | 성공 |
| 1,000만 | 9,089,247ms | 9,081,084ms | 약 1,101건/초 | 확정 단계 실패 |

데이터가 10배 증가했지만 상세 수집 시간은 약 56배 증가했고, 1,000만 건 실행 시간의 대부분을 수집 단계가 차지했다. 해당 실행 중 PostgreSQL CPU는 지속해서 포화됐지만 애플리케이션 heap은 낮게 유지됐다. 따라서 첫 번째 최적화 대상은 애플리케이션 병렬성이 아니라 페이지가 진행될수록 증가하는 데이터베이스 조회 비용이다.

기존 reader는 `(payment_approved_at, payment_id)`를 정렬 키로 사용하지만 Spring Batch가 다음 페이지 조건을 분리된 `OR` 조건으로 생성한다. 합성 데이터처럼 모든 `payment_approved_at`이 같으면 PostgreSQL이 이전 범위를 반복해서 읽고 필터링할 가능성이 있다. 실제 실행계획이 기존 보고서에 없으므로 특정 실행계획을 이미 확정된 원인으로 간주하지 않으며, 새 쿼리의 초기·중간·마지막 cursor 실행계획을 검증해 개선 여부를 판단한다.

수집이 끝난 뒤 확정 단계는 한 실행의 모든 `settlement_details.id`를 `SELECT ... FOR UPDATE`로 반환받는다. 1,000만 건 실행에서는 PostgreSQL JDBC 드라이버가 결과를 보관하는 과정에서 `OutOfMemoryError`가 발생했다. 정산 상세는 수집 완료 후 변경하지 않는 스냅샷이므로 실행 행을 잠가 상태 전이를 직렬화하고 상세 행 전체 잠금은 제거한다.

## 상세 수집 최적화

### 명시적 tuple keyset reader

`collectSettlementDetailsStep`은 Spring Batch가 다음 페이지 SQL을 조립하는 `JdbcPagingItemReader` 대신 배치가 SQL을 명시적으로 소유하는 keyset reader를 사용한다. reader는 첫 페이지와 후속 페이지를 구분한다.

첫 페이지는 다음 조건으로 조회한다.

```sql
SELECT ...
FROM payments p
LEFT JOIN orders o ON ...
LEFT JOIN sales s ON ...
LEFT JOIN seller_principal_identities spi ON ...
WHERE p.status = 'APPROVED'
  AND p.approved_at >= :start_at
  AND p.approved_at < :end_at
ORDER BY p.approved_at ASC, p.id ASC
LIMIT :page_size
```

후속 페이지에는 PostgreSQL row constructor 비교 조건을 추가한다.

```sql
AND (p.approved_at, p.id) > (:last_approved_at, :last_payment_id)
```

Spring Batch가 생성하는 다음 형태의 분리된 `OR` 조건으로 되돌리지 않는다.

```sql
p.approved_at > :last_approved_at
OR (p.approved_at = :last_approved_at AND p.id > :last_payment_id)
```

정렬 키는 기존과 동일한 `(payments.approved_at, payments.id)`를 유지한다. `approved_at`이 모두 같아도 `id`가 유일한 순서를 보장하며, 기존 `(status, approved_at, id)` 인덱스가 cursor 이후 범위를 탐색할 수 있게 한다. `payment_id` 하나만 cursor로 사용하는 방식이나 `OFFSET` 페이징으로 변경하지 않는다.

projection은 기존과 동일하게 결제, 주문, 판매와 판매자 사용자 매핑을 읽는다. join 누락과 금액·상태 오류를 processor가 발견해 Job을 실패시키는 계약도 변경하지 않는다. 조회 성능을 위해 `LEFT JOIN`을 `INNER JOIN`으로 바꾸어 잘못된 데이터를 조용히 제외하지 않는다.

### checkpoint와 재시작

reader는 마지막으로 읽은 `payment_approved_at`과 `payment_id`를 자신의 `ExecutionContext` 상태로 관리한다. 상태는 chunk transaction과 Spring Batch checkpoint가 성공적으로 commit될 때만 재시작 기준으로 영속화한다.

재시작 시에는 마지막으로 commit된 두 cursor 값보다 큰 행부터 읽는다. 실패한 chunk에서 읽었지만 commit되지 않은 항목은 다시 처리하고, 이미 commit된 항목은 다시 읽지 않는다. cursor 두 값 중 하나만 존재하거나 타입을 복원할 수 없으면 처음부터 재조회하거나 값을 보정하지 않고 명확한 상태 오류로 Step을 실패시킨다.

reader는 전체 대상 ID, 페이지 목록 또는 이미 처리한 cursor 목록을 JVM 메모리에 보관하지 않는다. 페이지 크기와 fetch 크기는 기존 `japda.batch.daily-seller-settlement` 설정을 사용하고 양수 검증을 유지한다.

### 인덱스 원칙

첫 구현에서는 새 영구 인덱스를 추가하지 않고 기존 `payments(status, approved_at, id)` 인덱스를 사용한다. 새 tuple keyset SQL의 초기·중간·마지막 cursor에 대해 `EXPLAIN (ANALYZE, BUFFERS)`를 비교하고 다음을 확인한다.

- 페이지가 진행돼도 이전 범위에 대한 대량 필터 제거가 누적되지 않는다.
- index scan 또는 동등하게 범위가 제한된 계획으로 page size에 근접한 행만 읽는다.
- 중간과 마지막 페이지의 실행 시간이 첫 페이지에 비례해 지속적으로 증가하지 않는다.

실행계획이 기존 인덱스를 사용하지 않거나 heap·buffer 접근이 계속 누적될 때만 별도 migration으로 인덱스를 추가한다. 확정 집계를 빠르게 하려는 목적으로 `settlement_details`에 인덱스를 먼저 추가해 수집 단계의 1,000만 건 쓰기 비용을 늘리지 않는다.

## 검산·확정 최적화

### 실행 행 잠금

`confirmSellerSettlementsStep`은 transaction 시작 후 대상 `settlement_runs` 행 하나를 `SELECT ... FOR UPDATE`로 잠근다. 잠금 뒤 상태가 `COLLECTED` 또는 멱등 재실행을 위한 `CONFIRMED`인지 확인한다.

`settlement_details`와 `seller_settlements`의 모든 ID를 조회해 `FOR UPDATE`로 잠그는 작업은 수행하지 않는다. 수집 Step은 `COLLECTING` 상태에서만 상세를 추가하고, `COLLECTED` 전환 뒤 정산 상세를 추가·수정·삭제하는 애플리케이션 경로는 두지 않는다. 확정 작업은 실행 행 잠금, JobInstance 식별 계약과 데이터베이스 unique 제약을 함께 사용해 직렬화한다.

운영자가 배치 외부에서 정산 테이블을 직접 변경하는 행위까지 row lock으로 방어하지 않는다. 직접 변경은 지원하는 애플리케이션 동작이 아니며 기존 검산 실패를 통해 탐지한다.

### 판매자별 임시 집계

정산 상세 1,000만 건을 여러 검증 쿼리에서 반복 집계하지 않는다. 확정 transaction이 사용하는 동일한 PostgreSQL connection에 `ON COMMIT DROP` 임시 테이블을 만들고 판매자별 집계를 한 번 저장한다. 컬럼과 `seller_id` primary key를 정의한 뒤 `INSERT ... SELECT ... GROUP BY seller_id`로 채우며, 임시 테이블은 transaction 종료 시 제거한다.

임시 집계에는 다음 값을 저장한다.

- `seller_id`
- `min_recipient_user_id`
- `max_recipient_user_id`
- `detail_count`
- `gross_amount`

`seller_id`를 임시 테이블의 primary key로 사용한다. `recipient_user_id`는 not null이므로 최소값과 최대값이 다르면 한 판매자의 상세에 둘 이상의 지급 대상이 섞인 것으로 판단하고 전체 transaction을 rollback한다. `COUNT(DISTINCT ...)`를 별도로 수행하지 않는다.

임시 집계의 `SUM(detail_count)`와 `SUM(gross_amount)`를 `settlement_runs.collected_count`, `collected_amount`와 비교한다. 값이 일치하고 실행 상태가 `COLLECTED`일 때만 임시 집계에서 `seller_settlements`를 집합 연산으로 생성한다. 수수료와 순액 계산 규칙, `BIGINT` 범위 확인과 데이터베이스 제약은 기존 확정 단계 명세를 유지한다.

저장 후 검산은 1,000만 건의 상세를 다시 읽지 않고 판매자별 임시 집계와 `seller_settlements`를 비교한다. 판매자 집합, 지급 대상, 상세 건수, 총액, 수수료와 순액 중 하나라도 다르면 transaction을 rollback한다. 모든 검산을 통과한 뒤에만 `settlement_runs`를 `CONFIRMED`로 전환한다.

업무 transaction은 commit됐지만 Spring Batch 메타데이터 완료 처리가 반영되지 않아 `CONFIRMED` 상태로 재실행되는 경우에도 같은 임시 집계를 만든다. 이때 새 판매자별 정산을 insert하지 않고 기존 `seller_settlements`와 집계 결과를 비교하여 같으면 멱등 성공하고 다르면 실패한다.

임시 테이블 생성, 집계 또는 비교 SQL이 실패하면 확정 transaction 전체를 rollback한다. connection pool의 다른 connection에서 임시 테이블을 조회하지 않으며 Spring transaction에 결합된 같은 connection을 사용한다.

## 지갑 입금 단계

이번 작업은 판매자별 지갑 입금의 transaction과 멱등성 계약을 변경하지 않는다. 기존 100만 건·판매자 1만 명 측정에서 입금 단계는 약 6초였으므로 수집과 확정을 먼저 개선하고 판매자 10만 명 기준 5분 상한 충족 여부를 측정한다.

5분 상한을 넘을 때는 원장 source 반복 조회, 판매자별 불필요한 존재 확인과 JDBC round trip을 별도 설계 대상으로 조사한다. 같은 사용자 지갑에 여러 판매자 정산이 집중될 수 있는지 확인하지 않은 상태에서 입금 writer를 병렬화하지 않는다.

## 오류 처리와 정합성

다음 오류는 모두 Job 실패로 처리한다.

- reader cursor가 일부만 존재하거나 복원할 수 없음
- 같은 keyset 페이지에서 정렬 키가 증가하지 않음
- 기존 수집 processor의 주문·판매·금액·지급 대상 검증 실패
- 수집 집계와 판매자별 임시 집계의 건수 또는 금액 불일치
- 한 판매자에게 둘 이상의 `recipient_user_id`가 존재함
- 임시 집계와 저장된 판매자별 정산 결과 불일치
- 확정을 시작할 수 없는 `SettlementRun` 상태
- 임시 테이블 또는 집계 SQL 실행 실패

데이터 오류를 skip하거나 cursor를 임의로 다음 값으로 보정하지 않는다. `ON CONFLICT DO NOTHING`으로 누락이나 중복을 숨기지 않으며, 기존 unique 및 check constraint 위반을 그대로 실패로 처리한다. 로그에는 Job과 Step 식별자, `settlementRunId`, cursor와 오류 분류처럼 복구에 필요한 값만 남기고 개인정보, credential과 전체 정산 projection을 기록하지 않는다.

## 성능 측정과 관측성

성능 판정은 기존 `performanceTest` source set과 격리된 PostgreSQL Testcontainer를 사용한다. 1시간 SLA 시나리오는 다음 값으로 고정한다.

| 항목 | 값 |
| --- | ---: |
| 판매자 수 | 100,000 |
| 주문·승인 결제 수 | 10,000,000 |
| 승인 시각 분포 | 정산일의 동일 시각 |
| chunk size | 1,000 |
| page size | 1,000 |
| fetch size | 1,000 |
| Hikari maximum pool size | 5 |
| warm-up 횟수 | 0 |
| 측정 횟수 | 1 |

Job timeout은 측정 및 보고서 기록 여유를 위해 65분으로 설정하되, 성공 판정은 기록된 Job duration이 60분 이하인지 별도로 검사한다. timeout을 통과했다는 사실만으로 SLA를 충족한 것으로 간주하지 않는다.

성능 데이터의 승인 시각 분포는 `japda.performance.dataset.approval-time-distribution` 설정으로 선택하며 `FIXED`와 `UNIFORM`만 허용한다. 기본값 `FIXED`는 기존처럼 모든 결제를 정산일 12시에 승인한다. `UNIFORM`은 0부터 시작하는 결제 순번을 정산일의 86,400,000밀리초에 균등하게 대응시켜 시작 시각 이상, 다음 날 시작 시각 미만에 결정론적으로 배치한다. 지원하지 않는 값은 데이터 생성 전에 실패시킨다.

성능 보고서는 기존 파일에 전체 및 Step별 시간, 처리량, commit 수, JVM과 PostgreSQL 자원을 기록한다. 별도 진단 실행은 `query-plans.txt`에 keyset 쿼리의 초기·중간·마지막 cursor별 실행 시간과 `EXPLAIN (ANALYZE, BUFFERS)` 결과를 남긴다. 실행계획 진단은 공식 SLA 측정과 다른 독립 schema에서 수행하여 cache warming이나 선행 조회가 측정값에 영향을 주지 않게 한다. 실행계획에는 실제 결제·사용자 식별값을 기록하지 않는다.

정산일 전체에 승인 시각이 분산된 시나리오도 실행해 특정 합성 분포에서만 동작하는 최적화가 아닌지 확인한다. 이 시나리오는 동일한 정합성 검증을 통과해야 하지만 1시간 SLA의 공식 기준은 승인 시각이 동일한 더 불리한 시나리오로 한다.

## 검증 전략

단위 테스트와 PostgreSQL Testcontainers 기반 통합 테스트로 다음을 검증한다.

1. 첫 페이지가 정산일, 상태와 정렬 조건에 맞는 결제만 반환한다.
2. 후속 페이지가 tuple cursor보다 큰 행만 반환하고 `OFFSET`이나 분리된 `OR` 조건을 사용하지 않는다.
3. 모든 승인 시각이 같아도 여러 페이지에서 결제를 누락하거나 중복하지 않는다.
4. 승인 시각이 분산된 경우에도 `(approved_at, id)` 순서를 유지한다.
5. 정산일 시작 경계는 포함하고 다음 날 시작 경계는 제외한다.
6. join 관계가 누락된 결제를 조회에서 제외하지 않고 processor가 데이터 오류로 실패시킨다.
7. 여러 chunk가 commit된 뒤 실패하면 마지막 commit cursor 다음부터 재시작한다.
8. cursor가 일부만 저장되거나 손상되면 Step을 실패시킨다.
9. 확정 단계가 `settlement_runs` 행을 잠그고 상세 ID 전체 잠금 쿼리를 실행하지 않는다.
10. 판매자별 임시 집계가 상세 건수, 총액과 지급 대상을 정확히 계산한다.
11. 같은 판매자의 지급 대상이 둘 이상이면 판매자별 정산 insert와 상태 전환을 모두 rollback한다.
12. 임시 집계와 수집 완료 집계가 다르면 확정을 실패시킨다.
13. 확정 결과 저장 후에는 상세를 다시 전체 집계하지 않고 임시 집계와 판매자별 결과를 검산한다.
14. 확정 업무 transaction commit 뒤 메타데이터 완료 전 장애를 재현하고 재실행이 중복 행 없이 성공하는지 검증한다.
15. 동시에 확정을 시도해도 실행 행 잠금과 상태 검증으로 하나의 유효한 결과만 생성한다.
16. 대상이 없는 정산일도 빈 임시 집계와 0건·0원 검산을 거쳐 정상 완료한다.
17. 100만 건 기준 시나리오가 전체 4분 이내에 완료된다.
18. 1,000만 건 기준 시나리오가 단계별 상한과 전체 60분 상한을 모두 만족한다.
19. 1,000만 건 실행에서 모든 기존 업무 검산과 Spring Batch counter 검증을 통과한다.
20. 전체 backend와 batch test·build가 기존 API, 원장과 정산 동작에 회귀를 만들지 않는다.

성능 결과는 한 번의 로컬 실행만으로 일반적인 운영 성능을 보장하지 않는다. 다만 동일한 격리 환경과 고정 시나리오에서 상한을 넘으면 이번 작업의 완료 조건을 충족하지 못한 것으로 판정한다.

## 예상 변경 범위

구현 시 변경 가능성이 높은 위치는 다음과 같다.

- `:batch`의 정산 상세 reader 구성과 cursor 상태 관리 구성 요소
- `DailySellerSettlementJobConfiguration`의 수집 reader bean
- `SellerSettlementJdbcRepository`의 잠금 및 판매자별 집계 SQL
- `ConfirmSellerSettlementsTasklet`의 확정 흐름
- 수집 reader와 확정 repository의 단위·통합 테스트
- 성능 테스트의 승인 시각 분포 설정, SLA 검증과 실행계획 진단 산출물
- 구현 결과와 충돌하는 기존 수집·확정·성능 테스트 명세의 해당 절

첫 구현에는 새 dependency, 새 실행 애플리케이션, API 변경과 Flyway migration을 포함하지 않는다. 실행계획 검증 결과 새 인덱스가 필요하면 근거와 쓰기 비용을 제시하고 별도 migration을 작업 범위에 추가한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- 멀티 partition, task executor 또는 멀티 thread 정산
- chunk, page와 fetch 기본값의 근거 없는 확대
- 지갑 입금 writer의 병렬화
- 정산 업무 테이블의 보존·삭제 정책
- 운영 scheduler, 수동 실행 API와 관리자 화면
- 취소·환불, 역분개, 정정 정산과 늦게 승인된 결제 처리
- 실제 계좌 지급과 외부 금융 시스템 연동
- PostgreSQL 또는 Spring Batch 이외의 새 저장·처리 기술 도입

명시적 tuple keyset reader와 확정 집계 개선 이후에도 기준 시나리오가 1시간을 넘으면 결과를 실패로 기록한다. 이 경우 현재 설계 안에서 검증되지 않은 미세 조정을 반복하지 않고, 실제 실행계획과 단계별 시간을 근거로 `payment_id` 범위 partition을 별도의 아키텍처 설계와 ADR 대상으로 검토한다.
