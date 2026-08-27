package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Exatamente um dos dois (pieceId OU pieceSetId) deve vir preenchido —
 * validado no OrderService, não é possível expressar "XOR" só com Bean Validation.
 */
public record AddOrderItemRequestDto(

        UUID pieceId,

        UUID pieceSetId,

        @NotNull
        @Min(1)
        Integer quantity
) {}
