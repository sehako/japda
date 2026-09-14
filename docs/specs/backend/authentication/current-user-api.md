# 현재 로그인 사용자 조회 API 설계

## 목적과 완료 조건

Google 로그인 성공 후 SPA가 현재 로그인 상태와 사용자 정보를 확인할 수 있도록 `GET /api/auth/me`를 제공한다. 로그인 성공 리다이렉트는 기존처럼 JWT를 `HttpOnly` 쿠키에 설정하고, SPA는 리다이렉트된 화면에서 이 API를 호출한다. 응답은 내부 사용자 `id`, 현재 저장된 `email`, 현재 저장된 `roles`로 제한한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 유효한 서비스 JWT 쿠키로 요청하면 DB의 현재 사용자·역할을 조회해 `200 OK`를 반환한다.
- 쿠키가 없거나 JWT가 유효하지 않거나 해당 사용자가 없으면 동일한 `401 Unauthorized` 계약을 반환한다.
- Google 로그인용 임시 세션이나 기존 도메인 API의 ID 헤더만으로는 `/api/auth/me`에 인증되지 않는다.
- 기존 도메인 API의 접근 상태와 ID 헤더 계약은 변경하지 않는다.
- SPA의 자격 증명 포함 요청, 성공·인증 실패 응답, JWT 검증과 DB 변경 반영을 테스트하고 Spring REST Docs로 API 계약을 문서화한다.

## 기존 구조와 범위

[Google 소셜 로그인과 JWT 발급](google-social-login.md) 및 `ADR-020`의 후속 단계다. 로그인 성공 시 발급한 JWT에는 내부 사용자 ID가 `sub`로 들어가고 역할은 들어가지 않는다. JWT는 `JAPDA_ACCESS_TOKEN`이라는 `HttpOnly`, `SameSite=Lax`, `Path=/api` 쿠키에 담기며 유효기간은 1시간이다. `users`에는 ID와 이메일이, `user_roles`에는 `BUYER`·`ADMIN`이 저장된다.

이번 범위는 `/api/auth/me`의 쿠키 기반 JWT 검증, 현재 사용자·역할 조회, 인증 실패 응답, 자격 증명 CORS 계약 및 API 문서다. 사용자 표시 이름·프로필 사진·전화번호 저장, 프론트엔드 화면·상태 관리, 토큰 갱신, 로그아웃, 역할별 API 인가는 포함하지 않는다. 기존 구매자·판매자 데이터와 `users.id`를 연결하거나 기존 API의 `X-Buyer-Id`·`X-Seller-Id`를 대체하지 않는다. 인증된 상태 변경 API와 그에 필요한 CSRF 보호도 이번 범위에서 제외한다.

## HTTP 계약

```http
GET /api/auth/me
Cookie: JAPDA_ACCESS_TOKEN=<서비스 JWT>
```

성공 시 `200 OK`, `Content-Type: application/json`, `Cache-Control: no-store`로 반환한다.

```json
{
  "id": 123,
  "email": "buyer@example.com",
  "roles": ["ADMIN", "BUYER"]
}
```

`id`는 `users.id`의 숫자 값이다. `email`은 최근 Google 로그인에서 검증되어 DB에 저장된 값이고, `roles`는 요청 시점의 `user_roles` 값을 중복 없이 이름순으로 정렬한 배열이다. 저장되지 않은 닉네임·프로필 사진, Google `sub`, JWT 또는 Google 토큰은 반환하지 않는다. 이 응답의 역할은 현재 저장된 상태를 화면에 알려주는 정보이며, 기존 도메인 API에 대한 접근 권한을 부여하지 않는다. 관리자 이메일 설정 변경으로 이미 저장된 `ADMIN` 역할을 회수하는 정책은 기존 후속 인가 범위에 남긴다.

