package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record PieceSetRequestDto(

        @NotBlank
        @Size(max = 150)
        String name,

        String description,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal price,

        @NotEmpty(message = "Um conjunto precisa de pelo menos uma peça")
        Set<UUID> pieceIds
) {
}
