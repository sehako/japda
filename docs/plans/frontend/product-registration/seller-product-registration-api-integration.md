# ExecPlan: 판매자 상품 등록 API 연동

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/superpowers/specs/2026-09-09-seller-product-registration-api-integration-design.md`, 2026-09-09 판매자 상품 등록 API 연동 브레인스토밍

이 계획은 기존 판매자 상품 등록 form을 확정된 상품 생성, 이미지 업로드와 판매 등록 API에 연결하는 하나의 종단 간 기능 단위다. 세 백엔드 요청은 내부적으로 순차 실행하지만 사용자에게는 한 번의 상품 등록으로 보이며, 중간 단계가 실패하면 현재 브라우저 세션에서 이미 성공한 요청을 반복하지 않고 실패한 단계부터 재시도한다.

## 목적과 인수 기준

이 변경 뒤에 판매자는 한 페이지에서 상품 정보, 이미지와 판매 조건을 모두 입력하고 실제 상품 등록을 완료할 수 있다. 모든 입력이 서버 제약을 충족하기 전에는 제출할 수 없으며, 등록 중에는 중복 제출과 입력 변경을 막는다. 일부 요청이 성공한 뒤 실패하더라도 화면에 남아 있는 진행 정보로 안전하게 재시도할 수 있다.

- 상품명, 설명, 1~10장의 유효한 이미지, 대표 이미지, 가격, 판매 기간과 수량이 모두 유효해야 `상품 등록` 버튼이 활성화된다.
- 한 번의 제출로 `POST /api/products`, `POST /api/products/{productId}/images`, `POST /api/products/{productId}/sales`가 순서대로 실행된다.
- 세 요청 모두 API 계층 한곳에서 관리하는 임시 `X-Seller-Id: 1` 헤더를 전송한다.
- 제출 중에는 전체 form이 잠기고 사용자는 기술적인 API 단계 대신 `상품 등록 중…` 상태를 본다.
- 이미지 업로드가 실패하면 이미 생성된 상품을 다시 만들지 않고 같은 `productId`로 이미지 업로드부터 재시도한다.
- 판매 등록이 실패하면 상품 생성과 이미지 업로드를 반복하지 않고 판매 등록만 재시도한다.
- 부분 성공 뒤에는 서버에 반영된 section을 잠가 화면 값과 서버 값이 달라지지 않게 한다.
- 서버의 필드 오류는 해당 입력에 연결되고, 일반·네트워크 오류는 접근 가능한 form 오류로 표시된다.
- 전체 성공 뒤에는 입력값과 생성된 상품 ID가 유지된 완료 상태를 표시하며, `새 상품 등록`으로만 form을 초기화한다.
- `apps/frontend`에서 `npm run test`, `npm run lint`, `npm run build`가 모두 성공한다.
- 로컬 Vite proxy를 통해 성공 흐름과 이미지·판매 단계의 부분 실패 재시도를 브라우저에서 확인할 수 있다.

이번 계획에는 실제 인증, 새로고침 뒤 복구, 상품 상세·목록 화면, Router, TanStack Query, 공통 HTTP client, 운영 배포·CORS 변경, 상품 수정·삭제, 이미지 원격 조회와 E2E 도구를 포함하지 않는다.

## 맥락과 구현 접근

- `apps/frontend/src/app/App.tsx`: Router 없이 판매자 상품 등록 페이지 하나를 렌더링한다.
- `apps/frontend/src/pages/SellerProductRegistrationPage.tsx`: 페이지 제목과 API 미연동 안내를 표시하고 등록 form을 배치한다.
- `apps/frontend/src/features/product-registration/model/productRegistration.ts`: `ProductRegistrationDraft`, 이미지, field, 오류와 초기 draft를 정의한다.
- `apps/frontend/src/features/product-registration/model/productRegistrationValidation.ts`: 현재 필수 입력, 양의 정수와 판매 시각 순서를 검증한다. 서버의 길이·파일·미래 시각 제약은 아직 반영하지 않았다.
- `apps/frontend/src/features/product-registration/hook/useProductRegistration.ts`: draft, 검증 상태와 이미지 object URL 생명주기를 관리한다. 현재 `validate()`는 서버 요청 없이 mock 완료 상태만 만든다.
- `apps/frontend/src/features/product-registration/ui/ProductRegistrationForm.tsx`: hook과 세 section을 조합하고 제출 후 첫 오류 focus를 관리한다.
- `apps/frontend/src/features/product-registration/ui/ProductRegistrationSummary.tsx`: 입력 요약과 현재 mock 제출 CTA를 렌더링한다.
- `apps/frontend/vite.config.ts`: React와 Tailwind plugin만 구성되어 있고 API proxy가 없다.
- `apps/frontend/package.json`: React 19, Vite 8과 TypeScript 6을 사용하며 test script와 test dependency가 없다.
- `docs/architecture/frontend.md`: API 호출은 feature의 `api`, 여러 요청 조율과 form 상태는 `hook`, 렌더링은 `ui`에 두도록 정한다.
- `docs/DESIGN.md`: 판매자 화면의 기능적 계층, 무채색 surface, 접근성, 반응형 원칙을 정한다.

기존 `App → Page → ProductRegistrationForm → useProductRegistration` 흐름과 세 입력 section을 유지한다. 새 `features/product-registration/api`가 native `fetch` 요청과 응답 해석을 소유하고, model은 API DTO, 화면 draft 변환과 제출 상태 타입을 소유한다. hook은 client form 상태와 세 변경 요청을 조율한다. 현재 한 feature에서만 필요한 API 코드이므로 `shared`로 공통화하지 않는다.

사용자에게 보이는 등록 동작은 하나지만 hook은 상품 생성 완료 여부, `productId`, 이미지 업로드 완료 여부와 실패 단계를 보존한다. 재시도할 때 완료 상태를 기준으로 앞선 요청을 건너뛴다. 전체 성공 뒤에는 화면 내 완료 상태를 사용하고 상세 화면 이동은 후속 기능으로 남긴다.

## 인터페이스와 의존성

### 프론트엔드 API 경계

`features/product-registration/api/productRegistrationApi.ts`에 다음 의미의 독립 요청 함수를 둔다. 구현 중 명칭은 기존 naming 규칙과 컴파일러 피드백에 따라 조정할 수 있지만 책임과 계약은 유지한다.

```ts
createProduct(request: CreateProductRequest): Promise<ProductResponse>
uploadProductImages(
  productId: number,
  request: UploadProductImagesRequest,
): Promise<ProductImageUploadResponse>
createSale(productId: number, request: CreateSaleRequest): Promise<SaleResponse>
```

모든 함수는 상대 `/api` 경로와 임시 `X-Seller-Id: 1`을 사용한다. JSON 요청은 적절한 `Content-Type`을 보내고 이미지 요청은 브라우저가 boundary를 설정하도록 `FormData`만 전달한다. 성공이 아닌 응답은 본문을 `ProblemDetail`로 해석하되 잘못되거나 비어 있는 응답도 안전한 feature 오류로 변환한다.

API 및 제출 타입은 `features/product-registration/model`에 둔다.

- `CreateProductRequest`: `name`, `description`
- `ProductResponse`: 최소 `id`, `sellerId`, `name`, `description`, `status`, `createdAt`
- `UploadProductImagesRequest`: 원본 `File[]`, 0-based `representativeIndex`
- `ProductImageUploadResponse`: `productId`, `status`, `images`; 각 이미지는 `id`, `sortOrder`, `representative`, `contentType`, `sizeBytes`
- `CreateSaleRequest`: numeric `price`, `quantity`, offset 포함 `startsAt`, `endsAt`
- `SaleResponse`: `id`, `productId`, `price`, `initialQuantity`, `remainingQuantity`, `startsAt`, `endsAt`, `status`, `createdAt`
- `ProblemDetail`: `title`, `status`, `detail`, `instance`, 선택적인 `errors`
- 제출 진행 상태: 생성된 `productId`, 완료 단계, pending, 실패와 전체 성공을 구분할 수 있는 discriminated union 또는 동등하게 배타적인 타입

서버 필드명은 UI field로 명시적으로 변환한다. `name`, `description`, `price`, `quantity`는 같은 이름으로, `startsAt`과 `endsAt`은 `saleStartsAt`과 `saleEndsAt`으로, `files`와 `representativeIndex`는 `images`로 연결한다. `request`, `sellerId`, `productId`와 대응할 수 없는 key는 form 수준 오류로 처리한다.

### 확정된 백엔드 계약

상품 생성은 `POST /api/products`에 `{name, description}`을 보내며 성공 시 `201`과 상품 `id`를 반환한다. 상품명은 Unicode 공백 제거 뒤 1~100 code point, 설명은 1~5,000 code point다. 요청은 멱등하지 않다.

이미지 업로드는 `POST /api/products/{productId}/images`의 multipart 요청이다. `files` part 1~10개와 0-based `representativeIndex`를 전송한다. JPEG, PNG, WebP만 허용하며 개별 10 MiB, 전체 50 MiB 이하여야 한다. 응답은 이미지 URL 없이 metadata와 `READY` 상태를 반환한다.

판매 등록은 READY 상품에 `POST /api/products/{productId}/sales`로 `{price, quantity, startsAt, endsAt}`을 보낸다. 가격과 수량은 양의 정수이고 시각은 offset 포함 ISO-8601이다. 종료는 시작보다 늦고 미래여야 한다.

세 API의 오류는 `application/problem+json`을 사용하며 상황에 따라 `400`, `404`, `409`, `413`, `415`, `503` 또는 `500`을 반환한다. 이미지 실제 signature 검증은 서버가 수행하며 프론트엔드는 선언 MIME, 개수와 크기만 사전 검증한다.

### 새 개발 의존성

`docs/architecture/decisions/ADR-007-frontend-testing-stack.md`에 따라 다음 개발 dependency를 추가한다.

- `vitest`
- `@testing-library/react`
- `@testing-library/user-event`
- `@testing-library/jest-dom`
- `jsdom`

`package.json`에는 CI용 `test` script를 `vitest run`으로 추가한다. 반복 개발용 watch script는 실제 사용 필요가 확인되는 경우에만 추가한다. MSW와 E2E dependency는 추가하지 않고 Vitest의 `fetch` 대역으로 API 경계를 검증한다.

## 작업 계획

### 마일스톤 1: 테스트 기반과 확정 계약 검증을 만든다

먼저 `apps/frontend/package.json`과 `package-lock.json`에 승인된 테스트 dependency와 `test` script를 추가한다. Vite 설정 또는 별도 Vitest 설정에 `jsdom` 환경과 `apps/frontend/src/test/setup.ts`를 연결하고 `@testing-library/jest-dom/vitest` matcher를 준비한다. 설정 파일을 추가한다면 Vite plugin 및 alias를 중복 정의하지 않고 현재 구성을 재사용한다.

아직 구현되지 않은 서버 계약을 표현하는 실패 테스트부터 작성한다. `productRegistrationValidation.test.ts`에서 Unicode code point 길이, 이미지 MIME·개수·개별·전체 크기, 대표 이미지, 양의 정수, 판매 기간과 미래 종료 조건을 검증한다. draft를 상품·이미지·판매 요청으로 바꾸는 model 변환 테스트에서 trim 정책, 파일 순서, 대표 index와 `datetime-local`의 offset 포함 ISO-8601 변환을 고정한다.

그 테스트를 통과하도록 `productRegistration.ts`, `productRegistrationValidation.ts`와 필요한 feature model/util 파일을 최소 범위로 변경한다. 이미지 제한을 넘는 파일을 선택했을 때 object URL을 만들거나 draft에 추가하지 않도록 hook과 검증 책임의 경계를 명확히 한다.

`apps/frontend`에서 실행한다.

    npm run test
    npm run lint
    npm run build

예상 관찰 결과: 새 테스트 runner가 실행되고 model 테스트가 모두 통과하며 기존 앱도 lint와 TypeScript/Vite build를 통과한다.

### 마일스톤 2: API 경계를 테스트 우선으로 구현한다

`features/product-registration/api/productRegistrationApi.test.ts`를 먼저 추가한다. 각 요청이 올바른 상대 URL, method, `X-Seller-Id: 1`, JSON body 또는 `FormData`를 만드는지 검증한다. 이미지 파일 순서와 `representativeIndex`를 확인하고, 성공 응답을 반환하는 경우와 `ProblemDetail`, 비정상 JSON, 빈 응답, 네트워크 실패를 feature 오류로 변환하는 경우를 포함한다.

실패 테스트를 확인한 뒤 `productRegistrationApi.ts`를 구현한다. 상품·이미지·판매 함수를 서로 독립적으로 유지하고 세 요청의 순차 조율은 API 모듈에 넣지 않는다. 응답 상태별 사용자 문구 결정도 API 모듈에 섞지 않고 구조화된 오류 정보만 hook에 전달한다.

`vite.config.ts`에는 개발 서버의 `/api` 요청을 `http://localhost:8080`으로 전달하는 proxy를 추가한다. 클라이언트 코드는 환경별 절대 URL을 알지 않고 운영 same-origin에서도 같은 상대 경로를 사용한다.

