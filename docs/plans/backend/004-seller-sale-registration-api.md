# ExecPlan: 판매자 판매 정보 등록 API 구현

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/prd.md`의 한정판 상품 판매 및 판매자 기능, `docs/architecture/decisions/ADR-003-sale-owns-price.md`, 2026-09-09 판매 정보 등록 API 브레인스토밍

이 ExecPlan의 범위는 판매자가 자신이 소유한 `READY` 상품에 가격, 수량과 판매 기간을 등록하고 PostgreSQL에 하나의 `Sale`을 생성하는 독립적으로 검증 가능한 백엔드 기능으로 제한한다. 상품 생성과 최초 이미지 등록은 이 기능의 선행 작업이며, 프론트엔드 제출 조율은 포함하지 않는다.

## 목적과 인수 기준

이 변경 뒤에는 판매자가 상품 ID와 자신의 임시 식별자를 전달하고 판매 조건을 등록할 수 있다. 백엔드는 상품 소유권과 `READY` 상태, 판매 값과 기간을 검증하고, 같은 상품의 기존 판매 기간과 겹치지 않을 때만 판매 정보를 저장한다.

- 유효한 `X-Seller-Id`와 판매 본문으로 `POST /api/products/{productId}/sales`를 호출하면 `201 Created`, `Location: /api/sales/{saleId}`와 생성된 판매 응답을 받는다.
- `Sale`은 `productId`, KRW 정수 가격, 최초 수량, 잔여 수량, 시작·종료 시각과 생성 시각을 저장하며 `sellerId`와 계산 가능한 상태는 중복 저장하지 않는다.
- 존재하지 않는 상품과 다른 판매자의 상품은 모두 `404 Not Found`를 반환한다.
- 소유한 상품이 `DRAFT`이면 `409 Conflict`를 반환한다.
- 같은 상품의 판매 기간은 시작 포함·종료 제외 구간 `[startsAt, endsAt)`으로 해석하며, 중첩 등록과 동일 요청 재전송은 `409 Conflict`를 반환한다.
- PostgreSQL exclusion constraint가 순차 요청뿐 아니라 동시 요청에서도 판매 기간 중첩을 최종적으로 방지한다.
- 상태는 저장하지 않고 현재 시각, 판매 기간과 잔여 수량으로 `SCHEDULED`, `ON_SALE`, `SOLD_OUT`, `ENDED` 중 하나를 계산해 응답한다.

이번 계획은 상품·이미지 등록 구현, 판매 수정·취소·삭제·조회 API, 주문과 재고 예약·차감, 하루 최대 20개 상품 편성 정책, 판매자별 목록, JWT 인증·인가, 프론트엔드 호출 조율과 멱등성 키를 포함하지 않는다. 판매 일정 수정이나 취소가 필요해지면 exclusion constraint 적용 대상을 포함해 별도 기능으로 설계한다.

## 맥락과 구현 접근

`docs/architecture/backend.md`에 따라 `sale` 기능을 `presentation`, `application`, `domain`, `infrastructure` 계층으로 나눈다. HTTP Request는 presentation에서 Application DTO로 변환하고, Application Service는 Domain Repository만 의존하며 트랜잭션과 여러 도메인 객체의 흐름을 조율한다. JPA Entity와 Domain Entity는 하나로 사용한다.

- `docs/prd.md`: 판매자가 상품별 판매 일정과 수량을 설정하고, 동일 상품을 여러 판매 일정으로 다시 판매할 수 있어야 한다고 정의한다.
- `docs/architecture/decisions/ADR-002-backend-persistence-stack.md`: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers 사용 결정을 기록한다.
- `docs/architecture/decisions/ADR-003-sale-owns-price.md`: `Product`는 카탈로그 정보를, `Sale`은 가격·판매 수량·시작·종료 시각을 소유하도록 결정한다.
- `docs/plans/backend/001-seller-product-registration-api.md`: 상품은 이미지 없이 `DRAFT`로 생성되고 판매 일정은 `READY` 상품에만 등록할 수 있다고 정의한다.
- `docs/plans/backend/003-seller-product-image-upload-api.md`: 최초 이미지 등록과 같은 DB 트랜잭션에서 상품을 `DRAFT`에서 `READY`로 전환하고 `Product` 행을 비관적으로 잠그도록 계획한다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/product/domain/Product.kt`: 현재 작업 트리에서 `DRAFT → READY` 상태 전이를 도메인 메서드로 제공한다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/product/domain/ProductRepository.kt`: 현재 작업 트리에서 일반 조회와 `findByIdForUpdate()` 계약을 제공한다.
- `apps/backend/src/main/resources/db/migration/V1__create_products.sql`과 `V2__create_product_images.sql`: 상품과 이미지 스키마가 각각 `V1`, `V2`를 사용한다.

판매 정보는 다음 구조로 추가한다. 타입 수가 적은 초기 단계이므로 `request`, `response`, `dto` 하위 패키지를 미리 만들지 않는다.

```text
io.github.sehako.japda.sale
├── presentation
│   ├── SaleController
│   ├── SaleExceptionHandler
│   ├── CreateSaleRequest
│   └── CreateSaleRequestConverter
├── application
│   ├── SaleService
│   ├── CreateSaleDto
│   ├── SaleResponse
│   ├── SaleTargetProductNotFoundException
│   └── ProductNotReadyForSaleException
├── domain
│   ├── Sale
│   ├── SaleStatus
│   ├── InvalidSaleException
│   ├── SalePeriodConflictException
│   └── SaleRepository
└── infrastructure
    ├── SaleJpaRepository
    └── SaleRepositoryImpl
