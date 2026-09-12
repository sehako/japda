# 구매자 배송지 등록 API 설계

## 목적과 완료 조건

구매자가 주문에 재사용할 배송지를 자신의 배송지 목록에 등록하는 API를 제공한다. 저장 배송지는 주문 당시 값을 보존하는 기존 주문 배송지 스냅샷과 독립된 생명주기를 가지며, 한 구매자는 배송지를 최대 10개까지 등록할 수 있다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- `POST /api/shipping-addresses`로 구매자 배송지 하나를 등록하고 `201 Created`로 반환한다.
- 기존 주문 API와 같은 `X-Buyer-Id` 헤더로 구매자를 식별한다.
- 배송지명을 포함한 입력 문자열을 정규화하고 기존 주문 배송지와 같은 배송 필드 검증 규칙을 적용한다.
- 같은 구매자의 배송지명 중복을 금지하고 DB unique 제약으로 최종 무결성을 보장한다.
- 구매자별 배송지 목록 행을 잠가 동시 요청에서도 배송지가 10개를 넘지 않게 한다.
- 저장 배송지와 주문의 배송지 스냅샷 사이에 JPA 연관관계를 만들지 않는다.
- PostgreSQL 영속성, 동시성, HTTP 계약과 Spring REST Docs 문서 생성을 검증한다.

## 기존 구조와 결정

[백엔드 아키텍처](../../../architecture/backend.md), [상품 원본과 판매 일정의 도메인 경계 결정](../../../architecture/decisions/ADR-009-product-and-sale-domain-boundaries.md), [주문 행 기반 재고 예약과 판매 일정 잠금 결정](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md), [구매자 배송지 목록 도메인과 잠금 행 구성 결정](../../../architecture/decisions/ADR-017-buyer-shipping-address-book-domain-and-locking.md)을 따른다.

현재 별도 구매자 또는 사용자 도메인과 인증 체계는 없다. 주문 API는 `X-Buyer-Id` 헤더의 양수 `Long` 값을 임시 구매자 식별자로 사용한다. 이번 API도 같은 식별 방식을 사용하며 구매자가 실제로 존재하는지는 확인하지 않는다.

기존 `order.domain.model.ShippingAddress`는 주문에 포함된 값 객체이다. 주문 생성 요청으로 받은 배송 정보를 주문 행에 복사해 주문 당시의 수취인과 주소를 보존한다. 새 `shippingaddress` 도메인은 사용자가 반복해서 선택할 수 있는 배송지 원본과 목록 규칙을 소유하며, 주문 도메인이나 주문 테이블을 변경하지 않는다.

## 범위

포함 범위는 구매자 배송지 단건 등록, 사용자별 배송지명 중복 금지, 사용자별 최대 10개 제한, 동시성 제어, 오류 응답, PostgreSQL 테스트와 API 문서이다.

다음은 제외한다.

- 배송지 목록 및 단건 조회 API
- 배송지 수정·삭제 API
- 기본 배송지 지정·변경 기능
- 주문 생성 시 저장 배송지 선택 또는 주문과 저장 배송지의 연동
- 실제 구매자 존재 여부 확인
- 인증·인가 체계 도입
- 주소 정제, 도로명 주소 검색 또는 외부 주소 서비스 연동
- 개인정보 필드 암호화와 별도 보안 인프라
- 배송지 변경 시각을 위한 `updated_at`

## HTTP 계약

### 요청

```http
POST /api/shipping-addresses
Content-Type: application/json
X-Buyer-Id: 123
```

```json
{
  "addressName": "집",
  "recipientName": "홍길동",
  "phoneNumber": "010-1234-5678",
  "postalCode": "06236",
  "address": "서울특별시 강남구 테헤란로 123",
  "detailAddress": "101동 1001호",
  "deliveryMessage": "문 앞에 놓아 주세요"
}
```

| 항목 | 계약 |
| --- | --- |
| `X-Buyer-Id` | 필수 헤더, 양의 `Long` |
| `addressName` | 필수, 공백 제거 후 1~100자 |
| `recipientName` | 필수, 공백 제거 후 1~100자 |
| `phoneNumber` | 필수, 공백 제거 후 1~30자 |
| `postalCode` | 필수, 공백 제거 후 1~20자 |
| `address` | 필수, 공백 제거 후 1~255자 |
| `detailAddress` | 필수, 공백 제거 후 1~255자 |
| `deliveryMessage` | 선택, 공백 제거 후 빈 값은 `null`, 최대 500자 |

