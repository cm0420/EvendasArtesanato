package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PieceSetRepository extends JpaRepository<PieceSet, UUID> {

    Page<PieceSet> findAllByDeletedAtIsNull(Pageable pageable);
}
