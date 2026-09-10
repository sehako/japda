# 구매자 판매 상품 목록 조회 API 설계

## 목적과 완료 조건

구매자가 판매일 하나를 선택해 해당 날짜의 판매 상품 목록을 조회하는 공개 API를 제공한다. 과거 판매 이력과 한국 기준 오늘·내일 상품만 조회할 수 있으며, 그보다 먼 미래 날짜는 거절한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 유효한 판매일은 해당 날짜의 판매 상품을 `200 OK`로 반환한다.
- 판매 기록이 없는 유효한 날짜는 빈 목록을 반환한다.
- 과거 날짜에는 하한을 두지 않고, 한국 기준 내일까지 조회를 허용한다.
- 한국 기준 내일보다 미래인 날짜는 계약된 `400 Bad Request`로 거절한다.
- 상품·판매·대표 이미지 정보를 조회 전용 projection과 단일 join 쿼리로 조회한다.
- 판매 기간과 상태는 기존 판매 시간 정책 및 주입된 `Clock`을 기준으로 계산한다.
- 대표 이미지의 상대 경로를 반환하고 실제 이미지 제공은 후속 CloudFront 작업으로 분리한다.
- PostgreSQL 조회, HTTP 계약과 Spring REST Docs 문서 생성을 검증한다.

## 기존 구조와 결정

[백엔드 아키텍처](../../../architecture/backend.md), [판매자 판매 일정 등록 설계](seller-sale-scheduling-api.md), [상품·판매 도메인 경계 결정](../../../architecture/decisions/ADR-009-product-and-sale-domain-boundaries.md), [구매자 판매 상품 조회 모델 결정](../../../architecture/decisions/ADR-010-buyer-sale-product-query-model.md), [상품 이미지 상대 경로 응답 결정](../../../architecture/decisions/ADR-011-product-image-relative-path-response.md)을 따른다.

기존 판매 등록은 `READY` 상품만 허용한다. `READY` 상품에는 이미지가 1~10장 존재하고 대표 이미지가 정확히 하나 존재한다. 판매 기간은 `Asia/Seoul` 기준 판매일 00:00 이상 다음 날 00:00 미만이며, `Sale`은 상품 Entity 대신 `productId`만 참조한다.

이번 조회는 이러한 불변식을 전제로 판매 일정, 상품 원본과 대표 이미지를 조합한다. 조회 편의를 위해 Entity 연관관계를 추가하거나 쓰기 모델의 경계를 변경하지 않는다.

## 범위

포함 범위는 `GET /api/sales`, 단일 판매일 검증, 판매 상품 목록 조회, 시간 기반 상태 계산, 대표 이미지 상대 경로, 오류 응답, PostgreSQL 테스트와 API 문서이다.

다음은 제외한다.

- 판매 상품 상세 조회
- 키워드 검색, 필터, 정렬 선택과 pagination
- 남은 재고 조회, 재고 예약·차감과 `SOLD_OUT` 상태
- 주문과 구매 처리
- 판매 일정 수정·취소
- 구매자 인증과 개인화
- CloudFront 배포, OAC, 캐시·서명 정책과 프런트엔드 환경 설정
- S3 Presigned URL 생성과 백엔드를 통한 이미지 다운로드
- 이미지 추가·교체·삭제와 썸네일 생성

## HTTP 계약

### 요청

```http
GET /api/sales?saleDate=2026-09-10
```

| 항목 | 계약 |
| --- | --- |
| 인증 | 불필요한 공개 조회 |
| `saleDate` | 필수 쿼리 파라미터, 유효한 `YYYY-MM-DD` |
| 시간대 | `Asia/Seoul` |
| 허용 범위 | 과거 하한 없음, 한국 기준 내일까지 |
| 정렬 | `createdAt ASC`, 동률이면 `saleId ASC` |
| pagination | 하루 최대 20건이므로 제공하지 않음 |

