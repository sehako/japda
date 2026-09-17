CREATE INDEX payments_status_approved_at_id_idx
    ON payments (status, approved_at, id);
