# ExecPlan: 판매자 상품 등록 API 구현

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/prd.md`의 한정판 상품 판매 및 판매자 기능, 2026-09-09 백엔드 상품 등록 API 브레인스토밍

이 ExecPlan의 범위는 판매자가 한정판 상품의 기본 정보를 등록하고 PostgreSQL에 저장하는 하나의 독립적으로 검증 가능한 백엔드 기능으로 제한한다. 이미지 업로드와 판매 일정 등록은 이 기능이 제공하는 `productId`를 사용하는 후속 작업이다.

## 목적과 인수 기준

이 변경 뒤에는 판매자가 자신의 임시 식별자를 헤더로 전달하고 상품명, 설명과 KRW 정수 가격을 등록할 수 있다. 백엔드는 입력 규칙을 검증하고 `DRAFT` 상품을 PostgreSQL에 저장한 뒤 생성된 상품과 위치를 반환한다.

- 유효한 `X-Seller-Id`와 상품 본문으로 `POST /api/products`를 호출하면 `201 Created`, `Location: /api/products/{id}`와 생성된 상품 응답을 받는다.
- 저장된 상품은 요청의 판매자 ID, 공백을 제거한 상품명과 설명, 가격, `DRAFT` 상태와 생성 시각을 가진다.
- 헤더가 없거나 양의 `Long`이 아니면 `400 Bad Request`를 받는다.
- 상품명, 설명 또는 가격이 입력 규칙을 위반하면 `400 Bad Request`와 `ProblemDetail` 형식의 필드별 오류를 받는다.
- PostgreSQL Testcontainers에서 Flyway가 최초 스키마를 만들고 JPA가 해당 스키마와 정상 연동된다.

이번 계획은 실제 판매자 존재 여부와 권한 검증, JWT 인증·인가, 이미지 업로드와 연결, `READY` 상태 전환, 판매 일정과 수량 설정, 상품 수정·조회·삭제, 상품 등록 멱등성 처리를 포함하지 않는다.

## 맥락과 구현 접근

백엔드는 현재 Spring Boot Web MVC 스캐폴드와 Context Test만 존재한다. 상품 기능을 추가할 때 `docs/architecture/backend.md`의 기능 단위 패키지와 `presentation → application → domain`, `infrastructure → domain` 의존성 규칙을 따른다.

- `docs/prd.md`: 상품과 판매 정보를 분리하고 동일 상품에 여러 판매 일정을 둘 수 있어야 한다고 정의한다.
- `docs/architecture/backend.md`: Domain Entity와 JPA Entity를 하나로 사용하고 Domain에 Repository Interface를 두도록 규정한다.
- `docs/architecture/decisions/ADR-002-backend-persistence-stack.md`: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers 사용 결정을 기록한다.
- `apps/backend/build.gradle.kts`: 현재 Web MVC와 기본 테스트 의존성만 있으며 영속성, Bean Validation과 Testcontainers 의존성을 추가해야 한다.
- `apps/backend/src/main/resources/application.yaml`: 애플리케이션 이름 외에 데이터 소스, JPA와 Flyway 설정이 없다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/BackendApplication.kt`: 모든 기능 패키지를 스캔하는 루트 애플리케이션 클래스다.
- `apps/backend/src/test/kotlin/io/github/sehako/japda/BackendApplicationTests.kt`: 데이터베이스 도입 뒤 PostgreSQL 컨테이너를 제공하도록 보완해야 하는 Context Test다.

`product` 패키지는 타입이 적은 초기 단계이므로 `request`, `response`, `dto` 하위 패키지를 미리 만들지 않고 다음과 같이 구성한다.

```text
io.github.sehako.japda.product
├── presentation
│   ├── ProductController
│   ├── ProductExceptionHandler
│   └── CreateProductRequest
├── application
│   ├── ProductService
│   ├── CreateProductDto
│   └── ProductResponse
├── domain
│   ├── Product
│   ├── ProductStatus
│   └── ProductRepository
└── infrastructure
    ├── ProductJpaRepository
    └── ProductRepositoryImpl
```

`ProductController`는 HTTP 입력을 Application DTO로 바꾸고, `ProductService`는 트랜잭션 안에서 상품 생성과 Repository 저장을 조율한다. `Product`는 생성 규칙과 초기 상태를 보장한다. Application 계층은 `JpaRepository`에 직접 의존하지 않는다.

