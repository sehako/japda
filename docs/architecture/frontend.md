# 프론트엔드 아키텍처 지침

## 기본 구조

기술 스택은 TypeScript + React + TailwindCSS를 사용한다.

프로젝트는 `apps/frontend`에 위치하며 Vite로 개발 서버와 빌드를 실행한다. 패키지는 npm으로 관리하고 TailwindCSS는 공식 Vite 플러그인으로 연동한다. [ADR-001](decisions/ADR-001-frontend-vite.md)을 따른다.

단위 및 컴포넌트 통합 테스트는 Vitest, React Testing Library, `user-event`와 `jsdom`을 사용한다. [ADR-007](decisions/ADR-007-frontend-testing-stack.md)을 따른다.

패키지는 기능 또는 도메인 단위로 구성한다.

```text
src
├── app
├── pages
├── features
└── shared
```

각 feature는 필요에 따라 다음 구조를 가진다.

```text
{domain}
├── api
├── model
├── hook
├── ui
└── util
```

필요하지 않은 디렉터리는 미리 만들지 않는다.

의존성 방향:

```text
app → pages → features → shared
```

---

## app

애플리케이션 전역 설정을 관리한다.

* Router
* Provider
* 환경 및 전역 설정

도메인 로직을 작성하지 않는다.

---

## pages

라우팅 단위의 페이지를 관리한다.

* 여러 feature를 조합한다.
* 페이지 레이아웃과 라우팅 정보를 처리한다.
* 복잡한 API 호출이나 도메인 로직을 직접 작성하지 않는다.

```text
ProductPage
OrderDetailPage
```

---

## features

도메인 또는 기능에 종속된 코드를 관리한다.

특정 feature에서만 사용하는 코드는 해당 feature 내부에 유지한다.

### api

서버 API 호출과 Query / Mutation 정의를 관리한다.

```text
orderApi
orderQuery
orderMutation
```

* `Api`: HTTP 요청
* `Query`: 조회
* `Mutation`: 변경

UI 컴포넌트에서 API를 직접 호출하지 않는다.

### model

도메인 및 API 타입을 관리한다.

```text
Order
CreateOrderRequest
OrderResponse
```

서버 응답과 화면 모델의 의미가 다르면 별도 타입으로 분리한다.

### hook

화면 로직을 관리한다.

* Query / Mutation 사용
* 상태 및 이벤트 처리
* Form 로직
* 여러 API 흐름 조율

커스텀 훅은 `use`로 시작한다.

단순 로직까지 무조건 hook으로 분리하지 않는다.

### ui

도메인 UI 컴포넌트를 관리한다.

* 렌더링과 사용자 입력에 집중한다.
* API를 직접 호출하지 않는다.
* 데이터와 동작은 props 또는 hook을 통해 전달받는다.

### util

해당 feature에서만 사용하는 단순 변환 및 보조 함수를 관리한다.

API 호출, 상태 관리, 비즈니스 흐름은 작성하지 않는다.

---

## shared

특정 도메인에 종속되지 않는 재사용 코드를 관리한다.

예:

* API Client
* Button / Input / Modal
* 공통 Hook
* 공통 Type
* Utility
* Constant

특정 비즈니스 도메인에 종속된 코드는 `shared`에 두지 않는다.

두 곳에서 사용된다는 이유만으로 바로 공통화하지 않는다.

---

## 의존성 규칙

* `shared`는 `features`, `pages`, `app`을 의존하지 않는다.
* `features`는 `pages`, `app`을 의존하지 않는다.
* feature 간 직접 의존은 기본적으로 피한다.
* 여러 feature의 조합은 `pages` 또는 상위 계층에서 처리한다.
* 순환 의존성을 만들지 않는다.

---

## 데이터 흐름

기본 흐름은 다음과 같다.

```text
Page / UI
    ↓
Hook
    ↓
Query / Mutation
    ↓
API
    ↓
Server
```

API 구현 세부사항이 UI까지 노출되지 않도록 한다.

---

## 상태 관리

서버 상태와 클라이언트 상태를 구분한다.

### 서버 상태

예:

* 상품
* 주문
* 결제
* 사용자 정보

Query 라이브러리를 통해 관리한다.

서버 데이터를 별도의 전역 상태에 불필요하게 복제하지 않는다.

### 클라이언트 상태

예:

* Modal
* Tab
* Form
* 임시 UI 상태

가능한 가장 좁은 범위에서 관리한다.

```text
Component State
→ Custom Hook
→ Context / Global State
```

Context나 전역 상태는 실제 공유 필요성이 있을 때만 사용한다.

---

## 컴포넌트 작성 규칙

* 페이지는 feature를 조합한다.
* UI는 렌더링과 사용자 입력에 집중한다.
* 복잡한 API 및 상태 흐름은 hook으로 분리한다.
* 하나의 컴포넌트가 지나치게 많은 책임을 가지지 않도록 한다.
* 단순 컴포넌트를 불필요하게 세분화하지 않는다.

---

## Form / Mutation 규칙

Form 상태는 서버 상태와 분리한다.

```text
Form State
   ↓
Mutation
   ↓
Server
   ↓
Query 갱신
```

Mutation 성공 후 영향을 받는 Query를 갱신하거나 무효화한다.

---

## TailwindCSS

스타일링은 TailwindCSS를 기본으로 한다.

반복되는 UI가 실제로 동일한 역할을 가질 때만 공통 컴포넌트로 분리한다.

특정 도메인에 종속된 UI는 해당 feature에 유지한다.

---

## 테스트

순수 model과 변환 로직은 단위 테스트로 검증한다. React component와 hook은 내부 구현보다 사용자가 관찰하는 입력, 상태, 오류와 접근성 동작을 중심으로 통합 테스트한다.

네트워크 경계는 우선 `fetch` 대역으로 검증한다. 실제 필요가 확인되기 전에는 MSW나 E2E 도구를 기본 의존성으로 추가하지 않는다.

---

## 네이밍 규칙

| 역할      | 규칙                        |
| ------- | ------------------------- |
| 페이지     | `{Domain}Page`            |
| UI 컴포넌트 | `{Domain}{Role}`          |
| 커스텀 훅   | `use{Domain}`             |
| API 모듈  | `{domain}Api`             |
| 조회 정의   | `{domain}Query`           |
| 변경 정의   | `{domain}Mutation`        |
| 요청 타입   | `{Action}{Domain}Request` |
| 응답 타입   | `{Domain}Response`        |
| 도메인 타입  | `{Domain}`                |
| 도메인 유틸  | `{domain}Util`            |

---

## 금지 사항

* 페이지에 복잡한 도메인 로직 작성
* UI에서 API 직접 호출
* 서버 상태를 불필요하게 전역 상태에 복제
* feature 간 순환 의존
* 특정 도메인 코드를 무분별하게 `shared`로 이동
* 단순 props 전달을 피하기 위한 Context 남용
* 단순 상태를 위한 전역 상태 사용
* 모든 로직을 무조건 custom hook으로 추출
* 모든 중복 코드를 즉시 공통화
* 필요성 없는 Wrapper, Mapper, Adapter 생성
