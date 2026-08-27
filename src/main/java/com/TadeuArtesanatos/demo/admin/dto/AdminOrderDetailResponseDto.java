package com.TadeuArtesanatos.demo.admin.dto;

import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;

import java.util.List;

public record AdminOrderDetailResponseDto(
        OrderResponseDto order,
        String customerName,
        String customerEmail,
        String customerPhone,
        List<PaymentResponseDto> payments
) {}
