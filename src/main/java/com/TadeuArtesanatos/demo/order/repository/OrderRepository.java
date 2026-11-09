package com.TadeuArtesanatos.demo.order.repository;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    Page<Order> findAllByCustomerId(UUID customerId, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus status, Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.checkoutStartedAt < :cutoff")
    List<UUID> findIdsByStatusAndCheckoutStartedAtBefore(@Param("status") OrderStatus status, @Param("cutoff") LocalDateTime cutoff);

    /** Trava a linha (SELECT ... FOR UPDATE) sem carregar a entidade; vazio se não existir. */
    @Query(value = "SELECT id FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockById(@Param("id") UUID id);
}
