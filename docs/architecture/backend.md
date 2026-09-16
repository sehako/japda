# 백엔드 아키텍처 지침

## Gradle 프로젝트와 실행 애플리케이션

`apps/backend`는 기존 root project를 API 애플리케이션으로 유지하는 Gradle 멀티 프로젝트다. 독립 실행 가능한 배치 애플리케이션은 `:batch`, API와 배치가 공유하는 사용자 지갑·원장 기능은 plain jar인 `:modules:ledger`에 둔다. 기존 백엔드의 다른 도메인은 필요한 결정 없이 하위 프로젝트로 이동하지 않는다. [ADR-024](decisions/ADR-024-backend-api-batch-ledger-multi-project.md)를 따른다.

API와 배치는 서로 HTTP로 호출하지 않고 필요한 application 기능을 라이브러리 의존으로 재사용한다. `:batch`는 API root project의 presentation 또는 application에 의존하지 않으며 기존 거래 데이터는 배치 전용 JDBC projection으로 읽는다.

두 애플리케이션은 동일한 PostgreSQL 스키마를 사용한다. Flyway migration 파일과 실행 책임은 API root project에 두고 배치에서는 Flyway와 Spring Batch의 자동 스키마 초기화를 비활성화한다. API migration을 완료한 뒤 배치를 실행하며 배치는 시작 시 필요한 스키마가 없으면 즉시 실패한다.

## 기본 구조

패키지는 기능 또는 도메인 단위로 구성하고, 각 계층 내부는 역할 또는 구체적인 책임별 하위 패키지로 나눈다. [ADR-016](decisions/ADR-016-backend-role-based-package-structure.md)을 따른다.

각 도메인은 필요에 따라 다음 계층을 가진다.

{domain}
├── presentation
│   ├── controller
│   ├── request
│   └── serializer
├── application
│   ├── service
│   ├── dto
│   └── response
├── domain
│   ├── model
│   └── repository
├── infrastructure
│   └── persistence
└── exception

역할별 하위 패키지는 클래스나 파일 수와 관계없이 사용한다. 단일 파일이라는 이유로 계층 패키지 바로 아래에 배치하지 않는다. 실제 파일이 생길 때만 패키지를 만들며 빈 패키지는 미리 만들지 않는다.

도메인 내부에 별도의 기능 경계가 필요하면 `{domain}/{layer}/{feature}/{role}` 순서로 구성한다. `util`, `helper`, `common`처럼 범위가 불명확한 패키지는 사용하지 않고 `cursor`, `serializer`, `config`처럼 구체적인 책임이 드러나는 이름을 사용한다.

기능별 예외는 `{domain}/exception`에 둔다. Spring에 의존하지 않는 공통 예외 추상화는 `global/exception`에 두고, Spring `ProblemDetail`을 사용하는 HTTP 오류 변환은 `global/error`에 둔다.

의존성 방향:

presentation → application → domain
infrastructure → domain
domain → {domain}.exception → global.exception

## 계층 책임

### presentation
- HTTP 요청/응답 처리
- Request 객체 관리
- Request → Application Dto 변환
- 비즈니스 로직 금지

### application
- 유스케이스와 비즈니스 흐름 조율
- Transaction 관리
- Repository 및 외부 시스템 호출
- Dto 입력 관리
- Domain 결과를 Response로 변환
- Entity 상태 직접 변경 금지
- Spring Web과 HTTP 전송 타입에 의존하지 않는다. Presentation이 Request와 파일 입력을 application Dto 및 입력 인터페이스로 변환한다.
- `@Service`, `@Transactional` 등 유스케이스 실행과 트랜잭션 지원을 위한 Spring 기능은 사용할 수 있다. [ADR-007](decisions/ADR-007-application-layer-spring-web-independence.md)을 따른다.

### domain
- JPA Entity = Domain Entity
- Entity, Value Object, 상태 Enum, 핵심 비즈니스 규칙 관리
- Repository Interface 정의
- 상태 변경은 도메인 메서드로 처리
- 공개 Setter를 통한 상태 변경 금지

### infrastructure
- Spring Data JPA, QueryDSL, Redis, PG, Kafka 등 기술 구현
- Domain Repository Interface 구현
- 외부 시스템 Client 구현

## DTO 규칙

HTTP Request
→ Request
→ Application Dto
→ Domain
→ Response
→ HTTP Response

- Request는 presentation
- Dto와 Response는 application
- Entity를 API Response로 직접 반환하지 않는다.
- 파일 업로드는 application이 입력 인터페이스를 정의하고 presentation이 Spring Web 파일 타입을 어댑터로 변환한다.

## Repository 규칙

- Repository Interface는 domain
- Spring Data JpaRepository와 구현체는 infrastructure
- application은 JpaRepository, EntityManager, QueryDSL 구현체에 직접 의존하지 않는다.

## JPA 규칙

