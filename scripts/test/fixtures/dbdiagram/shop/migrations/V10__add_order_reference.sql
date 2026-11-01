ALTER TABLE shop_ordering.orders
    ADD COLUMN reference VARCHAR(32),
    ADD CONSTRAINT orders_reference_unique UNIQUE (reference);
