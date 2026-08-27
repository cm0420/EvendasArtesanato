package com.TadeuArtesanatos.demo.payment.gateway;

public record PaymentWebhookPayload(String transactionId, String status) {}
