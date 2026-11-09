package com.TadeuArtesanatos.demo.order.repository;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    /**
     * Cria o carrinho só se o cliente ainda não tem um (índice único parcial da V19).
     * Dois "abrir carrinho" simultâneos criavam dois, e depois toda busca do carrinho dava 500.
     */
    @Modifying
    @Query(value = """
            INSERT INTO orders (id, customer_id, status, total_amount, created_at)
            VALUES (uuid_generate_v4(), :customerId, 'CARRINHO', 0, now())
            ON CONFLICT (customer_id) WHERE status = 'CARRINHO' DO NOTHING
            """, nativeQuery = true)
    void insertCartIfAbsent(@Param("customerId") UUID customerId);

    long countByPaidAtGreaterThanEqualAndPaidAtLessThan(LocalDateTime start, LocalDateTime endExclusive);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.paidAt >= :start AND o.paidAt < :endExclusive")
    BigDecimal sumTotalPaidBetween(@Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);

    /** Vendas pagas agrupadas por dia (só dias com venda; o service completa os dias vazios). */
    @Query(value = """
            SELECT CAST(paid_at AS date), COUNT(*), COALESCE(SUM(total_amount), 0)
            FROM orders
            WHERE paid_at >= :start AND paid_at < :endExclusive
            GROUP BY CAST(paid_at AS date)
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> sumPaidByDay(@Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.status <> :excluded GROUP BY o.status")
    List<Object[]> countByStatusExcluding(@Param("excluded") OrderStatus excluded);

    /** Trava a linha (SELECT ... FOR UPDATE) sem carregar a entidade; vazio se não existir. */
    @Query(value = "SELECT id FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockById(@Param("id") UUID id);
}