```

`Sale`은 `Product` 객체와 JPA 연관관계를 만들지 않고 `productId: Long`만 보유한다. DB foreign key로 참조 무결성을 보장하고, Application Service가 `ProductRepository`로 상품 상태와 소유권을 검사한다. 판매자의 식별자는 `Product.sellerId`가 단일 출처이므로 `sales`에 중복 저장하지 않는다.

`SaleService`는 주입된 `Clock`에서 현재 시각을 한 번만 얻는다. 같은 트랜잭션에서 `ProductRepository.findByIdForUpdate(productId)`로 상품 행을 잠그고 존재 여부, 소유권과 `READY` 상태를 순서대로 확인한 뒤 `Sale.create()`와 `SaleRepository.save()`를 호출한다. 이미지 등록도 같은 상품 행 잠금을 사용하므로, 이미지 트랜잭션이 먼저 커밋되면 판매 등록이 성공하고 판매 등록이 먼저 `DRAFT`를 관찰하면 `409 Conflict` 후 재시도할 수 있다.

판매 기간 중첩은 애플리케이션의 사전 조회만으로 판단하지 않는다. PostgreSQL `btree_gist` extension과 exclusion constraint를 최종 무결성 경계로 사용한다. Repository adapter는 저장을 즉시 flush하여 constraint 위반을 트랜잭션 안에서 확인하고, 해당 constraint 이름일 때만 `SalePeriodConflictException`으로 변환한다. 다른 DB 오류는 중첩 오류로 위장하지 않고 예상하지 못한 오류 처리로 전달한다.

## 아키텍처 결정 기록

구현 코드보다 먼저 `docs/architecture/decisions/ADR-006-sale-boundary-and-period-overlap.md`를 작성하고 `docs/architecture/decisions/README.md` 목록에 추가한다. ADR에는 다음 결정을 하나의 판매 일정 영속성 경계로 기록한다.

- `Sale`은 `Product` JPA 연관관계 대신 `productId`와 DB foreign key를 사용한다.
- `Sale`은 `sellerId`와 상태를 저장하지 않는다.
- 판매 상태는 현재 시각, 기간과 잔여 수량으로 계산한다.
- 같은 상품의 판매 기간 중첩은 PostgreSQL `btree_gist` 기반 exclusion constraint로 방지한다.

이 결정은 기존 `docs/architecture/backend.md`의 ID 참조 허용 원칙과 PostgreSQL 사용 결정을 구체화하며 기존 규칙을 변경하지 않으므로 해당 문서는 수정하지 않는다. 구현 중 기존 규칙을 바꿔야 하는 발견이 생기면 임의로 확정하지 않고 사용자 승인을 받은 새 ADR을 먼저 작성한다.

## 인터페이스와 의존성

### HTTP 계약

요청은 다음과 같다.

```http
POST /api/products/10/sales
X-Seller-Id: 123
Content-Type: application/json

