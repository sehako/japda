# 판매자 상품 이미지 최초 등록 API 설계

## 목적과 완료 조건

판매자가 생성한 `DRAFT` 상품에 이미지 1~10장과 대표 이미지 정확히 1장을 등록하고, 등록이 완료되면 상품을 `READY`로 전환한다. `READY`는 판매 일정을 등록할 준비가 된 상태이며 판매 시작을 의미하지 않는다.

다음 조건을 모두 만족하면 완료된 것으로 본다.

- 상품 소유자가 이번 스펙의 최소 검증을 통과한 이미지와 대표 이미지 지정을 전송하면 S3에 원본을 저장하고 DB에 이미지 메타데이터를 저장한다.
- 이미지 메타데이터 전체 저장과 `DRAFT → READY` 전환은 하나의 DB 트랜잭션으로 처리한다.
- 잘못된 요청이나 업로드 실패로 일부 이미지만 등록된 상품을 만들지 않는다.
- 같은 상품에 동시 등록 요청이 들어와도 하나만 성공한다.
- 성공·실패 계약을 테스트하고 Spring REST Docs로 문서화한다.

## 기존 구조와 범위

기존 `POST /api/products`는 상품명과 선택적인 설명을 받아 `DRAFT` 상품을 생성한다. 해당 요청·응답 계약은 유지하며, 생성된 상품 ID로 별도 이미지 등록 API를 호출한다. 이미지가 없는 `DRAFT` 상품은 허용하지만 `READY` 상품에는 이미지 1~10장과 대표 이미지 정확히 1장이 필요하다.

기존 [상품 기본 정보 등록 스펙](seller-product-registration-api.md), [백엔드 아키텍처](../../../architecture/backend.md), [S3 저장 결정](../../../architecture/decisions/ADR-005-product-image-storage-with-s3.md), [상품 이미지 패키지 구성 결정](../../../architecture/decisions/ADR-006-product-image-package-structure.md), [Application 계층의 Spring Web 타입 분리 결정](../../../architecture/decisions/ADR-007-application-layer-spring-web-independence.md), [파일 시그니처 검증 결정](../../../architecture/decisions/ADR-008-product-image-signature-validation.md), [역할별 하위 패키지 구성 결정](../../../architecture/decisions/ADR-016-backend-role-based-package-structure.md)을 따른다.

포함 범위는 백엔드 최초 이미지 등록 API, 파일 검증, S3 저장, DB 메타데이터, 상태 전환, 실패 시 보상 삭제, 관련 테스트와 API 문서이다.

다음 항목은 제외한다.

- 프런트엔드 화면, 상품·이미지 조회 API와 다운로드 URL 발급
- CloudFront 구성과 Presigned URL 발급
- 이미지 추가·교체·삭제, 대표 이미지 변경
- 리사이즈·압축·썸네일 생성
- 이미지 전체 디코딩을 통한 손상 여부 정밀 검증과 디코딩 자원 제한
- WebP 디코더 등 정밀 검증을 위한 외부 라이브러리 도입
- 판매 일정 생성과 판매 시작
- 실제 판매자 인증과 계정 존재 확인
- 멱등 키와 성공 응답 재생, 자동 고아 객체 정리 작업

## 요청 계약

```http
POST /api/products/42/images
X-Seller-Id: 1
Content-Type: multipart/form-data; boundary=...
```

| 항목 | 계약 |
| --- | --- |
| `productId` | 양의 `Long` 상품 ID |
| `X-Seller-Id` | 기존 API와 동일한 임시 판매자 식별 헤더, 양의 `Long` |
| `files` | 같은 이름으로 반복되는 파일 파트, 1~10개 |
| `representativeIndex` | 정확히 하나의 일반 폼 파트, 0부터 `files` 개수 - 1까지의 필수 정수 |

`representativeIndex` 파트 예시는 다음과 같다.

```http
Content-Disposition: form-data; name="representativeIndex"

1
```

위 요청은 두 번째 파일을 대표 이미지로 지정한다. `files` 파트의 수신 순서를 0부터 시작하는 표시 순서로 저장한다. 파일명은 식별 기준으로 사용하지 않으므로 같은 파일명을 가진 파일도 허용한다. 동일 내용 파일의 중복 제거는 수행하지 않는다.

