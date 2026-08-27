package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotBlank;

public record RequestRefundDto(

        @NotBlank
        String reason
) {}
