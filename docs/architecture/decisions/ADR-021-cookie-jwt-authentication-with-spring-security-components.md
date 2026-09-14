# ADR-021: 기존 Spring Security 구성 요소로 쿠키 JWT 인증

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-14

## 결정

`GET /api/auth/me`에 한정해 `JAPDA_ACCESS_TOKEN` 쿠키의 서비스 JWT로 사용자를 인증한다. Spring Security의 `AuthenticationConverter`가 쿠키를 인증 전 `Authentication`으로 변환하고, 기존 `AuthenticationFilter`가 이 필터 전용 `ProviderManager`에 전달한다. Google OAuth2 Login의 인증 제공자 설정은 변경하지 않는다. JWT 전용 `AuthenticationProvider`는 기존 의존성의 `NimbusJwtDecoder`로 서명과 알고리즘, 발급자, 대상, 만료 시각을 검증하고 JWT의 `sub`를 내부 사용자 ID로 해석한다. 인증 결과에는 원문 JWT를 남기지 않고 현재 요청의 `SecurityContext`에만 설정하며 세션에 저장하지 않는다. 인증 성공 후에는 리다이렉트 없이 요청을 계속 처리한다.

현재 사용자 정보는 JWT의 이메일·역할 claim이 아니라 DB의 사용자와 역할에서 조회한다. 쿠키 누락·JWT 무효·사용자 삭제는 같은 `401 ProblemDetail`로 반환한다. Google OAuth2 Login 흐름과 기존 도메인 API의 `permitAll`·ID 헤더 계약은 유지한다. 이번 범위에서는 Resource Server starter, 별도 JWT 라이브러리, 비밀번호 로그인용 `DaoAuthenticationProvider`를 추가하지 않는다.

## 이유

현재 OAuth2 Client 의존성으로 Spring Security 인증 필터·제공자 확장 지점과 JWT 발급에 사용 중인 Nimbus 기반 JWT 기능을 이미 사용할 수 있다. 인증 시 `AuthenticationManager`를 거치면 토큰 검증과 `SecurityContext` 설정을 Spring Security 흐름 안에서 처리할 수 있다. 인증 범위가 조회 API 하나이고 JWT는 쿠키로 전달되므로, 필요한 쿠키 변환과 제공자만 구현하는 방식이 새 Resource Server 의존성을 추가하는 것보다 현재 범위에 맞다. 역할을 DB에서 조회하면 변경된 역할을 다음 조회에 반영할 수 있다.

## 트레이드오프

Resource Server가 제공하는 JWT 인증 필터·제공자 대신 쿠키 변환, JWT 인증 제공자, 인증 실패 응답을 직접 구성하고 검증해야 한다. 쿠키가 없는 요청과 무효 JWT 요청을 같은 `401`로 처리해야 하며, Google OAuth2 Login 설정과 인증 적용 범위가 겹치지 않도록 관리해야 한다. 이후 JWT 인증을 다른 API로 확대할 때 이 구성의 재사용 범위와 Resource Server 도입 여부를 다시 검토한다. 쿠키로 인증하는 상태 변경 API를 도입할 때는 CSRF 보호를 함께 설계한다.
