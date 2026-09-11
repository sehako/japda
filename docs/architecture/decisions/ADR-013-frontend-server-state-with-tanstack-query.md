# ADR-013: 프론트엔드 서버 상태에 TanStack Query 사용

- 상태: 승인
- 적용 영역: frontend
- 결정일: 2026-09-11

## 결정

`apps/frontend`의 서버 상태 조회와 변경에 `@tanstack/react-query` v5를 표준 Query 라이브러리로 사용한다. `QueryClientProvider`는 `app` 계층에서 구성하고, feature의 hook은 Query와 Mutation을 사용해 API 요청 상태와 생명주기를 조율한다. API 모듈은 HTTP 요청과 응답 계약 검증에 집중한다.

Query function이 제공받은 `AbortSignal`은 API 모듈과 공통 API client까지 전달한다. query key에는 응답을 구분하는 입력을 포함한다. cache 유지 시간, 자동 재조회와 retry 정책은 각 API 및 화면의 계약에 맞게 Query별로 명시한다.

구매자 판매 상품 조회부터 이 결정을 적용한다. 기존 판매자 기능의 custom hook 기반 서버 상태 관리는 현재 동작을 유지하며 별도 작업에서 점진적으로 전환한다.

## 이유

프론트엔드 아키텍처는 서버 상태를 Query 라이브러리로 관리하도록 정의하지만 기존 구현에는 표준 라이브러리가 없어 custom hook이 loading, error, cache와 요청 취소를 각각 처리하고 있다. 구매자 메인 페이지는 날짜 변경, 요청 취소, 늦은 응답 경쟁 방지와 명시적 재시도가 필요하므로 이를 공통 라이브러리의 query key, 상태와 취소 계약으로 관리한다.

서버 상태 관리 방식을 표준화하면 이후 주문과 결제 기능에서도 중복 요청 제거, cache 무효화와 Mutation 연계를 같은 방식으로 확장할 수 있다.

## 트레이드오프

`@tanstack/react-query` 런타임 dependency와 전역 Provider 구성이 추가되며 Query의 cache, stale, retry와 자동 재조회 정책을 이해하고 관리해야 한다. 라이브러리 기본값이 화면 계약과 다를 수 있으므로 필요한 Query는 옵션을 명시적으로 설정해야 한다.

기존 판매자 기능을 즉시 전환하지 않으므로 한동안 custom hook 방식과 TanStack Query 방식이 공존한다. 단순한 단일 요청에도 Query 정의와 테스트 Provider가 필요해 초기 코드와 테스트 설정이 늘어난다.
