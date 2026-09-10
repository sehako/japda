# 판매자 상품 기본 정보 등록 API 설계

## 배경

Japda는 하나의 상품을 여러 판매 일정과 가격으로 반복 판매할 수 있어야 한다. `docs/prd.md`는 상품과 판매 정보를 분리하도록 명시하므로, 상품 원본 정보와 실제 판매 조건은 서로 다른 생명주기로 관리한다.

현재 브랜치의 백엔드는 Spring Boot 기본 구조만 있으며 데이터베이스 연동과 상품 기능이 구현되지 않았다. 폐기된 `develop` 브랜치의 코드는 구현 대상으로 병합하거나 전제하지 않고, 현재 아키텍처와 비교하기 위한 참고 자료로만 사용한다.

## 목표

판매자가 상품명과 선택적인 상품 설명을 입력하면 백엔드가 상품을 `DRAFT` 상태로 PostgreSQL에 저장하고 생성 결과를 반환하는 API를 제공한다.

다음 조건을 모두 만족하면 이 기능이 완료된 것으로 본다.

- 유효한 `POST /api/products` 요청은 PostgreSQL에 상품 한 건을 저장한다.
- 저장된 상품은 요청의 판매자 식별자와 정규화된 상품명·설명을 가지며 초기 상태는 `DRAFT`이다.
- 응답은 `201 Created`, 생성된 상품 정보, `Location: /api/products/{id}` 헤더를 포함한다.
- 잘못된 요청은 Spring의 `ProblemDetail`을 사용한 `application/problem+json` 응답을 반환한다.
- 실제 PostgreSQL을 사용하는 통합 테스트가 HTTP 요청부터 데이터 저장까지 검증한다.

## 범위

### 포함

- 판매자 상품 기본 정보 등록 HTTP API
- 임시 판매자 식별용 `X-Seller-Id` 헤더
- 상품 생성 규칙과 `DRAFT` 초기 상태
- PostgreSQL, Spring Data JPA, Flyway 기반 영속성
- 기능별 오류 분류와 공통 `ProblemDetail` 변환
- 도메인·프레젠테이션·영속성·통합 테스트
- Spring REST Docs 기반 API 문서 생성
- 공통 영속성 기술과 오류 처리 구조에 필요한 ADR 및 백엔드 아키텍처 문서 갱신

### 제외

- 상품 이미지 등록과 이미지 저장소 연동
- 판매 가격, 수량, 시작·종료 시각 등록
- 하루 최대 20개 판매 일정 제한과 일정 경쟁 정책
- 상품 수정과 조회
- 판매자 인증·인가 및 판매자 계정 존재 여부 확인
- 상품 판매 준비 완료 상태로의 전환

## 도메인 경계

현재 기능이 생성하는 것은 판매 조건이 결합된 판매 상품이 아니라 판매자가 소유한 상품 원본이다. `Product`는 다음 정보만 소유한다.

- `id`: 데이터베이스가 생성하는 상품 식별자
- `sellerId`: 요청 헤더에서 받은 양수의 판매자 식별자
- `name`: 공백 제거 후 1자 이상 100자 이하인 상품명
- `description`: 선택적인 상품 설명
- `status`: 생성 시 `DRAFT`
- `createdAt`: 상품 생성 시각

`description`이 누락되거나 `null`이거나 공백으로만 구성되면 `null`로 정규화한다. 값이 있으면 앞뒤 공백을 제거한 결과가 1자 이상 3000자 이하여야 한다.

가격, 판매 수량, 판매 시작·종료 시각은 향후 별도의 `Sale`이 `productId`와 함께 소유한다. 이미지 메타데이터 역시 향후 별도 기능으로 등록한다. 따라서 현재 `Product`와 `products` 테이블에는 이 정보를 추가하지 않는다.

## API 계약

### 요청

```http
POST /api/products
X-Seller-Id: 1
Content-Type: application/json

{
  "name": "한정판 상품",
  "description": "선택적인 상품 설명"
}
```

- `X-Seller-Id`는 필수이며 양의 정수여야 한다.
- `name`은 필수이며 공백 제거 후 1자 이상 100자 이하여야 한다.
- `description`은 선택 사항이다. 값이 있으면 공백 제거 후 최대 3000자까지 허용한다.
- 알 수 없는 판매자 ID인지 확인하는 작업은 인증·회원 기능과 함께 후속으로 처리한다.

HTTP 계약상 `name`은 필수지만 Request와 Application DTO에서는 nullable 타입으로 받아 누락된 값도 domain의 동일한 상품명 검증 규칙과 `PRODUCT_NAME_REQUIRED` 오류 코드로 처리한다.

### 성공 응답

