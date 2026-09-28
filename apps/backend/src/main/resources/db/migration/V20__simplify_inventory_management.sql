ALTER TABLE sales
    ADD COLUMN committed_quantity INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT sales_committed_quantity_range CHECK (
        committed_quantity >= 0 AND committed_quantity <= quantity
    );

ALTER TABLE orders
    DROP CONSTRAINT orders_status_valid,
    ADD CONSTRAINT orders_status_valid CHECK (
        status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'PAYMENT_FAILED')
    );

DELETE FROM settlement_details;
DELETE FROM settlement_entries;
DELETE FROM inventory_reservations;
DELETE FROM payments;
DELETE FROM orders;

DROP TABLE inventory_reservations;
DROP TABLE sale_inventory_counters;
