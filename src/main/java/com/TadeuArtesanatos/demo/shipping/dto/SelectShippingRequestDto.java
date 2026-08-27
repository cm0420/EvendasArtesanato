package com.TadeuArtesanatos.demo.shipping.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectShippingRequestDto(

        @NotBlank
        String code
) {}
