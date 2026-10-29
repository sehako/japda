# 데이터베이스 스키마

이 문서는 `apps/backend/src/main/resources/db/migration`의 Flyway migration V1부터 V20까지를 순서대로 적용한 현재 PostgreSQL 스키마를 나타낸다. 애플리케이션 업무 테이블과 Spring Batch 메타데이터 테이블을 분리해 표시한다.

## 표기 규칙

- `PK`, `FK`, `UK`는 각각 기본 키, 외래 키, 단일 컬럼 고유 키를 의미한다.
- Mermaid ER 다이어그램의 관계선은 migration에 선언된 물리 외래 키만 나타낸다.
- `buyer_id`, `seller_id`, `ledger_entries.source_id`처럼 도메인 식별자를 저장하지만 외래 키가 없는 컬럼은 관계선으로 연결하지 않는다.
- 컬럼 설명에 `NULL 허용`이 없으면 `NOT NULL`이다. 단, Spring Batch 공식 스키마에서 `NOT NULL`이 명시되지 않은 컬럼도 `NULL 허용`으로 표시한다.
- 복합 고유 제약, 검사 제약, 인덱스처럼 ER 속성으로 충분히 표현되지 않는 구조는 다이어그램 아래에 별도로 정리한다.

## 업무 테이블

```mermaid
erDiagram
    products {
        bigint id PK
        bigint seller_id
        varchar_100 name
        varchar_3000 description "NULL 허용"
        varchar_20 status "DRAFT 또는 READY"
        timestamptz created_at
    }

    product_images {
        bigint id PK
        bigint product_id FK
        varchar_500 object_key UK
        varchar_20 content_type
        bigint size_bytes
        integer display_order
        boolean is_representative
        timestamptz created_at
    }

    sale_days {
        date sale_date PK
        integer capacity
        integer registered_count
    }

    sales {
        bigint id PK
        bigint product_id FK
        bigint seller_id
        date sale_date FK
        bigint price
        integer quantity
        integer committed_quantity "기본값 0"
        timestamptz created_at
    }

    orders {
        bigint id PK
        bigint sale_id FK
        bigint buyer_id
        uuid idempotency_key
        integer quantity
        varchar_100 product_name
        bigint unit_price
        bigint total_price
        varchar_30 status
        varchar_100 recipient_name
        varchar_30 phone_number
        varchar_20 postal_code
        varchar_255 address
        varchar_255 detail_address
        varchar_500 delivery_message "NULL 허용"
        timestamptz created_at
        timestamptz expires_at
        varchar_64 payment_order_id UK
    }

    buyer_shipping_address_books {
        bigint id PK
        bigint buyer_id UK
        timestamptz created_at
    }

    buyer_shipping_addresses {
        bigint id PK
        bigint buyer_shipping_address_book_id FK
        varchar_100 address_name
        varchar_100 recipient_name
        varchar_30 phone_number
        varchar_20 postal_code
        varchar_255 address
        varchar_255 detail_address
        varchar_500 delivery_message "NULL 허용"
        timestamptz created_at
    }

    payments {
        bigint id PK
        bigint order_id FK, UK
        varchar_200 payment_key UK
        varchar_36 toss_idempotency_key UK
        varchar_30 status
        bigint requested_amount
        timestamptz created_at
        timestamptz first_requested_at "NULL 허용"
        timestamptz last_checked_at "NULL 허용"
        timestamptz next_reconcile_at "NULL 허용"
        timestamptz approved_at "NULL 허용"
    }

    users {
        bigint id PK
        varchar_20 provider
        varchar_255 provider_subject
        varchar_320 email
        timestamptz created_at
    }

    user_roles {
        bigint user_id PK, FK
        varchar_20 role PK
    }

    buyer_principal_identities {
        bigint user_id PK, FK
        bigint buyer_id UK
    }

    seller_principal_identities {
        bigint user_id PK, FK
        bigint seller_id UK
    }

    settlement_runs {
        bigint id PK
        date settlement_date UK
        integer platform_fee_rate_bps
        varchar_30 status
        bigint collected_count "기본값 0"
        bigint collected_amount "기본값 0"
        timestamptz started_at
        timestamptz collection_completed_at "NULL 허용"
        timestamptz confirmation_completed_at "NULL 허용"
        timestamptz completed_at "NULL 허용"
        timestamptz created_at
    }

    settlement_details {
        bigint id PK
        bigint settlement_run_id FK
        bigint payment_id FK, UK
        bigint order_id FK
        bigint sale_id FK
        bigint seller_id
        bigint recipient_user_id FK
        integer quantity
        bigint unit_price
        bigint gross_amount
        timestamptz payment_approved_at
        timestamptz created_at
    }

    seller_settlements {
        bigint id PK
        bigint settlement_run_id FK
        bigint seller_id
        bigint recipient_user_id FK
        bigint detail_count
        bigint gross_amount
        bigint platform_fee_amount
        bigint net_amount
        varchar_30 status
        timestamptz confirmed_at
        timestamptz credited_at "NULL 허용"
        timestamptz created_at
    }

    wallets {
        bigint id PK
        bigint user_id FK, UK
        bigint balance "기본값 0"
        timestamptz created_at
        timestamptz updated_at
    }

    ledger_entries {
        bigint id PK
        bigint wallet_id FK
        varchar_20 direction
        bigint amount
        bigint balance_after
        varchar_50 source_type
        bigint source_id
        timestamptz created_at
    }

    settlement_entries {
        bigint id PK
        bigint payment_id FK, UK
        bigint order_id FK
        bigint sale_id FK
        bigint seller_id
        bigint recipient_user_id FK
        integer quantity
        bigint unit_price
        bigint gross_amount
        timestamptz payment_approved_at
        date settlement_date
        timestamptz created_at
    }

    products ||--o{ product_images : "product_id"
    products ||--o{ sales : "product_id"
    sale_days ||--o{ sales : "sale_date"
    sales ||--o{ orders : "sale_id"
    orders ||--o| payments : "order_id"
    buyer_shipping_address_books ||--o{ buyer_shipping_addresses : "buyer_shipping_address_book_id"
    users ||--o{ user_roles : "user_id"
    users ||--o| buyer_principal_identities : "user_id"
    users ||--o| seller_principal_identities : "user_id"
    settlement_runs ||--o{ settlement_details : "settlement_run_id"
    payments ||--o| settlement_details : "payment_id"
    orders ||--o{ settlement_details : "order_id"
    sales ||--o{ settlement_details : "sale_id"
    users ||--o{ settlement_details : "recipient_user_id"
    settlement_runs ||--o{ seller_settlements : "settlement_run_id"
    users ||--o{ seller_settlements : "recipient_user_id"
    users ||--o| wallets : "user_id"
    wallets ||--o{ ledger_entries : "wallet_id"
    payments ||--o| settlement_entries : "payment_id"
    orders ||--o{ settlement_entries : "order_id"
    sales ||--o{ settlement_entries : "sale_id"
    users ||--o{ settlement_entries : "recipient_user_id"
```

