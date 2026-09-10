# ADR-007: Application 계층에서 Spring Web 타입 분리

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

Application 계층은 `MultipartFile`, `Part`, `ResponseEntity` 등 Spring Web과 HTTP 전송 타입에 의존하지 않는다. Presentation 계층이 HTTP 입력을 application이 정의한 Dto와 입력 인터페이스로 변환하고, application은 해당 타입으로 유스케이스를 조율한다.

파일 입력이 필요한 경우 application이 크기와 새로운 입력 스트림 생성을 제공하는 인터페이스를 정의하고 presentation이 `MultipartFile` 어댑터로 구현한다. Application은 `java.io.InputStream` 등 JVM 표준 타입을 사용할 수 있으며 스트림의 사용 범위와 종료를 관리한다.

Application 계층의 Spring 전체 의존성을 제거하지는 않는다. 기존 구조와 같이 `@Service`, `@Transactional` 등 유스케이스 실행과 트랜잭션 지원을 위한 Spring 기능은 허용한다. 외부 시스템 호출과 짧은 DB 트랜잭션을 분리해야 하는 유스케이스는 외부 흐름을 조율하는 Service와 트랜잭션을 수행하는 별도 Service로 나누어 Spring proxy 경계를 명시한다.

## 이유

HTTP와 파일 업로드 프레임워크 타입을 application에서 제거하면 유스케이스 입력과 테스트가 웹 프레임워크에 결합되지 않는다. 반면 현재 application Service가 사용하는 Spring의 의존성 주입과 선언적 트랜잭션까지 제거하면 별도 transaction port와 composition root가 필요해지고 기존 코드와 다른 구조가 생긴다.

Spring Web 비의존성과 Spring 실행 지원 허용을 구분하면 계층 책임을 명확히 하면서 현재 백엔드 구조와 일관성을 유지할 수 있다. 별도 트랜잭션 Service는 S3 호출을 트랜잭션 밖에 두고 JPA 작업만 짧은 트랜잭션으로 묶으며 동일 객체 내부 호출로 선언적 트랜잭션이 적용되지 않는 문제를 피한다.

## 트레이드오프

Application Service는 Spring annotation에 의존하므로 Spring 없이 그대로 조립하고 실행할 수 있는 완전한 프레임워크 독립 계층은 아니다. 파일 입력 인터페이스와 presentation 어댑터가 추가되어 단순한 값 Dto보다 타입 수가 늘어난다.

트랜잭션 Service 분리로 application 클래스 사이의 호출이 추가된다. 외부 시스템과 DB의 원자성이 자동으로 보장되는 것은 아니므로 각 유스케이스가 실패 보상과 커밋 결과 불명확 상황을 별도로 처리해야 한다.
