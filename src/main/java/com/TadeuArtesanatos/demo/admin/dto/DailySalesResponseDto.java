package com.TadeuArtesanatos.demo.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailySalesResponseDto(
        LocalDate date,
        long totalOrders,
        BigDecimal totalRevenue
) {}