{
  "price": 10000,
  "quantity": 100,
  "startsAt": "2026-09-09T09:00:00Z",
  "endsAt": "2026-09-16T09:00:00Z"
}
```

성공 응답은 다음과 같다.

```http
HTTP/1.1 201 Created
Location: /api/sales/1
Content-Type: application/json
```

```json
{
  "id": 1,
  "productId": 10,
  "price": 10000,
  "initialQuantity": 100,
  "remainingQuantity": 100,
  "startsAt": "2026-09-09T09:00:00Z",
  "endsAt": "2026-09-16T09:00:00Z",
  "status": "SCHEDULED",
  "createdAt": "2026-09-09T08:00:00Z"
}
```

`Location`은 상대 URI `/api/sales/{saleId}`로 반환한다. 아직 판매 조회 API는 구현하지 않지만 생성된 리소스의 정규 식별 경로를 미리 유지한다. 알 수 없는 JSON 필드는 기존 상품 생성 API와 같이 허용한다.

입력 규칙은 다음과 같다.

- `sellerId`: `X-Seller-Id` 헤더로 전달하며 1 이상의 `Long`이어야 한다.
- `productId`: 경로 변수로 전달하며 1 이상의 `Long`이어야 한다.
- `price`: 필수 JSON 정수이며 1 이상의 `Long`인 KRW 가격이다.
- `quantity`: 필수 JSON 정수이며 1 이상의 `Long`이다.
- `startsAt`, `endsAt`: 필수 JSON 문자열이며 offset을 포함한 ISO-8601 시각이다. Application과 Domain에서는 `Instant`로 관리한다.
- `endsAt`은 `startsAt`보다 늦어야 하고 등록 시점보다 미래여야 한다.
- `startsAt`은 현재보다 과거여도 허용한다. 이 경우 다른 조건을 만족하면 즉시 판매 중으로 계산한다.

presentation은 헤더·경로·JSON의 누락, 타입과 시각 형식을 검증하고 `CreateSaleDto`로 변환한다. `Sale.create()`는 가격, 수량, 기간 순서와 종료 시각의 미래 조건처럼 단일 판매 객체에서 판단 가능한 규칙을 검사한다. 여러 필드가 잘못되면 `InvalidSaleException`에 필드별 오류를 가능한 범위에서 모은다. 생성 시 `initialQuantity`와 `remainingQuantity`에는 같은 요청 수량을 설정한다.

`Sale.statusAt(now)`는 상태를 저장하지 않고 다음 우선순위로 계산한다.

```text
now >= endsAt          -> ENDED
remainingQuantity == 0 -> SOLD_OUT
now < startsAt         -> SCHEDULED
그 외                   -> ON_SALE
```

### 오류 계약

`SaleExceptionHandler`는 `SaleController`로 범위를 제한한 `@RestControllerAdvice`로 구현한다. 두 도메인이 사용한다는 이유만으로 기존 `ProductExceptionHandler`를 공통화하지 않는다. 응답은 기존 상품 API의 `ProblemDetail` 형태와 내부 정보 비노출 원칙을 따른다.

- 헤더, 경로, 본문 구조·타입·값 오류는 `400 Bad Request`다. 필드 오류는 `errors`의 `sellerId`, `productId`, `price`, `quantity`, `startsAt`, `endsAt` 또는 `request` 키로 반환한다.
- 상품이 없거나 다른 판매자 소유이면 동일한 `404 Not Found`다. 타인의 상품 ID 존재 여부를 노출하지 않는다.
- 소유한 상품이 `DRAFT`이면 `409 Conflict`다.
- 기존 기간과 중첩되거나 동일 요청이 재전송되면 `409 Conflict`다.
- 예상하지 못한 서버·DB 오류는 내부 예외, SQL과 constraint 세부 정보를 노출하지 않는 `500 Internal Server Error`다.
- 현재 인증 기능이 없으므로 기존 상품 API와 같이 `X-Seller-Id`를 임시 식별자로 사용한다. JWT 인증이 병합되면 Request와 Application 계약은 유지하고 Controller의 판매자 식별자 공급만 인증 주체로 교체한다.

`404`와 두 종류의 `409`는 상태 코드는 같아도 title과 detail로 원인을 구분한다. 동일 요청 재전송을 기존 성공 응답으로 복원하지 않으며 `Idempotency-Key` 저장소나 요청 해시를 추가하지 않는다.

### PostgreSQL 계약

현재 작업 트리의 이미지 migration이 `V2`이므로 판매 migration은 `V3__create_sales.sql`을 예상한다. 실행 직전에 최신 migration 번호를 다시 확인하고 이미 사용 중이면 다음 번호로 조정한다. migration은 다음 형태의 무결성을 제공한다.

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE sales
(
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    product_id         BIGINT                   NOT NULL,
    price              BIGINT                   NOT NULL,
    initial_quantity   BIGINT                   NOT NULL,
    remaining_quantity BIGINT                   NOT NULL,
    starts_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_sales_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_sales_price CHECK (price >= 1),
    CONSTRAINT chk_sales_initial_quantity CHECK (initial_quantity >= 1),
    CONSTRAINT chk_sales_remaining_quantity CHECK (
        remaining_quantity BETWEEN 0 AND initial_quantity
    ),
    CONSTRAINT chk_sales_period CHECK (ends_at > starts_at),
    CONSTRAINT ex_sales_product_period EXCLUDE USING gist (
        product_id WITH =,
        (tstzrange(starts_at, ends_at, '[)')) WITH &&
    )
);
```

