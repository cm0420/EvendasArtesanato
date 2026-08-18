package com.TadeuArtesanatos.demo.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequestDto(

        @NotBlank
        @Size(max = 50)
        String label,

        @NotBlank
        @Size(max = 100)
        String recipientName,

        @NotBlank
        @Pattern(regexp = "^\\(?\\d{2}\\)?[\\s-]?9?\\d{4}-?\\d{4}$", message = "Telefone inválido")
        String recipientPhone,

        @NotBlank
        @Pattern(regexp = "^\\d{8}$", message = "CEP deve conter 8 dígitos")
        String zipCode,

        @NotBlank
        String street,

        @NotBlank
        String number,

        String complement,

        @NotBlank
        String neighborhood,

        @NotBlank
        String city,

        @NotBlank
        @Size(min = 2, max = 2)
        String state,

        Boolean isDefault
) {
}
