# 구매자 체크아웃 조회 API 설계

## 목적과 완료 조건

구매자가 주문을 생성하기 직전에 선택한 판매 상품의 정보와 수량별 예상 금액, 자신의 저장 배송지 목록을 한 번의 조회로 확인할 수 있게 한다. 체크아웃은 표시용 조회이며 주문 생성, 재고 예약 또는 결제를 수행하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- `saleId`, 구매 수량과 임시 구매자 식별자로 체크아웃 정보를 조회하고 `200 OK`로 반환한다.
- 상품명, 대표 이미지 경로와 단가는 서버에 저장된 상품·판매 정보에서 가져오고 예상 총액은 서버가 계산한다.
- 요청한 구매자의 저장 배송지 최대 10건을 반환하며, 저장 배송지가 없으면 빈 목록을 반환한다.
- 판매 상품과 저장 배송지를 단일 읽기 쿼리로 조회하되 다른 구매자의 배송지를 포함하지 않는다.
- 조회 중 주문, 예약, 배송지와 판매 데이터를 생성하거나 변경하지 않는다.
- HTTP 계약, PostgreSQL 조회와 Spring REST Docs 문서 생성을 검증한다.

## 기존 구조와 범위

[백엔드 아키텍처](../../../architecture/backend.md), [구매자 주문 생성 API](buyer-order-creation-api.md), [구매자 배송지 등록 API](../shipping-address/buyer-shipping-address-registration-api.md), [구매자 판매 상품 상세 조회 API](../sale/buyer-sale-product-detail-api.md), [주문 행 기반 재고 예약 결정](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md), [구매자 배송지 목록 결정](../../../architecture/decisions/ADR-017-buyer-shipping-address-book-domain-and-locking.md)을 따른다.

`POST /api/orders`는 주문을 생성할 때 판매 기간과 유효한 예약 수량을 확인하고, 상품명·가격·배송지의 스냅샷을 저장한다. 체크아웃 조회는 그 이전의 정보 확인 단계다. 저장 배송지는 `buyer_shipping_addresses`에 있고, 해당 배송지의 소유 구매자는 `buyer_shipping_address_books.buyer_id`를 통해 식별한다. 배송지 행의 `id`만으로 소유 구매자를 확인할 수 없다.

포함 범위는 단일 판매 상품의 체크아웃 조회, 요청 수량에 따른 예상 총액, 대표 이미지 한 장과 저장 배송지 목록, 입력·오류 계약, 조회 전용 영속성 테스트와 API 문서다. 주문 생성 계약, DB 스키마와 기존 상품·판매·배송지 API의 계약은 변경하지 않는다.

재고 부족 여부와 판매 기간에 따른 구매 가능 여부 판단, 남은 수량 표시, 주문·결제 식별자 발급, 3분 예약, 배송지 등록·수정·삭제, 프런트엔드 화면과 실제 PG 연동은 제외한다. 체크아웃 결과가 주문 생성의 성공을 보장하지 않는다.

## HTTP 계약

### 요청

```http
GET /api/checkout?saleId=100&quantity=2
X-Buyer-Id: 123
```

| 항목 | 계약 |
| --- | --- |
| `X-Buyer-Id` | 필수 헤더, 양의 `Long`; 기존 구매자 API의 임시 식별 방식 사용 |
| `saleId` | 필수 쿼리 매개변수, 양의 `Long` |
| `quantity` | 필수 쿼리 매개변수, 양의 `Int` |

`quantity`는 체크아웃에 표시할 예상 금액을 계산하는 입력이다. 이 조회에서는 판매 일정의 최초 수량이나 현재 예약 수량과 비교하지 않는다. 요청 본문, `Idempotency-Key`, 배송지 ID는 받지 않는다.

