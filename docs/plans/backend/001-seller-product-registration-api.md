# ExecPlan: 판매자 상품 등록 API 구현

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/prd.md`의 한정판 상품 판매 및 판매자 기능, 2026-09-09 백엔드 상품 등록 API 브레인스토밍

이 ExecPlan의 범위는 판매자가 한정판 상품의 기본 정보를 등록하고 PostgreSQL에 저장하는 하나의 독립적으로 검증 가능한 백엔드 기능으로 제한한다. 이미지 업로드와 판매 일정 등록은 이 기능이 제공하는 `productId`를 사용하는 후속 작업이다.

## 목적과 인수 기준

이 변경 뒤에는 판매자가 자신의 임시 식별자를 헤더로 전달하고 상품명과 설명을 등록할 수 있다. 백엔드는 입력 규칙을 검증하고 `DRAFT` 상품을 PostgreSQL에 저장한 뒤 생성된 상품과 위치를 반환한다.

- 유효한 `X-Seller-Id`와 상품 본문으로 `POST /api/products`를 호출하면 `201 Created`, `Location: /api/products/{id}`와 생성된 상품 응답을 받는다.
- 저장된 상품은 요청의 판매자 ID, 공백을 제거한 상품명과 설명, `DRAFT` 상태와 생성 시각을 가진다.
- 헤더가 없거나 양의 `Long`이 아니면 `400 Bad Request`를 받는다.
- 상품명 또는 설명이 입력 규칙을 위반하면 `400 Bad Request`와 `ProblemDetail` 형식의 필드별 오류를 받는다.
- PostgreSQL Testcontainers에서 Flyway가 최초 스키마를 만들고 JPA가 해당 스키마와 정상 연동된다.

이번 계획은 실제 판매자 존재 여부와 권한 검증, JWT 인증·인가, 이미지 업로드와 연결, `READY` 상태 전환, 가격·판매 일정·수량 설정, 상품 수정·조회·삭제, 상품 등록 멱등성 처리를 포함하지 않는다.

## 맥락과 구현 접근

백엔드는 현재 Spring Boot Web MVC 스캐폴드와 Context Test만 존재한다. 상품 기능을 추가할 때 `docs/architecture/backend.md`의 기능 단위 패키지와 `presentation → application → domain`, `infrastructure → domain` 의존성 규칙을 따른다.

- `docs/prd.md`: 상품과 판매 정보를 분리하고 동일 상품에 여러 판매 일정을 둘 수 있어야 한다고 정의한다.
- `docs/architecture/backend.md`: Domain Entity와 JPA Entity를 하나로 사용하고 Domain에 Repository Interface를 두도록 규정한다.
- `docs/architecture/decisions/ADR-002-backend-persistence-stack.md`: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers 사용 결정을 기록한다.
- `docs/architecture/decisions/ADR-003-sale-owns-price.md`: 상품의 카탈로그 정보와 판매 일정의 가격·수량·기간을 분리하는 결정을 기록한다.
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
│   ├── InvalidProductException
│   └── ProductRepository
└── infrastructure
    ├── ProductJpaRepository
    └── ProductRepositoryImpl
```

