# 구매자 판매 상품 상세 페이지 설계

## 목적과 완료 조건

구매자가 메인 페이지의 판매 상품을 선택해 해당 판매 일정의 상품 상세 정보를 확인할 수 있는 페이지를 제공한다. 상단에서는 대표 이미지와 구매 판단에 필요한 핵심 정보를 빠르게 보여주고, 하단에서는 상품 설명과 추가 이미지를 긴 세로 흐름으로 제공한다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 메인 페이지의 상품 카드 전체를 선택하면 해당 `saleId`의 `/sales/:saleId`로 이동한다.
- `/sales/:saleId`에 직접 접근해도 같은 상세 페이지가 표시된다.
- `GET /api/sales/{saleId}`로 상세 정보를 조회하고 전체 성공 응답을 런타임에서 검증한다.
- 상단에 대표 이미지 한 장, 판매 상태, 상품명, 가격, 판매 기간과 구매하기 버튼을 표시한다.
- 구매하기 버튼은 `ON_SALE`에서만 활성화하고, UI만 제공하며 주문 요청, 화면 이동, modal 또는 다른 동작을 수행하지 않는다.
- 하단에 상품 설명과 대표 이미지를 제외한 추가 이미지를 세로로 이어서 표시한다.
- loading, 잘못된 경로, 상품 미존재, 일반 오류와 재시도 상태를 구분한다.
- 모바일과 데스크톱에서 핵심 정보와 긴 상세 콘텐츠를 읽을 수 있다.
- 기존 구매자 메인, 판매자 페이지와 Not Found 경로가 계속 동작한다.
- 관련 프론트엔드 테스트, lint와 build가 통과한다.

## 기존 구조와 선행 계약

프론트엔드는 `apps/frontend`의 Vite, React, TypeScript, TailwindCSS, React Router와 TanStack Query 구성을 유지한다. [프론트엔드 아키텍처](../../../architecture/frontend.md)와 [디자인 지침](../../../DESIGN.md)을 따른다.

[구매자 판매 상품 상세 HTML 목업](../../../mockups/buyer-sale-product-detail.html)을 화면의 구현 기준 목업으로 사용한다. 구현 결과는 목업의 정보 구조, 문구, 컴포넌트 배치, 여백, 색상, 타이포그래피와 반응형 전환을 재현한다. 목업의 상품 데이터와 외부 이미지 URL은 시각적 예시이며 실제 구현에서는 이 문서의 API 계약과 이미지 URL 구성을 따른다. 목업과 이 문서의 동작 또는 API 요구사항이 충돌하면 이 문서를 우선한다.

메인 페이지의 목록과 카드 구조는 [구매자 메인 페이지 설계](buyer-main-page.md)를 기반으로 한다. 이 문서는 기존 설계의 비상호작용 상품 카드와 상품 상세 제외 범위를 상세 페이지 이동에 한해 확장한다. 달력, 날짜별 목록 조회와 로그인 버튼 동작은 변경하지 않는다.

상세 데이터는 [구매자 판매 상품 상세 조회 API](../../backend/sale/buyer-sale-product-detail-api.md)를 사용한다. 같은 상품이 여러 판매 일정에 등록될 수 있으므로 상세 조회와 URL에는 `productId`가 아닌 목록 응답의 `saleId`를 사용한다.

상세 API의 `quantity`는 판매 일정 등록 당시의 최초 판매 수량이며 남은 재고가 아니다. API에는 잔여 재고, `SOLD_OUT`과 실제 구매 가능 여부가 없으므로 화면은 수량을 표시하거나 재고를 기반으로 구매 가능 상태를 추론하지 않는다. 버튼의 활성 상태는 판매 상태만 기준으로 한다.

## 범위

### 포함

- `/sales/:saleId` 구매자 판매 상품 상세 route
- 메인 페이지 상품 카드의 상세 페이지 링크
- 공개 판매 상품 상세 조회와 런타임 응답 검증
- 대표 이미지 한 장과 핵심 판매 정보로 구성한 상단 영역
- 상품 설명과 추가 이미지로 구성한 세로형 상세 영역
- 동작 없는 구매하기 버튼 UI
- 상세 조회의 loading, 잘못된 경로, 미존재, 일반 오류와 재시도 상태
- 이미지 기준 URL 결합과 이미지별 대체 표시
- 판매 시작 또는 종료 경계의 상세 상태 갱신
- 라우팅, API 계약, hook과 주요 화면 상태 검증

### 제외

