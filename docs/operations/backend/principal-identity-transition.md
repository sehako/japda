# 구매자·판매자 주체 ID 연결 전환 절차

이 절차는 인증 주체인 `users.id`와 기존 구매자·판매자 도메인 ID의 소유권을 운영자가 확인한 뒤 연결할 때 사용한다. 과거 요청 헤더, 이메일 또는 숫자 ID 일치만으로 소유권을 추정하지 않는다. 소유권이 불명확하거나 중복 주장이 있는 기록은 연결하지 않고 보존한다.

1. 백엔드 전환본 배포 전, 운영 데이터에서 `orders.buyer_id`, `buyer_shipping_address_books.buyer_id`, `products.seller_id`, `sales.seller_id`의 고유 ID와 최대값을 확인한다. 외부 근거로 계정별 소유권을 검증하고, 충돌·미확인 건은 별도 목록에 남긴다.
2. 신규 구매자 생성과 구매자 ID 발급을 멈춘 상태에서 Flyway `V10`을 적용한다. `V10`은 기존 주문·배송지의 구매자 ID 최댓값을 반영해 `buyer_domain_id_seq`를 시작한다. 기존 도메인 행은 수정하지 않는다.
3. 검증된 계정만 `buyer_principal_identities(user_id, buyer_id)` 또는 `seller_principal_identities(user_id, seller_id)`에 등록한다. 두 테이블의 `user_id`와 도메인 ID는 각각 고유하며 양수여야 한다. 여러 계정 또는 여러 ID가 얽힌 건은 해결 전까지 등록하지 않는다.
4. 등록이 끝난 뒤 신규 구매자 생성을 계속 멈춘 상태에서 `orders`, `buyer_shipping_address_books`, `buyer_principal_identities`의 `buyer_id` 최댓값과 현재 시퀀스 값을 다시 확인한다. `buyer_domain_id_seq`의 다음 발급값을 두 값보다 크게 맞춘다. 이미 발급된 시퀀스 값을 뒤로 되돌리지 않는다. 이후 신규 구매자 생성을 재개한다.
5. 백엔드와 프론트엔드의 JWT 쿠키·CSRF 연동 검증이 끝난 뒤 함께 배포한다. 미연결 사용자는 `403 AUTH_BUYER_LINK_REQUIRED` 또는 `403 AUTH_SELLER_LINK_REQUIRED`를 받는지 확인한다. 연결되지 않은 과거 기록은 계속 보존한다.

아래 조회는 등록과 발급 재개 전 확인에 사용할 수 있다. 연결을 등록할 때는 운영자가 별도로 검증한 실제 `user_id`와 도메인 ID만 입력한다.

```sql
SELECT GREATEST(
    COALESCE((SELECT MAX(buyer_id) FROM orders), 0),
    COALESCE((SELECT MAX(buyer_id) FROM buyer_shipping_address_books), 0),
    COALESCE((SELECT MAX(buyer_id) FROM buyer_principal_identities), 0)
) AS max_existing_buyer_id;

SELECT last_value, is_called FROM buyer_domain_id_seq;
```

발급을 중지한 상태에서 다음 발급값이 `max_existing_buyer_id`와 이미 발급된 시퀀스 값보다 크지 않다면, 검증한 값으로 시퀀스를 재조정한다. `setval`은 트랜잭션 롤백으로 되돌아가지 않으므로 실행 직전에 값을 다시 읽고 결과를 확인한다.

```sql
SELECT setval(
    'buyer_domain_id_seq',
    GREATEST(
        (SELECT last_value FROM buyer_domain_id_seq) + 1,
        COALESCE((SELECT MAX(buyer_id) FROM orders), 0) + 1,
        COALESCE((SELECT MAX(buyer_id) FROM buyer_shipping_address_books), 0) + 1,
        COALESCE((SELECT MAX(buyer_id) FROM buyer_principal_identities), 0) + 1
    ),
    false
);
```
