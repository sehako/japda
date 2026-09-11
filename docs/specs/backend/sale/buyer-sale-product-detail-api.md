# 구매자 판매 상품 상세 조회 API 설계

## 목적과 완료 조건

구매자가 판매 상품 목록에서 선택한 판매 일정의 상품 상세 정보를 조회하는 공개 API를 제공한다. 판매 시작 전, 판매 중, 판매 종료 후에도 같은 `saleId`로 상세 정보를 조회할 수 있으며 상품의 전체 이미지를 표시 순서대로 반환한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 존재하는 판매 일정은 판매 상태와 관계없이 `200 OK`로 반환한다.
- 존재하지 않는 판매 일정은 계약된 `404 Not Found`로 반환한다.
- 판매 일정, 상품 원본과 전체 이미지 정보를 단일 join 쿼리와 Interface Projection으로 조회한다.
- 판매 기간과 상태는 기존 판매 시간 정책 및 주입된 `Clock`을 기준으로 계산한다.
- 전체 이미지의 상대 경로, 표시 순서와 대표 여부를 반환한다.
- PostgreSQL 조회, HTTP 계약과 Spring REST Docs 문서 생성을 검증한다.

## 기존 구조와 결정

[백엔드 아키텍처](../../../architecture/backend.md), [판매자 판매 일정 등록 설계](seller-sale-scheduling-api.md), [구매자 판매 상품 목록 조회 설계](buyer-sale-product-list-api.md), [상품·판매 도메인 경계 결정](../../../architecture/decisions/ADR-009-product-and-sale-domain-boundaries.md), [구매자 판매 상품 조회 모델 결정](../../../architecture/decisions/ADR-010-buyer-sale-product-query-model.md), [상품 이미지 상대 경로 응답 결정](../../../architecture/decisions/ADR-011-product-image-relative-path-response.md)을 따른다.

기존 판매 등록은 `READY` 상품만 허용한다. `READY` 상품에는 이미지가 1~10장 존재하고 대표 이미지가 정확히 하나 존재한다. `Sale`은 상품 Entity 대신 `productId`만 참조하며, 판매 기간은 `Asia/Seoul` 기준 판매일 00:00 이상 다음 날 00:00 미만이다.

이번 조회는 이러한 불변식을 전제로 판매 일정, 상품 원본과 전체 이미지를 조합한다. 조회 편의를 위해 Entity 연관관계를 추가하거나 쓰기 모델의 경계를 변경하지 않는다.

## 범위

포함 범위는 `GET /api/sales/{saleId}`, 판매 상품 상세 조회, 시간 기반 상태 계산, 전체 이미지 상대 경로, 오류 응답, PostgreSQL 테스트와 API 문서이다.

다음은 제외한다.

- 남은 재고 조회, 재고 예약·차감과 `SOLD_OUT` 상태
- 주문과 구매 처리
- 상품 또는 판매 일정 수정·삭제
- 구매자 인증과 개인화
- 판매 당시 상품 원본의 별도 snapshot
- CloudFront 배포, OAC, 캐시·서명 정책과 프런트엔드 환경 설정
- S3 Presigned URL 생성과 백엔드를 통한 이미지 다운로드
- 이미지 추가·교체·삭제와 썸네일 생성

## HTTP 계약

### 요청

```http
GET /api/sales/100
```

| 항목 | 계약 |
| --- | --- |
| 인증 | 불필요한 공개 조회 |
| `saleId` | 필수 경로 변수, 양의 `Long` |
| 조회 범위 | 판매 시작 전, 판매 중, 판매 종료 후 모두 허용 |

목록 API가 반환한 `saleId`로 개별 판매 일정을 식별한다. 같은 상품이 여러 판매 일정에 등록될 수 있으므로 `productId`를 상세 조회 식별자로 사용하지 않는다.

