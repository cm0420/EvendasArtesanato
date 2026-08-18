CREATE TABLE customers (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID NOT NULL UNIQUE REFERENCES users(id),
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    phone         VARCHAR(20)  NOT NULL,
    cpf           VARCHAR(11)  NOT NULL UNIQUE,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP
);

CREATE INDEX idx_customers_email ON customers (email);
CREATE INDEX idx_customers_cpf   ON customers (cpf);
