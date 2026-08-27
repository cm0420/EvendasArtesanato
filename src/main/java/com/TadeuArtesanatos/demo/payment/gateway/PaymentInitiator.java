package com.TadeuArtesanatos.demo.payment.gateway;

import com.TadeuArtesanatos.demo.order.entity.Order;

public interface PaymentInitiator {

    /**
     * Inicia uma cobrança. Num gateway real, isso normalmente devolve uma
     * URL de checkout ou client secret — o resultado final chega depois,
     * via webhook. No fake, já devolve o resultado calculado.
     */
    PaymentChargeResult charge(Order order, String simulateOutcome);

    record PaymentChargeResult(String transactionId, String resultingStatus) {}
}