- 주문 생성 API 호출과 구매 처리
- 구매하기 버튼의 화면 이동, modal, event handler와 로그인 확인
- 결제, 재고 예약·차감과 동시성 처리
- 잔여 수량, `SOLD_OUT`, LOW STOCK과 구매 가능 여부
- 상품 옵션과 구매 수량 선택
- 이미지 carousel, thumbnail 선택, 확대와 lightbox
- rich text editor, Markdown, HTML 또는 구조화된 상세 콘텐츠 block
- countdown과 클라이언트의 판매 상태 계산
- 상품 검색, 추천, 후기와 문의
- 로그인과 인증 상태 관리
- 백엔드 API와 데이터베이스 변경
- 새로운 dependency와 공통 디자인 시스템 도입

## 접근 경로와 탐색

`app/router.tsx`에서 다음 route를 관리한다.

| 경로 | 동작 |
| --- | --- |
| `/` | 기존 `BuyerMainPage` 표시 |
| `/sales/:saleId` | 신규 `BuyerSaleDetailPage` 표시 |
| `/seller/products/new` | 기존 `SellerProductRegistrationPage` 표시 |
| `/seller/sales/new` | 기존 `SellerSaleSchedulingPage` 표시 |
| 그 외 | 기존 Not Found 페이지 표시 |

`BuyerSaleCard` 전체를 React Router의 `Link`로 구성하고 목적지를 `/sales/{saleId}`로 지정한다. 카드 내부에 별도 상세 버튼을 추가하지 않는다. 링크에는 상품명이 포함된 접근 가능한 이름을 제공하며 키보드 focus를 명확히 표시한다. 클릭 가능성은 pointer cursor와 경계선 변화 정도로 전달하고 hover 이동, shadow와 animation은 추가하지 않는다.

상세 페이지 상단에는 `/`로 이동하는 `상품 목록` 링크를 제공한다. 브라우저 방문 기록에 의존하는 뒤로가기 대신 고정된 메인 경로를 사용해 직접 접근한 경우에도 예측 가능한 이동을 보장한다.

상세 페이지의 `JAPDA`는 `/` 링크로 제공한다. 로그인 버튼은 기존 메인 페이지와 동일하게 표시하되 인증, 이동 또는 modal 동작을 추가하지 않는다. 이번 작업만을 위해 전역 header나 공통 layout을 선행 추출하지 않는다.

## 화면 구성

페이지는 상품 요약과 상품 콘텐츠의 두 영역으로 나눈다.

```text
JAPDA                                             로그인

상품 목록

┌────────────────────────────┬───────────────────────┐
│                            │ LIVE                  │
│                            │                       │
│       대표 상품 이미지     │ 상품명                │
│                            │ 35,000원              │
│                            │                       │
│                            │ 판매 기간             │
│                            │                       │
│                            │ [ 구매하기 ]          │
└────────────────────────────┴───────────────────────┘

                       PRODUCT DETAILS
─────────────────────────────────────────────────────

상품 설명을 전체 너비의 긴 세로 흐름으로 표시

추가 이미지 1

추가 이미지 2
```

### 상단 상품 요약

데스크톱에서는 대표 이미지와 상품 정보를 2열로 배치하고 이미지가 더 넓은 비중을 차지하게 한다. 모바일에서는 대표 이미지 다음에 상품 정보를 표시하는 단일 열로 전환한다. 가격과 판매 상태는 모바일에서도 상세 콘텐츠로 scroll하기 전에 노출한다.

정보는 다음 순서로 표시한다.

1. 판매 상태
2. 상품명
3. 가격
4. 판매 기간
5. 구매하기 버튼

판매 상태는 API 응답을 그대로 사용하며 화면 문구는 다음과 같다.

| API 상태 | 화면 문구 | 표현 |
| --- | --- | --- |
| `UPCOMING` | `UPCOMING` | Steel 계열의 낮은 강조 badge |
| `ON_SALE` | `LIVE` | Signal을 dot과 text에만 사용한 badge |
| `ENDED` | `ENDED` | Faint Gray 계열의 가장 낮은 강조 badge |

가격은 기존 `formatKoreanPrice`를 사용해 천 단위 구분 기호와 `원`을 표시한다. `startsAt`과 `endsAt`은 `Asia/Seoul` 기준 날짜와 시각으로 변환해 하나의 판매 기간으로 표시한다. countdown은 제공하지 않는다.

### 구매하기 버튼

