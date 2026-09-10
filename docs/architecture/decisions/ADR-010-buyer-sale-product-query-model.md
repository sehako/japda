# ADR-010: 구매자 판매 상품 목록을 조회 전용 projection으로 구성

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-10

## 결정

구매자 판매 상품 목록은 판매일 하나를 기준으로 `sales`, `products`, 대표 `product_images`를 단일 join 쿼리로 조회한다. `sale` 영역에 쓰기용 `SaleRepository`와 분리된 조회 전용 Repository 계약 및 조회 결과 타입을 두고, infrastructure가 native query와 내부 projection으로 이를 구현한다. application은 조회 결과에 판매 기간, 상태와 이미지 상대 경로를 더해 API Response로 변환한다.

조회 모델을 위해 `Sale`, `Product`, `ProductImage` Entity 사이에 JPA 연관관계를 추가하지 않는다. 조회 결과는 Entity나 API Response가 아니며 상태 변경에 사용하지 않는다.

## 이유

구매자 목록은 판매 조건, 상품 기본 정보와 대표 이미지가 항상 함께 필요하다. 조회 전용 projection은 필요한 열만 한 쿼리로 가져오며 application의 다중 Repository 호출과 결과 조립을 줄인다.

쓰기 Entity의 관계를 변경하지 않으므로 상품 원본과 판매 일정의 생명주기를 분리한 ADR-009를 유지할 수 있다. 조회 계약을 쓰기용 Repository와 분리해 판매 등록의 책임과 복합 화면 조회의 책임도 구분한다.

## 트레이드오프

조회 쿼리는 여러 도메인의 테이블 구조와 대표 이미지 불변식에 의존한다. 관련 스키마나 이미지 정책이 바뀌면 조회 구현과 projection을 함께 변경해야 한다. 단일 도메인 Repository를 각각 호출하는 방식보다 읽기 모델 전용 코드가 추가되고 native query 매핑을 실제 PostgreSQL에서 검증해야 한다.

이 결정은 구매자 판매 상품 목록에 한정한다. 모든 조회를 projection이나 단일 join으로 구현해야 한다는 일반 규칙으로 확장하지 않는다.
