package com.TadeuArtesanatos.demo.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponseDto(
        UUID id,
        String status,
        List<OrderItemResponseDto> items,
        BigDecimal totalAmount,
        String recipientName,
        String shippingCity,
        String shippingState,
        String trackingCode,
        LocalDateTime checkoutStartedAt,
        String refundReason,
        LocalDateTime refundRequestedAt,
        LocalDateTime refundDecidedAt,
        LocalDateTime createdAt
) {}
