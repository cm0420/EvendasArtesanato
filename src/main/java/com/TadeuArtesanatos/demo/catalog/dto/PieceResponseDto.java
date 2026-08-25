package com.TadeuArtesanatos.demo.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PieceResponseDto(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        boolean inStock,
        List<String> imageUrls,
        List<CategoryResponseDto> categories,
        LocalDateTime createdAt
) {
}
