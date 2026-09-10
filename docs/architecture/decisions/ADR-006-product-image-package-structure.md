# ADR-006: 상품 이미지 기능을 상품 도메인의 계층별 하위 패키지로 구성

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

상품 이미지 등록 기능은 독립된 최상위 도메인으로 분리하지 않고 기존 `product` 도메인에 포함한다. 이미지 기능의 파일은 기존 계층 경계를 유지하면서 `product/presentation/image`, `product/application/image`, `product/domain/image`, `product/infrastructure/image` 하위 패키지로 구성한다.

HTTP multipart 처리와 Request는 `presentation/image`, 이미지 등록 유스케이스와 Dto 및 Response는 `application/image`, `ProductImage`와 이미지 영속성 Repository 계약 및 핵심 규칙은 `domain/image`, JPA 영속성·이미지 디코더·S3 연동 구현은 `infrastructure/image`에 둔다. 상품 자체의 상태와 상태 전환은 기존 `product/domain`의 `Product`와 `ProductStatus`가 계속 관리한다.

이미지 기능과 무관한 상품 파일을 `image` 하위 패키지로 이동하지 않는다. S3 저장소 인터페이스의 정확한 계층 위치처럼 이 결정에서 확정하지 않은 외부 시스템 경계는 구현 전에 별도로 검토한다.

## 이유

상품 이미지는 상품 소유권, `DRAFT → READY` 상태 전환과 최초 등록 규칙에 직접 결합되므로 독립 도메인보다 상품 도메인 내부 기능으로 보는 것이 적합하다. 동시에 이미지 등록에는 HTTP 요청, 유스케이스 조율, 도메인 모델, JPA와 S3 구현 등 여러 파일이 필요하므로 각 계층을 평면적으로 확장하면 일반 상품 등록 코드와 이미지 코드의 탐색성과 응집도가 떨어진다.

각 계층 아래에 동일한 `image` 기능 경계를 두면 기존 `presentation → application → domain`, `infrastructure → domain` 의존성 방향을 유지하면서 이미지 관련 파일을 함께 찾을 수 있다.

## 트레이드오프

하나의 이미지 기능이 여러 계층의 `image` 패키지에 나뉘므로 기능 전체를 파악할 때 계층 사이를 이동해야 한다. `product/image/{layer}` 형태의 수직 구조보다 기존 백엔드 계층 규칙과의 일관성을 우선한다.

이미지 기능이 상품과 독립된 생명주기나 여러 도메인에서 공유되는 책임을 갖게 되면 현재 경계를 다시 검토해야 한다. 단순히 파일 수가 증가했다는 이유만으로 최상위 도메인이나 공통 이미지 모듈로 분리하지 않는다.
