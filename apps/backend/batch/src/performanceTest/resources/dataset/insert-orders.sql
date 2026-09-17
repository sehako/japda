INSERT INTO orders (
    id, sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price,
    status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
)
SELECT id,
       MOD(id - 1, :sellerCount) + 1,
       id,
       CAST(md5(CAST(:randomSeed AS TEXT) || ':order:' || id) AS UUID),
       'performance_' || :randomSeed || '_' || id,
       1,
       '성능 테스트 상품',
       :grossAmount,
       :grossAmount,
       'PAID',
       '수령인',
       '010-0000-0000',
       '00000',
       '주소',
       '상세',
       :orderCreatedAt,
       :orderExpiresAt
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
