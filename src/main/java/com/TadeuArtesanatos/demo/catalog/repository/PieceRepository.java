package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    Page<Piece> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Piece> findAllByCategories_IdAndDeletedAtIsNull(UUID categoryId, Pageable pageable);

    /** Trava a linha (SELECT ... FOR UPDATE) sem carregar a entidade; vazio se não existir. */
    @Query(value = "SELECT id FROM pieces WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockById(@Param("id") UUID id);
}
