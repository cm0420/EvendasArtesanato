package com.TadeuArtesanatos.demo.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "piece_sets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PieceSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "set_pieces",
            joinColumns = @JoinColumn(name = "set_id"),
            inverseJoinColumns = @JoinColumn(name = "piece_id")
    )
    private Set<Piece> pieces = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio da entidade ----

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.deletedAt == null;
    }

    /**
     * Estoque do conjunto NUNCA é persistido — é sempre o mínimo entre
     * as peças componentes, calculado em tempo real (regra fechada no CLAUDE.md).
     * Peça inativa (soft-deleted) conta como 0, tirando o conjunto de estoque.
     */
    public int calculateAvailableStock() {
        return pieces.stream()
                .mapToInt(piece -> piece.isActive() ? piece.getStock() : 0)
                .min()
                .orElse(0);
    }
}