`apps/frontend`에서 실행한다.

    npm run test -- productRegistrationApi
    npm run test
    npm run lint
    npm run build

예상 관찰 결과: 세 API 요청 계약과 오류 해석 테스트가 통과하고 Vite config가 타입 검사와 build를 통과한다.

### 마일스톤 3: 단일 제출과 부분 실패 재시도를 연결한다

`useProductRegistration`의 mock `isValidationComplete`와 동기 `validate()` 제출을 실제 비동기 등록 상태로 교체한다. 먼저 hook 또는 form 통합 테스트를 작성해 다음 상태 전이를 고정한다.

- 유효한 form의 첫 제출에서 상품, 이미지, 판매 API가 정확히 한 번씩 순서대로 호출된다.
- pending 동안 전체 form과 CTA가 잠기며 빠른 반복 클릭이 추가 요청을 만들지 않는다.
- 상품 실패 뒤에는 전체 form을 수정하고 상품부터 재시도한다.
- 이미지 실패 뒤에는 `productId`가 유지되고 상품 정보가 잠기며 이미지부터 재시도한다.
- 판매 실패 뒤에는 상품 정보와 이미지가 잠기며 판매만 재시도한다.
- 성공하면 입력과 상품 ID를 유지하고 `새 상품 등록`이 모든 로컬 상태와 object URL을 정리한다.