```http
HTTP/1.1 201 Created
Location: /api/products/1
Content-Type: application/json

{
  "id": 1,
  "sellerId": 1,
  "name": "한정판 상품",
  "description": "선택적인 상품 설명",
  "status": "DRAFT",
  "createdAt": "2026-09-10T00:00:00Z"
}
```

응답에는 JPA Entity를 직접 노출하지 않고 application 계층의 응답 객체를 사용한다.
`description`이 `null`로 정규화된 경우에도 응답 필드를 생략하지 않고 `"description": null`을 반환한다.

## 계층과 데이터 흐름

```text
POST /api/products
        ↓
presentation: 헤더·요청 본문 형식 확인 및 Application DTO 변환
        ↓
application: 상품 생성 유스케이스와 트랜잭션 조율
        ↓
domain: Product 생성 규칙, 입력 정규화, DRAFT 상태 부여
        ↓
domain ProductRepository
        ↓
infrastructure: Spring Data JPA adapter
        ↓
PostgreSQL products
```

- `presentation`은 HTTP 요청과 응답만 처리하고 비즈니스 규칙을 갖지 않는다.
- `application`은 트랜잭션 안에서 `Product`를 생성하고 domain의 `ProductRepository`를 호출한다.
- `domain`은 JPA Entity이자 Domain Entity인 `Product`, 상태, 생성 규칙, 입력 정규화와 Repository Interface를 소유한다.
- `infrastructure`는 Spring Data JPA Repository와 domain Repository 구현체를 소유한다.
- 생성 시각은 애플리케이션에서 주입받은 `Clock`을 사용해 테스트 가능하게 만든다.

presentation은 JSON 파싱, `X-Seller-Id` 존재 여부와 `Long` 변환처럼 HTTP 형식에 해당하는 검증만 처리한다. `sellerId > 0`, 상품명 공백 제거·필수·100자 제한, 설명 공백 제거·3000자 제한은 `Product` 생성 시 domain이 한 번 정규화하고 최종 검증한다. Domain 규칙 위반은 `ProductException`과 `ProductErrorCode`로 표현한다.

## 데이터베이스 설계

`products` 테이블은 최초 Flyway migration으로 생성한다.

| 열 | 의미 | 주요 제약 |
|---|---|---|
| `id` | 상품 식별자 | `BIGINT`, identity, primary key |
| `seller_id` | 판매자 식별자 | `BIGINT`, not null, `CHECK (seller_id > 0)` |
| `name` | 상품명 | `VARCHAR(100)`, not null, 공백 제외 길이 1~100 |
| `description` | 상품 설명 | `VARCHAR(3000)`, null 허용, 값이 있으면 공백 제외 길이 1~3000 |
| `status` | 상품 상태 | `VARCHAR(20)`, not null, `CHECK (status IN ('DRAFT'))` |
| `created_at` | 생성 시각 | time zone을 보존하는 timestamp, not null |

스키마 변경은 Hibernate 자동 생성이 아니라 Flyway SQL로 관리하고, Hibernate는 애플리케이션 시작 시 mapping과 schema의 일치 여부만 검증한다. 애플리케이션 검증과 데이터베이스 `CHECK` 제약은 동일한 불변식을 보호한다.

현재 범위에서는 판매자 테이블과 판매자 존재 여부 검증을 도입하지 않으므로 `seller_id`에 FK를 생성하지 않는다. `ProductStatus`와 DB 상태 제약은 현재 사용하는 `DRAFT`만 허용한다. 이미지 등록 기능에서 `READY`가 실제로 필요해지면 이미 적용된 migration을 수정하지 않고 새 Flyway migration으로 상태 제약을 확장한다.

데이터베이스 접속 정보는 환경변수로 주입하며 비밀번호나 운영 credential을 저장소에 기록하지 않는다. 테스트는 PostgreSQL Testcontainers의 동적 연결 정보를 사용한다.

## 오류 모델과 패키지 구조

오류 응답은 `HttpServletResponse`에 JSON을 직접 쓰지 않고 Spring의 `org.springframework.http.ProblemDetail`과 MVC 예외 처리 기능으로 생성한다.

```text
global/exception/
├── ErrorCode.kt
├── ErrorCategory.kt
└── BusinessException.kt

global/error/
├── ProblemDetailFactory.kt
└── GlobalExceptionHandler.kt

product/exception/
├── ProductErrorCode.kt
└── ProductException.kt
```

