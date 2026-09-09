# ADR-007: 프론트엔드 테스트 기술로 Vitest와 React Testing Library 사용

- 상태: 승인
- 적용 영역: frontend
- 결정일: 2026-09-09

## 결정

프론트엔드의 단위 및 컴포넌트 통합 테스트 runner로 Vitest를 사용한다. React 컴포넌트와 hook은 React Testing Library와 `user-event`로 사용자에게 관찰되는 동작을 검증하고, 브라우저 DOM이 필요한 테스트 환경은 `jsdom`을 사용한다. 네트워크 경계는 우선 Vitest에서 `fetch`를 대역해 검증하며, 실제 필요가 확인되기 전에는 MSW나 E2E 도구를 기본 스택에 포함하지 않는다.

## 이유

현재 판매자 상품 등록은 세 API를 순차 실행하고 부분 성공 뒤 실패한 단계부터 재시도해야 한다. TypeScript build와 lint만으로는 요청 중복 방지, 단계 보존, form 잠금, 서버 오류 연결과 접근성 회귀를 검증할 수 없다.

Vitest는 기존 Vite와 TypeScript 설정을 재사용할 수 있어 별도 변환기 구성이 적고, React Testing Library는 컴포넌트 내부 구현보다 사용자가 확인하는 입력, 상태와 접근성 결과를 중심으로 검증하게 한다. `user-event`는 실제 입력과 클릭에 가까운 상호작용을 제공하고 `jsdom`은 현재 필요한 form 및 DOM API 범위를 충족한다.

## 트레이드오프

개발 dependency와 테스트 설정, 테스트 실행 시간이 추가된다. `jsdom`은 실제 브라우저의 layout, 파일 처리와 네트워크 동작을 완전히 재현하지 못하므로 Vite proxy를 통한 수동 통합 검증이 계속 필요하다. `fetch` 대역은 API 계약을 빠르게 검증할 수 있지만 복잡한 네트워크 시나리오가 늘어나면 관리 비용이 커질 수 있으며, 그 시점에는 MSW 또는 E2E 도구 도입을 별도로 결정해야 한다.
