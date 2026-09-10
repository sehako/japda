# ADR-002: 백엔드 API 문서화에 Spring REST Docs 사용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

백엔드 HTTP API 문서는 Spring REST Docs로 생성한다. MockMvc 기반 Presentation Test가 요청과 응답을 검증하면서 문서 스니펫을 생성하고, Asciidoctor가 스니펫을 조합해 HTML 문서를 만든다. 문서 생성은 Gradle `build`에 포함해 테스트가 검증한 API 계약만 문서에 반영되도록 한다.

생성된 스니펫과 HTML 문서는 빌드 산출물로 취급해 Git에 포함하지 않는다. OpenAPI 변환, Swagger UI 같은 대화형 문서와 외부 게시 방식은 필요성이 구체화될 때 별도로 결정한다.

## 이유

API 명세와 실제 구현의 불일치를 줄이고, 성공 응답뿐 아니라 요청 헤더, 응답 필드와 `ProblemDetail` 오류 계약을 실행 가능한 테스트로 검증하기 위해서다. 기존 테스트 전략이 MockMvc 기반 Presentation Test를 사용하므로 문서 생성을 같은 테스트 흐름에 결합할 수 있다.

## 트레이드오프

문서 스니펫을 관리하는 만큼 Presentation Test의 작성 비용과 Gradle 빌드 시간이 증가한다. 테스트되지 않은 예시는 문서화할 수 없으므로 API 변경 시 테스트와 문서를 함께 수정해야 한다. 또한 기본 구성만으로는 대화형 API 탐색이나 OpenAPI 명세를 제공하지 않으므로 해당 기능이 필요하면 별도 도구와 파이프라인을 검토해야 한다.