`ProductController`는 HTTP 입력의 구조와 형식을 검증하고 Application DTO로 바꾼다. `ProductService`는 트랜잭션 안에서 현재 시각 결정, 상품 생성과 Repository 저장을 조율한다. `Product`는 문자열 정규화, 길이와 같은 의미 규칙과 초기 상태를 보장한다. Application 계층은 `JpaRepository`에 직접 의존하지 않는다.

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
  "description": "브랜드 협업 한정판 상품"
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
  "status": "DRAFT",
  "createdAt": "2026-09-09T12:00:00Z"
}
```

`Location`은 요청의 scheme이나 host를 포함하지 않는 상대 URI `/api/products/{id}`로 반환한다. 동일 판매자가 같은 이름의 상품을 여러 개 등록하는 것은 허용하며 멱등성 키는 받지 않는다.

검증 규칙은 다음과 같다.

- `sellerId`: 필수, 1 이상의 `Long`
- `name`: 필수, 앞뒤 Unicode 공백 제거 후 Unicode code point 기준 1자 이상 100자 이하
- `description`: 필수, 앞뒤 Unicode 공백 제거 후 Unicode code point 기준 1자 이상 5,000자 이하

presentation 계층은 헤더와 JSON의 누락, 형식 및 타입처럼 HTTP 입력 구조를 검증한다. `X-Seller-Id`는 `String?`으로 받은 뒤 Controller의 HTTP 입력 변환 과정에서 누락, `Long` 변환과 양수 여부를 확인한다. 상품명과 설명의 공백 제거 및 길이 검증은 `Product`가 단일하게 수행하며 내부 공백은 보존한다. 여러 필드가 잘못된 경우 `InvalidProductException`은 모든 필드 위반을 모아 전달한다.

알 수 없는 JSON 필드는 허용한다. 빈 본문, malformed JSON과 문자열 필드에 숫자·배열·객체 등 잘못된 타입을 전달한 요청은 `400 Bad Request`, JSON이 아닌 `Content-Type`은 `415 Unsupported Media Type`을 반환한다.

### 오류 계약

`ProductExceptionHandler`는 `ProductController`로 범위를 제한한 `@RestControllerAdvice`로 구현한다. 알려진 MVC 예외는 원래의 `400`, `405`, `415` 상태를 보존해 `ProblemDetail`로 변환하고, `InvalidProductException`은 `400 ProblemDetail`로 변환하며, 그 밖의 예외만 `500 ProblemDetail`로 변환한다. Servlet 응답에 직접 쓰지 않고 `ResponseEntity.status(...).body(problemDetail)`로 반환한다. 다른 도메인에서도 같은 오류 계약을 실제로 사용하기 전에는 공통 패키지로 이동하지 않는다.

```json
{
  "type": "about:blank",
  "title": "요청 값이 올바르지 않습니다.",
  "status": 400,
  "detail": "상품 등록 요청을 확인해 주세요.",
  "instance": "/api/products",
  "errors": {
    "name": "상품명은 필수입니다.",
    "description": "상품 설명은 필수입니다."
  }
}
```

`errors`는 필드별 메시지 하나를 갖는 `Map<String, String>`이며 JSON 객체의 키 순서는 계약에 포함하지 않는다. 본문 필드는 `name`과 `description`, 판매자 헤더는 논리적인 입력 이름인 `sellerId`, 필드를 특정할 수 없는 본문 파싱 실패는 `request` 키를 사용한다.

- 헤더 누락은 `sellerId: 판매자 ID는 필수입니다.`를 포함하는 `400 Bad Request`다.
- 헤더의 숫자 변환 실패와 0 이하는 `sellerId: 판매자 ID는 1 이상의 정수여야 합니다.`를 포함하는 `400 Bad Request`다.
- 누락되거나 공백뿐인 상품명은 `name: 상품명은 필수입니다.`를 포함하는 `400 Bad Request`다.
- 최대 길이를 초과한 상품명은 `name: 상품명은 100자 이하여야 합니다.`를 포함하는 `400 Bad Request`다.
- 누락되거나 공백뿐인 설명은 `description: 상품 설명은 필수입니다.`를 포함하는 `400 Bad Request`다.
- 최대 길이를 초과한 설명은 `description: 상품 설명은 5,000자 이하여야 합니다.`를 포함하는 `400 Bad Request`다.
- 빈 본문, malformed JSON과 타입 오류는 `request: 요청 본문을 읽을 수 없습니다.`를 포함하는 `400 Bad Request`다.
- 같은 필드에서 여러 제약을 위반하면 필수, 형식, 범위 순서에서 가장 먼저 해당하는 메시지 하나를 반환한다.
- `405 Method Not Allowed`와 `415 Unsupported Media Type`은 해당 상태의 `ProblemDetail`로 반환하되 title과 detail 문구는 Spring MVC의 표준 표현을 허용한다.
- 예상하지 못한 서버 또는 데이터베이스 오류는 다음 `500 Internal Server Error`를 반환한다. 서버에는 원래 예외를 기록하지만 응답에는 내부 메시지, SQL과 데이터베이스 정보를 노출하지 않는다.
- 인증 도입 전이므로 `401 Unauthorized`와 `403 Forbidden`은 이 API의 현재 계약에 포함하지 않는다.

```json
{
  "type": "about:blank",
  "title": "서버 오류가 발생했습니다.",
  "status": 500,
  "detail": "요청 처리 중 오류가 발생했습니다.",
  "instance": "/api/products"
}
```

### 도메인과 영속성 계약

`Product`는 JPA Entity이자 Domain Entity이며 다음 값을 가진다.

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `id` | `Long` | PostgreSQL identity가 생성 |
| `sellerId` | `Long` | 양수, 회원 테이블 외래 키 없음 |
| `name` | `String` | Unicode 공백 제거 후 code point 기준 1~100자 |
| `description` | `String` | Unicode 공백 제거 후 code point 기준 1~5,000자 |
| `status` | `ProductStatus` | 최초 `DRAFT` |
| `createdAt` | `Instant` | Application이 전달한 생성 시각 |

`Product`는 가격을 보유하지 않는다. 후속 `Sale`은 가격, 판매 시작·종료 시각과 판매 수량을 별도 테이블에서 관리한다. 판매자는 판매 일정을 등록할 때 양의 `Long`인 KRW 정수 가격을 필수로 입력하며, 같은 상품도 판매 일정마다 다른 가격을 가질 수 있다. 주문은 구매가 발생한 `Sale`의 가격을 주문 상품에 스냅샷으로 저장한다. 이 경계의 근거와 트레이드오프는 `docs/architecture/decisions/ADR-003-sale-owns-price.md`에 기록한다.

`ProductStatus`는 `DRAFT`와 `READY`를 정의하지만 이번 범위에서는 `DRAFT` 생성만 사용하고 `READY` 전환 메서드는 구현하지 않는다. `InvalidProductException`은 HTTP 타입에 의존하지 않고 잘못된 도메인 필드와 공개 가능한 메시지를 갖는다. `ProductRepository.save(product): Product`는 저장 후 PostgreSQL이 생성한 ID가 반영된 상품을 반환한다.

`ProductService`는 기본값이 `Clock.systemUTC()`인 `Clock` Bean을 주입받고 `Instant`를 생성해 `Product`에 전달한다. Entity lifecycle callback이나 데이터베이스 기본값으로 생성 시각을 다시 만들지 않는다.

최초 Flyway 마이그레이션은 `products` 테이블에 다음 제약을 반영한다.

- `GENERATED BY DEFAULT AS IDENTITY`인 `BIGINT` 기본 키와 JPA `GenerationType.IDENTITY`
- 외래 키가 없는 양수 `seller_id`
- 최대 길이가 드러나는 `NOT NULL` 상품명과 설명 열
- `DRAFT`와 `READY`만 허용하는 문자열 상태
- 데이터베이스 기본값 없이 `NOT NULL`인 `TIMESTAMP WITH TIME ZONE` 생성 시각

데이터베이스는 `seller_id > 0`, 이름과 설명의 허용 길이 및 상태 값을 CHECK 제약으로 다시 보장한다. 애플리케이션이 정규화된 문자열만 저장하며 데이터베이스는 내부 또는 앞뒤 공백 정규화를 수행하지 않는다.

Gradle에는 Spring Data JPA, Bean Validation, PostgreSQL JDBC Driver, Flyway PostgreSQL 지원, Spring Boot Testcontainers와 PostgreSQL Testcontainers 의존성을 추가한다. Spring Boot 의존성 관리를 사용하고 개별 버전은 직접 고정하지 않는다. `application.yaml`은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수를 데이터 소스 설정에 사용하고, 환경 변수가 없으면 로컬 PostgreSQL의 `jdbc:postgresql://localhost:5432/japda`, `root`, `1234`를 기본값으로 사용한다. JPA는 Flyway 스키마를 자동 생성하지 않고 `ddl-auto=validate`로 검증하며 Flyway를 활성화한다.

