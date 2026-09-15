# Architecture Decision Records

## 파일명

ADR 파일명은 `ADR-{일련번호}-{영문-kebab-case-제목}.md` 형식을 사용한다.

## 작성 형식

```md
# ADR-{번호}: {결정 사항}

- 상태: 제안 | 승인 | 대체됨
- 적용 영역: backend | frontend | common
- 결정일: YYYY-MM-DD

## 결정

{무엇을 선택했는지}

## 이유

{왜 선택했는지}

## 트레이드오프

{선택으로 인해 포기하거나 감수한 점}
```

## ADR 목록

| 번호 | 결정 | 적용 영역 | 상태 |
| --- | --- | --- | --- |
| 001 | [프론트엔드 빌드 도구로 Vite 사용](ADR-001-frontend-vite.md) | frontend | 승인 |
| 002 | [백엔드 API 문서화에 Spring REST Docs 사용](ADR-002-backend-api-documentation-with-spring-rest-docs.md) | backend | 승인 |
| 003 | [백엔드 영속성에 PostgreSQL, Spring Data JPA, Flyway 사용](ADR-003-backend-persistence-with-postgresql-jpa-flyway.md) | backend | 승인 |
| 004 | [백엔드 오류 응답에 ProblemDetail 계약 사용](ADR-004-backend-problem-detail-error-contract.md) | backend | 승인 |
| 005 | [상품 이미지 원본 저장소로 AWS S3 사용](ADR-005-product-image-storage-with-s3.md) | backend | 승인 |
| 006 | [상품 이미지 기능을 상품 도메인의 계층별 하위 패키지로 구성](ADR-006-product-image-package-structure.md) | backend | 승인 |
| 007 | [Application 계층에서 Spring Web 타입 분리](ADR-007-application-layer-spring-web-independence.md) | backend | 승인 |
| 008 | [상품 이미지 최초 등록 검증을 파일 시그니처로 제한](ADR-008-product-image-signature-validation.md) | backend | 승인 |
| 009 | [상품 원본과 판매 일정의 도메인 경계 분리](ADR-009-product-and-sale-domain-boundaries.md) | backend | 승인 |
| 010 | [구매자 판매 상품 목록을 조회 전용 projection으로 구성](ADR-010-buyer-sale-product-query-model.md) | backend | 승인 |
| 011 | [상품 이미지 조회 정보로 상대 경로 반환](ADR-011-product-image-relative-path-response.md) | backend | 승인 |
| 012 | [프론트엔드 클라이언트 라우팅에 React Router 사용](ADR-012-frontend-routing-with-react-router.md) | frontend | 승인 |
| 013 | [프론트엔드 서버 상태에 TanStack Query 사용](ADR-013-frontend-server-state-with-tanstack-query.md) | frontend | 승인 |
| 014 | [프론트엔드 테스트에 Vitest와 Testing Library 사용](ADR-014-frontend-testing-with-vitest-and-testing-library.md) | frontend | 승인 |
| 015 | [주문 행 기반 재고 예약과 판매 일정 잠금](ADR-015-order-row-reservation-with-sale-lock.md) | backend | 승인 |
| 016 | [백엔드 계층 내부를 역할별 하위 패키지로 구성](ADR-016-backend-role-based-package-structure.md) | backend | 승인 |
| 017 | [구매자 배송지 목록 도메인과 잠금 행 구성](ADR-017-buyer-shipping-address-book-domain-and-locking.md) | backend | 승인 |
| 018 | [구매자 체크아웃을 주문 영역의 조회 전용 모델로 구성](ADR-018-buyer-checkout-query-model.md) | backend | 승인 |
| 019 | [결제 시도 기록과 주문 예약의 정합성](ADR-019-payment-attempt-and-reservation-consistency.md) | backend | 승인 |
| 020 | [백엔드 Google OIDC 로그인과 JWT 쿠키 발급](ADR-020-backend-google-oidc-login-and-jwt-cookie.md) | backend | 승인 |
| 021 | [기존 Spring Security 구성 요소로 쿠키 JWT 인증](ADR-021-cookie-jwt-authentication-with-spring-security-components.md) | backend | 승인 |
| 022 | [보호 API의 쿠키 인증과 공통 클라이언트 CSRF 처리](ADR-022-frontend-protected-api-cookie-and-csrf.md) | frontend | 승인 |
| 023 | [구매자·판매자 식별을 인증 주체 연결로 전환하고 쿠키 API에 CSRF 보호 적용](ADR-023-buyer-seller-principal-identity-and-cookie-csrf.md) | backend | 승인 |
