package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;

public record UserUpdateRequestDto (
    @NotBlank
    @Email
    String email
){
}
