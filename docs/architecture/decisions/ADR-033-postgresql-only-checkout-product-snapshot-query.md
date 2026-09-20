# ADR-033: PostgreSQL 단일 체크아웃 상품 스냅샷 조회 사용

- 상태: 대체됨
- 적용 영역: backend
- 결정일: 2026-09-20

## 결정

`GET /api/checkout`의 상품 스냅샷은 Redis 캐시를 사용하지 않고 요청마다 PostgreSQL에서 조회한다. application은 기존 `CheckoutProductSnapshotQuery`와 `CheckoutShippingAddressQuery` 계약을 유지하며, PostgreSQL 구현으로 상품 스냅샷과 구매자 배송지 조회 결과를 조립한다.

Redis 의존성, 체크아웃 캐시 설정, 캐시 key·직렬화·SWR 재적재 구현과 관련 관측은 제거한다.

이 결정은 ADR-032를 대체하고 ADR-018의 체크아웃 조회 모델을 유지한다.

## 이유

체크아웃 상품 스냅샷 캐시의 운영 복잡도와 Redis 장애 우회 경로를 제거하고, 조회 경로를 PostgreSQL 단일 원본으로 단순화한다. 캐시 도입 이전과 동일하게 상품 스냅샷과 배송지를 각각 조회해 기존 HTTP 응답·오류 계약을 보존한다.

## 트레이드오프

반복 체크아웃 조회마다 상품·판매 관련 PostgreSQL 조회가 발생한다. 캐시 적중으로 절감하던 데이터베이스 읽기 비용은 포기하지만, stale 데이터와 캐시 설정·만료·직렬화·비동기 재적재 운영 부담은 없어진다.
