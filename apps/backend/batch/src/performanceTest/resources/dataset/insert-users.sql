INSERT INTO users (id, provider, provider_subject, email, created_at)
SELECT id,
       'GOOGLE',
       'performance-' || :randomSeed || '-' || id,
       'performance-' || :randomSeed || '-' || id || '@example.invalid',
       :entityCreatedAt
FROM generate_series(CAST(:startId AS BIGINT), CAST(:endId AS BIGINT)) AS generated(id)
