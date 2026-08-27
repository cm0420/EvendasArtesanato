package com.TadeuArtesanatos.demo.order.entity;

import com.TadeuArtesanatos.demo.customer.entity.Customer;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // ---- Snapshot do endereço de entrega, preenchido no checkout ----

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(name = "shipping_zip_code")
    private String shippingZipCode;

    @Column(name = "shipping_street")
    private String shippingStreet;

    @Column(name = "shipping_number")
    private String shippingNumber;

    @Column(name = "shipping_complement")
    private String shippingComplement;

    @Column(name = "shipping_neighborhood")
    private String shippingNeighborhood;

    @Column(name = "shipping_city")
    private String shippingCity;

    @Column(name = "shipping_state")
    private String shippingState;

    @Column(name = "tracking_code")
    private String trackingCode;

    @Column(name = "checkout_started_at")
    private LocalDateTime checkoutStartedAt;

    // ---- Frete, selecionado depois do checkout ----

    @Column(name = "shipping_cost", precision = 10, scale = 2)
    private BigDecimal shippingCost;

    @Column(name = "shipping_option_code")
    private String shippingOptionCode;

    @Column(name = "shipping_option_name")
    private String shippingOptionName;

    @Column(name = "estimated_delivery_days")
    private Integer estimatedDeliveryDays;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // ---- Estorno (fluxo/auditoria — sem devolução financeira real ainda) ----

    @Column(name = "refund_reason")
    private String refundReason;

    @Column(name = "refund_requested_at")
    private LocalDateTime refundRequestedAt;

    @Column(name = "refund_decided_at")
    private LocalDateTime refundDecidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_refund")
    private OrderStatus statusBeforeRefund;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio da entidade (máquina de estados) ----

    public void addItem(OrderItem item) {
        this.items.add(item);
        item.setOrder(this);
        recalculateTotal();
    }

    public void removeItem(OrderItem item) {
        this.items.remove(item);
        recalculateTotal();
    }

    public void recalculateTotal() {
        BigDecimal itemsTotal = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal shipping = (shippingCost != null) ? shippingCost : BigDecimal.ZERO;

        this.totalAmount = itemsTotal.add(shipping);
    }

    public void startCheckout() {
        this.status = OrderStatus.AGUARDANDO_PAGAMENTO;
        this.checkoutStartedAt = LocalDateTime.now();
    }

    // ---- Frete ----

    public void applyShipping(String optionCode, String optionName, BigDecimal cost, Integer estimatedDays) {
        this.shippingOptionCode = optionCode;
        this.shippingOptionName = optionName;
        this.shippingCost = cost;
        this.estimatedDeliveryDays = estimatedDays;
        recalculateTotal();
    }

    public boolean hasShippingSelected() {
        return this.shippingOptionCode != null;
    }

    public void markAsPaid() {
        this.status = OrderStatus.PAGO;
        this.paidAt = LocalDateTime.now();
    }

    public void ship(String trackingCode) {
        this.status = OrderStatus.ENVIADO;
        this.trackingCode = trackingCode;
    }

    public void deliver() {
        this.status = OrderStatus.ENTREGUE;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELADO;
    }

    public void expire() {
        this.status = OrderStatus.EXPIRADO;
    }

    public boolean isCart() {
        return this.status == OrderStatus.CARRINHO;
    }

    public boolean isAwaitingPayment() {
        return this.status == OrderStatus.AGUARDANDO_PAGAMENTO;
    }

    public boolean isPaid() {
        return this.status == OrderStatus.PAGO
                || this.status == OrderStatus.ENVIADO
                || this.status == OrderStatus.ENTREGUE;
    }

    // ---- Estorno ----

    public void requestRefund(String reason) {
        this.statusBeforeRefund = this.status;
        this.status = OrderStatus.ESTORNO_SOLICITADO;
        this.refundReason = reason;
        this.refundRequestedAt = LocalDateTime.now();
    }

    public void approveRefund() {
        this.status = OrderStatus.ESTORNADO;
        this.refundDecidedAt = LocalDateTime.now();
    }

    public void denyRefund() {
        this.status = this.statusBeforeRefund;
        this.refundDecidedAt = LocalDateTime.now();
    }

    public boolean hasRefundRequested() {
        return this.status == OrderStatus.ESTORNO_SOLICITADO;
    }
}
