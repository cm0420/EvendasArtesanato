package com.TadeuArtesanatos.demo.shipping.service;

import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.shipping.dto.ShippingQuoteResponseDto;
import com.TadeuArtesanatos.demo.shipping.gateway.ShippingProvider;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShippingService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ShippingProvider shippingProvider;
    private final OrderMapper orderMapper;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public List<ShippingQuoteResponseDto> getQuotes(User authenticatedUser, UUID orderId) {
        Order order = getOwnOrderAwaitingPaymentOrThrow(authenticatedUser, orderId);

        return shippingProvider.quote(order).stream()
                .map(q -> new ShippingQuoteResponseDto(q.code(), q.name(), q.price(), q.estimatedDeliveryDays()))
                .toList();
    }

    @Transactional
    public OrderResponseDto selectShipping(User authenticatedUser, UUID orderId, String code) {
        Order order = getOwnOrderAwaitingPaymentOrThrow(authenticatedUser, orderId);

        // O valor do pagamento é gravado ao iniciar; mudar o frete depois faria pagar o total antigo.
        if (paymentRepository.existsByOrderIdAndStatusIn(order.getId(), PaymentStatus.LIVE)) {
            throw new InvalidStateException("O frete não pode ser alterado depois de iniciar o pagamento");
        }

        ShippingProvider.ShippingQuote selected = shippingProvider.quote(order).stream()
                .filter(q -> q.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Opção de frete inválida: " + code));

        // Recalcula o preço de novo (não confia no que o cliente mandou) —
        // a cotação é sempre recomputada aqui, o `code` só seleciona qual das opções.
        order.applyShipping(selected.code(), selected.name(), selected.price(), selected.estimatedDeliveryDays());

        return orderMapper.toResponseDto(order);
    }

    private Order getOwnOrderAwaitingPaymentOrThrow(User authenticatedUser, UUID orderId) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Frete só pode ser consultado/alterado enquanto o pedido aguarda pagamento (status atual: " + order.getStatus() + ")");
        }

        return order;
    }
}
