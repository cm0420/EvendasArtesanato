package com.TadeuArtesanatos.demo.order.controller;

import com.TadeuArtesanatos.demo.order.dto.*;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // ---- Rotas /me — o próprio cliente autenticado ----

    @PostMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> getOrCreateCart(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(orderService.getOrCreateCart(user));
    }

    @PostMapping(value = "/me/items", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> addItem(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddOrderItemRequestDto dto
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.addItem(user, dto));
    }

    @PutMapping(value = "/me/items/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> updateItem(
            @AuthenticationPrincipal User user,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateOrderItemRequestDto dto
    ) {
        return ResponseEntity.ok(orderService.updateItemQuantity(user, itemId, dto));
    }

    @DeleteMapping(value = "/me/items/{itemId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> removeItem(
            @AuthenticationPrincipal User user,
            @PathVariable UUID itemId
    ) {
        return ResponseEntity.ok(orderService.removeItem(user, itemId));
    }

    @PostMapping(value = "/me/checkout", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> checkout(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CheckoutRequestDto dto
    ) {
        return ResponseEntity.ok(orderService.checkout(user, dto));
    }

    @PostMapping(value = "/me/{orderId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cancelOwnOrder(@AuthenticationPrincipal User user, @PathVariable UUID orderId) {
        orderService.cancelOwnOrder(user, orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/me/{orderId}/refund-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> requestRefund(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @Valid @RequestBody RequestRefundDto dto
    ) {
        orderService.requestRefund(user, orderId, dto);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> listOwnOrders(@AuthenticationPrincipal User user, Pageable pageable) {
        return ResponseEntity.ok(orderService.listOwnOrders(user, pageable));
    }

    @GetMapping(value = "/me/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> findOwnOrder(@AuthenticationPrincipal User user, @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.findOwnOrder(user, orderId));
    }

    // ---- Rotas admin — qualquer pedido, regra fixa, vai no SecurityConfig ----

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> listAll(
            Pageable pageable,
            @RequestParam(required = false) OrderStatus status
    ) {
        return ResponseEntity.ok(orderService.listAll(pageable, status));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> findByIdAsAdmin(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.findByIdAsAdmin(id));
    }

    @PostMapping(value = "/{id}/mark-paid", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> markAsPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.markAsPaid(id));
    }

    @PostMapping(value = "/{id}/ship", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> ship(@PathVariable UUID id, @Valid @RequestBody ShipOrderRequestDto dto) {
        return ResponseEntity.ok(orderService.ship(id, dto));
    }

    @PostMapping(value = "/{id}/deliver", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> deliver(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.deliver(id));
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cancelAsAdmin(@PathVariable UUID id) {
        orderService.cancelAsAdmin(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/refund-approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> approveRefund(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.approveRefund(id));
    }

    @PostMapping(value = "/{id}/refund-deny", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> denyRefund(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.denyRefund(id));
    }
}
