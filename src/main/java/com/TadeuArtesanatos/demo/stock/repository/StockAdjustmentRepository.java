package com.TadeuArtesanatos.demo.stock.repository;

import com.TadeuArtesanatos.demo.stock.entity.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {
}
