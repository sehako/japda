# ADR-032: 체크아웃 상품 스냅샷 캐시와 stale-while-revalidate 적용

- 상태: 대체됨
- 적용 영역: backend
- 결정일: 2026-09-20

## 결정

`GET /api/checkout`의 구매자 비종속 상품 표시 정보와 판매 단가를 `saleId`별 Redis 스냅샷으로 캐시한다. 스냅샷에는 `saleId`, 상품명, 대표 이미지 상대 경로, 판매 단가와 schema version, fresh·stale 만료 시각만 저장한다. 구매자 식별 정보, 배송지와 요청 수량은 캐시하지 않으며, 배송지는 요청마다 PostgreSQL에서 조회하고 예상 총액은 요청 수량과 캐시된 단가로 계산한다.

application은 `CheckoutProductSnapshotQuery`와 `CheckoutShippingAddressQuery`라는 기술 독립적인 조회 계약을 사용한다. Redis 구현은 상품 스냅샷 조회 계약을 구현하고, 캐시 미스 시 PostgreSQL 원본 조회 구현을 호출한다. 이에 따라 ADR-018의 판매·상품·대표 이미지·배송지를 결합하는 단일 조회는 상품 스냅샷 조회와 배송지 조회로 분리한다. 외부 HTTP 응답·오류 계약과 체크아웃의 조회 전용 성격은 유지한다.

캐시 미스와 hard-expire에는 각 요청이 PostgreSQL 원본을 조회하고 Redis에 스냅샷을 저장한다. 동시 요청의 중복 원본 조회는 허용하며, 응답 지연과 분산 lock·polling 운영 복잡도를 추가하지 않는다.

fresh 기간이 지난 스냅샷은 stale-while-revalidate 방식으로 즉시 반환하고 별도 실행기에서 비동기 재적재를 시작한다. 재적재 실패는 사용자 응답에 영향을 주지 않는다. payload 만료 시각으로 fresh·stale·hard-expire를 판정하고, Redis 물리 TTL은 stale 구간보다 길게 둔다.

Redis는 선택적 의존성으로 둔다. `order.checkout.cache.enabled`의 기본값은 `false`이며, 비활성화 시 Redis 연결·template·캐시 구현을 생성하지 않고 PostgreSQL 구현을 직접 사용한다. 활성화 상태의 Redis 연결·명령·직렬화·lock 오류도 체크아웃 오류로 전파하지 않고 PostgreSQL로 fallback한다. 캐시 설정, 연결, key namespace와 관측은 재고 Redis 구성과 분리한다.

이 결정은 [ADR-018](ADR-018-buyer-checkout-query-model.md)의 체크아웃 조회 모델을 보완한다.

## 이유

체크아웃에서 상품명, 대표 이미지와 판매 단가는 구매자와 무관하고, 현재 정책상 판매 중 변경하거나 삭제할 수 없는 값이다. 이 불변 값을 `saleId` 단위로 캐시하면 적중 경로에서 상품·판매 조인을 제거하면서 구매자별 배송지 격리와 최신 요청 수량 계산은 PostgreSQL 및 application 책임으로 유지할 수 있다.

SWR은 refresh 지연을 사용자 응답 지연으로 전환하지 않는다. Redis 장애 시 DB 조회로 우회하고 기본적으로 비활성화하면 Redis가 없는 환경에서도 기존 체크아웃 가용성과 계약을 보존한다.

## 트레이드오프

상품 또는 판매 수정·삭제 기능이 도입되면 캐시 무효화가 함께 필요하다. 이 기능이 없는 현재 불변성 계약에서는 stale 기간의 값도 허용하지만, 향후 쓰기 흐름이 이를 누락하면 오래된 정보가 반환될 수 있다.

cache miss와 hard-expire 동시 요청은 중복 PostgreSQL 조회를 유발할 수 있다. 상품 스냅샷 원본 조회의 비용보다 lock·polling 구현과 운영 복잡도가 크다고 판단해 이를 허용한다.

Redis 연결, JSON schema version, 비동기 실행기와 관측을 추가로 운영해야 한다. 또한 캐시 적중 여부에 따라 상품 스냅샷과 배송지 조회가 서로 다른 시점의 데이터를 조합할 수 있으나, 체크아웃은 주문 생성 결과를 보장하지 않는 예상 정보 조회이므로 이를 허용한다.
