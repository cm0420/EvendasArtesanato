package com.TadeuArtesanatos.demo.payment.controller;

import com.TadeuArtesanatos.demo.payment.dto.FakeWebhookRequestDto;
import com.TadeuArtesanatos.demo.payment.dto.InitiatePaymentRequestDto;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.service.PaymentService;
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
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // ---- Cliente autenticado (pagamento do próprio pedido) ----

    @PostMapping(value = "/api/orders/me/{orderId}/payments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponseDto> initiatePayment(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @RequestBody(required = false) InitiatePaymentRequestDto dto
    ) {
        String simulateOutcome = (dto != null) ? dto.simulateOutcome() : null;
        PaymentResponseDto result = paymentService.initiatePayment(user, orderId, simulateOutcome);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping(value = "/api/orders/me/{orderId}/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PaymentResponseDto>> listOwnOrderPayments(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(paymentService.listOwnOrderPayments(user, orderId));
    }

    // ---- Admin ----

    @GetMapping(value = "/api/payments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponseDto> findByIdAsAdmin(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.findByIdAsAdmin(id));
    }

    // ---- Webhook público — sem autenticação (gateway real não tem nosso JWT) ----

    @PostMapping(value = "/api/payments/webhook/fake", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> fakeWebhook(@Valid @RequestBody FakeWebhookRequestDto dto) {
        paymentService.processWebhook(dto.transactionId(), dto.status());
        return ResponseEntity.noContent().build();
    }
}