- FK를 가진 쪽의 단방향 연관관계를 기본으로 한다.
- 도메인 간 결합이 불필요하면 Entity 대신 ID로 참조한다.
- @OneToMany 컬렉션 매핑은 기본적으로 사용하지 않는다.
- 양방향 연관관계를 지양한다.
- CascadeType.ALL을 기본값처럼 사용하지 않는다.
- @OneToOne은 실제 1:1 관계가 DB 제약과 도메인 규칙으로 보장될 때만 사용한다.
- 연관관계 Fetch는 기본 LAZY로 한다.
- 자식 목록은 Repository Query, Projection, Application 조립으로 조회한다.
- 여러 도메인의 읽기 정보가 항상 함께 필요한 구매자 판매 상품 목록은 쓰기 Repository와 분리된 조회 전용 Repository 및 projection으로 단일 join 조회한다. 쓰기 Entity 사이의 JPA 연관관계는 추가하지 않는다. [ADR-010](decisions/ADR-010-buyer-sale-product-query-model.md)을 따른다.
- 구매자 체크아웃은 `order` 영역의 조회 전용 Repository에서 판매·상품·대표 이미지·구매자 저장 배송지를 단일 읽기 쿼리로 조합한다. 쓰기 Entity 사이의 JPA 연관관계는 추가하지 않는다. [ADR-018](decisions/ADR-018-buyer-checkout-query-model.md)을 따른다.

## 도메인 규칙

- Entity가 자신의 상태와 상태 전이 규칙을 관리한다.
- 단일 Entity에서 판단 가능한 규칙은 Entity에 둔다.
- 여러 Domain 객체의 흐름은 Application Service가 조율한다.
- 하나의 Entity에 속하기 어려운 핵심 규칙만 Domain Service로 분리한다.
- 상품 원본과 준비 상태는 `Product`, 판매일·가격·판매 수량은 `Sale`, 판매일별 정원은 판매 영역의 `SaleDay`가 관리한다. 판매 등록 application은 상품 Repository로 소유권과 `READY`를 확인하며 판매 Entity는 상품을 ID로 참조한다. 판매 등록으로 상품 상태를 변경하지 않는다. [ADR-009](decisions/ADR-009-product-and-sale-domain-boundaries.md)을 따른다.
- 단일 상품 주문과 결제 대기 예약은 `Order`가 관리한다. 주문은 `saleId`로 판매 일정을 참조하고 주문 application이 판매·상품 Repository를 조율하며, 주문 Entity와 판매·상품 Entity 사이에 JPA 연관관계를 추가하지 않는다. 주문 행의 상태와 만료 시각을 예약 기록으로 사용하고, 판매 일정 행을 잠근 뒤 유효한 예약 수량을 집계해 초과 판매를 막는다. [ADR-015](decisions/ADR-015-order-row-reservation-with-sale-lock.md)을 따른다.
- 집중되는 주문의 품절 요청을 DB 접근 전에 거절하기 위해 실제 주문이 발생한 판매 일정의 가용 재고만 Redis에 30초 동안 동적으로 적재한다. Single-flight로 초기화를 제한하고 Lua script로 재고를 원자적으로 선점하되, 기존 판매 일정 잠금과 주문 예약 집계를 최종 정합성 기준으로 유지한다. Redis 장애는 기존 DB 주문 경로로 우회하며 TTL을 요청마다 연장하지 않는다. [ADR-026](decisions/ADR-026-dynamic-redis-inventory-reservation.md)을 따른다.
- 결제 시도는 주문 ID를 참조하는 별도 `Payment`로 저장한다. 승인 중 또는 수동 확인 대상인 주문은 만료 후에도 예약하고, 검증된 결제 완료 수량은 판매 완료 수량으로 계속 집계한다. 결제 확정도 판매 일정 행을 먼저 잠그며 `Payment.APPROVED`와 `Order.PAID`를 함께 기록한다. [ADR-019](decisions/ADR-019-payment-attempt-and-reservation-consistency.md)을 따른다.
- 플랫폼 지갑은 판매자 역할이 아니라 `users.id`에 귀속하며 사용자별 하나만 둔다. 잔액 변경은 수정·삭제하지 않는 원장 항목과 같은 트랜잭션에서 처리하고 원인 종류와 원인 ID로 멱등성을 보장한다. 판매자 일일 정산은 구매별 근거를 보존하고 판매자별로 합산·검산한 뒤 연결된 사용자의 지갑에 입금한다. [ADR-025](decisions/ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)를 따른다.
- 의미와 규칙이 있는 값만 Value Object로 만든다.

## 배치

