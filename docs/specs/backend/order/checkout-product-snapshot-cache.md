# 체크아웃 상품 스냅샷 캐시 설계

## 목적과 완료 조건

구매자가 `GET /api/checkout`으로 주문 직전 정보를 확인할 때, 판매별로 불변인 상품 표시 정보와 판매 단가의 반복 조회 병목을 줄인다. Redis에 판매별 상품 스냅샷을 저장하고, 최초 적재와 만료 후 재적재에는 분산 single-flight를 적용한다. fresh 기간이 지난 값은 stale-while-revalidate(SWR) 방식으로 즉시 반환하면서 한 요청만 비동기 재적재한다.

다음 조건을 모두 만족하면 완료로 본다.

- 상품명, 대표 이미지 경로, 판매 단가를 `saleId`별 스냅샷으로 Redis에 캐시한다.
- 배송지와 그 밖의 구매자별 정보는 캐시하지 않고 요청마다 PostgreSQL에서 조회한다.
- 캐시 미스와 hard-expire에서 동시에 들어온 요청은 하나의 인스턴스만 원본 DB 적재를 수행한다.
- stale 기간에는 이전 스냅샷을 응답하고 하나의 요청만 비동기 재적재를 시작한다.
- Redis와 캐시 직렬화 오류는 체크아웃 API 오류로 전파하지 않고 DB 조회로 우회한다.
- 기존 HTTP 응답·오류 계약, 인증·구매자 격리, 주문·재고·결제 상태 비변경 계약을 유지한다.

## 전제와 범위

이 설계는 현재 기획의 다음 불변성 계약을 전제로 한다.

- 생성된 판매의 가격은 수정할 수 없다.
- 판매와 상품 표시 정보는 판매 중 수정하거나 삭제할 수 없다.
- 향후 판매·상품 수정 또는 삭제 기능을 도입하면, 해당 쓰기 흐름은 변경 대상 `saleId`의 캐시를 무효화해야 한다. 그 쓰기 기능과 무효화 구현은 이 작업의 범위가 아니다.

포함 범위는 체크아웃 상품 스냅샷 캐시, Redis 기반 분산 single-flight, SWR, 캐시 장애 우회, 관측과 테스트다. `GET /api/checkout`의 URL·요청·응답 형식, 주문 생성, 재고 예약, 결제, 배송지 CRUD, 상품·판매 수정 및 삭제 API는 변경하지 않는다.

기존 체크아웃 조회 API의 기본 계약은 [구매자 체크아웃 조회 API 설계](buyer-checkout-api.md)를 따른다. 캐시 구현 전에는 [ADR-018](../../../architecture/decisions/ADR-018-buyer-checkout-query-model.md)을 보완하는 새 ADR을 작성하고 ADR 목록을 갱신한다.

Redis는 선택적 외부 의존성이다. `order.checkout.cache.enabled`의 기본값은 `false`로 둔다. 비활성 상태에서는 Redis 연결, template, 캐시 구현을 생성하지 않고 PostgreSQL 상품 스냅샷 조회 구현을 직접 사용한다. 활성 상태에서만 Redis 캐시 구현을 생성하며, Redis 런타임 장애는 PostgreSQL fallback으로 처리한다.

## 캐시 경계와 조회 흐름

캐시 값은 구매자와 무관한 다음 필드만 포함한다.

| 필드 | 원본 | 설명 |
| --- | --- | --- |
| `saleId` | `sales.id` | 캐시 키와 값의 일관성 검증용 식별자 |
| `productName` | `products.name` | 체크아웃 표시 상품명 |
| `representativeImagePath` | 대표 `product_images` 객체 키 | 기존 응답 계약의 상대 경로 |
| `unitPrice` | `sales.price` | 수정 불가한 판매 단가 |

배송지 ID, 수취인 정보, 전화번호, 주소, 구매자 ID, 인증 정보와 요청 수량은 Redis에 저장하지 않는다. `quantity`는 요청마다 받아 캐시된 `unitPrice`와 곱해 `totalPrice`를 계산한다.

application 계층은 기술 독립적인 두 조회 계약만 사용한다.

1. `CheckoutProductSnapshotQuery`는 `saleId`로 상품 스냅샷을 반환하거나 판매 미존재를 알린다.
2. `CheckoutShippingAddressQuery`는 인증된 `buyerId`의 배송지 목록을 ID 오름차순으로 반환한다.

