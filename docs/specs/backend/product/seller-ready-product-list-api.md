# 판매자 판매 준비 완료 상품 목록 조회 API 설계

## 목적과 완료 조건

판매 일정 등록에 사용할 수 있도록 판매자가 본인 소유의 `READY` 상품 목록을 조회하는 API를 제공한다. 목록은 상품 식별자와 상품명만 반환하고, 상품 수가 증가해도 일정한 조회 비용을 유지할 수 있도록 정렬 기준별 커서 페이지네이션을 적용한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 판매자는 본인 소유이면서 `READY` 상태인 상품만 조회할 수 있다.
- 응답 항목은 상품 식별자와 상품명만 포함한다.
- 최신 등록순, 오래된 등록순, 상품명 오름차순과 상품명 내림차순을 지원한다.
- 각 정렬은 상품 식별자를 유일한 보조 정렬 기준으로 사용하고 커서 이후의 항목을 중복 없이 조회한다.
- 전체 건수 조회 없이 `size + 1`개 조회로 다음 페이지 존재 여부를 판단한다.
- PostgreSQL 복합 인덱스와 조회 projection으로 필터, 정렬 및 반환 열을 최적화한다.
- 잘못된 요청은 기존 `ProblemDetail` 계약으로 응답한다.
- Spring REST Docs로 HTTP 계약을 문서화한다.

## 기존 구조와 선행 조건

[상품 기본 정보 등록 설계](seller-product-registration-api.md), [상품 이미지 최초 등록 설계](seller-product-image-registration-api.md), [판매 일정 등록 설계](../sale/seller-sale-scheduling-api.md), [백엔드 아키텍처](../../../architecture/backend.md)를 따른다.

상품 기본 정보 등록은 상품을 `DRAFT`로 생성하고, 이미지 최초 등록이 완료되면 상품을 `READY`로 전환한다. `READY`는 판매 일정 등록 준비가 완료됐다는 뜻이며 판매 시작이나 특정 판매 일정 등록 여부를 의미하지 않는다.

하나의 상품 원본은 여러 판매 일정에서 다시 사용할 수 있다. 따라서 이 API는 `sales`를 조회하거나 판매 일정 등록 여부를 기준으로 상품을 제외하지 않는다. 판매 일정이 이미 존재하는 상품도 소유자와 `READY` 조건을 만족하면 결과에 포함한다.

## 범위

### 포함

- 판매자 본인의 `READY` 상품 목록 조회
- 네 가지 정렬과 정렬 기준별 커서 페이지네이션
- 조회 전용 응답과 projection
- PostgreSQL 조회 인덱스
- 입력 검증과 기존 `ProblemDetail` 오류 계약
- 애플리케이션, 프레젠테이션, 영속성 및 통합 테스트
- Spring REST Docs 문서 생성

### 제외

- `DRAFT`를 포함한 판매자 전체 상품 목록
- 상품 상세, 설명 및 생성 시각 반환
- 대표 이미지 URL과 S3 객체 조회
- 상품명 검색과 정의된 네 가지 외의 정렬 조건
- 판매 일정 등록 여부에 따른 필터
- 전체 상품 수와 전체 페이지 수 반환
- 특정 시점의 목록을 고정하는 스냅샷 페이지네이션
- 상품 수정, 삭제 및 상태 변경
- 실제 판매자 인증과 계정 존재 여부 확인

## HTTP 계약

### 요청

첫 페이지는 커서를 생략해 요청한다.

```http
GET /api/products/ready?sort=latest&size=20
X-Seller-Id: 1
```

다음 페이지는 직전 응답의 `nextCursor`를 전달한다.

```http
GET /api/products/ready?sort=name-asc&cursor=eyJ2IjoxLCJzb3J0IjoibmFtZS1hc2Mi...&size=20
X-Seller-Id: 1
```

