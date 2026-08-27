package com.TadeuArtesanatos.demo.order.job;

import com.TadeuArtesanatos.demo.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderExpirationJob {

    private final OrderService orderService;

    @Value("${app.order.checkout-expiration-minutes:20}")
    private long checkoutExpirationMinutes;

    /**
     * Roda a cada 2 minutos — encontra pedidos AGUARDANDO_PAGAMENTO cujo
     * checkout começou há mais tempo que o permitido, libera as reservas
     * de estoque ativas e marca o pedido como EXPIRADO.
     */
    @Scheduled(fixedRate = 120_000)
    public void expireStaleCheckouts() {
        orderService.expireStaleCheckouts(checkoutExpirationMinutes);
    }
}
