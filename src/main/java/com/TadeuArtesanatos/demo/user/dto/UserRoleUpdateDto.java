package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.NotNull;

public record UserRoleUpdateDto(
        @NotNull
        String role
) {
}
