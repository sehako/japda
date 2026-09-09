# ExecPlan: 판매자 상품 이미지 업로드 API 구현

> 이 ExecPlan은 자급자족하는 살아 있는 문서이다. 작업이 진행되는 동안 `진행 상황`, `예상 밖의 발견`, `결정 기록`, `결과와 회고`를 최신 상태로 유지한다.
>
> 출처: `docs/plans/backend/001-seller-product-registration-api.md`의 후속 이미지 기능, 2026-09-09 상품 이미지 업로드 브레인스토밍

이 ExecPlan은 기존 `POST /api/products`가 생성한 이미지 없는 `DRAFT` 상품에 판매자가 최초 이미지를 일괄 업로드하는 하나의 독립적으로 검증 가능한 백엔드 기능으로 제한한다. 백엔드는 이미지를 비공개 AWS S3 버킷에 저장하고 PostgreSQL에는 메타데이터만 저장하며, 대표 이미지가 등록되면 상품을 `READY`로 전환한다.

## 목적과 인수 기준

이 변경 뒤에는 판매자가 자신이 생성한 `DRAFT` 상품에 JPEG, PNG 또는 WebP 이미지 1장 이상 10장 이하를 한 번에 업로드하고, 파일 배열 중 한 장을 대표 이미지로 지정할 수 있다. 요청의 파일 순서는 저장된 이미지 정렬 순서가 된다.

- 유효한 `X-Seller-Id`, 상품 ID, 이미지 파일과 `representativeIndex`로 `POST /api/products/{productId}/images`를 호출하면 `201 Created`와 `READY` 상품 및 저장된 이미지 메타데이터를 받는다.
- 모든 원본 파일은 비공개 S3 버킷에 저장되고, PostgreSQL에는 전체 URL이 아닌 `objectKey`, 검증된 MIME type, 크기, 정렬 순서, 대표 여부와 생성 시각이 저장된다.
- 상품별 정렬 순서는 0부터 연속해서 부여되고 대표 이미지는 정확히 한 장이다.
- 상품이 없거나 요청 판매자 소유가 아니면 동일한 `404 Not Found`를 받는다.
- 이미지가 이미 등록됐거나 상품이 `READY`이면 `409 Conflict`를 받는다.
- 파일 규칙 위반은 `400 Bad Request`, 용량 제한 위반은 `413 Payload Too Large`, S3 일시 장애는 내부 정보를 숨긴 `503 Service Unavailable`로 응답한다.
- S3 업로드 일부 또는 전체가 성공한 뒤 나머지 업로드나 DB 반영이 실패하면 이번 요청에서 생성한 객체를 보상 삭제한다.
- S3 네트워크 I/O 동안 DB 트랜잭션과 행 잠금을 유지하지 않으며, 최종 DB 트랜잭션의 잠금 재검사와 DB 제약으로 동시 최초 업로드 중 하나만 성공한다.

이번 계획은 이미지 조회 URL 발급, CloudFront 연동, 이미지 변환·리사이징, 악성 콘텐츠 검사, 이미지 추가·삭제·교체, 대표 이미지 변경, 재정렬, S3 버킷·IAM·Lifecycle 인프라 생성을 포함하지 않는다. OAuth2/JWT 인증 branch가 아직 현재 branch에 병합되지 않았으므로 현재 상품 API와 동일하게 `X-Seller-Id`를 임시 판매자 식별자로 사용한다.

## 맥락과 구현 접근

현재 branch `feature/seller-product-registration`의 `POST /api/products`는 `ProductController.create()`에서 `X-Seller-Id`와 JSON 본문을 받고 `ProductService.create()`를 거쳐 PostgreSQL에 `DRAFT` 상품을 저장한다. `ProductStatus.READY`와 DB CHECK 값은 이미 있지만 상태 전이 메서드, 상품 조회 계약, 이미지 모델과 S3 설정은 없다.