- `global/exception`은 Spring에 의존하지 않는 공통 예외 계약만 소유한다.
- `global/error`는 Spring MVC 오류를 `ProblemDetail`로 변환하는 HTTP 기술 코드만 소유한다.
- `ErrorCode`는 안정적인 오류 코드, `ErrorCategory`, 사용자에게 공개할 기본 메시지와 선택적인 논리 속성명 `property`를 제공한다.
- `ErrorCategory`는 `INVALID_REQUEST`, `NOT_FOUND`, `CONFLICT`처럼 HTTP 기술과 분리된 의미를 표현한다.
- `BusinessException`은 `ErrorCode`를 보관하는 얕은 공통 예외다.
- `ProductException`은 `BusinessException`을 한 단계만 상속한다.
- `ProductErrorCode`는 `ErrorCode`를 구현하며 Spring MVC나 `HttpStatus`에 의존하지 않는다.
- `ProblemDetailFactory`만 `ErrorCategory`를 HTTP 상태로 변환하고 `ProblemDetail`을 구성한다. `property`가 있으면 해당 값을 key로 `errors` 확장 속성을 추가한다.
- `GlobalExceptionHandler`는 개별 상품 오류를 나열하지 않고 `BusinessException` 하나를 처리한다.

의존성 방향은 다음과 같이 제한한다.

```text
product.domain → product.exception → global.exception
global.error → global.exception
```

`ErrorCode` 계약은 다음 정보를 제공한다.

```kotlin
interface ErrorCode {
    val code: String
    val category: ErrorCategory
    val message: String
    val property: String?
}
```

상품 오류의 `property`는 `sellerId`, `name`, `description`처럼 domain의 논리 속성명을 사용한다.

향후 `SaleErrorCode`나 `OrderErrorCode`가 추가돼도 `GlobalExceptionHandler`에 도메인별 분기를 추가하지 않는다. 공통 handler는 비즈니스 예외, Spring MVC 요청·검증 예외, JSON 파싱 오류, 예상하지 못한 예외라는 안정적인 범주만 처리한다.

오류 응답은 `application/problem+json`이며 기본적으로 `type`, `title`, `status`, `detail`, `instance`와 안정적인 식별자인 `code` 확장 속성을 포함한다. 현재는 공개 오류 문서 URI를 운영하지 않으므로 `type`은 `about:blank`를 사용한다. 요청 필드 검증 오류에는 `errors` 확장 속성으로 필드별 오류를 제공한다. 예상하지 못한 서버 오류는 내부 예외 메시지, SQL, credential 등의 민감 정보를 노출하지 않는다.

오류 코드는 대문자 `UPPER_SNAKE_CASE`로 작성한다. JSON 파싱과 서버 내부 오류처럼 모든 API에 공통인 오류는 `COMMON_*`, 상품 규칙 오류는 `PRODUCT_*`로 분류한다. 공개한 오류 코드는 다른 의미로 변경하거나 다른 오류에 재사용하지 않는다.

```text
COMMON_REQUEST_HEADER_MISSING
COMMON_REQUEST_HEADER_INVALID
COMMON_REQUEST_BODY_MALFORMED
COMMON_INTERNAL_SERVER_ERROR
PRODUCT_SELLER_ID_INVALID
PRODUCT_NAME_REQUIRED
PRODUCT_NAME_TOO_LONG
PRODUCT_DESCRIPTION_TOO_LONG
```

```json
{
  "type": "about:blank",
  "title": "잘못된 요청",
  "status": 400,
  "detail": "요청 값이 올바르지 않습니다.",
  "instance": "/api/products",
  "code": "PRODUCT_NAME_REQUIRED",
  "errors": {
    "name": "상품명은 필수입니다."
  }
}
```

## 오류 시나리오

- `X-Seller-Id` 누락: `COMMON_REQUEST_HEADER_MISSING`으로 `400 Bad Request`
- `X-Seller-Id` 숫자 변환 실패: `COMMON_REQUEST_HEADER_INVALID`로 `400 Bad Request`
- `X-Seller-Id`가 0 이하: `PRODUCT_SELLER_ID_INVALID`로 `400 Bad Request`
- 요청 JSON 파싱 실패: `400 Bad Request`
- 상품명 누락 또는 공백: `PRODUCT_NAME_REQUIRED`로 `400 Bad Request`
- 상품명 100자 초과: `PRODUCT_NAME_TOO_LONG`으로 `400 Bad Request`
- 상품 설명 3000자 초과: `PRODUCT_DESCRIPTION_TOO_LONG`으로 `400 Bad Request`
- 상품 생성 규칙 위반: `400 Bad Request`
- 예상하지 못한 저장 실패: `500 Internal Server Error`와 노출이 제한된 일반 메시지

저장 실패 시 application 트랜잭션을 롤백해 부분 저장을 남기지 않는다.

## 테스트 전략

### Domain Test

Spring Context 없이 상품 생성, 입력 정규화, 길이 제한, `DRAFT` 초기 상태와 생성 시각을 검증한다.

