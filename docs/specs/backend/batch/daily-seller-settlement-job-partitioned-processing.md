# 판매자 일일 정산 Job 파티션 병렬 처리

> 이 문서의 결제 ID 기반 collection 입력과 `settlement_details` 생성 설계는 [결제 승인 시 정산 원천 스냅샷 적재](../settlement/payment-approval-settlement-entry-snapshot.md)가 대체한다. local partitioning, 결정론적 계획, worker별 checkpoint, 지갑 입금과 최종 검산 계약은 유지하되 collection 범위는 `settlement_entries.id`를 기준으로 한다.

## 목적

`dailySellerSettlementJob`이 승인 결제 1억 건 이상을 처리할 때도 구매별 정산 상세 수집과 판매자 지갑 입금을 제한된 동시성으로 병렬 실행한다. 각 worker가 상호 배타적인 ID 범위를 소유하게 하여 공유 reader를 사용하는 멀티스레드 Step의 상태 경쟁을 피하고, Spring Batch checkpoint와 동일 JobInstance 재시작 계약을 유지한다.

이 설계는 단일 배치 JVM과 단일 PostgreSQL을 유지한다. 별도 worker 애플리케이션이나 메시지 브로커를 도입하지 않고 Spring Batch local partitioning으로 처리량을 높인다.

## 성공 기준

- 단일 배치 JVM과 단일 PostgreSQL에서 승인 결제 100,000,000건, 판매자 1,000,000명의 고정 시나리오를 Job 실행 시간 60분 이내에 완료한다.
- 단계별 상한은 상세 수집 45분, 검산·확정 10분, 지갑 입금과 나머지 Step 합계 5분으로 둔다. 전체 60분 상한과 단계별 상한을 모두 충족해야 한다.
- 모든 승인 시각이 같은 `FIXED` 분포와 정산일 전체에 분산된 `UNIFORM` 분포에서 같은 논리 결과를 만든다. 공식 SLA는 기존 성능 명세와 같이 `FIXED` 분포의 결과로 판정하고 `UNIFORM` 분포는 정합성과 성능 퇴행을 별도로 확인한다.
- 결제, 판매자와 ID 범위에 편향이 있는 경우에도 누락과 중복 없이 완료하며 특정 파티션 하나가 전체 worker를 장시간 유휴 상태로 만들지 않는다.
- 정산 상세 건수·금액, 판매자별 정산, 지갑 잔액과 원장 불변식에 대한 기존 검산을 모두 통과한다.
- worker 실패 후 같은 JobInstance를 재시작하면 완료된 파티션은 반복 처리하지 않고 실패하거나 미완료된 파티션만 마지막 commit checkpoint부터 처리한다.
- 업무 데이터 오류는 skip하지 않으며 파티션 경계, checkpoint 또는 결과 검산 오류를 자동 보정하지 않는다.
- 성능 결과에는 JVM과 PostgreSQL 실행 환경, worker와 파티션 수, connection pool, chunk·page·fetch 크기, 단계별 처리량, CPU·메모리·I/O·lock wait와 검산 결과를 기록한다.

성능 테스트의 데이터 준비, migration과 Spring context 시작 시간은 기존 성능 테스트 명세와 동일하게 Job 실행 시간에서 제외한다. 한 번의 로컬 측정은 운영 환경의 절대 성능을 보장하지 않으므로 구현 완료 시 사용한 기준 장비와 컨테이너 자원을 결과에 함께 기록한다.

## 기존 결정과 명세 관계

다음 결정은 유지한다.