구매하기 버튼은 `ON_SALE`에서 Obsidian 배경과 Paper White 글자의 pill 형태 Primary CTA로 표시한다. `UPCOMING`과 `ENDED`에서는 `disabled`를 적용하고 Soft Mist 배경과 Faint Gray 글자로 비활성 상태를 표현한다. 문구는 모든 상태에서 `구매하기`로 유지한다.

`button type="button"`을 사용하지만 event handler를 연결하지 않는다. `ON_SALE` 상태에서 클릭하거나 키보드로 활성화해도 API 요청, route 변경, modal, 메시지 또는 상태 변경이 발생하지 않는다. 버튼은 후속 구매 흐름을 위한 시각적 경계이며, 잔여 재고나 다른 조건으로 실제 구매 가능 여부를 추론하지 않는다.

### 하단 상품 콘텐츠

하단은 상단과 충분한 여백 및 Concrete Gray 구분선으로 분리하고 `PRODUCT DETAILS` 제목을 제공한다. 콘텐츠는 전체 너비 안에서 읽기 적절한 최대 폭을 유지하며 긴 세로 흐름으로 표시한다.

`description`이 존재하면 일반 텍스트로 취급해 줄바꿈을 보존하고 자르지 않은 전체 내용을 먼저 표시한다. 클라이언트는 `description`에서 heading, 소재 정보, HTML 또는 다른 콘텐츠 구조를 추론하지 않는다. `description`이 `null`이면 별도 빈 설명 문구 없이 설명 영역을 생략한다.

전체 `images`에서 `isRepresentative`가 `true`인 항목은 상단에 한 번만 표시한다. 나머지 이미지는 API의 `displayOrder` 순서를 유지해 설명 아래에 한 장씩 크게 이어서 표시한다. 추가 이미지가 없으면 설명만 표시하고, 설명도 없으면 `추가 상세 정보가 없습니다.`라는 중립적인 안내를 표시한다.

하단 이미지는 carousel이나 선택 UI를 제공하지 않는다. 각 이미지의 원래 비율을 유지하고 컨테이너 너비를 넘지 않게 표시한다. 이미지 사이에는 충분한 세로 여백을 둔다.

## 이미지 처리

API의 `images[].path`는 `/products/...` 형식의 상대 경로이다. 기존 `shared/config/env.ts`의 `imageBaseUrl`과 `buildProductImageUrl`을 재사용해 실제 이미지 URL을 구성한다. 기준 URL 뒤와 상대 경로 앞의 `/`를 정규화한다.

대표 이미지는 `images` 중 `isRepresentative`가 `true`인 항목으로 결정한다. API 계약상 대표 이미지는 정확히 하나이지만, 성공 응답의 런타임 검증에서도 이미지가 1~10개인지, `displayOrder`가 0 이상의 안전한 정수인지, 대표 이미지가 정확히 하나인지 확인한다. 배열을 정렬해 계약 오류를 보정하지 않고 응답 순서가 `displayOrder` 오름차순인지 검증한다.

`VITE_IMAGE_BASE_URL`이 없거나 유효하지 않으면 데이터 조회를 실패시키지 않고 해당 이미지 위치에 중립적인 대체 영역을 표시한다. 개별 이미지 load가 실패한 경우에도 해당 이미지만 대체 영역으로 전환한다. 대표 이미지 대체 영역은 상품명을 접근 가능한 설명으로 사용하고, 추가 이미지에는 상품명과 표시 순서를 포함한 접근 가능한 설명을 제공한다. 객체 경로나 내부 오류는 화면에 노출하지 않는다.

## API 계약과 런타임 검증

상세 타입은 기존 목록 타입과 공통 필드의 의미를 유지하되 상세 응답의 차이를 명확히 표현한다.

```ts
interface BuyerSaleProductImage {
  path: string
  displayOrder: number
  isRepresentative: boolean
}

interface BuyerSaleProductDetail {
  saleId: number
  productId: number
  name: string
  description: string | null
  price: number
  quantity: number
  saleDate: string
  startsAt: string
  endsAt: string
  status: BuyerSaleStatus
  images: BuyerSaleProductImage[]
}
```

API 모듈은 기존 `requestApi`와 `apiBaseUrl`을 사용해 다음 요청을 수행한다.

```http
GET /api/sales/{saleId}
```

구매자 또는 판매자 식별자 header는 전송하지 않는다. `saleId`를 URL 경로에 포함하기 전에 양의 안전한 정수인지 확인한다. 문자열, 가격, 날짜, UTC `Instant`, 상태와 전체 이미지 배열을 포함한 성공 응답 전체를 런타임에서 검사한다. 계약에 맞지 않는 `200 OK` 응답은 정상 데이터로 표시하지 않고 상세 조회 오류로 처리한다.

