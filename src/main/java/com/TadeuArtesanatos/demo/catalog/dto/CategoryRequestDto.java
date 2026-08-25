package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryRequestDto(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 255)
        String description
) {
}
