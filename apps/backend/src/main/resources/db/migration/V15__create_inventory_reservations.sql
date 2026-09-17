CREATE TABLE sale_inventory_counters
(
    sale_id            BIGINT                   PRIMARY KEY,
    committed_quantity INTEGER                  NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT sale_inventory_counters_sale_fk FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT sale_inventory_counters_committed_quantity_non_negative CHECK (committed_quantity >= 0)
);

CREATE TABLE inventory_reservations
(
    id         UUID                     PRIMARY KEY,
    sale_id    BIGINT                   NOT NULL,
    order_id   BIGINT                   NOT NULL,
    quantity   INTEGER                  NOT NULL,
    status     VARCHAR(30)              NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT inventory_reservations_sale_fk FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT inventory_reservations_order_fk FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT inventory_reservations_order_unique UNIQUE (order_id),
    CONSTRAINT inventory_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT inventory_reservations_status_valid CHECK (
        status IN ('RESERVED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED')
    )
);

CREATE INDEX inventory_reservations_sale_status_expires_idx
    ON inventory_reservations (sale_id, status, expires_at);

INSERT INTO inventory_reservations (
    id,
    sale_id,
    order_id,
    quantity,
    status,
    expires_at,
    created_at,
    updated_at
)
SELECT gen_random_uuid(),
       orders.sale_id,
       orders.id,
       orders.quantity,
       CASE
           WHEN orders.status = 'PAID' OR payments.status = 'APPROVED' THEN 'CONFIRMED'
           WHEN payments.status IN ('CONFIRMING', 'REVIEW_REQUIRED') THEN 'PAYMENT_PENDING'
           WHEN payments.status = 'FAILED' THEN 'RELEASED'
           WHEN payments.id IS NULL
               AND orders.status = 'PENDING_PAYMENT'
               AND orders.expires_at > CURRENT_TIMESTAMP THEN 'RESERVED'
           ELSE 'RELEASED'
       END,
       orders.expires_at,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM orders
LEFT JOIN payments ON payments.order_id = orders.id;

INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at)
SELECT sales.id,
       COALESCE(SUM(inventory_reservations.quantity)
           FILTER (WHERE inventory_reservations.status <> 'RELEASED'), 0)::INTEGER,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM sales
LEFT JOIN inventory_reservations ON inventory_reservations.sale_id = sales.id
GROUP BY sales.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sale_inventory_counters inventory
        JOIN sales sale ON sale.id = inventory.sale_id
        WHERE inventory.committed_quantity < 0
           OR inventory.committed_quantity > sale.quantity
    ) THEN
        RAISE EXCEPTION '기존 재고 점유 수량이 최초 판매 수량 범위를 벗어났습니다.';
    END IF;
END
$$;
