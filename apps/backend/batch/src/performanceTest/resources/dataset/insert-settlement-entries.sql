INSERT INTO settlement_entries (
    payment_id, order_id, sale_id, seller_id, recipient_user_id,
    quantity, unit_price, gross_amount, payment_approved_at, settlement_date, created_at
)
SELECT p.id,
       o.id,
       s.id,
       s.seller_id,
       spi.user_id,
       o.quantity,
       o.unit_price,
       o.total_price,
       p.approved_at,
       (p.approved_at AT TIME ZONE 'Asia/Seoul')::DATE,
       p.approved_at
FROM payments p
JOIN orders o ON o.id = p.order_id
JOIN sales s ON s.id = o.sale_id
JOIN seller_principal_identities spi ON spi.seller_id = s.seller_id
WHERE p.id BETWEEN CAST(:startId AS BIGINT) AND CAST(:endId AS BIGINT)