- `docs/architecture/backend.md`: 기능 단위 패키지, `presentation → application → domain`, `infrastructure → domain`, JPA Entity와 Domain Entity 통합, 외부 시스템 추상화와 짧은 DB 트랜잭션 원칙을 정의한다.
- `docs/architecture/decisions/ADR-002-backend-persistence-stack.md`: PostgreSQL, Spring Data JPA, Flyway와 PostgreSQL Testcontainers 사용을 결정한다.
- `docs/architecture/decisions/ADR-005-product-image-storage.md`: Kubernetes 멀티 인스턴스 환경에서 상품 이미지를 비공개 AWS S3에 저장하고 DB에는 `objectKey`와 메타데이터만 저장하기로 결정한다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/product/domain/Product.kt`: 현재 `status`가 변경 불가능한 `val`이며 `DRAFT → READY` 전이 메서드가 없다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/product/domain/ProductRepository.kt`: 현재 `save()`만 제공하므로 일반 조회와 잠금 조회 계약이 필요하다.
- `apps/backend/src/main/kotlin/io/github/sehako/japda/product/infrastructure/ProductJpaRepository.kt`: 현재 기본 `JpaRepository`만 확장하며 잠금 쿼리가 없다.
- `apps/backend/src/main/resources/db/migration/V1__create_products.sql`: 현재 branch의 유일한 Flyway migration이다.
- `apps/backend/build.gradle.kts`: AWS SDK dependency가 없다.
- `apps/backend/src/main/resources/application.yaml`: multipart 제한과 S3 버킷·리전·prefix 설정이 없다.

이미지 업로드는 기존 `ProductController`와 상품 생성 서비스를 비대하게 만들지 않도록 별도 `ProductImageController`와 `ProductImageService` 흐름으로 구성한다. 상품과 이미지의 DB 반영만 담당하는 짧은 트랜잭션 경계는 별도 Application 컴포넌트로 분리해 S3 호출을 트랜잭션 밖에 둔다. S3 구현은 Domain에 정의한 객체 저장 계약 뒤에 두고 Application은 AWS SDK 타입에 직접 의존하지 않는다.

이미지 파일 바이트를 식별하는 객체 키에는 클라이언트 원본 파일명이나 DB identity 이미지 ID를 사용하지 않는다. DB 저장 전에 키가 필요하므로 서버가 생성한 UUID를 사용해 `{설정 prefix}/products/{productId}/{uuid}.{extension}` 형태로 만든다. `extension`은 검증된 실제 파일 형식에서 결정한다.

현재 계획 번호 `003`은 별도 인증 worktree의 `docs/plans/backend/002-google-oauth2-jwt-authentication.md`를 예약해 정했다. 이 계획은 현재 branch만을 구현 기준으로 삼으므로 migration 파일은 현재 다음 번호인 `V2__create_product_images.sql`로 기술한다. 인증 branch 또는 다른 migration이 먼저 병합되면 구현 시작 시 최신 Flyway 번호로 변경해야 한다.

## 인터페이스와 의존성

### HTTP 계약

요청은 다음과 같다.

```http
POST /api/products/123/images
X-Seller-Id: 456
Content-Type: multipart/form-data; boundary=...

files: front.jpg
files: side.webp
representativeIndex: 0
```

- `productId`: 필수인 양의 `Long`
- `X-Seller-Id`: 필수인 양의 `Long`
- `files`: 같은 이름으로 반복되는 파일 part 1개 이상 10개 이하
- `representativeIndex`: 0부터 시작하며 `files` 배열 범위 안에 있는 정수

파일 배열의 순서를 `sortOrder` 0부터 부여한다. 위 요청에서는 `front.jpg`가 `sortOrder=0`인 대표 이미지이고 `side.webp`가 `sortOrder=1`인 일반 이미지다.

