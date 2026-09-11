# ADR-012: 프론트엔드 클라이언트 라우팅에 React Router 사용

- 상태: 승인
- 적용 영역: frontend
- 결정일: 2026-09-10

## 결정

`apps/frontend`의 클라이언트 라우팅에 `react-router-dom`을 사용한다. `BrowserRouter`와 선언적인 route 구성을 `app` 계층에 두고, `pages` 계층의 페이지를 URL 경로에 연결한다.

최초 route 구성은 다음과 같다.

- `/`는 `/seller/products/new`로 이동한다.
- `/seller/products/new`는 기존 판매자 상품 등록 페이지를 표시한다.
- `/seller/sales/new`는 판매자 판매 일정 등록 페이지를 표시한다.
- 정의하지 않은 경로는 Not Found 페이지를 표시한다.

브라우저에서 하위 경로로 직접 접근하거나 새로고침할 수 있도록 배포 환경은 애플리케이션 경로 요청을 `index.html`로 전달하는 SPA fallback을 제공해야 한다. 배포 플랫폼별 rewrite 설정은 각 배포 환경에서 책임진다.

## 이유

기존 프론트엔드는 단일 페이지를 `App.tsx`에서 직접 렌더링했지만, 판매자 상품 등록과 판매 일정 등록을 각각 독립 URL로 제공해야 한다. URL 이동, 브라우저 뒤로가기, redirect와 Not Found 처리를 직접 구현하지 않고 검증된 라우팅 라이브러리의 일관된 계약을 사용한다.

라우트 선언은 전역 진입점의 책임이므로 기존 `app → pages → features → shared` 의존성 방향과도 일치한다.

## 트레이드오프

`react-router-dom`이라는 새 런타임 dependency와 route 구성이 추가된다. `BrowserRouter`는 정적 빌드만으로 하위 경로의 직접 접근을 보장하지 않으므로 배포 환경에 SPA fallback 설정이 필요하다.

클라이언트 라우팅만 제공하므로 서버 렌더링, route별 서버 데이터 로딩과 배포 플랫폼 설정은 해결하지 않는다. 페이지 수가 적은 현재 구조에는 수동 pathname 분기보다 코드가 늘지만, 페이지가 추가돼도 탐색 동작과 오류 처리를 같은 방식으로 확장할 수 있다.
