# 판매자 일일 정산 배치 설계

## 목적과 완료 조건

결제 완료된 구매를 정산일별로 수집하고 판매자별 총 결제금액과 플랫폼 수수료를 계산한 뒤, 판매자와 연결된 사용자의 플랫폼 지갑에 순정산금을 가상 입금한다. 초기 구현은 단일 파티션으로 정확성, 멱등성, 실패 후 재시작 가능성을 우선하며 이후 동일한 업무 규칙으로 멀티 파티션 성능을 비교할 수 있는 기준선을 제공한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 별도 Spring Boot 배치 애플리케이션이 CLI에서 `settlementDate`를 받아 한 번 실행된 뒤 종료된다.
- 한국 시간 기준 정산일에 승인된 `Payment.APPROVED`와 `Order.PAID` 구매만 수집한다.
- 구매별 정산 근거를 보존하고 판매자별·정산일별 총 결제금액을 정확히 합산한다.
- 실행 시작 시점의 플랫폼 수수료율을 스냅샷으로 저장하고 판매자별 합산 금액에 한 번 적용한다.
- 판매자와 연결된 `users.id`를 입금 대상자로 확정하고 사용자별 플랫폼 지갑에 순정산금을 입금한다.
- 동일 결제는 한 번만 정산하고 동일 판매자 정산은 한 번만 입금한다.
- 각 Step이 중간에 실패해도 마지막 성공 chunk 이후부터 안전하게 재시작한다.
- 수집 건수와 금액, 수수료, 순정산금을 검산한 뒤에만 원장 입금을 시작한다.
- PostgreSQL Testcontainers 통합 테스트로 정산, 원장, 중복 실행과 재시작을 검증한다.
- 단일 파티션의 처리량과 자원 사용량을 재현 가능한 조건으로 기록한다.

## 범위와 전제

[백엔드 아키텍처](../../../architecture/backend.md), [백엔드 영속성 결정](../../../architecture/decisions/ADR-003-backend-persistence-with-postgresql-jpa-flyway.md), [구매자·판매자 인증 주체 연결 결정](../../../architecture/decisions/ADR-023-buyer-seller-principal-identity-and-cookie-csrf.md), [결제 시도와 주문 예약 결정](../../../architecture/decisions/ADR-019-payment-attempt-and-reservation-consistency.md)을 출발점으로 한다.

현재 결제 승인은 검증된 토스페이먼츠 `DONE`에만 `Payment.APPROVED`와 `Order.PAID`를 같은 트랜잭션에서 기록한다. 판매 일정은 `sellerId`, 주문은 `saleId`, 결제는 `orderId`로 서로를 ID 참조한다. `seller_principal_identities`는 `seller_id`와 `user_id`의 일대일 연결을 보장한다.

포함 범위는 사용자 플랫폼 지갑, 불변 원장, 판매자별 일일 정산 데이터, 단일 파티션 Spring Batch Job, CLI 실행, Flyway migration, 통합 테스트와 성능 기준선 기록이다. 실제 은행 계좌 지급은 수행하지 않는다.

다음은 제외한다.

- 멀티 파티션과 원격 파티셔닝
- 1억 건 테스트 데이터 생성 도구와 특정 처리 건수 달성 보장
- 결제 취소·환불, 역분개와 정정 정산
- 복식부기와 플랫폼 회계 계정
- Redis와 메시지 브로커
- 애플리케이션 내부 scheduler와 외부 스케줄러 구성
- 관리자용 정산 API와 UI
- 실제 판매자 계좌 지급과 토스페이먼츠 지급대행
- 완료된 정산일에 뒤늦게 확정된 결제의 추가 정산

마지막 항목은 토스페이먼츠 웹훅이나 취소·환불을 도입할 때 별도 설계한다. 초기 배치는 정산일의 결제가 모두 확정된 뒤 실행한다.

## 모듈과 실행 구조

`apps/backend`를 Gradle 멀티 프로젝트의 루트로 유지하고 기존 root project는 API 애플리케이션으로 사용한다. 필요한 하위 모듈만 추가한다.

