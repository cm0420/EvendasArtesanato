package com.TadeuArtesanatos.demo.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveStockRequestDto(

        @NotNull
        UUID pieceId,

        @NotNull
        @Min(1)
        Integer quantity,

        @NotNull
        UUID orderId
) {}
