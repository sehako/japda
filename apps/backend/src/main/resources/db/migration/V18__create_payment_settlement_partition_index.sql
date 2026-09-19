CREATE INDEX payments_settlement_partition_idx
    ON payments (status, id)
    INCLUDE (approved_at);
