package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CheckoutRequestDto(

        @NotNull
        UUID addressId
) {}
