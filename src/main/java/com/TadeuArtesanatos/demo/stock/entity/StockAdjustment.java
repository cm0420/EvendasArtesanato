package com.TadeuArtesanatos.demo.stock.entity;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "stock_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "piece_id", nullable = false)
    private Piece piece;

    /** Positivo = adicionou estoque; negativo = removeu (ex: venda fora do site, perda, avaria). */
    @Column(name = "delta", nullable = false)
    private Integer delta;

    @Column(name = "reason", nullable = false)
    private String reason;

    /** Snapshot do email do admin que fez o ajuste — sem FK pra User, evita stock importar user. */
    @Column(name = "adjusted_by_email", nullable = false)
    private String adjustedByEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