### 복합·조건부 고유 제약

| 테이블 | 컬럼 또는 조건 |
| --- | --- |
| `product_images` | `(product_id, display_order)` |
| `product_images` | `product_id WHERE is_representative` |
| `sales` | `(seller_id, sale_date)` |
| `orders` | `(buyer_id, idempotency_key)` |
| `buyer_shipping_addresses` | `(buyer_shipping_address_book_id, address_name)` |
| `users` | `(provider, provider_subject)` |
| `user_roles` | `(user_id, role)` 기본 키 |
| `seller_settlements` | `(settlement_run_id, seller_id)` |
| `ledger_entries` | `(source_type, source_id)` |

### 상태·핵심 검사 제약

| 테이블 | 제약 요약 |
| --- | --- |
| `products` | `status IN ('DRAFT', 'READY')` |
| `product_images` | 지원 이미지 형식, 1~10 MiB 크기, 0~9 표시 순서 |
| `sale_days` | 양수 정원, `0 <= registered_count <= capacity` |
| `sales` | 양수 가격·판매 수량, `0 <= committed_quantity <= quantity` |
| `orders` | `status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'PAYMENT_FAILED')`, 양수 수량·금액, 주문 만료 시각·결제 주문 ID 형식 검증 |
| `payments` | `status IN ('CONFIRMING', 'APPROVED', 'FAILED', 'REVIEW_REQUIRED')`, 양수 요청 금액 |
| `users` | `provider = 'GOOGLE'` |
| `user_roles` | `role IN ('BUYER', 'ADMIN')` |
| `settlement_runs` | 정산 수수료율·집계값 범위, 상태와 완료 시각 일관성 |
| `settlement_details` | 양수 수량·단가·총액 |
| `seller_settlements` | 상태와 입금 시각 일관성, `gross_amount = platform_fee_amount + net_amount` |
| `wallets` | 음수가 아닌 잔액 |
| `ledger_entries` | 방향·원천 종류 제한, 양수 금액, 음수가 아닌 변경 후 잔액 |
| `settlement_entries` | 양수 수량·단가·총액, `gross_amount = quantity * unit_price` |

### 업무 인덱스

기본 키와 고유 제약이 자동으로 생성하는 인덱스는 제외한다.