테스트를 통과하도록 hook에 제출 orchestration과 상태를 구현한다. 매 제출 시 현재 draft 전체를 다시 검증하고, 완료되지 않은 첫 단계부터 실행한다. API 성공 직후 해당 완료 상태와 `productId`를 저장해 후속 요청 실패가 앞선 결과를 잃지 않게 한다. unmount나 새 상품 초기화 시 기존 이미지 object URL 정리 동작을 보존한다.

서버 `errors`를 UI field에 매핑하고 수정 가능한 field의 오류만 재검증한다. 이미 완료되어 잠긴 section의 서버 오류는 form 수준 안내로 유지한다. 네트워크 및 알 수 없는 오류는 사용자가 재시도할 수 있는 한글 메시지로 표현한다.

`apps/frontend`에서 실행한다.

    npm run test -- ProductRegistrationForm
    npm run test
    npm run lint
    npm run build

예상 관찰 결과: 성공 및 각 부분 실패 테스트에서 앞선 API가 중복 호출되지 않고 기존 정적 검증도 통과한다.

### 마일스톤 4: 하나의 등록 작업으로 보이는 UI를 완성한다

`ProductRegistrationForm`, 세 section과 `ProductRegistrationSummary`의 동작 테스트를 먼저 보강한다. 전체 draft가 유효하기 전 CTA가 비활성화되고 요약 영역이 미완료 또는 유효하지 않은 section을 알려야 한다. pending 중에는 `상품 등록 중…`만 노출하고 API 단계명은 표시하지 않는다. 실패 시에는 수정 가능한 section만 활성화하고 `다시 시도`를 제공한다. 전체 성공 시 상품 ID, 완료 안내와 `새 상품 등록`을 제공한다.