테스트에는 공유 `PostgreSqlTestContainerConfiguration`을 두고 Context Test, 영속성 테스트와 HTTP 통합 테스트가 가져와 사용한다. Spring Boot `@ServiceConnection`이 테스트 데이터 소스 연결값을 제공하므로 테스트 실행에는 데이터 소스 환경 변수가 필요하지 않다.

## 작업 계획

구현은 테스트 주도 방식으로 진행한다. 각 마일스톤에서 먼저 실패하는 테스트를 작성하고, 해당 테스트를 통과시키는 최소 구현을 추가한 뒤 관련 테스트를 다시 실행한다.

### 마일스톤 1: PostgreSQL 영속성 기반 확립

`apps/backend/build.gradle.kts`에 영속성, 검증, 마이그레이션과 Testcontainers 의존성을 추가한다. `apps/backend/src/main/resources/application.yaml`에 기본값 없는 환경 변수 기반 데이터 소스, 활성화된 Flyway와 JPA `ddl-auto=validate` 설정을 추가한다. `apps/backend/src/main/resources/db/migration/`에 최초 `products` 테이블 마이그레이션을 작성한다. 테스트 공통 영역에 `@ServiceConnection` 기반 `PostgreSqlTestContainerConfiguration`을 추가하고 `BackendApplicationTests`가 이를 가져오도록 보완한다. 이 단계에서는 Entity가 아직 없으므로 PostgreSQL 연결, Flyway 적용과 JPA Context 시작까지만 검증하고 실제 Entity와 테이블 매핑은 마일스톤 2에서 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests io.github.sehako.japda.BackendApplicationTests

