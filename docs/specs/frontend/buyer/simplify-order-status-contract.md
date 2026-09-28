# 주문 상태 계약 확장 프론트엔드 구현 계획

## 목적

백엔드의 [재고 관리 단순화 구현 계획](../../backend/order/simplify-inventory-management.md)에 따라 구매자 프론트엔드가 주문 생명주기의 네 가지 공개 상태를 일관되게 처리하도록 정비한다. 주문 내역에서는 백엔드가 반환한 `EXPIRED`, `PAYMENT_FAILED`를 계약 오류로 거절하지 않고 의미 있는 상태로 표시하며, 주문 생성의 동일 멱등성 키 재응답에서 현재 주문 상태가 `PAID`일 수 있다는 확장을 안전하게 처리한다.

이번 작업은 `apps/frontend/`의 구매자 주문·결제 흐름과 관련 명세·테스트만 대상으로 한다. 백엔드 API, 데이터베이스, 결제 제공자 연동과 새로운 dependency는 변경하지 않는다.

## 완료 조건

- 구매자 주문 내역 API 검증이 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`를 모두 허용한다.
- 주문 내역이 네 상태를 각각 `결제 대기`, `결제 완료`, `만료`, `결제 실패`로 표시한다.
- `expiresAt`은 `PENDING_PAYMENT`에서만 보조 정보로 표시하고, 클라이언트가 시각만으로 주문 상태를 재분류하지 않는다.
- `EXPIRED`와 `PAYMENT_FAILED` 주문이 목록 응답에 포함되어도 전체 목록을 계약 오류로 처리하지 않는다.
- 주문 생성 API의 응답 계약이 백엔드의 동일 멱등성 키 재응답 정책과 일치한다. 새 주문의 정상 응답은 `PENDING_PAYMENT`이며, 기존 주문의 `PAID`, `EXPIRED`, `PAYMENT_FAILED` 재응답도 상태에 맞게 안전하게 분기한다.
- 종료된 주문에 같은 주문·멱등성 키로 재결제를 시도하지 않으며, `EXPIRED`·`PAYMENT_FAILED` 상태에서 새 주문을 시작해야 하는 흐름을 기존 결제 오류 안내와 모순 없이 유지한다.
- 결제 결과 화면은 검증된 `PAID` 응답만 성공으로 표시하고, 결제 진행 중·실패 확정·결과 확인 필요 상태의 기존 구분을 유지한다.
- 관련 API·model·hook·UI 테스트와 프론트엔드 `build`, `lint`가 통과한다.
- 관련 프론트엔드 명세가 실제 상태 계약과 구현 범위를 반영한다.

## 배경과 현재 구조

### 백엔드 계약 변화

백엔드 계획은 주문 상태를 다음 네 값으로 제한한다.

```text
PENDING_PAYMENT
PAID
EXPIRED
PAYMENT_FAILED
```

결제 시도가 없는 만료 주문은 scheduler가 `EXPIRED`로 전환하고 재고를 반환한다. PG 실패가 확정된 주문은 `PAYMENT_FAILED`로 전환하고 재고를 한 번 반환한다. 결제 진행 중이거나 결과가 불명확한 주문은 주문 상태를 `PENDING_PAYMENT`로 유지한다.

또한 같은 `Idempotency-Key`로 주문 생성 요청을 재전송하면 새 주문을 만들지 않고 기존 주문을 반환하며, 응답 `status`는 현재 저장 상태를 반환할 수 있다. 따라서 결제 완료 뒤 동일 키의 재응답은 `PAID`가 될 수 있다.

### 현재 프론트엔드 영향 지점

- `buyer-order-history/model/buyerOrderHistory.ts`의 `BuyerOrderStatus`가 `PENDING_PAYMENT | PAID`만 정의한다.
- `buyer-order-history/api/buyerOrderHistoryApi.ts`의 런타임 검증 허용 목록이 두 상태로 제한된다.
- `buyer-order-history/ui/BuyerOrderRow.tsx`가 `PAID`가 아닌 모든 상태를 `결제 대기`로 표시한다.
- `buyer-checkout/model/buyerOrder.ts`와 `buyer-checkout/api/buyerOrderApi.ts`가 주문 생성 응답의 `status`를 `PENDING_PAYMENT`로만 검증한다.
- `useBuyerPayment`는 주문 생성 응답을 결제창 요청 전에 `checkBuyerOrder`로 검증한다. 이미 `PAID`인 재응답을 결제창으로 다시 보내지 않도록 분기 계약이 필요하다.
- `useBuyerPaymentConfirmation`은 `PAYMENT_CONFIRMATION_FAILED`, `PAYMENT_ORDER_EXPIRED`, `PAYMENT_CONFIRMATION_IN_PROGRESS`, `PAYMENT_REVIEW_REQUIRED`를 이미 구분하고 검증된 `PAID` 응답만 성공으로 처리한다.

## 범위

포함한다.

- 구매자 주문 내역의 주문 상태 타입, API 응답 검증, 상태 badge와 보조 문구
- 주문 생성 응답의 상태 타입·검증과 동일 멱등성 재응답 처리
- 결제 결과·주문 내역·체크아웃 사이의 종료 주문 재결제 금지 계약 검증
- 관련 프론트엔드 명세와 테스트 갱신

포함하지 않는다.

- 백엔드 구현, migration, scheduler, 결제 상태 저장 로직
- 주문 취소·환불·재결제 API 추가
- 주문 상세, 상태 필터, 배송·환불 기능
- 결제 제공자 SDK 교체 또는 새로운 외부 dependency
- 공통 레이아웃·라우팅 구조의 리팩터링

## 인터페이스와 의존성 영향

### 주문 내역 응답

기존 응답의 필드와 페이지네이션은 유지하고 `status` 허용 값만 확장한다.

| API 상태 | 화면 문구 | `expiresAt` 표시 |
| --- | --- | --- |
| `PENDING_PAYMENT` | `결제 대기` | 표시 |
| `PAID` | `결제 완료` | 표시하지 않음 |
| `EXPIRED` | `만료` | 표시하지 않음 |
| `PAYMENT_FAILED` | `결제 실패` | 표시하지 않음 |

`PAYMENT_FAILED`는 디자인 지침의 긴급 상태에 해당하므로 기존 `Signal` 색상 사용을 검토하되, badge의 텍스트와 접근 가능한 이름을 함께 제공한다. `EXPIRED`는 중립적인 종료 상태로 표시한다.

### 주문 생성 응답

요청 본문, `Idempotency-Key`, 보호 API 호출 방식과 기존 필드는 유지한다. 응답 상태는 백엔드의 현재 상태 반환 정책을 반영해야 한다.

새 주문 응답은 `PENDING_PAYMENT`여야 하며 결제창을 연다. 동일 멱등성 키의 재응답은 기존 주문의 현재 상태를 반환할 수 있다. `PAID`이면 이미 승인된 주문을 다시 결제하지 않고 `/orders`로 이동한다. `EXPIRED` 또는 `PAYMENT_FAILED`이면 기존 주문을 재사용하지 않고 상태를 안내한 뒤 새 주문을 시작할 수 있게 한다.

### 결제 승인 결과

`POST /api/payments/confirm`의 성공 검증은 계속 `status === PAID`를 요구한다. 백엔드가 실패 확정 시 주문을 `PAYMENT_FAILED`로 저장하더라도 결제 승인 API의 기존 오류 코드 분류를 불필요하게 변경하지 않는다.

## 구현 접근

주문 내역 feature의 서버 응답 검증과 표시 모델을 먼저 네 상태로 확장한다. 상태별 표시를 조건문 하나에 암묵적으로 의존하지 않도록 상태와 화면 문구·스타일의 매핑을 명시적으로 둔다. 이를 통해 정의되지 않은 상태는 계속 계약 오류로 거절하면서, 새로 승인된 네 상태만 표시한다.

주문 생성 feature는 API 응답 타입을 백엔드 계약과 일치시키되, 결제창을 열 수 있는 주문과 이미 종료된 주문의 흐름을 분리한다. `PENDING_PAYMENT`만 결제창 요청의 입력으로 사용하고, `PAID`·`EXPIRED`·`PAYMENT_FAILED`는 같은 주문에 대한 재결제 입력으로 취급하지 않는다. 종료 상태의 새 결제는 기존 주문을 재사용하지 않고 새 멱등성 키로 새 주문을 생성하는 기존 원칙을 유지한다.

결제 결과 feature는 현재의 보수적인 성공 판정을 유지한다. 주문 내역에 표시되는 종료 상태와 결제 결과의 오류 상태를 같은 문자열로 억지로 통합하지 않고, API별 계약과 사용자 문구를 각각 검증한다.

## 작업 계획

### 1. 주문 내역 상태 계약 확장

`BuyerOrderStatus`와 API 런타임 검증 허용 목록에 `EXPIRED`, `PAYMENT_FAILED`를 추가한다. 주문 행은 네 상태를 명시적으로 매핑해 badge 문구와 시각 스타일을 결정하고, `PENDING_PAYMENT`일 때만 결제 기한을 렌더링한다. 기존 cursor·중복 제거·오류 처리 동작은 변경하지 않는다.

### 2. 주문 생성 재응답 계약 정비

백엔드의 동일 멱등성 키 재응답이 현재 주문 상태를 반환하는 정책에 맞춰 `BuyerOrder` 응답 타입과 검증을 네 상태로 조정한다. 새 주문 생성과 기존 주문 재응답을 구분할 수 있도록 hook의 결제 진행 조건을 정리한다. `PAID` 응답에서는 결제 SDK의 `requestPayment()`를 호출하지 않고 `/orders`로 이동하며, `EXPIRED`·`PAYMENT_FAILED` 응답에서도 기존 주문으로 결제를 재시도하지 않고 새 주문 흐름을 안내한다. 각 상태를 명시적으로 전달하는 테스트를 추가한다.

### 3. 결제 결과 호환성 검증

기존 `useBuyerPaymentConfirmation`과 결과 화면의 상태 매핑을 재검토한다. 검증되지 않은 `PAID` 응답, `PAYMENT_CONFIRMATION_FAILED`, `PAYMENT_ORDER_EXPIRED`, `PAYMENT_REVIEW_REQUIRED`가 각각 기존 기대 화면으로 가는지 확인한다. 필요 이상의 결제 결과 화면 변경은 하지 않는다.

### 4. 명세와 테스트 갱신

`buyer-order-history-page.md`의 상태 표·범위·완료 조건·검증 항목을 네 상태에 맞게 갱신한다. `buyer-toss-payment-window.md`와 `buyer-payment-confirmation-result.md`에는 종료 주문의 재사용 금지와 새 주문 시작 원칙을 백엔드 계약과 모순 없이 기록한다. API, model, hook, UI와 page 테스트에 정상·경계·계약 오류 사례를 추가한다.

## 검증 계획

작업 디렉터리는 `/home/sehako/workspace/japda/apps/frontend`로 한다.

1. 단위·컴포넌트 테스트:

   ```bash
   npm test -- --run
   ```

2. 정적 검사:

   ```bash
   npm run lint
   ```

3. 프로덕션 빌드:

   ```bash
   npm run build
   ```

핵심 관찰 결과는 다음과 같다.

- 주문 내역 API가 네 상태를 포함한 page를 정상 반환한다.
- 정의되지 않은 상태는 여전히 응답 계약 오류로 거절된다.
- 네 상태의 badge 문구·스타일과 `expiresAt` 조건이 일치한다.
- 동일 멱등성 키의 `PAID` 응답에서 결제창을 다시 열지 않는다.
- `EXPIRED`·`PAYMENT_FAILED` 주문에 기존 주문의 결제 재시도를 제공하지 않는다.
- 결제 결과는 검증된 `PAID`만 완료 화면으로 표시한다.

백엔드 구현이 아직 완료되지 않은 경우에는 실제 API 통합 검증을 수행하지 않고, 프론트엔드 mock 응답과 계약 테스트로 우선 검증한다. 백엔드가 완료된 뒤에는 동일 멱등성 키 재응답, 주문 내역 종료 상태, 만료 scheduler 결과를 통합 환경에서 추가 확인한다.

## 위험과 복구

- `PAID` 재응답을 일반적인 새 주문 응답처럼 처리하면 이미 승인된 주문에 결제창을 다시 열 수 있다. 결제 SDK 호출 직전 상태 검증을 유지하고, `PAID` 분기를 별도 테스트한다.
- `EXPIRED`·`PAYMENT_FAILED`를 단순히 `PENDING_PAYMENT`와 같은 스타일로 처리하면 사용자가 재결제 가능한 주문으로 오해할 수 있다. 상태별 문구와 액션을 명시적으로 분리한다.
- 백엔드가 주문 생성 응답의 상태 정책을 변경하지 않고 `PENDING_PAYMENT`만 반환하기로 결정하면 주문 생성 feature의 상태 확장은 불필요할 수 있다. 구현 전 백엔드 계약을 확정하고 계획의 해당 범위를 조정한다.
- 기존 프론트 명세와 백엔드 명세가 서로 다른 상태 집합을 정의하고 있다. 구현 전에 두 계약 문서를 함께 갱신하지 않으면 API 검증과 테스트가 다시 불일치할 수 있다.

## 진행 상황

- [x] 백엔드 재고 단순화 명세와 프론트엔드 영향 지점 조사
- [x] 주문 내역·주문 생성·결제 결과의 현재 상태 처리 확인
- [x] 구현 범위와 검증 명령 정의
- [x] 동일 멱등성 키 재응답의 상태 집합과 사용자 경험 확정
- [x] 주문 내역 상태 계약과 UI 구현
- [x] 주문 생성 종료 상태 재응답 처리 구현
- [x] 결제 결과 호환성 테스트 보강
- [x] 프론트엔드 명세·테스트·build·lint 갱신 및 실행

## 발견 사항

- 주문 내역 API 검증은 정의되지 않은 상태를 전체 응답 오류로 처리하므로 새 상태를 허용 목록에 추가하지 않으면 `EXPIRED` 또는 `PAYMENT_FAILED`가 포함된 페이지 전체가 표시되지 않는다.
- 주문 행은 `PAID` 여부만 확인하고 그 외를 모두 `결제 대기`로 표시하므로 새 상태를 타입만 확장해도 올바른 사용자 안내가 되지 않는다.
- 주문 생성 API 테스트는 `PAID` 응답을 현재 계약 오류로 검증하고 있다. 이는 백엔드 계획의 “동일 멱등성 키 재응답은 현재 상태 반환” 정책과 충돌한다.
- `useBuyerPayment`는 주문 응답 검증 통과 후 곧바로 Toss `requestPayment()`를 호출하므로 `PAID` 재응답의 결제 중복 방지 분기가 필요하다.
- 결제 결과 hook은 이미 `PAYMENT_CONFIRMATION_FAILED`, `PAYMENT_ORDER_EXPIRED`, `PAYMENT_REVIEW_REQUIRED`를 구분하고 있어, 백엔드 오류 코드가 유지되면 변경보다 회귀 검증이 중심이다.
- 디자인 지침은 `Signal`을 결제 실패·중요 경고에만 사용할 수 있도록 정의하고 있다.
- 주문 생성 hook은 종료 상태에서 `expired` 상태와 새 주문 안내를 재사용하고, 기존 UI의 `새 주문 시도` 동작으로 새 멱등성 키를 생성한다.
- 결제 결과의 production hook은 이미 확정된 상태 분기를 제공하므로 이번 변경에서는 결과 페이지 회귀 테스트와 명세 보강만 필요했다.

## 결정 로그

| 결정 | 근거 |
| --- | --- |
| 프론트엔드 상태 집합을 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`로 맞춤 | 백엔드 명세가 주문 공개 상태를 네 값으로 제한하고 주문 조회 API가 종료 상태를 반환하기 때문이다. |
| 클라이언트가 `expiresAt`만으로 상태를 추론하지 않음 | 만료·결제 진행·수동 확인 주문의 재고 정책과 주문 상태는 백엔드가 단일 원본으로 관리하기 때문이다. |
| 결제 결과의 성공 판정은 검증된 `PAID` 응답으로 유지 | URL이나 외부 PG 결과만으로 완료를 표시하지 않는 기존 안전 계약을 보존하기 위해서다. |
| 종료 주문에는 기존 주문 재결제 액션을 추가하지 않음 | 백엔드가 `EXPIRED`, `PAYMENT_FAILED` 주문의 동일 주문·멱등성 키 재결제를 금지하고 새 주문을 요구하기 때문이다. |
| 새 상태 추가를 구매자 `buyer` 카테고리의 단일 계획으로 관리 | 변경 대상이 주문 내역·체크아웃·결제 결과로 연결된 하나의 주문 생명주기 계약이기 때문이다. |
| 동일 멱등성 키 재응답은 네 상태 모두 현재 주문 상태로 허용 | 재시도마다 새 주문을 만들지 않고 기존 주문의 최신 상태를 반환하는 멱등성 정책을 유지하기 위해서다. |
| `PAID` 재응답은 결제 완료 화면이 아닌 `/orders`로 이동 | 주문 생성 응답에 `approvedAt` 등 완료 화면 정보가 없고, 결제 SDK 중복 호출을 막으면서 기존 화면 계약을 재사용할 수 있기 때문이다. |
| `EXPIRED`·`PAYMENT_FAILED` 재응답은 새 주문 흐름을 안내 | 기존 주문의 재결제를 허용하지 않으면서 구매자가 새 멱등성 키로 다시 시도할 수 있어야 하기 때문이다. |

