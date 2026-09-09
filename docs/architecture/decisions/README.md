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
| 002 | [백엔드 영속성 기술로 PostgreSQL, Spring Data JPA와 Flyway 사용](ADR-002-backend-persistence-stack.md) | backend | 승인 |
| 003 | [판매 가격을 Sale이 소유](ADR-003-sale-owns-price.md) | backend | 승인 |
| 005 | [상품 이미지를 AWS S3에 저장](ADR-005-product-image-storage.md) | backend | 승인 |
