package com.TadeuArtesanatos.demo.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record FakeWebhookRequestDto(

        @NotBlank
        String transactionId,

        @NotBlank
        String status
) {}
