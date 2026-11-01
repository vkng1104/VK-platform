CREATE SCHEMA shop_catalog;

CREATE TABLE shop_catalog.products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku VARCHAR(64) NOT NULL UNIQUE,
    name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'draft',
    price_cents INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT products_price_check CHECK (price_cents >= 0)
);

COMMENT ON TABLE shop_catalog.products IS 'Items that can be ordered.';
COMMENT ON COLUMN shop_catalog.products.sku IS 'Stock keeping unit shown on invoices.';