예상 관찰 결과: 데이터 소스 환경 변수 없이 PostgreSQL 컨테이너가 시작되고 Flyway가 `products` 테이블을 만든 뒤 Spring과 JPA Context Test가 통과한다.

### 마일스톤 2: 상품 도메인과 Application 흐름 완성

`product/domain`에 `Product`, `ProductStatus`, `InvalidProductException`, `ProductRepository`를 추가하고 Spring Context 없는 단위 테스트로 Unicode 공백 정규화, code point 길이, 모든 필드 위반 수집과 `DRAFT` 초기 상태를 먼저 고정한다. `product/application`에 DTO, Response와 `ProductService`를 추가하고 고정 `Clock`과 Repository 대역을 사용해 생성 시각, 저장 호출, 저장된 Entity 반환값과 응답 변환을 검증한다. `product/infrastructure`에 Spring Data JPA Repository와 Domain Repository 구현체를 추가하고 공유 PostgreSQL Testcontainer에서 identity ID, `TIMESTAMP WITH TIME ZONE`, Enum 문자열과 모든 열 매핑을 검증한다. 이 테스트가 Flyway 스키마와 JPA Entity의 실제 `ddl-auto=validate` 연동을 처음으로 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.domain.*' --tests 'io.github.sehako.japda.product.application.*' --tests 'io.github.sehako.japda.product.infrastructure.*'

예상 관찰 결과: 잘못된 상품 값은 생성 시 거부되고, 정상 상품은 PostgreSQL에 저장된 뒤 모든 필드가 동일하게 조회되며 모든 대상 테스트가 통과한다.

### 마일스톤 3: 상품 등록 HTTP API 제공