foreign key에는 cascade delete를 설정하지 않는다. 상품 삭제 정책은 아직 없으며 판매·주문 이력을 암묵적으로 삭제해서는 안 된다. `btree_gist` extension 생성 권한은 배포 PostgreSQL의 선행 운영 조건이다. 기존 dependency로 구현 가능하므로 Gradle dependency는 추가하지 않는다.

## 작업 계획

구현은 테스트 주도 방식으로 진행한다. 각 마일스톤에서 먼저 실패하는 테스트를 작성하고, 해당 테스트를 통과시키는 최소 구현을 추가한 뒤 관련 테스트를 다시 실행한다. 현재 작업 트리의 이미지 등록 변경을 임의로 수정하거나 되돌리지 않으며, 이미지 구현이 제공하는 상품 잠금 조회와 `READY` 전이를 선행 조건으로 사용한다.

### 마일스톤 1: ADR과 판매 도메인 규칙 확립

먼저 ADR-006과 ADR 목록을 갱신해 승인된 도메인 경계, 계산 상태와 PostgreSQL 중첩 방지 결정을 기록한다. 이어 Spring Context 없는 `SaleTest`에서 가격과 수량의 양수 조건, 기간 순서, 미래 종료 시각, 초기 잔여 수량과 상태 계산의 경계 시각을 고정한다. 여러 값 오류를 수집하는 동작을 먼저 실패하는 테스트로 작성한 뒤 `Sale`, `SaleStatus`, `InvalidSaleException`을 최소 구현한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.sale.domain.*'

예상 관찰 결과: 판매 객체가 유효한 값으로만 생성되고, 고정된 시각에서 네 상태와 경계 조건이 결정적으로 계산되며 테스트가 종료 코드 0으로 완료된다.

### 마일스톤 2: PostgreSQL 스키마와 Repository 경계 완성

최신 Flyway 번호를 확인한 뒤 예상 경로 `V3__create_sales.sql`에 `btree_gist`, `sales` 테이블, foreign key, check constraint와 `[)` exclusion constraint를 추가한다. `SaleRepository`, `SaleJpaRepository`, `SaleRepositoryImpl`을 추가하고, 저장 시 flush하여 `ex_sales_product_period` 위반만 `SalePeriodConflictException`으로 변환한다.

PostgreSQL Testcontainers 기반 `SaleRepositoryImplTest`는 정상 저장과 값 보존, Product 객체가 아닌 `productId` 매핑, foreign key와 check constraint, 앞 일정의 종료와 다음 일정의 시작이 같은 인접 기간 허용, 부분·전체·경계 중첩 거부를 검증한다. 동시에 겹치는 두 판매를 저장했을 때 정확히 한 건만 커밋되고 다른 한 건은 기간 충돌이 되는지도 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.sale.infrastructure.*'

예상 관찰 결과: Flyway가 PostgreSQL에 스키마와 extension을 만들고 JPA 매핑을 검증하며, 모든 DB 제약과 기간 중첩 테스트가 종료 코드 0으로 완료된다.

### 마일스톤 3: 판매 등록 Application 흐름 완성

