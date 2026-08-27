package com.TadeuArtesanatos.demo.order.entity;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Preenchido quando o item é uma peça avulsa — mutuamente exclusivo com `pieceSet`. */
    @ManyToOne
    @JoinColumn(name = "piece_id")
    private Piece piece;

    /** Preenchido quando o item é um conjunto/kit — mutuamente exclusivo com `piece`. */
    @ManyToOne
    @JoinColumn(name = "piece_set_id")
    private PieceSet pieceSet;

    /** Snapshot — nome do item (peça ou kit) no momento da compra. */
    @Column(name = "item_name", nullable = false)
    private String itemName;

    /** Snapshot — preço unitário no momento da compra. */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    // ---- Regras de negócio ----

    public void recalculateSubtotal() {
        this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isPieceItem() {
        return this.piece != null;
    }

    public boolean isSetItem() {
        return this.pieceSet != null;
    }
}