| 입력 | 필수 여부 | 계약 |
| --- | --- | --- |
| `X-Seller-Id` | 필수 | 양의 `Long` 판매자 식별자 |
| `sort` | 선택 | 정렬 방식, 기본값 `latest` |
| `cursor` | 선택 | 직전 응답에서 받은 Base64 URL-safe 불투명 커서 |
| `size` | 선택 | 페이지 크기, 기본값 `20`, 허용 범위 `1~100` |

`sort`는 다음 네 값만 허용한다.

| `sort` | 정렬 기준 |
| --- | --- |
| `latest` | `id DESC` |
| `oldest` | `id ASC` |
| `name-asc` | `name COLLATE "C" ASC, id ASC` |
| `name-desc` | `name COLLATE "C" DESC, id DESC` |

상품명은 중복될 수 있으므로 상품명 정렬에서도 `id`를 보조 정렬 기준으로 사용한다. 클라이언트는 커서의 내부 구조를 해석하거나 변경하지 않고 다음 요청에 그대로 전달한다. 커서는 이를 발급한 응답과 같은 `sort`에서만 사용할 수 있다.

### 성공 응답

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "items": [
    {
      "id": 41,
      "name": "한정판 상품"
    },
    {
      "id": 37,
      "name": "콜라보 상품"
    }
  ],
  "nextCursor": "eyJ2IjoxLCJzb3J0IjoibmFtZS1hc2Mi..."
}
```

- `items`는 요청한 `sort`에 따라 정렬한다.
- `nextCursor`는 다음 페이지가 있을 때 현재 페이지의 마지막 상품이 가진 정렬 경계를 인코딩한 문자열이다.
- 다음 페이지가 없으면 `nextCursor`는 `null`이다.
- 조회 결과가 없더라도 `items`는 빈 배열로 반환하고 `nextCursor` 필드를 생략하지 않는다.
- `hasNext`는 `nextCursor`의 존재 여부와 같은 정보를 중복하므로 반환하지 않는다.
- Entity를 직접 반환하지 않고 application 계층의 응답 객체를 사용한다.

마지막 페이지의 예시는 다음과 같다.

```json
{
  "items": [
    {
      "id": 3,
      "name": "마지막 상품"
    }
  ],
  "nextCursor": null
}
```

## 페이지네이션 정책

정렬별 커서 경계는 다음과 같다.

| 정렬 | 커서가 담는 값 | 후속 페이지 조건 |
| --- | --- | --- |
| `latest` | `id` | `id < :id` |
| `oldest` | `id` | `id > :id` |
| `name-asc` | `name`, `id` | `(name COLLATE "C", id) > (:name COLLATE "C", :id)` |
| `name-desc` | `name`, `id` | `(name COLLATE "C", id) < (:name COLLATE "C", :id)` |

상품명 정렬은 대소문자를 구분하고 PostgreSQL의 `C` collation을 명시적으로 사용한다. 이에 따라 데이터베이스 설치 환경과 무관한 UTF-8 바이트 순서로 정렬한다. 한국어 사전식 정렬, 초성 정렬, 대소문자를 무시한 정렬과 Unicode 정규화는 제공하지 않는다.

첫 페이지는 선택한 정렬에 맞춰 커서 조건 없이 조회한다. 예를 들어 `latest`의 첫 페이지는 다음과 같다.

```sql
SELECT id, name
FROM products
WHERE seller_id = :sellerId
  AND status = 'READY'
ORDER BY id DESC
LIMIT :queryLimit;
```

후속 페이지는 커서를 배타적 경계로 사용한다. 예를 들어 `latest`의 후속 페이지는 다음과 같다.

```sql
SELECT id, name
FROM products
WHERE seller_id = :sellerId
  AND status = 'READY'
  AND id < :cursor
