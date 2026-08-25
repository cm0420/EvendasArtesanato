package com.TadeuArtesanatos.demo.catalog.dto;

import java.util.UUID;

public record CategoryResponseDto(
        UUID id,
        String name,
        String description
) {
}
