# ADR-014: 프론트엔드 테스트에 Vitest와 Testing Library 사용

- 상태: 승인
- 적용 영역: frontend
- 결정일: 2026-09-11

## 결정

`apps/frontend`의 프론트엔드 테스트 runner로 `Vitest`를 사용한다. React hook과 UI 동작은 `@testing-library/react`와 `jsdom` 환경에서 검증한다. 순수 model과 API 계약 테스트도 같은 runner에서 실행하며 Node 기본 `assert`를 계속 사용할 수 있다.

테스트 명령은 `npm test`로 제공한다. 실제 사용자에게 보이는 상태와 상호작용을 우선 검증하고, 네트워크처럼 외부 경계에 해당하는 부분만 통제한다.

## 이유

기존 테스트는 `node:test` 기반 순수 TypeScript 테스트로 model과 API 모듈만 검증한다. 구매자 메인 페이지는 날짜 변경과 컴포넌트 해제 시 요청 취소, 늦은 응답 경쟁 방지, 자정 갱신과 접근 가능한 UI 상태처럼 React 생명주기와 DOM이 필요한 동작을 자동 검증해야 한다.

Vitest는 기존 Vite와 TypeScript 설정을 활용할 수 있고 Testing Library는 구현 구조보다 사용자 관점의 React 동작을 검증하는 데 적합하다.

## 트레이드오프

테스트 전용 dependency와 jsdom 실행 비용이 추가된다. 비동기 React 상태 변경은 테스트별 Query client, timer와 cleanup을 명시적으로 관리해야 한다.

jsdom은 실제 브라우저의 layout과 시각적 반응형 동작을 완전히 재현하지 않으므로 build, static analysis와 실제 브라우저 수동 확인을 함께 수행해야 한다.