대표 이미지 인덱스의 누락, 빈 값, 정수가 아닌 값, 범위 초과는 거부한다. 중복된 `representativeIndex` 파트도 거부해 대표 지정의 모호성을 허용하지 않는다. 잘못된 요청을 첫 번째 이미지 선택으로 보정하지 않는다.

## 파일 정책

- JPEG, PNG, WebP 원본만 허용한다.
- 각 파일은 1바이트 이상, 최대 `10 * 1024 * 1024`바이트이다.
- 파일 크기의 합계는 최대 `50 * 1024 * 1024`바이트이다. 상한과 동일한 크기는 허용한다.
- 확장자와 클라이언트가 보낸 `Content-Type`은 신뢰하지 않는다. 서버가 JPEG·PNG·WebP의 파일 시그니처로 형식을 판별한다.
- DB와 S3의 미디어 타입은 파일 시그니처로 판별한 형식으로 결정한다. 지원 형식의 시그니처가 없거나 형식 판별에 필요한 최소 헤더가 완전하지 않은 파일은 거부한다.
- 모든 파일의 검증이 끝나기 전에 S3 업로드를 시작하지 않는다.

지원 형식의 파일 시그니처는 다음과 같이 판별한다.

| 형식 | 파일 시작 바이트 |
| --- | --- |
| JPEG | `FF D8 FF` |
| PNG | `89 50 4E 47 0D 0A 1A 0A` |
| WebP | 0~3바이트가 ASCII `RIFF`이고 8~11바이트가 ASCII `WEBP` |

빈 파일은 `PRODUCT_IMAGE_FILE_INVALID`, 지원하는 시그니처와 일치하지 않거나 시그니처 판별에 필요한 바이트가 부족한 파일은 `PRODUCT_IMAGE_FORMAT_UNSUPPORTED`로 처리한다.

파일 합계 제한과 multipart 전체 요청 제한은 구분한다. multipart 경계와 메타데이터 오버헤드를 수용하도록 전체 요청 제한의 기본값은 51MiB로 두되, 파일 합계 50MiB 검증은 별도로 수행한다. 파트별 파일 제한은 10MiB로 설정한다. 크기 제한은 상수 또는 설정값으로 관리하며 프레임워크 단계의 초과 오류도 `ProblemDetail`로 변환한다.

파일 전체를 일괄 메모리에 적재하지 않고 제한된 임시 저장과 순차 검증을 사용한다. 이번 API는 새로운 외부 이미지 라이브러리를 추가하지 않고 파일 시그니처까지만 검증한다. 시그니처는 정상이지만 내부 데이터가 손상된 이미지가 등록될 수 있다는 한계를 허용한다. 이미지 전체 디코딩과 압축 해제 과정의 자원 제한은 별도 후속 이슈에서 다룬다.

## 소유권과 최초 등록 조건

상품이 없으면 `404 Not Found`, 요청 판매자와 상품 소유자가 다르면 `403 Forbidden`을 반환한다. 소유권 확인 후 상품 상태를 검사하며, 이미 `READY`이면 `409 Conflict`를 반환한다.

`X-Seller-Id`는 위조 가능한 임시 식별값이다. 소유자 ID 비교는 수행하지만 실제 인증을 보장하지 않으며, 향후 인증 principal로 교체한다.

`DRAFT` 상품의 최초 이미지 묶음 등록만 지원한다. 등록 완료 이후의 추가나 교체 요청은 이 API로 처리하지 않는다.

## 계층과 데이터 흐름

1. `presentation`이 헤더·경로·multipart 구조와 JSON 형식을 검증하고 application 입력으로 변환한다.
2. `application`이 상품 존재·소유권·상태를 사전 확인한다.
3. 모든 파일의 개수·크기·파일 시그니처와 대표 이미지 지정이 유효한지 확인한다.
4. 요청별 고유 키를 생성하고 S3에 파일을 업로드한다. 이 단계에는 DB 트랜잭션이나 행 잠금을 유지하지 않는다.
5. 짧은 DB 트랜잭션을 시작하고 상품 행을 잠근 뒤 소유권과 `DRAFT` 상태를 다시 확인한다.
6. 이미지 메타데이터 전체를 저장하고 도메인 메서드로 상품을 `READY`로 전환한다.
7. DB 커밋이 완료된 후 성공 응답을 반환한다.

