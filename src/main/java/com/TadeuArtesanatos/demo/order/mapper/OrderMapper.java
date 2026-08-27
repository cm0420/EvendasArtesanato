package com.TadeuArtesanatos.demo.order.mapper;

import com.TadeuArtesanatos.demo.order.dto.OrderItemResponseDto;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderResponseDto toResponseDto(Order order);

    @Mapping(target = "pieceId", expression = "java(item.isPieceItem() ? item.getPiece().getId() : null)")
    @Mapping(target = "pieceSetId", expression = "java(item.isSetItem() ? item.getPieceSet().getId() : null)")
    OrderItemResponseDto toResponseDto(OrderItem item);
}