허용되는 가장 먼 미래 날짜는 `Clock`의 현재 시각을 `Asia/Seoul` 날짜로 변환한 값에 하루를 더한 날짜이다. 검증과 응답 상태 계산에는 요청 처리 중 한 번 읽은 동일한 현재 시각을 사용해 자정 경계에서 판단이 어긋나지 않게 한다. 서버 기본 시간대에 의존하지 않는다.

예를 들어 한국 날짜가 `2026-09-10`이면 `2026-09-11`까지 허용하고 `2026-09-12`부터 거절한다. 서비스 시작 전을 포함한 과거 날짜도 허용하며, 기록이 없으면 오류 대신 빈 목록을 반환한다.

### 성공 응답

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "sales": [
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
      "status": "ON_SALE",
      "representativeImagePath": "/products/42/550e8400-e29b-41d4-a716-446655440000/5b8d6f60-5c2f-4c68-a4ab-234a5e37d140"
    }
  ]
}
```

`description`은 상품에 설명이 없으면 `null`이다. `quantity`는 판매자가 일정 등록 시 설정한 최초 판매 수량이며 남은 재고를 뜻하지 않는다. `startsAt`과 `endsAt`은 기존 판매 등록 API와 동일한 UTC `Instant` 문자열이다. 판매일 `2026-09-10`의 한국 시간 판매 기간은 `2026-09-10 00:00` 이상 `2026-09-11 00:00` 미만이며, 위 UTC 값은 같은 시각을 나타낸다.

구매자에게 필요하지 않은 `sellerId`, 상품의 내부 `ProductStatus`, S3 `objectKey`라는 저장소 필드명은 응답에 포함하지 않는다. 판매 기록이 없을 때의 응답은 다음과 같다.

```json
{
  "sales": []
}
```

## 판매 상태

이번 API의 상태는 요청 처리 시 읽은 현재 `Instant`와 판매 시작·종료 시각으로 계산한다.

| 조건 | 상태 |
| --- | --- |
| `now < startsAt` | `UPCOMING` |
| `startsAt <= now < endsAt` | `ON_SALE` |
| `now >= endsAt` | `ENDED` |

`SOLD_OUT`은 남은 재고가 없으므로 이번 범위에서 반환하지 않는다. 이후 주문·재고 기능에서 정확한 잔여 수량을 관리할 때 상태 계약을 확장한다. 판매 상태를 DB에 중복 저장하거나 시간 경과에 따라 상태를 갱신하는 예약 작업은 도입하지 않는다.

## 계층과 데이터 흐름

기존 `sale` 패키지의 계층 구조를 따른다.

- `presentation`: 기존 `SaleController`가 `saleDate` 쿼리 파라미터를 받고 application 호출 결과를 반환한다. 쿼리 파라미터 누락·형식 오류는 공통 요청 파라미터 오류로 변환한다.
- `application`: 기존 `SaleService`가 조회 시각을 한 번 읽고 날짜 정책 검증, 조회 호출, 판매 기간·상태 및 이미지 상대 경로 변환을 조율한다. Entity를 응답으로 직접 반환하지 않는다.
- `domain`: 조회 전용 `BuyerSaleProductQueryRepository` 계약과 조회 결과 타입을 둔다. `Sale`의 기존 판매 기간 정책을 재사용해 시간대·경계 규칙을 중복 정의하지 않는다.
- `infrastructure`: Spring Data JPA의 native query와 내부 projection으로 `sales`, `products`, `product_images`를 join하고 domain 조회 결과로 변환한다.

처리 순서는 다음과 같다.

1. presentation이 필수 `saleDate`를 `LocalDate`로 변환한다.
2. application이 `Clock`에서 현재 시각을 한 번 읽는다.
3. `Asia/Seoul` 기준 오늘을 구하고 요청 날짜가 내일보다 미래인지 검증한다.
4. 조회 Repository가 요청 날짜에 해당하는 판매 일정과 상품·대표 이미지를 단일 쿼리로 조회한다.
5. application이 각 결과의 판매 시작·종료 시각, 상태와 대표 이미지 상대 경로를 계산한다.
6. 정렬된 목록을 응답한다.

조회는 상태를 변경하거나 잠금을 획득하지 않으며 읽기 전용 트랜잭션을 사용한다. 쓰기용 `SaleRepository`에는 복합 조회 책임을 추가하지 않는다.

## 조회 projection과 쿼리

조회 결과에는 다음 값만 포함한다.

- 판매 일정 ID, 상품 ID
- 상품명과 선택적인 설명
- 판매 가격, 최초 판매 수량과 판매일
- 정렬에 필요한 판매 일정 생성 시각
- 대표 이미지 객체 키

infrastructure 쿼리는 다음 관계를 사용한다.

```text
sales
  INNER JOIN products
    ON products.id = sales.product_id
  INNER JOIN product_images
    ON product_images.product_id = products.id
   AND product_images.is_representative = true