`presentation`은 `MultipartFile`과 HTTP 파트 처리를 소유하고, Spring Web 타입을 application과 domain에 전달하지 않는다. `application`은 파일 검증·저장·보상 처리의 순서와 트랜잭션을 조율한다. `domain`은 이미지 등록 규칙, 상품 상태 전환과 Repository 계약을 소유한다. `infrastructure`는 JPA 영속성과 S3 연동의 기술 구현을 담당한다.

Application의 `ProductImageFile` 입력 인터페이스는 파일 크기와 매번 새로운 `InputStream`을 여는 기능을 제공한다. Presentation의 multipart 어댑터가 `MultipartFile`을 이 인터페이스로 변환하며, application은 사용한 스트림을 닫는다. 시그니처 검증은 첫 번째 스트림에서 판별에 필요한 선두 바이트만 읽고, S3 업로드는 새 스트림을 열어 전체 원본을 전송한다. 원본 파일명과 클라이언트가 보낸 미디어 타입은 application의 판별 근거로 전달하지 않는다.

`ProductImageRegistrationService`는 트랜잭션 없이 사전 확인, 최소 파일 검증, S3 업로드와 실패 보상을 조율한다. 별도 Spring Bean인 `ProductImageRegistrationCommitService`가 상품 행 잠금, 소유권과 상태 재확인, 이미지 메타데이터 저장 및 `READY` 전환을 하나의 짧은 `@Transactional` 메서드에서 수행한다. 동일 객체 내부 호출에 의존하지 않고 Service 사이 호출로 Spring transaction proxy를 적용한다.

이미지 기능은 별도 최상위 도메인으로 분리하지 않는다. 기존 `product` 도메인의 계층 구조를 유지하면서 이미지 관련 파일을 `product/{layer}/image/{role}` 순서의 역할별 하위 패키지에 둔다. `Product`와 `ProductStatus`는 `product/domain/model`에 둔다.

S3 저장·삭제는 상품 영역의 저장소 인터페이스로 추상화한다. 인터페이스에는 AWS SDK 타입을 노출하지 않으며, 실제로 다른 도메인이 공유하기 전에는 공통 파일 업로드 모듈로 확장하지 않는다.

## 영속성

새 Flyway migration으로 `product_images`를 추가하고 상품 상태 제약에 `READY`를 추가한다. 이미 적용된 migration은 수정하지 않는다.

| 열 | 의미와 제약 |
| --- | --- |
| `id` | DB 생성 이미지 식별자, 기본 키 |
| `product_id` | `products.id` 외래 키, 필수 |
| `object_key` | S3 객체 키, 필수, 유일 |
| `content_type` | 검증된 `image/jpeg`, `image/png`, `image/webp` 중 하나 |
| `size_bytes` | 원본 바이트 크기, 1~10MiB |
| `display_order` | 0~9, 상품 내 유일 |
| `is_representative` | 대표 여부, 필수 |
| `created_at` | 저장 시각, `Instant`에 대응하는 timestamp |

상품당 대표 이미지가 최대 하나임을 부분 유일 인덱스로 보호한다. 최소 이미지 수와 대표 이미지가 정확히 하나라는 규칙은 전체 묶음 검증과 상태 전환 트랜잭션으로 보장한다. 파일 합계 크기도 application의 묶음 검증으로 보장한다.

`Product`에 `@OneToMany` 컬렉션을 추가하지 않는다. 이미지 목록은 Repository를 통해 저장·조회하며 상품 상태 변경은 도메인 메서드로 수행한다. 상태는 공개 setter로 변경하지 않는다.

S3 객체 키는 상품 ID와 요청별·파일별 서버 생성 식별자를 포함해 충돌을 방지한다. 사용자 파일명을 키로 사용하지 않는다. 버킷과 리전은 설정으로 관리하며 DB에는 전체 URL이나 만료되는 서명 URL을 저장하지 않는다.

## S3 접근

버킷은 비공개로 유지한다. 백엔드는 실행 환경의 IAM 권한으로 필요한 객체 업로드·삭제를 수행하며 credential을 코드나 문서에 기록하지 않는다.

비공개 S3 선택이 CloudFront 도입을 의미하지는 않는다. 이미지 조회 기능에서 S3 Presigned URL 또는 CloudFront와 OAC를 선택할 수 있다. 이번 등록 API는 접근 URL을 발급하지 않는다.

