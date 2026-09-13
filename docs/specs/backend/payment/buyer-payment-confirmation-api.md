# 구매자 결제 승인 API 설계

## 목적과 완료 조건

구매자가 토스페이먼츠 결제 인증을 마치면 백엔드가 주문의 소유자·예약·금액을 검증하고 결제를 승인한다. 승인 결과가 불명확하거나 서버가 중단돼도 같은 결제 시도를 재확인해 주문과 재고의 정합성을 유지한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 구매자의 유효한 단일 상품 주문에 대해 토스페이먼츠 승인을 요청하고, 검증된 `DONE` 결과에만 주문을 `PAID`로 확정한다.
- 클라이언트의 `amount`는 저장된 `Order.totalPrice`와 비교하고, 토스에는 서버에 저장된 금액을 보낸다.
- 동일 결제의 API 재호출과 자동 재확인은 저장된 `Payment` 행과 토스 `Idempotency-Key`를 재사용한다.
- 승인 중이거나 결과가 불명확한 주문의 수량을 만료 시각 이후에도 예약 집계에 포함하고, `PAID` 수량은 계속 판매 완료 수량으로 집계한다.
- 확정된 승인 실패에는 예약을 해제하며, 결과가 불명확하거나 검증에 실패한 결제의 예약은 임의로 해제하지 않는다.
- PostgreSQL 영속성·동시성, 토스 HTTP 연동, 자동 재확인, API 계약과 Spring REST Docs를 검증한다.

## 기존 구조와 범위

[백엔드 아키텍처](../../../architecture/backend.md), [구매자 주문 생성 API](../order/buyer-order-creation-api.md), [주문 행 기반 재고 예약 결정](../../../architecture/decisions/ADR-015-order-row-reservation-with-sale-lock.md)을 출발점으로 한다. 현재 `Order`는 판매 일정 하나를 `saleId`로 참조하고, `PENDING_PAYMENT` 상태와 `expiresAt`으로 3분간 수량을 예약한다. `paymentOrderId`는 토스 결제 요청의 `orderId`로 이미 생성·저장된다. 기존 예약 집계는 미만료 `PENDING_PAYMENT`만 포함하므로 결제 승인 기능과 함께 변경해야 한다.

포함 범위는 동기식 `POST /api/payments/confirm`, 토스 승인·조회 Client, 주문별 결제 시도 기록, 주문 확정과 예약 집계, 불명확한 결제의 자동 재확인, 오류 응답, DB migration과 API 문서다. 새 dependency는 추가하지 않고 기존 Spring Web 기능으로 토스 HTTP Client를 구현한다. 시크릿 키와 HTTP 타임아웃은 외부 설정으로 주입하며 시크릿 키를 코드·문서·로그에 기록하지 않는다.

다음은 제외한다.

- 프론트엔드 결제 SDK·화면 변경
- 가상계좌 입금 대기와 그에 필요한 웹훅 처리
- 구매자 취소·환불 API와 취소 시도 기록
- 장바구니·복수 상품 주문·한 주문의 복수 결제 시도
- 호출별 영구 기록을 위한 `payment_operations` 테이블
- 실제 인증 체계 도입; 기존 구매자 API의 `X-Buyer-Id` 계약을 따른다.

이번 API는 승인 응답에서 바로 `DONE`이 되는 결제수단을 대상으로 한다. 즉시 완료되지 않는 결제 상태가 예상 밖으로 반환되면 주문을 `PAID`로 확정하거나 예약을 풀지 않고 수동 확인 대상으로 남긴다.

## HTTP 계약

```http
POST /api/payments/confirm
Content-Type: application/json
X-Buyer-Id: 123
```

```json
{
  "paymentKey": "토스가_발급한_결제_키",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "amount": 70000
}
```