### 성공 응답

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "saleId": 100,
  "productName": "한정판 상품",
  "representativeImagePath": "/products/42/550e8400-e29b-41d4-a716-446655440000/image-a",
  "quantity": 2,
  "unitPrice": 35000,
  "totalPrice": 70000,
  "shippingAddresses": [
    {
      "shippingAddressId": 7,
      "addressName": "집",
      "recipientName": "홍길동",
      "phoneNumber": "010-1234-5678",
      "postalCode": "06236",
      "address": "서울특별시 강남구 테헤란로 123",
      "detailAddress": "101동 1001호",
      "deliveryMessage": "문 앞에 놓아주세요"
    }
  ]
}
```

| 필드 | 계약 |
| --- | --- |
| `saleId` | 선택한 판매 일정 식별자 |
| `productName` | 현재 `Product.name` |
| `representativeImagePath` | 대표 이미지 객체 키 앞에 `/`를 붙인 상대 경로 |
| `quantity` | 요청한 수량 |
| `unitPrice` | 현재 `Sale.price`, 원화 |
| `totalPrice` | `unitPrice × quantity`, 원화 |
| `shippingAddresses` | 요청 구매자의 저장 배송지 목록, 없으면 `[]` |
| `shippingAddresses[].shippingAddressId` | 저장 배송지 선택용 식별자 |
| `shippingAddresses[].addressName` | 구매자가 정한 배송지명 |
| `shippingAddresses[].recipientName` | 수취인명 |
| `shippingAddresses[].phoneNumber` | 전화번호 |
| `shippingAddresses[].postalCode` | 우편번호 |
| `shippingAddresses[].address` | 기본 주소 |
| `shippingAddresses[].detailAddress` | 상세 주소 |
| `shippingAddresses[].deliveryMessage` | 배송 메모, 없으면 `null` |

배송지는 `shippingAddressId` 오름차순으로 반환한다. 기본 배송지 지정 규칙은 없으며 서버가 임의로 하나를 선택하지 않는다. 저장 배송지가 없더라도 상품과 금액을 반환하고 사용자가 배송 정보를 직접 입력할 수 있게 한다. 응답에는 `orderId`, `paymentOrderId`, 주문 상태, 예약 만료 시각, `buyerId`, 내부 book ID와 객체 키 원문을 포함하지 않는다.

사용자가 저장 배송지를 선택하면 클라이언트는 그 배송지의 수취인명·연락처·주소·배송 메모 값을 기존 `POST /api/orders`의 `shippingAddress` 입력으로 전달한다. `shippingAddressId`를 주문에 참조로 저장하지 않으며, 주문 생성 시 서버는 기존 계약대로 전달된 배송 정보를 스냅샷으로 저장한다. 사용자가 값을 수정해 주문하는 것도 기존 주문 생성 계약을 따른다.

## 조회 모델과 데이터 흐름

체크아웃 유스케이스는 `order` 영역의 조회 전용 application 서비스가 조율한다. `presentation`은 헤더와 쿼리 매개변수를 파싱하고, application은 입력 검증·예상 총액 계산·응답 조립을 담당한다. `order.domain.repository`에 체크아웃 조회 계약과 영속성 기술에 독립적인 결과 타입을 두고, `order.infrastructure.persistence`가 PostgreSQL 단일 조회를 구현한다. 조회 결과 타입이나 JPA Entity를 HTTP 응답으로 직접 반환하지 않는다.

단일 쿼리는 다음 다섯 테이블을 읽는다.

1. `sales`: 판매 일정, 현재 단가와 상품 ID
2. `products`: 현재 상품명
3. `product_images`: 해당 상품의 대표 이미지 한 장
4. `buyer_shipping_address_books`: 요청 `buyerId`의 book ID
5. `buyer_shipping_addresses`: 그 book에 속한 저장 배송지

`sales`와 `products`는 기존 FK로 연결한다. `product_images`는 상품 ID와 `is_representative = true`로 한 장만 선택한다. 배송지 조회는 `buyer_shipping_address_books.buyer_id = :buyerId`를 조건으로 book을 찾은 뒤 그 book의 배송지에만 연결한다. book이나 배송지가 없는 경우에도 상품 행이 남도록 배송지 측은 `LEFT JOIN`한다. 대표 이미지는 `LEFT JOIN`으로 가져와 판매 일정과 상품은 존재하지만 이미지가 없는 불변식 위반을 판매 일정 미존재와 구분한다.

대표 이미지는 상품당 최대 한 장이고 구매자별 배송지는 최대 10건이므로 정상 데이터에서 쿼리 결과는 판매 일정당 최대 10행이다. 저장 배송지가 없으면 배송지 열이 `null`인 상품 행 하나를 반환한다. application은 반복된 상품 필드의 첫 행과 배송지별 필드로 `CheckoutResponse` 하나를 만든다. 판매 일정 자체가 없을 때만 빈 쿼리 결과를 `404`로 변환한다. 상품 또는 대표 이미지의 기존 불변식이 깨진 경우 임의의 값을 보정하지 않고 내부 데이터 오류로 처리한다.

`orders`는 조회하지 않으며 예약 수량 집계, `sales` 행 잠금과 DB 쓰기도 수행하지 않는다. 조회는 읽기 전용 트랜잭션을 사용한다. 기존 저장 구조에서 구매자와 배송지의 연결을 확인하려면 book 조회가 필요하므로 `buyer_shipping_address_books`를 생략하지 않는다. 신규 테이블, migration과 dependency는 필요하지 않다.

## 계산과 주문 생성 경계

application은 `Sale.price`와 요청 수량으로 예상 총액을 계산하고 `Long` 곱셈 범위를 넘으면 거절한다. 체크아웃 조회 시점의 상품명과 단가를 보여주지만 주문 스냅샷은 생성하지 않는다. 판매 기간, 예약된 수량과 남은 재고는 조회하지도 판정하지도 않는다.

최종 구매 시 기존 `POST /api/orders`가 `Sale` 잠금 아래 판매 기간·예약 수량·가격과 요청 값을 다시 검증한다. 따라서 체크아웃을 성공적으로 조회한 뒤에도 주문 생성은 판매 종료나 재고 부족으로 실패할 수 있고, 체크아웃 응답의 예상 총액이 주문 생성 응답과 다르면 주문 생성 응답의 서버 계산 값을 기준으로 결제를 진행해야 한다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`과 공통 오류 handler를 사용한다.