쿠키가 없거나, JWT 서명·알고리즘·발급자·대상·만료 시각이 유효하지 않거나, `sub`가 양의 내부 사용자 ID가 아니거나, ID에 해당하는 사용자가 없으면 `401 Unauthorized`를 반환한다. 모든 경우에 동일한 `AUTH_UNAUTHENTICATED` 코드의 `application/problem+json` 응답을 사용하며 토큰 값이나 세부 실패 사유를 노출하지 않는다. 기존 `ProblemDetail` 계약에 401을 매핑할 인증 실패 범주를 추가한다. DB 조회 장애와 같은 서버 오류는 인증 실패로 바꾸지 않고 기존 서버 오류 계약에 따른다. 인증 실패 응답에도 `Cache-Control: no-store`를 적용한다.

## 인증 경계와 데이터 흐름

Spring Security의 `/api/auth/me` 인증 경계에서 `JAPDA_ACCESS_TOKEN` 쿠키만 서비스 JWT의 출처로 사용한다. 기존 발급 설정과 같은 서명 키로 `HS256` 서명을 검증하고, 설정된 발급자·대상과 유효기간을 확인한 뒤 `sub`를 내부 사용자 ID로 해석한다. Google OAuth2 세션의 인증 정보나 클라이언트가 임의로 보낸 ID 헤더는 이 API의 신원 근거로 사용하지 않는다. 이 경계는 `/api/auth/me`에만 적용하며 현재 `permitAll`인 다른 도메인 API를 일괄 보호하지 않는다.

`auth.presentation`은 인증된 사용자 ID를 받아 HTTP 응답을 처리한다. `auth.application`은 `UserRepository`와 `UserRoleRepository`를 통해 사용자와 역할을 읽고 응답 객체를 조립한다. JPA 조회 구현은 `auth.infrastructure.persistence`에 둔다. Entity를 직접 반환하거나 JWT의 역할 claim에서 응답을 구성하지 않는다. 조회는 상태를 변경하지 않으며 새 DB 테이블이나 migration을 추가하지 않는다. 이미 사용 중인 Spring Security의 JWT 검증 기능을 우선 사용하고, 새 dependency는 기존 의존성으로 구현하기 어려울 때만 추가한다.

SPA와 API가 서로 다른 origin인 경우 SPA는 자격 증명을 포함해 요청해야 한다. 백엔드는 허용된 명시적 origin에 한해 `/api/**` 자격 증명 CORS 응답을 제공하며 와일드카드 origin을 사용하지 않는다. 이 계약은 기존 `SameSite=Lax` 쿠키가 전달되는 동일 사이트 배치를 전제로 한다. 로그인 성공 리다이렉트 URL과 JWT 쿠키의 `Path=/api`는 유지한다.

## 검증과 아키텍처 결정

- JWT 검증 테스트에서 정상 서명, 변조, 만료, 잘못된 발급자·대상·알고리즘·`sub`를 확인한다.
- 사용자 조회 테스트에서 DB 이메일 변경과 역할 추가·제거가 다음 조회에 반영되는지 확인한다.
- MockMvc와 Spring REST Docs에서 성공 응답의 필드·헤더와 쿠키 누락·무효·삭제된 사용자에 대한 `401 ProblemDetail`을 검증한다. Google 세션과 ID 헤더만 있는 요청도 `401`인지 확인한다.
- 명시적 SPA origin의 자격 증명 CORS 요청을 검증하고, 기존 도메인 API의 접근·헤더 계약 회귀 테스트 및 백엔드 `build`를 실행한다. 실제 Google 계정이나 운영 비밀값은 사용하지 않는다.

기존 `ADR-020`은 JWT 발급까지만 결정했다. 쿠키 JWT를 검증해 API 신원을 정하는 것은 새 인증 경계이므로 구현 전에 별도 ADR로 기록한다. 해당 ADR은 `/api/auth/me`에 한정된 인증 적용 범위와 기존 API 계약 유지, DB 역할 조회, 쿠키 기반 인증의 CSRF 후속 조건을 명시한다.
