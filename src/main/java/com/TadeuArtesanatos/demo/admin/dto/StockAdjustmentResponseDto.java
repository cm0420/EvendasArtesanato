package com.TadeuArtesanatos.demo.admin.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StockAdjustmentResponseDto(
        UUID id,
        UUID pieceId,
        Integer delta,
        Integer newStock,
        String reason,
        String adjustedByEmail,
        LocalDateTime createdAt
) {}
