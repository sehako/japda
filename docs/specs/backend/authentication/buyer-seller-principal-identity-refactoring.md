# 구매자·판매자 API의 인증 주체 기반 식별 전환

## 목적과 완료 조건

기존 구매자·판매자 API는 요청자가 보낸 `X-Buyer-Id` 또는 `X-Seller-Id`를 소유자 식별자로 사용한다. 이 값을 다른 사람의 ID로 바꾸어 보내면 인증된 계정과 무관한 데이터에 접근할 수 있다. 해당 API의 식별 근거를 서비스 JWT 쿠키로 인증된 `users.id`와 DB에서 확인한 도메인 ID 연결로 바꾼다.

완료 조건은 다음과 같다.

- 보호 대상 API는 유효한 `JAPDA_ACCESS_TOKEN` 쿠키를 요구하며, 컨트롤러는 `@AuthenticationPrincipal`로 받은 `users.id`에서 서버가 조회한 `buyerId` 또는 `sellerId`를 기존 서비스에 전달한다.
- ID 헤더는 더 이상 필수 입력이나 소유권 판단 근거가 아니다. 헤더가 없거나 위조된 값이어도 인증 주체와 연결된 ID만 사용한다.
- 기존 데이터는 운영자가 계정과 소유권을 확인한 ID만 연결한다. 확인되지 않은 기록은 유지하지만 연결되지 않은 계정에서 접근할 수 없다.
- 쿠키 인증을 사용하는 상태 변경 요청은 CSRF 토큰을 요구한다. 인증 실패는 `401`, 소유자 연결 부재와 CSRF 검증 실패는 서로 다른 오류 코드의 `403 ProblemDetail`로 반환한다.
- 공개 판매 목록·상세 조회의 비로그인 접근은 유지한다. 백엔드 전환본은 프론트엔드의 인증·CSRF 대응 전까지 배포하지 않는다.

## 적용 범위와 HTTP 계약

| 구분 | 엔드포인트 | 현재 식별 입력 | 전환 후 식별 입력 |
| --- | --- | --- | --- |
| 판매자 | `POST /api/products` | `X-Seller-Id` | JWT 주체와 연결된 `sellerId` |
| 판매자 | `POST /api/products/{productId}/images` | `X-Seller-Id` | JWT 주체와 연결된 `sellerId` |
| 판매자 | `GET /api/products/ready` | `X-Seller-Id` | JWT 주체와 연결된 `sellerId` |
| 판매자 | `POST /api/sales` | `X-Seller-Id` | JWT 주체와 연결된 `sellerId` |
| 구매자 | `POST /api/shipping-addresses` | `X-Buyer-Id` | JWT 주체와 연결된 `buyerId` |
| 구매자 | `GET /api/checkout` | `X-Buyer-Id` | JWT 주체와 연결된 `buyerId` |
| 구매자 | `POST /api/orders` | `X-Buyer-Id` | JWT 주체와 연결된 `buyerId` |
| 구매자 | `POST /api/payments/confirm` | `X-Buyer-Id` | JWT 주체와 연결된 `buyerId` |

`GET /api/sales`와 `GET /api/sales/{saleId}`는 계속 공개한다. `GET /api/auth/me`의 인증과 응답 계약도 유지한다. 상품·판매 등록 응답에 포함된 `sellerId`와 기존 서비스의 DTO 및 소유권 검사는 서버에서 결정한 ID를 기준으로 유지한다. `Idempotency-Key` 등 ID 이외의 요청 계약은 유지한다.

보호 대상에서 쿠키가 없거나 JWT가 무효이거나 JWT의 사용자 ID에 해당하는 계정이 없으면 `401 AUTH_UNAUTHENTICATED`를 반환한다. Google 로그인 임시 세션, `BUYER` 역할만 있는 상태, ID 헤더만 있는 요청은 인증 또는 판매자 승인 근거가 아니다. 인증은 되었지만 필요한 구매자·판매자 연결이 없으면 `403 AUTH_BUYER_LINK_REQUIRED` 또는 `403 AUTH_SELLER_LINK_REQUIRED`를 반환한다. 두 응답은 기존 `application/problem+json` 형식과 `Cache-Control: no-store`를 사용한다. 다른 계정의 주문·상품 ID를 경로 또는 본문으로 보내는 경우에는 기존 서비스의 소유권 검사와 리소스 오류 계약을 적용하되, 요청 헤더로 그 검사를 우회할 수 없어야 한다.

