# 판매자 일일 정산 Job의 검산·확정 단계

## 목적과 완료 조건

[`dailySellerSettlementJob`의 대상 수집 단계](daily-seller-settlement-job-collection-stage.md)에 판매자별 검산·정산 확정 단계를 연결한다. 이 단계는 수집이 완료된 구매별 `settlement_details`를 판매자별로 합산하고, 정산 실행에 스냅샷한 플랫폼 수수료율을 판매자별 총 결제금액에 한 번 적용한다. 계산 결과와 수집 결과 전체를 검산한 뒤 판매자별 정산을 확정하여 후속 지갑 입금 단계가 변경되지 않는 지급 근거를 읽을 수 있게 한다.

이번 작업은 [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 `수집 → 검산·확정 → 지갑 입금` 흐름 중 검산·확정 단계만 구현하는 증분 범위다. 지갑과 원장 항목을 만들거나 잔액을 변경하지 않으며, 지갑 입금 단계가 연결되기 전에는 확정 결과를 실제 지급 완료로 해석하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 수집 완료 뒤 `confirmSellerSettlementsStep`이 실행된다.
- 한 정산 실행의 상세를 판매자별로 정확히 한 행의 정산 결과로 확정한다.
- 판매자별 총 결제금액에 플랫폼 수수료율을 한 번 적용하고 원 미만 금액을 버린다.
- 판매자별 지급 대상이 하나인지 검증하고 서로 다른 지급 대상이 섞이면 Job을 실패시킨다.
- 판매자별 결과를 다시 합산한 건수와 총 결제금액이 수집 완료 집계와 일치해야 한다.
- 확정 결과 저장과 `SettlementRun`의 확정 상태 전환을 하나의 transaction으로 처리한다.
- 업무 transaction 반영 후 Spring Batch 메타데이터 갱신 전에 재실행되어도 중복 확정하지 않는다.
- 수집 대상이 없는 정산일도 판매자별 결과 없이 정상 확정한다.
- 지갑 생성·입금, `LedgerEntry` 생성, 최종 정산 실행 완료와 운영 자동 실행은 이번 범위에 포함하지 않는다.

## Job flow와 실행 계약

검산·확정 단계의 기술 Step 이름은 다음과 같이 고정한다.

- 검산·확정 Step: `confirmSellerSettlementsStep`

이번 증분 범위의 Job flow는 다음과 같다.

```text
prepareSettlementRunStep
→ collectSettlementDetailsStep
→ completeSettlementCollectionStep
→ confirmSellerSettlementsStep
```

`confirmSellerSettlementsStep`은 하나의 tasklet Step으로 구성한다. 판매자별 결과의 일부만 확정된 상태를 허용하지 않고, 모든 판매자 계산·검산과 실행 상태 전환을 하나의 transaction에서 성공하거나 함께 rollback한다. 판매자 수에 비례하는 application 메모리 집계나 판매자별 반복 조회 대신 PostgreSQL의 집합 연산으로 집계한다.

Job 이름과 parameter 계약은 기존 수집 단계와 동일하다. `settlementDate`만 JobInstance를 식별하고, `platformFeeRateBps`는 최초 실행에서 저장한 비식별 parameter다. 이번 단계는 JobParameter를 다시 계산 근거로 사용하지 않고 `settlement_runs.platform_fee_rate_bps`에 저장된 스냅샷만 사용한다.

확정 Step이 추가되기 전에 이미 `COMPLETED`된 동일 JobInstance를 새로운 Job 정의로 다시 실행하여 확정 단계를 소급 수행하지 않는다. 기존 수집 단계 명세에 따라 이 버전의 Job은 운영 정산에 사용하지 않았다는 전제를 유지하며, 개발·테스트 중 생성된 기존 완료 실행은 다른 과거 정산일로 새 JobInstance를 실행한다. 임의의 식별 parameter나 별도 보정 Job으로 Spring Batch의 완료 제약을 우회하는 기능은 추가하지 않는다.

후속 작업에서 Job flow는 다음 방향으로 확장한다.

```text
수집 완료
→ 판매자별 검산·정산 확정
→ 판매자 지갑 가상 입금
→ 정산 실행 완료
```

후속 Step의 정확한 이름과 상태 전이는 별도 명세에서 확정한다.

## 업무 데이터 모델

API root project의 다음 Flyway migration에서 판매자별 정산 테이블과 확정 상태를 추가한다.

```text
apps/backend/src/main/resources/db/migration/V13__create_seller_settlements.sql
```

구현 시 이미 다른 `V13` migration이 존재하면 기존 파일을 변경하거나 버전을 충돌시키지 않고 다음 사용 가능한 버전을 사용한다. 기존 `V12` migration은 수정하지 않는다.

### `settlement_runs`

기존 `status` check constraint의 허용값에 `CONFIRMED`를 새 migration으로 추가한다.

| status | 의미 |
| --- | --- |
| `COLLECTING` | 구매별 상세 수집 중이거나 수집 실패 후 재시작 대기 상태 |
| `COLLECTED` | 구매별 상세 수집과 수집 집계 완료 상태 |
| `CONFIRMED` | 판매자별 계산과 전체 검산이 완료된 상태 |

다음 컬럼을 추가한다.

| column | type | 규칙 |
| --- | --- | --- |
| `confirmation_completed_at` | `TIMESTAMP WITH TIME ZONE` | nullable, `CONFIRMED` 전환 시 설정 |

기술적인 실패 상태는 Spring Batch 메타데이터로 관리하므로 별도의 `CONFIRMATION_FAILED` 상태를 추가하지 않는다. 확정 transaction이 실패하면 `SettlementRun`은 `COLLECTED`로 남는다.

### `seller_settlements`

판매자 한 명의 정산 실행별 확정 결과를 저장한다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `settlement_run_id` | `BIGINT` | not null, `settlement_runs.id` foreign key |
| `seller_id` | `BIGINT` | not null, 양수 |
| `recipient_user_id` | `BIGINT` | not null, `users.id` foreign key |
| `detail_count` | `BIGINT` | not null, 양수 |
| `gross_amount` | `BIGINT` | not null, 양수 |
| `platform_fee_amount` | `BIGINT` | not null, `0` 이상 |
| `net_amount` | `BIGINT` | not null, `0` 이상 |
| `status` | `VARCHAR(30)` | not null, 이번 범위에서는 `CONFIRMED` |
| `confirmed_at` | `TIMESTAMP WITH TIME ZONE` | not null |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |

`(settlement_run_id, seller_id)`에 unique 제약을 두어 한 실행에서 판매자별 정산 결과가 하나만 존재하도록 한다. `gross_amount = platform_fee_amount + net_amount` check constraint를 두며, 플랫폼 수수료율이 `10000` basis point이면 `net_amount`가 `0`일 수 있다.

후속 지갑 입금 Step이 한 실행의 확정 결과를 안정적으로 순회할 수 있도록 `(settlement_run_id, id)` 인덱스를 둔다. 향후 `LedgerEntry`의 정산 입금 source ID에는 `seller_settlements.id`를 사용할 수 있지만, source 종류와 원장 스키마 및 입금 상태 컬럼은 지갑 입금 단계 명세에서 확정한다. 이번 migration에서 사용되지 않는 지갑·원장 컬럼이나 후속 상태를 미리 추가하지 않는다.

판매자가 없는 정산 실행에는 `seller_settlements` 행을 만들지 않는다. 0원 판매자별 정산 행도 만들지 않는다.

## 판매자별 계산 규칙

집계 입력은 `settlement_run_id`가 현재 실행과 같은 `settlement_details`로 한정한다. 판매자별로 다음 값을 계산한다.

```text
detailCount = COUNT(*)
grossAmount = SUM(gross_amount)
platformFeeAmount = FLOOR(grossAmount × platformFeeRateBps ÷ 10_000)
netAmount = grossAmount - platformFeeAmount
```

플랫폼 수수료는 각 구매별 금액에 나누어 적용하지 않고 판매자별 `grossAmount`에 한 번 적용한다. 원 미만은 버리며, 계산 과정에서 `BIGINT` 곱셈 overflow가 발생하지 않도록 PostgreSQL `NUMERIC`으로 승격하여 곱셈과 `FLOOR`를 수행한 뒤 결과 범위를 검증하고 `BIGINT`로 저장한다.

`recipient_user_id`는 현재 사용자 매핑을 다시 조회하지 않고 수집 당시 `settlement_details`에 저장한 값을 사용한다. 같은 `settlement_run_id`와 `seller_id`에 둘 이상의 `recipient_user_id`가 존재하면 여러 정산으로 나누거나 임의의 값을 선택하지 않고 데이터 오류로 Job을 실패시킨다.

## 검산·확정 transaction

`confirmSellerSettlementsStep`은 Job execution context의 `settlementRunId`로 하나의 transaction을 시작하고, 같은 PostgreSQL connection에서 `ON COMMIT DROP` 임시 판매자 집계를 만든다. 임시 테이블은 `seller_id` primary key, `min_recipient_user_id`, `max_recipient_user_id`, `detail_count`, `gross_amount`를 가지며 `INSERT ... SELECT ... GROUP BY seller_id`로 한 번만 채운다.

1. `settlement_runs` 행 하나만 잠금 조회하고 상태가 `COLLECTED` 또는 `CONFIRMED`인지 확인한다.
2. 임시 집계의 전체 건수·금액을 `collected_count`, `collected_amount`와 비교하고, 최소·최대 지급 대상이 같은지와 금액 범위를 검증한다.
3. 상태가 `COLLECTED`이면 기존 결과가 없는지 확인한 뒤 임시 집계에서 `seller_settlements`를 집합 삽입한다.
4. 임시 집계와 저장 결과의 판매자 집합, 지급 대상, 상세 건수, 총액, 수수료, 순액, 상태와 확정 시각을 비교한다.
5. 상태가 `COLLECTED`이면 모든 검증 뒤 `settlement_runs.status`를 `CONFIRMED`로 변경하고 `confirmation_completed_at`을 기록한다. `CONFIRMED`이면 삽입과 상태 전환 없이 같은 비교만 수행한다.

`settlement_details`와 `seller_settlements`의 전체 ID를 조회하거나 `FOR UPDATE`로 잠그지 않는다. 수집 완료 뒤 정산 상세를 변경하는 지원 경로가 없고, 실행 행 잠금·JobInstance 식별·`(settlement_run_id, seller_id)` unique 제약이 확정 작업을 직렬화한다. 임시 테이블은 transaction에 결합된 connection에서만 사용하며 다른 connection에서 조회하지 않는다.

대상이 없으면 상세와 판매자별 결과의 건수·합계를 모두 `0`으로 해석하고 `seller_settlements`를 비워 둔 채 `CONFIRMED`로 전환한다. `SUM`의 `NULL` 결과는 검산 시 `0`으로 정규화한다.

정산 상세나 판매자별 정산 행을 이후 단계에서 수정하여 검산값을 맞추지 않는다. 불일치가 발견되면 원인을 숨기지 않고 transaction 전체를 rollback하여 운영자가 원본 데이터를 조사할 수 있게 한다.

## 재시작과 멱등성

확정 transaction 도중 오류가 발생하면 판매자별 결과 insert와 실행 상태 전환이 모두 rollback된다. 동일 JobInstance를 재시작하면 완료된 수집 Step은 건너뛰고 확정 Step을 다시 처음부터 실행한다.

업무 transaction은 commit됐지만 Spring Batch 메타데이터 갱신 전에 장애가 발생하면 Step이 다시 호출될 수 있다. 이 경우 `SettlementRun`이 이미 `CONFIRMED`이면 새 행을 insert하지 않고 다음 항목을 모두 재검산한다.

- 저장된 판매자별 결과의 판매자 집합과 상세의 판매자 집합이 같다.
- 각 판매자의 지급 대상, 상세 건수, 총 결제금액, 수수료와 입금 예정 금액이 계산 결과와 같다.
- 판매자별 결과의 전체 건수·금액 합계가 `SettlementRun`의 수집 집계와 같다.
- `confirmation_completed_at`과 각 결과의 `confirmed_at`이 존재한다.

모든 값이 같으면 멱등 성공으로 처리하고, 하나라도 다르면 데이터 오류로 Job을 실패시킨다. unique 충돌을 `ON CONFLICT DO NOTHING`으로 숨기거나 기존 결과를 덮어쓰지 않는다.

`SettlementRun` 상태가 `COLLECTED` 또는 `CONFIRMED`가 아니면 확정 Step을 실행하지 않는다. `COLLECTED` 상태인데 이미 `seller_settlements`가 존재하는 경우도 이전 transaction의 원자성 계약이 깨진 데이터 오류이므로 실패시킨다.

## 구성 요소와 의존성 경계

API root project는 `settlement_runs` 변경과 `seller_settlements` 생성 migration만 소유한다. API 애플리케이션에 정산 확정 service, repository 또는 Batch Step을 추가하지 않는다.

`:batch`는 다음 책임을 가진다.

- `confirmSellerSettlementsStep` 구성
- 판매자별 지급 대상 일관성 검증
- 판매자별 금액과 플랫폼 수수료 계산
- 판매자별 결과 저장과 전체 집계 검산
- `SettlementRun`의 `COLLECTED → CONFIRMED` 상태 전환
- 확정 transaction 재실행 시 결과 일치 검증

이번 범위는 JDBC와 PostgreSQL 집합 연산으로 구현한다. `:batch`는 API root project의 JPA Entity, repository 또는 application service를 참조하지 않으며 새로운 영속성 framework를 추가하지 않는다.

`:modules:ledger`에는 아직 지갑·원장 변경이 없으므로 이번 단계의 배치 전용 집계 타입이나 JDBC repository를 추가하지 않는다. 지갑 입금 단계에서 API와 배치가 실제로 공유할 원장 application 기능의 경계를 별도 설계한다.

## 오류 처리와 관측성

다음 오류는 모두 Job 실패로 처리한다.

- 존재하지 않는 `settlementRunId`
- 확정을 시작할 수 없는 `SettlementRun` 상태
- 수집 집계와 실제 상세의 건수 또는 총액 불일치
- 한 판매자의 상세에 서로 다른 `recipient_user_id`가 존재함
- 판매자별 수수료 또는 입금 예정 금액이 `BIGINT` 범위를 벗어남
- 판매자별 계산 결과와 저장 결과 불일치
- 판매자별 결과 전체와 수집 집계 불일치
- 이미 확정된 결과의 재검산 불일치
- `seller_settlements` unique 또는 다른 데이터베이스 제약 위반
- `SettlementRun` 확정 상태 전환 실패

데이터 오류를 skip하지 않고 retry도 구성하지 않는다. 확정 Step은 외부 시스템을 호출하지 않으므로 이번 범위에서 retry할 일시적 외부 오류가 없다.

로그에는 Job 이름, `settlementDate`, `settlementRunId`, JobExecution/StepExecution 식별자, 문제가 있는 `sellerId`와 오류 분류처럼 복구에 필요한 정보만 남긴다. 사용자 개인정보, 전체 금액 projection과 DB credential은 기록하지 않는다.

## 검증 전략

단위 테스트와 PostgreSQL Testcontainers 기반 통합 테스트로 다음을 검증한다.

1. 여러 구매 상세를 판매자별 한 행으로 합산한다.
2. 같은 판매자의 여러 상세에 수수료율을 개별 적용하지 않고 합계에 한 번 적용한다.
3. 원 미만 플랫폼 수수료를 버리고 `gross = fee + net`을 만족한다.
4. 수수료율 `0`에서 수수료가 `0`이고 입금 예정 금액이 총 결제금액과 같다.
5. 수수료율 `10000`에서 수수료가 총 결제금액과 같고 입금 예정 금액이 `0`이다.
6. 큰 금액 계산에서 중간 곱셈 overflow 없이 범위를 검증한다.
7. 같은 판매자의 지급 대상이 둘 이상이면 전체 확정을 rollback한다.
8. 수집 집계와 실제 상세의 건수 또는 총액이 다르면 실패한다.
9. 저장된 판매자별 건수·총액 합계가 수집 집계와 일치한다.
10. 대상이 없는 정산일은 판매자별 행 없이 `CONFIRMED`로 완료한다.
11. 확정 중 실패하면 판매자별 행과 실행 상태 변경이 모두 남지 않는다.
12. 실패한 동일 JobInstance를 재시작하면 완료된 수집 Step을 반복하지 않고 확정 Step을 다시 실행한다.
13. 업무 확정 commit과 메타데이터 갱신 사이의 재실행에서 중복 행을 만들지 않고 정상 완료한다.
14. 이미 확정된 결과가 계산 결과와 다르면 재실행을 실패시킨다.
15. `(settlement_run_id, seller_id)` unique 제약과 금액 check constraint가 잘못된 데이터를 차단한다.
16. 후속 조회용 `(settlement_run_id, id)` 인덱스와 외래 키를 생성한다.
17. API root의 전체 Flyway migration을 적용한 PostgreSQL에서 확정 Step이 실행된다.
18. 전체 backend와 batch test·build가 기존 수집 동작과 독립 실행 구조에 회귀를 만들지 않는다.

멱등 재실행 테스트는 결과를 임의로 미리 넣어 성공을 모사하는 것으로 끝내지 않는다. 확정 업무 transaction이 반영된 뒤 Spring Batch 메타데이터 완료 처리가 반영되지 않은 상황을 재현하고, 같은 JobInstance의 재시작 결과를 검증한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- `Wallet` 생성과 잔액 변경
- `LedgerEntry` 생성과 원장 source 계약
- 판매자별 정산의 지갑 입금 완료 상태
- `SettlementRun`의 지갑 입금 또는 최종 완료 상태
- 애플리케이션 내부 scheduler, 운영 Cron 또는 Kubernetes CronJob
- 운영용 수동 실행 API와 관리자 화면
- 판매자 정산 내역 조회 API와 화면
- PG 수수료, 환불 금액, 세금과 실제 계좌 지급
- 취소·환불, 역분개, 정정 정산과 늦게 확정된 결제 처리
- 확정된 판매자별 결과의 재계산·수정 기능
- 멀티 thread, partitioning과 성능 최적화
- 정산 및 Spring Batch 데이터의 보존·삭제 정책

후속 지갑 입금 단계는 `settlementRunId`로 확정된 `seller_settlements`를 읽고, 각 행의 `recipient_user_id`와 `net_amount`를 사용해 사용자 지갑 잔액과 불변 원장 항목을 같은 transaction에서 갱신한다. 해당 단계는 `seller_settlements.id` 기반 멱등성, 지갑 잠금, chunk 경계, 판매자별 입금 상태와 최종 실행 완료 조건을 별도 명세에서 결정한다.
