package com.TadeuArtesanatos.demo.customer.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.dto.AddressRequestDto;
import com.TadeuArtesanatos.demo.customer.dto.AddressResponseDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerRequestDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerResponseDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerUpdateRequestDto;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.mapper.CustomerMapper;
import com.TadeuArtesanatos.demo.customer.repository.AddressRepository;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final CustomerMapper customerMapper;

    @Transactional
    public CustomerResponseDto createCustomer(CustomerRequestDto dto, User authenticatedUser) {
        String cpfNormalizado = dto.cpf().replaceAll("\\D", "");

        if (customerRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }
        if (customerRepository.existsByCpf(cpfNormalizado)) {
            throw new IllegalArgumentException("CPF já cadastrado");
        }

        Customer customer = Customer.builder()
                .user(authenticatedUser)
                .firstName(dto.firstName())
                .lastName(dto.lastName())
                .email(dto.email())
                .phone(dto.phone())
                .cpf(cpfNormalizado)
                .build();

        Customer saved = customerRepository.save(customer);
        return customerMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponseDto findOwnProfile(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return customerMapper.toResponseDto(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponseDto findById(UUID id) {
        Customer customer = getActiveOrThrow(id);
        return customerMapper.toResponseDto(customer);
    }

    @Transactional
    public CustomerResponseDto updateOwnProfile(User authenticatedUser, CustomerUpdateRequestDto dto) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return applyUpdate(customer, dto);
    }

    @Transactional
    public void softDeleteOwnProfile(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        customer.softDelete();
    }

    @Transactional
    public AddressResponseDto addOwnAddress(User authenticatedUser, AddressRequestDto dto) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return persistAddress(customer, dto);
    }

    @Transactional
    public List<AddressResponseDto> listOwnAddresses(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return addressRepository.findAllByCustomerId(customer.getId())
                .stream()
                .map(customerMapper::toResponseDto)
                .toList();
    }

    @Transactional
    public void removeOwnAddress(User authenticatedUser, UUID addressId) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());

        Address address = addressRepository.findById(addressId)
                .filter(a -> a.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Endereço não encontrado"));

        addressRepository.delete(address);
    }

    // ---- helpers internos ----

    private CustomerResponseDto applyUpdate(Customer customer, CustomerUpdateRequestDto dto) {
        boolean emailPertenceAOutroCliente = customerRepository.findByEmail(dto.email())
                .map(existing -> !existing.getId().equals(customer.getId()))
                .orElse(false);

        if (emailPertenceAOutroCliente) {
            throw new IllegalArgumentException("Email já está em uso por outro cliente");
        }

        customer.setFirstName(dto.firstName());
        customer.setLastName(dto.lastName());
        customer.setEmail(dto.email());
        customer.setPhone(dto.phone());

        return customerMapper.toResponseDto(customer);
    }

    private AddressResponseDto persistAddress(Customer customer, AddressRequestDto dto) {
        if (Boolean.TRUE.equals(dto.isDefault())) {
            addressRepository.findAllByCustomerId(customer.getId())
                    .forEach(existing -> existing.setIsDefault(false));
        }

        Address address = Address.builder()
                .label(dto.label())
                .recipientName(dto.recipientName())
                .recipientPhone(dto.recipientPhone())
                .zipCode(dto.zipCode())
                .street(dto.street())
                .number(dto.number())
                .complement(dto.complement())
                .neighborhood(dto.neighborhood())
                .city(dto.city())
                .state(dto.state())
                .isDefault(Boolean.TRUE.equals(dto.isDefault()))
                .build();

        customer.addAddress(address);
        Address saved = addressRepository.save(address);
        return customerMapper.toResponseDto(saved);
    }

    private Customer getActiveOrThrow(UUID id) {
        return customerRepository.findById(id)
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente não encontrado"));
    }

    private Customer getActiveByUserOrThrow(UUID userId) {
        return customerRepository.findByUserId(userId)
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));
    }
}
