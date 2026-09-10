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