`orderId`는 내부 숫자형 주문 ID가 아니라 주문 생성 API가 반환한 `paymentOrderId`다. `paymentKey`는 공백이 아닌 최대 200자, `orderId`는 기존 토스 주문 식별자 형식, `amount`는 양의 정수 금액이어야 한다. API는 새로운 클라이언트 `Idempotency-Key`를 요구하지 않는다. 주문 생성 요청의 `Idempotency-Key`는 주문 중복 생성에만 사용하며 결제 승인용 토스 멱등키와 구분한다.

서버는 `paymentOrderId`로 주문을 찾고 `buyerId`가 일치하는지 확인한다. 주문을 찾을 수 없거나 다른 구매자의 주문이면 주문 존재 여부를 드러내지 않는 동일한 `404`를 반환한다. 첫 승인 시도는 주문이 `PENDING_PAYMENT`이고 `now < expiresAt`일 때만 허용한다. 요청 금액이 저장된 총액과 다르면 토스를 호출하지 않는다. 이미 생성된 결제 시도가 있으면 동일한 `paymentKey`만 허용한다.

성공 및 동일 결제의 완료 후 재호출은 `200 OK`로 다음 정보를 반환한다.

```json
{
  "orderId": 1000,
  "paymentOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PAID",
  "totalAmount": 70000,
  "approvedAt": "2026-09-13T06:00:00Z"
}
```

응답에는 시크릿 키, 토스 멱등키, 배송 정보, 내부 재확인 상태를 포함하지 않는다. 승인 처리 중 같은 결제로 다시 호출하면 중복 결제를 시작하지 않고 처리 중 충돌을 반환한다. `Payment`가 확정 실패 또는 수동 확인 필요 상태라면 동일 API가 새 `paymentKey`로 재승인하지 않는다. 구매자는 확정 실패 후 새 주문과 새 결제 인증을 시작한다.

## 데이터 모델과 책임

`Order`는 구매 의사, 배송 스냅샷, 예약과 구매 확정 상태를 관리한다. `OrderStatus`에는 기존 `PENDING_PAYMENT`와 `PAID`만 둔다. 결제 실패는 `OrderStatus`에 복제하지 않는다. `Order`는 확인된 결제 성공에만 `PAID`로 전이한다. `expiresAt`은 주문 생성 시각에서 계산한 원래 값을 유지해 기존 주문 생성의 멱등 응답을 바꾸지 않는다. 결제 시도가 확정 실패하면 `Payment.FAILED`를 예약 집계에서 제외해 예약을 즉시 해제한다. 이때 주문의 저장된 상태는 `PENDING_PAYMENT`지만 새 결제 시도는 허용하지 않는다.

새 `payment` 영역의 `Payment`는 토스 결제 시도 한 건을 관리한다. 한 주문에는 결제 시도 하나만 허용하며 `payments.order_id`에 FK·unique 제약을 둔다. `payments.payment_key`와 토스 멱등키도 각각 unique다. `Payment`는 내부 ID, `order_id`, `payment_key`, 서버 생성 UUID v4 토스 멱등키, 상태, 요청 금액, 생성·최초 승인 요청·마지막 확인·다음 확인 시각과 확정된 승인 시각을 저장한다. 토스 멱등키는 첫 외부 요청 전에 DB에 저장하고 같은 `Payment`의 모든 승인 재시도에서 동일하게 사용한다. 같은 `paymentKey`에 대한 HTTP 재호출이나 자동 재확인은 새 행을 만들지 않는다. 자동 재확인 대상에는 `(status, next_reconcile_at, id)` 인덱스를 둔다.

`PaymentStatus`는 `CONFIRMING`, `APPROVED`, `FAILED`, `REVIEW_REQUIRED`로 제한한다. `CONFIRMING`은 토스 결과가 아직 확정되지 않은 상태이며, `APPROVED`는 검증된 `DONE`, `FAILED`는 토스가 승인 실패·만료를 확정한 상태다. `REVIEW_REQUIRED`는 토스 응답의 식별자·금액 불일치, 지원하지 않는 입금 대기 상태 또는 재확인만으로 결론을 낼 수 없는 상태다. `APPROVED`와 `Order.PAID`는 같은 DB 트랜잭션에서 변경한다.