```

`WHERE sales.sale_date = :saleDate`로 한 날짜만 조회하고 `ORDER BY sales.created_at ASC, sales.id ASC`를 적용한다. 상품 준비 상태나 판매자별 조건을 다시 검사하지 않는다. 판매 등록 시점에 검증된 상태의 이력을 그대로 조회하며, 상품·이미지 수정 기능은 이번 범위에 없기 때문이다.

대표 이미지의 정확히 하나라는 DB 제약과 `READY` 전환 불변식을 전제로 `INNER JOIN`을 사용한다. 데이터가 불변식을 위반한 경우 해당 행을 임의의 이미지로 보정하거나 중복 제거하지 않는다. 실제 PostgreSQL 통합 테스트로 정상 데이터에서 판매 일정당 결과가 정확히 하나인지 검증한다.

조회 전용 결과는 API Response가 아니며 HTTP 타입이나 JSON 표현에 의존하지 않는다. `Sale`, `Product`, `ProductImage` Entity 사이에 `@ManyToOne`, `@OneToMany` 또는 양방향 연관관계를 추가하지 않는다.

## 대표 이미지 상대 경로

DB에는 기존 결정대로 S3 객체 키만 저장한다. API는 대표 이미지 객체 키 앞에 `/`를 붙인 URI 경로를 `representativeImagePath`로 반환한다. 서버가 생성하는 객체 키는 선행 `/`가 없는 `products/{productId}/{requestId}/{objectId}` 형식이므로 응답은 `/products/{productId}/{requestId}/{objectId}`가 된다.

S3 Presigned URL을 생성하거나 S3에 접근하지 않는다. 프런트엔드는 환경별 이미지 기준 URL과 `representativeImagePath`를 결합한다. 기준 URL을 백엔드 응답이나 DB에 저장하지 않는다.

이번 API가 반환하는 상대 경로는 이미지 식별 계약이며 CloudFront 도입 전 실제 다운로드 가능성을 보장하지 않는다. 후속 작업에서 비공개 S3 origin에 CloudFront OAC를 연결하고 해당 경로가 객체 키에 대응하도록 구성한다. CloudFront 도입으로 이번 API의 필드명이나 경로 형식을 변경하지 않는다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`, `ErrorCategory`와 공통 handler를 재사용한다.

| 상황 | HTTP 상태 | 오류 코드 | 속성 |
| --- | --- | --- | --- |
| `saleDate` 누락 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` | 없음 |
| `saleDate` 형식 또는 날짜가 올바르지 않음 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` | 없음 |
| `saleDate`가 한국 기준 내일보다 미래 | 400 | `SALE_DATE_OUT_OF_RANGE` | `saleDate` |
| 예상하지 못한 DB·내부 오류 | 500 | `COMMON_INTERNAL_SERVER_ERROR` | 없음 |

`SALE_DATE_OUT_OF_RANGE`의 메시지는 "판매일은 내일까지 조회할 수 있습니다."로 한다. 미래 범위 초과는 요청 값 자체가 허용 범위를 벗어난 것이므로 `409 Conflict`가 아닌 `400 Bad Request`로 처리한다. 쿼리 파라미터 누락 예외가 현재 공통 handler에서 내부 오류로 처리되지 않도록 공통 요청 파라미터 오류 변환 범위에 포함한다.