`product/presentation`에 nullable 본문 필드를 갖는 `CreateProductRequest`와 `X-Seller-Id`를 `String?`으로 받는 `ProductController`를 추가한다. 정상 요청의 상태, 상대 `Location`과 응답 본문을 MVC 테스트로 먼저 고정한다. 이어 상품 Controller로 범위를 제한한 `ProductExceptionHandler`를 추가하고 헤더·본문 구조 오류, 도메인 검증 오류, 405·415 MVC 오류와 예기치 않은 500 오류의 `ProblemDetail` 계약을 MVC 테스트로 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.presentation.*'

예상 관찰 결과: 정상 요청은 `201 Created`와 상대 `Location`을 반환한다. 헤더, 본문 또는 도메인 검증 실패는 정해진 필드별 `400 ProblemDetail`, 프로토콜 오류는 원래 상태의 `ProblemDetail`, 예상하지 못한 오류는 내부 정보가 없는 `500 ProblemDetail`을 반환하며 모든 MVC 테스트가 통과한다.

### 마일스톤 4: 전체 기능 통합 검증

실제 HTTP 요청부터 PostgreSQL 저장까지 연결되는 통합 테스트를 추가한다. 등록 응답의 `id`로 데이터베이스 상태를 확인해 판매자 ID, 정규화된 문자열, 상태와 생성 시각이 저장됐음을 검증한다. 이후 전체 백엔드 테스트와 빌드를 실행하고 결과를 이 문서의 살아 있는 섹션에 기록한다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 전체 테스트와 빌드가 실패 없이 완료되고 테스트 결과에 상품 등록의 정상·오류·영속성 시나리오가 포함된다.

## 검증

- 정상 등록: 양의 `X-Seller-Id`와 유효한 본문으로 호출하면 `201`, `Location`과 `DRAFT` 상품을 반환하고 동일한 값이 PostgreSQL에 저장된다.
- 문자열 정규화: 앞뒤 Unicode 공백이 있는 상품명과 설명을 등록하면 해당 공백만 제거되고 내부 공백은 보존된 값이 응답과 데이터베이스에 저장된다. 이모지가 포함된 길이 경계는 Unicode code point 기준으로 판정한다.
- 판매자 헤더 검증: 헤더 누락, 숫자가 아닌 값, 0과 음수 값은 각각 `sellerId` 키와 정해진 메시지를 갖는 `400 ProblemDetail`을 반환한다.
- 본문 검증: 누락·공백·최대 길이 초과 상품명과 설명은 `name` 또는 `description` 키와 정해진 메시지를 갖는 `400 ProblemDetail`을 반환한다. 여러 필드가 잘못되면 각 필드의 오류를 함께 반환한다.
- 본문 형식 검증: 빈 본문, malformed JSON과 타입 오류는 `request` 키를 갖는 `400 ProblemDetail`을 반환하고, 알 수 없는 JSON 필드는 무시하며, JSON이 아닌 미디어 타입은 `415 ProblemDetail`을 반환한다.
- 시간과 ID: 고정 `Clock`으로 등록하면 같은 `createdAt`이 응답과 `TIMESTAMP WITH TIME ZONE` 열에 저장되고 PostgreSQL identity가 서로 다른 ID를 생성한다.
- 프로토콜 오류: 지원하지 않는 HTTP 메서드와 미디어 타입은 각각 `405`, `415` 상태를 보존한 `ProblemDetail`을 반환한다.
- 중복 허용: 같은 판매자가 같은 이름으로 두 번 등록하면 서로 다른 ID의 상품 두 건이 생성된다.
- 내부 정보 보호: 처리되지 않은 내부 오류는 합의한 `500 ProblemDetail`을 반환하고 예외 메시지, SQL 또는 자격 증명을 포함하지 않는다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 두 명령이 모두 종료 코드 0으로 완료된다. Docker 또는 PostgreSQL Testcontainer를 실행할 수 없는 환경이라면 실패 원인과 실행하지 못한 검증 항목을 `예상 밖의 발견`과 `결과와 회고`에 기록한다.

