# 구매자 주문 생성 API 설계

## 목적과 완료 조건

구매자가 판매 중인 단일 상품의 결제를 시작하기 직전에 주문 정보와 배송지 스냅샷을 저장하고, 결제 대기 시간 동안 요청 수량을 예약하는 API를 제공한다. 주문 생성 시 토스페이먼츠 결제 요청의 `orderId`로 사용할 `paymentOrderId`도 서버에서 생성해 주문과 함께 저장하고 응답한다. 별도 재고·예약 테이블 없이 주문 행을 예약 기록으로 사용하고, 같은 판매 일정에 대한 동시 주문을 직렬화해 초과 판매를 막는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 판매 중인 `Sale` 하나와 양의 구매 수량으로 주문을 생성하고 `201 Created`로 반환한다.
- 상품명과 단가는 서버의 `Product.name`, `Sale.price`에서 가져오고 총액은 서버가 계산한다.
- 주문 생성 시점의 상품명, 가격과 배송 정보를 주문에 스냅샷으로 보존한다.
- 생성된 주문은 `PENDING_PAYMENT` 상태로 3분 동안 판매 수량을 예약한다.
- 같은 `sale` 행을 잠근 상태에서 유효한 예약 수량을 집계해 동시 요청에서도 초과 판매를 막는다.
- 동일 구매자의 같은 멱등성 키와 동일 요청에는 기존 주문을 반환하고, 요청 내용이 다르면 충돌로 거절한다.
- 토스페이먼츠 규격을 만족하는 전역 고유 `paymentOrderId`를 서버에서 생성해 주문에 저장하고 성공 응답으로 반환한다.
- 동일한 멱등 요청에는 최초 주문에 저장된 `paymentOrderId`를 그대로 반환한다.
- PostgreSQL 영속성, 동시성, HTTP 계약과 Spring REST Docs 문서 생성을 검증한다.

## 기존 구조와 결정

[백엔드 아키텍처](../../../architecture/backend.md), [상품 원본과 판매 일정의 도메인 경계 결정](../../../architecture/decisions/ADR-009-product-and-sale-domain-boundaries.md), [주문 행 기반 재고 예약과 판매 일정 잠금 결정](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md)을 따른다.

기존 `Product`는 상품명과 상품 원본을, `Sale`은 상품 ID, 판매일, 판매 가격과 최초 판매 수량을 관리한다. `Sale`의 판매 기간은 `Asia/Seoul` 기준 판매일 00:00 이상 다음 날 00:00 미만이다. 현재 상품과 판매 조건을 수정하는 API는 없지만, 결제 금액과 주문 이력이 이후 원본 데이터의 변화에 영향받지 않도록 주문 생성 시 필요한 값을 복사한다.

새 `order` 도메인은 구매 의사, 결제 대기 상태, 예약 만료와 배송 정보 스냅샷을 소유한다. `Order`는 `Sale` Entity와 JPA 연관관계를 맺지 않고 `saleId`로 참조한다. 주문 application은 기존 `sale`, `product` 도메인의 Repository를 호출해 판매 가능 여부와 서버 원본 값을 확인한다.

## 범위

포함 범위는 `POST /api/orders`, 단일 판매 상품 주문, 배송 정보 스냅샷, 가격 계산, 3분 재고 예약, 판매 일정 잠금, 멱등성 처리, 결제 요청용 주문 식별자 생성·저장·응답, 오류 응답, PostgreSQL 테스트와 API 문서이다.

다음은 제외한다.

- 주문 조회·수정·취소 API
- 토스페이먼츠 SDK 호출과 결제 승인·실패 처리 등 실제 PG 연동
- 만료된 주문의 상태를 갱신하는 Scheduler 또는 배치
- 복수 상품 주문, `order_items`와 장바구니
- 별도 재고·예약 테이블
- 회원 주소록 API와 주소 식별자 참조
- 실제 인증·인가 체계
- 배송 접수·추적·완료 처리
- 개인정보 필드 암호화와 별도 보안 인프라

## HTTP 계약

### 요청