## 미해결 질문

없음. 동일 멱등성 키 재응답은 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED`를 모두 현재 주문 상태로 반환하며, `PAID`는 `/orders`로 이동하고 두 종료 실패 상태는 새 주문 흐름을 안내하는 것으로 확정했다.

## 결과 및 회고

### 실제 변경

- `buyer-order-history`의 상태 타입과 API 런타임 검증을 `PENDING_PAYMENT`, `PAID`, `EXPIRED`, `PAYMENT_FAILED` 네 값으로 확장했다.
- 주문 행에 상태별 문구·스타일 매핑을 도입하고 `expiresAt`은 `PENDING_PAYMENT`에서만 표시하도록 했다.
- 주문 생성 응답 검증과 `useBuyerPayment`를 확장해 `PAID` 재응답은 `/orders`로 이동하고, `EXPIRED`·`PAYMENT_FAILED`는 기존 주문을 재사용하지 않고 새 주문을 안내하도록 했다.
- 결제 결과 화면의 검증된 `PAID` 성공 판정과 기존 오류 상태 매핑을 회귀 테스트로 보강했다.
- `buyer-order-history-page.md`, `buyer-toss-payment-window.md`, `buyer-payment-confirmation-result.md`를 실제 상태 계약과 재결제 금지 원칙에 맞게 갱신했다.

### 검증 결과

- `npm test -- --run`: 47개 파일, 406개 테스트 통과
- `npm run lint`: 통과
- `npm run build`: 통과
- `git diff --check`: 통과

동일 멱등성 키의 `PAID` 재응답에서 `requestPayment()`가 호출되지 않고 `/orders`로 이동하는 테스트를 통과했다. `EXPIRED`·`PAYMENT_FAILED` 재응답도 기존 주문의 결제를 재시도하지 않고 새 주문 안내로 전환하는 테스트를 통과했다. 정의되지 않은 상태는 API 계약 오류로 계속 거절하며, 네 상태의 주문 내역 표시와 `expiresAt` 조건도 검증했다.

### 계획과의 차이 및 후속 작업

- 계획과 구현 범위의 차이는 없다. 결제 결과 production hook은 기존 계약이 이미 요구사항을 충족해 수정하지 않고 테스트·명세만 보강했다.
- 실제 백엔드와의 통합 환경 검증은 백엔드 구현 및 통합 환경 준비 이후 후속 작업으로 남긴다.