## 위험과 완화

- `X-Seller-Id`는 위조할 수 있다. 인증 수단으로 표현하거나 운영 권한 검증에 사용하지 않고, JWT 도입 시 Controller 입력 경계를 교체한다.
- 상품 등록 성공 뒤 후속 이미지 업로드가 실패하면 이미지 없는 `DRAFT`가 남는다. 삭제로 보상하지 않고 같은 상품에서 이미지 업로드를 재시도하도록 후속 기능을 설계한다.
- JPA Entity 규칙과 Flyway 스키마가 어긋나면 애플리케이션 시작이 실패한다. 마일스톤 2의 JPA 스키마 검증과 PostgreSQL 영속성 테스트로 조기에 발견한다.
- Testcontainers는 Docker 실행 환경에 의존한다. 실행 불가 시 테스트를 다른 DB로 대체하지 않고 미검증 사실을 계획에 기록한다.
- 일반적인 POST 재시도로 중복 상품이 생성될 수 있다. PRD가 결제에만 멱등성을 요구하고 상품명 중복을 허용하므로 현재 범위에서는 감수한다.

## 진행 상황

- [x] 2026-09-09 00:00Z 저장소 구조, PRD, 백엔드 아키텍처, 의존성과 테스트 기반을 조사했다.
- [x] 2026-09-09 00:00Z 백엔드 상품 등록 API의 요구사항과 설계를 사용자와 확정했다.
- [x] 2026-09-09 01:48Z 가격 소유권을 `Product`에서 `Sale`로 변경하고 API·도메인·영속성 계약과 ADR에 반영했다.
- [x] 2026-09-09 02:08Z 영속성 단계, 검증 책임, 오류 계약, ID·시간 생성과 Testcontainers 연결의 모호함을 해소해 계획에 반영했다.
- [x] 2026-09-09 02:21Z 마일스톤 1을 구현하고 PostgreSQL Context Test로 Flyway 스키마 생성을 검증했다.
- [x] 2026-09-09 02:25Z 마일스톤 2를 구현하고 도메인, Application, PostgreSQL 영속성 테스트를 검증했다.
- [x] 2026-09-09 02:35Z 마일스톤 3을 구현하고 MVC 성공·검증·오류·프로토콜 계약을 검증했다.
- [x] 2026-09-09 02:37Z 마일스톤 4의 HTTP-PostgreSQL 통합 테스트와 전체 테스트 17개 및 빌드를 검증했다.
- [x] 2026-09-09 02:47Z Controller의 HTTP 입력 변환을 `CreateProductRequestConverter`로 분리하고 전체 테스트 20개 및 빌드를 다시 검증했다.
- [x] 2026-09-09 02:52Z 데이터소스 환경 변수에 로컬 PostgreSQL 기본 연결값을 추가하고 전체 테스트 20개 및 빌드를 다시 검증했다.

## 예상 밖의 발견

- 관찰: 백엔드는 Web MVC 스캐폴드만 있어 상품 기능 구현 전에 영속성 기반부터 추가해야 한다.
  근거: `apps/backend/build.gradle.kts`에 JPA, PostgreSQL, Flyway와 Testcontainers 의존성이 없고 `application.yaml`에도 데이터 소스 설정이 없다.
- 관찰: 최초 Context Test 실행에서 소스에 없는 과거 마이그레이션 파일이 `build/resources/main`에 남아 테스트가 잘못 통과했다.
  근거: `./gradlew clean test --tests io.github.sehako.japda.BackendApplicationTests`로 빌드 산출물을 제거한 뒤 `products` 테이블 부재를 검증하는 테스트가 기대한 이유로 실패했다.