테스트에 맞춰 각 section에 필요한 `disabled` 또는 동등한 잠금 prop을 추가하고 native form control의 disabled 상태를 사용한다. 기존 label, helper, `aria-describedby`, `aria-invalid`, focus indicator를 보존한다. form 수준 status와 오류는 `aria-live`로 알리고 필드 오류가 있으면 기존 ref map을 사용해 첫 오류로 focus를 이동한다.

`SellerProductRegistrationPage`의 API 미연동 안내를 실제 등록 화면 설명으로 바꾼다. 기존 `docs/DESIGN.md`의 색상, square surface, 주요 CTA pill, 데스크톱 sticky summary와 모바일 한 열 배치를 유지하며 Signal 색상은 오류 상태에만 사용한다.

`apps/frontend`에서 실행한다.

    npm run test
    npm run lint
    npm run build
    npm run dev

예상 관찰 결과: 자동 검증이 모두 성공하고 개발 서버에서 한 번의 등록, pending, 부분 실패 재시도와 완료 화면이 반응형·키보드 환경에서도 동작한다. `npm run dev`는 수동 확인 동안만 실행하고 완료 후 종료한다.

## 검증

자동 검증은 `apps/frontend`에서 수행한다.

    npm run test
    npm run lint
    npm run build

예상 관찰 결과: Vitest가 model, API와 form 통합 테스트를 모두 통과하고, oxlint가 오류 없이 종료되며, `tsc -b && vite build`가 production bundle을 만든다.