`SaleServiceTest`에 Product 잠금 조회 사용, 없는 상품과 다른 판매자 상품의 동일한 not-found 처리, `DRAFT` 충돌, 정상 판매 저장, 기간 중첩 전달과 고정 `Clock` 사용을 먼저 작성한다. `SaleService`는 하나의 트랜잭션에서 잠근 Product를 검증하고 `Sale.create()`와 저장을 조율한다. 이미지 기능의 Product Repository fake가 새 판매 테스트와 공유되지 않도록 sale 테스트 내부 대역을 두되 실제 Domain Repository 계약을 따른다.

이미지 등록과 판매 등록의 실제 경합 테스트는 이미지 기능이 완료된 뒤 통합 마일스톤에서 추가한다. 이 마일스톤에서는 Application 경계에서 `DRAFT`와 `READY` 결과를 각각 결정적으로 검증한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.sale.application.*'

예상 관찰 결과: `READY`이면서 요청 판매자가 소유한 상품만 판매 등록에 성공하고, 실패 흐름은 Sale을 저장하지 않으며 테스트가 종료 코드 0으로 완료된다.

### 마일스톤 4: JSON HTTP API와 오류 계약 제공

standalone MockMvc 테스트로 `POST /api/products/{productId}/sales`의 요청 변환, `201`, 상대 `Location`과 응답 상태 계산을 고정한다. 헤더·경로·본문의 누락과 타입 오류, 잘못된 시각, 도메인 값 오류, not-found, `DRAFT`, 기간 중첩과 예상하지 못한 오류가 합의된 `ProblemDetail`로 변환되는지 검증한 뒤 Controller, Request converter와 Sale 전용 Exception Handler를 구현한다.

기존 `ProductExceptionHandler`의 범위를 넓히거나 공통 handler를 새로 만들지 않는다. 기존 상품·이미지 Controller의 오류 응답과 `405`, `415` 처리가 바뀌지 않았는지 presentation 회귀 테스트를 함께 실행한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.sale.presentation.*' --tests 'io.github.sehako.japda.product.presentation.*'

예상 관찰 결과: 정상 요청은 `201`과 합의된 응답을 반환하고 모든 입력·상태 오류가 내부 정보를 노출하지 않는 상태와 본문으로 변환되며 기존 Product HTTP 계약도 유지된다.

### 마일스톤 5: 상품-이미지-판매 흐름 통합 검증

PostgreSQL은 기존 Testcontainers 설정을 사용하고 S3 저장소는 테스트 대역으로 교체하는 Spring Boot 통합 테스트를 추가한다. 상품 생성만 마친 `DRAFT` 상태에서는 판매 등록이 `409`인지 확인하고, 이미지 등록으로 `READY`가 된 뒤 같은 판매 요청이 성공하는지 검증한다. 이어 같은 요청 재전송과 겹치는 기간은 `409`, 인접 기간은 `201`, 다른 판매자의 요청은 `404`인지 확인한다.

같은 `DRAFT` 상품에 대한 이미지 등록과 판매 등록 경합에서는 두 흐름이 동일한 Product 행 잠금을 사용해 손상된 상태를 만들지 않는지 검증한다. 같은 상품에 겹치는 판매 등록 두 건을 동시에 실행하면 정확히 한 건만 저장되어야 한다.

전체 백엔드 테스트와 빌드를 실행한다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 상품 생성, 이미지 등록, `READY` 전환과 판매 등록이 순서대로 성공하고, 상태·소유권·중첩·동시성 회귀를 포함한 전체 테스트와 빌드가 종료 코드 0으로 완료된다.

## 검증

