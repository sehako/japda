INSERT INTO products (id, seller_id, name, status, created_at)
SELECT id,
       id,
       '성능 테스트 상품-' || id,
       'READY',
       :entityCreatedAt
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