```text
apps/backend/
├── src/                         # 기존 API 애플리케이션
├── batch/
│   └── src/                     # 별도 배치 Boot 애플리케이션과 settlement 영역
├── modules/
│   └── ledger/
│       └── src/                 # 사용자 지갑·원장과 입금 유스케이스
├── build.gradle.kts
└── settings.gradle.kts
```

빌드와 의존성 방향은 다음과 같다.

```text
API root project ────────→ :modules:ledger
:batch ─────────────────→ :modules:ledger
:batch ──────X──────────→ API presentation/application
```

API와 배치는 각각 독립된 Spring Boot 실행 파일과 JVM 프로세스로 실행한다. `:modules:ledger`는 실행 파일이 아닌 plain jar다. 배치는 API를 HTTP로 호출하지 않으며 두 애플리케이션은 동일한 PostgreSQL을 사용한다.

정산 대상 조회는 배치 모듈의 JDBC 기반 전용 Reader가 담당한다. 배치가 기존 API root project에 의존하거나 주문·결제 JPA Entity를 공유하지 않는다. 지갑 입금은 `:modules:ledger`의 application 유스케이스를 호출한다. 이 유스케이스는 Spring Web 타입에 의존하지 않는다.

## 데이터베이스 migration 소유권

Flyway migration 파일과 실행 책임은 기존 API root project에 유지한다. 지갑, 원장, 정산과 Spring Batch 메타데이터 테이블 migration도 `apps/backend/src/main/resources/db/migration`에 추가한다.

배치 애플리케이션은 Flyway와 Spring Batch의 자동 스키마 초기화를 비활성화한다. 시작 시 필수 업무 테이블과 Spring Batch 메타데이터 테이블이 없거나 기대한 스키마와 맞지 않으면 즉시 실패한다. 배포와 실행 순서는 API migration 완료 후 배치 실행이다.

별도 migration 애플리케이션이나 공통 migration 모듈은 두 애플리케이션의 배포 독립성이 실제로 필요해질 때 검토한다.

## 사용자 지갑과 원장 모델

`Wallet`은 판매자 전용 지갑이 아니라 `users.id`에 귀속되는 플랫폼 공용 지갑이다. 사용자가 구매자와 판매자 역할을 함께 가져도 지갑은 하나다. 사용자가 최초로 잔액 변경을 일으킬 때 `userId` 기준으로 멱등 생성한다.

`wallets`는 다음 정보를 저장한다.

- 내부 지갑 ID
- `users.id`를 참조하는 `user_id`
- 출금 가능한 현재 잔액 `available_balance`
- 동시 갱신을 위한 version
- 생성·수정 시각

`user_id`에는 unique 제약을 두고 금액은 KRW 최소 단위의 `BIGINT`로 저장한다. 초기 범위는 입금만 지원하며 잔액은 음수가 될 수 없다.

`ledger_entries`는 다음 정보를 저장한다.

- 내부 원장 ID와 `wallet_id`
- 입출금 의미를 나타내는 `entry_type`
- 양수 금액과 반영 후 잔액 `balance_after`
- 발생 원인을 식별하는 `source_type`, `source_id`
- 발생 시각

정산 입금의 `entry_type`은 `SETTLEMENT_CREDIT`, `source_type`은 `SELLER_SETTLEMENT`, `source_id`는 판매자별 정산 ID다. `(source_type, source_id)`에 unique 제약을 두며 원장 행은 생성 후 수정하거나 삭제하지 않는다. 정정은 후속 역분개 설계로 처리한다.

지갑 입금 application 유스케이스는 다음을 하나의 DB 트랜잭션으로 수행한다.

1. `userId`의 지갑을 조회하고 없으면 unique 제약을 이용해 멱등 생성한다.
2. 지갑 행을 잠그거나 version을 검증해 잔액 변경을 직렬화한다.
3. 동일한 `source_type`, `source_id`의 원장이 있는지 확인한다.
4. 기존 원장이 없을 때만 잔액을 증가시키고 원장 행을 생성한다.
5. 기존 원장이 있으면 잔액을 다시 증가시키지 않고 기존 처리 결과를 반환한다.

