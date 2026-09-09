# ExecPlan: 판매자 상품 등록 UI 구현

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/prd.md`의 판매자 상품 등록 요구사항, `docs/architecture/frontend.md`, `docs/DESIGN.md`, `docs/architecture/decisions/ADR-003-sale-owns-price.md`, 2026-09-09 판매자 상품 등록 UI 브레인스토밍

이 ExecPlan은 백엔드 API를 호출하지 않고 상품 정보, 상품 이미지와 판매 조건을 한 페이지에서 입력·검증하는 판매자 상품 등록 UI 하나를 구현하는 계획이다. 입력 필드와 검증 규칙은 UI 구상을 위한 임시 계약이며, 상품·이미지·판매 API가 확정되면 request schema와 서버 검증 규칙에 맞춰 변경한다.

## 목적과 인수 기준

이 변경 뒤에는 판매자가 한 페이지에서 상품명과 설명, 여러 이미지, 판매 가격·일정·수량을 입력하고 등록 전 UI 흐름을 검증할 수 있다. 제출은 실제 저장 요청을 보내지 않으며, 모든 임시 검증을 통과했을 때 `상품 등록 UI 검증이 완료되었습니다`라는 안내를 표시한다.

- 화면은 상품 정보, 상품 이미지, 판매 일정 및 수량의 세 섹션과 등록 요약으로 구성된다.
- 상품 이미지 여러 장을 선택해 로컬 preview로 확인하고 대표 이미지를 바꾸거나 개별 삭제할 수 있다.
- 상품명·설명·이미지, 양의 정수 가격·수량, 필수 판매 시각과 종료 시각 순서를 검증한다.
- 검증 오류는 해당 입력과 연결된 한글 메시지로 표시하고 제출 시 첫 오류 입력으로 focus를 이동한다.
- 검증 성공 후 입력값을 유지하며, 입력값이 바뀌면 성공 안내를 해제한다.
- 데스크톱에서는 입력 영역과 오른쪽 등록 요약을 나란히 표시하고, 모바일에서는 모든 영역을 한 열로 표시한다.
- 키보드만으로 입력, 이미지 선택·대표 지정·삭제와 제출을 수행할 수 있다.
- 실제 API, 인증, 서버 저장과 라우팅이 구현됐다고 오해할 코드나 문구를 추가하지 않는다.
- `apps/frontend`에서 `npm run lint`와 `npm run build`가 성공한다.

이번 계획은 실제 API 호출, Query·Mutation, 인증·인가, 판매자 경로 라우팅, 이미지 원격 업로드·압축·재정렬, 서버 오류·재시도, 임시저장, 별도 상품 미리보기 화면, 화면 이탈 방지, 카테고리, 배송·반품 정보, 상품 옵션과 SKU를 포함하지 않는다. 신규 runtime 또는 test dependency도 추가하지 않는다.

## 맥락과 구현 접근

프론트엔드는 React 19, TypeScript, Vite와 TailwindCSS를 사용하지만 현재는 기본 Vite 시작 화면만 있다. `apps/frontend/src/App.tsx`가 starter UI와 상태를 직접 관리하고 `apps/frontend/src/App.css`와 `src/index.css`가 기본 예제 스타일을 제공한다. Router, form, schema validation, server-state와 test 라이브러리는 없다.

`docs/architecture/frontend.md`에 따라 `app → pages → features → shared` 의존성 방향을 지킨다. 페이지는 feature를 조합하고, form 상태와 이벤트 조율은 custom hook에 두며, 도메인 UI는 렌더링과 입력에 집중한다. 현재 화면 하나에만 필요한 요소를 `shared`로 공통화하지 않고, API 계약이 없으므로 `api` 디렉터리도 만들지 않는다.

예상 구조는 다음과 같다.

```text
apps/frontend/src
├── app
│   └── App.tsx
├── pages
│   └── SellerProductRegistrationPage.tsx
├── features
│   └── product-registration
│       ├── model
│       │   ├── productRegistration.ts
│       │   └── productRegistrationValidation.ts
│       ├── hook
│       │   └── useProductRegistration.ts
│       └── ui
│           ├── ProductRegistrationForm.tsx
│           ├── ProductInformationSection.tsx
│           ├── ProductImageSection.tsx
│           ├── ProductSaleSection.tsx
│           └── ProductRegistrationSummary.tsx
├── index.css
└── main.tsx
```

`src/app/App.tsx`는 전역 진입점 역할로 `SellerProductRegistrationPage`만 렌더링한다. `src/main.tsx`는 새 app 경로를 import한다. 기존 root `src/App.tsx`와 `src/App.css`의 starter 화면은 제거하되, 이번 기능과 무관한 public 및 asset 파일은 정리하지 않는다.

`SellerProductRegistrationPage`는 페이지 제목과 API 미연동 안내를 제공하고 `ProductRegistrationForm`을 배치한다. `ProductRegistrationForm`은 `useProductRegistration`을 사용해 세 section과 요약을 연결하며, 각 section은 값·오류·이벤트 callback을 props로 받는다. DOM focus는 UI가 관리하고 hook의 검증 결과가 반환한 첫 오류 field key를 해당 ref에 연결한다.

`docs/DESIGN.md`에 따라 Paper White, Soft Mist, Obsidian, Concrete Gray, Steel과 오류용 Signal을 CSS token으로 선언한다. form, panel과 이미지 surface는 `0px` radius와 shadow 없는 형태로 만들고, 주요 제출 동작에만 Obsidian pill button을 사용한다. 데스크톱은 입력 본문과 오른쪽 summary의 두 열이며, 모바일 breakpoint 아래에서는 한 열로 전환한다. 별도 공통 UI layer 없이 feature UI의 Tailwind class로 구성하고 전역 CSS에는 token과 base style만 둔다.

## 인터페이스와 임시 모델

`productRegistration.ts`는 API request가 아닌 UI 전용 타입을 정의한다. 구현 시 명칭은 코드와 컴파일러 피드백에 따라 조정할 수 있지만 다음 의미는 유지한다.

```ts
type ProductRegistrationDraft = {
  name: string
  description: string
  images: ProductRegistrationImage[]
  primaryImageId: string | null
  price: string
  saleStartsAt: string
  saleEndsAt: string
  quantity: string
}