- 배치 Job은 API HTTP 흐름과 분리된 `:batch` 애플리케이션에서 실행한다.
- 초기 판매자 일일 정산 Job은 수집, 검산·확정, 지갑 입금 Step을 단일 파티션·단일 thread로 순서대로 실행한다.
- Job 입력은 식별 가능한 JobParameter로 받고 실패한 동일 JobInstance를 checkpoint부터 재시작한다. 임의의 run ID로 중복 실행 제약을 우회하지 않는다.
- 금액과 정산 소유권 오류는 skip하지 않고 Job을 실패시킨다. 일시적인 인프라 오류에만 제한적인 retry를 사용한다.
- 배치 업무 데이터와 Spring Batch 메타데이터는 같은 PostgreSQL과 transaction manager를 사용해 chunk 변경과 checkpoint를 함께 commit한다.
- 성능 결과에는 데이터 분포, 실행 환경, chunk·page·connection 설정, 처리량, 지연 분포, 자원 사용량과 금액 검산 결과를 함께 기록한다.

## 외부 시스템

- PG 등 핵심 흐름에 영향을 주는 외부 시스템은 필요하면 Interface로 추상화한다.
- 모든 기술 요소에 불필요한 Interface를 만들지 않는다.
- 외부 API 호출을 DB Transaction 안에 장시간 포함하지 않는다.
- 상품 이미지 원본은 비공개 AWS S3에 백엔드 검증 후 저장하고, DB에는 접근 URL 대신 객체 키와 메타데이터를 저장한다. [ADR-005](decisions/ADR-005-product-image-storage-with-s3.md)를 따른다.
- 구매자 판매 상품 목록에는 대표 이미지 객체 키를 직접 명명하거나 완전한 URL을 생성하지 않고 이미지 상대 경로를 반환한다. 이미지 제공 도메인 결합과 CloudFront 연동은 클라이언트 및 후속 인프라 작업의 책임으로 둔다. [ADR-011](decisions/ADR-011-product-image-relative-path-response.md)을 따른다.
- 상품 이미지 S3 업로드는 DB Transaction 밖에서 수행하고, 메타데이터 저장과 상품 상태 전환은 짧은 DB Transaction으로 처리한다. 확정된 실패는 보상 삭제하며 커밋 결과가 불명확하면 DB 참조 확인 전 객체를 삭제하지 않는다.

## 공통 코드

- 여러 도메인에서 실제로 공유되는 기술 코드만 공통 영역으로 이동한다.
- 두 곳에서 사용된다는 이유만으로 바로 공통화하지 않는다.
- 기능별 오류 타입은 Spring HTTP 타입에 의존하지 않는다.
- HTTP 오류 응답은 Spring `ProblemDetail`을 사용한다.

## API 문서화

- 백엔드 HTTP API 문서는 Spring REST Docs로 생성한다.
- MockMvc 기반 Presentation Test에서 요청과 응답을 검증하고 문서 스니펫을 생성한다.
- 요청 헤더, 경로·쿼리 매개변수, 요청·응답 필드와 오류 응답 중 해당 API가 공개하는 계약을 문서화한다.
- Asciidoctor로 HTML 문서를 생성하고, 문서 생성과 검증을 Gradle `build`에 포함한다.
- 생성된 스니펫과 HTML 문서는 빌드 산출물로 취급하며 Git에 포함하지 않는다.
- OpenAPI 변환, 대화형 문서와 외부 게시 방식은 별도 결정이 있기 전까지 기본 범위에 포함하지 않는다.

## 테스트

- 테스트 클래스와 메서드에 @DisplayName을 사용한다.
- 테스트 메서드 이름은 한글로 작성한다.
- 형식은 `동작_기대결과`를 기본으로 한다.
- 테스트 코드는 대상 프로덕션 코드의 패키지를 따른다. 여러 계층을 함께 검증하는 통합 테스트는 해당 도메인 패키지에 둘 수 있다.
- Domain Test는 가능하면 Spring Context 없이 작성한다.
- Persistence Test는 PostgreSQL Testcontainers 사용을 우선한다.

## 네이밍

Controller: {Domain}Controller
Request: {Action}{Domain}Request
Dto: {Action}{Domain}Dto
Response: {Domain}Response
Service: {Domain}Service
Repository: {Domain}Repository
JpaRepository: {Domain}JpaRepository
Repository 구현체: {Domain}RepositoryImpl
외부 Client: {Service}Client
상태 Enum: {Domain}Status

## 금지 사항

- Service에서 Entity 상태 직접 변경
- Entity 공개 Setter 남용
- Entity를 API Response로 직접 반환
- application에서 JpaRepository 직접 사용
- 무분별한 @OneToMany / 양방향 연관관계 / CascadeType.ALL
- 패턴을 맞추기 위한 불필요한 Interface, Mapper, Facade 생성
- `util`, `helper`, `common`처럼 책임이 불명확한 패키지 생성
- 도메인 규칙·프로토콜·설정의 의미를 가진 문자열과 숫자 리터럴은 코드에 직접 작성하지 않고, 의도가 드러나는 이름의 상수 또는 설정값으로 관리한다.
