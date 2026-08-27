package com.TadeuArtesanatos.demo.payment.gateway;

public interface PaymentWebhookHandler {

    /**
     * Processa o retorno do gateway (real: assíncrono, via HTTP; fake: chamado
     * direto pelo PaymentService logo após o charge()). Idempotente — chamar
     * duas vezes com o mesmo transactionId não duplica efeito nenhum.
     */
    void handle(PaymentWebhookPayload payload);
}