type ProductRegistrationImage = {
  id: string
  file: File
  previewUrl: string
}

type ProductRegistrationErrors = Partial<
  Record<ProductRegistrationField, string>
>
```

가격과 수량은 빈 입력과 잘못된 숫자 표현을 보존하기 위해 form 상태에서는 문자열로 관리한다. API request 타입이나 `Long` 변환은 만들지 않는다. 이미지 식별자는 대표 이미지 선택과 React key에만 사용하는 UI 내부 값이며 서버 식별자를 의미하지 않는다.

임시 검증 규칙은 다음과 같다.

- 상품명과 설명은 `trim()` 결과가 비어 있지 않아야 한다.
- 이미지가 한 장 이상 있고 `primaryImageId`가 현재 이미지 중 하나를 가리켜야 한다.
- 가격은 1원 이상의 정수 문자열이어야 한다.
- 판매 시작·종료 시각이 모두 유효하고 종료 시각이 시작 시각보다 이후여야 한다.
- 판매 수량은 1 이상의 정수 문자열이어야 한다.

상품명·설명의 최대 길이, 이미지 MIME type·용량·개수, 판매 가능 기간과 최대 수량은 API 계약이 확정되지 않았으므로 임의로 정하지 않는다. 브라우저 입력 요소에 필요한 최소 제약만 사용하고 서버 계약처럼 표현하지 않는다.

가격은 `docs/architecture/decisions/ADR-003-sale-owns-price.md`에 따라 상품 정보가 아니라 판매 일정 및 수량 섹션에 둔다. 같은 상품이 판매 일정마다 다른 가격을 가질 수 있다는 경계를 화면 정보 구조에도 반영한다.

## 상태와 오류 처리

`useProductRegistration`은 하나의 draft, 필드별 오류, 제출 시도 여부와 mock 검증 성공 여부를 가장 좁은 feature 범위에서 관리한다. Context나 전역 상태를 사용하지 않는다. 등록 요약은 같은 draft에서 상품 정보 입력 여부, 이미지 수와 판매 정보 입력 여부를 파생한다.

첫 제출 전에는 입력 중이라는 이유만으로 오류를 노출하지 않는다. 제출하면 순수 함수가 전체 draft를 검증하고 오류 map과 첫 오류 field key를 반환한다. 오류가 있으면 성공 상태를 해제하고 form이 해당 입력으로 focus를 이동한다. 제출 시도 이후에는 변경된 필드만 다시 검증하되, 시작·종료 시각처럼 서로 의존하는 값은 함께 검증한다.

검증을 통과하면 페이지 상단의 접근 가능한 status 영역에 `상품 등록 UI 검증이 완료되었습니다`를 표시하고 입력값을 유지한다. `상품이 등록되었습니다`처럼 실제 저장을 암시하는 문구와 자동 화면 이동은 사용하지 않는다. 이후 입력이나 이미지가 바뀌면 성공 상태를 해제한다.

이미지를 선택할 때 `URL.createObjectURL`로 preview URL을 만든다. 이미지를 삭제할 때 해당 URL을 즉시 revoke하고, hook unmount 시 남은 URL을 모두 revoke한다. React StrictMode에서도 현재 URL만 정리하도록 최신 이미지 목록을 ref로 추적한다. 첫 이미지를 기본 대표로 지정하며, 대표 이미지를 삭제하면 남은 첫 이미지를 대표로 자동 지정하고 이미지가 없으면 `primaryImageId`를 `null`로 바꾼다.

## 작업 계획

### 마일스톤 1: starter 화면을 아키텍처와 디자인 기반으로 교체

`src/app/App.tsx`와 `src/pages/SellerProductRegistrationPage.tsx`를 추가하고 `src/main.tsx`가 새 app entry를 렌더링하도록 변경한다. 기존 root `src/App.tsx`와 `src/App.css`의 starter 화면을 제거한다. `src/index.css`의 Vite 예제 theme, dark mode, accent와 shadow를 JAPDA color·typography·spacing token과 최소 base style로 교체한다. 이 단계의 결과는 API나 form 로직 없이도 판매자 상품 등록 페이지의 제목과 기본 surface가 디자인 문서에 맞게 표시되는 것이다.

`apps/frontend`에서 실행한다.

```bash
npm run lint
npm run build
```

예상 관찰 결과: 두 명령이 종료 코드 0으로 완료되고 starter asset import나 삭제된 stylesheet 참조가 남지 않는다.

### 마일스톤 2: 임시 form 모델과 순수 검증 규칙 완성

`features/product-registration/model`에 draft, image, field와 error 타입을 정의한다. 별도 파일의 순수 검증 함수가 필수 텍스트, 대표 이미지, 양의 정수 가격·수량과 판매 시각 순서를 검증하고 안정적인 첫 오류 field key를 반환하도록 한다. API request, response 또는 server DTO를 만들지 않는다.

현재 test runner가 없으므로 신규 test dependency나 테스트 파일을 추가하지 않는다. TypeScript build로 타입 계약을 확인하고 브라우저 검증 시나리오를 마일스톤 4에서 수동 확인한다.

`apps/frontend`에서 실행한다.

```bash
npm run lint
npm run build
```

예상 관찰 결과: 임시 model과 validation 모듈이 lint와 TypeScript compile을 통과한다.

### 마일스톤 3: form hook과 세 입력 section 구현

`useProductRegistration`에 draft 변경, 제출 시도 이후 재검증, mock 제출 성공 상태와 이미지 object URL 생명주기를 구현한다. `ProductInformationSection`, `ProductImageSection`과 `ProductSaleSection`은 hook 또는 API를 직접 소유하지 않고 props로 전달받은 값과 callback만 사용한다. `ProductRegistrationForm`이 section을 조합하고 field ref와 첫 오류 focus를 관리한다.

이미지 section은 여러 파일 선택, 정사각형 preview, 대표 badge와 지정 button, 개별 삭제를 제공한다. 드래그 재정렬, 파일 압축과 remote upload는 만들지 않는다. 모든 입력에 보이는 label을 제공하고 helper·error text를 입력과 연결하며 keyboard focus를 유지한다.

`apps/frontend`에서 실행한다.

```bash
npm run lint
npm run build
```

예상 관찰 결과: 모든 form 모듈이 lint와 build를 통과하고 브라우저에서 세 입력 section에 값을 입력할 수 있다.

### 마일스톤 4: 등록 요약, 반응형 배치와 전체 흐름 검증

`ProductRegistrationSummary`에 상품 정보 입력 여부, 선택 이미지 수와 판매 정보 입력 여부를 표시하고, 실제 저장이 아닌 `입력 내용 검증` CTA를 제공한다. 데스크톱에서는 세 section과 오른쪽 summary를 두 열로 배치하고 summary를 읽기 쉬운 위치에 유지한다. 모바일에서는 상품 정보, 이미지, 판매 정보, summary와 CTA를 한 열로 배치한다. 검증 성공 안내와 입력 변경 시 성공 해제를 연결한다.

`apps/frontend`에서 실행한다.

```bash
npm run lint
npm run build
npm run dev
```

예상 관찰 결과: lint와 build가 성공하고, 개발 서버에서 아래 수동 검증 항목을 확인할 수 있다. `npm run dev`는 검증하는 동안만 실행하고 완료 후 종료한다.

## 검증

자동화 test runner가 없는 현재 상태를 숨기지 않는다. 구현 완료 시 정적 검증과 browser 수동 검증 결과를 이 문서의 `진행 상황`, `예상 밖의 발견`, `결과와 회고`에 기록한다.

정적 검증은 `apps/frontend`에서 실행한다.

```bash
npm run lint
npm run build
```

예상 관찰 결과: 두 명령이 모두 종료 코드 0으로 완료된다.

개발 서버를 실행해 다음 항목을 확인한다.

- 넓은 화면에서 세 입력 section과 오른쪽 summary가 겹치지 않는다.
- 모바일 너비에서 모든 영역이 한 열로 표시되고 가로 scroll이 생기지 않는다.
- 빈 form을 제출하면 모든 필수 오류가 표시되고 상품명으로 focus가 이동한다.
- 공백뿐인 상품명·설명, 0·음수·소수·숫자가 아닌 가격과 수량을 거부한다.
- 종료 시각이 시작 시각과 같거나 빠르면 시각 오류를 표시한다.
- 여러 이미지를 선택하면 모두 preview되고 대표 이미지를 바꿀 수 있다.
- 일반 이미지를 삭제하면 해당 이미지만 사라지고 대표 이미지는 유지된다.
- 대표 이미지를 삭제하면 남은 첫 이미지가 대표가 되며, 마지막 이미지를 삭제하면 이미지 필수 오류를 표시할 수 있다.
- 제출 시도 후 잘못된 값을 고치면 관련 오류가 사라지고 시각 상호 검증도 갱신된다.
- 모든 값을 유효하게 입력해 제출하면 실제 저장과 구분되는 성공 안내가 나타나고 입력값은 유지된다.
- 성공 후 값을 바꾸면 성공 안내가 사라진다.
- 키보드만으로 모든 입력, 이미지 선택·대표 지정·삭제와 제출을 수행할 수 있다.
- label, helper와 error text가 입력에 연결되고 focus indicator가 보인다.

## 위험과 완화

- 입력 필드와 검증 규칙이 확정 API와 달라질 수 있다. UI 타입에 `Request` 명칭을 사용하지 않고 임시 계약임을 화면 안내와 계획에 기록하며, API 확정 후 model과 validation을 다시 맞춘다.
- 실제 백엔드는 상품 생성 뒤 발급된 `productId`로 이미지와 판매 정보를 각각 연결할 수 있다. 현재 hook에 가상의 API 순서, 보상 삭제나 재시도를 넣지 않고 실제 계약이 확정될 때 별도 연동 설계를 진행한다.
- object URL을 해제하지 않으면 반복적인 이미지 선택에서 메모리가 누적될 수 있다. 삭제와 unmount 경로에서 URL을 revoke하고 StrictMode에서 중복 정리가 생기지 않도록 최신 목록을 추적한다.
- 자동화 테스트가 없어 validation과 이미지 상태 회귀를 자동 검출하지 못한다. 순수 validation 모듈로 경계를 분리하고 이번 범위에서는 명시된 browser 시나리오를 모두 기록해 수동 검증한다. test runner 도입은 별도 결정으로 다룬다.
- starter 파일 삭제와 사용자 변경이 겹칠 수 있다. 구현 직전에 실제 diff와 staged 상태를 다시 확인하고 승인된 프론트엔드 파일만 수정한다.

## 진행 상황

- [x] 2026-09-09 판매자 상품 등록 UI의 저장소 구조, 프론트엔드 아키텍처와 디자인 원칙을 조사했다.
- [x] 2026-09-09 API 미연동 범위, 한 페이지·세 섹션, 임시 검증과 mock 성공 흐름을 사용자와 확정했다.
- [x] 2026-09-09 ADR-003을 확인하고 가격을 판매 일정 및 수량 section에 배치했다.
- [x] 2026-09-09 마일스톤 1을 구현하고 `npm run lint`, `npm run build` 성공을 확인했다.
- [x] 2026-09-09 마일스톤 2를 구현하고 `npm run lint`, `npm run build` 성공을 확인했다.
- [x] 2026-09-09 마일스톤 3을 구현하고 `npm run lint`, `npm run build` 성공을 확인했다.
- [x] 2026-09-09 마일스톤 4를 구현하고 정적 검증 및 headless browser 전체 흐름 검증을 완료했다.

## 예상 밖의 발견

- 관찰: 현재 프론트엔드는 Vite starter 화면뿐이며 Router, form, schema validation, server-state와 test dependency가 없다.
  근거: `apps/frontend/package.json`, `apps/frontend/src/App.tsx`, `apps/frontend/src/index.css`.
- 관찰: 승인된 ADR에 따라 가격은 `Product`가 아니라 `Sale`이 소유한다.
  근거: `docs/architecture/decisions/ADR-003-sale-owns-price.md`.
- 관찰: 작업 트리에는 이 계획과 무관한 백엔드 계획 및 ADR 변경이 이미 존재한다. 해당 변경을 수정하거나 staging하지 않는다.
  근거: 계획 작성 시점의 `git status --short`.
- 관찰: 프로젝트에는 test runner가 없지만 로컬 Google Chrome의 DevTools Protocol을 사용해 dependency 추가 없이 실제 React 화면의 입력과 반응형 동작을 검증할 수 있었다.
  근거: `npm run dev`와 headless Chrome에서 수행한 1440px·375px 시나리오 결과.
- 관찰: 구현 중 작업 트리에 이 계획과 무관한 백엔드 test 변경이 추가로 나타났다. 해당 변경을 수정하지 않았다.
  근거: 구현 완료 전 `git status --short`.

## 결정 기록

- 결정: 실제 API 없이 입력·검증·이미지 preview와 mock 성공 상태까지 구현한다.
  이유: 이미지와 판매 API 계약을 추측해 만드는 재작업을 피하면서 판매자 UX를 먼저 검증하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상품 정보, 상품 이미지, 판매 일정 및 수량을 한 페이지의 세 section으로 구성한다.
  이유: 백엔드의 저장 경계는 드러내되 판매자에게는 하나의 등록 작업으로 제공하고, 독립 저장 요구가 없는 현재 단계에서 wizard 상태를 추가하지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 가격은 판매 일정 및 수량 section에 둔다.
  이유: `ADR-003-sale-owns-price.md`의 승인된 가격 소유권과 일치시키기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 기능 전용 hook과 React 기본 상태 관리를 사용하고 신규 dependency를 추가하지 않는다.
  이유: 현재 form 복잡도에 충분하고 `docs/architecture/frontend.md`의 form 책임을 지키면서 미확정 API에 대한 추상화를 만들지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 현재 feature 하나를 위해 `api`, `shared` 또는 세 개의 별도 feature를 미리 만들지 않는다.
  이유: 실제 재사용과 API 계약이 없는 상태에서 불필요한 계층과 경계를 만들지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 기존 test runner가 없으므로 이번 UI 계획에 test dependency 도입을 포함하지 않는다.
  이유: 프로젝트 공통 테스트 기술 선택을 단일 UI 기능에 묶지 않고, 정적 검증과 명시적인 browser 수동 검증으로 현재 결과를 확인하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

판매자 상품 등록 UI를 계획한 `app → pages → features` 구조로 구현했다. 상품 정보, 여러 이미지와 대표 이미지, 판매 가격·일정·수량을 한 화면에서 입력할 수 있고, 실제 저장 없이 전체 입력을 검증해 완료 안내를 표시한다. 제출 이후에는 변경된 필드와 상호 의존하는 판매 시각 오류를 다시 계산하며, 대표 이미지 삭제와 object URL 정리도 계획한 생명주기에 맞춰 처리한다.

JAPDA 디자인 토큰을 Tailwind theme와 base style에 반영하고 starter 화면을 제거했다. 화면은 1440px에서 입력 영역과 360px 요약 영역의 두 열로, 375px에서 한 열로 렌더링되며 두 너비 모두 가로 scroll이 생기지 않았다. 입력과 버튼은 native form 요소를 사용하고 label, helper, error text 및 focus indicator를 연결했다.

검증 결과는 다음과 같다.

- `npm run lint`: 성공
- `npm run build`: 성공
- `git diff --check`: 성공
- `npm run dev`: Vite 개발 서버 기동과 페이지·entry 응답 확인
- headless Chrome: 빈 제출 시 7개 필수 오류와 상품명 focus, 잘못된 가격·수량·판매 시각 거부, 이미지 2장 preview, 대표 이미지 변경, 일반·대표·마지막 이미지 삭제, 대표 이미지 자동 승계, 성공 안내와 입력 유지, 변경 시 성공 안내 해제 확인
- headless Chrome: 1440px 두 열, 375px 한 열, 두 너비의 가로 scroll 없음과 page console 오류 없음 확인

계획과 구현 범위의 차이는 없다. 계획대로 신규 runtime·test dependency, API 호출, Router와 원격 이미지 처리는 추가하지 않았다. 자동화 test runner가 없으므로 회귀 테스트 파일은 추가하지 않았고, OS 파일 선택 대화상자 자체의 키보드 조작은 headless 환경에서 실행하지 못했다. 이미지 입력과 대표 지정·삭제는 native file input과 button으로 구현했으며, headless 시나리오에서는 동일한 change·click 경로를 확인했다.