백엔드를 `localhost:8080`에서 실행하고 프론트엔드에서 `npm run dev`를 실행해 다음을 수동 검증한다.

- 필수값이 비었거나 상품명·설명 길이, 이미지 형식·개수·용량, 대표 이미지, 가격·수량 또는 기간이 잘못되면 CTA가 비활성화되고 원인을 확인할 수 있다.
- 유효한 입력으로 한 번 제출하면 폼이 잠기고 완료될 때까지 `상품 등록 중…`이 표시된다.
- 성공하면 입력값과 생성된 상품 ID가 보존되며 새 상품 등록을 누르면 초기 form으로 돌아간다.
- 이미지 요청을 실패시킨 뒤 재시도하면 상품이 중복 생성되지 않고 이미지와 판매 요청만 진행된다.
- 판매 요청을 실패시킨 뒤 재시도하면 상품과 이미지 요청이 반복되지 않는다.
- `ProblemDetail.errors`가 상품, 이미지와 판매 입력에 연결되고 일반 오류는 `aria-live` 영역에 표시된다.
- 실패 안내에는 새로고침하면 현재 재시도 정보를 복구할 수 없다는 제한이 포함된다.
- 1440px와 375px 너비에서 레이아웃이 겹치거나 가로 scroll이 생기지 않는다.
- 키보드만으로 입력, 대표 이미지 선택, 제출, 재시도와 새 상품 등록을 수행할 수 있다.
- 브라우저 console에 처리되지 않은 promise rejection, React warning 또는 object URL 관련 오류가 없다.

실제 S3 또는 실패 응답을 재현할 수 없는 환경이라면 자동화된 API/form 테스트를 해당 시나리오의 주 검증 근거로 기록하고, 수행하지 못한 수동 항목과 이유를 `결과와 회고`에 남긴다.

