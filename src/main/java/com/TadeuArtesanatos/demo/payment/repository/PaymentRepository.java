package com.TadeuArtesanatos.demo.payment.repository;

import com.TadeuArtesanatos.demo.payment.entity.Payment;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findAllByOrderId(UUID orderId);

    /** Pagamento ainda valendo (pendente ou aprovado) — recusado não conta. */
    boolean existsByOrderIdAndStatusIn(UUID orderId, java.util.Collection<PaymentStatus> statuses);
}
