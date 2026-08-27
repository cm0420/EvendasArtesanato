package com.TadeuArtesanatos.demo.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponseDto(
        UUID id,
        UUID pieceId,
        UUID pieceSetId,
        String itemName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal
) {}