- 정상 등록: `READY` 상품에 유효한 요청을 보내면 `201`, `/api/sales/{id}` Location과 저장 값·계산 상태를 반환한다.
- 값 검증: 0·음수·범위 밖 `Long`, 부동소수·문자열 타입, 누락 필드와 잘못된 시각 문자열은 `400`이다.
- 기간 검증: `endsAt <= startsAt` 또는 `endsAt <= now`는 `400`이며 과거 `startsAt`과 미래 `endsAt` 조합은 허용한다.
- 상태 계산: 시작 전은 `SCHEDULED`, 기간 내 양수 재고는 `ON_SALE`, 기간 내 0 재고는 `SOLD_OUT`, 종료 경계부터는 `ENDED`다.
- 상품 경계: 없는 상품과 다른 판매자 상품은 동일한 `404`, `DRAFT` 상품은 `409`이며 Sale을 저장하지 않는다.
- 기간 경계: 같은 상품의 부분·전체 중첩과 동일 요청 재전송은 `409`, 앞 일정의 `endsAt`과 다음 일정의 `startsAt`이 같은 경우는 성공한다. 다른 상품의 같은 기간은 허용한다.
- 동시성: 같은 상품의 중첩 판매 요청 중 정확히 한 건만 성공하고, 이미지 등록과 경합해도 `READY`가 아닌 상품에 Sale이 저장되지 않는다.
- 영속성: Product foreign key, 가격·수량·기간 check constraint와 exclusion constraint가 PostgreSQL에서 동작한다.
- 회귀: 기존 상품 생성과 이미지 등록의 Domain, Application, Persistence, HTTP와 통합 테스트가 계속 통과한다.
- 보안: 타인의 Product 존재 여부, 내부 예외, SQL과 constraint 이름이 오류 응답에 노출되지 않는다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew build

예상 관찰 결과: 두 명령이 종료 코드 0으로 완료된다. Docker를 사용할 수 없으면 PostgreSQL Testcontainers 검증을 다른 DB로 대체하지 않고 미검증 항목을 `예상 밖의 발견`과 `결과와 회고`에 기록한다.

## 위험과 복구

- `CREATE EXTENSION btree_gist` 권한이 없는 배포 환경에서는 migration이 실패한다. 구현·배포 전에 대상 PostgreSQL의 허용 extension과 생성 권한을 확인하고, 권한이 없으면 자동으로 애플리케이션 검사 방식으로 낮추지 말고 사용자에게 대안을 다시 승인받는다.
- exclusion constraint는 저장된 모든 판매 일정에 적용된다. 판매 취소나 일정 수정이 추가되면 취소 데이터의 보존 방식과 constraint 적용 조건을 새로 설계해야 한다.
- 현재 이미지 등록 구현은 작업 트리에서 진행 중이다. Sale 구현을 시작할 때 해당 변경의 완료 상태, `ProductRepository.findByIdForUpdate()` 계약, `Product.READY` 전이와 실제 migration 번호를 다시 확인한다. 사용자 변경을 되돌리거나 덮어쓰지 않는다.
- Product 잠금은 이미지 상태 전이와 판매 등록의 경쟁을 직렬화하지만 exclusion constraint가 판매 기간 무결성의 최종 방어선이다. constraint 위반 변환은 이름이 정확히 `ex_sales_product_period`인 경우에만 수행해 다른 DB 결함을 `409`로 숨기지 않는다.
- 동일 요청이 재전송되면 성공 응답을 복원하지 않고 `409`를 반환한다. 향후 네트워크 재시도에서 성공 응답 복원이 필요하면 멱등성 저장소와 요청 해시를 별도 설계한다.
- migration은 적용 후 기존 파일을 수정하거나 삭제하지 않는다. 문제가 발견되면 새 forward migration으로 수정하며, 데이터 삭제나 강제 초기화는 사용자 승인 없이 수행하지 않는다.

## 열린 질문

구현을 막는 열린 질문은 없다. 하루 최대 20개 상품 편성, 판매 수정·취소, 주문 재고 동시성, 판매 조회와 JWT 인증은 이번 인수 기준과 독립된 후속 기능이다.

## 진행 상황

- [x] 2026-09-09 00:00Z PRD, 백엔드 아키텍처, ADR-003, 기존 상품 등록 계획과 이미지 등록 계획을 조사했다.
- [x] 2026-09-09 00:00Z API 경로, 값·시각 규칙, 계산 상태, 오류 상태, 중첩·멱등성·도메인 참조 정책을 사용자와 확정했다.
- [x] 2026-09-09 00:00Z 현재 작업 트리에서 이미지 등록 구현과 `V2` migration이 진행 중이며 Sale이 상품 잠금 조회와 `READY` 전이에 의존함을 확인했다.
- [x] 2026-09-09 00:00Z 하나의 독립 검증 가능한 판매 등록 기능으로 ExecPlan을 작성했다.
- [x] 2026-09-09 05:00Z 구현 직전에 최신 branch, 이미지 기능 완료 상태, 인증 병합 여부, Flyway 번호와 ADR 목록을 재확인했다.
- [x] 2026-09-09 05:23Z 마일스톤 1의 ADR과 판매 도메인 규칙을 테스트 주도로 구현했다.
- [x] 2026-09-09 05:23Z 마일스톤 2의 PostgreSQL 스키마와 Repository 경계를 테스트 주도로 구현했다.
- [x] 2026-09-09 05:23Z 마일스톤 3의 판매 등록 Application 흐름을 테스트 주도로 구현했다.
- [x] 2026-09-09 05:23Z 마일스톤 4의 JSON HTTP API와 오류 계약을 테스트 주도로 구현했다.
- [x] 2026-09-09 05:24Z 마일스톤 5의 상품-이미지-판매 통합 및 전체 회귀 검증을 완료했다.

