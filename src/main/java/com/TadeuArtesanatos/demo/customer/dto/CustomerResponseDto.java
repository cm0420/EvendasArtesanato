package com.TadeuArtesanatos.demo.customer.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CustomerResponseDto(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String cpf,
        List<AddressResponseDto> addresses,
        LocalDateTime createdAt
) {
}