전화번호와 우편번호는 국가별 형식을 제한하지 않고 필수 여부와 길이만 검증한다. 같은 구매자의 `addressName`은 정규화한 값으로 비교하고 대소문자를 구분한다. 실제 주소가 같아도 배송지명이 다르면 별도 배송지로 등록할 수 있다.

`address`는 향후 국내 주소 검색 서비스가 반환하는 전체 한글 도로명주소를 저장할 수 있도록 최대 255자로 유지한다. [행정안전부 주소 API 체험](https://m1.juso.go.kr/addrlink/openApi/apiExprn.do)은 전체 도로명주소와 참고항목 제외 주소를 구분하고, [공공데이터포털의 도로명주소 데이터 사례](https://www.data.go.kr/data/15152101/fileData.do?recommendDataYn=Y)는 도로명주소 컬럼에 최대 200자를 사용한다. `address`에는 검색 결과의 전체 한글 도로명주소를, `detailAddress`에는 동·층·호 등 별도 상세주소를 저장한다. 특정 제공자의 부가 응답 필드를 그대로 보존하는 것은 이번 계약에 포함하지 않는다.

요청 DTO는 HTTP 입력을 application DTO로 변환하고, 문자열 정규화와 의미 검증은 domain factory가 담당한다. JSON 형식 오류와 헤더 누락·형식 오류는 기존 공통 HTTP 오류 계약으로 처리한다.

### 성공 응답

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "shippingAddressId": 1,
  "addressName": "집",
  "recipientName": "홍길동",
  "phoneNumber": "010-1234-5678",
  "postalCode": "06236",
  "address": "서울특별시 강남구 테헤란로 123",
  "detailAddress": "101동 1001호",
  "deliveryMessage": "문 앞에 놓아 주세요",
  "createdAt": "2026-09-12T12:34:56Z"
}
```

응답에는 생성된 배송지 ID, 정규화된 배송 정보와 UTC `Instant` 형식의 생성 시각을 포함한다. 헤더로 전달한 `buyerId`와 내부 목록 ID는 반환하지 않는다. 기존 주문 생성 API와 가까운 계약을 사용하며 `Location` 헤더는 반환하지 않는다.

## 도메인과 계층

새 기능은 `io.github.sehako.japda.shippingaddress` 아래에서 기존 역할 기반 패키지 구조를 따른다.

- `presentation`: `ShippingAddressController`가 `X-Buyer-Id`와 등록 요청을 받고 application DTO로 변환한다.
- `application`: `BuyerShippingAddressService`가 domain 입력을 생성하고 persistence 충돌을 API 오류로 변환한다. 별도 등록 transaction service가 목록 생성, 잠금, 개수·중복 확인과 저장을 하나의 트랜잭션으로 조율한다.
- `domain`: `BuyerShippingAddressBook`, `BuyerShippingAddress`와 각 Repository 인터페이스가 배송지 목록 및 등록 규칙과 영속성 계약을 관리한다.
- `infrastructure`: Spring Data JPA 기반 목록 upsert·잠금, 배송지 집계·조회·저장과 named unique 제약 변환을 구현한다.
- `exception`: 배송지 입력, 개수 초과와 배송지명 중복에 대한 도메인 오류를 정의한다.

의존 방향은 `shippingaddress.presentation → shippingaddress.application → shippingaddress.domain`을 따른다. application은 `@Transactional`을 사용할 수 있지만 Spring Web 또는 JPA 구현 타입에는 의존하지 않는다. domain Entity는 다른 도메인의 Entity 또는 Repository에 의존하지 않는다.

`BuyerShippingAddress`는 주문의 `ShippingAddress`를 재사용하지 않는다. 두 모델은 현재 같은 배송 필드 검증 범위를 갖지만, 저장 배송지와 주문 스냅샷은 변경 이유와 오류 계약이 다르므로 독립된 모델로 유지한다.

## 영속 모델

`buyer_shipping_address_books` 테이블은 다음 값을 저장한다.

- 내부 생성 식별자 `id`
- 임시 구매자 식별자 `buyer_id`
- 생성 시각 `created_at`

`id`는 PK이고 `buyer_id`는 양수 `BIGINT NOT NULL`과 unique 제약을 갖는다. 구매자 한 명당 book 한 행만 존재한다. `created_at`은 `TIMESTAMP WITH TIME ZONE NOT NULL`로 저장한다. book은 구매자별 등록을 직렬화하는 aggregate와 잠금 행이며, 개수 카운터와 `updated_at`은 저장하지 않는다.

`buyer_shipping_addresses` 테이블은 다음 값을 저장한다.

- 내부 생성 식별자 `id`
- 목록 참조 `buyer_shipping_address_book_id`
- 배송지명 `address_name`
- 수취인명 `recipient_name`
- 전화번호 `phone_number`
- 우편번호 `postal_code`
- 기본 주소 `address`
- 상세 주소 `detail_address`
- 선택 배송 메모 `delivery_message`
- 생성 시각 `created_at`

`buyer_shipping_address_book_id`는 `buyer_shipping_address_books.id`를 참조하는 `NOT NULL` FK이다. 삭제 기능이 없으므로 cascade 삭제 규칙은 추가하지 않는다. `(buyer_shipping_address_book_id, address_name)`에 named unique 제약을 두어 같은 book 안의 배송지명 중복을 최종 차단하고 book별 개수 조회에도 활용한다.

필수 문자열은 각 HTTP 계약의 최대 길이에 맞는 `VARCHAR NOT NULL`로, 배송 메모는 `VARCHAR(500) NULL`로 저장한다. 양수 식별자와 문자열 길이는 application 규칙뿐 아니라 가능한 범위에서 DB 제약으로도 보호한다. 기존 migration은 수정하지 않고 후속 Flyway migration으로 두 테이블을 생성한다.

PostgreSQL의 `VARCHAR(n)`은 바이트가 아닌 문자 수를 제한하므로 `VARCHAR(255)`는 한글을 포함해 최대 255자를 저장한다. 이 동작은 [PostgreSQL 문자 타입 문서](https://www.postgresql.org/docs/15/datatype-character.html)를 기준으로 한다.

## 등록과 동시성

application은 요청 처리에서 `Clock`으로 생성 시각을 한 번 읽고 domain 입력 검증을 먼저 완료한다. 유효한 요청은 다음 순서를 하나의 DB 트랜잭션으로 처리한다.

1. `buyer_id`에 해당하는 book 행을 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`으로 생성한다.
2. `BuyerShippingAddressBookRepository.findByBuyerIdForUpdate(buyerId)`로 book 행에 비관적 쓰기 잠금을 획득한다.
3. 해당 book의 저장 배송지 개수를 조회한다.
4. 이미 10개이면 개수 초과 오류로 중단한다.
5. 정규화된 `addressName`이 같은 배송지가 있는지 조회한다.
6. 중복이면 배송지명 중복 오류로 중단한다.
7. book의 내부 ID를 참조하는 `BuyerShippingAddress`를 저장하고 응답으로 변환한다.