### Presentation Test

MockMvc로 성공 응답, 필수 헤더, 헤더 타입과 범위, 요청 본문 검증을 확인한다. 설명이 `null`이면 성공 응답에 `"description": null`이 포함되는지 확인한다. 실패 응답은 `application/problem+json`, `ProblemDetail` 기본 필드, `code`, 필드 오류가 있는 경우 `errors`를 검증한다.

동일한 테스트에서 Spring REST Docs 스니펫을 생성한다. 성공 요청과 응답의 `X-Seller-Id`, 요청 필드, `Location` 헤더와 응답 필드를 문서화하고, 대표적인 실패 응답의 `ProblemDetail` 기본 필드와 `code`, 선택적인 `errors` 구조를 문서화한다. 문서화한 필드가 실제 계약과 다르면 테스트가 실패해야 한다.

### Application Test

Repository와 `Clock`을 대역으로 사용해 상품 생성과 저장 호출, 응답 변환을 검증한다.

### Persistence Test

PostgreSQL Testcontainers에서 Flyway migration, identity ID 생성, nullable 설명, Enum 문자열 mapping, DB 제약을 검증한다. 저장 후 persistence context를 비운 다음 다시 조회해 실제 mapping을 확인한다.

### Integration Test

`@SpringBootTest`와 MockMvc로 실제 HTTP 요청을 보내고 PostgreSQL에서 저장된 행을 확인한다. `201 Created`, `Location`, 응답 본문과 DB 값이 모두 일치해야 한다.

## 문서화 결정

PostgreSQL, Spring Data JPA, Flyway, PostgreSQL Testcontainers 조합은 여러 기능이 공유하는 기술 선택이므로 구현 전에 ADR로 기록한다. 공통 `ProblemDetail` 오류 계약과 `ErrorCode`/`BusinessException` 구조 역시 후속 API가 따를 공통 경계이므로 같은 구현 작업에서 별도 ADR로 기록한다.

백엔드 HTTP API 문서는 `ADR-002`에 따라 Spring REST Docs로 생성한다. 이 기능의 MockMvc 기반 Presentation Test에서 요청과 응답을 검증하면서 스니펫을 생성하고, Asciidoctor가 이를 조합해 HTML 문서를 만든다. 문서 생성은 Gradle `build`에 포함하며 생성된 스니펫과 HTML은 Git에 포함하지 않는다. OpenAPI 변환, 대화형 문서와 외부 게시 방식은 현재 기능 범위에서 제외한다.

승인된 결정으로 현재 `docs/architecture/backend.md`의 공통 코드와 패키지 규칙이 구체화되므로, ADR 작성과 함께 다음 원칙을 해당 문서에 반영한다.

- 기능별 예외는 `{domain}/exception`에 둔다.
- Spring에 의존하지 않는 공통 예외 추상화는 `global/exception`에 둔다.
- Spring `ProblemDetail`을 사용하는 HTTP 오류 변환은 `global/error`에 둔다.
- `domain → {domain}.exception → global.exception` 의존을 허용한다.
- 기능별 오류 타입은 Spring HTTP 타입에 의존하지 않는다.
- 오류 응답은 Spring `ProblemDetail`을 사용한다.

## 후속 기능과의 연결

1. 현재 API로 `DRAFT` 상품 기본 정보를 생성한다.
2. 별도 이미지 API가 이미지 저장과 메타데이터 등록을 완료한다.
3. 이미지 등록 완료 규칙에 따라 상품을 판매 준비 상태로 전환한다.
4. 별도 판매 일정 API가 `productId`, 가격, 수량, 시작·종료 시각을 등록한다.
5. 판매 일정 영역이 하루 최대 20개 제한과 판매자 간 일정 경쟁 정책을 처리한다.

현재 기능은 1단계만 구현하며 후속 단계의 테이블이나 API를 미리 만들지 않는다.

## 위험과 대응

- 임시 `X-Seller-Id`는 위조할 수 있다. 현재 범위에서는 식별값 전달용으로만 사용하고 인증 기능 도입 시 인증 principal로 교체한다.
- 애플리케이션과 DB 제약이 달라질 수 있다. 동일한 경계값을 Domain Test와 Persistence Test에서 각각 검증한다.
- 공통 오류 구조가 과도하게 확장될 수 있다. 공통 handler는 안정적인 예외 범주만 처리하고 기능별 오류 코드는 기능 패키지에 둔다.
- 폐기된 브랜치의 구현이 현재 설계를 암묵적으로 지배할 수 있다. 과거 코드는 참고만 하고 현재 PRD, 아키텍처 문서와 이 설계를 기준으로 새로 구현한다.
