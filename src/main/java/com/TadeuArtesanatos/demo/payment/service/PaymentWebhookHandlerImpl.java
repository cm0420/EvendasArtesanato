package com.TadeuArtesanatos.demo.payment.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.payment.entity.Payment;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookHandler;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookPayload;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWebhookHandlerImpl implements PaymentWebhookHandler {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;

    @Override
    @Transactional
    public void handle(PaymentWebhookPayload payload) {
        Payment payment = paymentRepository.findByTransactionId(payload.transactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada: " + payload.transactionId()));

        // Idempotência: se já foi processado antes (APPROVED ou DECLINED),
        // não repete nada — chamar duas vezes com o mesmo transactionId é seguro.
        if (payment.isResolved()) {
            return;
        }

        PaymentStatus novoStatus = PaymentStatus.valueOf(payload.status().toUpperCase());

        if (novoStatus == PaymentStatus.APPROVED) {
            payment.approve();
            orderService.markAsPaid(payment.getOrder().getId());
        } else if (novoStatus == PaymentStatus.DECLINED) {
            payment.decline();
            // Pedido continua AGUARDANDO_PAGAMENTO — decisão fechada: cliente pode tentar de novo.
        }
        // Se vier PENDING de novo, não faz nada — continua aguardando resolução.
    }
}
