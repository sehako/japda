# ADR-020: 백엔드 Google OIDC 로그인과 JWT 쿠키 발급

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-14

## 결정

백엔드 Spring Security OAuth2 Login으로 Google OIDC authorization code 흐름과 `state` 검증을 처리한다. 검증된 Google `sub`를 외부 식별자로 사용해 내부 사용자와 역할을 PostgreSQL에 저장한다. 최초 사용자에게 `BUYER`를 부여하고, 검증된 이메일이 설정된 관리자 Gmail 주소와 일치하면 `ADMIN`을 추가한다.

로그인 성공 시 내부 사용자 ID를 `sub`로 하는 1시간짜리 서명 JWT를 발급하고, 동일 사이트 SPA가 사용할 수 있도록 `HttpOnly`, `SameSite=Lax`, `/api` 경로의 쿠키로 전달한다. 운영 환경에서는 `Secure`를 사용한다. JWT를 URL에 넣지 않으며, 성공과 실패는 설정된 SPA 주소로 리다이렉트한다. 이번 단계에서는 JWT 검증과 API 인가를 도입하지 않고 기존 도메인 API의 헤더 계약을 유지한다.

## 이유

Google OIDC 검증은 Spring Security의 표준 흐름에 맡기고, 변경 가능한 이메일 대신 Google `sub`로 계정을 식별한다. 내부 사용자 ID와 역할을 서비스 DB에서 관리하면 외부 계정 정보와 서비스 권한을 분리할 수 있다. JWT를 `HttpOnly` 쿠키로 전달하면 URL을 통한 토큰 노출을 피할 수 있다.

## 트레이드오프

OAuth2 왕복 중 `state` 저장을 위한 임시 세션이 필요하다. `SameSite=Lax` 쿠키는 동일 사이트 SPA·API 배치를 전제로 하며, 로컬 HTTP와 운영 HTTPS의 `Secure` 설정을 구분해야 한다. 쿠키 기반 JWT 인증을 추가할 때는 CSRF 보호를 함께 설계해야 한다. 관리자 이메일 목록 변경에 따른 기존 역할 회수와 요청 시점의 권한 확인은 후속 인가 작업이 필요하다.