## 조회와 상태 갱신

전체 데이터 흐름은 다음과 같다.

```text
BuyerSaleCard
     ↓ /sales/:saleId
BuyerSaleDetailPage
     ↓
useBuyerSaleDetail(saleId)
     ↓
TanStack Query
     ↓
fetchBuyerSaleDetail(saleId, AbortSignal)
     ↓
GET /api/sales/{saleId}
     ↓
loading / invalid / not found / error / detail
```

상세 query key에는 `saleId`를 포함한다. query function의 `AbortSignal`을 API 모듈까지 전달하고 컴포넌트가 해제되거나 다른 상품으로 이동하면 이전 요청을 취소한다. 자동 retry와 network reconnect 재조회는 사용하지 않으며 오류 화면의 명시적인 재시도만 제공한다.

API가 상태 계산의 기준이므로 클라이언트는 현재 시각으로 `UPCOMING`, `ON_SALE` 또는 `ENDED`를 직접 계산하지 않는다. 조회 응답을 받은 뒤 현재 상태의 다음 경계가 미래라면 해당 `startsAt` 또는 `endsAt`에 상세 query를 한 번 무효화해 서버 상태를 다시 조회한다. 경계 timer는 컴포넌트 해제, 상세 대상 변경과 재조회 시 정리한다.

## 조회 상태와 오류 처리

| 상태 | 화면 동작 |
| --- | --- |
| 최초 조회 중 | 상단 2열 구조와 하단 영역의 크기를 유지하는 loading 표시 |
| 조회 성공 | 상품 요약과 하단 상세 콘텐츠 표시 |
| 잘못된 `saleId` | API를 호출하지 않고 `유효하지 않은 상품 경로입니다.`와 상품 목록 링크 표시 |
| `404 SALE_NOT_FOUND` | `판매 상품을 찾을 수 없습니다.`와 상품 목록 링크 표시 |
| 기타 조회 실패 | 일반 오류 안내와 `다시 시도` 버튼 표시 |
| 재조회 중 | 기존 오류를 제거하고 loading 표시 |

라우트의 `saleId`가 숫자 형식이 아니거나 0 이하이거나 안전한 정수 범위를 벗어나면 잘못된 경로로 처리한다. API의 구체적인 내부 메시지, 이미지 경로와 예외 정보는 사용자에게 노출하지 않는다.

404와 일반 오류는 기존 `ApiError`의 HTTP 상태와 오류 코드를 사용해 구분한다. 공통 API client 자체의 기본 오류 문구를 상세 페이지 문구로 변경하지 않고, 상세 feature가 화면 상태에 맞는 문구를 결정한다.

## 프론트엔드 구조와 책임

기존 `app → pages → features → shared` 의존 방향을 유지한다.

```text
src/
├── app/
│   └── router.tsx
├── pages/
│   ├── buyer-main/
│   │   └── BuyerMainPage.tsx
│   └── buyer-sale-detail/
│       └── BuyerSaleDetailPage.tsx
└── features/
    └── buyer-sale/
        ├── api/
        │   └── buyerSaleApi.ts
        ├── hook/
        │   ├── useBuyerSales.ts
        │   └── useBuyerSaleDetail.ts
        ├── model/
        │   └── buyerSale.ts
        └── ui/
            ├── BuyerSaleCard.tsx
            ├── BuyerSaleDetail.tsx
            └── BuyerSaleList.tsx
```

- `app/router.tsx`는 상세 URL과 페이지 연결만 담당한다.
- `BuyerSaleDetailPage`는 route parameter를 검증하고 상세 hook의 상태와 UI를 조합한다.
- `useBuyerSaleDetail`은 상세 query와 판매 상태 경계 갱신을 관리한다.
- `buyerSaleApi`는 HTTP 요청과 상세 응답의 런타임 검증을 담당한다.
- `BuyerSaleDetail`은 검증된 데이터 렌더링에 집중하며 API를 직접 호출하지 않는다.
- 기존 가격, 상태 문구와 이미지 URL 변환 함수는 의미가 같은 범위에서 재사용한다.
- 상세 페이지만을 위해 공용 layout, header, mapper 또는 전역 상태를 만들지 않는다.

## 접근성과 반응형

