# 판매자 일일 정산 Job의 대상 수집 단계

## 목적과 완료 조건

판매자 일일 정산 전체를 수행할 `dailySellerSettlementJob`을 정의하고, 한국 시간 기준으로 종료된 정산일의 승인 결제를 구매별 정산 상세로 수집하는 첫 업무 단계를 구현한다. 이번 작업은 [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 `수집 → 검산·확정 → 지갑 입금` 흐름 중 수집 단계만 먼저 구현하는 증분 범위다. 별도의 수집 전용 Job을 만들지 않으며, 후속 작업은 같은 Job flow 뒤에 검산·확정 및 판매자 지갑 가상 입금 Step을 연결한다.

수집 결과는 후속 Step이 재조회할 수 있는 업무 테이블에 영속화하고, Spring Batch checkpoint를 사용해 실패한 동일 JobInstance를 재시작할 수 있어야 한다. 전체 정산 흐름이 완성되기 전에는 이 증분 버전의 Job을 운영 정산에 실행하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- `dailySellerSettlementJob`이 필수 JobParameter를 검증하고 준비, 상세 수집, 수집 완료 Step을 순서대로 실행한다.
- 종료된 한국 시간 정산일에 승인된 결제를 빠짐없이 구매별 정산 상세로 저장한다.
- 주문 금액과 결제 요청 금액, 주문 상태 및 판매자 사용자 매핑을 검증하고 데이터 오류를 발견하면 Job을 실패시킨다.
- 수집된 결제는 다른 정산 실행에 중복 포함될 수 없다.
- 실패한 같은 정산일의 JobInstance는 마지막으로 commit된 chunk 다음부터 재시작할 수 있다.
- 대상이 없는 정산일도 실행 이력과 0건·0원 결과를 남기고 정상 완료한다.
- 자동 실행, scheduler, 수수료 계산, 판매자별 검산·확정 및 지갑 입금은 이번 증분 범위에서 수행하지 않는다.

이 명세는 [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 프로젝트·데이터베이스 경계와 [배치 프로젝트 기반 명세](batch-project-and-metadata-schema-foundation.md)를 따른다. 배치는 API root project에 의존하지 않고 기존 결제·주문·판매 데이터를 JDBC projection으로 읽으며, Flyway migration은 API root project가 소유한다.

## Job 실행 계약

Job과 수집 단계 내부의 기술 Step 이름은 다음과 같이 고정한다.

- Job: `dailySellerSettlementJob`
- 준비 Step: `prepareSettlementRunStep`
- 상세 수집 Step: `collectSettlementDetailsStep`
- 수집 완료 Step: `completeSettlementCollectionStep`

이번 증분 범위의 Job flow는 `prepareSettlementRunStep → collectSettlementDetailsStep → completeSettlementCollectionStep` 순서다. 세 Step은 모두 ADR-025의 수집 단계에 속하며, 후속 검산·확정 또는 지갑 입금 단계를 의미하지 않는다. 준비와 완료 처리를 별도 tasklet Step으로 두어 실행 이력 생성이나 수집 완료 상태 전환 실패가 Job 실패로 반영되게 한다.

최종 Job flow는 다음 방향으로 확장한다. 후속 Step의 정확한 이름과 내부 구성은 각 단계의 별도 명세에서 확정한다.

```text
정산 실행 준비
→ 결제 상세 수집
→ 수집 완료
→ 판매자별 검산·정산 확정
→ 판매자 지갑 가상 입금
→ 정산 실행 완료
```

따라서 `completeSettlementCollectionStep`은 전체 Job 완료 Step이 아니라 수집 업무 단계의 완료 경계다. Spring Batch 관점에서는 이번 증분 버전이 이 Step에서 `COMPLETED`되지만, 이를 최종 정산 완료로 해석하거나 운영 정산에 사용하지 않는다.

Job은 다음 parameter를 필수로 받는다.

| parameter | 형식 | JobInstance 식별 | 계약 |
| --- | --- | --- | --- |
| `settlementDate` | ISO-8601 `YYYY-MM-DD` | 식별 | 한국 시간 기준 정산일이다. 실행 시점의 한국 날짜보다 과거여야 한다. |
| `platformFeeRateBps` | `0` 이상 `10000` 이하의 정수 | 비식별 | 실행 시작 시점의 플랫폼 수수료율이다. 이번 Step은 계산하지 않고 정산 실행에 스냅샷만 저장한다. |

`settlementDate`만 JobInstance를 식별한다. 같은 정산일의 실패한 실행은 새로운 식별 parameter를 추가하지 않고 동일 JobInstance로 재시작한다. `platformFeeRateBps`는 비식별 parameter이지만 최초 실행에서 `SettlementRun`에 저장하며, 재시작 시 입력값이 저장된 값과 다르면 데이터 오류로 실패한다.

JobParameter 검증은 DB 변경 전에 수행한다. 누락된 parameter, 잘못된 날짜 형식, 범위를 벗어난 수수료율, 오늘 또는 미래의 정산일은 Job 실행을 거부한다. 날짜 비교와 조회 구간 계산에는 고정된 `Asia/Seoul` 시간대를 사용하고 서버의 기본 시간대에 의존하지 않는다.

배치 애플리케이션의 Spring Batch 자동 Job 실행은 계속 비활성화한다. 이번 범위는 테스트와 후속 개발에서 명시적으로 실행할 수 있는 Job bean과 실행 계약만 제공하며 애플리케이션 내부 `@Scheduled`, 운영 Cron, Kubernetes CronJob, 수동 실행 HTTP API는 추가하지 않는다. 검산·확정과 지갑 입금 Step이 연결되기 전에는 운영 환경에서 이 Job을 실행하지 않는다.

## 업무 데이터 모델

새 업무 테이블은 API root project의 다음 Flyway migration에 생성한다.

```text
apps/backend/src/main/resources/db/migration/V12__create_settlement_collection_tables.sql
```

구현 시 이미 다른 `V12` migration이 존재하면 기존 파일을 변경하거나 버전을 충돌시키지 않고 다음 사용 가능한 버전을 사용한다.

### `settlement_runs`

정산일별 업무 실행과 마지막으로 완료된 업무 단계를 기록한다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `settlement_date` | `DATE` | not null, unique |
| `platform_fee_rate_bps` | `INTEGER` | not null, `0..10000` |
| `status` | `VARCHAR(30)` | not null, `COLLECTING` 또는 `COLLECTED` |
| `collected_count` | `BIGINT` | not null, 기본값 `0`, 음수 불가 |
| `collected_amount` | `BIGINT` | not null, 기본값 `0`, 음수 불가 |
| `started_at` | `TIMESTAMP WITH TIME ZONE` | not null |
| `collection_completed_at` | `TIMESTAMP WITH TIME ZONE` | nullable |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |

`settlement_date` unique 제약은 한 정산일에 하나의 업무 실행만 존재하게 한다. 최초 Step 시작 시 `COLLECTING`으로 생성하고 수집 완료 후 `COLLECTED`로 전환한다. 기술적인 실패 상태와 checkpoint는 Spring Batch 메타데이터를 기준으로 판단하므로 `FAILED` 업무 상태를 추가하지 않는다. 실패하거나 프로세스가 비정상 종료되면 `SettlementRun`은 `COLLECTING`으로 남는다.

같은 `dailySellerSettlementJob`에 후속 검산·확정 및 지갑 입금 단계가 추가될 때 `status`의 허용값은 새 migration으로 확장한다. 기존 migration과 이번 범위의 상태 의미를 소급 변경하지 않는다.

### `settlement_details`

승인 결제 한 건의 정산 근거를 변경하지 않는 스냅샷으로 저장한다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `settlement_run_id` | `BIGINT` | not null, `settlement_runs.id` foreign key |
| `payment_id` | `BIGINT` | not null, `payments.id` foreign key, unique |
| `order_id` | `BIGINT` | not null, `orders.id` foreign key |
| `sale_id` | `BIGINT` | not null, `sales.id` foreign key |
| `seller_id` | `BIGINT` | not null, 양수 |
| `recipient_user_id` | `BIGINT` | not null, `users.id` foreign key |
| `quantity` | `INTEGER` | not null, 양수 |
| `unit_price` | `BIGINT` | not null, 양수 |
| `gross_amount` | `BIGINT` | not null, 양수 |
| `payment_approved_at` | `TIMESTAMP WITH TIME ZONE` | not null |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |

`payment_id`에는 전역 unique 제약을 둔다. 한 승인 결제가 여러 정산 실행에 포함되는 것을 데이터베이스에서도 차단하며, 중복 충돌을 `ON CONFLICT DO NOTHING`으로 숨기지 않는다. 후속 Step이 한 실행의 상세를 안정적으로 조회할 수 있도록 `(settlement_run_id, id)` 인덱스를 둔다.

`seller_id`와 `recipient_user_id`를 함께 저장한다. `recipient_user_id`는 수집 시점의 `seller_principal_identities` 매핑을 스냅샷한 값이며, 이후 매핑이 변경되더라도 해당 정산 실행의 지급 대상은 바뀌지 않는다. 이번 Step은 사용자 지갑의 존재 여부를 조회하거나 지갑을 생성하지 않는다.

## 수집 데이터 흐름

수집 단계 전체를 단일 partition·단일 thread로 실행한다. `prepareSettlementRunStep`과 `completeSettlementCollectionStep`은 각각 하나의 transaction으로 실행하는 tasklet Step이고, `collectSettlementDetailsStep`은 chunk 기반 `ItemReader → ItemProcessor → ItemWriter`로 구성한다. chunk size와 reader page size의 초기값은 모두 `100`으로 고정한다. 외부 설정으로 성능 값을 미리 노출하지 않는다.

### Reader

Step-scoped JDBC paging reader가 `payments`를 기준 테이블로 사용한다. 조회 범위는 `settlementDate`의 한국 시간 시작 이상, 다음 날 한국 시간 시작 미만으로 계산한 두 `Instant`다.

```text
settlementDate=2026-09-15
시작: 2026-09-15T00:00:00+09:00 이상
종료: 2026-09-16T00:00:00+09:00 미만
```

조회 대상의 필수 조건은 다음과 같다.

- `payments.status = 'APPROVED'`
- `payments.approved_at >= 시작 Instant`
- `payments.approved_at < 종료 Instant`

Reader는 `payments → orders → sales → seller_principal_identities`를 JDBC projection으로 조회한다. 대상 결제가 join 누락으로 조용히 제외되지 않도록 검증 대상 관계는 `LEFT JOIN`하고 nullable projection으로 읽는다. 정렬 키는 `payments.approved_at ASC, payments.id ASC`로 고정한다. 두 값을 함께 사용해 동일한 승인 시각에도 순서를 유일하게 만들고 Spring Batch execution context에 reader 상태를 저장한다.

과거 정산일만 실행할 수 있고 승인 시각은 승인 완료 후 변경되지 않으므로 실행 중 paging 대상이 새로 추가되거나 정렬 순서가 바뀌지 않는 것을 전제로 한다.

### Processor

Processor는 JDBC projection을 `SettlementDetail` 저장 command로 변환하면서 다음 조건을 검증한다.

- 연결된 주문과 판매가 존재한다.
- 주문 상태가 `PAID`다.
- 주문 수량, 단가와 총액이 양수다.
- `orders.total_price`와 `payments.requested_amount`가 같다.
- `seller_principal_identities`에서 판매자의 `userId`를 찾을 수 있다.

정산 원금인 `gross_amount`에는 구매 당시 스냅샷인 `orders.total_price`를 사용한다. `orders.quantity`와 `orders.unit_price`도 함께 저장한다. 한 조건이라도 충족하지 못하면 해당 결제의 `paymentId`와 오류 종류를 포함한 업무 예외를 발생시킨다. 결제 금액, 사용자 정보와 인증 정보 전체를 로그에 출력하지 않는다.

Processor는 `null`을 반환해 항목을 필터링하지 않는다. 데이터 오류에 대한 skip과 retry도 구성하지 않으므로 하나의 잘못된 항목이 해당 chunk를 rollback하고 Step을 실패시킨다.

### Writer

JDBC batch writer가 처리 결과를 `settlement_details`에 insert한다. detail insert와 해당 chunk의 Spring Batch checkpoint 갱신은 같은 데이터베이스 transaction 경계에서 commit한다. unique 충돌이나 데이터베이스 제약 위반은 그대로 Step 실패로 처리한다.

## `SettlementRun` 생명주기와 재시작

별도 JDBC repository와 준비·완료 tasklet이 `SettlementRun`의 생명주기를 관리한다. listener의 예외 전파에 업무 완료 여부를 의존하지 않는다.

`prepareSettlementRunStep`은 다음 순서로 처리한다.

1. `settlementDate`로 `SettlementRun`을 조회한다.
2. 최초 실행이면 수수료율을 스냅샷하고 `COLLECTING` 상태로 생성한다.
3. 재시작이면 기존 상태가 `COLLECTING` 또는 `COLLECTED`인지 확인한다. `COLLECTED`는 완료 Step의 업무 transaction 반영 후 기술 메타데이터 처리가 끝나지 않아 재실행되는 경우에만 허용한다.
4. 재시작 parameter의 수수료율이 저장된 스냅샷과 같은지 확인한다.
5. `settlementRunId`만 Step execution context에 저장한다.

`prepareSettlementRunStep`에는 `allowStartIfComplete=true`를 적용해 Job 재시작 때마다 기존 실행과 수수료율을 다시 검증한다. 이후 Step에서도 사용할 수 있도록 `settlementRunId`를 Job execution context로 승격한다. 업무 상세 목록은 execution context에 저장하지 않는다. 후속 Step은 `settlementRunId`로 `settlement_details`를 다시 읽는다.

`collectSettlementDetailsStep`이 정상적으로 모든 항목을 처리한 뒤에만 `completeSettlementCollectionStep`을 실행한다. 완료 Step은 저장된 detail을 `COUNT(*)`, `SUM(gross_amount)`로 다시 집계하고, 집계값을 `collected_count`, `collected_amount`에 기록하며 `collection_completed_at`을 설정한 뒤 `COLLECTED`로 전환한다. 대상이 없으면 각각 `0`, `0`으로 기록하고 정상 완료한다. 집계와 상태 전환은 완료 Step의 단일 transaction에서 처리하며 실패하면 Job도 실패한다.

일부 chunk가 commit된 뒤 상세 수집이 실패하면 저장된 detail은 유지되고 현재 chunk만 rollback된다. `SettlementRun`은 `COLLECTING`, Spring Batch StepExecution은 `FAILED`로 남는다. 동일한 `settlementDate`의 JobInstance를 재시작하면 준비 Step이 기존 실행을 다시 검증한 뒤 Spring Batch가 상세 수집 Step의 마지막 checkpoint 이후부터 Reader를 재개하고 기존 `SettlementRun`과 commit된 detail을 이어서 사용한다.

상세 수집 Step 완료 후 완료 Step만 실패하면 재시작 시 준비 Step을 다시 실행하고 완료된 상세 수집 Step은 건너뛴 뒤 완료 Step만 다시 실행한다. 업무 transaction은 commit됐지만 Spring Batch 메타데이터 갱신 전에 장애가 발생한 경우에도 안전하도록 완료 Step은 멱등하게 동작한다. 이미 `COLLECTED`이면 저장된 집계값과 detail 재집계 결과가 같은지 확인하고 성공하며, 값이 다르면 데이터 오류로 실패한다.

완료된 동일 정산일은 Spring Batch의 완료된 JobInstance 규칙과 `settlement_runs.settlement_date` unique 제약으로 다시 수집하지 못하게 한다. 운영자가 과거 완료 결과를 임의로 다시 만드는 기능은 이번 범위에 포함하지 않는다.

## 구성 요소와 의존성 경계

API root project는 업무 테이블 migration만 소유한다. 정산 수집을 이유로 API 애플리케이션에 Job, reader, processor 또는 writer를 추가하지 않는다.

`:batch`는 다음 책임을 가진다.

- Job과 Step 구성
- JobParameter 검증 및 한국 시간 조회 범위 계산
- 정산 실행 준비·완료 tasklet
- 결제 수집 JDBC projection과 paging reader
- 수집 항목 검증 processor
- 정산 상세 JDBC writer
- `SettlementRun` JDBC repository

`:batch`는 API root project의 JPA Entity, repository, application service 또는 source set을 참조하지 않는다. 기존 Spring Batch, Spring JDBC와 PostgreSQL 의존성으로 구현하며 새로운 영속성 framework를 추가하지 않는다.

`:modules:ledger`에는 실행 framework와 무관하고 향후 API와 배치가 공유할 필요가 있는 정산 상태 및 값 타입만 둔다. Spring Batch, JDBC, JPA bean이나 배치 전용 projection은 원장 모듈로 이동하지 않는다. 실제로 둘 이상의 실행 주체가 공유하지 않는 배치 내부 타입은 `:batch`에 유지해 빈 추상화나 범용 repository를 만들지 않는다.

## 오류 처리와 관측성

다음 오류는 모두 Job 실패로 처리한다.

- JobParameter 계약 위반
- 기존 `SettlementRun`과 재시작 수수료율 불일치
- 주문·판매 관계 누락
- 승인 결제에 연결된 주문이 `PAID`가 아님
- 주문 금액과 결제 요청 금액 불일치
- 판매자 사용자 매핑 누락
- 정산 상세 unique 또는 다른 데이터베이스 제약 위반
- `SettlementRun` 최종 집계 또는 상태 전환 실패
- 이미 완료된 `SettlementRun`의 저장 집계와 detail 재집계 결과 불일치

업무 오류를 skip하지 않고, 일시적 오류에 대한 임의 retry도 추가하지 않는다. Spring Batch와 JDBC의 기본 예외 변환 및 실행 메타데이터로 실패 원인을 확인한다. 별도 실패 상세 테이블, 알림 채널과 운영 dashboard는 추가하지 않는다.

로그에는 Job 이름, `settlementDate`, JobExecution/StepExecution 식별자, 문제의 `paymentId`와 오류 분류처럼 복구에 필요한 식별 정보만 남긴다. DB credential, 결제 key, 사용자 개인정보 및 전체 금액 projection은 기록하지 않는다.

## 검증 전략

단위 테스트와 PostgreSQL Testcontainers 기반 통합 테스트로 다음을 검증한다.

1. `settlementDate`와 `platformFeeRateBps` 누락, 형식 및 범위 오류를 거부한다.
2. 오늘과 미래의 한국 날짜를 거부하고 과거 날짜를 허용한다.
3. 한국 시간 정산일 시작에 승인된 결제는 포함하고 다음 날 시작 시각의 결제는 제외한다.
4. `APPROVED`가 아닌 결제는 수집하지 않는다.
5. 정상 결제의 주문·판매·지급 대상 스냅샷을 detail에 저장한다.
6. 주문 상태, 금액 일치, 판매 관계와 판매자 사용자 매핑 검증 실패가 Job을 실패시키고 현재 chunk를 rollback한다.
7. 대상이 없는 정산일도 `COLLECTED`, 0건, 0원으로 완료한다.
8. 정상 완료 시 detail의 실제 건수·합계와 `SettlementRun` 집계값이 일치한다.
9. `paymentId` unique 제약이 다른 실행의 중복 수집을 차단한다.
10. 여러 chunk 중간에 발생한 실패에서 이미 commit된 detail은 유지되고, 같은 JobInstance 재시작 시 나머지만 저장된다.
11. 재시작 시 다른 `platformFeeRateBps`를 전달하면 실패한다.
12. 상세 수집 완료 후 완료 Step이 실패하면 재시작 시 상세를 다시 수집하지 않고 완료 Step만 재실행한다.
13. 완료 Step의 업무 commit과 메타데이터 갱신 사이 재실행에서도 집계가 중복되거나 달라지지 않는다.
14. 완료된 같은 정산일의 JobInstance를 다시 실행할 수 없다.
15. API root의 전체 Flyway migration을 적용한 PostgreSQL에서 Job이 실행되고, batch가 migration이나 schema 자동 초기화를 수행하지 않는다.
16. 전체 backend와 batch test·build가 기존 API 동작과 독립 실행 구조에 회귀를 만들지 않는다.

재시작 테스트는 detail을 미리 넣어 성공을 모사하지 않는다. 실제 chunk 처리 중 의도적으로 실패시켜 Spring Batch metadata와 업무 데이터가 함께 checkpoint되는지를 검증한 뒤 동일 JobInstance를 재시작한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- 애플리케이션 내부 scheduler, 운영 Cron 또는 Kubernetes CronJob
- 운영용 수동 실행 API와 관리자 화면
- 수수료 계산과 판매자별 합산
- 검산·확정 Step과 `SellerSettlement` 모델
- 지갑 생성·검증·입금과 `LedgerEntry` 기록
- 취소·환불, 역분개, 정정 정산과 늦게 확정된 결제 처리
- skip, retry 및 실패 항목 별도 보관
- 멀티 thread, partitioning과 성능 최적화
- 완료된 정산일의 재수집·재처리 기능
- Spring Batch 메타데이터와 정산 상세의 보존·삭제 정책

후속 검산·확정 Step은 같은 `dailySellerSettlementJob` 안에서 `settlementRunId`로 `settlement_details`를 읽고, 이번 실행에서 스냅샷한 `platform_fee_rate_bps`를 판매자별 총 결제금액에 한 번 적용한다. 그 뒤 판매자 지갑 가상 입금 Step을 연결한다. 후속 단계의 테이블, 계산 규칙과 상태 전이는 별도 명세에서 결정한다.
