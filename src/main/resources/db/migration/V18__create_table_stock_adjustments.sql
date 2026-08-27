CREATE TABLE stock_adjustments (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    piece_id           UUID NOT NULL REFERENCES pieces(id),
    delta              INTEGER NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    adjusted_by_email  VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_adjustments_piece_id ON stock_adjustments (piece_id);