## 사용자와 도메인 ID 연결

`users.id`는 인증 주체의 ID이고, 기존 `orders.buyer_id`·`buyer_shipping_address_books.buyer_id` 및 `products.seller_id`·`sales.seller_id`는 각각 별도의 도메인 ID로 유지한다. 숫자가 우연히 같아도 동일인으로 추론하지 않는다. Flyway migration으로 구매자 연결과 판매자 연결을 각각 저장한다. 각 연결에는 `user_id`와 해당 도메인 ID를 필수로 두고, `user_id`의 `users(id)` 외래 키, 양수 ID 검사, 양쪽 ID의 고유 제약으로 사용자와 도메인 ID 사이의 일대일 관계를 보장한다. 주문·배송지·상품·판매 테이블의 기존 행을 재작성하거나 확인되지 않은 ID에 외래 키를 일괄 추가하지 않는다.

운영자가 외부 근거로 확인한 기존 소유권만 연결 데이터로 등록한다. 과거 요청의 ID 헤더, 이메일 일치, 숫자 ID 일치만으로 자동 연결하지 않는다. 하나의 도메인 ID에 여러 계정의 소유권 주장이 있거나 한 계정에 여러 도메인 ID가 섞여 있으면 확인 전까지 연결하지 않는다. 연결되지 않은 과거 주문·배송지·상품·판매 기록은 DB에 남지만 API 접근은 허용하지 않는다. 이력 검증과 연결 데이터 등록 절차는 운영 작업이며, 이번 범위에 관리자 API를 추가하지 않는다.

신규 사용자가 생성될 때 구매자 연결과 새 `buyerId`를 함께 생성한다. 이미 존재하는 사용자에게 연결이 없다면 로그인이나 첫 API 요청만으로 과거 ID를 추정하거나 새 ID를 자동 부여하지 않고 `403`을 반환한다. 신규 `buyerId`는 `orders.buyer_id`, `buyer_shipping_address_books.buyer_id`, 등록된 구매자 연결의 최대값보다 큰 값부터 DB 시퀀스로 발급한다. migration과 전환 절차에서 기존 ID의 최댓값을 확인하고 발급 시작점을 맞추며, 이후 발급은 DB의 동시성 보장에 맡긴다. 판매자 연결은 운영자가 확인한 ID만 등록한다. 연결의 존재가 판매자 API 접근 조건이며 별도 `SELLER` 역할을 도입하지 않는다. 기존 `BUYER`·`ADMIN` 역할 저장과 `/api/auth/me`의 역할 응답은 그대로 둔다.

인증 경계에서는 기존 JWT 쿠키의 검증 구성 요소를 보호 대상 요청으로 확대한다. 구매자·판매자 연결 조회는 `auth`의 application·repository 경계를 통해 수행하고, presentation에서 인증 주체 ID를 연결 조회에 넘긴다. application은 Spring Web 타입에 의존하지 않는다. 연결이 확인된 뒤 기존 각 도메인 서비스에 ID를 전달하므로 서비스의 거래·소유권 규칙과 도메인 ID 구조를 변경할 필요가 없다.

## CSRF와 SPA 연동

JWT는 브라우저가 자동 전송하는 쿠키이므로 `POST`, `PUT`, `PATCH`, `DELETE` 등 상태 변경 요청에 CSRF 검증을 적용한다. 백엔드는 인증된 SPA가 호출할 `GET /api/auth/csrf`를 제공하고, 응답에 CSRF 토큰과 요청에 사용할 헤더 이름을 담아 `Cache-Control: no-store`로 반환한다. SPA는 로그인 직후와 토큰 갱신이 필요한 때 이 API를 자격 증명과 함께 호출한 다음, 변경 요청에 받은 토큰을 지정된 헤더로 보낸다. CSRF 토큰은 JWT나 URL에 넣지 않는다.