infrastructure 계층의 Redis 구현은 `CheckoutProductSnapshotQuery`를 구현하고, 캐시 미스 시 PostgreSQL 원본 조회 구현을 호출한다. 배송지 PostgreSQL 구현은 캐시와 독립적으로 매 요청 실행한다. application 서비스는 두 결과를 조립하고 기존의 입력 검증, 곱셈 범위 검증, 응답·오류 변환을 유지한다.

따라서 캐시 적중 시에는 상품·판매 테이블을 읽지 않고 배송지 관련 테이블만 읽는다. 캐시 미스·재적재 시에는 상품 스냅샷 원본 조회와 배송지 조회가 각각 실행될 수 있다. 기존의 다섯 테이블 단일 조회 계약은 이 캐시 설계로 대체되며, API의 외부 계약은 바뀌지 않는다.

## 키, 값과 만료 정책

`order.checkout.cache.*` 외부 설정은 최소한 다음 값을 제공한다. 환경별 설정만으로 Redis 사용 여부와 연결·만료 정책을 바꿀 수 있어야 하며, 기존 `order.inventory.redis.*` 설정은 재사용하지 않는다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `enabled` | `false` | Redis 캐시 구현과 Redis 연결 생성 여부 |
| `host` | 환경별 지정 | Redis 호스트 |
| `port` | 환경별 지정 | Redis 포트 |
| `connect-timeout` | 500ms | Redis 연결 제한 시간 |
| `command-timeout` | 200ms | Redis 명령 제한 시간 |
| `fresh-ttl` | 5분 | fresh 구간 길이 |
| `stale-ttl` | 55분 | fresh 이후 허용할 stale 구간 길이 |
| `physical-ttl` | 65분 | Redis key 보존 시간 |
| `refresh-lock-ttl` | 3초 | 분산 single-flight lock 보존 시간 |

키 namespace 기본값은 `japda`로 하고 캐시 키는 다음 형식을 사용한다.

```text
{namespace}:checkout:product-snapshot:v1:{saleId}
{namespace}:checkout:product-snapshot:v1:{saleId}:refresh-lock
```

값은 JSON으로 직렬화하며 `schemaVersion`, 스냅샷 필드, `freshUntil`, `staleUntil`을 포함한다. schema version이 맞지 않거나 역직렬화할 수 없는 값은 캐시 미스로 취급한다.

기본 시간 정책은 다음과 같다. 모든 값은 설정으로 변경 가능해야 하며, `staleUntil`은 `freshUntil`보다 이후여야 한다.

| 구간 | 기본값 | 동작 |
| --- | --- | --- |
| fresh | 적재 후 5분 | 캐시 값을 즉시 반환한다. |
| stale | 적재 후 5분 초과~60분 이하 | 캐시 값을 즉시 반환하고 단일 비동기 재적재를 시도한다. |
| hard-expire | 적재 후 60분 초과 | 캐시 값을 사용하지 않고 single-flight로 원본 적재한다. |
| Redis 물리 TTL | 적재 후 65분 | stale 값과 만료 메타데이터가 hard-expire 판단 전에 삭제되지 않도록 여유를 둔다. |
| refresh lock TTL | 3초 | 적재 작업 중단 시 다른 요청이 재시도할 수 있게 한다. |

시간 판정은 애플리케이션이 현재 시각과 payload의 만료 시각을 비교해 수행한다. Redis native TTL만으로는 stale 값을 읽을 수 없으므로 SWR 정책의 기준으로 사용하지 않는다.

## Single-flight와 SWR

캐시 미스 또는 hard-expire에서 요청은 `refresh-lock` 키에 `SET NX`와 lock TTL을 사용해 락을 획득한다.

- 락 획득 요청은 PostgreSQL에서 상품 스냅샷을 읽고 Redis에 fresh·stale 만료 시각과 물리 TTL을 포함해 저장한 뒤 락을 해제한다.
- 락 미획득 요청은 25ms 간격으로 최대 3초 동안 캐시를 다시 읽는다. 적재된 fresh 또는 stale 값이 보이면 이를 사용한다.
- 대기 시간이 지나거나 락 보유 요청이 실패한 경우에는 요청 지연을 무한정 늘리지 않고 PostgreSQL 원본 조회로 우회한다. 이 저하 상황에서는 단일 DB 적재 보장을 포기하지만 체크아웃 가용성을 우선한다.

