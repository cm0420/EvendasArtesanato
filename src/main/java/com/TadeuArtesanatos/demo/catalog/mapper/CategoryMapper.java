package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.CategoryResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Category;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponseDto toResponseDto(Category category);
}
