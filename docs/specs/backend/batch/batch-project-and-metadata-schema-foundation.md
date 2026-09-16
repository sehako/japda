# 배치 프로젝트와 메타데이터 스키마 기반 구성

## 목적과 완료 조건

기존 API 애플리케이션과 자원 사용, 실행 주기 및 장애 범위를 분리할 수 있도록 독립 실행 가능한 Spring Batch 프로젝트를 추가한다. API와 배치에서 지갑·원장 기능을 공유할 수 있는 라이브러리 프로젝트도 함께 만들고 배치에서 이 모듈을 참조하도록 연결한다. 실제 정산 기능을 구현하기 전에 Gradle 프로젝트 경계, 실행 진입점과 Spring Batch 메타데이터 스키마 소유권을 먼저 확립하는 것이 목적이다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- Gradle이 기존 API root project, `:batch`, `:modules:ledger`를 하나의 multi-project build로 인식한다.
- `:batch`는 독립적인 Spring Boot 실행 파일을 만들 수 있고 `:modules:ledger`는 실행 파일이 아닌 plain jar를 만든다.
- `:batch`는 `:modules:ledger`에 의존하지만 기존 API root project에는 의존하지 않는다.
- 기존 API root project가 소유한 Flyway migration으로 PostgreSQL용 Spring Batch 메타데이터 스키마를 생성한다.
- 배치는 Flyway와 Spring Batch의 자동 스키마 초기화를 수행하지 않으며 필요한 메타데이터 스키마가 없으면 시작 단계에서 실패한다.
- 세 프로젝트의 build와 관련 테스트가 통과하고 기존 API의 동작과 산출물에 회귀가 없다.

