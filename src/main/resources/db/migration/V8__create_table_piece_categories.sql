CREATE TABLE piece_categories (
    piece_id      UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    category_id   UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (piece_id, category_id)
);