## 위험과 완화

- 세 요청은 하나의 서버 transaction이 아니다. hook이 완료 단계와 `productId`를 즉시 보존하고 완료된 section을 잠가 중복 상품과 화면·서버 불일치를 줄인다.
- 페이지 새로고침 시 메모리 상태를 잃는다. 조회 API가 없는 동안 영속 복구를 추측해 구현하지 않고 실패 안내에 새로고침 제한을 명시한다.
- 상품 생성 성공 뒤 사용자가 이탈하면 DRAFT 상품이 남을 수 있다. 현재 API에 삭제·조회 계약이 없으므로 보상 요청을 만들지 않으며 이 운영상 제한을 결과에 기록한다.
- 임시 `sellerId = 1`은 인증이 아니다. API 계층 한곳에만 두고 UI나 여러 요청 조율 코드에 복제하지 않아 실제 인증 도입 때 교체 범위를 제한한다.
- `datetime-local`은 offset이 없다. 변환 단위 테스트로 브라우저 로컬 시각이 서버에 전달되는 실제 instant와 일치하는지 고정한다.
- 브라우저 MIME은 신뢰할 수 없다. 클라이언트 검증은 빠른 피드백 용도로만 사용하고 서버의 signature 검증 오류를 이미지 field에 연결한다.
- `jsdom`은 layout과 실제 네트워크를 재현하지 않는다. 자동화 테스트에 더해 Vite proxy를 통한 브라우저 검증을 수행한다.
- 신규 dependency 설치와 `package-lock.json` 변경이 필요하다. 실행 전에 dependency 설치 승인을 받고 승인된 테스트 도구만 추가한다.
- 작업 트리에 이미 존재하는 backend와 `.codex/rules/common.rules` 변경은 사용자 작업이다. 구현·staging·commit에서 수정하거나 포함하지 않는다.

## 복구와 롤백

프론트엔드 변경은 backend schema나 저장 데이터를 변경하지 않는다. 마일스톤별로 test, lint와 build를 통과시킨 뒤 다음 단계로 진행한다. 부분 구현을 되돌려야 하면 승인된 프론트엔드 변경 파일만 대상으로 하고 사용자 작업이나 backend 변경을 건드리지 않는다.

로컬 개발 proxy가 환경과 맞지 않으면 API 호출 코드의 상대 경로는 유지하고 `vite.config.ts`의 proxy 설정만 조정할 수 있다. 테스트 환경이 build 설정과 충돌하면 runtime 코드를 우회하지 말고 Vitest 설정과 TypeScript 포함 범위를 수정해 `npm run test`, `npm run lint`, `npm run build`가 함께 통과하는 상태로 복구한다.

## 진행 상황

- [x] 2026-09-09 07:11Z 기존 프론트엔드 구조, 확정된 백엔드 API 계약, 테스트 환경과 계획 문서 관례를 조사했다.
- [x] 2026-09-09 07:11Z 제출 UX, 임시 판매자 ID, 부분 실패 재시도, 성공 상태, 연결 방식과 테스트 기술을 사용자와 확정했다.
- [x] 2026-09-09 07:11Z 승인된 설계를 바탕으로 초기 ExecPlan을 작성했다.
- [x] 2026-09-09 07:30Z 마일스톤 1을 구현하고 model 테스트 11개, lint와 production build로 검증했다.
- [x] 2026-09-09 07:33Z 마일스톤 2를 구현하고 API 테스트 7개, 전체 테스트 18개, lint와 production build로 검증했다.
- [x] 2026-09-09 07:46Z 마일스톤 3을 구현하고 form 통합 테스트로 순차 제출, 중복 방지, 부분 실패 재시도와 초기화를 검증했다.
- [x] 2026-09-09 07:46Z 마일스톤 4를 구현하고 CTA 상태, section 잠금, 오류 focus, 완료 및 재시도 UI를 검증했다.
- [x] 2026-09-09 07:46Z 전체 테스트 25개, lint, production build와 개발 서버 HTTP 응답을 검증하고 수행하지 못한 수동 항목을 기록했다.

