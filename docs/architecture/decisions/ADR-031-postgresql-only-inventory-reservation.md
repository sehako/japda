# ADR-031: PostgreSQL 단일 재고 예약 경로 사용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-20

## 결정

주문 생성에서 Redis 품절 마커를 제거하고, 선행 멱등성 조회 뒤 모든 신규 요청을 PostgreSQL 주문 transaction으로 처리한다. 주문 가능 여부와 재고 정합성은 `sale_inventory_counters`의 수량 조건부 UPDATE 및 `inventory_reservations`의 주문별 예약만으로 판정한다.

품절 요청도 PostgreSQL에 도달해 조건부 UPDATE 실패와 잔여 수량 판정 후 기존 품절 오류를 반환한다. `SoldOutInventoryMarker`, Redis 연결·설정·key 생성·관측, Redis 전용 의존성과 테스트를 제거한다. 주문 HTTP 계약, 재고 카운터와 예약 데이터 모델, 결제 및 재고 예약 상태 전이는 변경하지 않는다.

이 결정은 [ADR-029](ADR-029-redis-sold-out-marker.md)를 대체한다. Redis 기능 전용 인프라는 새 애플리케이션 버전이 모든 인스턴스에 배포되고 마지막 구버전이 기록한 마커 TTL이 지난 뒤에 제거한다. 기존 key는 만료에 맡기며 강제 삭제하지 않는다.

## 이유

ADR-029의 품절 마커는 PostgreSQL이 최종 정합성을 보장하는 상태에서, 모든 신규 주문에 Redis 조회를 추가하고 완전 품절 요청에는 Redis 기록을 추가한다. 성공 주문의 추가 네트워크 왕복과 품절 요청의 처리 비용이 비슷해져, 품절 빠른 거절의 이점이 Redis 의존성과 운영 복잡성을 정당화하지 못한다.

PostgreSQL 조건부 UPDATE는 이미 동시 요청의 초과 판매를 차단하며, 주문별 예약은 점유·반환과 결제 상태 전이를 보존한다. Redis를 제거하면 주문 경로의 외부 상태와 장애 우회가 사라지고, 예약 재고가 있어도 TTL 동안 거절되는 과소 재고도 발생하지 않는다.

## 트레이드오프

완전 품절 뒤에도 요청이 PostgreSQL transaction과 카운터 UPDATE를 시도하므로, Redis 마커가 있던 경우보다 품절 요청의 DB 부하와 지연이 증가할 수 있다. 집중된 품절 트래픽의 PostgreSQL 영향은 성공 전용 시나리오와 분리해 측정·관측한다.

Redis 마커 hit로 DB 접근을 피하는 최적화와 해당 Micrometer 지표는 제거된다. 향후 품절 트래픽이 실제 병목으로 확인되면 PostgreSQL 정합성을 유지하는 대안을 별도 아키텍처 결정으로 검토한다.