### 성공 응답

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "saleId": 100,
  "productId": 42,
  "name": "한정판 상품",
  "description": "상품 설명",
  "price": 35000,
  "quantity": 100,
  "saleDate": "2026-09-10",
  "startsAt": "2026-09-09T15:00:00Z",
  "endsAt": "2026-09-10T15:00:00Z",
  "status": "ENDED",
  "images": [
    {
      "path": "/products/42/550e8400-e29b-41d4-a716-446655440000/image-a",
      "displayOrder": 0,
      "isRepresentative": true
    },
    {
      "path": "/products/42/550e8400-e29b-41d4-a716-446655440000/image-b",
      "displayOrder": 1,
      "isRepresentative": false
    }
  ]
}
```

| 필드 | 계약 |
| --- | --- |
| `saleId` | 판매 일정 식별자 |
| `productId` | 상품 식별자 |
| `name` | 상품명 |
| `description` | 상품 설명, 설명이 없으면 `null` |
| `price` | 원화 기준 판매 가격 |
| `quantity` | 판매 일정 등록 시 설정한 최초 판매 수량 |
| `saleDate` | `Asia/Seoul` 기준 판매일 |
| `startsAt` | 판매 시작 시각을 나타내는 UTC `Instant` 문자열 |
| `endsAt` | 판매 종료 시각을 나타내는 UTC `Instant` 문자열 |
| `status` | 조회 시점의 판매 상태 |
| `images` | 표시 순서로 정렬된 전체 상품 이미지 1~10개 |
| `images[].path` | `/`로 시작하는 이미지 상대 경로 |
| `images[].displayOrder` | 0부터 시작하는 이미지 표시 순서 |
| `images[].isRepresentative` | 대표 이미지 여부, 목록에서 정확히 하나만 `true` |

`quantity`는 남은 재고를 뜻하지 않는다. 구매자에게 필요하지 않은 `sellerId`, 상품의 내부 `ProductStatus`, S3 `objectKey`, 이미지의 내부 식별자와 저장 메타데이터는 응답에 포함하지 않는다.

## 판매 상태

조회 가능 여부와 판매 상태를 분리한다. 존재하는 판매 일정은 상태와 관계없이 조회하며, 요청 처리 시 읽은 현재 `Instant`와 판매 시작·종료 시각으로 응답 상태를 계산한다.

| 조건 | 상태 |
| --- | --- |
| `now < startsAt` | `UPCOMING` |
| `startsAt <= now < endsAt` | `ON_SALE` |
| `now >= endsAt` | `ENDED` |

`SOLD_OUT`은 남은 재고가 없으므로 이번 범위에서 반환하지 않는다. 판매 상태를 DB에 중복 저장하거나 시간 경과에 따라 갱신하는 예약 작업은 도입하지 않는다.

## 계층과 데이터 흐름

기존 `sale` 패키지의 계층 구조와 구매자 판매 상품 목록 조회 모델을 확장한다.

- `presentation`: 기존 `SaleController`가 `saleId` 경로 변수를 받고 application 호출 결과를 반환한다. 경로 변수 형식 오류는 공통 요청 파라미터 오류로 변환한다.
- `application`: 기존 `SaleService`가 식별자 검증, 조회 호출, 미존재 처리, 조회 시각 획득, 판매 기간·상태 및 이미지 상대 경로 변환을 조율한다. Entity를 응답으로 직접 반환하지 않는다.
- `domain`: 기존 `BuyerSaleProductQueryRepository`에 상세 조회 계약을 추가하고, 판매·상품 정보와 이미지 목록을 담는 조회 전용 결과 타입을 둔다.
- `infrastructure`: 기존 `BuyerSaleProductJpaRepository`에 native join 쿼리와 내부 Interface Projection을 추가한다. 이미지별 projection 행을 하나의 domain 상세 조회 결과로 조립한다.

처리 순서는 다음과 같다.

1. presentation이 `saleId`를 `Long`으로 변환한다.
2. application이 `saleId`가 양수인지 검증한다.
3. 조회 Repository가 해당 판매 일정, 상품과 전체 이미지를 단일 join 쿼리로 조회한다.
4. 조회 결과가 없으면 application이 판매 일정 미존재 오류를 반환한다.
5. application이 `Clock`에서 현재 시각을 한 번 읽는다.
6. 기존 판매 기간 정책으로 시작·종료 시각과 상태를 계산한다.
7. 전체 이미지 객체 키를 상대 경로로 변환하고 상세 응답을 반환한다.

조회는 상태를 변경하거나 잠금을 획득하지 않으며 읽기 전용 트랜잭션을 사용한다. 쓰기용 `SaleRepository`에는 복합 조회 책임을 추가하지 않는다.

## 조회 projection과 쿼리

`BuyerSaleProductJpaRepository`는 이미지 한 장당 projection 행 하나를 반환한다. projection은 다음 scalar 값만 제공한다.

- 판매 일정 ID, 상품 ID
- 상품명과 선택적인 설명
- 판매 가격, 최초 판매 수량과 판매일
- 이미지 객체 키, 표시 순서와 대표 여부

native query는 다음 관계를 사용한다.

```sql
SELECT s.id                 AS "saleId",
       s.product_id         AS "productId",
       p.name               AS "name",
       p.description        AS "description",
       s.price              AS "price",
       s.quantity           AS "quantity",
       s.sale_date          AS "saleDate",
       pi.object_key        AS "imageObjectKey",
       pi.display_order     AS "imageDisplayOrder",
       pi.is_representative AS "imageRepresentative"