기존 `orders_status_valid` DB 제약은 `PAID`를 허용하도록 후속 migration에서 갱신한다. `Payment`는 `Order`를 ID로 참조하며 주문·판매 Entity와 JPA 연관관계를 만들지 않는다. `payment.presentation`은 HTTP 타입을 처리하고, `payment.application`은 승인·재확인 흐름과 짧은 트랜잭션을 조율하며 토스 호출 계약을 인터페이스로 정의한다. `payment.domain`은 결제 시도 상태와 전이를 관리한다. `payment.infrastructure`는 JPA와 토스 Client를 구현한다. 주문 application은 결제 domain Entity를 직접 조작하지 않는다.

## 승인과 재고 예약 흐름

첫 승인 시도는 다음 순서로 처리한다.

1. 요청 형식, 주문 소유자, `paymentOrderId`, 요청 금액을 검증한다.
2. 짧은 DB 트랜잭션에서 해당 `Sale` 행을 비관적으로 잠그고 주문과 기존 결제 시도를 다시 조회한다. 만료 전 `PENDING_PAYMENT` 주문이며 기존 결제 시도가 없을 때만 `Payment(CONFIRMING)`을 저장한다. 토스 멱등키와 다음 확인 시각도 이 시점에 저장한다.
3. DB 트랜잭션 밖에서 토스 `POST /v1/payments/confirm`에 저장된 `paymentKey`, `paymentOrderId`, `Order.totalPrice`와 토스 멱등키를 전송한다. 시크릿 키는 서버의 Basic 인증 헤더에만 사용한다.
4. 토스 응답의 `paymentKey`, `orderId`, `totalAmount`, `status = DONE`을 저장된 값과 비교한다. 검증된 성공에만 새 DB 트랜잭션에서 같은 `Sale`을 잠그고 `Payment.APPROVED`, `Order.PAID`와 승인 시각을 함께 저장한다.

같은 판매 일정에 관련된 트랜잭션은 `Sale` 행을 먼저 잠그고 주문·결제 시도를 이어서 확인하는 순서를 지킨다. 토스 HTTP 호출 동안 DB 잠금을 유지하지 않는다. 승인 시도 저장 전 트랜잭션이 실패하면 토스를 호출하지 않는다. 토스 승인 뒤 확정 트랜잭션이 실패하면 `Payment.CONFIRMING`이 남아 자동 재확인 대상이 된다.

주문 생성의 판매 가능 수량 집계는 다음 주문 수량을 더한다.

- 상태가 `PAID`인 모든 주문
- 상태가 `PENDING_PAYMENT`이고 `expiresAt > now`이며 연결된 `Payment.FAILED`가 없는 주문
- 원래 만료 시각이 지났더라도 `CONFIRMING` 또는 `REVIEW_REQUIRED`인 `Payment`가 연결된 주문

한 주문이 여러 조건에 해당해도 한 번만 센다. `Payment.FAILED`는 토스의 확정 실패 응답을 확인한 짧은 트랜잭션에서 저장한다. 집계는 `orders`와 `payments`를 읽지만 결제 domain Entity를 주문 domain에 의존시키지 않는다. 기존 미만료 예약 인덱스를 유지하고 `PAID` 집계에 사용할 `(sale_id)` 부분 인덱스를 추가한다.