ORDER BY id DESC
LIMIT :queryLimit;
```

`queryLimit`에는 요청 `size + 1`을 전달한다. 조회 결과가 요청 `size`보다 많으면 마지막 한 건을 응답에서 제거하고, 응답에 남은 마지막 상품의 정렬 경계를 인코딩해 `nextCursor`로 설정한다. 추가 한 건이 없으면 `nextCursor`는 `null`이다. 전체 건수를 구하는 `COUNT` 쿼리는 실행하지 않는다.

각 정렬에서 커서가 없는 쿼리와 있는 쿼리를 분리한다. `cursor IS NULL OR ...` 형태의 조건을 사용하지 않아 각 쿼리의 인덱스 범위 조건을 명확하게 유지한다. 상품명 정렬은 PostgreSQL 행 값 비교를 사용해 이름과 식별자의 복합 경계를 하나의 인덱스 범위 조건으로 표현한다.

조회 도중 새 상품이 생성되거나 기존 `DRAFT` 상품이 `READY`로 전환될 수 있다. 이미 지나간 정렬 구간에 새로 들어간 상품은 발급된 커서의 후속 페이지에 나타나지 않을 수 있으며, 클라이언트는 최신 목록을 확인할 때 첫 페이지부터 다시 조회한다. 현재 상품명 수정과 `READY`에서 다른 상태로 전환하는 기능은 없으므로 동일 정렬의 커서를 순차적으로 사용할 때 이미 반환한 항목을 다시 반환하지 않는다. 이번 API는 여러 요청에 걸친 스냅샷 일관성을 보장하지 않는다.

### 커서 형식

커서는 버전이 있는 내부 payload를 Base64 URL-safe 방식으로 인코딩한 문자열이다. 패딩은 사용하지 않는다.

- 모든 커서는 버전, `sort`, 마지막 상품의 `id`를 포함한다.
- 상품명 정렬의 커서는 마지막 상품의 `name`도 포함한다.
- 현재 버전은 `1`이다.
- 디코딩 실패, 지원하지 않는 버전, 필수 값 누락, 값 범위 위반과 요청 `sort` 불일치는 잘못된 커서로 처리한다.
- Base64 인코딩은 암호화나 서명이 아니다. 커서를 변조하더라도 항상 요청 판매자의 `seller_id`와 `READY` 조건을 적용하므로 다른 판매자의 상품에 접근할 수 없다.

내부 payload의 논리 구조는 다음과 같다. 이는 서버 구현 계약이며 HTTP 응답에서는 인코딩된 문자열만 공개한다.

```json
{
  "version": 1,
  "sort": "name-asc",
  "id": 37,
  "name": "콜라보 상품"
}
```

## 영속성과 조회 최적화

새 Flyway migration으로 다음 인덱스를 추가한다. 기존 migration은 수정하지 않으며 실제 migration 번호는 구현 시점의 저장소에서 다음 번호를 사용한다.

```sql
CREATE INDEX products_seller_ready_id_idx
    ON products (seller_id, status, id)
    INCLUDE (name);

CREATE INDEX products_seller_ready_name_id_idx
    ON products (seller_id, status, name COLLATE "C", id);
```

- 두 인덱스 모두 `seller_id`와 `status`를 동등 조건에 사용한다.
- `products_seller_ready_id_idx`는 `latest`와 `oldest`의 커서 범위와 정렬에 사용한다.
- `products_seller_ready_name_id_idx`는 `name-asc`와 `name-desc`의 복합 커서 범위와 정렬에 사용한다.
- PostgreSQL B-tree의 역방향 스캔을 사용하므로 오름차순과 내림차순 인덱스를 각각 만들지 않는다.
- 식별자 정렬 인덱스는 응답에 필요한 `name`을 포함 열로 두어 PostgreSQL이 조건에 따라 index-only scan을 선택할 수 있게 한다. 상품명 정렬 인덱스는 `name`과 `id`가 모두 키 열이다.
- 실제 실행 계획은 테이블 통계와 데이터 분포에 따라 PostgreSQL이 결정하므로 특정 scan 방식을 API 계약으로 보장하지 않는다.

조회에서는 전체 `Product` Entity 대신 `id`, `name`만 선택하는 전용 projection을 사용한다. 상품 설명, 상태와 생성 시각을 불필요하게 로딩하지 않는다. `sales`나 `product_images`와 조인하지 않는다.

## 계층과 데이터 흐름

```text
GET /api/products/ready
        ↓