내부 SQL, 객체 키의 저장 의미, AWS 설정과 예외 메시지는 오류 응답에 포함하지 않는다.

## 호환성과 영속성

- 기존 `POST /api/sales` 요청·응답 계약과 동작을 변경하지 않는다.
- 기존 판매 기간 계산과 UTC 응답 형식을 유지한다.
- 기존 migration과 테이블을 변경하지 않으며 새 migration도 필요하지 않다.
- 새 dependency를 추가하지 않는다.
- 상품·이미지·판매 Entity의 연관관계와 쓰기 흐름을 변경하지 않는다.
- 공통 누락 쿼리 파라미터 오류 처리는 이후 다른 필수 쿼리 파라미터에도 동일한 `400` 계약을 제공한다.

## 검증 전략

### 도메인·애플리케이션

- 주입한 `Clock`으로 과거, 오늘과 내일을 허용하고 모레 이후를 `SALE_DATE_OUT_OF_RANGE`로 거절하는지 확인한다.
- 한국 시간 자정 직전·정각에 허용 날짜 상한이 올바르게 바뀌는지 확인한다.
- 판매 시작 직전·정각과 종료 직전·정각의 `UPCOMING`, `ON_SALE`, `ENDED` 경계를 확인한다.
- 요청 처리 중 동일한 현재 시각으로 날짜 검증과 모든 응답 상태를 계산하는지 확인한다.
- 대표 이미지 객체 키가 선행 `/` 하나를 가진 상대 경로로 변환되는지 확인한다.
- 빈 조회 결과를 빈 응답 목록으로 변환하는지 확인한다.

### PostgreSQL 영속성

- Testcontainers PostgreSQL에서 판매 일정, 상품과 대표 이미지가 단일 조회 결과로 정확히 매핑되는지 확인한다.
- 같은 날짜의 여러 판매 일정이 `createdAt`, `saleId` 순서로 반환되는지 확인한다.
- 다른 날짜의 판매 일정과 대표가 아닌 이미지가 결과에 포함되지 않는지 확인한다.
- 판매 일정마다 결과가 하나씩 반환되어 join으로 행이 중복되지 않는지 확인한다.
- 실행 SQL 또는 Hibernate 통계를 이용해 목록 데이터 조회가 한 번의 join 쿼리인지 확인한다.

### HTTP·문서

- MockMvc로 필수 `saleDate`, 날짜 형식, 성공 필드, nullable 설명, 빈 목록과 오류 계약을 검증한다.
- 내일보다 미래인 날짜의 `ProblemDetail` 상태, 코드와 `errors.saleDate`를 검증한다.
- 구매자·판매자 헤더 없이 조회되는지 확인한다.
- 같은 테스트에서 Spring REST Docs로 쿼리 파라미터, 응답 필드와 대표 오류를 문서화한다.
- HTTP부터 실제 PostgreSQL 조회까지 통합 검증하고 관련 테스트와 Gradle `build`를 실행한다.

테스트 클래스와 메서드는 기존 한글 네이밍과 `@DisplayName` 규칙을 따른다.

## 주요 결정과 후속 작업

조회 전용 projection과 단일 join 쿼리는 ADR-010으로 기록한다. 대표 이미지 상대 경로 응답과 이미지 제공 책임 분리는 ADR-011로 기록한다.

후속 CloudFront 작업에서는 다음을 별도로 설계한다.

- 비공개 S3 origin과 OAC
- 배포 도메인과 HTTPS
- 객체 경로 매핑
- 캐시·무효화 및 오류 응답 정책
- 프런트엔드의 환경별 이미지 기준 URL
- 필요한 경우 signed URL 또는 signed cookie

주문·재고 기능에서는 남은 수량의 원천과 동시성 제어를 먼저 설계한 뒤 `SOLD_OUT` 상태와 구매 가능 여부를 확장한다. 이번 API의 `quantity`를 남은 재고로 재해석하지 않는다.