## 예상 밖의 발견

- 관찰: ExecPlan이 출처로 참조한 `docs/superpowers/specs/2026-09-09-seller-product-registration-api-integration-design.md`는 현재 저장소에 없지만, ExecPlan에는 구현 계약과 승인된 결정이 자급자족하도록 기록되어 있다.
  근거: 계획 실행 시점의 파일 조회와 `bee84b6` 커밋 내용.
- 관찰: 프로젝트의 TypeScript `erasableSyntaxOnly` 설정은 constructor parameter property 문법을 허용하지 않는다.
  근거: 마일스톤 2 첫 build의 `TS1294`와 명시적 class field로 변경한 뒤 성공한 build.
- 관찰: 부분 실패 뒤 수정 가능한 field를 변경할 때 실패 단계를 함께 지우면 완료 단계는 보존되어도 CTA가 `상품 등록`으로 돌아가 재시도 의미가 흐려진다.
  근거: 판매 실패 뒤 가격을 수정하는 form 회귀 테스트의 RED-GREEN 결과.
- 관찰: 최종 검증 시 backend가 `localhost:8080`에서 실행 중이지 않아 Vite proxy를 통한 실제 API 요청과 브라우저 종단 간 확인을 수행할 수 없었다.
  근거: `curl http://localhost:8080/api/products` 연결 실패. Vite 개발 서버 자체는 `http://127.0.0.1:5173/`에서 `200` 응답을 확인했다.
- 관찰: 기존 UI는 실제 API가 확정되면 별도 연동 설계를 진행하도록 의도적으로 API, 오류와 재시도를 제외했다.
  근거: `docs/plans/frontend/001-seller-product-registration-ui.md`의 범위와 결과.
- 관찰: 상품 등록은 상품 생성, 이미지 업로드와 판매 등록의 세 요청으로 분리되며 상품 생성은 멱등하지 않다.
  근거: `ProductController.create`, `ProductImageController.upload`, `SaleController.create`와 관련 backend 테스트.
- 관찰: 이미지 업로드 성공 전에는 상품이 DRAFT이고 성공 뒤 READY가 되어야 판매를 등록할 수 있다.
  근거: `ProductImageService.validateInitialProduct`, `SaleExceptionHandler.handleProductNotReady`.
- 관찰: 현재 프론트엔드에는 test runner, HTTP client, Query 라이브러리, Router, API base URL 또는 Vite proxy가 없다.
  근거: `apps/frontend/package.json`, `apps/frontend/vite.config.ts`, `apps/frontend/src` 파일 목록.
- 관찰: 이미지 업로드 응답에는 원격 URL이 없어 전체 등록 성공 뒤 서버 이미지를 다시 표시할 수 없다.
  근거: `ProductImageUploadResponse`, `docs/architecture/decisions/ADR-005-product-image-storage.md`.
- 관찰: 계획 작성 시 작업 트리에 이 범위와 무관한 `.codex/rules/common.rules`와 backend 변경이 존재한다.
  근거: 2026-09-09 계획 작성 시점의 `git status --short`.

## 결정 기록

