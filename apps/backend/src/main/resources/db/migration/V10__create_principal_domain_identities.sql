CREATE TABLE buyer_principal_identities
(
    user_id  BIGINT NOT NULL PRIMARY KEY,
    buyer_id BIGINT NOT NULL UNIQUE,
    CONSTRAINT buyer_principal_identities_user_fk FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT buyer_principal_identities_buyer_id_positive CHECK (buyer_id > 0)
);

CREATE TABLE seller_principal_identities
(
    user_id   BIGINT NOT NULL PRIMARY KEY,
    seller_id BIGINT NOT NULL UNIQUE,
    CONSTRAINT seller_principal_identities_user_fk FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT seller_principal_identities_seller_id_positive CHECK (seller_id > 0)
);

CREATE SEQUENCE buyer_domain_id_seq AS BIGINT;

SELECT setval(
    'buyer_domain_id_seq',
    GREATEST(
        COALESCE((SELECT MAX(buyer_id) FROM orders), 0),
        COALESCE((SELECT MAX(buyer_id) FROM buyer_shipping_address_books), 0),
        COALESCE((SELECT MAX(buyer_id) FROM buyer_principal_identities), 0)
    ) + 1,
    false
);