| 인덱스 | 대상 |
| --- | --- |
| `products_seller_ready_id_idx` | `products(seller_id, status, id) INCLUDE (name)` |
| `products_seller_ready_name_id_idx` | `products(seller_id, status, name COLLATE "C", id)` |
| `orders_active_reservation_idx` | `orders(sale_id, expires_at) WHERE status = 'PENDING_PAYMENT'` |
| `orders_paid_sale_idx` | `orders(sale_id) WHERE status = 'PAID'` |
| `orders_buyer_created_id_idx` | `orders(buyer_id, created_at DESC, id DESC)` |
| `payments_reconcile_idx` | `payments(status, next_reconcile_at, id)` |
| `payments_status_approved_at_id_idx` | `payments(status, approved_at, id)` |
| `payments_settlement_partition_idx` | `payments(status, id) INCLUDE (approved_at)` |
| `settlement_details_run_id_id_idx` | `settlement_details(settlement_run_id, id)` |
| `seller_settlements_run_id_id_idx` | `seller_settlements(settlement_run_id, id)` |
| `ledger_entries_wallet_id_id_idx` | `ledger_entries(wallet_id, id)` |
| `settlement_entries_settlement_date_id_idx` | `settlement_entries(settlement_date, id)` |

## Spring Batch 메타데이터 테이블

```mermaid
erDiagram
    BATCH_JOB_INSTANCE {
        bigint JOB_INSTANCE_ID PK
        bigint VERSION "NULL 허용"
        varchar_100 JOB_NAME
        varchar_32 JOB_KEY
    }

    BATCH_JOB_EXECUTION {
        bigint JOB_EXECUTION_ID PK
        bigint VERSION "NULL 허용"
        bigint JOB_INSTANCE_ID FK
        timestamp CREATE_TIME
        timestamp START_TIME "NULL 허용"
        timestamp END_TIME "NULL 허용"
        varchar_10 STATUS "NULL 허용"
        varchar_2500 EXIT_CODE "NULL 허용"
        varchar_2500 EXIT_MESSAGE "NULL 허용"
        timestamp LAST_UPDATED "NULL 허용"
    }

    BATCH_JOB_EXECUTION_PARAMS {
        bigint JOB_EXECUTION_ID FK
        varchar_100 PARAMETER_NAME
        varchar_100 PARAMETER_TYPE
        varchar_2500 PARAMETER_VALUE "NULL 허용"
        char_1 IDENTIFYING
    }

    BATCH_STEP_EXECUTION {
        bigint STEP_EXECUTION_ID PK
        bigint VERSION
        varchar_100 STEP_NAME
        bigint JOB_EXECUTION_ID FK
        timestamp CREATE_TIME
        timestamp START_TIME "NULL 허용"
        timestamp END_TIME "NULL 허용"
        varchar_10 STATUS "NULL 허용"
        bigint COMMIT_COUNT "NULL 허용"
        bigint READ_COUNT "NULL 허용"
        bigint FILTER_COUNT "NULL 허용"
        bigint WRITE_COUNT "NULL 허용"
        bigint READ_SKIP_COUNT "NULL 허용"
        bigint WRITE_SKIP_COUNT "NULL 허용"
        bigint PROCESS_SKIP_COUNT "NULL 허용"
        bigint ROLLBACK_COUNT "NULL 허용"
        varchar_2500 EXIT_CODE "NULL 허용"
        varchar_2500 EXIT_MESSAGE "NULL 허용"
        timestamp LAST_UPDATED "NULL 허용"
    }

    BATCH_STEP_EXECUTION_CONTEXT {
        bigint STEP_EXECUTION_ID PK, FK
        varchar_2500 SHORT_CONTEXT
        text SERIALIZED_CONTEXT "NULL 허용"
    }

    BATCH_JOB_EXECUTION_CONTEXT {
        bigint JOB_EXECUTION_ID PK, FK
        varchar_2500 SHORT_CONTEXT
        text SERIALIZED_CONTEXT "NULL 허용"
    }

    BATCH_JOB_INSTANCE ||--o{ BATCH_JOB_EXECUTION : "JOB_INSTANCE_ID"
    BATCH_JOB_EXECUTION ||--o{ BATCH_JOB_EXECUTION_PARAMS : "JOB_EXECUTION_ID"
    BATCH_JOB_EXECUTION ||--o{ BATCH_STEP_EXECUTION : "JOB_EXECUTION_ID"
    BATCH_JOB_EXECUTION ||--o| BATCH_JOB_EXECUTION_CONTEXT : "JOB_EXECUTION_ID"
    BATCH_STEP_EXECUTION ||--o| BATCH_STEP_EXECUTION_CONTEXT : "STEP_EXECUTION_ID"
```

`BATCH_JOB_INSTANCE`에는 `(JOB_NAME, JOB_KEY)` 고유 제약이 있다. Spring Batch 식별자 생성을 위해 `BATCH_JOB_INSTANCE_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_STEP_EXECUTION_SEQ` 시퀀스를 사용한다.

## 기타 시퀀스와 제거된 테이블

- `buyer_domain_id_seq`: 구매자 도메인 식별자를 생성한다.
- `sale_inventory_counters`, `inventory_reservations`: V15에서 생성되었지만 V20에서 제거되었으므로 현재 스키마에는 존재하지 않는다.
