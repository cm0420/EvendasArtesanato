CREATE TABLE piece_images (
    piece_id        UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    url             VARCHAR(500) NOT NULL,
    display_order   INTEGER NOT NULL,
    PRIMARY KEY (piece_id, display_order)
);
