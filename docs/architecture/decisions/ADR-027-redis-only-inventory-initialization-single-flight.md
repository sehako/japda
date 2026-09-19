# ADR-027: Redis 분산 lock만으로 재고 초기화 Single-flight 구성

- 상태: 대체됨 ([ADR-029](ADR-029-redis-sold-out-marker.md))
- 적용 영역: backend
- 결정일: 2026-09-16

## 결정

동적 Redis 재고 캐시가 없는 모든 주문 요청은 애플리케이션 인스턴스 내부에서 초기화 결과를 공유하지 않고 고유 token으로 Redis 초기화 lock 획득을 시도한다. Redis lock 획득자만 DB 재고 snapshot을 조회하고, lock을 얻지 못한 요청은 제한된 시간 동안 Redis 재고 생성 여부를 polling한 뒤 생성되지 않으면 기존 DB 주문 경로로 우회한다.

`ConcurrentHashMap`, `CompletableFuture` 또는 이에 준하는 JVM 내부 상태로 초기화 결과를 공유하지 않는다. 단일 인스턴스와 여러 인스턴스 모두 같은 Redis lock과 polling 규칙을 사용한다.

이 결정은 애플리케이션 인스턴스 내부 초기화 결과 공유를 포함한 ADR-026의 Single-flight 구성을 대체한다. Redis를 짧은 수명의 보조 선점 계층으로 사용하고, 기존 DB 잠금과 예약 집계를 최종 정합성 기준으로 유지하는 나머지 결정은 계속 따른다.

## 이유

JVM 내부 공유와 Redis 분산 lock을 함께 사용하면 인스턴스 내부 요청과 인스턴스 간 요청이 서로 다른 동기화 경로를 거친다. 모든 cache miss 요청이 Redis lock을 사용하면 배포 인스턴스 수와 관계없이 같은 소유권 및 timeout 규칙을 적용할 수 있다.

Redis Lua script가 lock token 소유권과 재고 캐시 부재를 원자적으로 확인하므로 늦게 완료된 DB snapshot이 다른 초기화 결과를 덮어쓰는 것을 방지할 수 있다. 애플리케이션 프로세스 내부 상태를 두지 않아 인스턴스 종료와 교체 때 정리할 상태도 생기지 않는다.

## 트레이드오프

동일 인스턴스에서 동시에 발생한 cache miss도 각각 Redis lock 획득과 polling 명령을 수행하므로 JVM 내부에서 결과를 공유하는 방식보다 Redis 호출 수가 늘어난다.

초기화가 진행되는 동안 lock을 얻지 못한 요청은 polling 간격만큼 추가 지연될 수 있고, 대기 제한 안에 캐시가 생성되지 않으면 DB 경로로 우회한다. 대신 Redis 장애와 초기화 지연이 주문 전체 실패로 확산되지 않는 fail-open 정책을 유지한다.
