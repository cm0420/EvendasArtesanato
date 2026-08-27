package com.TadeuArtesanatos.demo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockAdjustmentRequestDto(

        @NotNull
        Integer delta,

        @NotBlank
        String reason
) {}
