package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPasswordUpdateDto(
        @NotBlank
        String currentPassword,
        @NotBlank
        @Size(min = 8, max = 64)
        String newPassword,
        @NotBlank
        String confirmPassword
) {
}
