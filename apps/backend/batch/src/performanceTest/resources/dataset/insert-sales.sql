INSERT INTO sales (id, product_id, seller_id, sale_date, price, quantity, created_at)
SELECT id,
       id,
       id,
       :saleDate,
       :grossAmount,
       CAST(
           :orderCount / :sellerCount +
           CASE WHEN id <= MOD(:orderCount, :sellerCount) THEN 1 ELSE 0 END
           AS INTEGER
       ),
       :entityCreatedAt
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