이 설계는 [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 승인된 프로젝트 및 데이터베이스 경계를 구현 가능한 범위로 구체화한다. 새로운 아키텍처 결정을 추가하거나 기존 결정을 변경하지 않는다.

## Gradle 프로젝트 구조

`apps/backend`의 기존 root project와 `src` 위치는 API 애플리케이션으로 유지한다. root project를 집계 전용 프로젝트로 바꾸거나 기존 API 소스를 하위 프로젝트로 이동하지 않는다.

```text
apps/backend
├── build.gradle.kts
├── settings.gradle.kts
├── src
│   └── main
│       ├── kotlin                    # 기존 API 애플리케이션
│       └── resources
│           └── db/migration          # API가 소유하는 전체 Flyway migration
├── batch
│   ├── build.gradle.kts
│   └── src/main
│       ├── kotlin/io/github/sehako/japda/batch
│       │   ├── BatchApplication.kt
│       │   └── global/infrastructure/schema
│       │       └── BatchMetadataSchemaVerifier.kt
│       └── resources
│           └── application.yaml
└── modules
    └── ledger
        └── build.gradle.kts
```

`settings.gradle.kts`에는 `:batch`와 `:modules:ledger`를 등록한다. 각 하위 프로젝트는 기존 root project와 동일한 Kotlin, Spring Boot 및 dependency management 버전을 사용한다. 이번 작업을 이유로 root project의 기존 plugin, dependency 또는 task 구성을 공통 convention plugin이나 새 build logic으로 이동하지 않는다.

의존성 방향은 다음과 같다.

```text
API root project       :batch
       │                  │
       │ 필요할 때 추가   │ implementation
       ▼                  ▼
             :modules:ledger
```

이번 범위에서는 `:batch`만 `implementation(project(":modules:ledger"))`로 원장 모듈에 연결한다. API root project의 원장 의존성은 실제 공유 application 기능을 추가하는 후속 작업에서 필요해질 때 선언한다. `:batch`가 API root project를 project dependency로 참조하거나 API의 Entity, application service 또는 presentation 코드를 source set으로 가져오지 않는다.

## 프로젝트별 구성

### 배치 애플리케이션

`:batch`에는 Kotlin JVM·Spring plugin, Spring Boot plugin과 dependency management를 적용한다. 실행 기반에는 Spring Batch starter, JDBC 접근, Kotlin reflection과 PostgreSQL runtime driver만 포함한다. 정산 구현에 필요한 JPA, QueryDSL, Web MVC 또는 외부 시스템 client dependency는 미리 추가하지 않는다. 기존 dependency만으로 기반을 구성하고, 후속 기능이 요구할 때 별도 근거를 확인해 추가한다.

`BatchApplication`은 `io.github.sehako.japda.batch`를 기준으로 배치 구성 요소를 탐색하는 독립 실행 진입점이다. API의 `BackendApplication`을 import하거나 공유하지 않는다. 아직 실행할 Job이 없으므로 Job, Step, reader, processor, writer와 업무 패키지는 생성하지 않는다. 빈 계층 패키지나 의미 없는 placeholder 클래스도 추가하지 않는다.

배치의 기본 설정은 다음 계약을 가진다.

- API와 같은 PostgreSQL 접속 설정을 환경별로 주입받되 별도의 애플리케이션 설정 파일과 process로 실행한다.
- `spring.flyway.enabled=false`로 두어 배치가 migration을 실행하지 않도록 한다.
- Spring Batch JDBC 스키마 초기화 설정을 `never`로 두어 framework가 시작 과정에서 DDL을 실행하지 않도록 한다.
- `BatchMetadataSchemaVerifier`가 애플리케이션 준비 과정에서 필수 메타데이터 테이블에 읽기 검증 쿼리를 실행한다. 테이블이 없거나 접근할 수 없으면 임시 in-memory 저장소나 자동 생성으로 대체하지 않고 시작에 실패한다.
- 이번 범위에는 실행할 Job이 없으므로 애플리케이션 시작 시 자동으로 실행되는 Job도 없다. 후속 Job은 식별 가능한 JobParameter를 받는 명시적 실행 계약을 별도로 설계한다.

Spring Batch infrastructure bean 생성만으로는 실행할 Job이 없는 시작 시점에 메타데이터 테이블 조회가 보장되지 않는다. 따라서 시작 검증을 framework의 부수 효과에 맡기지 않는다. 검증기는 스키마를 생성하거나 수정하지 않고 Spring Batch가 사용하는 기본 prefix의 필수 테이블을 현재 database user로 읽을 수 있는지만 확인한다. 실패 시 원래의 PostgreSQL 예외를 민감정보 없이 시작 실패 원인으로 남긴다.

### 원장 모듈

`:modules:ledger`는 Kotlin JVM 기반 plain jar다. Spring Boot plugin을 적용하거나 `bootJar`를 생성하지 않는다. API 또는 배치의 실행 설정과 main class를 포함하지 않는다.

이번 작업에서는 프로젝트 연결을 증명하기 위한 가짜 업무 타입, Wallet·LedgerEntry 모델, repository interface 또는 Spring bean을 만들지 않는다. 비어 있는 jar도 유효한 모듈 경계로 취급하며 Gradle의 project dependency 해석과 jar 생성으로 연결을 검증한다. 원장 application 및 domain 코드는 해당 업무 설계가 승인된 후 추가한다.

## Spring Batch 메타데이터 migration

Spring Batch 메타데이터는 JobInstance, JobExecution, StepExecution, 실행 parameter와 execution context를 저장하는 기술 스키마다. 업무 정산 데이터가 아니지만 배치의 재시작과 checkpoint를 지원하는 실행 기반이므로 프로젝트 생성과 함께 준비한다.

새 Flyway migration은 기존 API 경로인 `apps/backend/src/main/resources/db/migration`에 다음 버전으로 추가한다.

```text
V11__create_spring_batch_metadata.sql
```

migration SQL은 구현 시점에 실제로 해석되는 Spring Batch dependency에 포함된 PostgreSQL 공식 schema script를 기준으로 작성한다. 다른 Spring Batch 버전의 예제나 임의로 단순화한 DDL을 사용하지 않는다. 공식 script의 테이블, sequence, primary key, foreign key와 version column을 보존하고 Flyway가 한 번 실행하는 PostgreSQL SQL로 관리한다. 애플리케이션 시작 시 dependency 내부 script를 동적으로 실행하지 않는다.

메타데이터 테이블과 sequence에는 Spring Batch가 기대하는 기본 이름을 사용한다. 별도 table prefix, schema 분리 또는 커스텀 `JobRepository`는 이번 범위에서 도입하지 않는다. 기존 업무 테이블과 같은 PostgreSQL schema 및 transaction manager를 사용하는 [ADR-024](../../../architecture/decisions/ADR-024-backend-api-batch-ledger-multi-project.md)의 결정을 따른다.

API가 시작되거나 배포 migration 절차가 수행될 때 기존 migration 다음으로 `V11`을 적용한다. 배치는 API migration이 성공한 이후에만 실행한다. 배치가 database user 권한 부족이나 메타데이터 부재를 감지했을 때 DDL 권한으로 자체 복구하지 않는다.

이번 migration에는 다음 항목을 포함하지 않는다.

- Wallet, LedgerEntry, SettlementRun, SettlementDetail 또는 SellerSettlement 업무 테이블
- 기존 결제·주문·판매 데이터 변환이나 backfill
- 샘플 JobInstance 또는 운영 초기 데이터 삽입
- 메타데이터 정리·보존 정책을 위한 procedure 또는 scheduler

Spring Batch dependency 버전을 이후 올리면서 공식 메타데이터 스키마 변경이 필요하면 기존 migration을 수정하지 않고 새 Flyway migration으로 전환한다.

## 실행 및 실패 경계

API와 배치는 별도 JVM과 실행 파일로 배포한다. API가 Flyway migration을 완료하기 전에는 새 버전의 배치를 실행하지 않는다. 동일한 database를 사용하더라도 connection pool과 process lifecycle은 공유하지 않는다.

이번 단계에서는 애플리케이션 내부 `@Scheduled`, 운영 Cron 표현식, Kubernetes CronJob 또는 별도 scheduler 제품을 선택하지 않는다. 실제 Job이 생길 때 실행 주기, JobParameter와 중복 실행 정책을 함께 결정한다. 배치 프로젝트의 생성 자체가 정산 또는 다른 업무 처리를 시작하지 않는다.

메타데이터 스키마가 없거나 접근할 수 없는 경우는 배포 순서 또는 환경 구성 오류다. 배치는 이를 무시하거나 자동 DDL로 우회하지 않고 실패해야 한다. DB 비밀번호, token과 같은 민감정보는 설정 파일에 직접 기록하지 않고 환경별 secret으로 제공한다.

## 검증 전략

다음 검증을 수행한다.

1. Gradle project 조회로 root project, `:batch`, `:modules:ledger`가 올바른 경로에 등록됐는지 확인한다.
2. `:modules:ledger:jar`를 실행하여 plain jar가 생성되고 `bootJar` 대상이 아닌지 확인한다.
3. `:batch:bootJar`를 실행하여 API와 별개의 main class를 가진 실행 jar가 생성되는지 확인한다.
4. 전체 backend test와 build를 실행하여 기존 API plugin, dependency, REST Docs와 bootJar 구성에 회귀가 없는지 확인한다.
5. PostgreSQL Testcontainers에 API root project의 전체 Flyway migration을 적용하고 Spring Batch 메타데이터 테이블과 sequence, 주요 foreign key가 생성되는지 검증한다.
6. 배치 통합 설정에서 자동 스키마 초기화가 비활성화되었는지 확인한다. migration이 적용된 PostgreSQL에서는 Batch infrastructure와 `BatchMetadataSchemaVerifier`가 정상적으로 초기화되고, 비어 있는 PostgreSQL에서는 검증기가 필요한 메타데이터 부재를 감지하여 시작을 실패시키는지 확인한다.

테스트는 dependency에 포함된 공식 schema script를 별도 테스트 fixture로 복제하여 성공시키지 않는다. 실제 `V11` migration을 검증 대상으로 사용해야 한다. 테스트를 위해 배치에서 API production code에 project dependency를 추가하지 않으며, 필요한 경우 테스트 task가 root migration 디렉터리를 명시적인 입력으로 사용한다.

## 범위 제외

이번 설계에는 다음 작업을 포함하지 않는다.

- 판매자 일일 정산 Job과 Step 구성
- reader, processor, writer, listener 및 retry·skip 정책
- Wallet·LedgerEntry와 정산 domain/application 구현
- 정산 업무 테이블과 기존 데이터 migration
- API에서 원장 모듈을 사용하는 기능
- 배치 scheduler와 운영 배포 환경 선택
- 멀티 thread, partitioning 및 성능 최적화
- Spring Batch 메타데이터 보존·삭제 정책

후속 정산 구현은 [ADR-025](../../../architecture/decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 수집, 검산·확정, 지갑 입금 단계와 멱등성 규칙을 별도 명세로 구체화한 뒤 진행한다.
