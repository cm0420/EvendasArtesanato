package com.TadeuArtesanatos.demo.payment.gateway.fake;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentInitiator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "fake")
public class FakePaymentGateway implements PaymentInitiator {

    private static final java.util.Set<String> VALID_OUTCOMES =
            java.util.Set.of("APPROVED", "DECLINED", "PENDING");

    @Override
    public PaymentChargeResult charge(Order order, String simulateOutcome) {
        String outcome = (simulateOutcome != null) ? simulateOutcome.toUpperCase() : "APPROVED";

        if (!VALID_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("simulateOutcome inválido — use APPROVED, DECLINED ou PENDING");
        }

        String transactionId = "fake_txn_" + UUID.randomUUID();
        return new PaymentChargeResult(transactionId, outcome);
    }
}
