package com.TadeuArtesanatos.demo.shipping.controller;

import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.shipping.dto.SelectShippingRequestDto;
import com.TadeuArtesanatos.demo.shipping.dto.ShippingQuoteResponseDto;
import com.TadeuArtesanatos.demo.shipping.service.ShippingService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders/me/{orderId}/shipping")
@RequiredArgsConstructor
public class ShippingController {

    private final ShippingService shippingService;

    @GetMapping(value = "/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ShippingQuoteResponseDto>> getQuotes(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(shippingService.getQuotes(user, orderId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> selectShipping(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @Valid @RequestBody SelectShippingRequestDto dto
    ) {
        return ResponseEntity.ok(shippingService.selectShipping(user, orderId, dto.code()));
    }
}