현재 인증 기능이 없으므로 `X-Seller-Id`를 개발 단계의 임시 판매자 식별자로 사용한다. Controller가 이 헤더를 추출해 Application Service에 넘긴다. 회원 테이블에 대한 외래 키와 판매자 존재·권한 검증은 추가하지 않는다. JWT 인증이 도입되면 요청 본문과 Application Service 계약은 유지하고 Controller의 식별자 공급 방식을 인증 주체 기반으로 교체한다.

상품은 이미지 없이 `DRAFT`로 생성된다. 후속 이미지 업로드 기능은 반환된 `productId`에 이미지를 연결하고 필수 이미지가 갖춰지면 `READY`로 전환한다. 판매 일정 기능은 `READY` 상품만 허용한다. 이 계획에서는 `READY` 전환 메서드나 이미지 관련 코드를 미리 구현하지 않는다.

## 인터페이스와 의존성

### HTTP 계약

요청은 다음과 같다.

```http
POST /api/products
X-Seller-Id: 123
Content-Type: application/json

{
  "name": "한정판 스니커즈",
  "description": "브랜드 협업 한정판 상품",
  "price": 259000
}
```

성공 응답은 다음과 같다.

```http
HTTP/1.1 201 Created
Location: /api/products/1
Content-Type: application/json
```

```json
{
  "id": 1,
  "sellerId": 123,
  "name": "한정판 스니커즈",
  "description": "브랜드 협업 한정판 상품",
  "price": 259000,
  "status": "DRAFT",
  "createdAt": "2026-09-09T12:00:00Z"
}
```

동일 판매자가 같은 이름의 상품을 여러 개 등록하는 것은 허용하며 멱등성 키는 받지 않는다.

검증 규칙은 다음과 같다.

- `sellerId`: 필수, 1 이상의 `Long`
- `name`: 필수, 앞뒤 공백 제거 후 1자 이상 100자 이하
- `description`: 필수, 앞뒤 공백 제거 후 1자 이상 5,000자 이하
- `price`: 필수, 1원 이상의 `Long`
- 통화: KRW로 고정하고 요청값으로 받지 않음

HTTP 검증은 presentation 계층에서 빠르게 실패시키고, 동일한 규칙을 `Product` 생성 시 다시 보장한다.

### 오류 계약

`ProductExceptionHandler`는 `@RestControllerAdvice`로 구현하고 현재 상품 API의 예외만 처리한다. Servlet 응답에 직접 쓰지 않고 `ResponseEntity.status(...).body(problemDetail)`로 반환한다. 다른 도메인에서도 같은 오류 계약을 실제로 사용하기 전에는 공통 패키지로 이동하지 않는다.

```json
{
  "type": "about:blank",
  "title": "요청 값이 올바르지 않습니다.",
  "status": 400,
  "detail": "상품 등록 요청을 확인해 주세요.",
  "instance": "/api/products",
  "errors": {
    "name": "상품명은 필수입니다.",
    "price": "가격은 1원 이상이어야 합니다."
  }
}
```

- 헤더 누락, 숫자 변환 실패, 0 이하 판매자 ID는 `400 Bad Request`다.
- 상품명, 설명, 가격 검증 실패는 `400 Bad Request`다.
- 예상하지 못한 서버 또는 데이터베이스 오류는 `500 Internal Server Error`이며 내부 메시지와 데이터베이스 정보를 노출하지 않는다.
- 인증 도입 전이므로 `401 Unauthorized`와 `403 Forbidden`은 이 API의 현재 계약에 포함하지 않는다.

### 도메인과 영속성 계약

`Product`는 JPA Entity이자 Domain Entity이며 다음 값을 가진다.

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `id` | `Long` | PostgreSQL이 생성 |
| `sellerId` | `Long` | 양수, 회원 테이블 외래 키 없음 |
| `name` | `String` | 공백 제거 후 1~100자 |
| `description` | `String` | 공백 제거 후 1~5,000자 |
| `price` | `Long` | 1원 이상인 KRW 정수 금액 |
| `status` | `ProductStatus` | 최초 `DRAFT` |
| `createdAt` | `Instant` | 생성 시각 |

가격은 `Product`가 보유한다. 후속 `Sale`은 판매 시작·종료 시각과 판매 수량을 별도 테이블에서 관리하며, 같은 상품의 여러 판매 일정에는 상품 가격이 동일하게 적용된다.

최초 Flyway 마이그레이션은 상품 테이블에 다음 제약을 반영한다.

- 자동 증가 `BIGINT` 기본 키
- 외래 키가 없는 양수 `seller_id`
- 최대 길이가 드러나는 상품명과 설명 열
- 1원 이상을 보장하는 `BIGINT` 가격
- Enum 순서와 무관한 문자열 상태
- 타임존을 포함하는 생성 시각

