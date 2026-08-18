package com.TadeuArtesanatos.demo.customer.dto;

import java.util.UUID;

public record AddressResponseDto(
        UUID id,
        String label,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String street,
        String number,
        String complement,
        String neighborhood,
        String city,
        String state,
        String country,
        Boolean isDefault
) {
}
