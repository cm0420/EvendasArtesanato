CREATE TABLE payments (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id         UUID NOT NULL REFERENCES orders(id),
    transaction_id   VARCHAR(255) NOT NULL UNIQUE,
    status           VARCHAR(20) NOT NULL,
    amount           NUMERIC(10,2) NOT NULL,
    provider         VARCHAR(50) NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
