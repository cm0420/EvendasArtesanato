CREATE TABLE pieces (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(150)   NOT NULL,
    description   TEXT,
    price         NUMERIC(10,2)  NOT NULL,
    stock         INTEGER        NOT NULL DEFAULT 0,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP
);

CREATE INDEX idx_pieces_deleted_at ON pieces (deleted_at);
