package com.TadeuArtesanatos.demo.stock.repository;

import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findAllByOrderId(UUID orderId);
}
