package com.TadeuArtesanatos.demo.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentResponseDto(
        UUID id,
        UUID orderId,
        String transactionId,
        String status,
        BigDecimal amount,
        String provider,
        LocalDateTime createdAt
) {}