Gradle에는 Spring Data JPA, Bean Validation, PostgreSQL JDBC Driver, Flyway PostgreSQL 지원, Spring Boot Testcontainers와 PostgreSQL Testcontainers 의존성을 추가한다. Spring Boot 의존성 관리를 사용하고 개별 버전은 직접 고정하지 않는다. `application.yaml`은 데이터 소스 자격 증명을 코드에 넣지 않고 환경 변수로 받으며, JPA가 Flyway 스키마를 자동 생성하지 않고 검증하도록 설정한다.

## 작업 계획

구현은 테스트 주도 방식으로 진행한다. 각 마일스톤에서 먼저 실패하는 테스트를 작성하고, 해당 테스트를 통과시키는 최소 구현을 추가한 뒤 관련 테스트를 다시 실행한다.

### 마일스톤 1: PostgreSQL 영속성 기반 확립

`apps/backend/build.gradle.kts`에 영속성, 검증, 마이그레이션과 Testcontainers 의존성을 추가한다. `apps/backend/src/main/resources/application.yaml`에 환경 변수 기반 데이터 소스, Flyway와 JPA 스키마 검증 설정을 추가한다. `apps/backend/src/main/resources/db/migration/`에 최초 상품 테이블 마이그레이션을 작성한다. `BackendApplicationTests`가 PostgreSQL Testcontainer를 제공하도록 보완하여 실제 PostgreSQL에 Flyway가 적용된 상태에서 Spring Context가 시작되는지 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests io.github.sehako.japda.BackendApplicationTests

예상 관찰 결과: PostgreSQL 컨테이너가 시작되고 Flyway 마이그레이션과 JPA 검증 뒤 Context Test가 통과한다.

### 마일스톤 2: 상품 도메인과 Application 흐름 완성

`product/domain`에 `Product`, `ProductStatus`, `ProductRepository`를 추가하고 Spring Context 없는 단위 테스트로 생성 규칙과 `DRAFT` 초기 상태를 먼저 고정한다. `product/application`에 DTO, Response와 `ProductService`를 추가하고 Repository 대역을 사용해 저장 호출과 응답 변환을 검증한다. `product/infrastructure`에 Spring Data JPA Repository와 Domain Repository 구현체를 추가하고 PostgreSQL Testcontainers에서 저장·조회 및 열 매핑을 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.domain.*' --tests 'io.github.sehako.japda.product.application.*' --tests 'io.github.sehako.japda.product.infrastructure.*'

예상 관찰 결과: 잘못된 상품 값은 생성 시 거부되고, 정상 상품은 PostgreSQL에 저장된 뒤 모든 필드가 동일하게 조회되며 모든 대상 테스트가 통과한다.

### 마일스톤 3: 상품 등록 HTTP API 제공

`product/presentation`에 `CreateProductRequest`와 `ProductController`를 추가한다. 정상 요청의 상태, `Location`과 응답 본문을 MVC 테스트로 먼저 고정한다. 이어 잘못된 헤더와 본문을 `ProblemDetail`로 변환하는 `ProductExceptionHandler`를 `@RestControllerAdvice`로 추가하고 오류 응답 계약을 MVC 테스트로 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.presentation.*'

예상 관찰 결과: 정상 요청은 `201 Created`를 반환하고 헤더 또는 본문 검증 실패는 정해진 `400 Bad Request` 응답을 반환하며 모든 MVC 테스트가 통과한다.

### 마일스톤 4: 전체 기능 통합 검증

실제 HTTP 요청부터 PostgreSQL 저장까지 연결되는 통합 테스트를 추가한다. 등록 응답의 `id`로 데이터베이스 상태를 확인해 판매자 ID, 정규화된 문자열, 가격, 상태와 생성 시각이 저장됐음을 검증한다. 이후 전체 백엔드 테스트와 빌드를 실행하고 결과를 이 문서의 살아 있는 섹션에 기록한다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 전체 테스트와 빌드가 실패 없이 완료되고 테스트 결과에 상품 등록의 정상·오류·영속성 시나리오가 포함된다.

## 검증