API와 배치는 향후 같은 입금 유스케이스를 재사용할 수 있다. 다만 결제대금 발생, 정산 완료, 관리자 보상은 회계적 의미가 다르므로 무분별한 범용 `deposit` 하나로 합치지 않고 명시적인 command와 `entry_type`으로 구분한다.

## 정산 데이터 모델

`settlement_runs`는 정산일별 업무 실행을 나타낸다.

- 내부 실행 ID와 `settlement_date`
- basis point 단위 `fee_rate_bps`
- 대상 구매 건수와 판매자 수
- 총 결제금액, 총 수수료와 총 순정산금
- 실행 상태와 시작·완료 시각

실행 상태는 `COLLECTING`, `CREDITING`, `COMPLETED`로 제한한다. 최초 생성과 수집 중에는 `COLLECTING`, 검산 완료 후에는 `CREDITING`, 모든 입금이 끝나면 `COMPLETED`다. 기술적인 실패 상태와 재시작 위치는 Spring Batch 메타데이터에서 관리하고 업무 상태는 마지막으로 완료된 단계에 유지한다.

`settlement_date`에는 unique 제약을 둔다. 최초 실행에서 수수료율을 스냅샷으로 저장하고 재시작은 저장된 값을 사용한다. 완료된 정산일은 일반 재실행할 수 없다. 정산 대상이 없는 날도 합계가 모두 0인 `COMPLETED` 실행으로 남긴다.

`seller_settlements`는 판매자별·정산 실행별 집계다.

- `settlement_run_id`
- 매출 귀속 식별자인 `seller_id`
- 실제 입금 대상 스냅샷인 `beneficiary_user_id`
- 총 결제금액 `gross_amount`
- 수수료 `fee_amount`
- 순정산금 `net_amount`
- 구매 건수
- `COLLECTING`, `READY`, `CREDITED` 상태
- 생성·입금 시각

`(settlement_run_id, seller_id)`에 unique 제약을 둔다. 판매자–사용자 연결이 나중에 변경돼도 과거 입금 대상이 바뀌지 않도록 `beneficiary_user_id`를 수집 시점에 저장한다.

`settlement_items`는 구매별 정산 근거다.

- `seller_settlement_id`
- `payment_id`와 `order_id`
- 결제 승인 시각
- 총 결제금액
- 생성 시각

`payment_id`에는 전역 unique 제약을 두어 하나의 결제가 다른 정산 실행이나 재실행에서 다시 포함되지 않게 한다. 도메인 사이의 JPA 연관관계는 추가하지 않고 ID와 DB FK로 참조한다.

정산 대상 Reader를 위해 `payments`에는 승인 상태·승인 시각·ID 순서의 조회 인덱스를 추가한다. 지갑 입금 Reader를 위해 `seller_settlements`에는 상태·ID 순서의 인덱스를 둔다. 실제 인덱스는 PostgreSQL 실행 계획과 기준 데이터 측정으로 검증한다.

## 수수료 계산

수수료율은 basis point 단위의 정수이며 `0` 이상 `10,000` 이하다. 구매별로 수수료를 반올림하지 않고 판매자별 총 결제금액을 먼저 합산한 뒤 한 번 계산한다.

```text
grossAmount = settlement_items.gross_amount 합계
feeAmount = floor(grossAmount × feeRateBps ÷ 10,000)
netAmount = grossAmount - feeAmount
```

곱셈 중 정수 overflow가 발생하지 않도록 넓은 정수 연산으로 계산한 뒤 `BIGINT` 범위를 검증한다. 계산 결과가 음수이거나 저장 범위를 넘으면 해당 Job을 실패시킨다.

## Job 계약과 실행 방식

Job 이름은 `dailySellerSettlementJob`으로 한다. 필수 식별 JobParameter는 `settlementDate`이며 `YYYY-MM-DD` 형식을 사용한다. 애플리케이션은 지정된 Job을 한 번 실행하고 성공 또는 실패 종료 코드를 반환한다. 내부 `@Scheduled`는 두지 않는다.

