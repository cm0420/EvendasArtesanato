CREATE TABLE stock_reservations (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    piece_id      UUID NOT NULL REFERENCES pieces(id),
    quantity      INTEGER NOT NULL,
    order_id      UUID NOT NULL,
    status        VARCHAR(20) NOT NULL,
    expires_at    TIMESTAMP NOT NULL,
    created_at    TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_reservations_piece_id ON stock_reservations (piece_id);
CREATE INDEX idx_stock_reservations_status_expires ON stock_reservations (status, expires_at);