성공 응답은 다음과 같다. 비공개 저장소의 `objectKey`, 버킷명과 공개 URL은 반환하지 않는다.

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "productId": 123,
  "status": "READY",
  "images": [
    {
      "id": 1,
      "sortOrder": 0,
      "representative": true,
      "contentType": "image/jpeg",
      "sizeBytes": 245120
    },
    {
      "id": 2,
      "sortOrder": 1,
      "representative": false,
      "contentType": "image/webp",
      "sizeBytes": 188420
    }
  ]
}
```

파일 검증 규칙은 다음과 같다.

- 파일 개수: 1개 이상 10개 이하
- 개별 파일 크기: 1byte 이상 10MiB 이하
- 파일 크기 합계: 50MiB 이하
- 허용 형식: JPEG(`image/jpeg`), PNG(`image/png`), WebP(`image/webp`)
- 선언된 `Content-Type`과 JPEG·PNG·WebP 파일 시그니처 일치
- 유효한 `representativeIndex`

확장자만으로 형식을 판단하지 않는다. JPEG는 SOI 시그니처, PNG는 표준 8byte 시그니처, WebP는 RIFF/WEBP 시그니처를 검사한다. 시그니처 검사용 스트림과 S3 전송용 스트림은 분리해 검증 과정이 업로드 바이트를 소비하지 않게 한다.

Spring multipart 전역 제한은 개별 파일 10MiB를 보장하고 multipart 오버헤드를 수용하도록 요청 제한을 50MiB보다 조금 크게 설정한다. Application은 실제 파일 크기 합계를 다시 계산해 정확한 50MiB 제한을 적용한다. Spring이 Controller 진입 전에 크기 초과를 거부하는 경우도 `413 ProblemDetail`로 변환한다.

### 오류 계약

`ProductExceptionHandler`의 범위를 `ProductController`와 `ProductImageController`로 확장하고 기존 상품 오류 응답 형식을 유지한다. 예외 메시지, S3 응답 본문, 버킷명, 객체 키, SQL과 credential은 응답에 노출하지 않는다.

- 잘못된 `productId`, 판매자 헤더, 파일 개수·형식, 빈 파일과 대표 인덱스는 필드별 `errors`를 가진 `400 Bad Request`다.
- 상품이 없거나 `sellerId`가 다르면 상품 존재 여부를 숨기기 위해 같은 `404 Not Found`다.
- 이미지가 이미 존재하거나 상품이 `READY`인 경우와 동시 업로드 충돌은 `409 Conflict`다.
- 개별 또는 합계 용량 제한 초과는 `413 Payload Too Large`다.
- S3 저장 호출 실패는 내부 정보를 숨긴 `503 Service Unavailable`다.
- 그 밖의 예상하지 못한 오류는 기존 계약과 같은 `500 Internal Server Error`다.

오류 응답의 `instance`는 요청 URI를 사용한다. `errors` 키는 `productId`, `sellerId`, `files`, `representativeIndex` 중 공개 가능한 논리 입력 이름을 사용한다.

### Domain과 Application 계약

`Product`의 `status`는 외부 Setter 없이 도메인 메서드만 변경할 수 있도록 `var`와 `private set`으로 바꾼다. 이미지 메타데이터 저장과 같은 트랜잭션에서 호출하는 `markReadyAfterImageRegistration()`은 `DRAFT`에서만 `READY`로 전환하고 그 밖의 상태에서는 도메인 충돌을 발생시킨다. 대표 이미지 존재와 이미지 목록 전체의 규칙은 여러 Entity를 함께 보는 Application 흐름에서 먼저 검증한다.

`ProductImage`는 JPA Entity이자 Domain Entity이며 다음 값을 가진다.

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `id` | `Long?` | PostgreSQL identity가 생성 |
| `product` | `Product` | FK를 가진 이미지에서 상품을 LAZY 단방향 참조 |
| `objectKey` | `String` | 서버 생성 키, 필수이며 전체 테이블에서 유일 |
| `contentType` | `String` | `image/jpeg`, `image/png`, `image/webp` 중 하나 |
| `sizeBytes` | `Long` | 1byte 이상 10MiB 이하 |
| `sortOrder` | `Int` | 최초 등록에서 0부터 이미지 수보다 작은 연속 값 |
| `representative` | `Boolean` | 상품별 정확히 한 장이 `true` |
| `createdAt` | `Instant` | 주입된 UTC `Clock`으로 Application이 결정 |

`Product`에는 `@OneToMany` 컬렉션을 추가하지 않는다. `ProductImageRepository`가 상품별 존재 여부와 이미지 저장·조회를 담당하고 Application이 응답 목록을 조립한다.

`ProductRepository`에는 `findById(id)`와 최종 트랜잭션에서 사용하는 `findByIdForUpdate(id)` 계약을 추가한다. `ProductJpaRepository`의 잠금 조회는 PostgreSQL 행에 `PESSIMISTIC_WRITE` 잠금을 획득한다. Application은 잠금 조회 뒤 상품 존재·소유권·상태와 기존 이미지 부재를 다시 확인한다.

S3 객체 저장 계약은 최소한 다음 동작을 제공한다.

- 검증된 콘텐츠를 지정한 `objectKey`로 저장
- 이번 요청에서 저장한 객체 삭제
- 보상 삭제에도 실패한 객체에 cleanup 대상 태그 부여 시도

객체 저장 계약은 AWS SDK 타입이나 `MultipartFile`을 노출하지 않는다. 파일 크기, 선언 MIME type과 필요할 때 새 스트림을 여는 중립 입력을 사용한다. `MultipartFile` 변환은 presentation 경계에서 끝낸다.

### 영속성 계약

현재 branch 기준 `V2__create_product_images.sql`은 `product_images` 테이블과 다음 제약을 추가한다.

- `BIGINT GENERATED BY DEFAULT AS IDENTITY` 기본 키
- `products(id)`를 참조하는 `product_id` 외래 키
- 필수 `object_key`, `content_type`, `size_bytes`, `sort_order`, `is_representative`, `created_at`
- 전체 테이블에서 유일한 `object_key`
- 상품별 유일한 `(product_id, sort_order)`
- `size_bytes`가 1 이상 10MiB 이하인 CHECK 제약
- `sort_order`가 0 이상 9 이하인 CHECK 제약
- 세 가지 허용 MIME type CHECK 제약
- `is_representative = true`인 행에만 적용되는 상품별 부분 유일 인덱스

부분 유일 인덱스는 대표 이미지가 최대 한 장임을 보장한다. 최초 등록에서 최소 한 장이 대표 이미지라는 규칙은 Domain과 Application이 보장하고 같은 트랜잭션에서 이미지와 `READY` 상태를 저장한다.

### S3와 애플리케이션 설정

`apps/backend/build.gradle.kts`에는 AWS SDK for Java 2.x BOM 또는 Spring Boot dependency management에서 확인된 호환 버전을 사용해 S3 모듈만 추가한다. 구현 시 `dependencyInsight`로 실제 해석 버전을 확인하고 개별 라이브러리 버전을 여러 곳에 흩어 쓰지 않는다.

`application.yaml`에는 다음 의미의 설정을 추가한다. 환경변수명은 기존 대문자 snake case 규칙을 따른다.

- `PRODUCT_IMAGE_S3_BUCKET`: 기본값 없는 비공개 버킷명
- `PRODUCT_IMAGE_S3_REGION`: 기본값 없는 AWS 리전
- `PRODUCT_IMAGE_S3_PREFIX`: 기본값을 둘 수 있는 상품 이미지 객체 prefix
- Spring multipart 개별 파일 및 요청 크기 제한

credential은 설정에 추가하지 않고 AWS 기본 credential provider chain을 사용한다. Kubernetes에서는 Pod Identity 또는 동등한 workload identity를 전제로 한다. S3 client는 설정된 리전과 기본 credential chain으로 생성한다.

Context Test와 통합 테스트에는 실제 버킷이 아닌 명시적인 dummy 버킷·리전 설정과 S3 저장소 대역을 제공한다. 테스트 시작 과정에서 로컬 AWS credential 탐색이나 네트워크 호출이 발생하지 않아야 하며, 운영 설정의 필수값 검증은 별도 설정 바인딩 테스트로 확인한다.

운영 환경은 대상 prefix에 대한 `PutObject`, `DeleteObject`와 cleanup tagging 최소 권한, 버킷 암호화, Block Public Access를 제공해야 한다. 보상 삭제 실패 객체에만 cleanup 태그를 붙이고 Lifecycle 정책은 그 태그가 있는 객체만 만료시켜야 한다. 정상 이미지가 포함된 prefix 전체에 기간 기반 만료를 적용하지 않는다. 실제 버킷·IAM·Lifecycle 생성은 저장소에 인프라 코드가 확인되지 않았으므로 이 계획의 구현 범위가 아니다.

## 작업 계획

구현은 테스트 주도 방식으로 진행한다. 각 마일스톤에서 먼저 실패하는 테스트를 작성하고 해당 테스트를 통과시키는 최소 구현을 추가한 뒤 관련 테스트를 다시 실행한다.

### 마일스톤 1: 이미지 도메인과 PostgreSQL 계약 확립

`ProductTest`에 `DRAFT → READY` 정상 전이와 이미 `READY`인 상품의 재전이 거부 테스트를 먼저 추가하고, `Product`가 상태를 도메인 메서드로만 변경하도록 보완한다. `ProductImage` 생성 규칙을 Spring Context 없는 테스트로 고정하고 `ProductImageRepository`를 정의한다.

`V2__create_product_images.sql`과 JPA 매핑을 추가한 뒤 PostgreSQL Testcontainers에서 정상 이미지 여러 장의 순서와 대표 여부가 보존되는지 검증한다. 이어 중복 순서, 두 대표 이미지, 중복 객체 키, 범위 밖 크기·순서와 허용하지 않은 MIME type이 실제 DB 제약에서 거부되는지 검증한다. `ProductRepository`와 JPA adapter에는 일반 조회와 비관적 잠금 조회를 추가하고 기존 테스트 대역을 새 계약에 맞게 보완한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.domain.*' --tests 'io.github.sehako.japda.product.infrastructure.*'

예상 관찰 결과: 이미지와 상품 상태 규칙이 단위 테스트에서 고정되고, JPA가 `product_images` Flyway 스키마를 검증하며 모든 DB 제약과 상품 잠금 조회 테스트가 PostgreSQL에서 통과한다.

### 마일스톤 2: S3 저장 adapter와 파일 검증 완성

AWS SDK S3 dependency와 버킷·리전·prefix 설정을 추가한다. 허용 파일 시그니처와 선언 MIME type 일치, 개별 10MiB와 합계 50MiB, 최대 10장, 대표 인덱스를 검사하는 단위를 작성한다. 검증된 형식에서 확장자를 선택하고 주입 가능한 키 생성기로 예측 가능한 객체 키를 만들게 한다.

객체 저장 인터페이스와 `S3ProductImageStorage`를 추가한다. AWS SDK client 대역으로 put 요청의 버킷, 키, MIME type과 콘텐츠 길이, delete 요청과 cleanup tagging 요청을 검증한다. SDK 예외는 공개 가능한 저장소 예외로 변환하되 로그나 예외 응답에 credential과 S3 내부 응답을 포함하지 않는다.

기존 `BackendApplicationTests`와 상품 통합 테스트에는 dummy S3 설정과 저장소 대역을 적용해 새 필수 설정 때문에 기존 Context Test가 깨지지 않도록 한다. 반대로 운영용 설정 바인딩 테스트는 버킷이나 리전이 빠졌을 때 애플리케이션이 모호한 런타임 오류 대신 시작 단계에서 실패하는지 확인한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.application.*' --tests 'io.github.sehako.japda.product.infrastructure.*'

예상 관찰 결과: 허용 형식과 모든 경계 용량이 결정적으로 판정되고, S3 adapter가 올바른 요청을 구성하며 실제 AWS 자격 증명이나 네트워크 없이 대상 테스트가 통과한다.

### 마일스톤 3: 업로드 조율과 보상 흐름 완성

`ProductImageService`는 요청 전체 검증과 일반 상품 조회를 수행한 뒤 S3 객체를 순차 저장하고 성공한 키를 추적한다. 모든 객체 저장이 끝난 뒤 별도 트랜잭션 컴포넌트를 호출한다. 트랜잭션 컴포넌트는 상품을 잠금 조회하고 존재·소유권·`DRAFT` 상태와 기존 이미지 부재를 다시 확인한 다음 이미지 메타데이터 저장과 `READY` 전환을 한 트랜잭션으로 완료한다.

Application 단위 테스트는 정상 흐름, 소유권 불일치, 기존 이미지, `READY` 상품, 두 동시 요청의 최종 충돌, 중간 S3 실패, DB 실패를 검증한다. 실패 시 이번 요청의 성공 객체만 역순으로 삭제하는지 확인하고, 삭제 실패가 원래 오류를 덮지 않으며 cleanup tagging과 로그가 시도되는지 검증한다. S3 호출 동안 트랜잭션이 열리지 않는 구조도 컴포넌트 경계 테스트로 고정한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.application.*'

예상 관찰 결과: 정상 요청만 모든 이미지와 `READY` 상태를 저장하고 각 실패 시나리오가 DB 부분 저장 없이 S3 보상 동작과 정해진 Application 오류를 남긴다.

### 마일스톤 4: multipart HTTP API와 오류 계약 제공

기존 상품 생성 Controller와 분리한 `ProductImageController`에 `POST /api/products/{productId}/images`를 추가한다. request converter는 `X-Seller-Id`, 경로 ID, 반복 `files` part와 `representativeIndex`를 Application 입력으로 변환한다. 정상 multipart 요청의 `201` 응답과 이미지 순서·대표 여부·`READY` 상태를 standalone MockMvc 테스트로 먼저 고정한다.

`ProductExceptionHandler`의 대상 Controller를 확장하고 `400`, `404`, `409`, `413`, `503`, `500`을 합의된 `ProblemDetail`로 변환한다. Spring multipart resolver가 Controller 전에 발생시키는 크기 초과 예외도 Spring Boot 통합 테스트에서 `413`으로 검증한다. 기존 `POST /api/products`의 JSON 계약과 `405`, `415` 처리가 바뀌지 않았는지 회귀 테스트를 실행한다.

`apps/backend`에서 실행한다.

    ./gradlew test --tests 'io.github.sehako.japda.product.presentation.*'

예상 관찰 결과: 정상 multipart 요청은 `201` 응답을 반환하고 모든 입력·상태·외부 저장 오류가 내부 정보 없는 합의된 `ProblemDetail` 상태와 본문으로 변환되며 기존 상품 등록 API 테스트도 통과한다.

### 마일스톤 5: HTTP-S3-PostgreSQL 흐름 통합 검증

S3 저장소는 테스트 대역으로 교체하고 PostgreSQL은 기존 Testcontainers 설정을 사용하는 Spring Boot 통합 테스트를 추가한다. 먼저 기존 API로 `DRAFT` 상품을 생성한 다음 이미지 multipart 요청을 보내 DB의 이미지 순서·대표 여부·메타데이터와 상품 `READY` 상태를 확인한다. 다른 판매자, 재업로드와 저장 실패 시나리오에서 DB가 변경되지 않고 성공한 S3 객체만 보상 삭제되는지도 검증한다.

전체 백엔드 테스트와 빌드를 실행하고 실제 해석된 AWS SDK dependency를 확인한다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew dependencyInsight --dependency software.amazon.awssdk:s3 --configuration runtimeClasspath
    ./gradlew build

예상 관찰 결과: 전체 테스트와 빌드가 종료 코드 0으로 완료되고, S3 모듈 버전이 하나로 해석되며 실제 AWS 계정 없이 상품 생성부터 이미지 메타데이터 저장과 상태 전환까지 검증된다.

## 검증

- 정상 업로드: 이미지 1장과 10장 경계에서 `201`, 연속 정렬 순서, 대표 이미지 한 장과 `READY` 상태를 반환하고 같은 값이 PostgreSQL과 S3 저장 대역에 기록된다.
- 대표 선택: 첫 번째, 중간, 마지막 인덱스를 각각 대표로 지정해 요청 순서와 대표 여부가 보존된다.
- 파일 검증: JPEG·PNG·WebP의 선언 MIME type과 시그니처가 모두 일치할 때만 허용되고 확장자 위장, MIME type 불일치, SVG, GIF와 빈 파일은 `400`이다.
- 개수와 용량: 파일 없음, 11장, 개별 10MiB 초과, 합계 50MiB 초과와 Spring 전역 요청 한도 초과가 각각 합의된 `400` 또는 `413`이다.
- 상품 경계: 없는 상품과 다른 판매자 상품은 같은 `404`, 이미지가 있거나 `READY`인 상품은 `409`이며 DB와 S3가 바뀌지 않는다.
- 정합성: 중간 S3 실패와 최종 DB 실패는 성공 객체를 보상 삭제하고 이미지 행이나 `READY` 상태를 남기지 않는다.
- 보상 실패: 삭제 실패는 원래 오류를 보존하고 cleanup tagging을 시도하며 객체 키는 응답에 노출하지 않는다.
- 동시성: 같은 `DRAFT` 상품의 동시 최초 업로드 중 한 요청만 이미지 저장과 `READY` 전환에 성공하고 다른 요청의 객체는 보상 삭제된다.
- 영속성: 상품별 정렬 순서, 대표 이미지와 객체 키 유일성, 크기·형식 CHECK 제약이 PostgreSQL에서 동작한다.
- 회귀: 기존 `POST /api/products`의 `201`, 입력 검증, `ProblemDetail`, PostgreSQL 저장과 프로토콜 테스트가 계속 통과한다.
- 설정·보안: 버킷과 리전 누락은 명확한 시작 실패로 드러나고 credential, 버킷명, 객체 키와 SDK 내부 정보가 오류 응답에 포함되지 않는다.

`apps/backend`에서 실행한다.

    ./gradlew clean test
    ./gradlew dependencyInsight --dependency software.amazon.awssdk:s3 --configuration runtimeClasspath
    ./gradlew build

예상 관찰 결과: 세 명령이 종료 코드 0으로 완료된다. Docker를 사용할 수 없으면 PostgreSQL Testcontainers 검증을 다른 DB로 대체하지 않고 미검증 항목을 `예상 밖의 발견`과 `결과와 회고`에 기록한다. 실제 AWS 버킷 연동은 자동 테스트 범위가 아니므로 배포 환경 smoke test가 별도로 필요하다.

## 위험과 완화

- S3와 PostgreSQL은 원자적으로 커밋할 수 없다. S3 선저장, 짧은 DB 트랜잭션, 요청별 성공 키 추적과 보상 삭제로 부분 실패를 완화한다.
- 보상 삭제와 cleanup tagging이 모두 실패하면 고아 객체가 남을 수 있다. 원래 오류를 보존하면서 식별 가능한 로그와 경보 근거를 남기고 운영에서 수동 또는 후속 reconciliation 대상으로 처리한다.
- 백엔드가 최대 50MiB 요청을 중계하므로 파드의 메모리·임시 디스크와 요청 시간이 증가한다. 파일을 불필요하게 `ByteArray`로 복제하지 않고 재개 가능한 스트림 형태로 전달하며 Spring과 ingress의 요청 한도·timeout을 함께 맞춘다.
- 비관적 잠금 전에 S3 업로드하므로 동시 요청은 불필요한 객체를 만들 수 있다. S3 I/O 동안 DB 잠금을 유지하지 않는 대신 패배 요청을 보상 삭제하는 비용을 감수한다.
- 현재 `X-Seller-Id`는 위조할 수 있다. 이 계획에서는 기존 001 계약과 일관되게 사용하되 운영 인증 수단으로 표현하지 않는다.
- 별도 인증 worktree가 먼저 병합되면 JWT `sub` 기반 판매자 ID 공급과 Flyway 번호가 현재 계획과 달라진다. 구현 시작 전에 최신 branch를 조사해 Controller 입력, 보안 테스트와 migration 번호만 조정하고 이미지 Application 계약은 유지한다.
- 인증 worktree에도 `V1` migration이 존재하는 현재 상태에서는 Flyway version 충돌이 발생한다. 이 계획을 실행하기 전에 병합 순서에 따라 기존 migration 번호 충돌이 해소됐는지 확인한다.
- 실제 AWS 연동은 단위·통합 테스트 대역만으로 endpoint, IAM과 네트워크 설정을 완전히 검증할 수 없다. 배포 환경에서 최소 파일 put/delete와 cleanup tagging smoke test를 별도 수행한다.

## 진행 상황

- [x] 2026-09-09 00:00Z 기존 상품 등록 계획, 구현, 테스트, Flyway 스키마와 백엔드 아키텍처를 조사했다.
- [x] 2026-09-09 00:00Z Kubernetes 멀티 인스턴스 조건, S3 저장, multipart 업로드, 대표 이미지와 정렬 순서, 파일 제한, 상태 전이와 실패 보상 방식을 사용자와 확정했다.
- [x] 2026-09-09 00:00Z 별도 인증 worktree의 `002` 계획과 ADR·migration 번호 충돌 가능성을 확인하고 현재 branch 기준 계획으로 작성하기로 결정했다.
- [ ] 구현 시작 전에 최신 branch의 인증 병합 여부, Flyway 번호와 ADR 목록을 재확인한다.
- [ ] 마일스톤 1의 이미지 도메인과 PostgreSQL 계약을 테스트 주도로 구현한다.
- [ ] 마일스톤 2의 파일 검증과 S3 adapter를 테스트 주도로 구현한다.
- [ ] 마일스톤 3의 업로드 조율, 트랜잭션과 보상 흐름을 테스트 주도로 구현한다.
- [ ] 마일스톤 4의 multipart HTTP API와 오류 계약을 테스트 주도로 구현한다.
- [ ] 마일스톤 5의 통합·회귀 테스트와 전체 검증을 완료한다.

## 예상 밖의 발견

- 관찰: 현재 상품 구현에는 `READY` enum 값과 DB CHECK 값만 있고 상태 전이 메서드, 상품 조회와 잠금 계약은 없다.
  근거: `Product.status`는 `val`이고 `ProductRepository`에는 `save()`만 있으며 `ProductJpaRepository`에는 별도 query가 없다.
- 관찰: 현재 branch의 유일한 Flyway migration은 `V1__create_products.sql`이지만 인증 worktree에도 아직 병합되지 않은 `V1__create_members.sql`이 존재한다.
  근거: 두 worktree의 `apps/backend/src/main/resources/db/migration/`을 비교했다. 이미지 계획은 현재 branch에서 `V2`를 사용하되 선행 병합 시 재번호화가 필요하다.
- 관찰: 인증 worktree의 `002` 계획은 JWT cookie의 `sub`를 내부 회원 ID로 사용하며 `AuthController.me()`는 `@AuthenticationPrincipal Jwt`에서 해당 값을 읽는다.
  근거: 인증 worktree의 `NimbusAccessTokenIssuer`, `CookieBearerTokenResolver`, `AuthController`와 `SecurityConfig`를 확인했다. 현재 상품 branch에는 이 코드가 없다.
- 관찰: 기존 상품 Controller에만 범위가 제한된 `ProductExceptionHandler`가 있어 새 Controller의 도메인·Application 오류는 자동으로 같은 응답 계약을 사용하지 않는다.
  근거: `@RestControllerAdvice(assignableTypes = [ProductController::class])` 선언을 확인했다.

## 결정 기록

- 결정: Kubernetes 멀티 인스턴스의 상품 이미지 원본은 비공개 AWS S3 버킷에 저장하고 DB에는 `objectKey`와 메타데이터만 저장한다.
  이유: 파드 로컬 파일 유실과 인스턴스 간 불일치를 피하고 애플리케이션 수명과 이미지 수명을 분리하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 기존 상품 생성 API는 유지하고 `POST /api/products/{productId}/images`가 여러 이미지를 multipart로 받는다.
  이유: 001 계획의 `DRAFT` 우선 생성과 실패 재시도 경계를 보존하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 한 요청에서 이미지 1장 이상 10장 이하와 `representativeIndex`를 받고 파일 배열 순서를 정렬 순서로 저장한다.
  이유: 최초 이미지 집합의 대표 유일성과 순서를 한 번에 검증하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 대표 이미지가 포함된 이미지 메타데이터 저장과 같은 트랜잭션에서 상품을 `DRAFT`에서 `READY`로 전환한다.
  이유: 판매 가능한 상품 상태와 필수 이미지 존재가 어긋나지 않게 하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: JPEG, PNG, WebP만 허용하고 파일당 10MiB, 합계 50MiB로 제한하며 선언 MIME type과 실제 파일 시그니처 일치를 검증한다.
  이유: 초기 이미지 요구사항을 충족하면서 서버 자원 사용과 단순 확장자 위장을 제한하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: S3에 먼저 순차 업로드하고 짧은 DB 트랜잭션으로 메타데이터와 상태를 반영하며 실패 시 이번 요청의 객체를 보상 삭제한다.
  이유: S3 I/O 동안 DB 트랜잭션을 유지하지 않으면서 부분 실패를 처리하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 최초 이미지 등록만 허용하고 이미 이미지가 있거나 `READY`인 상품은 `409`로 거절한다.
  이유: 이미지 추가·삭제·교체와 재정렬을 현재 기능에서 분리해 최초 등록 계약을 명확히 유지하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: 현재 branch 기준으로 `X-Seller-Id`와 `V2__create_product_images.sql`을 사용한다.
  이유: 현재 구현 가능한 코드와 001 API 계약을 기준으로 자급자족하는 계획을 만들고, 인증 branch의 구현·병합 시점에 JWT 주체와 migration 순서를 별도로 조정하기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex
- 결정: S3 객체는 비공개로 저장하고 이미지 조회 URL과 CloudFront 연동은 후속 기능으로 분리한다.
  이유: 저장과 전달 정책을 분리하고 현재 업로드 기능에 필요하지 않은 공개 경로 설계를 미루기 위해서다.
  일자/작성자: 2026-09-09, 사용자와 Codex

## 결과와 회고

아직 구현을 시작하지 않았다. 실행 중 완료한 기능, 검증 결과, 남은 운영 작업과 후속 개선 사항을 여기에 기록한다.
