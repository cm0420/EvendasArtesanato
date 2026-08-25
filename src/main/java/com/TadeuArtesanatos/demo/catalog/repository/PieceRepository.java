package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    Page<Piece> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Piece> findAllByCategories_IdAndDeletedAtIsNull(UUID categoryId, Pageable pageable);
}
