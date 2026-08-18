package com.TadeuArtesanatos.demo.user.mapper;

import com.TadeuArtesanatos.demo.user.dto.UserResponseDto;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponseDto toResponseDto(User user);
}
