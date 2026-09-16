# 판매자 일일 정산 Job 성능 테스트 환경

## 목적과 완료 조건

[`dailySellerSettlementJob`](daily-seller-settlement-job-wallet-credit-stage.md)의 성능을 운영 데이터나 외부 PostgreSQL 없이 반복 측정할 수 있는 테스트 환경을 제공한다. 실행자가 외부 설정으로 판매자 수, 주문 수와 정산 조건을 지정하면 테스트가 PostgreSQL Testcontainer를 시작하고 합성 데이터를 준비한 뒤 실제 배치 애플리케이션과 Job을 실행한다.

성능 수치는 정산 결과의 업무 정합성이 확인된 실행에서만 유효한 것으로 본다. 데이터 준비, 애플리케이션 시작과 Job 실행 시간을 구분하고, 실행 설정과 환경을 결과에 함께 기록하여 같은 조건을 재현하고 서로 다른 설정의 결과를 비교할 수 있게 한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- `:batch`에 일반 테스트와 분리된 성능 테스트 source set과 명시적 Gradle task가 존재한다.
- 외부 설정만으로 판매자 수, 주문 수, 데이터 seed, 정산일, 금액, 수수료율과 반복 횟수를 지정할 수 있다.
- 테스트가 직접 소유한 PostgreSQL Testcontainer에 API root의 전체 Flyway migration을 적용한다.
- 설정된 판매자 수와 주문 수에 맞는 유효한 정산 대상 데이터를 결정론적으로 생성한다.
- 실제 `BatchApplication` context와 `JobOperator`를 사용해 `dailySellerSettlementJob`을 실행한다.
- Job과 Step의 시간, 처리량, 실행 횟수, 자원 사용량과 유효 설정을 결과 파일로 남긴다.
- 정산 상세, 판매자별 정산, 지갑, 원장과 Spring Batch 실행 결과를 검산한다.
- 준비 실패, Job 실패 또는 검산 실패는 성능 결과 성공으로 기록하지 않고 Gradle task를 실패시킨다.
- 일반 `test`와 `check`는 성능 테스트를 자동 실행하지 않는다.
- 외부 PostgreSQL이나 운영 데이터에 연결할 수 없는 안전 경계를 가진다.

## 기존 결정과 범위

이 설계는 새로운 실행 애플리케이션이나 업무 저장 방식을 추가하지 않고 다음 결정을 따른다.