기존 `POST /api/orders`의 경로, 요청 헤더·본문, 응답 필드와 최초 생성의 `201 Created`는 유지한다. 주문 생성 application은 판매 일정 잠금 후 호출하는 수량 집계를 위 규칙으로 교체한다. 기존 `sumActiveReservedQuantity`처럼 결제 대기 예약만 뜻하는 이름은 결제 완료 수량까지 포함하는 이름으로 변경한다. 같은 `Idempotency-Key`의 재요청은 새 주문이나 예약을 만들지 않고 기존 주문의 식별자·가격·원래 `expiresAt`을 유지하지만, 응답의 `status`는 현재 저장 상태를 반환한다. 따라서 결제 완료 뒤에는 `PAID`가 반환될 수 있다. 기존 주문 생성 설계 문서와 API 문서·테스트의 "최초 응답과 동일" 설명은 이 확장된 상태 계약에 맞게 구현 단계에서 갱신한다.

## 자동 재확인과 멱등성

서버는 `CONFIRMING` 시도 중 다음 확인 시각이 지난 행을 주기적으로 찾는다. 각 시도는 짧은 트랜잭션에서 다음 확인 시각을 갱신해 중복 작업을 줄이고, 토스 호출은 트랜잭션 밖에서 수행한다. 여러 서버 또는 API 재호출이 겹쳐도 같은 결제에는 저장된 토스 멱등키와 같은 요청 값을 사용한다. 토스가 이전 요청 처리 중이라는 응답을 주거나 네트워크가 끊기면 상태를 `FAILED`로 단정하지 않고 다시 확인한다.

재확인은 우선 저장된 `paymentKey` 또는 `paymentOrderId`로 토스 결제를 조회한다. 검증된 `DONE`이면 주문과 결제를 함께 확정한다. 토스가 승인을 허용하고 조회만으로 결과를 알 수 없으면 같은 멱등키로 승인 요청을 재시도한다. 토스가 `ABORTED`, `EXPIRED` 등 승인 실패를 명확히 확정한 경우에만 `Payment.FAILED`로 전이해 예약을 해제한다. 단순 HTTP 오류나 조회 실패만으로 결제 실패를 단정하지 않는다. 반환값이 저장된 주문과 불일치하거나 즉시 완료 대상이 아닌 상태이면 즉시 `REVIEW_REQUIRED`로 전이한다. 첫 승인 요청 후 15분까지 조회·재시도를 해도 결론을 낼 수 없을 때도 `REVIEW_REQUIRED`로 전이한다. 전이 시 운영 로그에 식별 가능한 내부 결제 ID와 원인 코드만 남긴다. `REVIEW_REQUIRED`의 수량은 확인 전까지 계속 예약한다.

자동 재확인은 30초 간격으로 실행하고 `CONFIRMING` 시도는 최초 요청 또는 직전 확인에서 30초가 지나면 다시 확인한다. HTTP 연결 제한 시간은 3초, 응답 제한 시간은 10초로 설정하며 이 값과 재확인 간격은 운영 설정으로 변경할 수 있다. 토스 결제 인증의 유효 시간은 10분이고 멱등키의 유효 기간은 최초 요청 후 15일이다. 로컬 주문 시각만으로 토스 인증 만료를 추정하지 않고 토스 응답과 조회 결과를 기준으로 승인 가능 여부를 판단한다. 토스가 만료를 확정하면 재승인을 멈추며, 토스 멱등키를 바꾸어 재승인하지 않는다. 운영자가 `REVIEW_REQUIRED` 건의 결론을 내리지 못해도 재고를 강제로 해제하는 자동 시간 제한은 두지 않는다.

## 오류 계약

기존 `ProblemDetail` 계약을 사용하며 민감한 토스 응답 본문·시크릿 키·원본 예외를 API 응답에 포함하지 않는다.