## 성공 응답

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "productId": 42,
  "status": "READY",
  "images": [
    {
      "id": 101,
      "displayOrder": 0,
      "isRepresentative": false
    },
    {
      "id": 102,
      "displayOrder": 1,
      "isRepresentative": true
    }
  ]
}
```

`images`는 표시 순서 오름차순이다. Entity, S3 객체 키, credential과 내부 저장소 응답을 노출하지 않는다. 이번 API는 조회 URI를 새로 정의하지 않으며 `Location` 헤더는 계약에 포함하지 않는다.

## 오류 계약

기존 `application/problem+json`과 `ProblemDetail` 계약을 따른다. 상품 오류는 `PRODUCT_*`, HTTP 형식과 공통 요청 오류는 `COMMON_*`로 구분한다. 내부 SDK 오류나 SQL, credential은 응답에 포함하지 않는다.

| 상황 | HTTP 상태 | 오류 코드 |
| --- | --- | --- |
| 판매자 헤더 누락·형식 오류 | 400 | 기존 `COMMON_REQUEST_HEADER_MISSING`, `COMMON_REQUEST_HEADER_INVALID` |
| 판매자 ID가 0 이하 | 400 | 기존 `PRODUCT_SELLER_ID_INVALID` |
| 상품 ID 형식 오류 | 400 | `COMMON_REQUEST_PARAMETER_INVALID` |
| 상품 ID가 0 이하 | 400 | `PRODUCT_ID_INVALID` |
| multipart 구조 오류 | 400 | `COMMON_REQUEST_BODY_MALFORMED` |
| 이미지가 0장 또는 10장 초과 | 400 | `PRODUCT_IMAGE_COUNT_INVALID` |
| 대표 이미지 지정 오류 | 400 | `PRODUCT_IMAGE_REPRESENTATIVE_INVALID` |
| 빈 파일 | 400 | `PRODUCT_IMAGE_FILE_INVALID` |
| 지원하지 않는 시그니처·시그니처 판별 바이트 부족 | 400 | `PRODUCT_IMAGE_FORMAT_UNSUPPORTED` |
| 파일 또는 합계 크기 초과 | 413 | `PRODUCT_IMAGE_SIZE_EXCEEDED` |
| multipart 파서의 요청 크기 초과 | 413 | `COMMON_REQUEST_SIZE_EXCEEDED` |
| 요청 전체의 지원하지 않는 미디어 타입 | 415 | `COMMON_MEDIA_TYPE_UNSUPPORTED` |
| 상품 없음 | 404 | `PRODUCT_NOT_FOUND` |
| 상품 소유자 불일치 | 403 | `PRODUCT_ACCESS_DENIED` |
| 최초 등록 완료 또는 동시 등록에서 패배 | 409 | `PRODUCT_IMAGES_ALREADY_REGISTERED` |
| S3 저장 실패 | 500 | `PRODUCT_IMAGE_STORAGE_FAILED` |
| DB 저장 실패 등 예상하지 못한 오류 | 500 | 기존 `COMMON_INTERNAL_SERVER_ERROR` |

대표 인덱스의 누락·빈 값·형식·중복·범위 검증 오류는 `representativeIndex`, 개수·파일 오류는 `files`를 논리 속성으로 사용한다. 여러 오류가 있는 요청에서 모든 오류를 수집하는 것은 보장하지 않는다.

기존 오류 분류에 필요한 `FORBIDDEN`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE` 의미를 추가하고 `ProblemDetailFactory`에서 HTTP 상태로 변환한다. 기능별 예외가 HTTP 타입에 의존하거나 공통 handler가 상품 오류를 개별 나열하게 만들지 않는다.

## 실패 보상과 동시성

S3와 DB는 단일 트랜잭션이 아니므로 S3 저장을 DB 롤백으로 취소할 수 없다. 업로드 일부 실패 또는 DB 트랜잭션의 확정된 실패 시 이번 요청에 할당한 객체 키들을 대상으로 보상 삭제를 시도한다. 업로드 응답이 실패했더라도 S3에 객체가 생성됐을 수 있으므로 성공 응답을 받은 파일만 삭제 대상으로 한정하지 않는다.

동시 요청은 서로 다른 객체 키로 업로드한다. 최종 상품 행 잠금 안에서 첫 번째 요청만 `DRAFT`를 확인하고 등록한다. 두 번째 요청은 `READY`를 확인해 `409`를 반환하고 자신이 업로드한 객체만 삭제한다. 다른 요청이 등록한 객체를 삭제하지 않는다.

