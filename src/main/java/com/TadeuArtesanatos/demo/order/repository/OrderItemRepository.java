package com.TadeuArtesanatos.demo.order.repository;

import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /**
     * Agrega quantidade vendida por peça, só considerando pedidos pagos (PAGO/ENVIADO/ENTREGUE)
     * e só itens de peça avulsa (item de kit não é quebrado nos componentes — limitação documentada).
     */
    @Query("""
            SELECT oi.piece.id, oi.itemName, SUM(oi.quantity)
            FROM OrderItem oi
            WHERE oi.piece IS NOT NULL
              AND oi.order.status IN ('PAGO', 'ENVIADO', 'ENTREGUE')
            GROUP BY oi.piece.id, oi.itemName
            ORDER BY SUM(oi.quantity) DESC
            """)
    List<Object[]> findBestSellingPieces(Pageable pageable);
}
