# ADR-004: 백엔드 오류 응답에 ProblemDetail 계약 사용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

백엔드 HTTP API 오류 응답은 Spring의 `ProblemDetail`을 사용해 `application/problem+json`으로 제공한다. Spring에 의존하지 않는 공통 오류 계약인 `ErrorCode`, `ErrorCategory`, `BusinessException`은 `global/exception`에 두고, Spring MVC 예외를 HTTP 응답으로 변환하는 `ProblemDetailFactory`와 `GlobalExceptionHandler`는 `global/error`에 둔다.

기능별 오류 코드와 예외는 각 기능의 `exception` 패키지에 두며 Spring HTTP 타입에 의존하지 않는다. 공통 handler는 기능별 예외를 나열하지 않고 `BusinessException`, 요청 형식 오류, 예상하지 못한 오류의 안정적인 범주만 처리한다.

## 이유

기능별 비즈니스 오류와 HTTP 기술을 분리하면서도 모든 API가 일관된 오류 형식을 제공해야 한다. `ProblemDetail`은 표준 오류 필드와 확장 속성을 함께 제공하므로 안정적인 오류 코드와 필드별 오류를 표현할 수 있다. 공통 handler가 도메인별 분기를 알지 않게 하면 새 기능이 추가되어도 공통 오류 처리 구조를 변경하지 않아도 된다.

## 트레이드오프

도메인 오류 계약과 HTTP 변환 계층을 별도로 유지해야 하므로 단순한 API보다 타입과 파일 수가 늘어난다. 공개된 오류 코드와 응답 구조는 클라이언트 계약이 되므로 이후 변경 시 하위 호환성을 고려해야 한다. Spring의 `ProblemDetail`을 HTTP 경계에 사용하므로 프레젠테이션 구현은 Spring MVC에 결합된다.
