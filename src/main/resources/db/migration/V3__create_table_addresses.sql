CREATE TABLE addresses (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id      UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    label            VARCHAR(50)  NOT NULL,
    recipient_name   VARCHAR(100) NOT NULL,
    recipient_phone  VARCHAR(20)  NOT NULL,
    zip_code         VARCHAR(8)   NOT NULL,
    street           VARCHAR(255) NOT NULL,
    number           VARCHAR(20)  NOT NULL,
    complement       VARCHAR(255),
    neighborhood     VARCHAR(100) NOT NULL,
    city             VARCHAR(100) NOT NULL,
    state            VARCHAR(2)      NOT NULL,
    country          VARCHAR(50)  NOT NULL DEFAULT 'BR',
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_addresses_customer_id ON addresses (customer_id);
