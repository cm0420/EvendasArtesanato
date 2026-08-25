package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.PieceSetResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = PieceMapper.class)
public interface PieceSetMapper {

    @Mapping(target = "availableStock", expression = "java(pieceSet.calculateAvailableStock())")
    PieceSetResponseDto toResponseDto(PieceSet pieceSet);
}