| 상황 | HTTP 상태 | 오류 코드 | 속성 |
| --- | --- | --- | --- |
| `X-Buyer-Id` 누락 | 400 | `COMMON_REQUEST_HEADER_MISSING` | 없음 |
| `X-Buyer-Id` 형식 오류 또는 0 이하 | 400 | `COMMON_REQUEST_HEADER_INVALID` | 없음 |
| 쿼리 매개변수 누락 또는 숫자 형식 오류 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` | 없음 |
| `saleId`가 0 이하 | 400 | `ORDER_SALE_ID_INVALID` | `saleId` |
| `quantity`가 0 이하 | 400 | `ORDER_QUANTITY_INVALID` | `quantity` |
| 판매 일정이 존재하지 않음 | 404 | `ORDER_SALE_NOT_FOUND` | `saleId` |
| 예상 총액이 `Long` 범위를 초과함 | 409 | `ORDER_TOTAL_PRICE_INVALID` | 없음 |
| 예상하지 못한 DB·내부 데이터 오류 | 500 | `COMMON_INTERNAL_SERVER_ERROR` | 없음 |

판매 시작 전·종료 후 또는 재고 부족은 체크아웃 조회의 오류가 아니다. 오류 응답에는 다른 구매자의 배송 정보, 객체 키 원문, SQL과 내부 예외를 포함하지 않는다.

## 검증 전략과 결정 기록

- application 테스트에서 양수 식별자·수량, 예상 총액과 곱셈 범위 초과, 빈 배송지 목록 및 응답 조립을 확인한다.
- PostgreSQL 테스트에서 다섯 테이블 조회가 한 SQL로 실행되는지, 대표 이미지만 반환하는지, 배송지가 0건·1건·10건일 때 상품 행과 목록이 올바르게 조립되는지 확인한다.
- 서로 다른 구매자의 배송지가 섞이지 않고, 같은 book의 배송지만 ID 오름차순으로 반환하는지 확인한다.
- 체크아웃 조회 전후에 주문 행이나 예약 상태가 생성·변경되지 않는지 확인한다.
- MockMvc와 Spring REST Docs로 헤더·쿼리 매개변수·성공 필드·대표 오류를 검증하고 문서화한다. 관련 테스트와 Gradle `build`를 실행한다.

단일 조회가 `order` 조회 모델에서 판매·상품·저장 배송지의 읽기 데이터를 조합하는 의존성 방향은 새로운 아키텍처 결정이다. 구현 전에 [ADR 작성 규칙](../../../architecture/decisions/README.md)에 따라 이를 기록한다. `Order` Entity가 저장 배송지 Entity를 참조하지 않는 기존 ADR-017의 경계와 주문 생성의 판매 일정 잠금 규칙은 유지한다.