```http
POST /api/orders
Content-Type: application/json
X-Buyer-Id: 123
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "saleId": 100,
  "quantity": 2,
  "shippingAddress": {
    "recipientName": "홍길동",
    "phoneNumber": "010-1234-5678",
    "postalCode": "06236",
    "address": "서울특별시 강남구 테헤란로 123",
    "detailAddress": "101동 1001호",
    "deliveryMessage": "문 앞에 놓아주세요"
  }
}
```

| 항목 | 계약 |
| --- | --- |
| `X-Buyer-Id` | 필수 헤더, 양의 `Long` |
| `Idempotency-Key` | 필수 헤더, UUID |
| `saleId` | 필수, 양의 `Long` |
| `quantity` | 필수, 양의 `Int`, 생성 시점의 남은 수량 이하 |
| `recipientName` | 필수, 공백 제거 후 1~100자 |
| `phoneNumber` | 필수, 공백 제거 후 1~30자 |
| `postalCode` | 필수, 공백 제거 후 1~20자 |
| `address` | 필수, 공백 제거 후 1~255자 |
| `detailAddress` | 필수, 공백 제거 후 1~255자 |
| `deliveryMessage` | 선택, 공백 제거 후 빈 값은 `null`, 최대 500자 |

전화번호와 우편번호는 국가별 표현을 제한하는 형식 검증을 적용하지 않고 필수 여부와 길이만 검증한다. 실제 인증이 도입되기 전까지 기존 판매자 API와 같은 임시 식별 방식으로 `X-Buyer-Id`를 사용한다.

클라이언트는 상품명, 단가, 총액, 상태와 만료 시각을 전송하지 않는다. 서버가 잠근 `Sale`과 연결된 `Product`를 조회해 신뢰할 수 있는 값을 만든다.

