INSERT INTO sale_days (sale_date, capacity, registered_count)
SELECT :saleDate, :sellerCount, :sellerCount
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT))
