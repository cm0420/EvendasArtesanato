package com.TadeuArtesanatos.demo.shipping.dto;

import java.math.BigDecimal;

public record ShippingQuoteResponseDto(
        String code,
        String name,
        BigDecimal price,
        Integer estimatedDeliveryDays
) {}