presentation: 헤더와 쿼리 매개변수 파싱, 응답 직렬화
        ↓
application: 입력 범위와 커서 검증, size + 1 조회, 페이지 응답 조립
        ↓
domain ProductRepository: READY 상품 요약 조회 계약
        ↓
infrastructure: 정렬과 커서 유무별 projection 조회
        ↓
PostgreSQL products + 복합 인덱스
```

- `presentation`은 `X-Seller-Id`, `sort`, `cursor`, `size`의 HTTP 형식을 처리하고 application 유스케이스를 호출한다.
- `application`은 판매자 식별자, 정렬과 페이지 입력의 의미를 검증하고 커서를 디코딩한다. 읽기 전용 트랜잭션에서 정렬별 조회를 수행하고 결과와 다음 커서를 조립한다.
- `domain`은 `ProductRepository`의 판매 준비 완료 상품 요약 조회 계약과 기술 독립적인 조회 결과 모델을 소유한다.
- `infrastructure`는 Spring Data JPA 기반 projection 조회와 domain 조회 결과 변환을 담당한다.
- application은 `JpaRepository`, SQL 또는 Spring Web 타입에 직접 의존하지 않는다.

기존 `ProductController`와 `ProductService`에 조회 유스케이스를 추가한다. 응답은 상품 항목을 나타내는 `ReadyProductResponse`와 페이지를 나타내는 `ReadyProductPageResponse`로 분리한다. 읽기 유스케이스에는 `@Transactional(readOnly = true)`를 적용한다.

새 도메인이나 모듈 경계, 의존성 방향을 도입하지 않으므로 ADR은 작성하지 않는다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`, `CommonErrorCode`, `ProductErrorCode`와 공통 예외 처리기를 재사용한다.

