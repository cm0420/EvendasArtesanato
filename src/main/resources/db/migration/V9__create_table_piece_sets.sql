CREATE TABLE piece_sets (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(150)   NOT NULL,
    description   TEXT,
    price         NUMERIC(10,2)  NOT NULL,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP
);
