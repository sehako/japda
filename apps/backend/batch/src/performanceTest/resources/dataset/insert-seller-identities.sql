INSERT INTO seller_principal_identities (user_id, seller_id)
SELECT id, id
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
