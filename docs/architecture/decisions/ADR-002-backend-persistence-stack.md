# ADR-002: 백엔드 영속성 기술로 PostgreSQL, Spring Data JPA와 Flyway 사용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-09

## 결정

백엔드의 관계형 데이터베이스로 PostgreSQL을 사용하고, Domain Entity 영속화에는 Spring Data JPA를 사용한다. 데이터베이스 스키마와 변경 이력은 Flyway 마이그레이션으로 관리한다. 영속성 통합 테스트는 PostgreSQL Testcontainers 사용을 우선한다.

## 이유

한정 재고, 주문, 결제, 취소와 정산은 트랜잭션과 데이터 정합성이 중요한 관계형 데이터다. 운영 환경과 테스트 환경에서 PostgreSQL의 제약과 동작을 동일하게 검증할 필요가 있다. Spring Data JPA는 기존 백엔드 아키텍처의 Domain Repository와 JPA Entity 규칙에 부합한다. Flyway는 스키마 변경을 명시적인 파일과 순서로 관리하여 환경마다 동일한 스키마를 재현할 수 있게 한다.

## 트레이드오프

인메모리 저장소나 H2보다 로컬 개발 및 테스트 구성이 복잡하며 Docker 실행 환경이 필요하다. Spring Data JPA의 영속성 컨텍스트와 지연 로딩 특성을 이해해야 하고, Flyway 마이그레이션을 애플리케이션 모델 변경과 함께 관리해야 한다. 반면 실제 PostgreSQL과 다른 테스트 데이터베이스에서 생길 수 있는 동작 차이를 줄이고 스키마 변경 이력을 명확히 유지할 수 있다.