## 예상 밖의 발견

- 관찰: 병렬로 진행되던 이미지 등록 구현이 완료되어 `003` 계획의 모든 마일스톤이 완료 처리되었고, 전체 백엔드 테스트가 다시 통과했다.
  근거: `docs/plans/backend/003-seller-product-image-upload-api.md`의 진행 상황과 결과와 회고, `GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew --no-daemon test` 성공 결과를 확인했다.
- 관찰: 첫 전체 검증에서는 Gradle 배포본을 내려받은 직후 daemon이 종료되었으나, `--no-daemon` 재실행은 성공했다.
  근거: 첫 실행은 테스트 결과 없이 `Gradle build daemon disappeared unexpectedly`로 종료되었고 같은 작업 트리의 재실행은 `BUILD SUCCESSFUL`이었다.
- 관찰: Hibernate 7.4.5는 PostgreSQL exclusion constraint 위반을 변환할 때 `ConstraintViolationException.constraintName`을 `null`로 제공했다.
  근거: 실제 PostgreSQL 통합 테스트에서 하위 `SQLException`은 SQLState `23P01`과 `ex_sales_product_period` 이름을 제공했지만 Hibernate 예외의 constraint 이름은 비어 있었다. Repository adapter는 Hibernate 이름 검사를 우선하고, SQLState와 정확히 인용된 constraint 이름을 함께 확인하는 fallback을 사용한다.
- 관찰: 검증 도중 병렬 이미지 작업이 이미지 코드를 `product` 하위의 기능별 세부 패키지로 이동해 공유 빌드의 소스 스냅샷과 테스트 컴파일 사이에 일시적 불일치가 발생했다.
  근거: 이미지 production과 테스트 파일의 이동이 완료된 뒤 별도 빌드 디렉터리에서 `compileKotlin`과 판매·Product presentation 테스트가 성공했다. 판매 구현은 이동된 이미지 계약을 기준으로 통합 테스트 import를 유지했다.
- 관찰: 조사 직전에는 이미지 등록이 문서 계획만 존재했지만, ExecPlan 작성 시점의 공유 작업 트리에는 이미지 구현 파일과 `V2__create_product_images.sql`이 추가되어 있고 Product 상태 전이와 잠금 Repository 계약도 변경 중이다.
  근거: `git status --short`, `Product.markReadyAfterImageRegistration()`, `ProductRepository.findByIdForUpdate()`와 `V2__create_product_images.sql`을 확인했다.
- 관찰: 현재 인증 구현은 없으며 상품 생성과 이미지 계획은 임시 `X-Seller-Id` 계약을 사용한다.
  근거: `ProductController.create()`와 `CreateProductRequestConverter.parseSellerId()`, 현재 backend dependency를 확인했다.
- 관찰: 현재 ADR 목록은 001, 002, 003, 005이며 판매 기간 중첩과 도메인 참조 방식을 다루는 ADR은 없다.
  근거: `docs/architecture/decisions/README.md`의 ADR 목록을 확인했다.

## 결정 기록