- 결정: 기존 단일 form에서 세 API를 순차 실행하고 사용자에게 하나의 등록 작업으로 표시한다.
  이유: 기존 화면 구조를 유지하면서 backend 저장 순서를 충족하고 기술적인 단계 노출을 피하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: API 계층 한곳에서 임시 `sellerId = 1`을 관리한다.
  이유: 현재 인증 계약 없이 필요한 header를 가장 작은 교체 경계에 두기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 부분 성공 뒤 완료 상태와 `productId`를 메모리에 보존하고 실패한 단계부터 재시도한다.
  이유: 상품 생성이 멱등하지 않고 이미지 최초 업로드가 한 번만 허용되어 전체 재실행이 중복 또는 충돌을 만들기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 완료된 section을 잠그고 새로고침 뒤 복구는 제외한다.
  이유: 수정·조회 API가 없는 상태에서 화면 값과 이미 저장된 서버 값을 다르게 만들거나 근거 없는 복구 흐름을 만들지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 전체 성공 뒤 현재 화면에서 완료 상태와 상품 ID를 표시한다.
  이유: 상품 상세 화면과 Router가 없는 현재 빈 경로로 이동하지 않고 등록 결과를 명확히 확인하게 하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 전체 draft가 서버 제약을 충족할 때만 CTA를 활성화하고 요청 중에는 전체 form을 잠근다.
  이유: 사용자에게 하나의 원자적인 등록 동작으로 보이게 하고 유효하지 않은 후속 입력 때문에 부분 성공이 발생할 가능성을 줄이기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: native `fetch`와 feature 내부 API 모듈을 사용한다.
  이유: 현재 한 feature의 mutation 흐름에 충분하며 공통 client 또는 Query 라이브러리를 미리 도입하지 않기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 상대 `/api`와 Vite 개발 proxy를 사용하고 운영은 same-origin을 전제로 한다.
  이유: 클라이언트에서 환경별 절대 URL과 CORS 결정을 분리하면서 로컬 프론트엔드와 backend를 연결하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: Vitest, React Testing Library, `user-event`와 `jsdom`을 테스트 기반으로 도입한다.
  이유: 여러 API의 순서, 부분 성공 재시도, form 잠금, 오류와 접근성을 lint와 build만으로 검증할 수 없기 때문이다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

판매자 상품 등록 form을 상품 생성, 이미지 업로드와 판매 등록 API에 연결했다. native `fetch` 경계는 상대 `/api` 경로와 공통 `X-Seller-Id: 1` 헤더를 사용하며 JSON, multipart, `ProblemDetail`, 비정상 응답과 네트워크 실패를 feature 내부에서 처리한다. Vite 개발 proxy는 `/api`를 `http://localhost:8080`으로 전달한다.

hook은 성공한 단계와 `productId`를 즉시 보존한다. 이미지 실패 뒤에는 상품 생성을 건너뛰고, 판매 실패 뒤에는 상품 생성과 이미지 업로드를 건너뛴다. 완료된 section과 pending 중 전체 form은 native `disabled`로 잠긴다. 서버 field 오류는 수정 가능한 입력에 연결하며 일반 오류와 잠긴 field 오류는 form alert로 안내한다. 전체 성공 뒤 입력과 상품 ID를 유지하고 `새 상품 등록`에서 draft, 진행 상태와 object URL을 정리한다.

Vitest, React Testing Library, `user-event`, `jest-dom`과 `jsdom`을 추가했다. model과 API 단위 테스트 및 form 통합 테스트 총 25개가 통과했다. `npm run lint`와 `npm run build`도 오류와 경고 없이 통과했고 `git diff --check`가 성공했다. `npm run dev -- --host 127.0.0.1`은 정상 기동했으며 루트 문서가 HTTP `200`으로 응답했다.

계획과 달리 실제 backend 연동, 1440px·375px layout, 키보드 전용 조작과 브라우저 console은 backend 미실행 및 브라우저 자동화 도구 부재로 수동 확인하지 못했다. 성공, pending, 이미지·판매 부분 실패, 서버 field 오류, 입력 잠금과 object URL 정리는 자동화된 form 테스트를 주 검증 근거로 삼았다. 실제 S3 오류와 서버 응답을 포함한 브라우저 검증은 backend 실행 환경에서 후속 확인이 필요하다.

구현 범위나 승인된 아키텍처 결정은 변경하지 않았다. 작업 시작 시 이미 존재하던 `docs/architecture/frontend.md`, `docs/architecture/decisions/README.md`, `docs/architecture/decisions/ADR-007-frontend-testing-stack.md` 변경은 수정하지 않고 보존했다.
