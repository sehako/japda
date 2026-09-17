CREATE INDEX orders_buyer_created_id_idx
    ON orders (buyer_id, created_at DESC, id DESC);
