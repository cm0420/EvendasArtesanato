package com.TadeuArtesanatos.demo.shipping.gateway.fake;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.shipping.gateway.ShippingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.shipping.provider", havingValue = "fake")
public class FakeShippingProvider implements ShippingProvider {

    // Fórmula deliberadamente arbitrária — não simula Correios de verdade.
    // Não existe campo de peso/dimensão na Piece ainda; base pela quantidade de itens.
    private static final BigDecimal BASE_FEE = BigDecimal.valueOf(15.00);
    private static final BigDecimal PER_ITEM_FEE = BigDecimal.valueOf(2.50);
    private static final BigDecimal EXPRESS_MULTIPLIER = BigDecimal.valueOf(1.8);

    @Override
    public List<ShippingQuote> quote(Order order) {
        int totalItems = order.getItems().stream().mapToInt(item -> item.getQuantity()).sum();

        BigDecimal baseCost = BASE_FEE.add(PER_ITEM_FEE.multiply(BigDecimal.valueOf(totalItems)))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal expressCost = baseCost.multiply(EXPRESS_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

        return List.of(
                new ShippingQuote("STANDARD", "Entrega padrão", baseCost, 7),
                new ShippingQuote("EXPRESS", "Entrega expressa", expressCost, 3)
        );
    }
}
