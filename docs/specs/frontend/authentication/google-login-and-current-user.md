# Google 로그인 연동과 현재 사용자 표시

## 목적과 완료 조건

구매자 메인, 상품 상세, 체크아웃 화면의 로그인 버튼을 백엔드 Google OAuth2 Login에 연결한다. 로그인 왕복 후 SPA는 `GET /api/auth/me`로 현재 사용자를 확인하고, 세 화면의 헤더에 로그인 상태를 일관되게 표시한다. 사용자는 로그인 전에 보던 화면으로 돌아간다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 세 화면의 로그인 버튼에서 백엔드 `/oauth2/authorization/google`로 브라우저 전체 이동이 시작된다.
- 로그인 성공 리다이렉트 후 자격 증명을 포함한 `/api/auth/me` 요청이 성공해야 로그인 완료로 표시하고, 로그인 전 화면으로 돌아간다.
- 세 화면은 비로그인, 확인 중, 로그인, 조회 오류 상태를 구분해 표시한다. 로그인한 경우 현재 저장된 이메일을 표시한다.
- 로그인 실패 리다이렉트와 `/api/auth/me`의 `401`, 그 밖의 조회 오류를 각각 구분해 안내한다.
- 기존 구매·판매·결제 API의 개발용 ID 헤더와 요청 계약은 유지한다.

## 근거와 범위

[Google 소셜 로그인과 JWT 발급](../../backend/authentication/google-social-login.md)에 따라 백엔드는 Google 로그인을 처리하고 서비스 JWT를 `JAPDA_ACCESS_TOKEN` `HttpOnly` 쿠키에 설정한다. 성공 시 고정된 SPA `/auth/success`로, 실패 시 `/auth/failure?error=...`로 이동시킨다. 로컬 백엔드 기본 설정도 이 경로를 사용한다. JWT 쿠키의 경로는 `/api`이고 수명은 1시간이다. 프론트엔드는 JWT나 Google 토큰을 읽거나 저장하지 않는다.

[현재 로그인 사용자 조회 API](../../backend/authentication/current-user-api.md)의 `GET /api/auth/me`는 쿠키 인증 후 `id`, `email`, `roles`를 반환한다. `401`은 `AUTH_UNAUTHENTICATED` 코드의 `application/problem+json`이며, 성공과 인증 실패 응답에는 `Cache-Control: no-store`가 적용된다. 현재 저장소에서 이 API는 설계 문서로 정의되어 있고 백엔드 구현은 아직 확인되지 않았다. 실제 Google 왕복 통합 검증은 API가 구현된 뒤 수행한다.

이번 범위는 `apps/frontend/`의 세 화면 로그인 표시, 두 로그인 결과 route, 인증 feature, 설정과 관련 테스트다. 백엔드, DB, JWT 검증 방식, 토큰 갱신, 로그아웃, 회원 정보 수정, 계정 페이지, 인증된 상태 변경 API, 역할별 화면 접근 제어와 기존 도메인 ID 연결은 포함하지 않는다. `roles`는 조회 결과의 정보이며 기존 API의 접근 권한으로 해석하지 않는다.

## 화면과 데이터 흐름

1. 세 화면에서 사용하는 로그인 UI가 클릭 시 현재 탭의 `pathname + search + hash`를 `sessionStorage`에 기록한다. 저장소를 사용할 수 없어도 로그인은 시작하며, 로그인 후 기본 경로 `/`로 돌아간다. 이후 백엔드의 `/oauth2/authorization/google`로 `window.location.assign`을 호출해 전체 페이지를 이동시킨다. OAuth2 시작 요청을 `fetch`로 호출하지 않는다.
2. Google 왕복과 JWT 쿠키 설정은 백엔드가 담당한다. 브라우저가 `/auth/success`로 돌아오면 성공 화면은 `/api/auth/me`를 조회해 실제 로그인 상태를 확인한다. 리다이렉트 자체만으로 인증 완료로 판단하지 않는다.
3. 조회가 `200`이면 저장된 내부 경로로 이동하고 저장값을 제거한다. 저장값이 없거나 잘못됐으면 `/`로 이동한다. 복귀 경로는 URL 파서로 현재 SPA와 origin이 같은지 확인하고, `/`로 시작하면서 `//`로 시작하지 않는 경로만 허용한다. `/auth/success`와 `/auth/failure`는 복귀 대상으로 사용하지 않는다. 체크아웃의 `quantity` 등 기존 검색 조건은 유지한다. 이동할 때 성공 결과 route를 방문 기록에서 교체해 뒤로 가기로 다시 처리되지 않게 한다.
4. `/auth/failure`는 허용된 `error` 코드에 따라 실패 안내와 로그인 재시도를 제공한다. 재시도할 때 원래 복귀 경로는 유지한다. 복귀 경로가 없는 직접 접근에서는 기본 경로 `/`를 사용한다.
5. 세 화면의 헤더는 동일한 현재 사용자 Query를 사용한다. 로그인 성공 이후에도 새로고침, 화면 이동, 브라우저 재진입 시 `/api/auth/me` 결과로 상태를 판단한다. 사용자 정보를 별도 전역 상태나 영구 저장소에 복제하지 않는다.

