package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    Page<Piece> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Piece> findAllByCategories_IdAndDeletedAtIsNull(UUID categoryId, Pageable pageable);

    /**
     * Lock pessimista (SELECT ... FOR UPDATE) — usado pelo módulo `stock` durante
     * a reserva/liberação de estoque. A transação que chamar isso bloqueia a linha
     * até fazer commit; um segundo request concorrente espera até a primeira terminar.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Piece p WHERE p.id = :id")
    Optional<Piece> findByIdForUpdate(@Param("id") UUID id);
}
