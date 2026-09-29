# JAPDA

> 한정판 상품을 잡다. Limited goods. Catch yours.

JAPDA는 한정판 상품의 판매, 주문, 결제와 판매자 정산까지 커머스의 핵심 흐름을 구현하는 프로젝트다. 한정된 재고를 안전하게 관리하고 구매자와 판매자에게 필요한 기능을 제공한다.

## 주요 기능

### 구매자

- 판매 중인 상품 목록과 상세 정보 조회
- 배송지 등록과 체크아웃
- 주문 생성과 주문 내역 조회
- Toss Payments 결제 승인과 결과 처리
- Google 계정을 이용한 로그인

### 판매자

- 상품과 상품 이미지 등록
- 판매 일정과 판매 수량 설정
- 판매 가능한 상품 조회

### 정산

- 결제 승인 시점의 정산 원장 기록
- 판매자별 일일 정산 처리
- Spring Batch 기반 분할 정산 작업

제품의 전체 목표와 범위는 [제품 요구사항](docs/prd.md)에서 확인할 수 있다.

## 기술 스택

### Backend

- Kotlin, Spring Boot
- Spring MVC, Spring Security, Spring Data JPA
- Spring Batch, Spring REST Docs
- PostgreSQL, Flyway
- AWS S3, Toss Payments
- Gradle

### Frontend

- TypeScript, React, Vite
- React Router, TanStack Query
- TailwindCSS
- Vitest, Testing Library, Oxlint

## 저장소 구조

```text
japda/
├── apps/
│   ├── backend/
│   │   ├── batch/         # 판매자 정산 Batch 애플리케이션
│   │   └── modules/
│   │       └── ledger/    # 지갑과 원장 모듈
│   └── frontend/          # React 웹 애플리케이션
└── docs/
    ├── architecture/      # 아키텍처 원칙과 ADR
    ├── specs/             # 기능 및 API 명세
    ├── DESIGN.md          # 디자인 시스템
    └── prd.md             # 제품 요구사항
```

## 로컬 실행

### 사전 요구사항

- JDK 25
- Node.js와 npm
- PostgreSQL

Backend 테스트는 Testcontainers를 사용하므로 Docker 호환 컨테이너 실행 환경이 필요하다.

### Backend

`apps/backend`에서 실행한다.

```bash
cd apps/backend
./gradlew bootRun
```

기본 데이터베이스 접속 주소는 `jdbc:postgresql://localhost:5432/japda`다. 접속 정보와 외부 서비스 설정은 실행 환경에 맞게 환경 변수로 지정한다. 애플리케이션 시작 시 Flyway가 데이터베이스 migration을 실행한다.

정산 Batch 애플리케이션은 다음 명령으로 실행한다.

```bash
./gradlew :batch:bootRun
```

Batch 애플리케이션은 같은 PostgreSQL 데이터베이스와 Spring Batch metadata schema를 사용하며, 시작만으로 정산 Job을 자동 실행하지 않는다.

### Frontend

`apps/frontend`에서 실행한다.

```bash
cd apps/frontend
npm install
npm run dev
```

개발 서버는 기본적으로 `http://localhost:5173`에서 실행된다. 자세한 내용은 [프론트엔드 실행 안내](apps/frontend/README.md)를 참고한다.

## 환경 변수

### Backend

| 변수 | 설명 | 기본 동작 |
| --- | --- | --- |
| `DB_URL` | PostgreSQL JDBC URL | 로컬 `japda` 데이터베이스 사용 |
| `DB_USERNAME` | 데이터베이스 사용자 | 로컬 개발 기본값 사용 |
| `DB_PASSWORD` | 데이터베이스 비밀번호 | 로컬 개발 기본값 사용 |
| `GOOGLE_CLIENT_ID` | Google OAuth Client ID | 필수 |
| `GOOGLE_CLIENT_SECRET` | Google OAuth Client Secret | 필수 |
| `AUTH_JWT_SIGNING_KEY` | JWT 서명 키 | 실행 환경에서 지정 |
| `AWS_S3_BUCKET` | 상품 이미지 저장용 S3 bucket | 필수 |
| `AWS_REGION` | S3 bucket의 AWS region | 필수 |
| `TOSS_CLIENT_MODE` | Toss Payments client 모드 | `fake` |
| `TOSS_SECRET_KEY` | Toss Payments secret key | 실제 client 사용 시 지정 |

전체 설정은 [`application.yaml`](apps/backend/src/main/resources/application.yaml)과 [Batch `application.yaml`](apps/backend/batch/src/main/resources/application.yaml)에서 확인할 수 있다.

### Frontend

| 변수 | 설명 | 기본 동작 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | Backend API 기본 주소 | 현재 origin 사용 |
| `VITE_IMAGE_BASE_URL` | 상품 이미지 기본 주소 | 현재 origin 사용 |
| `VITE_TOSS_PAYMENT_PREVIEW_ENABLED` | 테스트 결제 화면 활성화 여부 | 비활성화 |
| `VITE_TOSS_CLIENT_KEY` | Toss Payments test client key | 결제 화면 활성화 시 지정 |

비밀번호와 API key 등 민감정보는 저장소에 커밋하지 않는다.

## 검증

Backend 전체 테스트와 빌드는 `apps/backend`에서 실행한다.

```bash
./gradlew test
./gradlew build
```

Frontend 테스트와 정적 검사, 빌드는 `apps/frontend`에서 실행한다.

```bash
npm run test
npm run lint
npm run build
```

## API 문서

Backend API 문서는 테스트에서 생성한 Spring REST Docs snippet을 사용한다.

```bash
cd apps/backend
./gradlew asciidoctor
```

생성된 문서는 `apps/backend/build/docs/asciidoc/index.html`에서 확인할 수 있다. Backend 실행 파일을 빌드하면 같은 문서가 `/docs/index.html` 경로에 포함된다.

## 프로젝트 문서

- [제품 요구사항](docs/prd.md)
- [Backend 아키텍처](docs/architecture/backend.md)
- [Frontend 아키텍처](docs/architecture/frontend.md)
- [디자인 시스템](docs/DESIGN.md)
- [ADR 목록](docs/architecture/decisions/README.md)
- [Backend API 문서 진입점](apps/backend/src/docs/asciidoc/index.adoc)
- [Backend 기능 명세](docs/specs/backend)
