package com.TadeuArtesanatos.demo.payment.service;

import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.entity.Payment;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentInitiator;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookHandler;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookPayload;
import com.TadeuArtesanatos.demo.payment.mapper.PaymentMapper;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final PaymentInitiator paymentInitiator;
    private final PaymentWebhookHandler paymentWebhookHandler;
    private final PaymentMapper paymentMapper;

    @Transactional
    public PaymentResponseDto initiatePayment(User authenticatedUser, UUID orderId, String simulateOutcome) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        if (!order.hasShippingSelected()) {
            throw new InvalidStateException("Selecione o frete antes de pagar");
        }

        // Dois pagamentos aprovados para o mesmo pedido = cobrança dupla (o segundo seria ignorado).
        if (paymentRepository.existsByOrderIdAndStatusIn(order.getId(), PaymentStatus.LIVE)) {
            throw new InvalidStateException("Já existe um pagamento em andamento para este pedido");
        }

        PaymentInitiator.PaymentChargeResult chargeResult = paymentInitiator.charge(order, simulateOutcome);

        Payment payment = Payment.builder()
                .order(order)
                .transactionId(chargeResult.transactionId())
                .status(PaymentStatus.PENDING)
                .amount(order.getTotalAmount())
                .provider("fake")
                .build();
        paymentRepository.save(payment);

        // Simula o "round-trip" assíncrono do gateway real — aqui, síncrono mesmo,
        // porque não existe rede de verdade envolvida. Um gateway real chamaria
        // o endpoint público /api/payments/webhook/... em vez disso, mas o código
        // que processa o resultado (PaymentWebhookHandlerImpl) é o mesmo.
        paymentWebhookHandler.handle(new PaymentWebhookPayload(chargeResult.transactionId(), chargeResult.resultingStatus()));

        Payment atualizado = paymentRepository.findByTransactionId(chargeResult.transactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Pagamento não encontrado após processamento"));

        return paymentMapper.toResponseDto(atualizado);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponseDto> listOwnOrderPayments(User authenticatedUser, UUID orderId) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return paymentRepository.findAllByOrderId(order.getId()).stream()
                .map(paymentMapper::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponseDto findByIdAsAdmin(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Pagamento não encontrado"));
        return paymentMapper.toResponseDto(payment);
    }

    /** Chamado pelo endpoint público de webhook — usado por gateways reais no futuro. */
    @Transactional
    public void processWebhook(String transactionId, String status) {
        paymentWebhookHandler.handle(new PaymentWebhookPayload(transactionId, status));
    }
}
