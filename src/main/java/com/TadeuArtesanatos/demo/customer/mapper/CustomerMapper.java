package com.TadeuArtesanatos.demo.customer.mapper;

import com.TadeuArtesanatos.demo.customer.dto.AddressResponseDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerResponseDto;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerResponseDto toResponseDto(Customer customer);

    AddressResponseDto toResponseDto(Address address);
}
