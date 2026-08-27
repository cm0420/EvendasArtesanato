CREATE TABLE order_items (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id      UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    piece_id      UUID REFERENCES pieces(id),
    piece_set_id  UUID REFERENCES piece_sets(id),
    item_name     VARCHAR(150) NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    quantity      INTEGER NOT NULL,
    subtotal      NUMERIC(10,2) NOT NULL,
    CONSTRAINT chk_order_items_exactly_one_reference CHECK (
        (piece_id IS NOT NULL AND piece_set_id IS NULL) OR
        (piece_id IS NULL AND piece_set_id IS NOT NULL)
    )
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