- 결정: 판매 생성 경로는 `POST /api/products/{productId}/sales`이고 성공 시 `201`과 `/api/sales/{saleId}` Location을 반환한다.
  이유: 대상 상품을 경로에서 명확히 하고 이미지 API와 구조를 맞추되 Sale의 독립 식별자를 유지하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: `Sale`은 `Product` 연관관계 대신 `productId`와 DB foreign key를 사용하고 `sellerId`를 중복 저장하지 않는다.
  이유: JPA 영속성 이점은 유지하면서 도메인 객체 결합과 소유권 데이터 불일치를 피하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 가격과 최초 수량은 1 이상의 `Long`이고, 잔여 수량은 최초 수량과 같은 값으로 시작해 이후 0까지 허용한다. 가격 단위는 KRW 정수다.
  이유: ADR-003의 가격 결정과 판매 재고 요구사항을 따르고 임의의 업무 상한을 추가하지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 판매 상태는 컬럼으로 저장하지 않고 현재 시각, 기간과 잔여 수량으로 `SCHEDULED`, `ON_SALE`, `SOLD_OUT`, `ENDED`를 계산한다.
  이유: 시간 경과에 따른 상태 동기화 배치와 저장 상태 불일치를 피하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: `endsAt > startsAt`, `endsAt > now`를 요구하고 과거 `startsAt`은 허용한다.
  이유: 유효한 미래 종료를 보장하면서 즉시 판매와 클라이언트·서버 시각 오차를 허용하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 판매 기간은 `[startsAt, endsAt)`로 해석하고 같은 상품의 기간 중첩을 허용하지 않는다.
  이유: 한 시점에 적용할 가격과 재고의 모호성을 없애면서 종료와 시작이 같은 연속 판매는 허용하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 기간 중첩은 PostgreSQL `btree_gist`와 exclusion constraint로 최종 보장한다.
  이유: 동시 요청과 애플리케이션 저장 경로 누락에도 핵심 무결성을 유지하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 없는 상품과 타인의 상품은 `404`, `DRAFT`와 기간 중첩은 `409`로 처리한다.
  이유: 상품 존재 여부 노출을 막고 유효한 리소스의 현재 상태 충돌을 HTTP 의미에 맞게 표현하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 멱등성 키를 사용하지 않으며 동일 요청 재전송은 기간 중첩으로 `409`를 반환한다.
  이유: 이번 범위에 별도 멱등성 저장소와 요청 해시 정책을 추가하지 않기로 했기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

판매자가 자신이 소유한 `READY` 상품에 가격, 수량과 `[startsAt, endsAt)` 판매 기간을 등록하는 API를 구현했다. `Sale`은 상품 ID와 판매 값만 저장하고 판매자와 상태를 중복 저장하지 않으며, 응답 상태는 주입된 `Clock`의 동일한 현재 시각을 기준으로 계산한다. 상품 없음과 타인 소유는 동일한 `404`, `DRAFT`와 기간 중첩은 원인별 `409`, 입력 오류는 필드별 `400`으로 반환하고 예상하지 못한 오류의 내부 정보는 노출하지 않는다.

PostgreSQL에는 `V3__create_sales.sql`로 `sales` 테이블, foreign key, check constraint와 `btree_gist` 기반 exclusion constraint를 추가했다. Repository는 `saveAndFlush()`로 위반을 호출 안에서 확인하고 `ex_sales_product_period`만 도메인 충돌로 변환한다. Hibernate 7.4.5에서 exclusion constraint 이름이 비어 있는 실제 동작 때문에 Hibernate constraint 이름 확인을 우선하되, 하위 `SQLException`의 SQLState `23P01`과 정확히 인용된 constraint 이름을 함께 확인하는 fallback을 추가했다. 이는 오류 계약을 바꾸지 않는 adapter 구현 차이다.

도메인, application, PostgreSQL 영속성, standalone HTTP와 실제 Spring Boot 통합 테스트를 추가했다. 통합 테스트는 `DRAFT → 이미지 등록 → READY → 판매 등록`, 동일·중첩·인접 기간, 타인 소유 은닉, 이미지와 판매 등록 경합 및 동시 중첩 판매 중 정확히 한 건만 저장되는 동작을 검증한다. 확정된 판매 경계와 중첩 방지 결정은 `ADR-006-sale-boundary-and-period-overlap.md`에 기록하고 ADR 목록을 갱신했다.

최종 검증은 병렬 이미지 패키지 이동과 공유 Gradle 산출물 경합을 피하도록 별도 빌드 디렉터리를 사용했다. `GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew --no-daemon -Pkotlin.incremental=false -I /tmp/japda-sale-domain.init.gradle clean test`와 같은 옵션의 `build`가 모두 종료 코드 0으로 완료됐다. 계획의 인수 기준에서 제외되거나 미검증으로 남은 항목은 없다. 운영 배포 전 PostgreSQL 환경의 `btree_gist` extension 생성 권한 확인은 계획에 명시된 선행 조건으로 남는다.