- 정상 등록: 양의 `X-Seller-Id`와 유효한 본문으로 호출하면 `201`, `Location`과 `DRAFT` 상품을 반환하고 동일한 값이 PostgreSQL에 저장된다.
- 문자열 정규화: 앞뒤 공백이 있는 상품명과 설명을 등록하면 공백이 제거된 값이 응답과 데이터베이스에 저장된다.
- 판매자 헤더 검증: 헤더 누락, 숫자가 아닌 값, 0과 음수 값은 각각 `400`과 `ProblemDetail`을 반환한다.
- 본문 검증: 누락·공백·최대 길이 초과 문자열과 1원 미만 가격은 `400`과 필드별 오류를 반환한다.
- 중복 허용: 같은 판매자가 같은 이름으로 두 번 등록하면 서로 다른 ID의 상품 두 건이 생성된다.
- 내부 정보 보호: 처리되지 않은 내부 오류 응답에 예외 메시지, SQL 또는 자격 증명이 포함되지 않는다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 두 명령이 모두 종료 코드 0으로 완료된다. Docker 또는 PostgreSQL Testcontainer를 실행할 수 없는 환경이라면 실패 원인과 실행하지 못한 검증 항목을 `예상 밖의 발견`과 `결과와 회고`에 기록한다.

## 위험과 완화

- `X-Seller-Id`는 위조할 수 있다. 인증 수단으로 표현하거나 운영 권한 검증에 사용하지 않고, JWT 도입 시 Controller 입력 경계를 교체한다.
- 상품 등록 성공 뒤 후속 이미지 업로드가 실패하면 이미지 없는 `DRAFT`가 남는다. 삭제로 보상하지 않고 같은 상품에서 이미지 업로드를 재시도하도록 후속 기능을 설계한다.
- JPA Entity 규칙과 Flyway 스키마가 어긋나면 애플리케이션 시작이 실패한다. JPA 스키마 검증과 PostgreSQL Context Test로 조기에 발견한다.
- Testcontainers는 Docker 실행 환경에 의존한다. 실행 불가 시 테스트를 다른 DB로 대체하지 않고 미검증 사실을 계획에 기록한다.
- 일반적인 POST 재시도로 중복 상품이 생성될 수 있다. PRD가 결제에만 멱등성을 요구하고 상품명 중복을 허용하므로 현재 범위에서는 감수한다.

## 진행 상황

- [x] 2026-09-09 00:00Z 저장소 구조, PRD, 백엔드 아키텍처, 의존성과 테스트 기반을 조사했다.
- [x] 2026-09-09 00:00Z 백엔드 상품 등록 API의 요구사항과 설계를 사용자와 확정했다.
- [ ] 마일스톤 1을 구현하고 검증한다.
- [ ] 마일스톤 2를 구현하고 검증한다.
- [ ] 마일스톤 3을 구현하고 검증한다.
- [ ] 마일스톤 4를 구현하고 전체 검증 결과를 기록한다.

## 예상 밖의 발견

- 관찰: 백엔드는 Web MVC 스캐폴드만 있어 상품 기능 구현 전에 영속성 기반부터 추가해야 한다.
  근거: `apps/backend/build.gradle.kts`에 JPA, PostgreSQL, Flyway와 Testcontainers 의존성이 없고 `application.yaml`에도 데이터 소스 설정이 없다.

## 결정 기록

- 결정: 상품 기본 정보와 판매 일정·수량을 분리하고 가격은 `Product`가 보유한다.
  이유: 동일 상품을 여러 판매 일정으로 다시 판매한다는 PRD를 따르면서 일정별 가격 차등은 현재 요구하지 않기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 등록 API는 이미지를 받지 않고 `DRAFT` 상품을 먼저 생성한다.
  이유: 실제 이미지 업로드를 별도 기능으로 분리하면서 프론트엔드가 하나의 등록 UX로 두 API를 조율할 수 있기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 인증 도입 전에는 필수 `X-Seller-Id` 헤더를 양의 `Long`으로 받고 판매자 존재 여부는 검증하지 않는다.
  이유: 요청 본문을 임시 인증 방식에 결합하지 않고 JWT 도입 시 Controller 경계만 교체하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 식별자와 가격은 `Long`을 사용하고 통화는 KRW로 고정한다.
  이유: 현재 요구되는 정수 금액과 관계형 데이터 모델을 가장 단순하게 표현하기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers를 사용한다.
  이유: 트랜잭션과 정합성이 중요한 커머스 데이터를 실제 운영 데이터베이스와 동일한 동작으로 검증하고 스키마 변경 이력을 재현하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 오류는 상품 범위의 `@RestControllerAdvice`에서 `ProblemDetail`과 `ResponseEntity` 빌더를 사용해 반환한다.
  이유: 응답 객체에 직접 쓰지 않고 일관된 HTTP 오류 계약을 제공하되 사용되지 않은 공통 계층은 만들지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

아직 완료되지 않음.
