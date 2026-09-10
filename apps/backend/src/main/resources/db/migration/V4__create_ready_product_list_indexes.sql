CREATE INDEX products_seller_ready_id_idx
    ON products (seller_id, status, id)
    INCLUDE (name);

CREATE INDEX products_seller_ready_name_id_idx
    ON products (seller_id, status, name COLLATE "C", id);
