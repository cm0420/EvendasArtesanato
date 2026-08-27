package com.TadeuArtesanatos.demo.payment.repository;

import com.TadeuArtesanatos.demo.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findAllByOrderId(UUID orderId);
}
