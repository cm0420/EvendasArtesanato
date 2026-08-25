CREATE TABLE set_pieces (
    set_id        UUID NOT NULL REFERENCES piece_sets(id) ON DELETE CASCADE,
    piece_id      UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    PRIMARY KEY (set_id, piece_id)
);