예시는 다음과 같다.

```bash
java -jar japda-batch.jar \
  --spring.batch.job.name=dailySellerSettlementJob \
  settlementDate=2026-09-15
```

같은 `settlementDate`의 동시 실행은 Spring Batch `JobInstance`와 `settlement_runs.settlement_date` unique 제약으로 차단한다. 동일 JobInstance의 실패 후 재시작은 허용하되 완료된 JobInstance의 일반 재실행은 거절한다.

## Step 1: 정산 대상 수집

단일 thread의 `JdbcPagingItemReader`가 `payment.id`를 유일한 정렬 키로 사용해 다음 조건을 만족하는 구매를 읽는다.

- `Payment.APPROVED`
- `Order.PAID`
- `Payment.approvedAt`이 `settlementDate`의 `Asia/Seoul` 날짜 범위에 포함
- `settlement_items`에 포함되지 않은 결제
- `Sale.sellerId`와 연결된 `users.id`가 존재

Reader projection은 `paymentId`, `orderId`, `sellerId`, `beneficiaryUserId`, `approvedAt`, `grossAmount`만 포함한다. Writer는 chunk 내부 항목을 판매자별로 묶고 하나의 트랜잭션에서 구매별 `settlement_item`을 생성한 뒤 `seller_settlement`의 총액과 건수를 누적한다.

Spring Batch `JobRepository`와 업무 데이터는 같은 PostgreSQL과 transaction manager를 사용해 chunk 업무 변경과 checkpoint를 함께 commit한다. `settlement_items.payment_id` unique 제약은 commit 결과가 불명확하거나 잘못된 재처리가 발생해도 중복 정산을 막는 최종 방어선이다.

판매자–사용자 연결 누락, 유효하지 않은 금액과 DB 제약 위반은 skip하지 않고 Job을 실패시킨다. 일시적인 DB 연결 또는 lock 오류만 제한된 횟수로 retry한다.

## Step 2: 검산과 정산 확정

수집이 완료되면 각 `seller_settlement`를 `settlement_items` 집계와 비교한다.

- 상세 건수 합계와 `purchase_count`가 같다.
- 상세 금액 합계와 `gross_amount`가 같다.
- 총 결제금액이 양수다.
- `beneficiary_user_id`가 유효하다.

모든 판매자 집계가 유효할 때 수수료와 순정산금을 계산하고 `seller_settlement`를 `READY`로 전이한다. `settlement_runs`의 전체 건수, 판매자 수와 금액 합계도 같은 상세·요약 데이터로 계산해 저장한다.

검산이 끝나면 `settlement_run`을 `CREDITING`으로 전이한다. 정산 대상이 없으면 0건과 0원 합계를 저장한 뒤 입금 Step을 통과해 정상 완료한다.

하나라도 일치하지 않으면 어떤 판매자의 원장 입금도 시작하지 않고 Job을 실패시킨다. 재시작하면 검산 Step부터 다시 실행한다.

## Step 3: 사용자 지갑 입금

단일 thread의 Reader가 `READY` 상태인 `seller_settlements`를 ID 오름차순으로 읽는다. Writer는 각 정산에 대해 `:modules:ledger`의 판매자 정산금 입금 유스케이스를 호출한다.

원장 생성, 지갑 잔액 증가와 `seller_settlement.CREDITED` 전이는 하나의 DB 트랜잭션으로 수행한다. 원장은 존재하지만 정산 상태가 `CREDITED`가 아닌 복구 상황에서는 잔액을 다시 증가시키지 않고 정산 상태만 확정한다.

모든 판매자 정산이 `CREDITED`가 되면 `settlement_run`을 `COMPLETED`로 전이하고 완료 시각을 저장한다. 입금 중 실패하면 마지막 성공 chunk 이후부터 재시작하며 이미 존재하는 원장 source를 다시 입금하지 않는다.

## 실패 처리와 운영 제약

