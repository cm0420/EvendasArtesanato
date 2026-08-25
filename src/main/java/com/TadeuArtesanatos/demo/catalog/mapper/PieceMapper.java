package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.PieceResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = CategoryMapper.class)
public interface PieceMapper {

    @Mapping(target = "inStock", expression = "java(piece.isInStock())")
    PieceResponseDto toResponseDto(Piece piece);
}
