package com.TadeuArtesanatos.demo.admin.dto;

import java.util.UUID;

public record BestSellingPieceResponseDto(
        UUID pieceId,
        String pieceName,
        long totalQuantitySold
) {}