| 상황 | HTTP 상태 | 처리 |
| --- | --- | --- |
| 필수 헤더 누락·형식 오류 또는 요청 형식 오류 | 400 | 기존 `COMMON_REQUEST_HEADER_MISSING`, `COMMON_REQUEST_HEADER_INVALID`, `COMMON_REQUEST_BODY_MALFORMED` |
| `paymentKey`, `orderId`, `amount` 값 오류 | 400 | `PAYMENT_KEY_INVALID`, `PAYMENT_ORDER_ID_INVALID`, `PAYMENT_AMOUNT_INVALID` |
| 주문이 없거나 구매자가 다름 | 404 | `PAYMENT_ORDER_NOT_FOUND` |
| 요청 금액 불일치 | 409 | `PAYMENT_AMOUNT_MISMATCH`; 토스 호출 금지 |
| 최초 시도 전 주문 만료 | 409 | `PAYMENT_ORDER_EXPIRED`; 토스 호출 금지 |
| 같은 주문에 다른 `paymentKey` 사용 | 409 | `PAYMENT_KEY_CONFLICT`; 토스 호출 금지 |
| 같은 결제 승인 처리 중 | 409 | `PAYMENT_CONFIRMATION_IN_PROGRESS`; 기존 시도 유지 |
| 토스가 승인 실패·만료를 확정 | 409 | `PAYMENT_CONFIRMATION_FAILED`; 결제 실패 기록과 예약 해제 |
| 토스 응답 불명확 또는 일시적 연결 장애 | 503 | `PAYMENT_CONFIRMATION_UNAVAILABLE`; 시도와 예약 유지 |
| 토스 데이터 불일치 또는 즉시 완료 대상이 아닌 상태 | 409 | `PAYMENT_REVIEW_REQUIRED`; 수동 확인 필요 상태와 예약 유지 |

`503`은 기존 공통 오류 범주에 없으므로 백엔드 오류 매핑에 서비스 일시 불가 범주를 추가한다. 확인된 `PAID`에 대한 동일 요청은 원래 성공 정보를 `200`으로 반환한다. 토스가 이미 `DONE`을 반환했지만 DB 확정이 지연된 동안에는 성공을 단정한 HTTP 응답을 보내지 않는다.

## 검증 전략과 주요 결정

- Domain Test에서 주문의 결제 완료 전이, `Payment`의 상태 전이와 다른 `paymentKey` 재사용 거절을 확인한다.
- PostgreSQL Testcontainers에서 주문별·토스 키별 unique 제약, 승인 진행·완료·실패·만료에 따른 예약 수량, 동일 판매 일정의 주문 생성과 결제 확정 경합을 확인한다.
- 가짜 토스 Client를 사용한 Application Test에서 금액·소유자 검증 전 토스 호출 금지, 같은 멱등키 재사용, 중복 요청, 확정 실패, 응답 단절 뒤 재확인, 토스 성공 뒤 DB 확정 실패의 복구를 확인한다.
- 토스 Client Test에서 Basic 인증 구성, 승인·조회 요청의 필드, 타임아웃·토스 오류 분류와 응답 검증을 확인하되 실제 시크릿 키나 운영 API를 사용하지 않는다.
- MockMvc와 Spring REST Docs로 성공·대표 오류의 헤더, 요청·응답 필드와 `ProblemDetail`을 문서화하고 Gradle `build`를 실행한다.

현재 기획은 [PRD](../../../prd.md)의 단일 상품 바로 구매를 따른다. 장바구니와 묶음 결제가 필요해지면 `Order`의 상품 참조와 여러 판매 일정의 예약 규칙을 다시 설계해야 하지만, `Payment`가 주문을 참조하는 경계는 유지할 수 있다. 한 주문의 복수 결제 시도, 취소·부분 취소와 호출별 감사 이력이 필요해질 때 `payments.order_id` unique 제약 및 별도 작업 기록을 재검토한다. 이번 결정은 기존 ADR-015의 예약 집계 규칙과 주문·결제 도메인 경계에 영향을 주므로, 기존 ADR을 보존하고 신규 ADR로 기록한 뒤 구현한다.

토스 API의 승인 본문·상태·조회 계약은 [코어 API](https://docs.tosspayments.com/reference), 인증과 멱등키 유효 기간은 [인증 및 기타 헤더 설정](https://docs.tosspayments.com/reference/using-api/authorization)을 기준으로 한다.
