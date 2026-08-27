package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipOrderRequestDto(

        @NotBlank
        String trackingCode
) {}
