package com.TadeuArtesanatos.demo.stock.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StockReservationResponseDto(
        UUID id,
        UUID pieceId,
        Integer quantity,
        UUID orderId,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {}