- [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 API·배치·원장 모듈 경계와 migration 소유권
- [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 정산 근거 보존, 사용자 귀속 지갑, 원장 멱등성과 금액 불변식
- [ADR-030](../../../architecture/decisions/ADR-030-daily-seller-settlement-local-partitioning.md)의 local partitioning, 결정론적 파티션 계획, worker별 checkpoint와 지갑 입금 잠금·검산 계약
- [백엔드 아키텍처 지침](../../../architecture/backend.md)의 동일 PostgreSQL·transaction manager, checkpoint와 성능 결과 기록 원칙
- [기존 대용량 최적화 명세](daily-seller-settlement-job-large-scale-performance-optimization.md)의 명시적 keyset paging, 전체 상세 ID 비적재, PostgreSQL 집합 집계 원칙
- [성능 테스트 환경 명세](daily-seller-settlement-job-performance-test-environment.md)의 격리된 PostgreSQL Testcontainer, 결과 검산과 보고서 계약

이 설계는 ADR-025와 백엔드 아키텍처 지침에 명시됐던 단일 partition·단일 thread 실행 모델을 대체한다. local partitioning, 파티션별 checkpoint와 공통 실행 행 잠금 제거 결정은 ADR-030에 기록하고 ADR 목록과 백엔드 아키텍처 지침에 반영했다.

## 현재 병목과 제약

현재 Job은 다음 Step을 순서대로 실행한다.

```text
prepare
  → collect settlement details
  → complete collection
  → confirm seller settlements
  → credit seller wallets
  → complete run
```

상세 수집은 하나의 `SettlementPaymentKeysetReader`, processor와 writer가 모든 결제를 순차 처리한다. 지갑 입금도 하나의 reader와 writer가 모든 판매자별 정산을 순차 처리한다.

현재 `SellerWalletCreditWriter`는 각 chunk에서 같은 `settlement_runs` 행을 `FOR UPDATE`로 잠근다. 이 구조에서 worker만 늘리면 모든 worker가 공통 행 잠금을 기다리므로 지갑 입금은 사실상 직렬화된다. 반면 `seller_principal_identities`는 `seller_id`와 `user_id`를 각각 유일하게 제한하므로 서로 다른 판매자별 정산은 정상 데이터에서 서로 다른 사용자 지갑을 대상으로 한다.

확정 Step은 하나의 transaction에서 `settlement_details`를 판매자별로 집계하고 결과를 검산한다. 이 단계는 실행 전체를 한 번만 확정해야 하므로 단순 worker 분할의 대상이 아니다.

## 선택한 구조

### 전체 흐름

```text
prepare run
  → prepare collection partition plan
  → partitioned collection manager
      ├─ collection worker 000
      ├─ collection worker 001
      └─ collection worker N
  → complete collection
  → confirm seller settlements
  → prepare credit partition plan
  → partitioned credit manager
      ├─ credit worker 000
      ├─ credit worker 001
      └─ credit worker N
  → complete run
```

manager Step은 파티션을 실행하고 완료 여부를 조율한다. 각 worker Step은 자신의 범위 안에서 기존과 같은 단일 스레드 chunk 처리를 수행한다. 모든 collection worker가 완료된 후에만 수집 완료 검산을 실행하고, 모든 credit worker가 완료된 후에만 실행 완료 검산을 실행한다.

collection과 credit은 Job 흐름에서 동시에 실행되지 않으므로 하나의 전용 bounded thread pool을 공유한다. thread pool queue는 논리 파티션 전체를 무제한 적재하지 않으며 Spring Batch manager가 생성한 파티션 수만 수용할 수 있게 제한한다. 애플리케이션 종료 시에는 실행 중 chunk가 transaction 경계에서 종료되고 Spring Batch 메타데이터에 실패 상태가 남도록 graceful shutdown을 적용한다.

### 동시성 기본값

다음 설정을 `japda.batch.daily-seller-settlement` 아래에 추가한다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `worker-count` | 8 | 동시에 실행할 partition worker의 최대 수 |
| `collection-partition-count` | 64 | 상세 수집의 논리 파티션 수 |
| `credit-partition-count` | 64 | 지갑 입금의 논리 파티션 수 |
| `chunk-size` | 기존 값 유지 | worker transaction당 처리 건수 |
| `page-size` | 기존 값 유지 | worker reader의 페이지 크기 |
| `fetch-size` | 기존 값 유지 | JDBC fetch 크기 |

논리 파티션 수는 worker 수보다 크게 두어 먼저 끝난 worker가 다음 범위를 처리할 수 있게 한다. `worker-count`와 두 partition count는 양수여야 하며 partition count는 worker count 이상이어야 한다. 조건을 만족하지 않으면 애플리케이션 시작 시 실패한다.

성능 기준 시나리오에서는 worker 8개와 Hikari maximum pool size 12를 시작점으로 사용한다. manager, JobRepository와 단일 tasklet이 사용할 connection 여유를 위해 운영 설정에서도 maximum pool size를 `worker-count + 4` 이상으로 둔다. 4·8·16 worker를 비교하되 PostgreSQL CPU, I/O 또는 lock wait가 악화되면 더 큰 값을 선택하지 않는다. 완료 구현의 기본값은 8 worker로 고정하고 환경별 조정 결과를 성능 보고서에 기록한다.

## 파티션 계획

### 공통 원칙

- 파티션 범위는 `[startInclusive, endExclusive)`로 표현한다.
- 파티션 이름은 종류와 0부터 시작하는 고정 폭 순번으로 만든다. 같은 JobInstance를 재시작해도 이름이 바뀌지 않아야 한다.
- 준비 Step이 최소·최대 ID와 partition count를 사용해 모든 범위를 한 번 계산하고 Job `ExecutionContext`에 저장한다.
- 재시작 시 이미 저장된 계획을 재사용한다. 데이터베이스를 다시 조회해 범위를 확대·축소하거나 partition count 설정 변경을 반영하지 않는다.
- 저장된 계획에 범위 누락, 중첩, 역전, 일부 값 누락 또는 저장된 실제 파티션 수와 경계 key의 불일치가 있으면 Job을 실패시킨다. 재시작 시 현재 partition count 설정이 달라진 것은 오류로 보지 않고 저장된 계획을 우선한다.
- 대상이 없으면 빈 계획을 저장하고 manager Step은 worker를 만들지 않은 채 정상 완료한다.
- 마지막 범위의 `endExclusive`는 조회된 최대 ID에 1을 더한 값이다. 최대 ID가 `Long.MAX_VALUE`이면 마지막 파티션만 상한 포함 조건을 사용해 overflow를 피한다.
- 범위 폭은 `(maxId - minId + 1)`을 partition count로 나눈 몫과 나머지로 계산한다. 경계 산술에는 JDK의 `BigInteger` 또는 동등한 overflow 안전 연산을 사용하고, JDBC에 전달할 실제 경계만 `Long`으로 변환한다. ID 공간이 partition count보다 작으면 빈 범위를 만들지 않고 실제 ID 공간만큼만 파티션을 생성한다.

Job `ExecutionContext`에는 파티션 종류별 최소 ID, 최대 ID, 실제 partition count와 각 파티션 경계를 저장한다. 각 값은 `collection.partition.000.startInclusive`, `collection.partition.000.endExclusive`처럼 고정된 key의 `Long`과 마지막 상한 포함 여부의 `String` 또는 `Boolean`으로 저장한다. 임의 객체, collection과 별도 직렬화 형식을 저장하지 않는다. 전체 대상 ID 목록이나 처리된 ID 목록도 저장하지 않는다.

파티션 계획을 업무 테이블에 중복 저장하지 않는다. worker 진행 상태와 checkpoint는 Spring Batch 메타데이터를 정합성 기준으로 사용한다. 운영 관측은 Job·manager Step·worker Step의 이름과 `ExecutionContext` 범위를 통해 제공한다.

### 데이터 편향 처리

ID 범위는 행 개수가 아니라 ID 공간을 같은 폭으로 나누므로 삭제나 데이터 생성 패턴에 따라 파티션별 건수가 다를 수 있다. 이를 완화하기 위해 기본적으로 worker 수의 8배인 64개 논리 파티션을 사용한다.

파티션별 read·write·commit 건수와 실행 시간을 보고서에 기록한다. 최장 파티션 시간이 전체 collection 또는 credit 시간의 25%를 초과하면 편향 실패로 판정한다. 이 경우 범위 수를 근거 없이 계속 늘리지 않고 실제 ID 분포와 실행계획을 확인해 partition count 또는 경계 산정 방식을 후속 설계한다.

## 상세 수집 파티셔닝

### 범위 생성

collection 계획 준비 Step은 정산일 범위와 `APPROVED` 상태를 만족하는 `payments.id`의 최소값과 최대값을 조회한다. 이 시점에 확정한 ID 범위를 같은 JobInstance의 모든 재시작에서 유지한다.

결제 승인이 완료된 뒤 `PaymentStatus.APPROVED`에서 다른 상태로 전이하거나 `approved_at`을 수정하는 애플리케이션 경로는 현재 존재하지 않는다. 다만 배치 시작 후 과거 정산일의 결제가 늦게 `APPROVED`로 전이되는 문제는 기존 정산의 범위 밖이며, 이 설계에서도 실행 중 입력 snapshot을 별도 테이블로 복제하지 않는다. 늦은 승인·취소·환불·정정 정산은 별도 정책과 설계 대상으로 남긴다.

### worker reader

각 collection worker는 다음 조건으로 자신의 결제 범위만 읽는다.

```sql
WHERE p.status = 'APPROVED'
  AND p.approved_at >= :start_at
  AND p.approved_at < :end_at
  AND p.id >= :partition_start_id
  AND p.id < :partition_end_id
  AND p.id > :last_payment_id
ORDER BY p.id ASC
LIMIT :page_size
```

첫 페이지에는 `last_payment_id` 조건을 생략한다. 마지막 상한 포함 파티션은 `p.id <= :partition_max_id`를 사용한다. 정렬 키는 파티션 안에서 유일한 `payments.id` 하나로 제한하고 파티션마다 마지막으로 반환한 ID를 자신의 worker `ExecutionContext`에 저장한다.

기존 projection과 `LEFT JOIN`은 유지한다. 주문, 판매 또는 판매자 사용자 연결이 누락된 결제를 조회에서 제외하지 않고 processor가 업무 데이터 오류로 발견하게 한다. 기존 금액·상태·소유권 검증과 `settlement_details` batch insert도 유지한다.

worker reader는 전체 ID, 페이지 목록 또는 다른 파티션의 cursor를 메모리에 보관하지 않는다. cursor가 범위 밖이거나 일부만 저장됐거나 복원할 수 없으면 해당 worker Step을 실패시킨다.

### 인덱스

파티션 reader가 ID 범위와 정렬을 사용하므로 기존 `(status, approved_at, id)` 인덱스만으로 충분하다고 가정하지 않는다. 구현에는 다음 인덱스를 Flyway migration으로 추가한다.

```sql
CREATE INDEX payments_settlement_partition_idx
    ON payments (status, id)
    INCLUDE (approved_at);
```

이 인덱스는 `status`와 ID 범위로 worker의 탐색 영역을 제한하고 `approved_at` 날짜 필터를 index 단계에서 평가할 수 있게 한다. `order_id`와 `requested_amount`까지 포함해 인덱스를 과도하게 키우지 않으며, 대상 결제의 projection과 join에는 heap 접근을 허용한다.

구현 검증에서 초기·중간·마지막 파티션과 각 파티션의 초기·중간·마지막 cursor에 대해 `EXPLAIN (ANALYZE, BUFFERS)`를 기록한다. 대량 정렬, 전체 날짜 범위 반복 scan 또는 page 진행에 따른 선형 성능 저하가 있으면 구현 완료로 판정하지 않는다. 기존 인덱스는 결제 날짜 범위 조회에 계속 사용되므로 이번 작업에서 제거하지 않는다.

### transaction과 재시작

각 worker의 chunk 변경, worker `ExecutionContext` cursor와 Spring Batch 메타데이터는 기존과 같이 같은 PostgreSQL transaction manager로 함께 commit한다. 하나의 worker가 실패해도 이미 commit된 다른 worker의 업무 데이터와 checkpoint는 유지한다.

manager Step은 하나 이상의 worker가 실패하면 실패한다. 이미 실행 중인 다른 worker는 현재 chunk 경계를 마치고 결과를 기록할 수 있다. 재시작은 `COMPLETED` worker를 건너뛰고 실패하거나 미완료된 worker만 마지막 commit cursor 다음부터 실행한다.

`settlement_details.payment_id` unique 제약을 파티션 겹침과 중복 처리의 최종 방어선으로 유지한다. unique 위반을 `ON CONFLICT DO NOTHING`으로 숨기거나 skip하지 않는다.

## 수집 완료와 판매자별 확정

모든 collection worker가 완료된 뒤 기존 `completeSettlementCollectionStep`이 상세 전체 건수와 총액을 집계하여 `settlement_runs`를 `COLLECTED`로 전이한다. 일부 worker가 실패하거나 완료되지 않은 상태에서는 이 Step에 진입하지 않는다.

`confirmSellerSettlementsStep`은 단일 transaction과 단일 실행을 유지한다. 실행 행을 `FOR UPDATE`로 잠그고 PostgreSQL 임시 테이블에 판매자별 집계를 한 번 생성한 뒤 기존 건수·금액·지급 대상·수수료·순액 검산과 `CONFIRMED` 전이를 수행한다.

확정 집계를 애플리케이션 partition worker로 분리하지 않는다. 판매자 경계가 상세 수집 파티션과 일치하지 않고, 부분 집계의 영구 저장·merge·재시작 계약이 추가되기 때문이다. PostgreSQL 자체의 집합 연산과 가능한 병렬 실행계획을 우선 사용한다.

1억 건 성능 검증에서 확정 Step이 10분 상한을 넘으면 상세 수집 파티셔닝의 worker 수를 늘려 우회하지 않는다. 실행계획, temp 파일 크기, `work_mem`, CPU와 I/O를 근거로 판매자 해시 범위별 영구 부분 집계와 최종 merge를 별도 아키텍처 설계 대상으로 검토한다.

## 지갑 입금 파티셔닝

### 범위 생성

credit 계획 준비 Step은 현재 `settlement_run_id`에 속한 `seller_settlements.id`의 최소값과 최대값을 조회하고 collection과 같은 방식으로 상호 배타적인 범위를 저장한다. 판매자별 정산이 없으면 빈 계획으로 정상 완료한다.

### worker 처리

각 credit worker는 자신의 ID 범위에 속한 판매자별 정산 ID를 오름차순 keyset 방식으로 읽는다. 각 판매자별 정산은 정확히 하나의 파티션에만 속한다.

worker 시작 시 `settlement_runs`를 잠금 없이 한 번 조회하여 상태가 `CONFIRMED` 또는 멱등 재실행을 위한 `COMPLETED`인지 검증한다. chunk writer에서는 현재의 공통 `settlement_runs FOR UPDATE` 조회를 제거한다. JobRepository가 같은 JobInstance의 동시 실행을 방지하고, Job 흐름상 모든 credit worker가 끝나기 전에 완료 Step이 실행되지 않는 계약을 사용한다.

각 item은 해당 chunk transaction 안에서 다음 순서를 유지한다.

1. 대상 `seller_settlements` 행을 `FOR UPDATE`로 조회하고 실행 ID와 상태를 검증한다.
2. `CONFIRMED`이고 순액이 양수이면 기존 원장 source를 검사한다.
3. 사용자 지갑을 멱등 생성하고 지갑 행을 `FOR UPDATE`로 잠근다.
4. 잔액 증가, 원장 insert와 판매자별 정산의 `CREDITED` 전이를 같은 chunk transaction에서 수행한다.
5. 이미 `CREDITED`이면 원장과 입금 결과가 정확히 일치하는지 검증한다.

`seller_principal_identities.user_id`와 `seller_id`의 unique 제약 때문에 정상 정산 데이터에서는 서로 다른 판매자가 같은 사용자 지갑을 공유하지 않는다. 파티션은 판매자별 정산 ID 범위가 겹치지 않으므로 worker 간 같은 지갑 행 경합도 발생하지 않는다. 제약과 다른 데이터가 관찰되거나 같은 지갑 lock wait가 발생하면 이를 재시도로 은폐하지 않고 정합성 오류로 조사한다.

원장 `(source_type, source_id)` unique 제약과 기존 멱등 검증을 유지한다. 예상하지 않은 기존 원장, 금액·사용자 불일치, 상태 전이 실패는 worker와 manager Step을 실패시킨다.

## 실행 완료

모든 credit worker가 완료된 뒤 기존 `completeSettlementRunStep`을 단일 transaction으로 실행한다. 전체 판매자별 정산이 `CREDITED`인지, 양수 순액에 대응하는 원장이 정확히 한 건씩 존재하는지, 원장 건수와 금액이 판매자별 정산 결과와 일치하는지 검증한 뒤에만 실행을 `COMPLETED`로 전이한다.

완료 transaction이 commit됐지만 Spring Batch 메타데이터 완료 처리가 반영되지 않은 경우에는 같은 JobInstance 재시작이 기존 결과를 검증하고 중복 입금 없이 완료돼야 한다.

## 동시 실행과 fencing

- `settlementDate`는 식별 JobParameter로 유지하고 임의의 run ID로 새 JobInstance를 만들지 않는다.
- `settlement_runs.settlement_date` unique 제약과 Spring Batch JobRepository를 같은 정산일의 중복 실행 방어선으로 유지한다.
- 같은 JobInstance를 서로 다른 프로세스에서 동시에 시작하려는 경우 한 실행만 시작되고 다른 실행은 JobRepository의 실행 상태 충돌로 거절돼야 한다.
- partition worker를 애플리케이션 외부에서 직접 실행하는 경로는 제공하지 않는다.
- 배치 업무 테이블을 외부에서 직접 수정하는 행위까지 worker lock으로 방어하지 않는다. 이는 지원하는 애플리케이션 동작이 아니며 최종 검산 실패로 탐지한다.

## 오류 처리

다음 오류는 모두 해당 worker와 manager를 거쳐 Job 실패로 처리한다.

- 파티션 계획의 누락, 중첩, 역전 또는 복원 실패
- worker cursor가 범위 밖이거나 손상됨
- 동일 페이지에서 cursor가 증가하지 않음
- 결제, 주문, 판매, 지급 대상 또는 금액 검증 실패
- 파티션 경계 밖의 결제나 판매자별 정산 조회
- 정산 상세의 중복 결제 unique 위반
- 입금 대상 실행·판매자별 정산 상태 불일치
- 예상하지 않은 기존 원장 또는 원장 결과 불일치
- 수집 완료, 판매자별 확정 또는 실행 완료 검산 불일치
- worker thread pool 또는 DB connection 획득 실패

업무 데이터 오류에는 retry를 적용하지 않는다. deadlock, connection 단절 같은 일시적 인프라 오류도 자동 retry를 먼저 추가하지 않고 Spring Batch 재시작으로 복구한다. 실제 장애 측정으로 안전한 retry 대상과 횟수가 확인될 때만 별도 설계한다.

한 worker의 실패를 이유로 이미 commit된 다른 worker 결과를 삭제하지 않는다. 재시작 가능한 checkpoint와 unique 제약을 사용하며 정산 상세, 판매자별 정산, 지갑 또는 원장을 자동 정리하는 보상 로직을 두지 않는다.

## 관측성

로그와 성능 보고서에는 다음 값을 기록한다.

- Job과 JobExecution 식별자, `settlementRunId`, 정산일
- manager와 worker Step 이름
- 파티션 종류·순번·시작 ID·종료 ID
- 파티션별 read·write·commit·rollback·filter 건수와 실행 시간
- 전체와 단계별 처리량
- active worker 수와 executor queue 사용량
- Hikari active·idle·pending connection
- PostgreSQL CPU·I/O·temp 파일·buffer·lock wait와 deadlock
- JVM heap·GC·thread 수
- 전체 정산 상세, 판매자별 정산, 원장 건수와 금액 검산 결과

개별 구매자·판매자·사용자 식별자, 결제 키, credential과 전체 projection은 로그에 남기지 않는다. 오류 조사에는 파티션 범위와 문제가 발생한 업무 행의 내부 ID만 사용한다.

## 검증 전략

### 단위 테스트

1. ID 공간을 요청한 수 이하의 연속된 비중첩 범위로 나눈다.
2. 최소값과 최대값이 같거나 ID 공간이 partition count보다 작은 경우 빈 범위를 만들지 않는다.
3. `Long.MAX_VALUE` 최대 ID에서 overflow 없이 마지막 포함 범위를 만든다.
4. 저장된 파티션 계획을 재시작할 때 설정값이나 현재 데이터 변화와 무관하게 같은 범위를 복원한다.
5. 겹치거나 일부가 누락된 계획과 손상된 cursor를 거절한다.
6. collection reader가 자신의 ID 범위와 정산일 안에서만 오름차순으로 읽는다.
7. credit reader가 자신의 판매자별 정산 ID 범위만 읽는다.
8. partition count와 worker count 설정 검증이 잘못된 값을 거절한다.

### PostgreSQL 통합 테스트

1. 여러 collection worker가 동일 승인 시각의 결제를 누락·중복 없이 수집한다.
2. 승인 시각이 분산돼도 ID 파티션과 날짜 조건을 모두 만족한다.
3. 파티션 경계 직전·경계값·직후의 결제가 정확히 한 worker에만 포함된다.
4. 한 collection worker가 여러 chunk commit 후 실패하면 완료 worker는 재실행하지 않고 실패 worker만 마지막 cursor부터 재시작한다.
5. 하나의 worker에서 업무 데이터 오류가 발생하면 수집 완료와 확정 Step으로 진행하지 않는다.
6. 전체 collection 완료 후 기존 건수·금액 검산과 확정 결과가 단일 thread 결과와 동일하다.
7. 여러 credit worker가 공통 `settlement_runs` 행을 `FOR UPDATE`하지 않는다.
8. 서로 다른 worker가 각 판매자 정산, 지갑, 원장과 상태 전이를 정확히 한 번 적용한다.
9. credit worker 실패와 재시작 후 중복 잔액 증가나 원장이 없다.
10. 모든 credit worker 완료 전에는 실행 완료 Step이 시작되지 않는다.
11. 동일 JobInstance 동시 시작 시 하나의 유효한 실행만 존재한다.
12. 대상 결제나 판매자가 없는 정산일도 빈 파티션 계획과 0건·0원 검산으로 완료한다.
13. worker 1개 설정의 결과가 기존 단일 thread 결과와 동일하다.

### 장애와 경합 테스트

- collection worker의 read 이후, write 이전, chunk commit 이후에 각각 장애를 주입한다.
- credit worker의 지갑 잠금 전, 잔액 변경 후, 원장 insert 후, 판매자별 정산 상태 전이 후에 각각 장애를 주입한다.
- worker 수보다 많은 파티션에서 한 파티션을 의도적으로 지연해 남은 worker가 다른 파티션을 계속 처리하는지 확인한다.
- 제한된 connection pool에서 connection 고갈, lock wait와 deadlock 여부를 확인한다.
- Job 종료 신호를 보내 진행 중 transaction과 Step 상태가 재시작 가능한 상태로 남는지 확인한다.

### 성능 테스트

기존 `performanceTest` source set을 확장하여 다음 순서로 실행한다.

| 단계 | 승인 결제 | 판매자 | 목적 |
| --- | ---: | ---: | --- |
| 회귀 | 1,000,000 | 10,000 | 기존 4분 상한과 결과 비교 |
| 기존 대용량 | 10,000,000 | 100,000 | 기존 60분 상한과 병렬화 회귀 확인 |
| 중간 용량 | 30,000,000 | 300,000 | 처리량 선형성과 DB 포화점 확인 |
| 목표 용량 | 100,000,000 | 1,000,000 | 새 60분 SLA 판정 |

각 용량에서 worker 1·4·8·16을 독립 schema와 독립 프로세스로 실행한다. 앞선 실행의 cache warming이 다음 결과에 영향을 주지 않게 한다. 공식 결과는 `worker-count=8`, collection·credit partition count 64, Hikari maximum pool size 12로 판정하고 다른 조합은 포화점 진단에 사용한다.

성능 데이터 생성 시간과 디스크 요구량도 별도로 기록하며 실행 전 예상 데이터와 인덱스 크기를 확인한다. 호스트의 여유 디스크가 예상치에 안전 여유를 더한 값보다 작으면 1억 건 실행을 시작하지 않는다.

1억 건 전체 실행 전에 대표 파티션을 사용해 다음 쿼리의 `EXPLAIN (ANALYZE, BUFFERS)`를 별도 schema에서 수집한다.

- collection 파티션 경계 산정 쿼리
- collection worker의 첫·중간·마지막 keyset 페이지
- 수집 완료 건수·금액 집계
- 판매자별 임시 집계와 결과 insert
- credit 파티션 경계 산정과 ID 페이지 조회
- 최종 원장 검산

### 테스트 실행 예시

다음 명령은 repository root가 아니라 Gradle wrapper가 있는 `apps/backend`에서 실행한다.

```bash
cd apps/backend
```

배치 모듈의 단위·통합 테스트 전체를 실행한다.

```bash
./gradlew :batch:test
```

파티션 경계, checkpoint와 Job 재시작을 포함하는 정산 Job 통합 테스트만 실행한다. 구현 후에도 테스트 클래스 이름은 기존 `DailySellerSettlementJobIntegrationTest`를 유지하고 파티션 시나리오를 이 클래스에 추가한다.

```bash
./gradlew :batch:test \
  --tests 'io.github.sehako.japda.batch.settlement.DailySellerSettlementJobIntegrationTest'
```

성능 harness의 데이터 생성, 설정 검증, 자원 수집과 결과 검산 지원 코드를 실행한다. 실제 대용량 성능 테스트는 실행하지 않는다.

```bash
./gradlew :batch:performanceTestSupport
```

소규모 성능 smoke test는 10만 건으로 실행한다. 이 명령은 파티션 설정 binding, worker 실행, 결과 파일 생성과 전체 검산을 빠르게 확인하기 위한 것이며 SLA 판정에는 사용하지 않는다.

```bash
./gradlew :batch:performanceTest \
  -Djapda.performance.dataset.seller-count=1000 \
  -Djapda.performance.dataset.order-count=100000 \
  -Djapda.performance.dataset.generation-batch-size=10000 \
  -Djapda.performance.dataset.random-seed=42 \
  -Djapda.performance.dataset.gross-amount=10000 \
  -Djapda.performance.dataset.approval-time-distribution=FIXED \
  -Djapda.performance.job.settlement-date=2026-09-15 \
  -Djapda.performance.job.platform-fee-rate-bps=1000 \
  -Djapda.performance.job.timeout=10m \
  -Djapda.performance.warmup-iterations=0 \
  -Djapda.performance.measurement-iterations=1 \
  -Djapda.batch.daily-seller-settlement.worker-count=4 \
  -Djapda.batch.daily-seller-settlement.collection-partition-count=32 \
  -Djapda.batch.daily-seller-settlement.credit-partition-count=32 \
  -Djapda.batch.daily-seller-settlement.chunk-size=1000 \
  -Djapda.batch.daily-seller-settlement.page-size=1000 \
  -Djapda.batch.daily-seller-settlement.fetch-size=1000 \
  -Dspring.datasource.hikari.maximum-pool-size=8 \
  -Dspring.datasource.hikari.minimum-idle=4
```

기존 1천만 건 회귀 시나리오는 다음과 같이 실행한다. Job timeout 통과와 별개로 기존 60분 SLA와 전체 검산을 모두 통과해야 한다.

```bash
./gradlew :batch:performanceTest \
  -Djapda.performance.dataset.seller-count=100000 \
  -Djapda.performance.dataset.order-count=10000000 \
  -Djapda.performance.dataset.generation-batch-size=100000 \
  -Djapda.performance.dataset.random-seed=42 \
  -Djapda.performance.dataset.gross-amount=10000 \
  -Djapda.performance.dataset.approval-time-distribution=FIXED \
  -Djapda.performance.job.settlement-date=2026-09-15 \
  -Djapda.performance.job.platform-fee-rate-bps=1000 \
  -Djapda.performance.job.timeout=65m \
  -Djapda.performance.warmup-iterations=0 \
  -Djapda.performance.measurement-iterations=1 \
  -Djapda.batch.daily-seller-settlement.worker-count=8 \
  -Djapda.batch.daily-seller-settlement.collection-partition-count=64 \
  -Djapda.batch.daily-seller-settlement.credit-partition-count=64 \
  -Djapda.batch.daily-seller-settlement.chunk-size=1000 \
  -Djapda.batch.daily-seller-settlement.page-size=1000 \
  -Djapda.batch.daily-seller-settlement.fetch-size=1000 \
  -Dspring.datasource.hikari.maximum-pool-size=12 \
  -Dspring.datasource.hikari.minimum-idle=8
```

목표 1억 건 SLA 시나리오는 다음과 같이 실행한다. 실행 전 성능 harness가 출력하는 예상 데이터·인덱스 크기와 호스트 여유 디스크를 확인한다.

```bash
./gradlew :batch:performanceTest \
  -Djapda.performance.dataset.seller-count=1000000 \
  -Djapda.performance.dataset.order-count=100000000 \
  -Djapda.performance.dataset.generation-batch-size=100000 \
  -Djapda.performance.dataset.random-seed=42 \
  -Djapda.performance.dataset.gross-amount=10000 \
  -Djapda.performance.dataset.approval-time-distribution=FIXED \
  -Djapda.performance.job.settlement-date=2026-09-15 \
  -Djapda.performance.job.platform-fee-rate-bps=1000 \
  -Djapda.performance.job.timeout=65m \
  -Djapda.performance.warmup-iterations=0 \
  -Djapda.performance.measurement-iterations=1 \
  -Djapda.performance.resource-sampling-interval-ms=1000 \
  -Djapda.batch.daily-seller-settlement.worker-count=8 \
  -Djapda.batch.daily-seller-settlement.collection-partition-count=64 \
  -Djapda.batch.daily-seller-settlement.credit-partition-count=64 \
  -Djapda.batch.daily-seller-settlement.chunk-size=1000 \
  -Djapda.batch.daily-seller-settlement.page-size=1000 \
  -Djapda.batch.daily-seller-settlement.fetch-size=1000 \
  -Dspring.datasource.hikari.maximum-pool-size=12 \
  -Dspring.datasource.hikari.minimum-idle=8
```

`UNIFORM` 분포는 같은 명령에서 다음 property만 변경해 별도 실행한다.

```bash
-Djapda.performance.dataset.approval-time-distribution=UNIFORM
```

구현 시 `performanceTest` task가 기존 batch tuning property와 함께 `worker-count`, `collection-partition-count`, `credit-partition-count`를 fork된 테스트 JVM에 전달하도록 허용 목록을 확장한다. 전달된 실제 값은 `scenario.properties`와 Gradle 실행 요약에 기록한다. 이 변경 전에는 위 세 property가 성능 테스트 JVM에 전달되지 않으므로 파티션 성능 명령을 유효한 구현 검증으로 간주하지 않는다.

모든 성능 실행은 `apps/backend/batch/build/reports/performance/daily-seller-settlement/{run-id}/` 아래에 독립 결과를 남긴다. 실패한 실행도 출력된 `run-id`의 `scenario.properties`, `summary.properties`, 단계별 측정값과 자원 표본을 보존한다.

## 예상 변경 범위

- `DailySellerSettlementJobConfiguration`의 Job flow, manager·worker Step과 task executor 구성
- `DailySellerSettlementBatchProperties`의 worker·partition 설정
- collection과 credit 파티션 계획을 생성·복원하는 배치 구성 요소
- `SettlementPaymentKeysetReader`의 파티션 범위와 ID cursor 계약
- 판매자별 정산 ID reader의 파티션 범위 계약
- `SellerWalletCreditWriter`의 공통 실행 행 잠금 제거와 worker 시작 상태 검증
- 결제 파티션 조회 인덱스를 추가하는 Flyway migration
- Spring Batch partition·재시작 통합 테스트와 장애 주입 테스트
- 성능 시나리오, 새 partition tuning property 전달 허용 목록, 파티션별 측정값과 1억 건 SLA 보고서
- 신규 ADR, ADR 목록과 `docs/architecture/backend.md`의 배치 실행 모델
- 기존 수집·지갑 입금·대용량 최적화·성능 테스트 명세에서 이 문서로 대체되는 단일 thread 관련 절

새 dependency는 추가하지 않는다. Spring Batch의 local partitioning과 기존 bounded task executor, JDBC와 PostgreSQL 기능만 사용한다.

## 대안과 트레이드오프

### 공유 reader 기반 멀티스레드 Step

하나의 Step에 task executor만 연결하면 변경량은 작지만 reader cursor와 페이지 상태를 worker가 공유하게 된다. reader thread safety, 처리 순서, checkpoint와 실패 재시작 경계를 명확히 유지하기 어렵다. 현재 명시적 keyset reader의 단일 cursor 계약과 맞지 않으므로 선택하지 않는다.

### PostgreSQL `INSERT ... SELECT`로 전체 수집

애플리케이션 왕복을 줄여 더 높은 처리량을 낼 가능성이 있다. 그러나 기존 processor의 건별 주문·판매·금액·지급 대상 오류 분류, chunk checkpoint와 실패 지점 재시작 계약을 SQL 집합 처리에 맞게 다시 정의해야 한다. 이번 변경은 기존 검증 계약을 유지하는 것을 우선하므로 선택하지 않는다.

### remote partitioning과 멀티 JVM worker

배치 애플리케이션을 수평 확장할 수 있지만 worker 배포, 메시지 전달, 중복 delivery, worker heartbeat와 운영 복구 체계가 필요하다. 현재 저장소에는 운영 Job launcher와 worker 인프라도 없으므로 1차 설계로 선택하지 않는다. 단일 JVM이 CPU나 connection 수의 한계가 된다는 측정 근거가 생기면 local partition worker 계약을 유지한 채 별도 ADR로 검토한다.

### 판매자별 확정까지 애플리케이션 파티셔닝

상세 수집 파티션은 결제 ID 기준이고 확정 결과는 판매자 기준이므로 경계가 일치하지 않는다. 부분 집계를 안전하게 재시작하려면 영구 중간 테이블과 merge 상태가 필요하다. 현재 PostgreSQL 집합 집계가 JVM 메모리 적재 없이 동작하므로 먼저 단일 확정의 1억 건 실행계획과 시간을 측정한다.

## 범위 제외

- 멀티 JVM remote partitioning과 별도 worker 애플리케이션
- 메시지 브로커, 분산 queue와 외부 workflow engine
- 운영 scheduler, 수동 실행 API, 관리자 화면과 알림 채널
- 늦게 승인된 결제, 취소·환불·역분개와 정정 정산 정책
- 정산 업무 테이블의 보존·삭제·archive 정책
- 실제 계좌 지급과 외부 금융 시스템 연동
- 확정 단계의 영구 부분 집계와 merge
- 근거 없이 chunk·page·fetch 크기나 PostgreSQL 설정을 확대하는 조정
- 파티션 실패 시 이미 commit된 업무 데이터를 삭제하는 자동 보상