### 성공 응답

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "orderId": 1000,
  "paymentOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING_PAYMENT",
  "productName": "한정판 상품",
  "quantity": 2,
  "unitPrice": 35000,
  "totalPrice": 70000,
  "expiresAt": "2026-09-11T06:03:00Z"
}
```

`paymentOrderId`는 토스페이먼츠 결제 요청의 `orderId`로 전달할 서버 생성 식별자이다. canonical UUID v4 문자열을 사용해 토스페이먼츠의 6~64자 및 영문 대소문자·숫자·`-`, `_`, `=` 허용 규격을 만족한다. 내부 DB 식별자인 숫자형 `orderId`, 구매자가 제공하는 `Idempotency-Key`와는 서로 다른 목적과 수명주기를 가지므로 재사용하지 않는다.

`totalPrice`는 `Sale.price × quantity`이며 `Long` 범위를 넘지 않는지 검사한다. `expiresAt`은 주문 생성에 사용한 UTC `Instant`에서 3분을 더한 값이다. 배송 정보는 저장하지만 결제 시작에 필요한 성공 응답에는 반환하지 않는다.

동일 구매자가 같은 `Idempotency-Key`와 동일한 정규화 요청을 다시 보내면 새 주문이나 새 `paymentOrderId`를 만들지 않고, 만료 시간도 연장하지 않은 채 최초 주문과 같은 응답을 `201 Created`로 반환한다. 최초 주문이 이미 만료됐어도 기존 주문의 `paymentOrderId`와 과거 `expiresAt`을 반환하며, 새 예약에는 새 멱등성 키가 필요하다.

## 주문 모델과 영속성

`orders` 테이블과 이에 대응하는 `Order` Entity는 다음 값을 저장한다.

- 내부 생성 식별자 `id`
- 토스페이먼츠 결제 요청용 식별자 `payment_order_id`
- 참조 식별자 `sale_id`
- 임시 구매자 식별자 `buyer_id`
- UUID `idempotency_key`
- 주문 수량 `quantity`
- 상품명 스냅샷 `product_name`
- 주문 당시 단가 `unit_price`
- 계산된 총액 `total_price`
- 주문 상태 `status`
- 배송 정보 `recipient_name`, `phone_number`, `postal_code`, `address`, `detail_address`, `delivery_message`
- 생성 시각 `created_at`
- 예약 만료 시각 `expires_at`

`sale_id`는 `sales.id`를 참조하는 FK로 둔다. `product_id`와 `seller_id`는 `sale_id`로 식별할 수 있고 현재 주문 규칙에 독립적으로 필요하지 않으므로 중복 저장하지 않는다. 주문에 복사한 `product_name`, `unit_price`, `total_price`와 배송 정보는 원본 데이터가 이후 변경되더라도 수정하지 않는다.

`payment_order_id`는 `VARCHAR(64) NOT NULL`로 저장하고 전역 unique 제약을 둔다. 6~64자 길이와 영문 대소문자·숫자·`-`, `_`, `=`만 허용하는 check 제약으로 토스페이먼츠 형식 규칙을 보호한다. 신규 주문은 canonical UUID v4 문자열을 사용한다. `Order.create`가 주문마다 한 번 생성하며 application이나 presentation이 값을 입력하지 않는다.

이미 적용된 주문 테이블 migration은 수정하지 않고 후속 migration에서 `payment_order_id`를 추가한다. 기존 주문에는 `legacy_<id>` 형식의 전역 고유 값을 채운 뒤 `NOT NULL`, 형식 check와 unique 제약을 적용한다. 이 값은 토스페이먼츠 허용 형식을 만족하며 기존 주문도 동일한 영속성 불변식을 갖게 한다.

UUID 충돌은 현실적으로 무시할 수 있을 만큼 희박하므로 별도 재시도 흐름을 추가하지 않는다. DB unique 제약을 최종 방어선으로 두며, 충돌이 발생하면 멱등성 충돌로 잘못 변환하지 않고 예상하지 못한 내부 오류로 처리한다.

`(buyer_id, idempotency_key)` unique 제약으로 멱등성을 최종 보장한다. 유효한 예약 수량 집계를 위해 `status = 'PENDING_PAYMENT'`인 행의 `(sale_id, expires_at)` 부분 인덱스를 둔다. 양수 식별자·수량·가격과 문자열 길이는 애플리케이션 규칙뿐 아니라 가능한 범위에서 DB 제약으로도 보호한다.

`ShippingAddress`는 주문에 속하는 값 객체이며 별도 테이블이나 독립 생명주기를 갖지 않는다. JPA embedded 값으로 매핑할 수 있지만 외부 주소 Entity와 연관관계를 만들지 않는다.

이번 범위의 `OrderStatus`는 `PENDING_PAYMENT`만 정의한다. 향후 결제 기능이 `PAID`, `CANCELED`, `EXPIRED` 등의 상태와 전이 규칙을 별도 설계한다. 현재는 `expiresAt`을 기준으로 예약 유효성만 판단하며 시간이 지났다는 이유로 주문 행을 수정하지 않는다.

## 재고 예약과 동시성

판매 가능한 최초 수량의 원천은 `Sale.quantity`이다. 주문 생성 시 같은 `sale`에 대해 다음 순서를 하나의 DB 트랜잭션으로 수행한다.

1. `SaleRepository.findByIdForUpdate(saleId)`로 `sales` 행에 비관적 쓰기 잠금을 획득한다.
2. 잠금을 기다리는 동안 같은 멱등성 키의 주문이 커밋됐을 수 있으므로 기존 주문을 다시 조회한다. 동일 요청이면 기존 주문을 반환하고 다른 요청이면 충돌로 거절한다.
3. 요청 처리에서 한 번 읽은 `now`가 `Sale.startsAt <= now < Sale.endsAt`인지 확인한다.
4. 주문 Repository에서 `sale_id`가 같고 `status = PENDING_PAYMENT`이며 `expires_at > now`인 수량의 합을 조회한다.
5. `Sale.quantity - reservedQuantity`가 요청 수량 이상인지 확인한다.
6. `ProductRepository.findById(sale.productId)`로 상품명을 조회한다.
7. canonical UUID v4 `paymentOrderId`와 서버 단가, 상품명, 배송 정보로 주문을 생성하고 저장한다.

모든 주문 생성이 예약 수량 조회 전에 같은 `sales` 행을 잠그므로, 같은 판매 일정의 요청은 직렬화된다. 별도 예약 수량 카운터를 갱신하지 않아 만료 시 되돌릴 상태가 없으며, `expires_at <= now`인 주문은 즉시 집계에서 제외된다. Scheduler 지연이나 서버 재시작이 예약 해제의 정확성에 영향을 주지 않는다.

잠금은 판매 일정 하나에만 적용하며 외부 API 호출을 트랜잭션에 포함하지 않는다. 서로 다른 판매 일정의 주문은 서로를 막지 않는다. 향후 결제가 추가되면 결제 확정과 만료 경합도 같은 판매 일정 잠금과 주문 상태 전이 규칙 아래에서 설계한다.

## 멱등성 처리

동일 요청 여부는 정규화한 다음 값을 모두 비교한다.

- `saleId`
- `quantity`
- `recipientName`
- `phoneNumber`
- `postalCode`
- `address`
- `detailAddress`
- `deliveryMessage`

처리 순서는 다음과 같다.

1. 주문 application이 요청을 정규화하고 도메인 입력 규칙을 검증한다.
2. `(buyerId, idempotencyKey)`로 기존 주문을 조회한다.
3. 기존 주문이 있으면 요청 값과 비교해 동일하면 저장된 `paymentOrderId`를 포함한 기존 응답을 반환하고 다르면 멱등성 충돌을 반환한다.
4. 기존 주문이 없으면 트랜잭션 서비스가 `sale` 행을 잠근다.
5. 같은 `sale` 잠금을 기다린 동일 키 요청을 처리하기 위해 기존 주문을 다시 조회하고, 있으면 동일 요청 반환 또는 충돌로 처리한다.
6. 기존 주문이 없으면 판매 시간과 재고를 확인하고 `paymentOrderId`를 생성해 주문과 함께 저장한다.
7. 서로 다른 `sale`을 대상으로 같은 키를 동시에 사용해 unique 제약에서 충돌하면 infrastructure가 제약 이름을 확인해 전용 persistence 예외로 변환한다.
8. 바깥 application 서비스가 새 트랜잭션에서 기존 주문을 다시 조회하고 동일 요청 반환 또는 충돌로 변환한다.

저장 시 `saveAndFlush`를 사용해 unique 위반이 트랜잭션 경계 안에서 확인되게 한다. 이미 실패 표시된 트랜잭션 안에서 기존 주문을 다시 조회하지 않는다. 이는 현재 판매 일정 등록에서 동시 unique 충돌을 변환하는 구조와 같은 원칙이다.

## 계층과 데이터 흐름

- `presentation`: `OrderController`가 `X-Buyer-Id`, `Idempotency-Key`와 `CreateOrderRequest`를 받고 application DTO로 변환한다. 비즈니스 로직과 JPA 타입을 포함하지 않는다.
- `application`: `OrderService`가 선행 멱등성 조회와 persistence 충돌 처리를 담당하고, 주문 생성 트랜잭션 서비스가 판매 잠금, 시간·재고 검증, 상품 조회와 저장을 조율한다. 응답은 저장된 `paymentOrderId`를 포함한 `OrderResponse`로 변환한다.
- `domain`: `Order`, `ShippingAddress`, `OrderStatus`, `OrderRepository`가 주문 상태와 생성 규칙, `paymentOrderId` 생성, 입력 정규화, 금액 계산 및 영속성 계약을 관리한다.
- `infrastructure`: Spring Data JPA 기반 주문 저장·조회·예약 수량 집계와 unique 제약 변환을 구현한다. 기존 `SaleRepository`의 비관적 잠금 조회 구현도 infrastructure에 둔다.

의존 방향은 `order.presentation → order.application → order.domain`을 따른다. 주문 application은 유스케이스 조율을 위해 `sale.domain`, `product.domain`의 Repository와 Entity를 사용할 수 있지만, `Order` Entity가 다른 도메인의 Entity나 Repository에 의존하지 않는다. `sale`과 `product`는 `order`를 의존하지 않는다.

## 오류 계약

기존 `application/problem+json`, `ProblemDetail`, `ErrorCategory`와 공통 handler를 재사용한다.

| 상황 | HTTP 상태 | 오류 코드 | 속성 |
| --- | --- | --- | --- |
| `X-Buyer-Id` 또는 `Idempotency-Key` 누락 | 400 | `COMMON_REQUEST_HEADER_MISSING` | 없음 |
| 구매자 ID 또는 멱등성 키 형식 오류 | 400 | `COMMON_REQUEST_HEADER_INVALID` | 없음 |
| JSON 형식 오류 또는 필수 객체 누락 | 400 | `COMMON_REQUEST_BODY_MALFORMED` | 없음 |
| `saleId`가 양수가 아님 | 400 | `ORDER_SALE_ID_INVALID` | `saleId` |
| 수량이 없거나 양수가 아님 | 400 | `ORDER_QUANTITY_INVALID` | `quantity` |
| 배송 필수값 누락·공백 또는 최대 길이 초과 | 400 | 해당 `ORDER_*` 오류 | 해당 배송 필드 |
| 판매 일정이 존재하지 않음 | 404 | `ORDER_SALE_NOT_FOUND` | `saleId` |
| 판매 시작 전 또는 종료 후 | 409 | `ORDER_SALE_NOT_OPEN` | `saleId` |
| 요청 수량이 남은 수량보다 큼 | 409 | `ORDER_QUANTITY_UNAVAILABLE` | `quantity` |
| 같은 멱등성 키에 다른 요청 사용 | 409 | `ORDER_IDEMPOTENCY_CONFLICT` | 없음 |
| 계산된 총액이 `Long` 범위를 초과함 | 409 | `ORDER_TOTAL_PRICE_INVALID` | 없음 |
| 예상하지 못한 DB·내부 오류 | 500 | `COMMON_INTERNAL_SERVER_ERROR` | 없음 |

`paymentOrderId`의 극히 드문 unique 충돌은 `ORDER_IDEMPOTENCY_CONFLICT`로 변환하지 않고 예상하지 못한 DB·내부 오류로 처리한다. 오류 응답에는 배송 정보, `paymentOrderId`, 원본 persistence 예외, SQL과 내부 잠금 정보를 포함하지 않는다. 상품이 FK 불변식과 다르게 존재하지 않는 경우도 예상하지 못한 내부 데이터 오류로 처리한다.

## 호환성과 확장 방향

- 기존 상품·판매 API의 요청과 응답 계약을 변경하지 않는다.
- 주문 생성 요청 계약과 기존 성공 응답 필드는 유지하고 `paymentOrderId`만 추가한다.
- 클라이언트는 `paymentOrderId`를 생성하거나 요청으로 보내지 않으며, 응답값을 토스페이먼츠 결제 요청의 `orderId`로 전달한다.
- `Sale.quantity`는 계속 최초 판매 수량을 의미하며 남은 수량으로 재해석하지 않는다.
- `SaleRepository`에는 주문 흐름에 필요한 ID 기반 비관적 잠금 조회만 추가한다.
- 새 dependency를 추가하지 않는다.
- 주문 만료 기간 3분은 의미가 드러나는 주문 설정값으로 관리하며 코드 곳곳에 숫자 리터럴을 반복하지 않는다.
- 실제 인증 도입 시 `X-Buyer-Id` 파싱을 인증 주체에서 구매자 ID를 얻는 방식으로 교체하되 주문 모델의 `buyerId`는 유지한다.
- 주소록 도입 시 클라이언트가 선택한 주소를 주문 요청 값으로 펼쳐 전송하고, 주문은 계속 배송 스냅샷을 저장한다.
- 복수 상품 주문은 별도 설계에서 주문 헤더와 `order_items`로 분리한다. 이번 단일 상품 구조를 미리 일반화하지 않는다.

## 검증 전략

### 도메인

- 구매자·판매 식별자, 수량과 배송 필드의 필수값·길이 검증을 확인한다.
- 문자열 앞뒤 공백 제거와 빈 배송 메모의 `null` 변환을 확인한다.
- 상품명과 단가로 총액을 계산하고 곱셈 범위 초과를 거절하는지 확인한다.
- 생성 상태가 `PENDING_PAYMENT`이고 `expiresAt`이 생성 시각에서 정확히 3분 뒤인지 확인한다.
- 생성된 `paymentOrderId`가 canonical UUID v4이며 토스페이먼츠 길이·문자 규격을 만족하는지 확인한다.
- 서로 다른 신규 주문에는 서로 다른 `paymentOrderId`가 생성되는지 확인한다.
- 저장된 주문과 정규화된 재요청의 동일성 비교를 확인한다.

### 애플리케이션

- 판매 시작 직전·정각, 종료 직전·정각의 생성 허용 경계를 주입한 `Clock`으로 확인한다.
- 요청 처리 중 읽은 같은 `Instant`가 판매 시간, 예약 집계, 생성·만료 시각에 사용되는지 확인한다.
- 존재하지 않는 판매 일정, 판매 시간 외 요청과 부족한 수량을 계약된 오류로 변환하는지 확인한다.
- 상품명과 단가를 서버 Repository 값으로 사용하고 클라이언트 입력으로 받지 않는지 확인한다.
- 같은 멱등성 키와 동일 요청은 기존 주문을 반환하고 다른 요청은 충돌하는지 확인한다.
- 같은 멱등성 키와 동일 요청에는 기존 주문의 `paymentOrderId`를 반환하고 새 값을 생성하지 않는지 확인한다.
- 최초 조회 뒤 같은 판매 일정의 잠금을 기다린 동일 키 요청도 재고 부족보다 기존 주문을 우선 반환하는지 확인한다.
- 멱등성 재요청이 만료 시각을 연장하지 않는지 확인한다.

### PostgreSQL 영속성과 동시성

- migration의 FK, `payment_order_id`의 `NOT NULL`·형식 check·unique 제약과 부분 인덱스를 검증한다.
- 기존 주문에 `legacy_<id>` 형식의 `payment_order_id`가 채워지고 신규 불변식이 적용되는지 확인한다.
- 같은 `payment_order_id` 저장을 DB unique 제약이 거절하고 이를 멱등성 persistence 충돌로 잘못 변환하지 않는지 확인한다.
- 만료되지 않은 `PENDING_PAYMENT` 주문만 예약 수량에 포함되고 `expiresAt == now`는 제외되는지 확인한다.
- 같은 판매 일정에 판매 수량을 초과하도록 동시에 요청해 성공 수량 합이 `Sale.quantity`를 넘지 않는지 확인한다.
- 서로 다른 판매 일정의 주문이 독립적으로 처리되는지 확인한다.
- 같은 멱등성 키의 동시 요청이 주문 하나만 생성하는지 확인한다.
- unique 제약 이외의 무결성 오류가 멱등성 충돌로 잘못 변환되지 않는지 확인한다.

### HTTP·문서

- MockMvc로 필수 헤더, UUID와 구매자 ID 형식, 요청 필드, 성공 상태·필드와 대표 오류를 검증한다.
- 성공 응답과 Spring REST Docs에 `paymentOrderId`가 필수 문자열 필드로 포함되는지 확인한다.
- 가격과 상품명 필드를 요청으로 받지 않고 서버 계산 결과만 응답하는지 확인한다.
- 배송 정보가 성공 응답과 오류 응답에 노출되지 않는지 확인한다.
- Spring REST Docs로 요청 헤더, 요청·응답 필드와 오류 응답을 문서화한다.
- HTTP부터 실제 PostgreSQL 저장까지 통합 검증하고 관련 테스트와 Gradle `build`를 실행한다.

테스트 클래스와 메서드는 기존 한글 네이밍과 `@DisplayName` 규칙을 따른다.

## 주요 결정

주문 행을 예약 기록으로 사용하고 판매 일정 행 잠금으로 동시 주문을 직렬화하는 결정은 ADR-015로 기록했다. 이 결정은 현재 단일 상품 주문의 정합성을 단순한 구조로 보장하며, 결제 상태 전이·취소·복수 상품이 추가될 때 해당 요구사항과 함께 다시 검토한다.

결제 요청용 식별자는 내부 주문 ID나 구매자 제공 멱등성 키를 재사용하지 않고 서버가 생성한 canonical UUID v4로 관리한다. 이는 [토스페이먼츠 주문서형 결제 JavaScript SDK](https://docs.tosspayments.com/sdk/v2/js/payment-widget)의 `orderId` 규격을 만족하면서 내부 식별자와 외부 결제 프로토콜의 역할을 분리한다. 이번 변경은 기존 주문 모델 내부의 필드와 API 응답을 확장할 뿐 도메인 경계나 외부 시스템 연동 구조를 변경하지 않으므로 별도 ADR을 작성하지 않는다.