- 관찰: Controller 타입으로 범위를 제한한 `@RestControllerAdvice`는 handler가 정해지기 전에 발생하는 405와 415 예외를 처리하지 않는다.
  근거: 독립 MVC 테스트에서 405 응답에 본문이 없었고, Spring Boot 4.1의 `spring.mvc.problemdetails.enabled`가 전역 MVC 프로토콜 예외용 `ProblemDetailsExceptionHandler`를 별도로 제공함을 로컬 소스에서 확인했다. 상품 도메인 오류는 범위가 제한된 `ProductExceptionHandler`가 처리하고 405와 415는 Spring MVC 표준 처리기가 상태와 `ProblemDetail`을 보존하도록 구성했다.
- 관찰: Jackson 3 기본 바인딩은 숫자 JSON 값을 Kotlin `String`으로 강제 변환한다.
  근거: 숫자 상품명이 등록 성공으로 처리되는 실패 테스트를 확인했다. 전역 Jackson 정책을 변경하지 않고 `CreateProductRequest`에서 `JsonNode`로 구조를 받은 뒤 Controller가 문자열과 `null`만 허용하도록 제한했다.

## 결정 기록

- 대체된 결정: 상품 기본 정보와 판매 일정·수량을 분리하고 가격은 `Product`가 보유한다.
  대체 이유: 판매자가 판매 일정별 가격·수량·기간을 함께 설정하는 흐름이 더 자연스럽고, 동일 상품을 다른 가격으로 다시 판매할 수 있어야 하기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: `Product`는 가격을 보유하지 않고, `Sale`이 해당 판매 일정의 가격을 보유한다.
  이유: 상품의 카탈로그 정보와 실제 판매 조건을 분리하고 판매 일정 등록 시 가격·수량·기간을 함께 입력하는 판매자 흐름을 제공하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 등록 API는 이미지를 받지 않고 `DRAFT` 상품을 먼저 생성한다.
  이유: 실제 이미지 업로드를 별도 기능으로 분리하면서 프론트엔드가 하나의 등록 UX로 두 API를 조율할 수 있기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 인증 도입 전에는 필수 `X-Seller-Id` 헤더를 양의 `Long`으로 받고 판매자 존재 여부는 검증하지 않는다.
  이유: 요청 본문을 임시 인증 방식에 결합하지 않고 JWT 도입 시 Controller 경계만 교체하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 식별자는 `Long`을 사용한다.
  이유: 관계형 데이터 모델의 상품 식별자를 단순하게 표현하기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers를 사용한다.
  이유: 트랜잭션과 정합성이 중요한 커머스 데이터를 실제 운영 데이터베이스와 동일한 동작으로 검증하고 스키마 변경 이력을 재현하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 오류는 상품 범위의 `@RestControllerAdvice`에서 `ProblemDetail`과 `ResponseEntity` 빌더를 사용해 반환한다.
  이유: 응답 객체에 직접 쓰지 않고 일관된 HTTP 오류 계약을 제공하되 사용되지 않은 공통 계층은 만들지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: presentation은 HTTP 입력 구조를 검증하고 `Product`가 Unicode 공백 정규화와 code point 길이 규칙을 단일하게 검증한다.
  이유: Bean Validation과 Domain에 의미 규칙을 중복하여 경계값 판정이 달라지는 문제를 피하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 오류 응답은 `Map<String, String>`인 `errors` 확장 필드를 사용하고 상품 Controller 범위의 Advice가 알려진 HTTP 상태를 보존하며 나머지 예외만 고정된 `500 ProblemDetail`로 변환한다.
  이유: 클라이언트가 필드별 오류를 단순하게 표시하면서도 프로토콜 오류를 서버 오류로 왜곡하지 않고 내부 정보를 숨기기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: PostgreSQL identity와 JPA `GenerationType.IDENTITY`로 상품 ID를 생성하고, `ProductService`가 주입받은 UTC `Clock`으로 생성 시각을 정해 Entity에 전달한다.
  이유: 식별자 생성 책임을 데이터베이스에 두고 시간 의존성을 테스트 가능하게 만들며 응답과 저장값의 단일 출처를 유지하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 마일스톤 1은 공유 `@ServiceConnection` Testcontainer로 PostgreSQL 연결과 Flyway 적용을 검증하고, Entity와 Flyway 스키마의 실제 매핑 검증은 마일스톤 2에서 수행한다.
  이유: Entity가 존재하기 전에 JPA 테이블 매핑을 검증한다는 단계 간 모순을 제거하고 테스트 데이터 소스 환경 변수를 별도로 요구하지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 도메인과 본문 오류는 Controller 범위를 제한한 `ProductExceptionHandler`가 처리하고, handler 결정 전에 발생하는 405와 415는 Spring Boot의 표준 ProblemDetail 지원을 사용한다.
  이유: 상품 오류 처리기를 다른 Controller로 확장하지 않으면서도 MVC 프로토콜 오류의 원래 상태와 `ProblemDetail` 응답을 보존하기 위해서다.
  일자/작성자: 2026-09-09, Codex