정산은 금액을 다루므로 데이터 오류를 skip하고 부분 성공으로 완료하지 않는다. 일시적인 DB 오류에만 제한적인 retry와 backoff를 적용한다. retry 소진, 데이터 검증 실패와 예상하지 못한 예외는 프로세스를 실패 종료한다.

`settlement_run`에는 마지막으로 완료된 업무 단계를 기록하고 상세 실행·실패·Step·checkpoint 상태는 Spring Batch 메타데이터를 기준으로 확인한다. 실패한 실행은 같은 `settlementDate`로 재시작하며 새로운 수수료율이나 임의의 run ID로 우회하지 않는다.

로그에는 `jobExecutionId`, `settlementRunId`, `settlementDate`, `stepName`을 공통 문맥으로 포함한다. 결제 키, 사용자 이메일과 인증정보는 기록하지 않는다.

## 검증 전략

Domain Test는 지갑 잔액 증가, 양수가 아닌 금액 거부, 원장 source 멱등성과 정산 상태 전이를 Spring Context 없이 검증한다.

PostgreSQL Testcontainers 기반 Persistence·Integration Test는 다음을 검증한다.

- 사용자 최초 입금 시 지갑 생성과 `user_id` unique 제약
- 동일 사용자의 동시 지갑 생성에서 지갑 한 개만 유지
- 동일 원장 source 재처리 시 잔액 중복 증가 방지
- 한국 시간 날짜 경계의 정산 대상 조회
- 승인되지 않은 결제와 이미 정산된 결제 제외
- 여러 구매의 판매자별 합산과 수수료 절사
- 판매자와 연결된 사용자의 지갑 입금
- 수집 중 실패 후 checkpoint 재시작
- 검산 실패 시 원장 입금 없음
- 입금 중 실패 후 재시작과 기존 입금 복구
- 같은 정산일의 동시 실행 및 완료 후 재실행 거부
- 판매자–사용자 연결 누락 시 Job 실패

전체 Job 통합 테스트는 작은 고정 데이터셋으로 대상 건수, 총 결제금액, 총 수수료, 총 순정산금, 판매자 수, 지갑 잔액과 원장 source를 끝까지 대조한다. 배치 build와 API build를 모두 실행해 공유 모듈과 migration 호환성을 확인한다.

## 단일 파티션 성능 기준선

초기 구현은 단일 파티션·단일 thread로 실행한다. 이후 멀티 파티션과 같은 조건으로 비교할 수 있도록 다음 정보를 함께 기록한다.

- 테스트 데이터 크기와 분포
- PostgreSQL과 실행 머신 사양
- JVM heap과 garbage collector 설정
- chunk size, page size와 connection pool 크기
- 전체 Job과 Step별 소요 시간
- 초당 read/write 건수
- chunk commit 시간의 P50, P95, P99
- 최대 heap 사용량
- DB connection 사용량, CPU와 I/O
- 처리 건수와 금액 검산 결과

단순 총 소요 시간만으로 안정성을 주장하지 않는다. 실패 주입 후 재시작 결과, 중복 원장 0건과 전체 금액 검산 성공을 성능 결과와 함께 제시한다.

## 주요 결정과 후속 확장

이번 설계는 단일 파티션을 성능 최적화의 최종형이 아니라 정합성과 복구가 검증된 기준 구현으로 둔다. 멀티 파티션은 판매자 또는 결제 ID 범위 분할, 파티션별 집계 경합과 connection pool 한계를 별도 설계하고 같은 데이터셋으로 비교한다.

토스페이먼츠 웹훅을 도입하면 정산일 종료 후 뒤늦게 확정되는 결제를 다음 실행에서 수용할 watermark 또는 추가 정산 정책이 필요하다. 취소·환불을 도입하면 이미 입금된 정산의 역분개와 다음 정산 상계 규칙을 별도로 결정한다. 복식부기가 필요해지면 기존 불변 원장의 source 멱등성은 유지하면서 플랫폼 계정과 차변·대변 균형을 추가한다.
