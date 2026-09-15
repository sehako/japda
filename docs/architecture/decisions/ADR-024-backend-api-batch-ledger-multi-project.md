# ADR-024: API·배치·원장 기능을 점진적 Gradle 멀티 프로젝트로 구성

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-15

## 결정

기존 `apps/backend` Gradle root project와 소스 위치를 API 애플리케이션으로 유지하고, 독립 실행 가능한 `:batch` 하위 프로젝트와 plain jar인 `:modules:ledger` 하위 프로젝트를 추가한다. API와 배치는 필요할 때 `:modules:ledger`의 사용자 지갑·원장 application 기능을 코드 수준에서 재사용하며 서로 HTTP로 호출하지 않는다. 배치는 기존 API root project에 의존하지 않고 정산 대상 데이터를 JDBC projection으로 읽는다.

API와 배치는 동일한 PostgreSQL 스키마를 사용한다. Flyway migration 파일과 실행 책임은 기존 API root project에 유지하고 지갑·원장·정산 및 Spring Batch 메타데이터 migration도 기존 migration 경로에 기록한다. 배치에서는 Flyway와 Spring Batch의 자동 스키마 초기화를 비활성화하며 API migration이 완료된 뒤 실행한다.

## 이유

배치를 API와 별도 JVM과 실행 파일로 분리하면 HTTP 요청 처리와 Job의 자원 사용, 실행 주기와 장애를 독립적으로 운영할 수 있다. 지갑 입금 규칙을 라이브러리 모듈로 공유하면 배치가 원장 테이블을 임의로 변경하거나 API를 네트워크 호출하지 않고도 같은 멱등성과 트랜잭션 규칙을 적용할 수 있다.

기존 백엔드 전체를 API, commerce, persistence 등으로 즉시 재구성하면 배치와 무관한 소스 이동과 의존성 변경이 커진다. 기존 root project를 유지한 점진적 분리는 현재 구조를 보존하면서 필요한 실행 경계와 공유 경계만 추가한다. Flyway 소유권을 API에 유지하면 기존 migration 이력을 옮기지 않고 스키마 변경 주체를 하나로 제한할 수 있다.

## 트레이드오프

Gradle root project가 집계 전용이 아니라 API 애플리케이션 역할도 겸하므로 완전히 대칭적인 멀티 프로젝트 구조는 아니다. API와 배치는 같은 데이터베이스를 공유해 스키마와 배포 순서에 결합되며, API migration이 완료되기 전에는 새 배치를 실행할 수 없다. 배치가 API의 도메인 Entity를 재사용하지 않으므로 정산 조회 projection과 SQL은 기존 결제·주문·판매 스키마 변경을 별도로 따라야 한다.

두 애플리케이션의 완전한 배포 독립성이나 데이터베이스 분리가 필요해지면 migration 전용 실행 단위, 공통 schema artifact 또는 이벤트 기반 연동을 새 ADR로 결정한다. 기존 백엔드의 다른 도메인을 이 결정만으로 모듈화하지 않는다.
