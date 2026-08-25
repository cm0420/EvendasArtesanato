package com.TadeuArtesanatos.demo.security.dto;

public record TokenResponseDto(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static TokenResponseDto of(String accessToken, long expiresIn) {
        return new TokenResponseDto(accessToken, "Bearer", expiresIn);
    }
}