- 상품 카드 링크와 상품 목록 링크에는 keyboard focus 표시를 제공한다.
- 페이지의 `h1`은 상품명으로 하고 상세 영역 제목은 하위 heading으로 구성한다.
- 상태는 색상뿐 아니라 `UPCOMING`, `LIVE`, `ENDED` 텍스트로 전달한다.
- 이미지에는 상품명과 순서를 반영한 대체 텍스트 또는 대체 영역을 제공한다.
- loading과 오류 상태의 변경은 적절한 live region으로 전달한다.
- 모바일의 구매하기 버튼은 충분한 touch area를 제공하지만 bottom fixed로 만들지 않는다.
- 긴 상품명, 긴 설명과 여러 이미지에서도 가로 overflow가 발생하지 않게 한다.
- 이미지 영역은 표시 전 크기를 확보해 큰 layout shift를 줄인다.

## 호환성과 아키텍처 결정

- 기존 목록 및 상세 API 계약을 변경하지 않는다.
- 기존 구매자 메인 페이지의 달력과 목록 조회 동작을 변경하지 않는다.
- 기존 판매자 route와 Not Found 처리를 유지한다.
- 상품 카드의 표시 정보와 크기는 유지하고 상호작용만 상세 링크로 확장한다.
- 새 dependency, 환경 변수와 전역 상태를 추가하지 않는다.
- 기존 계층이나 도메인 경계와 의존 방향을 변경하지 않으므로 새로운 ADR은 작성하지 않는다.

## 검증 전략

### 모델과 API

- 양의 안전한 정수인 `saleId`만 상세 요청에 사용하는지 확인한다.
- API base URL과 `/api/sales/{saleId}`를 올바르게 결합하는지 확인한다.
- `AbortSignal`과 공개 GET 요청 옵션이 전달되는지 확인한다.
- nullable 설명, 상태, 날짜, UTC 시각과 이미지 1개 및 10개 경계를 검증한다.
- 이미지 경로, 표시 순서, 정렬과 대표 이미지 정확히 한 개 조건을 검증한다.
- 잘못된 성공 응답을 상세 조회 오류로 변환하는지 확인한다.
- 404와 일반 API 오류 정보가 보존되는지 확인한다.

### Hook

- query key가 `saleId`별로 분리되는지 확인한다.
- query function의 `AbortSignal`이 API에 전달되는지 확인한다.
- 자동 retry와 reconnect 재조회 없이 명시적인 재시도가 동작하는지 확인한다.
- `UPCOMING`은 `startsAt`, `ON_SALE`은 `endsAt` 경계에서 query를 갱신하는지 확인한다.
- 상세 대상 변경과 unmount 시 경계 timer를 정리하는지 확인한다.

### UI와 페이지

- 상품 카드 전체가 올바른 `/sales/{saleId}` 링크인지 확인한다.
- 상세 route 직접 접근과 기존 route가 함께 동작하는지 확인한다.
- 대표 이미지가 상단에 한 번만 표시되는지 확인한다.
- 설명과 추가 이미지가 합의된 순서로 모두 표시되는지 확인한다.
- 설명 또는 추가 이미지가 없는 상태를 올바르게 표시하는지 확인한다.
- loading, 잘못된 경로, 404, 일반 오류와 재시도를 구분하는지 확인한다.
- 이미지 기준 URL 오류와 개별 이미지 load 실패 시 대체 영역을 표시하는지 확인한다.
- `ON_SALE`에서만 구매하기 버튼이 활성화되고 `UPCOMING`과 `ENDED`에서는 비활성화되는지 확인한다.
- 활성화된 구매하기 버튼 클릭이 API 요청, route 변경, modal 또는 상태 변경을 일으키지 않는지 확인한다.
- 긴 상품명, 설명과 여러 이미지에 대한 반응형 배치는 build와 실제 브라우저에서 확인한다.

## 주요 결정과 후속 작업

상단은 대표 이미지와 핵심 판매 정보만 제공하고, 상품 설명과 추가 이미지는 하단의 긴 세로 흐름으로 분리한다. 상세 식별자에는 판매 일정의 의미를 보존하는 `saleId`를 사용한다. 구매하기 버튼은 `ON_SALE`에서만 활성화하며, 후속 주문 기능이 연결될 위치를 보여주는 UI 경계로만 제공한다.

후속 구매 기능은 인증, 잔여 재고의 원천, 실제 구매 가능 상태, 주문 생성 실패와 중복 요청 정책을 별도로 설계한 뒤 이 버튼에 연결한다. 해당 설계가 승인되기 전에는 판매 상태 외의 조건을 버튼의 활성 여부에 추가하지 않는다.
