# ADR-003: 백엔드 영속성에 PostgreSQL, Spring Data JPA, Flyway 사용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

백엔드의 관계형 데이터 저장소로 PostgreSQL을 사용하고, 도메인 Entity의 영속성 구현에는 Spring Data JPA를 사용한다. 데이터베이스 스키마와 변경 이력은 Flyway migration으로 관리하며, Hibernate는 스키마를 자동 생성하지 않고 애플리케이션 시작 시 Entity mapping과 스키마가 일치하는지만 검증한다.

테스트에서 실제 데이터베이스 동작을 확인해야 하는 Persistence Test와 Integration Test는 PostgreSQL Testcontainers를 사용한다. 데이터베이스 접속 정보는 환경변수 또는 Testcontainers가 제공하는 동적 설정으로 주입한다.

## 이유

상품과 향후 판매·주문 데이터는 관계와 트랜잭션 일관성이 중요하며, 운영과 테스트에서 동일한 PostgreSQL 동작을 검증할 필요가 있다. Spring Data JPA는 현재 계층 구조의 domain Repository Interface와 infrastructure adapter를 구현하는 데 적합하다. Flyway를 사용하면 적용된 스키마 변경을 명시적이고 재현 가능한 이력으로 유지할 수 있다.

## 트레이드오프

PostgreSQL과 Testcontainers 실행 환경이 필요하므로 로컬 테스트와 CI의 준비 비용 및 실행 시간이 증가한다. JPA mapping과 Flyway migration을 함께 유지해야 하며 두 정의가 어긋나면 애플리케이션 시작이 실패한다. 데이터베이스별 제약과 SQL을 사용하므로 다른 데이터베이스로 전환할 때 migration과 테스트를 수정해야 한다.
