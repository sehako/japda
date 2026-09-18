INSERT INTO payments (
    id, order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at, approved_at
)
SELECT id,
       id,
       'performance-payment-' || :randomSeed || '-' || id,
       CAST(CAST(md5(CAST(:randomSeed AS TEXT) || ':payment:' || id) AS UUID) AS TEXT),
       'APPROVED',
       :grossAmount,
       :orderCreatedAt,
       CASE :approvalTimeDistribution
           WHEN 'FIXED' THEN :approvedAt
           WHEN 'UNIFORM' THEN :approvalTimeStart +
               ((id - 1) * 86400000 / :orderCount) * INTERVAL '1 millisecond'
       END
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