로그인 화면 전환은 백엔드 리다이렉트에 맞춰 `app`의 React Router에 `/auth/success`와 `/auth/failure`를 추가한다. 결과 페이지는 `pages`, 요청·응답 검증과 Query 및 로그인 UI는 `features/authentication`에 둔다. 기존 세 화면은 인증 feature의 로그인 표시 UI를 사용하되, 헤더 전체를 공통 컴포넌트로 추출하지 않는다. 이 구성은 [프론트엔드 아키텍처](../../../architecture/frontend.md)의 의존성 방향과 TanStack Query 서버 상태 원칙을 따른다. 새 dependency나 아키텍처 원칙 변경은 필요하지 않다.

## API 및 상태 계약

인증 feature의 API는 기존 `shared/api/apiClient.ts`의 요청 함수를 사용해 `GET /api/auth/me`만 `credentials: 'include'`로 호출하고 Query의 `AbortSignal`을 전달한다. 공통 요청 함수의 상품 등록용 기본 오류 문구를 인증 화면에 그대로 노출하지 않고, 인증 feature에서 상태에 맞는 문구로 바꾼다. `VITE_API_BASE_URL`이 있으면 그 설정의 origin에 `/oauth2/authorization/google`을 붙여 로그인 시작 주소를 만든다. 설정이 없으면 현재 origin을 사용한다. 로컬 Vite 개발 서버는 `/api`, `/oauth2`, `/login/oauth2`를 `http://localhost:8080`으로 전달하며, 배포 환경도 OAuth2 시작 경로와 `/api`를 백엔드로 전달해야 한다. SPA와 API가 다른 origin이면 백엔드가 허용한 명시적 SPA origin에서 자격 증명 CORS를 사용하고, 쿠키 전송이 가능한 동일 사이트 배치를 전제로 한다. 기존 API 요청의 `credentials` 기본값은 바꾸지 않는다.

응답은 양의 안전한 정수 `id`, 문자열 `email`, 중복 없는 `BUYER`·`ADMIN` 이름순 배열 `roles`로 검증한다. 화면에는 `email`만 표시한다. 역할은 현재 사용자 조회 결과에 보관하되 이번 범위에서 헤더 배지, 메뉴 노출, route 차단 또는 기존 API 호출 조건으로 사용하지 않는다.

현재 사용자 Query는 다음 상태를 제공한다.

| 결과 | 헤더 표시 | 처리 |
| --- | --- | --- |
| 조회 중 | `로그인 상태 확인 중` | 로그인 여부를 추측해 표시하지 않는다. |
| `200` | 이메일 | 현재 사용자로 표시한다. |
| `401` | `로그인` | 오류 코드와 관계없이 재로그인이 필요한 비로그인 상태로 처리한다. |
| 네트워크·서버·응답 형식 오류 | `로그인 상태를 확인하지 못했습니다`와 재시도 | 비로그인으로 단정하지 않는다. |

Query key는 인증 feature에서 한 곳에 정의한다. `401`은 재시도하지 않고 비로그인 결과로 처리한다. 다른 오류도 자동 재시도하지 않으며 사용자가 재시도할 수 있다. `staleTime: 0`, `retry: false`, `refetchOnMount: true`, `refetchOnWindowFocus: true`, `refetchOnReconnect: true`로 두어 화면 진입, 창 포커스 복귀, 연결 복구 시 쿠키 만료와 서버 변경을 확인한다. 재조회 중에는 이전 결과를 로그인 상태로 표시하지 않고 확인 중 상태를 표시하며, 재조회에 실패해도 이전 사용자 정보를 표시하지 않는다. 쿠키 내용이나 조회 응답을 `localStorage` 또는 `sessionStorage`에 저장하지 않는다. 복귀 경로만 탭의 `sessionStorage`에 일시 보관한다.

`/auth/success`에서 `/api/auth/me`가 `401`이면 성공으로 이동하지 않고 로그인 버튼을 표시하며 원래 복귀 경로를 유지한다. 그 밖의 조회 오류에는 재조회 버튼을 표시한다. `/auth/failure`의 `EMAIL_UNVERIFIED`는 검증된 이메일이 필요하다고 안내하고, `GOOGLE_LOGIN_FAILED` 및 알 수 없는 코드는 일반 로그인 실패로 안내한다. URL의 오류 문자열을 그대로 화면에 출력하지 않는다. 실패 화면에서는 메인 이동도 제공한다.

## 검증

- 인증 API 테스트에서 `credentials: 'include'`, `AbortSignal`, 정상 응답 검증, 오류 코드와 관계없는 `401` 비로그인 처리와 네트워크·서버·응답 형식 오류 구분을 확인한다.
- 로그인 흐름 테스트에서 세 화면의 버튼이 백엔드 OAuth2 시작 주소로 이동하고 내부 복귀 경로를 보관하는지, 성공 후 `/api/auth/me` 확인 전에는 로그인 완료로 이동하지 않는지 확인한다.
- 성공 route 테스트에서 원래 화면과 검색 조건 복귀, 저장 경로 누락·변조 시 `/` 복귀, 복귀 경로 제거를 확인한다.
- 실패 route와 헤더 테스트에서 허용된 오류 코드 안내, 재시도, 비로그인·조회 중·로그인·조회 오류 표시를 확인한다.
- 기존 구매·판매·결제 API 호출의 개발용 ID 헤더가 사용자 `id`나 `roles`로 대체되지 않았는지 관련 회귀 테스트를 확인한다. 프론트엔드 `test`, `build`, `lint`를 실행하고, 백엔드 `/api/auth/me` 구현 후 동일 사이트 쿠키와 CORS를 포함한 실제 왕복을 확인한다.
