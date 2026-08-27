CREATE TABLE orders (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id           UUID NOT NULL REFERENCES customers(id),
    status                VARCHAR(30) NOT NULL,
    total_amount          NUMERIC(10,2) NOT NULL DEFAULT 0,
    recipient_name        VARCHAR(100),
    recipient_phone       VARCHAR(20),
    shipping_zip_code     VARCHAR(8),
    shipping_street       VARCHAR(255),
    shipping_number       VARCHAR(20),
    shipping_complement   VARCHAR(255),
    shipping_neighborhood VARCHAR(100),
    shipping_city         VARCHAR(100),
    shipping_state        VARCHAR(2),
    tracking_code         VARCHAR(100),
    checkout_started_at   TIMESTAMP,
    refund_reason         VARCHAR(500),
    refund_requested_at   TIMESTAMP,
    refund_decided_at     TIMESTAMP,
    status_before_refund  VARCHAR(30),
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_status_checkout ON orders (status, checkout_started_at);
