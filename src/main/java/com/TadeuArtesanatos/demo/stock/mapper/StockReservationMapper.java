package com.TadeuArtesanatos.demo.stock.mapper;

import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface StockReservationMapper {

    @Mapping(target = "pieceId", source = "piece.id")
    StockReservationResponseDto toResponseDto(StockReservation reservation);
}