같은 구매자의 모든 등록 요청은 개수 조회 전에 같은 book 행을 잠그므로 직렬화된다. 9개가 등록된 상태에서 두 요청이 동시에 들어와도 먼저 잠금을 획득한 요청만 열 번째 배송지를 저장하고, 나중 요청은 잠금 획득 후 10개를 확인해 거절한다. 서로 다른 구매자는 다른 book 행을 잠그므로 서로를 막지 않는다.

book upsert와 잠금 방식은 기존 `SaleDay`의 `createIfAbsent`와 `findBySaleDateForUpdate` 패턴을 따른다. 목록의 별도 개수 카운터는 두지 않으며, 최대 10개인 현재 범위에서는 잠금 후 집계가 단순하고 충분하다.

개수 초과와 배송지명 중복 조건이 동시에 성립하면 개수 초과 오류를 우선한다. named unique 제약은 정상 application 흐름 밖의 동시성 또는 데이터 무결성 문제에 대한 최종 방어선이다. 해당 배송지명 unique 제약 위반만 전용 persistence 충돌로 식별해 배송지명 중복 오류로 변환하고, 다른 DB 오류는 예상하지 못한 내부 오류로 처리한다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`, `ErrorCategory`와 공통 handler를 재사용한다.

| 상황 | HTTP 상태 | 오류 코드 | 속성 |
| --- | --- | --- | --- |
| `X-Buyer-Id` 누락 | 400 | `COMMON_REQUEST_HEADER_MISSING` | 없음 |
| 구매자 ID 형식 오류 또는 0 이하 | 400 | `COMMON_REQUEST_HEADER_INVALID` | 없음 |
| JSON 형식 오류 | 400 | `COMMON_REQUEST_BODY_MALFORMED` | 없음 |
| 배송지명 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_NAME_INVALID` | `addressName` |
| 수취인명 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_RECIPIENT_NAME_INVALID` | `recipientName` |
| 전화번호 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_PHONE_NUMBER_INVALID` | `phoneNumber` |
| 우편번호 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_POSTAL_CODE_INVALID` | `postalCode` |
| 기본 주소 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_ADDRESS_INVALID` | `address` |
| 상세 주소 누락·공백 또는 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_DETAIL_ADDRESS_INVALID` | `detailAddress` |
| 배송 메모 최대 길이 초과 | 400 | `BUYER_SHIPPING_ADDRESS_DELIVERY_MESSAGE_INVALID` | `deliveryMessage` |
| 배송지가 이미 10개임 | 409 | `BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED` | 없음 |
| 같은 구매자의 배송지명 중복 | 409 | `BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED` | `addressName` |
| 예상하지 못한 DB·내부 오류 | 500 | `COMMON_INTERNAL_SERVER_ERROR` | 없음 |

오류 응답에는 배송 정보, 원본 persistence 예외, SQL과 내부 잠금 정보를 포함하지 않는다.

## 검증 전략

### 도메인

- 배송지명과 기존 배송 필드의 필수값·길이 검증을 확인한다.
- 모든 문자열의 앞뒤 공백 제거와 빈 배송 메모의 `null` 변환을 확인한다.
- 경계 길이는 허용하고 최대 길이를 초과하면 해당 필드 오류가 발생하는지 확인한다.
- 생성 시각과 book 참조가 올바르게 보존되는지 확인한다.

### 애플리케이션

- 최초 등록 시 book을 생성한 뒤 배송지를 저장하는지 확인한다.
- 배송지가 10개 미만이면 등록하고 이미 10개이면 개수 초과 오류를 반환하는지 확인한다.
- 같은 book의 정규화된 배송지명이 중복되면 거절하는지 확인한다.
- 서로 다른 구매자에게 같은 배송지명을 등록할 수 있는지 확인한다.
- 개수 초과와 배송지명 중복이 동시에 성립하면 개수 초과 오류를 우선하는지 확인한다.
- 저장된 값을 정규화된 성공 응답으로 변환하는지 확인한다.

### PostgreSQL 영속성과 동시성

- migration의 PK, FK, `NOT NULL`, 길이, 양수 check와 두 unique 제약을 검증한다.
- book의 `createIfAbsent`가 같은 구매자에게 한 행만 만들고 비관적 쓰기 잠금이 동작하는지 확인한다.
- 같은 book의 배송지명 중복은 거절하고 서로 다른 book의 같은 배송지명은 허용하는지 확인한다.
- 배송지 9개를 가진 같은 구매자의 동시 등록 두 건 중 정확히 한 건만 성공하는지 확인한다.
- 서로 다른 구매자의 등록 요청이 독립적으로 처리되는지 확인한다.
- 배송지명 unique 이외의 무결성 오류가 중복 오류로 잘못 변환되지 않는지 확인한다.

### HTTP·문서

- MockMvc로 필수 헤더, 구매자 ID 형식, 모든 요청 필드와 성공 상태·응답 필드를 검증한다.
- `buyerId`와 내부 book ID가 성공 응답에 노출되지 않는지 확인한다.
- 개수 초과와 배송지명 중복의 대표 ProblemDetail 응답을 검증한다.
- HTTP부터 실제 PostgreSQL 저장까지 통합 검증한다.
- Spring REST Docs로 요청 헤더, 요청·응답 필드와 대표 오류를 문서화하고 `src/docs/asciidoc/index.adoc`에 포함한다.
- 관련 테스트와 Gradle `build`를 실행한다.

테스트 클래스와 메서드는 기존 한글 네이밍과 `@DisplayName` 규칙을 따른다. 새 dependency는 추가하지 않는다.

## 주요 결정

저장 배송지는 주문 스냅샷과 분리된 독립 도메인으로 관리한다. 사용자별 book 행은 외부 구매자 식별자인 `buyer_id`를 unique 값으로 가지며, 배송지 행은 외부 식별자를 반복 저장하지 않고 내부 `buyer_shipping_address_book_id` FK로 book을 참조한다.

사용자별 최대 10개 규칙은 application 조회만으로 처리하지 않고 book 행의 비관적 쓰기 잠금으로 동시 요청을 직렬화한다. 이 도메인 경계와 정합성 방식은 ADR-017로 기록한다.
