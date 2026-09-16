# 판매자 일일 정산 Job의 지갑 입금·완료 단계

## 목적과 완료 조건

[`dailySellerSettlementJob`의 검산·확정 단계](daily-seller-settlement-job-confirmation-stage.md)에 판매자 지갑 가상 입금과 정산 실행 완료 단계를 연결한다. 확정된 `seller_settlements`의 지급 대상과 입금 예정 금액을 사용해 사용자 플랫폼 지갑 잔액을 증가시키고, 수정·삭제하지 않는 원장 항목을 남긴다. 모든 판매자별 입금 결과를 검산한 뒤 정산 실행을 최종 완료한다.

이번 작업은 [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 `수집 → 검산·확정 → 지갑 입금` 흐름 중 마지막 증분 범위다. 이 단계까지 연결된 뒤에만 `dailySellerSettlementJob`의 정상 완료를 해당 정산일의 가상 입금 완료로 해석한다. 실제 계좌 지급은 의미하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 확정 단계 뒤 `creditSellerWalletsStep`과 `completeSettlementRunStep`이 순서대로 실행된다.
- 양수 입금액은 사용자별 `Wallet` 잔액과 불변 `LedgerEntry`에 같은 transaction으로 반영된다.
- 판매자별 정산 상태 전환도 지갑·원장 변경과 같은 transaction에 참여한다.
- `seller_settlements.id`를 원장 source ID로 사용하여 같은 판매자별 정산을 중복 입금하지 않는다.
- 존재하지 않는 지갑은 사용자별로 멱등 생성하고, 기존 지갑은 잠근 뒤 잔액을 변경한다.
- 입금액이 `0`인 판매자별 정산은 지갑과 원장을 만들지 않고 입금 완료로 처리한다.
- 모든 판매자별 입금 결과를 검산한 뒤 `SettlementRun`을 최종 완료한다.
- chunk 중간 실패와 동일 JobInstance 재시작에서 완료된 입금을 반복하지 않는다.
- 대상이 없는 정산일도 지갑·원장 변경 없이 정상 완료한다.

## 기존 결정과 범위

이 설계는 다음 승인된 결정을 구체화하며 새로운 아키텍처 결정을 추가하지 않는다.

- [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 API·배치·공유 원장 모듈 경계
- [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 사용자 귀속 지갑, 단식 원장, source 멱등성과 3단계 정산 흐름
- [백엔드 아키텍처 지침](../../../architecture/backend.md)의 배치 실행 및 계층 의존성 원칙

지갑 입금 규칙은 API와 배치가 재사용할 수 있도록 `:modules:ledger`에 둔다. `:batch`가 `wallets`와 `ledger_entries`를 직접 변경하거나 API 애플리케이션을 HTTP로 호출하지 않는다. 정산 행 조회와 상태 전환, Job Step 구성은 배치의 책임으로 유지한다.

## Job flow와 실행 계약

추가하는 기술 Step 이름은 다음과 같이 고정한다.

- 판매자 지갑 입금 Step: `creditSellerWalletsStep`
- 정산 실행 완료 Step: `completeSettlementRunStep`

최종 Job flow는 다음과 같다.

```text
prepareSettlementRunStep
→ collectSettlementDetailsStep
→ completeSettlementCollectionStep
→ confirmSellerSettlementsStep
→ creditSellerWalletsStep
→ completeSettlementRunStep
```

Job 이름과 parameter 계약은 기존 단계와 동일하다. `settlementDate`만 JobInstance를 식별하고 `platformFeeRateBps`는 최초 실행에서 저장한 비식별 parameter다. 지갑 입금 단계는 JobParameter를 입금 근거로 사용하지 않고 확정된 `seller_settlements`만 사용한다.

`creditSellerWalletsStep`은 chunk 기반으로 구성하며 chunk size와 reader page size의 초기값은 모두 `100`으로 고정한다. 단일 partition·단일 thread로 실행하고 성능 설정을 외부 설정으로 미리 노출하지 않는다. `completeSettlementRunStep`은 하나의 transaction으로 실행하는 tasklet Step이다.

이 단계가 추가되기 전에 검산·확정 단계까지만 포함한 JobInstance가 이미 Spring Batch에서 `COMPLETED`됐다면 같은 JobInstance에 지갑 입금 단계를 소급 실행하지 않는다. 이전 증분 버전은 운영 정산에 사용하지 않았다는 전제를 유지하고, 개발·테스트 데이터는 다른 과거 정산일로 최종 Job을 실행한다. 임의의 식별 parameter나 보정 Job으로 완료 제약을 우회하는 기능은 추가하지 않는다.

## 업무 데이터 모델

API root project가 소유하는 다음 Flyway migration에 지갑·원장 테이블과 입금 완료 상태를 추가한다.

```text
apps/backend/src/main/resources/db/migration/V14__create_wallet_ledger_and_complete_settlements.sql
```

구현 시 이미 다른 `V14` migration이 존재하면 기존 파일을 변경하거나 버전을 충돌시키지 않고 다음 사용 가능한 버전을 사용한다. 기존 `V12`, `V13` migration은 수정하지 않는다.

### `wallets`

사용자 한 명이 플랫폼에서 공유하는 현재 잔액 projection을 저장한다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `user_id` | `BIGINT` | not null, `users.id` foreign key, unique |
| `balance` | `BIGINT` | not null, 기본값 `0`, `0` 이상 |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | not null |

지갑은 판매자별이 아니라 사용자별 하나다. 같은 사용자가 여러 판매자에 연결돼 있어도 모든 정산 입금은 같은 지갑에 누적한다.

### `ledger_entries`

지갑 잔액 변경의 원인과 반영 결과를 보존하는 단식 원장이다.

| column | type | 규칙 |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | primary key |
| `wallet_id` | `BIGINT` | not null, `wallets.id` foreign key |
| `direction` | `VARCHAR(20)` | not null, `CREDIT` 또는 `DEBIT` |
| `amount` | `BIGINT` | not null, 양수 |
| `balance_after` | `BIGINT` | not null, `0` 이상 |
| `source_type` | `VARCHAR(50)` | not null, 이번 범위에서는 `SELLER_SETTLEMENT` |
| `source_id` | `BIGINT` | not null, 양수 |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | not null |

`(source_type, source_id)`에 unique 제약을 두어 한 업무 원인이 원장에 한 번만 반영되도록 한다. 지갑별 원장 순회용 `(wallet_id, id)` 인덱스를 둔다.

`direction` check constraint는 `CREDIT`, `DEBIT`만 허용하고 `source_type` check constraint는 이번 범위의 `SELLER_SETTLEMENT`만 허용한다. `SELLER_SETTLEMENT` source는 반드시 `CREDIT`이어야 한다는 교차 check constraint도 둔다. 향후 다른 source 종류를 추가할 때는 새 migration으로 허용값과 조합을 확장한다.

원장 항목은 application 계약상 수정하거나 삭제하지 않는다. `direction`은 ADR-025가 정한 양수 금액 기반 입출금 원장 계약을 표현하지만, 이번 범위에서는 `CREDIT` 생성만 구현한다. `DEBIT` application 유스케이스나 잔액 차감 기능은 추가하지 않는다.

정산 입금 원장의 source 계약은 다음과 같다.

```text
direction = CREDIT
amount = seller_settlements.net_amount
source_type = SELLER_SETTLEMENT
source_id = seller_settlements.id
```

다형 source에는 단일 외래 키를 적용할 수 없으므로 `ledger_entries.source_id`에 `seller_settlements` 외래 키를 두지 않는다. 배치의 입력 검증과 최종 검산이 정산 source의 존재 및 일치를 보장한다.

### `seller_settlements`

기존 `status` check constraint의 허용값을 다음과 같이 확장한다.

| status | 의미 |
| --- | --- |
| `CONFIRMED` | 판매자별 계산과 검산은 완료됐지만 지갑 입금은 완료되지 않음 |
| `CREDITED` | 지갑 입금 또는 0원 완료 처리가 끝남 |

다음 컬럼을 추가한다.

| column | type | 규칙 |
| --- | --- | --- |
| `credited_at` | `TIMESTAMP WITH TIME ZONE` | nullable, `CREDITED` 전환 시 설정 |

`CONFIRMED`이면 `credited_at`은 `NULL`, `CREDITED`이면 `credited_at`은 not null이어야 한다. 양수 `net_amount`의 `CREDITED` 행은 일치하는 원장 항목을 가져야 하며, `0`원 행은 원장 항목을 갖지 않는다. 다형 source 관계라서 이 조건은 데이터베이스 check constraint가 아니라 application transaction과 최종 검산으로 보장한다.

### `settlement_runs`

기존 `status` check constraint에 최종 상태를 추가한다.

| status | 의미 |
| --- | --- |
| `COLLECTING` | 구매별 상세 수집 중이거나 수집 실패 후 재시작 대기 상태 |
| `COLLECTED` | 구매별 상세 수집과 집계 완료 상태 |
| `CONFIRMED` | 판매자별 계산과 전체 검산 완료 상태이며 지갑 입금 진행 가능 |
| `COMPLETED` | 모든 판매자별 입금과 최종 검산 완료 상태 |

다음 컬럼을 추가한다.

| column | type | 규칙 |
| --- | --- | --- |
| `completed_at` | `TIMESTAMP WITH TIME ZONE` | nullable, `COMPLETED` 전환 시 설정 |

일부 판매자의 입금 chunk만 commit된 동안에도 실행 상태는 `CONFIRMED`로 유지한다. `SettlementRun.status`는 진행 중인 기술 상태가 아니라 마지막으로 완료된 업무 단계를 기록하므로 별도의 `CREDITING` 상태를 추가하지 않는다. `COMPLETED`이면 `completed_at`이 존재하고 그 외 상태이면 `completed_at`은 `NULL`이어야 한다.

## 공유 원장 application 기능

`:modules:ledger`는 실행 framework와 무관하게 호출할 수 있는 지갑 입금 유스케이스를 제공한다. API root project와 `:batch`가 이 모듈에 의존하며, 배치 애플리케이션은 원장 모듈의 Spring 구성을 명시적으로 import한다. 원장 모듈은 Spring Boot 실행 진입점이나 `bootJar`를 갖지 않는 plain jar를 유지한다.

공유 application 입력은 다음 의미를 가진다.

```text
CreditWalletCommand
- userId: 입금 받을 사용자 ID
- amount: 양수 입금액
- sourceType: 입금 원인 종류
- sourceId: 입금 원인의 식별자
```

공유 기능은 domain repository interface를 통해 다음 순서로 처리한다.

1. `userId`, `amount`, `sourceId`가 양수이고 지원하는 `sourceType`인지 검증한다.
2. 같은 `(sourceType, sourceId)`의 원장 항목이 이미 있으면 지갑 사용자, 방향과 금액이 요청과 정확히 같은지 검증한다.
3. 기존 항목이 모두 일치하면 잔액을 다시 변경하지 않고 멱등 성공 결과를 반환한다.
4. 지갑이 없으면 `INSERT ... ON CONFLICT (user_id) DO NOTHING`으로 잔액 `0`의 지갑 생성을 시도한다.
5. 해당 사용자의 지갑을 `SELECT ... FOR UPDATE`로 조회하고 잠근다.
6. 잠금 획득 후 같은 source를 다시 확인하여 동시 호출 결과를 검증한다.
7. 현재 잔액과 입금액의 합이 PostgreSQL `BIGINT` 범위 안인지 검증한다.
8. 지갑 잔액과 `updated_at`을 갱신하고 `CREDIT` 원장 항목을 생성한다.

지갑 갱신과 원장 insert는 호출자의 transaction에 참여하며 반드시 함께 commit 또는 rollback된다. 공유 서비스는 Spring의 기본 `REQUIRED` 전파를 사용해 배치 chunk transaction에 참여한다. 별도 transaction이나 `REQUIRES_NEW`를 사용하지 않는다.

동일 source의 원장 항목이 존재하지만 사용자, 방향 또는 금액이 다르면 기존 항목을 덮어쓰거나 요청을 성공으로 간주하지 않고 오류로 처리한다. unique 충돌을 무조건 성공으로 숨기지 않는다.

원장 모듈은 `seller_settlements`를 조회하거나 상태를 변경하지 않는다. 정산 source의 선택과 판매자별 완료 상태는 `:batch`가 소유한다. 원장 모듈은 API root project의 Entity, repository 또는 예외 타입에 의존하지 않는다.

## 판매자 지갑 입금 Step

### Reader

Step-scoped JDBC paging reader는 Job execution context의 `settlementRunId`로 해당 실행의 모든 `seller_settlements.id`를 오름차순 조회한다. projection에는 판매자별 금액이나 사용자 정보를 복사하지 않고 식별자만 전달하며 Writer가 잠금 조회한 최신 행을 처리 근거로 사용한다.

Reader 조건에 `status = 'CONFIRMED'`를 넣지 않는다. commit된 chunk가 행 상태를 `CREDITED`로 변경하는 동안 paging 대상 집합이 축소되면 offset 기반 페이지에서 아직 처리하지 않은 행을 건너뛸 수 있기 때문이다. 한 실행의 판매자별 행 집합은 확정 단계 이후 추가·삭제하지 않으며, 상태와 입금 완료 시각만 변경한다.

reader page size와 fetch size는 `100`이고 정렬 키 `id`는 unique해야 한다. 실행 상태가 `CONFIRMED` 또는 재검산 가능한 후속 상태인지 여부는 Writer와 완료 tasklet에서 검증하며, 조회 결과가 없다는 이유만으로 잘못된 실행 상태를 정상 처리하지 않는다.

### Writer와 transaction

Writer는 chunk의 각 `sellerSettlementId`를 순서대로 다음과 같이 처리한다.

1. `seller_settlements` 행을 `FOR UPDATE`로 조회한다.
2. 행이 현재 `settlementRunId`에 속하는지 검증한다.
3. 부모 `SettlementRun`이 `CONFIRMED` 또는 멱등 재검산 대상인 `COMPLETED`인지 검증한다.
4. 현재 판매자별 상태와 `net_amount`에 따라 신규 입금 또는 멱등 재검산을 수행한다.

부모 실행이 `CONFIRMED`이면 `CONFIRMED`, `CREDITED` 판매자별 정산을 모두 처리할 수 있다. 부모 실행이 이미 `COMPLETED`이면 모든 판매자별 정산이 `CREDITED`여야 하며 기존 결과 재검산만 허용한다. `COMPLETED` 실행에 `CONFIRMED` 행이 남아 있으면 새로 입금하여 복구하지 않고 최종 완료 불변식이 깨진 데이터 오류로 실패한다.

`CONFIRMED`이고 `net_amount > 0`이면 같은 정산 source의 원장 항목이 아직 없어야 한다. 공유 원장 application 기능으로 입금한 뒤 같은 transaction에서 판매자별 상태를 `CREDITED`로 바꾸고 `credited_at`을 기록한다.

`CONFIRMED`이고 `net_amount = 0`이면 지갑을 생성하거나 0원 원장을 기록하지 않는다. 같은 정산 source의 원장 항목이 없음을 확인한 뒤 판매자별 상태와 시각만 갱신한다. 플랫폼 수수료율이 `10000` basis point인 정산에서도 양수만 허용하는 원장 계약을 깨지 않고 정상 완료할 수 있다.

`CREDITED` 행이 다시 전달되면 다음을 재검산한다.

- `credited_at`이 존재한다.
- 양수 `net_amount`이면 source 원장이 정확히 하나 존재한다.
- 원장의 지갑 사용자가 `recipient_user_id`와 같다.
- 원장 방향이 `CREDIT`이고 금액이 `net_amount`와 같다.
- `0`원 행이면 같은 source의 원장 항목이 존재하지 않는다.

모든 값이 일치하면 잔액과 상태를 다시 변경하지 않고 멱등 성공으로 처리한다. 하나라도 다르면 데이터 오류로 Job을 실패시킨다.

한 chunk에서 수행한 지갑 생성, 잔액 변경, 원장 생성, 판매자별 상태 전환과 Spring Batch checkpoint 갱신은 같은 PostgreSQL transaction manager를 사용한다. 항목 하나가 실패하면 해당 chunk 전체가 rollback되고 앞서 완료된 chunk는 유지된다.

## 최종 검산과 실행 완료

`completeSettlementRunStep`은 Job execution context의 `settlementRunId`로 다음 순서를 하나의 transaction에서 처리한다.

1. `settlement_runs` 행을 `FOR UPDATE`로 조회한다.
2. 실행 상태가 `CONFIRMED` 또는 `COMPLETED`인지 확인한다.
3. 해당 실행의 모든 `seller_settlements`가 `CREDITED`이고 `credited_at`을 가지는지 확인한다.
4. 양수 `net_amount`인 모든 판매자별 정산에 정확히 일치하는 `SELLER_SETTLEMENT` 원장이 존재하는지 확인한다.
5. 각 원장의 지갑 사용자가 `recipient_user_id`, 방향이 `CREDIT`, 금액이 `net_amount`인지 확인한다.
6. `0`원 판매자별 정산에는 같은 source의 원장이 없음을 확인한다.
7. 해당 실행의 판매자별 정산 ID를 source로 사용하는 누락되거나 일치하지 않는 원장이 없는지 전체 집합으로 검산한다.
8. `CONFIRMED`이면 실행 상태를 `COMPLETED`로 변경하고 `completed_at`을 기록한다.

판매자별 정산이 없는 실행은 모든 검산을 빈 집합의 정상 결과로 처리하고 `COMPLETED`로 전환한다.

이미 `COMPLETED`인 실행에서 tasklet이 다시 호출되면 `completed_at` 존재 여부와 위의 전체 입금 결과를 다시 검산한다. 모두 일치하면 상태와 시각을 변경하지 않고 멱등 성공하며, 하나라도 다르면 실패한다.

## 후속 상태를 고려한 기존 Step 재실행

`prepareSettlementRunStep`은 기존과 같이 저장된 수수료율을 검증하고 `settlementRunId`를 승격한다. 새 최종 상태를 이유로 새 실행을 만들지 않는다.

재시작 과정에서 완료된 이전 업무 Step이 다시 호출될 가능성에 대비해 다음 상태 처리 범위를 확장한다.

- `completeSettlementCollectionStep`은 `COMPLETED`에서도 기존 수집 집계와 상세 집계를 재검산한다.
- `confirmSellerSettlementsStep`은 `COMPLETED`에서도 기존 판매자별 확정 결과를 상세 계산과 재검산한다. 이때 `CREDITED`를 `CONFIRMED`의 정상 후속 상태로 인정하되 지급 대상, 건수, 금액, 수수료, 입금 예정 금액과 `confirmed_at` 검증은 그대로 수행한다.
- 이미 `CREDITED`인 판매자별 정산은 지갑 입금 Step에서 원장과 일치할 때만 멱등 성공한다.

후속 상태를 허용한다는 이유로 각 Step의 기존 불변식 검증을 생략하지 않는다. `COMPLETED` 데이터가 수집 또는 확정 결과와 다르면 최종 상태라도 Job을 실패시킨다.

## 재시작과 멱등성

chunk 도중 오류가 발생하면 현재 chunk의 지갑·원장·판매자 상태와 checkpoint가 함께 rollback된다. 동일 JobInstance를 재시작하면 Spring Batch가 마지막 commit 이후부터 Reader를 재개한다.

정상 transaction 경계에서는 원장만 생성되고 판매자 상태가 남지 않거나 그 반대인 부분 commit이 발생하지 않는다. 그럼에도 수동 변경이나 계약 위반 데이터를 정상 결과로 흡수하지 않도록 다음 규칙을 적용한다.

- `CONFIRMED` 판매자별 정산에 같은 source 원장이 이미 있으면 원자성 계약이 깨진 데이터 오류로 실패한다.
- `CREDITED` 판매자별 정산의 양수 입금 원장이 없거나 내용이 다르면 실패한다.
- `CREDITED`인 0원 정산에 원장이 있으면 실패한다.
- 공유 원장 기능을 같은 source로 직접 재호출했을 때는 기존 항목과 요청이 완전히 같아야 멱등 성공한다.

업무 transaction 반영 후 Spring Batch 메타데이터 갱신 전에 Step이 다시 실행되는 상황에서도 Reader가 전체 판매자 집합을 안정적으로 순회하고 `CREDITED` 행을 재검산하므로 잔액을 중복 증가시키지 않는다.

## 구성 요소와 의존성 경계

API root project는 다음 책임을 가진다.

- 지갑·원장 및 정산 완료 상태 Flyway migration 소유
- `:modules:ledger` project dependency 선언
- 기존 Flyway 실행 책임 유지

`:modules:ledger`는 다음 책임을 가진다.

- 지갑과 원장 domain 타입 및 상태 값
- 지갑 입금 application 입력과 유스케이스
- 원장 source 멱등성, 지갑 잠금과 잔액 범위 검증
- 지갑·원장 repository interface와 JDBC 구현
- API와 배치가 import할 Spring bean 구성

`:batch`는 다음 책임을 가진다.

- `creditSellerWalletsStep`과 `completeSettlementRunStep` 구성
- 정산 실행에 속한 판매자별 정산 ID 순회
- 판매자별 정산 잠금, 상태 검증과 `CREDITED` 전환
- 0원 정산 완료 처리
- 공유 원장 application 기능 호출
- 정산 source 원장 전체 검산
- `SettlementRun`의 `CONFIRMED → COMPLETED` 상태 전환

원장 모듈은 API root project나 `:batch`에 의존하지 않는다. `:batch`는 API root project를 project dependency로 참조하지 않는다. migration 파일은 계속 API root project에만 둔다.

## 오류 처리와 관측성

다음 오류는 모두 Job 실패로 처리한다.

- 존재하지 않는 `settlementRunId` 또는 `sellerSettlementId`
- 다른 정산 실행에 속한 판매자별 정산
- 입금을 시작하거나 완료할 수 없는 `SettlementRun` 상태
- 입금할 수 없는 판매자별 정산 상태
- 존재하지 않는 지급 대상 사용자
- 지갑 생성 또는 잠금 조회 실패
- 지갑 잔액의 `BIGINT` overflow
- 기존 source 원장의 지갑 사용자, 방향 또는 금액 불일치
- `CONFIRMED` 행에 이미 존재하는 source 원장
- `CREDITED` 행의 누락되거나 잘못된 source 원장
- 0원 정산에 존재하는 source 원장
- 원장 unique 또는 다른 데이터베이스 제약 위반
- 판매자별 상태 또는 정산 실행 상태 전환 실패
- 최종 입금 결과 집합 검산 실패

데이터 오류를 skip하지 않고 retry도 구성하지 않는다. 이번 단계는 외부 시스템을 호출하지 않으며, 일시적 오류와 데이터 오류를 구분해 재시도할 별도 외부 연동이 없다.

로그에는 Job 이름, `settlementDate`, `settlementRunId`, `sellerSettlementId`, `sellerId`, JobExecution/StepExecution 식별자와 오류 분류처럼 복구에 필요한 정보만 남긴다. 사용자 개인정보, 지갑 전체 잔액, 전체 원장 내용과 DB credential은 기록하지 않는다.

## 검증 전략

단위 테스트와 PostgreSQL Testcontainers 기반 통합 테스트로 다음을 검증한다.

1. 지갑이 없는 사용자의 첫 양수 정산이 지갑을 만들고 잔액과 원장을 함께 생성한다.
2. 기존 지갑에 입금하면 이전 잔액에 금액을 더하고 올바른 `balance_after`를 기록한다.
3. 같은 사용자가 여러 판매자 또는 여러 정산 입금을 받아도 지갑은 하나이고 잔액은 정확히 누적된다.
4. `source_type = SELLER_SETTLEMENT`, `source_id = seller_settlements.id`로 원장을 생성한다.
5. 같은 source의 동일 요청은 공유 원장 기능에서 잔액을 다시 변경하지 않고 멱등 성공한다.
6. 같은 source의 사용자, 방향 또는 금액이 다르면 실패한다.
7. 동시 최초 입금에서도 사용자별 지갑이 하나만 생성되고 두 원장이 직렬화된 잔액을 기록한다.
8. 현재 잔액과 입금액의 합이 `BIGINT` 범위를 벗어나면 지갑·원장·판매자 상태를 모두 rollback한다.
9. `net_amount = 0`이면 지갑과 원장 없이 판매자별 정산만 `CREDITED`가 된다.
10. 0원 정산에 같은 source 원장이 있으면 실패한다.
11. Reader가 상태로 대상 집합을 축소하지 않고 여러 page의 판매자별 정산을 빠짐없이 처리한다.
12. 한 chunk의 중간 항목이 실패하면 해당 chunk 전체가 rollback되고 이전 chunk는 유지된다.
13. 실패한 동일 JobInstance를 재시작하면 checkpoint 이후 항목만 신규 입금하고 완료된 입금을 중복 반영하지 않는다.
14. 입금 업무 transaction 반영 뒤 Step 재호출을 재현해 `CREDITED` 행을 재검산하고 중복 입금하지 않는다.
15. `CONFIRMED` 행에 원장이 미리 존재하거나 `CREDITED` 행의 원장이 누락·불일치하면 실패한다.
16. 모든 판매자별 정산이 입금되기 전에는 `SettlementRun`을 완료하지 않는다.
17. 모든 입금과 원장이 일치하면 `SettlementRun`을 `COMPLETED`로 전환하고 완료 시각을 기록한다.
18. 대상이 없는 정산일은 지갑·원장 행 없이 `COMPLETED`로 끝난다.
19. 이미 `COMPLETED`인 실행의 전체 결과가 일치하면 멱등 성공하고 다르면 실패한다.
20. 기존 수집·확정 Step이 `COMPLETED` 상태에서도 자신의 집계와 계산 불변식을 재검산한다.
21. 지갑 사용자 unique, 원장 source unique, 금액·잔액·상태·시각 check constraint와 조회 인덱스를 migration 테스트로 검증한다.
22. API root의 전체 Flyway migration을 적용한 PostgreSQL에서 최종 Job이 실행된다.
23. `:modules:ledger`가 plain jar를 유지하고 API와 배치가 공유 application 기능을 로드할 수 있다.
24. 전체 backend와 batch test·build가 기존 주문·결제 및 정산 수집·확정 동작에 회귀를 만들지 않는다.

재시작 테스트는 지갑 잔액이나 원장을 임의로 미리 넣어 성공을 모사하는 것으로 끝내지 않는다. 실제 여러 chunk 처리 중 의도적으로 실패시켜 Spring Batch metadata와 업무 데이터의 commit 경계를 검증한 뒤 동일 JobInstance를 재시작한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- 지갑 잔액 조회, 원장 조회 또는 관리자 HTTP API
- 지갑 출금과 `DEBIT` application 유스케이스
- 실제 은행 계좌 지급과 외부 지급 시스템 연동
- 애플리케이션 내부 scheduler, 운영 Cron 또는 Kubernetes CronJob
- 운영용 수동 실행 API와 관리자 화면
- 취소·환불, 역분개, 정정 정산과 늦게 확정된 결제 처리
- 복식부기, 보류금, 출금 가능 잔액과 정산 주기 변경
- 멀티 thread, partitioning과 성능 최적화
- 정산, 지갑, 원장 및 Spring Batch 데이터의 보존·삭제 정책
- 검산·확정 단계까지만 완료된 개발 데이터의 보정 실행 기능

이 단계가 구현되면 ADR-025가 정의한 판매자 일일 정산의 업무 Job은 완성된다. 실제 운영 투입에는 별도로 실행 스케줄, 수동 실행 절차, 모니터링·알림과 데이터 보존 정책을 결정해야 한다.
