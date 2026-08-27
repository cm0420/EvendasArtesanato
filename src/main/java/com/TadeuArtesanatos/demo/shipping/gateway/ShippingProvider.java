package com.TadeuArtesanatos.demo.shipping.gateway;

import com.TadeuArtesanatos.demo.order.entity.Order;

import java.util.List;

public interface ShippingProvider {

    List<ShippingQuote> quote(Order order);

    record ShippingQuote(String code, String name, java.math.BigDecimal price, Integer estimatedDeliveryDays) {}
}
