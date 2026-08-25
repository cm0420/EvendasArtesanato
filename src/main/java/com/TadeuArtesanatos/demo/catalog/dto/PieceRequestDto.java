package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PieceRequestDto(

        @NotBlank
        @Size(max = 150)
        String name,

        String description,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal price,

        @NotNull
        @Min(0)
        Integer stock,

        List<String> imageUrls,

        Set<UUID> categoryIds
) {
}
