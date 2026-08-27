package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderItemRequestDto(

        @NotNull
        @Min(1)
        Integer quantity
) {}
