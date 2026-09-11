ALTER TABLE orders
    ADD COLUMN payment_order_id VARCHAR(64);

UPDATE orders
SET payment_order_id = 'legacy_' || id;

ALTER TABLE orders
    ALTER COLUMN payment_order_id SET NOT NULL,
    ADD CONSTRAINT orders_payment_order_id_format
        CHECK (
            char_length(payment_order_id) BETWEEN 6 AND 64
                AND payment_order_id ~ '^[A-Za-z0-9_=-]+$'
        ),
    ADD CONSTRAINT orders_payment_order_id_unique UNIQUE (payment_order_id);