stale 값이 있으면 요청은 값을 즉시 반환한다. 동시에 lock 획득을 시도한 한 요청만 별도 실행기에서 재적재를 수행한다. 락을 획득하지 못한 stale 요청은 재적재를 기다리지 않는다. 재적재 실패 시 stale 값은 `staleUntil`까지 유지하며, 다음 stale 요청이 다시 재적재를 시도한다.

락 소유자는 자신이 만든 lock만 해제해야 한다. 따라서 lock 값에는 예측 불가능한 소유 토큰을 넣고, 비교 후 삭제를 원자적으로 수행한다. 만료된 소유자가 다른 요청의 락을 삭제해서는 안 된다.

## 장애 처리와 관측

Redis 연결·명령 오류, 락 오류, 직렬화·역직렬화 오류, 비동기 재적재 오류는 오류 로그와 메트릭을 남긴다. 동기 요청에서는 원본 PostgreSQL 조회로 우회하고, 비동기 재적재 오류는 사용자 응답에 영향을 주지 않는다. PostgreSQL 원본 조회 실패와 기존의 상품·대표 이미지 불변식 위반은 현재 체크아웃 오류 계약대로 처리한다.

캐시 구성은 기존 재고 품절 마커의 `order.inventory.redis.*` 설정, 키, template, 의존성을 재사용하지 않는다. 재고 Redis 제거 작업과 독립된 `order.checkout.cache.*` 설정, Redis 연결과 키 생성기를 둔다. `enabled=false`에서는 Redis client나 연결을 초기화하지 않으므로 Redis가 배포 환경에 없어도 애플리케이션은 PostgreSQL 조회만으로 기동·동작한다.

최소한 다음 저카디널리티 관측값을 기록한다.

- 캐시 결과: `fresh-hit`, `stale-hit`, `miss`, `hard-expire`, `fallback`
- 재적재 결과: `loaded`, `lock-contended`, `failed`
- Redis 작업 시간과 오류 수
- 원본 PostgreSQL 조회 수와 시간

`saleId`, `buyerId`, 주소, 상품명 등 고카디널리티 또는 개인정보는 메트릭 태그에 넣지 않는다.

## 검증 전략

- application 테스트에서 캐시된 단가와 요청 수량으로 총액을 계산하고, 배송지 결과를 올바르게 조립하며, 기존 입력·곱셈 범위 오류를 유지하는지 확인한다.
- Redis 단위 테스트에서 fresh/stale/hard-expire 판정, JSON schema 불일치, 물리 TTL, lock 소유 토큰 비교 삭제, 락 경합, 재적재 실패와 Redis 오류의 DB fallback을 검증한다.
- 구성 테스트에서 `enabled=false`일 때 Redis 연결·캐시 구현이 생성되지 않고 PostgreSQL 구현만 선택되는지, `enabled=true`일 때만 Redis 구성이 생성되는지 검증한다.
- PostgreSQL·Redis Testcontainers 통합 테스트에서 최초 동시 요청의 단일 원본 적재, fresh hit의 상품 원본 조회 생략, stale 동시 요청의 즉시 응답과 단일 비동기 재적재, hard-expire 경합의 polling, Redis 중단 시 정상 DB 응답을 검증한다.
- 통합·API 테스트에서 서로 다른 구매자의 배송지가 섞이지 않고 Redis에 PII가 저장되지 않으며, 체크아웃 조회가 주문·재고·결제 상태를 변경하지 않는지 확인한다.
- Micrometer 테스트에서 결과·재적재·fallback 메트릭을 확인한다.

## 주요 결정과 트레이드오프

상품 표시 정보와 불변 판매 단가를 함께 캐시해 상품·판매 조인을 캐시 적중 경로에서 제거한다. 그 대가로 기존 단일 SQL 조회는 상품 스냅샷 조회와 배송지 조회의 두 책임으로 나뉜다.

SWR은 데이터 갱신 직후 최대 55분의 오래된 값을 반환할 수 있다. 현재 불변성 계약 아래에서는 원본 데이터 변경이 없으므로 이 위험을 허용한다. 향후 변경 기능은 캐시 무효화를 반드시 포함해야 한다.

Redis 장애와 lock 보유 요청 실패 시 DB fallback을 선택해 표시용 조회의 가용성을 보장한다. 이때 짧은 기간의 중복 DB 조회는 성능 최적화보다 정상 응답을 우선한 의도된 동작이다.
