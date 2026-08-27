package com.TadeuArtesanatos.demo.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesReportResponseDto(
        LocalDate startDate,
        LocalDate endDate,
        long totalOrders,
        BigDecimal totalRevenue
) {}