- [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 API·배치·공유 원장 모듈 경계와 API root의 migration 소유권
- [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 단일 partition·단일 thread 정산 흐름, 사용자 귀속 지갑과 원장 불변식
- [백엔드 아키텍처 지침](../../../architecture/backend.md)의 배치 transaction, 재시작과 성능 결과 기록 원칙
- [지갑 입금·완료 단계 명세](daily-seller-settlement-job-wallet-credit-stage.md)의 최종 Job flow와 검산 계약

성능 테스트는 `:batch` 안에서만 실행되는 테스트 지원 기능이다. 운영용 runner, scheduler, HTTP API 또는 독립 애플리케이션을 추가하지 않는다. 테스트가 API root의 migration을 적용하는 것은 테스트 fixture 준비 책임이며, 운영에서 migration을 API가 선행 실행한다는 책임을 변경하지 않는다.

외부화하는 chunk, page와 fetch 설정은 단일 partition·단일 thread 안에서 기존 Job의 처리 단위를 조절하기 위한 값이다. task executor나 partition 수는 설정으로 노출하지 않는다. 따라서 이번 설계는 기존 아키텍처 결정을 변경하지 않으며 새 ADR을 작성하지 않는다.

## 테스트 실행 경계

### Source set과 Gradle task

`apps/backend/batch`에 `performanceTest` source set과 같은 이름의 Gradle task를 추가한다. 성능 테스트 코드는 다음 디렉터리에 둔다.

```text
apps/backend/batch/src/performanceTest/kotlin
apps/backend/batch/src/performanceTest/resources
```

`performanceTest` source set은 `main` output을 사용하고 Testcontainers, Flyway, JUnit과 기존 테스트 지원 dependency를 재사용한다. 일반 통합 테스트의 private fixture를 참조하지 않고 성능 시나리오가 필요한 데이터 생성과 검산 기능을 자신의 source set 안에 둔다. 새 dependency는 기존 dependency만으로 구현하기 어려운 경우에만 추가한다.

`performanceTest` task는 다음 계약을 가진다.

- 실행자가 명시적으로 호출할 때만 실행한다.
- `test`, `check`와 `build`의 기본 task dependency에 연결하지 않는다.
- `japda.performance.`로 시작하는 system property만 fork된 테스트 JVM에 전달한다.
- 표준 Spring datasource tuning property 중 명세에 열거한 값만 전달한다.
- 성공한 실행과 실패한 실행 모두 결과 디렉터리를 식별할 수 있는 실행 ID를 출력한다.
- JUnit의 병렬 실행을 사용하지 않고 시나리오를 한 프로세스 안에서 순차 실행한다.

환경변수는 Spring Boot의 relaxed binding 규칙으로 같은 설정에 대응할 수 있다. 같은 값이 system property와 환경변수에 모두 있으면 Spring Boot의 표준 property 우선순위를 따른다. 비밀번호나 token을 성능 설정으로 받지 않는다.

### 명시적 실행 예시

```bash
./gradlew :batch:performanceTest \
  -Djapda.performance.dataset.seller-count=1000 \
  -Djapda.performance.dataset.order-count=100000 \
  -Djapda.performance.dataset.random-seed=42 \
  -Djapda.performance.dataset.gross-amount=10000 \
  -Djapda.performance.job.settlement-date=2026-09-15 \
  -Djapda.performance.job.platform-fee-rate-bps=1000 \
  -Djapda.performance.measurement-iterations=5
```

Gradle daemon의 시작 시간, dependency resolution과 테스트 JVM 시작 시간은 Job 실행 시간에 포함하지 않는다.

## 외부 설정 계약

### 시나리오 설정

| property | 필수 여부 | 기본값 | 의미 |
| --- | --- | --- | --- |
| `japda.performance.dataset.seller-count` | 필수 | 없음 | 정산 대상 판매자와 지급 대상 사용자 수 |
| `japda.performance.dataset.order-count` | 필수 | 없음 | 승인 결제까지 완료된 주문 수 |
| `japda.performance.dataset.random-seed` | 선택 | `1` | 식별자와 입력 순서 재현에 사용하는 seed |
| `japda.performance.dataset.gross-amount` | 필수 | 없음 | 각 주문의 정산 대상 총액 |
| `japda.performance.job.settlement-date` | 필수 | 없음 | `yyyy-MM-dd` 형식의 정산일 |
| `japda.performance.job.platform-fee-rate-bps` | 필수 | 없음 | Job에 전달할 플랫폼 수수료율 |
| `japda.performance.job.timeout` | 선택 | `30m` | 한 iteration의 Job 완료를 기다리는 최대 시간 |
| `japda.performance.warmup-iterations` | 선택 | `0` | 결과 집계에서 제외할 전체 Job 실행 횟수 |
| `japda.performance.measurement-iterations` | 선택 | `1` | 결과 집계에 포함할 전체 Job 실행 횟수 |
| `japda.performance.resource-sampling-interval-ms` | 선택 | `100` | JVM과 PostgreSQL 자원 표본 수집 간격 |

설정 객체는 애플리케이션 context를 시작하고 container를 생성하기 전에 다음을 검증한다.

- `seller-count`는 `1` 이상이다.
- `order-count`는 `seller-count` 이상이다.
- `gross-amount`는 양수이고 주문 수와 곱한 값 및 후속 집계가 PostgreSQL `BIGINT` 범위를 넘지 않는다.
- `settlement-date`는 실행 시점의 한국 기준 오늘보다 과거다.
- `platform-fee-rate-bps`는 기존 Job parameter 계약 범위 안이다.
- Job timeout은 양수인 Spring `Duration` 형식이다.
- warm-up 횟수는 `0` 이상이고 측정 횟수는 `1` 이상이다.
- 자원 표본 간격은 양수다.

지원하지 않는 값은 자동 보정하지 않고 실행 전에 명확한 설정 오류로 실패시킨다.

### Batch tuning 설정

현재 collection과 wallet credit 단계가 공통 `CHUNK_SIZE = 100` 상수를 chunk, page와 fetch 크기에 함께 사용하므로 이를 다음 설정으로 분리한다.

| property | 기본값 | 적용 범위 |
| --- | --- | --- |
| `japda.batch.daily-seller-settlement.chunk-size` | `100` | collection과 wallet credit chunk size |
| `japda.batch.daily-seller-settlement.page-size` | `100` | 두 JDBC paging reader의 page size |
| `japda.batch.daily-seller-settlement.fetch-size` | `100` | 두 JDBC paging reader의 fetch size |

세 값은 모두 양수여야 한다. 기본값을 기존 상수와 같은 `100`으로 두어 별도 설정이 없는 운영 및 테스트 동작을 보존한다. collection과 wallet credit에 서로 다른 값을 제공하는 단계별 tuning은 이번 범위에 포함하지 않는다.

connection pool은 Spring Boot 표준 property를 그대로 사용하며 별도의 중복 설정 모델을 만들지 않는다. 성능 task는 최소한 다음 값을 테스트 JVM에 전달하고 실제 binding 결과를 보고서에 기록한다.

- `spring.datasource.hikari.maximum-pool-size`
- `spring.datasource.hikari.minimum-idle`
- `spring.datasource.hikari.connection-timeout`

Testcontainer의 JDBC URL, username과 password는 harness가 생성한다. 외부 datasource 접속 정보로 덮어쓸 수 없게 하며 보고서에는 password를 기록하지 않는다.

## Testcontainer와 반복 실행 격리

한 번의 Gradle task는 PostgreSQL Testcontainer 하나를 시작한다. container image는 기존 batch 통합 테스트와 같은 버전을 사용하여 기능 테스트와 성능 테스트의 데이터베이스 차이를 만들지 않는다.

각 warm-up 및 측정 iteration은 container 안에 독립 schema를 생성한다. schema 이름은 harness가 생성한 iteration 식별자로 정하고 외부 입력을 직접 SQL 식별자로 사용하지 않는다. 각 iteration은 다음 자원을 독립적으로 가진다.

- API 업무 테이블과 shared ledger 테이블
- Spring Batch metadata 테이블
- 합성 주문·결제·판매자 데이터
- `BatchApplication` context와 datasource pool
- JobRepository의 JobInstance와 execution 기록

각 schema에는 API root의 Flyway migration 전체를 순서대로 적용한다. 기존 batch 통합 테스트가 사용하는 root migration 위치 해석 방식을 재사용하고 migration 파일을 복제하지 않는다. migration이나 metadata schema 검증이 실패하면 해당 iteration에서 Job을 시작하지 않는다.

iteration은 순차 실행하며 이전 application context와 datasource pool을 닫은 뒤 다음 schema를 사용한다. schema를 재사용하거나 `TRUNCATE`로 초기 상태를 모사하지 않는다. 전체 task 종료 시 Testcontainers가 소유한 container를 종료하며 외부 데이터베이스에 cleanup SQL을 실행하지 않는다.

## 합성 데이터 계약

### 데이터 형태

첫 버전은 비교 기준이 명확한 균등 분포만 지원한다.

- 판매자마다 별도의 사용자와 `seller_principal_identities`를 하나씩 생성한다.
- 판매자마다 정산 대상 상품과 판매 일정을 하나씩 생성한다.
- 모든 판매자에게 주문을 한 건씩 먼저 배정한다.
- 남은 주문은 판매자 순서대로 round-robin 배정한다.
- 주문마다 정산일의 서울 시간 범위에 속하는 승인 결제를 정확히 한 건 생성한다.
- 모든 주문의 정산 대상 금액은 `gross-amount`로 동일하다.
- 배치 projection과 업무 제약이 요구하는 사용자, 상품, 판매, 주문과 결제 관계를 모두 만족한다.
- 배치가 제외해야 하는 실패 결제, 누락 관계와 비정상 금액은 생성하지 않는다.

`random-seed`는 생성 식별자와 insert 입력 순서를 결정하는 데 사용한다. 같은 schema 상태, 설정과 seed는 같은 논리 데이터 집합과 예상 검산값을 만들어야 한다. 판매자별 주문 건수는 seed와 관계없이 최대 한 건만 차이 나는 균등 분포를 유지한다.

한 판매자에게 주문이 집중되는 skewed 또는 Zipf 분포, 여러 금액 구간과 오류 데이터는 이번 범위에 포함하지 않는다. 후속 분포가 추가되더라도 분포 이름과 parameter를 결과에 반드시 기록한다.

### 데이터 준비

합성 데이터 생성기는 `JdbcTemplate`의 batch insert처럼 기존 dependency가 제공하는 일괄 쓰기 기능을 사용한다. 주문마다 application service나 HTTP endpoint를 호출하지 않는다. 준비 자체의 비용이 Job 처리량을 왜곡하지 않도록 다음 시간을 별도로 측정한다.

- schema 생성과 migration 시간
- 합성 데이터 계산 시간
- 데이터 insert 시간
- `BatchApplication` context 시작 시간

데이터 준비 시간은 Job duration과 처리량 계산에서 제외하지만 결과 보고서에는 남긴다. insert가 끝난 뒤 테이블별 건수와 기준 금액 합계를 확인하고 예상값과 다르면 Job을 실행하지 않는다.

## Job 실행 계약

성능 harness는 기존 통합 테스트와 같이 non-web `BatchApplication` context를 시작한 뒤 `JobOperator`로 `dailySellerSettlementJob`을 명시 실행한다. `spring.batch.job.enabled`의 기본값은 계속 `false`로 유지하며 운영용 `ApplicationRunner`를 추가하지 않는다.

Job parameter는 다음과 같이 전달한다.

- `settlementDate`: 기존 계약과 같이 JobInstance 식별 parameter
- `platformFeeRateBps`: 기존 계약과 같이 비식별 parameter

각 iteration이 독립 schema를 사용하므로 동일한 `settlementDate`를 반복해도 이전 JobInstance와 충돌하지 않는다. 임의의 식별 parameter를 추가하거나 run ID로 완료된 JobInstance의 중복 실행 제약을 우회하지 않는다.

Job duration은 `JobOperator.start` 호출 준비가 끝난 시점부터 Job이 terminal status에 도달한 시점까지로 측정하고 Spring Batch의 `JobExecution` 시작·종료 시각과 교차 확인한다. Step duration은 각 `StepExecution`의 시작·종료 시각을 기준으로 한다. warm-up iteration도 동일한 Job과 데이터를 처리하지만 통계 집계에서 제외한다.

## 측정과 결과 기록

### 결과 위치

각 task 실행은 다음 디렉터리에 충돌하지 않는 실행 ID를 사용한다.

```text
apps/backend/batch/build/reports/performance/daily-seller-settlement/{run-id}/
```

`run-id`는 결과 파일 경로 식별용일 뿐 JobParameter나 업무 데이터에는 사용하지 않는다. 결과는 build 산출물이며 Git에 포함하지 않는다.

### 결과 파일

새 직렬화 dependency 없이 UTF-8 properties와 CSV로 다음 파일을 생성한다.

| 파일 | 내용 |
| --- | --- |
| `scenario.properties` | 입력 설정, 데이터 분포, 실제 batch·pool 설정, JVM·OS·PostgreSQL과 실행 환경 |
| `iterations.csv` | iteration 종류, 상태, 준비 단계별 시간, Job 시간과 전체 처리량 |
| `steps.csv` | iteration별 Step 상태, read/write/filter/skip/commit/rollback 수, 시간과 처리량 |
| `resources.csv` | 측정 시각별 JVM heap·process CPU와 PostgreSQL container CPU·memory 표본 |
| `validation.properties` | 예상·실제 건수와 금액, 지갑·원장 검산 및 최종 성공 여부 |
| `summary.properties` | 측정 iteration의 최소·최대·평균, p50·p95·p99와 전체 판정 |

percentile은 측정 iteration의 Job duration과 같은 Step 이름의 duration 집합에서 계산한다. 측정 iteration이 percentile을 안정적으로 해석하기에 부족한 경우에도 계산값과 표본 수를 함께 기록하며 과도한 정밀도를 주장하지 않는다.

자원 sampler는 Job 실행 구간에만 동작한다. JVM 값은 표준 management API를 사용하고 PostgreSQL 값은 Testcontainers가 사용하는 Docker client의 container stats에서 얻는다. 자원 표본 수집이 지원되지 않는 실행 환경에서는 해당 필드를 비워 성공처럼 숨기지 않고 `UNAVAILABLE`과 원인을 기록한다. 자원 측정 불가만으로 업무 검산에 성공한 Job을 실패시키지는 않지만, 결과 요약은 자원 지표가 없는 불완전한 측정임을 표시한다.

로그와 결과에는 datasource password, 전체 JDBC credential, 개인정보나 개별 원장 내용을 기록하지 않는다. JDBC URL을 기록할 때도 credential parameter를 제거한다.

### 콘솔 출력

task 종료 시 다음 항목만 간결하게 출력한다.

- 실행 ID와 결과 디렉터리
- warm-up 및 측정 횟수
- 판매자와 주문 수
- 유효한 chunk, page, fetch와 connection pool 설정
- Job duration과 처리량의 요약 통계
- 업무 검산과 자원 측정 가능 여부

대량의 iteration별 세부 값은 콘솔에 반복 출력하지 않고 결과 파일에서 확인하게 한다.

## 검산 계약

각 iteration은 다음 항목을 모두 검증한다.

1. Job과 모든 기술 Step의 상태가 `COMPLETED`다.
2. Step의 skip 수와 rollback 수가 `0`이다.
3. `settlement_details` 건수가 `order-count`와 같다.
4. `seller_settlements` 건수가 `seller-count`와 같다.
5. 정산 상세와 판매자별 정산의 gross, fee와 net 합계가 생성 설정에서 계산한 예상값과 같다.
6. 모든 판매자별 정산이 `CREDITED`이고 `credited_at`을 가진다.
7. `settlement_runs`가 `COMPLETED`이고 최종 집계와 완료 시각이 존재한다.
8. 양수 `net_amount`인 판매자별 정산마다 `SELLER_SETTLEMENT` 원장이 정확히 하나 존재한다.
9. 원장 사용자, 방향, 금액과 source ID가 판매자별 정산과 일치한다.
10. wallet 잔액 합계와 원장 입금액 합계가 전체 `net_amount`와 같다.
11. 사용자별 wallet 잔액과 마지막 원장의 `balance_after`가 일치한다.
12. Spring Batch의 read/write/commit count가 생성된 입력과 설정된 chunk 계약에 부합한다.

Job이 실패하거나 위 검산 중 하나라도 실패하면 해당 iteration과 전체 task를 실패로 기록한다. 실패한 실행의 timing은 진단 자료로 남길 수 있지만 성공한 성능 표본이나 percentile에 포함하지 않는다. 일부 측정 iteration만 성공한 결과도 전체 성공으로 간주하지 않는다.

## 오류 처리와 안전장치

다음 상황에서는 Job 실행 전에 실패한다.

- 필수 설정 누락 또는 범위 오류
- 총액 계산 overflow 가능성
- Docker 또는 Testcontainers 사용 불가
- PostgreSQL 시작 실패
- API migration 위치 확인 또는 migration 적용 실패
- 합성 데이터 insert나 사전 건수 검증 실패
- 외부 datasource URL 또는 credential로 Testcontainer 설정을 덮어쓰려는 시도

다음 상황에서는 실행 결과를 실패로 기록하고 Gradle task를 실패시킨다.

- Job 또는 Step 실패
- 설정한 timeout 안에 Job이 terminal status에 도달하지 않음
- Spring Batch counter 불일치
- 업무 건수, 상태 또는 금액 불일치
- wallet과 ledger 불변식 위반
- 결과 파일 작성 실패

성능 테스트는 데이터 오류를 skip하거나 retry하여 성공 표본으로 만들지 않는다. 테스트 실패 후 외부 데이터베이스를 정리하거나 수정하지 않으며 자신이 시작한 container와 application context만 종료한다.

## 구성 요소와 책임

성능 테스트 지원 코드는 다음 책임으로 분리한다. 실제 패키지와 클래스는 이 책임을 드러내는 이름을 사용하며 범위가 불명확한 `util`, `helper`, `common` 패키지를 만들지 않는다.

- 시나리오 설정: 외부 property binding, 기본값과 사전 검증
- container fixture: PostgreSQL lifecycle, iteration schema와 migration 적용
- 데이터 생성: 결정론적 입력 계산, batch insert와 예상 결과 생성
- Job 실행: application context lifecycle, JobParameter 구성과 `JobOperator` 호출
- 자원 측정: JVM과 PostgreSQL container 표본 수집
- 결과 검산: Batch metadata와 업무 데이터 불변식 확인
- 결과 기록: properties·CSV 생성과 콘솔 요약

프로덕션 코드는 `DailySellerSettlementJobConfiguration`의 상수를 외부 설정 객체로 대체하는 범위에서만 변경한다. 데이터 생성, container lifecycle, 측정과 보고 기능을 `main` source set에 두지 않는다.

## 검증 전략

성능 환경 자체는 다음 검증을 가진다.

1. 필수 설정이 없거나 판매자·주문 수 관계가 잘못되면 container 시작 전에 실패한다.
2. 같은 seed와 설정이 같은 판매자별 주문 분포와 예상 금액을 만든다.
3. 주문 수가 판매자 수로 나누어떨어지지 않아도 모든 판매자가 최소 한 건을 가지고 최대 한 건 차이의 균등 분포가 된다.
4. 작은 데이터로 migration, seed, 전체 Job과 최종 검산이 한 번에 성공한다.
5. 둘 이상의 measurement iteration이 독립 schema와 JobRepository를 사용한다.
6. warm-up 결과가 percentile과 평균 계산에서 제외된다.
7. Job 또는 검산을 의도적으로 실패시키면 Gradle task가 실패하고 성공 통계에서 제외된다.
8. 외부 datasource 설정을 주입해도 Testcontainer 외의 DB에 연결하지 않는다.
9. 기본 batch tuning 설정이 기존 `100` 동작을 유지한다.
10. chunk, page, fetch와 Hikari 설정을 외부 주입하면 실제 bean과 reader/step에 반영되고 결과에도 같은 값이 기록된다.
11. 결과 파일에 credential과 개인정보가 포함되지 않는다.
12. 일반 `test`, `check`와 `build`가 성능 task를 자동 실행하지 않는다.
13. 기존 batch 통합 테스트와 전체 backend build가 설정 외부화로 회귀하지 않는다.

기능 검증용 소규모 실행은 자동화된 테스트에서 수행할 수 있지만 대규모 성능 실행과 특정 처리량 임계값을 일반 CI 성공 조건으로 두지 않는다. 성능 비교를 수행할 때는 같은 container image, JVM, host 자원, 데이터 설정과 tuning 설정을 사용한 결과끼리 비교한다.

## 결과 해석의 한계

Testcontainers 기반 결과는 동일 개발 장비나 전용 runner에서 상대 비교와 회귀 탐지에 사용한다. 다음 이유로 운영 절대 처리량을 보장하지 않는다.

- Docker와 host의 CPU·memory·disk 상태 영향을 받는다.
- 합성 데이터는 운영 데이터의 seller skew, 금액 분포와 인덱스 누적 상태를 재현하지 않는다.
- 한 task 안의 반복 실행은 같은 PostgreSQL process와 filesystem cache를 공유한다.
- 단일 JVM, 단일 partition·단일 thread 실행만 측정한다.
- 자원 sampling 자체가 작은 측정 오버헤드를 추가한다.

결과를 공유할 때는 처리량만 떼어 기록하지 않고 `scenario.properties`, 실행 환경과 검산 결과를 함께 보존한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- 운영 또는 개발 공용 PostgreSQL의 데이터 사용과 정리
- 실제 운영 데이터 dump의 반입과 익명화
- skewed·Zipf 판매자 분포, 여러 주문 금액 구간과 오류 데이터 생성
- 운영용 Job runner, scheduler, CronJob, HTTP API와 관리자 화면
- `spring.batch.job.enabled=true`를 통한 자동 실행
- multi-thread Step, partitioning, 병렬 Job과 여러 애플리케이션 인스턴스의 경합 측정
- stage별로 서로 다른 chunk, page와 fetch 설정
- 실패 Job 재시작 성능과 의도적 chunk rollback 성능 측정
- 외부 APM, Prometheus 또는 dashboard 연동
- CI의 처리량·지연 임계값과 성능 회귀 차단
- 운영 수준의 capacity 산정과 SLA 보장

멀티 thread나 partitioning을 비교하려면 ADR-025의 transaction, checkpoint, 지갑 잠금과 source 멱등성을 유지하는 별도 성능·경합 설계를 먼저 승인한다.
