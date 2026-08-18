package com.TadeuArtesanatos.demo.customer.controller;

import com.TadeuArtesanatos.demo.customer.dto.AddressRequestDto;
import com.TadeuArtesanatos.demo.customer.dto.AddressResponseDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerRequestDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerResponseDto;
import com.TadeuArtesanatos.demo.customer.dto.CustomerUpdateRequestDto;
import com.TadeuArtesanatos.demo.customer.service.CustomerService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(
        value = "/api/customers",
        produces = MediaType.APPLICATION_JSON_VALUE,
        consumes = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    // ---- Rotas /me — o próprio cliente autenticado, sem comparação de id ----

    @PostMapping("/me")
    public ResponseEntity<CustomerResponseDto> createOwnProfile(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody CustomerRequestDto dto
    ) {
        CustomerResponseDto created = customerService.createCustomer(dto, authenticatedUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerResponseDto> findOwnProfile(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        return ResponseEntity.ok(customerService.findOwnProfile(authenticatedUser));
    }

    @PutMapping("/me")
    public ResponseEntity<CustomerResponseDto> updateOwnProfile(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody CustomerUpdateRequestDto dto
    ) {
        return ResponseEntity.ok(customerService.updateOwnProfile(authenticatedUser, dto));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> softDeleteOwnProfile(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        customerService.softDeleteOwnProfile(authenticatedUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/addresses")
    public ResponseEntity<AddressResponseDto> addOwnAddress(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody AddressRequestDto dto
    ) {
        AddressResponseDto created = customerService.addOwnAddress(authenticatedUser, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/me/addresses")
    public ResponseEntity<List<AddressResponseDto>> listOwnAddresses(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        return ResponseEntity.ok(customerService.listOwnAddresses(authenticatedUser));
    }

    @DeleteMapping("/me/addresses/{addressId}")
    public ResponseEntity<Void> removeOwnAddress(
            @AuthenticationPrincipal User authenticatedUser,
            @PathVariable UUID addressId
    ) {
        customerService.removeOwnAddress(authenticatedUser, addressId);
        return ResponseEntity.noContent().build();
    }

    // ---- Rota admin — qualquer cliente pelo id, regra fixa, vai no SecurityConfig ----

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(customerService.findById(id));
    }
}