| 상황 | HTTP 상태 | 오류 코드 |
| --- | --- | --- |
| `X-Seller-Id` 누락 | 400 | `COMMON_REQUEST_HEADER_MISSING` |
| `X-Seller-Id` 숫자 변환 실패 또는 타입 범위 초과 | 400 | `COMMON_REQUEST_HEADER_INVALID` |
| 판매자 식별자가 0 이하 | 400 | `PRODUCT_SELLER_ID_INVALID` |
| `size` 숫자 변환 실패 또는 타입 범위 초과 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` |
| 지원하지 않는 `sort` | 400 | `PRODUCT_SORT_INVALID` |
| 커서 디코딩 실패, 지원하지 않는 버전, 내부 값 오류 또는 요청 정렬 불일치 | 400 | `PRODUCT_CURSOR_INVALID` |
| `size`가 `1~100` 범위 밖 | 400 | `PRODUCT_PAGE_SIZE_INVALID` |
| 예상하지 못한 조회 실패 | 500 | `COMMON_INTERNAL_SERVER_ERROR` |

정렬과 커서 오류에는 각각 논리 속성명 `sort`, `cursor`를 사용하고 페이지 크기 범위 오류에는 `size`를 사용한다. 빈 목록은 정상 결과이므로 `404 Not Found`로 처리하지 않는다. 현재는 판매자 계정 존재 여부를 확인하지 않으므로 존재하지 않는 판매자 식별자도 빈 목록을 반환한다. 커서 payload나 내부 디코딩 오류, SQL과 예외 메시지는 오류 응답에 노출하지 않는다.

## 검증 전략

### 애플리케이션 테스트

- 판매자 식별자, 정렬, 커서와 페이지 크기의 경계값을 검증한다.
- 정렬별 `size + 1`개 결과에서 응답 항목과 `nextCursor`를 올바르게 조립하는지 검증한다.
- 결과 수가 `size` 이하이면 `nextCursor`가 `null`인지 검증한다.
- 빈 결과가 빈 배열과 `null` 커서로 변환되는지 검증한다.
- 커서의 인코딩·디코딩, 지원하지 않는 버전, 손상된 payload와 요청 정렬 불일치를 검증한다.
- Repository에 정렬, `size + 1`과 올바른 복합 경계가 전달되는지 검증한다.

### 프레젠테이션 테스트와 API 문서

- MockMvc로 필수 판매자 헤더, 선택 쿼리 매개변수, 기본 정렬, 기본 페이지 크기와 성공 응답을 검증한다.
- 헤더와 쿼리 매개변수의 형식·범위 오류가 기존 `ProblemDetail` 계약을 따르는지 검증한다.
- 네 정렬의 첫 페이지, 다음 페이지와 마지막 페이지 응답을 검증한다.
- 같은 테스트에서 Spring REST Docs로 요청 헤더, 쿼리 매개변수와 응답 필드를 문서화한다.
- 백엔드 AsciiDoc 색인에 판매 준비 완료 상품 목록 조회 문서를 연결한다.

### PostgreSQL 영속성과 통합 테스트

- 판매자 본인의 `READY` 상품만 조회되고 다른 판매자의 상품과 `DRAFT` 상품은 제외되는지 검증한다.
- `latest`, `oldest`, `name-asc`, `name-desc` 결과가 정의된 순서와 일치하는지 검증한다.
- 같은 상품명을 가진 상품이 식별자 보조 정렬에 따라 안정적으로 정렬되는지 검증한다.
- 커서 경계 상품은 제외되고 선택한 방향의 다음 상품만 반환되는지 검증한다.
- 상품명에 한글, 영문, 공백과 특수문자가 있을 때 `C` collation 계약과 일치하는지 검증한다.
- 각 정렬로 페이지를 연속 조회했을 때 상품이 중복되거나 누락되지 않는지 검증한다.
- 판매 일정이 존재하는 `READY` 상품도 결과에 포함되는지 검증한다.
- migration 적용 후 두 복합 인덱스의 열 순서, collation과 포함 열이 의도와 같은지 확인한다.
- HTTP 요청부터 실제 PostgreSQL projection 조회까지 통합 검증한다.
- 관련 테스트와 Gradle `build`로 REST Docs 생성을 확인한다.

실행 계획은 데이터 양과 통계에 민감하므로 특정 `EXPLAIN` 출력 전체를 자동 테스트의 고정 문자열로 검증하지 않는다. 구현 확인 단계에서는 대표 데이터를 사용한 `EXPLAIN`으로 네 정렬의 커서 조회가 불필요한 전체 정렬이나 offset scan을 요구하지 않고 대응 인덱스를 사용할 수 있는지 별도로 확인하고 결과를 보고한다.

## 주요 결정

- 판매 일정 등록을 위한 명시적인 의미를 유지하기 위해 범용 상태 필터 대신 `GET /api/products/ready`를 사용한다.
- `latest`, `oldest`, `name-asc`, `name-desc` 네 정렬을 제공하고 기본값은 `latest`로 한다.
- 상품명이 중복돼도 순서를 고정할 수 있도록 상품명 정렬에 식별자를 보조 정렬 기준으로 사용한다.
- 정렬마다 커서 구성 값이 다르므로 버전과 정렬을 포함한 Base64 URL-safe 불투명 커서를 사용한다.
- 상품명은 환경에 따라 결과가 달라지지 않도록 대소문자를 구분하는 PostgreSQL `C` collation으로 정렬한다.
- 다음 페이지 판단에는 `size + 1` 조회를 사용하고 전체 건수는 제공하지 않는다.
- 판매 일정 존재 여부는 조회 조건에 포함하지 않는다. 상품 원본은 여러 판매 일정에 재사용할 수 있기 때문이다.
- 대표 이미지는 응답하지 않으며 S3 또는 이미지 테이블을 조회하지 않는다.