확정된 등록 실패에서는 해당 요청의 이미지 DB 행을 남기지 않고 `DRAFT`를 유지한다. 단, 동시 요청의 성공으로 상품이 이미 `READY`가 된 경우에는 그 상태를 되돌리지 않는다.

커밋 응답 단절처럼 DB 커밋 성공 여부가 불명확하면 객체를 즉시 삭제하지 않는다. 새 트랜잭션에서 이번 객체 키의 DB 참조 여부를 확인하고, 성공이 확인되면 정상 결과를 반환한다. 확인 자체가 불가능하면 일반 서버 오류를 반환하고 키와 요청 식별자를 운영 로그에 남긴다. 이 경우 실제 상품은 `READY`일 수 있다. DB가 참조하는 정상 객체를 보상 처리로 삭제하지 않는 것을 우선한다.

보상 삭제 실패, 프로세스 중단과 결과 불명확 상황에서는 참조되지 않는 S3 객체가 남을 수 있다. 요청 식별자·상품 ID·객체 키를 기록하되 파일 내용과 credential은 기록하지 않는다. 운영자는 처리 중인 요청이 아님을 확인하고 S3 객체와 DB 참조를 대조해 고아 객체를 정리한다. 정상 이미지가 함께 삭제될 수 있는 전체 이미지 경로의 일괄 만료 정책은 사용하지 않는다. 자동 정리와 장애 후 즉시 정합성 회복은 이번 범위에서 보장하지 않는다.

등록이 커밋된 뒤 응답이 유실되면 동일 요청 재전송도 `409`를 반환한다. 최초 성공 응답을 재현하는 멱등 처리는 제공하지 않는다.

## 검증 전략

- 도메인 테스트: 이미지 1장·10장 허용, 0장·11장 거부, 대표 인덱스 경계, 대표 정확히 1장, `DRAFT → READY`와 중복 전환 거부.
- 파일 검증 테스트: JPEG·PNG·WebP 시그니처, 빈 파일, 불완전한 최소 헤더, 위조 확장자·미디어 타입, 장당·합계 크기의 경계값을 검증한다. 이미지 전체 디코딩 결과는 검증하지 않는다.
- 프레젠테이션 테스트: multipart 수신 순서, `representativeIndex`의 중복·누락·빈 값·형식 오류, 소유권·상태 오류, `201` 응답과 `ProblemDetail` 계약을 검증한다. 같은 테스트에서 Spring REST Docs를 생성한다.
- 애플리케이션 테스트: 검증 실패 시 S3 미호출, 부분 업로드 실패 보상, DB 롤백 보상, 삭제 실패 로그, 커밋 결과 불명확 시 참조 확인과 안전한 보상 판단을 검증한다.
- 영속성 테스트: PostgreSQL Testcontainers로 migration, FK, 표시 순서 유일성, 대표 부분 유일 인덱스와 상태 제약을 검증한다.
- 통합 테스트: HTTP부터 실제 PostgreSQL 저장까지 검증하고, S3 대역으로 성공·실패를 재현한다. 동일 상품에 대한 동시 요청에서 성공 하나, 충돌 하나와 승자 객체 보존을 확인한다.
- S3 어댑터 테스트: 요청 키·본문·시그니처로 판별한 미디어 타입, 업로드·삭제 실패 변환을 검증한다. 실제 AWS 연동 검증 여부는 별도로 보고하며 대역 테스트만으로 실제 IAM·버킷 설정 검증을 주장하지 않는다.
- Gradle 관련 테스트와 `build`로 API 문서 생성을 확인한다.

## 주요 결정과 후속 연결

S3 저장소 선택과 백엔드 경유 업로드는 ADR-005로 기록한다. 상품 이미지 기능의 패키지 구성은 ADR-006, application의 Spring Web 타입 분리는 ADR-007, 최소 파일 검증은 ADR-008을 따른다. 기존 PostgreSQL 영속성, 계층 의존성과 `ProblemDetail` 구조는 유지한다.

이후 이미지 조회 기능은 저장된 객체 키로 접근 URL을 구성할 수 있다. 판매 일정 기능은 `READY` 상태를 전제로 별도 판매 조건을 등록하며, 이미지 수정 기능은 대표·개수·상태 불변식을 유지하는 별도 계약으로 설계한다.
