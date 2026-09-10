# 백엔드 아키텍처 지침

## 기본 구조

패키지는 기능 또는 도메인 단위로 구성한다.

각 도메인은 필요에 따라 다음 계층을 가진다.

{domain}
├── presentation
├── application
├── domain
├── infrastructure
└── util

필요하지 않은 계층이나 패키지는 미리 만들지 않는다.

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

### util
- 해당 도메인의 단순 보조 기능만 관리
- 비즈니스 규칙 작성 금지

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

## 도메인 규칙

- Entity가 자신의 상태와 상태 전이 규칙을 관리한다.
- 단일 Entity에서 판단 가능한 규칙은 Entity에 둔다.
- 여러 Domain 객체의 흐름은 Application Service가 조율한다.
- 하나의 Entity에 속하기 어려운 핵심 규칙만 Domain Service로 분리한다.
- 의미와 규칙이 있는 값만 Value Object로 만든다.

## 외부 시스템

- PG 등 핵심 흐름에 영향을 주는 외부 시스템은 필요하면 Interface로 추상화한다.
- 모든 기술 요소에 불필요한 Interface를 만들지 않는다.
- 외부 API 호출을 DB Transaction 안에 장시간 포함하지 않는다.
- 상품 이미지 원본은 비공개 AWS S3에 백엔드 검증 후 저장하고, DB에는 접근 URL 대신 객체 키와 메타데이터를 저장한다. [ADR-005](decisions/ADR-005-product-image-storage-with-s3.md)를 따른다.
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
- 비즈니스 규칙을 util에 작성
- 도메인 규칙·프로토콜·설정의 의미를 가진 문자열과 숫자 리터럴은 코드에 직접 작성하지 않고, 의도가 드러나는 이름의 상수 또는 설정값으로 관리한다.
