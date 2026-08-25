package com.TadeuArtesanatos.demo.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PieceSetResponseDto(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        int availableStock,
        List<PieceResponseDto> pieces,
        LocalDateTime createdAt
) {
}