Spring Security의 `CsrfTokenRepository`와 요청 처리기를 사용해 예상 토큰을 관리하고 검증한다. SPA가 토큰을 API 응답에서 읽으므로 CSRF 쿠키를 JavaScript에 노출할 필요가 없다. 쿠키 저장 방식을 택할 경우 CSRF 쿠키는 `HttpOnly`로 두고, 응답으로 전달한 토큰과 헤더 검증이 Spring Security의 지연 로딩·BREACH 처리와 함께 동작하도록 구성한다. 로그인 성공 후 이전 토큰이 폐기되면 조회 API가 새 토큰을 발급해야 한다. 이 흐름은 [Spring Security의 CSRF 및 SPA 지침](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)을 따른다.

CSRF 토큰이 없거나 틀린 **인증된** 변경 요청은 `403 AUTH_CSRF_INVALID` `ProblemDetail`로 반환한다. 인증 쿠키가 없거나 무효인 변경 요청은 CSRF 토큰 유무와 관계없이 `401 AUTH_UNAUTHENTICATED`가 우선하도록 보안 필터와 실패 처리를 구성한다. CSRF 검증이 실패하면 컨트롤러나 서비스가 실행되지 않아야 한다. 공개 조회와 `GET /api/auth/me`, `GET /api/auth/csrf`에는 CSRF 토큰을 요구하지 않는다. 허용된 SPA origin의 자격 증명 CORS 설정은 유지하고, 토큰 전달 헤더를 preflight에서 허용한다.

## 검증과 구현 순서

1. 코드 구현 전에 이 설계의 사용자·도메인 ID 연결 및 쿠키 인증 API 확대·CSRF 보호를 새 ADR로 기록하고 `docs/architecture/decisions/README.md` 목록을 갱신한다. 기존 [ADR-020](../../../architecture/decisions/ADR-020-backend-google-oidc-login-and-jwt-cookie.md)과 [ADR-021](../../../architecture/decisions/ADR-021-cookie-jwt-authentication-with-spring-security-components.md)은 수정하지 않는다. 승인된 결정이 [백엔드 아키텍처 지침](../../../architecture/backend.md)의 현재 규칙을 바꾸면 해당 문서도 갱신한다.
2. migration과 연결 조회·신규 구매자 ID 발급을 구현한다. 운영 데이터 등록은 확인된 소유권만 대상으로 별도 절차에서 수행한다.
3. JWT 인증 범위와 컨트롤러의 식별 입력을 전환하고 CSRF 토큰 조회·검증 및 오류 응답을 구성한다. ID 헤더 설명을 REST Docs에서 제거하고 쿠키·CSRF 헤더 및 `401`·`403` 계약으로 교체한다.
4. PostgreSQL 통합 테스트에서 기존 ID의 검증된 연결, 중복 연결 제약, 기존 ID와 충돌하지 않는 신규 발급, 미연결 기록의 접근 거부를 확인한다. MockMvc 통합 테스트에서 헤더 누락·위조, 타 계정의 상품·주문 소유권, 쿠키 누락·무효·삭제된 사용자, 구매자·판매자 연결 부재, CSRF 토큰 정상·누락·오류·로그인 후 갱신, 공개 판매 목록·상세 조회를 확인한다. Spring REST Docs로 변경된 성공·오류 응답을 문서화하고 백엔드 `build`를 실행한다.

이번 범위에는 프론트엔드 수정, 관리자 API, 기존 소유권의 자동 추정, 판매자 가입·승인 화면, 새 역할, 토큰 갱신·로그아웃을 포함하지 않는다. 현재 프론트엔드는 변경 요청에 CSRF 토큰을 보내지 않으므로, 백엔드 전환본의 배포는 후속 프론트엔드 대응과 연동 검증이 끝날 때까지 보류한다.