- 결정: `CreateProductRequest`는 두 본문 필드를 `JsonNode`로 받고 Controller가 문자열과 `null`만 Application DTO로 변환한다.
  이유: 다른 API의 Jackson 역직렬화 정책을 바꾸지 않고 숫자, 배열과 객체의 문자열 강제 변환을 상품 등록 경계에서만 차단하기 위해서다.
  일자/작성자: 2026-09-09, Codex
- 결정: `CreateProductRequestConverter`가 판매자 헤더와 `JsonNode`를 검증하고 `CreateProductDto`로 변환한다.
  이유: HTTP 입력 변환 책임을 presentation 계층에 유지하면서 `ProductController`를 요청 위임과 HTTP 응답 생성에 집중시키기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 데이터소스 환경 변수가 없으면 로컬 PostgreSQL의 `jdbc:postgresql://localhost:5432/japda`, `root`, `1234`를 기본 연결값으로 사용한다.
  이유: 별도 환경 변수 설정 없이도 로컬 데이터베이스를 준비한 개발자가 백엔드를 바로 실행할 수 있게 하기 위해서다. 배포 환경에서는 기존 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수가 기본값을 대체한다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

- `POST /api/products`가 유효한 판매자 헤더와 상품 본문을 받아 정규화된 `DRAFT` 상품을 저장하고 `201 Created`, 상대 `Location`과 상품 응답을 반환한다.
- 상품 도메인이 판매자 ID, Unicode 공백 정규화, code point 길이와 초기 상태를 보장하며, Application Service는 주입된 `Clock`의 생성 시각으로 트랜잭션 저장 흐름을 조율한다.
- Flyway 최초 마이그레이션, PostgreSQL과 Spring Data JPA Repository 구현, 환경 변수 기반 운영 데이터소스 설정 및 공유 Testcontainers 구성을 추가했다.
- 데이터소스 환경 변수가 없으면 `jdbc:postgresql://localhost:5432/japda`, `root`, `1234`로 연결하고, 환경 변수가 있으면 해당 값을 우선 사용한다.
- 상품 Controller에 한정한 `ProblemDetail` 오류 계약과 Spring MVC 표준 405·415 처리를 구현했다. `CreateProductRequestConverter`가 판매자 헤더와 `JsonNode`를 검증하고 Application DTO로 변환하므로 Controller는 요청 위임과 응답 생성에 집중한다.
- `./gradlew clean test`는 전체 테스트 20개가 실패 없이 통과했고, `./gradlew build`도 종료 코드 0으로 완료됐다.
- 승인된 인수 기준은 모두 충족했다. 이미지, 판매 일정, 인증·인가와 상품 조회·수정·삭제 등 제외 범위는 구현하지 않았다.