FROM sales s
INNER JOIN products p
        ON p.id = s.product_id
INNER JOIN product_images pi
        ON pi.product_id = p.id
WHERE s.id = :saleId
ORDER BY pi.display_order ASC
```

Spring Data Repository 메서드는 이미지별 Interface Projection의 `List`를 반환한다. infrastructure 구현체는 빈 목록을 미존재 결과로 변환하고, 첫 행의 판매·상품 필드와 모든 행의 이미지 필드로 하나의 상세 조회 결과를 만든다. projection과 조회 결과는 API Response가 아니며 HTTP 타입이나 JSON 표현에 의존하지 않는다.

Repository 계약의 반환 형태는 다음과 같다.

```kotlin
// domain
fun findDetailBySaleId(saleId: Long): BuyerSaleProductDetailQueryResult?

// Spring Data JPA
fun findDetailProjectionsBySaleId(saleId: Long): List<BuyerSaleProductDetailProjection>
```

Spring Data JPA의 빈 projection 목록은 infrastructure에서 `null`로 변환하고, application은 `null`을 `SALE_NOT_FOUND`로 변환한다.

판매 정보가 이미지 수만큼 반복되지만 상품당 이미지가 최대 10장이므로 비용이 제한적이다. 단일 쿼리로 필요한 데이터를 조회하고 application의 다중 Repository 호출과 조립을 피한다.

대표 이미지가 정확히 하나이고 이미지가 1장 이상이라는 `READY` 상품 불변식을 전제로 `INNER JOIN`을 사용한다. 정상적인 판매 일정에서 빈 쿼리 결과는 판매 일정 미존재로 처리한다. 데이터가 불변식을 위반한 경우 임의의 이미지를 만들거나 중복을 제거해 보정하지 않는다.

`Sale`, `Product`, `ProductImage` Entity 사이에 `@ManyToOne`, `@OneToMany` 또는 양방향 연관관계를 추가하지 않는다.

## 이미지 상대 경로

DB에는 기존 결정대로 S3 객체 키만 저장한다. API는 각 이미지 객체 키 앞에 `/`를 붙인 URI 경로를 `images[].path`로 반환한다. 서버가 생성하는 객체 키는 선행 `/`가 없는 `products/{productId}/{requestId}/{objectId}` 형식이므로 응답은 `/products/{productId}/{requestId}/{objectId}`가 된다.

이미지는 `displayOrder ASC`로 반환하며 정확히 하나의 항목만 `isRepresentative`가 `true`이다. 대표 이미지를 배열 첫 번째로 재정렬하지 않고 저장된 표시 순서를 유지한다.

S3 Presigned URL을 생성하거나 S3에 접근하지 않는다. 프런트엔드는 환경별 이미지 기준 URL과 `images[].path`를 결합한다. 기준 URL을 백엔드 응답이나 DB에 저장하지 않는다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`, `ErrorCategory`와 공통 handler를 재사용한다.

