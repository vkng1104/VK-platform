CREATE SCHEMA shop_ordering;

CREATE TABLE shop_ordering.orders (
    id UUID PRIMARY KEY,
    status TEXT NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT orders_status_check CHECK (status IN ('pending', 'paid'))
);

CREATE UNIQUE INDEX orders_pending_placed_idx
    ON shop_ordering.orders (placed_at)
    WHERE status = 'pending';

CREATE TABLE shop_ordering.order_lines (
    order_id UUID NOT NULL REFERENCES shop_ordering.orders (id) ON DELETE CASCADE,
    line_number SMALLINT NOT NULL,
    product_id UUID NOT NULL REFERENCES shop_catalog.products (id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (order_id, line_number)
);

CREATE INDEX order_lines_product_idx
    ON shop_ordering.order_lines (product_id, order_id DESC);

CREATE TABLE shop_ordering.order_receipts (
    order_id UUID PRIMARY KEY REFERENCES shop_ordering.orders (id),
    issued_at TIMESTAMPTZ NOT NULL
);