| 상황 | HTTP 상태 | 오류 코드 | 속성 |
| --- | --- | --- | --- |
| `saleId`가 `Long` 형식이 아님 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` | 없음 |
| `saleId`가 0 이하 | 400 | `SALE_ID_INVALID` | `saleId` |
| 판매 일정이 존재하지 않음 | 404 | `SALE_NOT_FOUND` | `saleId` |
| 예상하지 못한 DB·내부 오류 | 500 | `COMMON_INTERNAL_SERVER_ERROR` | 없음 |

`SALE_ID_INVALID`의 메시지는 "판매 일정 식별자는 양수여야 합니다."로 한다. `SALE_NOT_FOUND`의 메시지는 "판매 상품을 찾을 수 없습니다."로 한다. 판매 시작 전이나 판매 종료 후라는 이유로 오류를 반환하지 않는다.

내부 SQL, 객체 키의 저장 의미, AWS 설정과 예외 메시지는 오류 응답에 포함하지 않는다.

## 호환성과 영속성

- 기존 `GET /api/sales`와 `POST /api/sales` 요청·응답 계약을 변경하지 않는다.
- 기존 목록 API의 판매 기간, 상태와 UTC 응답 형식을 유지한다.
- 기존 migration과 테이블을 변경하지 않으며 새 migration도 필요하지 않다.
- 새 dependency를 추가하지 않는다.
- 상품·이미지·판매 Entity의 연관관계와 쓰기 흐름을 변경하지 않는다.
- 상품 수정·삭제 기능이 없는 현재 범위에서는 조회 시점의 상품 원본 정보를 반환한다. 판매 당시 정보 보존이 필요해지면 별도 snapshot 정책을 설계한다.

## 검증 전략

### 애플리케이션

- 양수인 `saleId`를 허용하고 0 이하를 `SALE_ID_INVALID`로 거절하는지 확인한다.
- 조회 결과가 없으면 `SALE_NOT_FOUND`를 반환하는지 확인한다.
- 판매 시작 직전·정각과 종료 직전·정각의 `UPCOMING`, `ON_SALE`, `ENDED` 경계를 확인한다.
- 판매 시작 전과 판매 종료 후에도 상세 응답을 반환하는지 확인한다.
- 요청 처리 중 읽은 동일한 현재 시각으로 상태를 계산하는지 확인한다.
- 모든 이미지 객체 키가 선행 `/` 하나를 가진 상대 경로로 변환되는지 확인한다.
- 이미지 표시 순서와 대표 여부가 보존되는지 확인한다.

### PostgreSQL 영속성

- Testcontainers PostgreSQL에서 판매 일정, 상품과 전체 이미지가 상세 조회 결과 하나로 조립되는지 확인한다.
- 이미지가 `displayOrder ASC`로 반환되고 대표 이미지 표시가 보존되는지 확인한다.
- 같은 상품의 모든 이미지가 포함되고 다른 상품의 이미지는 포함되지 않는지 확인한다.
- 이미지가 한 장과 열 장인 경계에서 올바르게 매핑되는지 확인한다.
- 존재하지 않는 `saleId`가 빈 projection 목록으로 반환되는지 확인한다.
- 실행 SQL 또는 Hibernate 통계를 이용해 상세 데이터 조회가 한 번의 join 쿼리인지 확인한다.

### HTTP·문서

- MockMvc로 경로 변수, 성공 필드, nullable 설명, 전체 이미지 배열과 오류 계약을 검증한다.
- `saleId` 형식 오류, 양수가 아닌 값과 미존재 값의 `ProblemDetail` 상태, 코드와 속성을 검증한다.
- 구매자·판매자 헤더 없이 조회되는지 확인한다.
- 같은 테스트에서 Spring REST Docs로 경로 변수, 응답 필드와 대표 오류를 문서화한다.
- HTTP부터 실제 PostgreSQL 조회까지 통합 검증하고 관련 테스트와 Gradle `build`를 실행한다.

테스트 클래스와 메서드는 기존 한글 네이밍과 `@DisplayName` 규칙을 따른다.

## 주요 결정과 후속 작업

이번 상세 조회는 기존 목록 조회의 조회 전용 Repository 패턴과 상품 이미지 상대 경로 계약을 같은 기능 영역에서 확장한다. 기존 계층·모듈 경계나 의존성 방향을 변경하지 않으므로 새로운 ADR은 작성하지 않는다.

후속 주문·재고 기능에서는 남은 수량의 원천과 동시성 제어를 먼저 설계한 뒤 `SOLD_OUT` 상태와 구매 가능 여부를 확장한다. 이번 API의 `quantity`를 남은 재고로 재해석하지 않는다.
