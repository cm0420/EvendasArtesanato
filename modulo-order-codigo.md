# Módulo `order` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.order`
> Depende de `customer` (dono do pedido), `catalog` (itens) e `stock` (reserva/confirmação/liberação).
> Não existe módulo de carrinho separado — `CARRINHO` é o primeiro estado do próprio pedido (já fechado no `CLAUDE.md`).
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas

- **Máquina de estados** (já fechada no `CLAUDE.md`): `CARRINHO → AGUARDANDO_PAGAMENTO → PAGO → ENVIADO → ENTREGUE`, com ramo `CANCELADO`/`EXPIRADO` que devolve estoque.
- **Sem soft delete em `Order`** — pedido nunca "some", ele transiciona de estado. `CANCELADO` já cumpre esse papel.
- **Preço e nome da peça são "congelados" no `OrderItem`** (snapshot) no momento da compra — se o admin mudar o preço da peça depois, pedidos antigos continuam mostrando o valor pago de verdade. Sem isso, o histórico ficaria mentiroso.
- **Endereço também é snapshot** — copiado do `Address` escolhido pro `Order` no momento do checkout, não uma FK viva. Se o cliente editar/apagar o endereço depois, o pedido antigo mantém os dados de entrega originais.
- **Só um carrinho aberto por cliente** — `POST /api/orders/me` retorna o carrinho `CARRINHO` já existente se houver, em vez de criar um novo toda vez.
- **`order` depende de `stock`, nunca o contrário** — mesmo raciocínio já fechado no módulo `stock`: evita dependência circular. É por isso que o `orderId` dentro de `StockReservation` continua sem `@JoinColumn` até agora.
- **Pagamento ainda não existe como módulo** — `POST /api/orders/{id}/mark-paid` é um endpoint `ADMIN` temporário, simulando manualmente o que o webhook do gateway real vai fazer quando `payment` for implementado. Fica documentado como provisório.
- **Confirmação de pagamento é idempotente** — chamar `mark-paid` duas vezes no mesmo pedido não gera erro nem confirma reserva de estoque duas vezes (checagem de estado antes de agir).
- **Cancelamento de pedido `PAGO`/depois disso vira estorno**, implementado neste módulo agora (fluxo/auditoria) — ver decisão detalhada abaixo. Cancelamento simples (`cancel()`) continua só pra `CARRINHO`/`AGUARDANDO_PAGAMENTO`; `PAGO` em diante usa o fluxo de estorno.
- **`OrderItem` é polimórfico** — referencia `Piece` **ou** `PieceSet`, nunca os dois ao mesmo tempo (constraint garantida por `CHECK` no banco). Comprar um kit não "explode" em itens separados — o item do pedido continua sendo "1x Kit Casa", mas a reserva de estoque por trás consome a quantidade de **cada peça componente** do kit.
- **Estorno: fluxo/auditoria agora, dinheiro de verdade depois.** Cliente solicita estorno (com motivo) em pedido `PAGO`/`ENVIADO`/`ENTREGUE`, virando `ESTORNO_SOLICITADO`. Admin aprova (`ESTORNADO`, final) ou nega (volta pro status anterior, guardado em `statusBeforeRefund`). Nenhuma reversão financeira real acontece aqui — isso só existe quando `payment` for implementado e tiver uma API de gateway pra estornar de verdade. Fica marcado como decisão consciente, não esquecimento.
- **Migration pendente do `stock` finalmente resolvida**: agora que a tabela `orders` existe, a constraint de FK física em `stock_reservations.order_id` (que tínhamos deixado documentada, mas não criada, no módulo `stock`) entra aqui, na Seção 10.

---

## 1. Enum `OrderStatus`

`order/entity/OrderStatus.java`

```java
package com.TadeuArtesanatos.demo.order.entity;

public enum OrderStatus {
    CARRINHO,
    AGUARDANDO_PAGAMENTO,
    PAGO,
    ENVIADO,
    ENTREGUE,
    CANCELADO,
    EXPIRADO,
    ESTORNO_SOLICITADO,
    ESTORNADO
}
```

---

## 2. Entity `Order`

`order/entity/Order.java`

```java
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
        this.totalAmount = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void startCheckout() {
        this.status = OrderStatus.AGUARDANDO_PAGAMENTO;
        this.checkoutStartedAt = LocalDateTime.now();
    }

    public void markAsPaid() {
        this.status = OrderStatus.PAGO;
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
```

---

## 3. Entity `OrderItem`

`order/entity/OrderItem.java`

```java
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
```

**Nota sobre a exclusividade `piece`/`pieceSet`:** a garantia de "só um dos dois preenchido" não vem de validação Java aqui — vem de duas camadas: (1) o `OrderService.addItem` só monta o `OrderItem` com um dos dois, nunca os dois juntos; (2) uma `CHECK` constraint no banco (migration, seção 10) barra fisicamente qualquer INSERT que viole isso, mesmo que algum bug no código tentasse.

---

## 4. Repository

`order/repository/OrderRepository.java`

```java
package com.TadeuArtesanatos.demo.order.repository;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    Page<Order> findAllByCustomerId(UUID customerId, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus status, Pageable pageable);

    List<Order> findAllByStatusAndCheckoutStartedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
```

---

## 5. DTOs

`order/dto/AddOrderItemRequestDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Exatamente um dos dois (pieceId OU pieceSetId) deve vir preenchido —
 * validado no OrderService, não é possível expressar "XOR" só com Bean Validation.
 */
public record AddOrderItemRequestDto(

        UUID pieceId,

        UUID pieceSetId,

        @NotNull
        @Min(1)
        Integer quantity
) {}
```

`order/dto/UpdateOrderItemRequestDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderItemRequestDto(

        @NotNull
        @Min(1)
        Integer quantity
) {}
```

`order/dto/CheckoutRequestDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CheckoutRequestDto(

        @NotNull
        UUID addressId
) {}
```

`order/dto/ShipOrderRequestDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipOrderRequestDto(

        @NotBlank
        String trackingCode
) {}
```

`order/dto/RequestRefundDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import jakarta.validation.constraints.NotBlank;

public record RequestRefundDto(

        @NotBlank
        String reason
) {}
```

`order/dto/OrderItemResponseDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponseDto(
        UUID id,
        UUID pieceId,
        UUID pieceSetId,
        String itemName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal
) {}
```

`order/dto/OrderResponseDto.java`

```java
package com.TadeuArtesanatos.demo.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponseDto(
        UUID id,
        String status,
        List<OrderItemResponseDto> items,
        BigDecimal totalAmount,
        String recipientName,
        String shippingCity,
        String shippingState,
        String trackingCode,
        LocalDateTime checkoutStartedAt,
        String refundReason,
        LocalDateTime refundRequestedAt,
        LocalDateTime refundDecidedAt,
        LocalDateTime createdAt
) {}
```

---

## 6. Mapper (MapStruct)

`order/mapper/OrderMapper.java`

```java
package com.TadeuArtesanatos.demo.order.mapper;

import com.TadeuArtesanatos.demo.order.dto.OrderItemResponseDto;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderResponseDto toResponseDto(Order order);

    @Mapping(target = "pieceId", expression = "java(item.isPieceItem() ? item.getPiece().getId() : null)")
    @Mapping(target = "pieceSetId", expression = "java(item.isSetItem() ? item.getPieceSet().getId() : null)")
    OrderItemResponseDto toResponseDto(OrderItem item);
}
```

---

## 7. Service

`order/service/OrderService.java`

```java
package com.TadeuArtesanatos.demo.order.service;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceSetRepository;
import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.AddressRepository;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.dto.*;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final PieceRepository pieceRepository;
    private final PieceSetRepository pieceSetRepository;
    private final StockService stockService;
    private final StockReservationRepository stockReservationRepository;
    private final OrderMapper orderMapper;

    // ---- Carrinho (CARRINHO) ----

    @Transactional
    public OrderResponseDto getOrCreateCart(User authenticatedUser) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order cart = orderRepository.findByCustomerIdAndStatus(customer.getId(), OrderStatus.CARRINHO)
                .orElseGet(() -> orderRepository.save(
                        Order.builder()
                                .customer(customer)
                                .status(OrderStatus.CARRINHO)
                                .totalAmount(BigDecimal.ZERO)
                                .build()
                ));

        return orderMapper.toResponseDto(cart);
    }

    @Transactional
    public OrderResponseDto addItem(User authenticatedUser, AddOrderItemRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);

        boolean temPeca = dto.pieceId() != null;
        boolean temConjunto = dto.pieceSetId() != null;

        if (temPeca == temConjunto) {
            throw new IllegalArgumentException("Informe exatamente um: pieceId OU pieceSetId, nunca os dois nem nenhum");
        }

        OrderItem item = temPeca ? buildPieceItem(dto) : buildSetItem(dto);
        item.recalculateSubtotal();

        cart.addItem(item);

        return orderMapper.toResponseDto(cart);
    }

    private OrderItem buildPieceItem(AddOrderItemRequestDto dto) {
        Piece piece = pieceRepository.findById(dto.pieceId())
                .filter(Piece::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

        return OrderItem.builder()
                .piece(piece)
                .itemName(piece.getName())
                .unitPrice(piece.getPrice())
                .quantity(dto.quantity())
                .build();
    }

    private OrderItem buildSetItem(AddOrderItemRequestDto dto) {
        PieceSet pieceSet = pieceSetRepository.findById(dto.pieceSetId())
                .filter(PieceSet::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Conjunto não encontrado"));

        return OrderItem.builder()
                .pieceSet(pieceSet)
                .itemName(pieceSet.getName())
                .unitPrice(pieceSet.getPrice())
                .quantity(dto.quantity())
                .build();
    }

    @Transactional
    public OrderResponseDto updateItemQuantity(User authenticatedUser, UUID itemId, UpdateOrderItemRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);
        OrderItem item = findItemOrThrow(cart, itemId);

        item.setQuantity(dto.quantity());
        item.recalculateSubtotal();
        cart.recalculateTotal();

        return orderMapper.toResponseDto(cart);
    }

    @Transactional
    public OrderResponseDto removeItem(User authenticatedUser, UUID itemId) {
        Order cart = getOwnCartOrThrow(authenticatedUser);
        OrderItem item = findItemOrThrow(cart, itemId);

        cart.removeItem(item);

        return orderMapper.toResponseDto(cart);
    }

    // ---- Checkout (CARRINHO → AGUARDANDO_PAGAMENTO) ----

    @Transactional
    public OrderResponseDto checkout(User authenticatedUser, CheckoutRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);

        if (cart.getItems().isEmpty()) {
            throw new InvalidStateException("Carrinho vazio — adicione ao menos um item antes de finalizar");
        }

        Address address = addressRepository.findById(dto.addressId())
                .filter(a -> a.getCustomer().getId().equals(cart.getCustomer().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Endereço não encontrado"));

        applyShippingSnapshot(cart, address);

        // Reserva estoque de cada item — se qualquer peça estiver esgotada,
        // a transação inteira faz rollback (nenhuma reserva parcial fica presa).
        // Item de kit: reserva a mesma quantidade em CADA peça componente.
        for (OrderItem item : cart.getItems()) {
            if (item.isPieceItem()) {
                reserveForPiece(item.getPiece().getId(), item.getQuantity(), cart.getId());
            } else {
                for (Piece component : item.getPieceSet().getPieces()) {
                    reserveForPiece(component.getId(), item.getQuantity(), cart.getId());
                }
            }
        }

        cart.startCheckout();

        return orderMapper.toResponseDto(cart);
    }

    private void reserveForPiece(UUID pieceId, Integer quantity, UUID orderId) {
        ReserveStockRequestDto reserveDto = new ReserveStockRequestDto(pieceId, quantity, orderId);
        stockService.reserve(reserveDto);
    }

    // ---- Confirmação de pagamento (temporária, manual — até `payment` existir) ----

    @Transactional
    public OrderResponseDto markAsPaid(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        // Idempotência: se já está pago (ou além), não repete a confirmação de estoque.
        if (order.isPaid()) {
            return orderMapper.toResponseDto(order);
        }

        if (!order.isAwaitingPayment()) {
            throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
        for (StockReservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                stockService.confirm(reservation.getId());
            }
        }

        order.markAsPaid();

        return orderMapper.toResponseDto(order);
    }

    // ---- Envio / entrega (ADMIN) ----

    @Transactional
    public OrderResponseDto ship(UUID orderId, ShipOrderRequestDto dto) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.PAGO) {
            throw new InvalidStateException("Só é possível enviar pedidos pagos (status atual: " + order.getStatus() + ")");
        }

        order.ship(dto.trackingCode());

        return orderMapper.toResponseDto(order);
    }

    @Transactional
    public OrderResponseDto deliver(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.ENVIADO) {
            throw new InvalidStateException("Só é possível entregar pedidos enviados (status atual: " + order.getStatus() + ")");
        }

        order.deliver();

        return orderMapper.toResponseDto(order);
    }

    // ---- Cancelamento (dono ou admin, só CARRINHO/AGUARDANDO_PAGAMENTO) ----

    @Transactional
    public void cancelOwnOrder(User authenticatedUser, UUID orderId) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        cancelInternal(order);
    }

    @Transactional
    public void cancelAsAdmin(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        cancelInternal(order);
    }

    private void cancelInternal(Order order) {
        if (order.getStatus() != OrderStatus.CARRINHO && order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Só é possível cancelar pedidos em carrinho ou aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        if (order.isAwaitingPayment()) {
            List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
            for (StockReservation reservation : reservations) {
                if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                    stockService.release(reservation.getId());
                }
            }
        }

        order.cancel();
    }

    // ---- Estorno (fluxo/auditoria — ver decisão no topo do arquivo) ----

    @Transactional
    public void requestRefund(User authenticatedUser, UUID orderId, RequestRefundDto dto) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (!order.isPaid()) {
            throw new InvalidStateException("Só é possível solicitar estorno de pedidos pagos (status atual: " + order.getStatus() + ")");
        }

        order.requestRefund(dto.reason());
    }

    @Transactional
    public OrderResponseDto approveRefund(UUID orderId) {
        Order order = getRefundRequestedOrderOrThrow(orderId);
        order.approveRefund();
        return orderMapper.toResponseDto(order);
    }

    @Transactional
    public OrderResponseDto denyRefund(UUID orderId) {
        Order order = getRefundRequestedOrderOrThrow(orderId);
        order.denyRefund();
        return orderMapper.toResponseDto(order);
    }

    private Order getRefundRequestedOrderOrThrow(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (!order.hasRefundRequested()) {
            throw new InvalidStateException("Este pedido não tem estorno solicitado (status atual: " + order.getStatus() + ")");
        }

        return order;
    }

    // ---- Consultas ----

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> listOwnOrders(User authenticatedUser, Pageable pageable) {
        Customer customer = getCustomerOrThrow(authenticatedUser);
        return orderRepository.findAllByCustomerId(customer.getId(), pageable).map(orderMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public OrderResponseDto findOwnOrder(User authenticatedUser, UUID orderId) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return orderMapper.toResponseDto(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> listAll(Pageable pageable, OrderStatus status) {
        Page<Order> page = (status != null)
                ? orderRepository.findAllByStatus(status, pageable)
                : orderRepository.findAll(pageable);

        return page.map(orderMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public OrderResponseDto findByIdAsAdmin(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return orderMapper.toResponseDto(order);
    }

    // ---- Expiração automática (chamada pelo job) ----

    @Transactional
    public void expireStaleCheckouts(long expirationMinutes) {
        var cutoff = java.time.LocalDateTime.now().minusMinutes(expirationMinutes);
        List<Order> stale = orderRepository.findAllByStatusAndCheckoutStartedAtBefore(OrderStatus.AGUARDANDO_PAGAMENTO, cutoff);

        for (Order order : stale) {
            List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
            for (StockReservation reservation : reservations) {
                if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                    stockService.release(reservation.getId());
                }
            }
            order.expire();
        }
    }

    // ---- helpers internos ----

    private Customer getCustomerOrThrow(User authenticatedUser) {
        return customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));
    }

    private Order getOwnCartOrThrow(User authenticatedUser) {
        Customer customer = getCustomerOrThrow(authenticatedUser);
        return orderRepository.findByCustomerIdAndStatus(customer.getId(), OrderStatus.CARRINHO)
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum carrinho aberto — crie um antes"));
    }

    private OrderItem findItemOrThrow(Order order, UUID itemId) {
        return order.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado no carrinho"));
    }

    private void applyShippingSnapshot(Order order, Address address) {
        order.setRecipientName(address.getRecipientName());
        order.setRecipientPhone(address.getRecipientPhone());
        order.setShippingZipCode(address.getZipCode());
        order.setShippingStreet(address.getStreet());
        order.setShippingNumber(address.getNumber());
        order.setShippingComplement(address.getComplement());
        order.setShippingNeighborhood(address.getNeighborhood());
        order.setShippingCity(address.getCity());
        order.setShippingState(address.getState());
    }
}
```

**Nota sobre `reserve()` dentro do loop de `checkout`:** cada `stockService.reserve(...)` já é `@Transactional` no `StockService`, mas como o `checkout()` também é `@Transactional`, tudo roda dentro de uma única transação maior — se a peça 2 de 3 itens estiver esgotada, o rollback desfaz a reserva da peça 1 também. Isso evita o cenário de "reservei metade do carrinho, mas travei no meio".

---

## 8. Job assíncrono — expiração de checkout

`order/job/OrderExpirationJob.java`

```java
package com.TadeuArtesanatos.demo.order.job;

import com.TadeuArtesanatos.demo.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderExpirationJob {

    private final OrderService orderService;

    @Value("${app.order.checkout-expiration-minutes:20}")
    private long checkoutExpirationMinutes;

    /**
     * Roda a cada 2 minutos — encontra pedidos AGUARDANDO_PAGAMENTO cujo
     * checkout começou há mais tempo que o permitido, libera as reservas
     * de estoque ativas e marca o pedido como EXPIRADO.
     */
    @Scheduled(fixedRate = 120_000)
    public void expireStaleCheckouts() {
        orderService.expireStaleCheckouts(checkoutExpirationMinutes);
    }
}
```

**Por que um job separado do `StockReservationCleanupJob`:** o job do `stock` já libera a *reserva* individual quando ela expira, mas não sabe nada sobre `Order` (não pode saber — `stock` não importa `order`). Esse job aqui, do lado de `order`, cuida da outra metade: encontrar os *pedidos* travados em `AGUARDANDO_PAGAMENTO` havia tempo demais e mudar o status deles pra `EXPIRADO`. Os dois jobs se completam sem se conhecerem.

---

## 9. Controller

`order/controller/OrderController.java`

```java
package com.TadeuArtesanatos.demo.order.controller;

import com.TadeuArtesanatos.demo.order.dto.*;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // ---- Rotas /me — o próprio cliente autenticado ----

    @PostMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> getOrCreateCart(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(orderService.getOrCreateCart(user));
    }

    @PostMapping(value = "/me/items", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> addItem(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddOrderItemRequestDto dto
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.addItem(user, dto));
    }

    @PutMapping(value = "/me/items/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> updateItem(
            @AuthenticationPrincipal User user,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateOrderItemRequestDto dto
    ) {
        return ResponseEntity.ok(orderService.updateItemQuantity(user, itemId, dto));
    }

    @DeleteMapping(value = "/me/items/{itemId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> removeItem(
            @AuthenticationPrincipal User user,
            @PathVariable UUID itemId
    ) {
        return ResponseEntity.ok(orderService.removeItem(user, itemId));
    }

    @PostMapping(value = "/me/checkout", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> checkout(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CheckoutRequestDto dto
    ) {
        return ResponseEntity.ok(orderService.checkout(user, dto));
    }

    @PostMapping(value = "/me/{orderId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cancelOwnOrder(@AuthenticationPrincipal User user, @PathVariable UUID orderId) {
        orderService.cancelOwnOrder(user, orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/me/{orderId}/refund-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> requestRefund(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @Valid @RequestBody RequestRefundDto dto
    ) {
        orderService.requestRefund(user, orderId, dto);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> listOwnOrders(@AuthenticationPrincipal User user, Pageable pageable) {
        return ResponseEntity.ok(orderService.listOwnOrders(user, pageable));
    }

    @GetMapping(value = "/me/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> findOwnOrder(@AuthenticationPrincipal User user, @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.findOwnOrder(user, orderId));
    }

    // ---- Rotas admin — qualquer pedido, regra fixa, vai no SecurityConfig ----

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> listAll(
            Pageable pageable,
            @RequestParam(required = false) OrderStatus status
    ) {
        return ResponseEntity.ok(orderService.listAll(pageable, status));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> findByIdAsAdmin(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.findByIdAsAdmin(id));
    }

    @PostMapping(value = "/{id}/mark-paid", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> markAsPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.markAsPaid(id));
    }

    @PostMapping(value = "/{id}/ship", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> ship(@PathVariable UUID id, @Valid @RequestBody ShipOrderRequestDto dto) {
        return ResponseEntity.ok(orderService.ship(id, dto));
    }

    @PostMapping(value = "/{id}/deliver", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> deliver(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.deliver(id));
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> cancelAsAdmin(@PathVariable UUID id) {
        orderService.cancelAsAdmin(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/refund-approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> approveRefund(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.approveRefund(id));
    }

    @PostMapping(value = "/{id}/refund-deny", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> denyRefund(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.denyRefund(id));
    }
}
```

**Regra a adicionar no `SecurityConfig`** (sincronizar junto das demais):

```java
// order — rotas /me autenticadas, rotas admin fixas
.requestMatchers("/api/orders/me/**").authenticated()
.requestMatchers(HttpMethod.GET, "/api/orders").hasRole("ADMIN")
.requestMatchers(HttpMethod.GET, "/api/orders/*").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/mark-paid").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/ship").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/deliver").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/cancel").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/refund-approve").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/orders/*/refund-deny").hasRole("ADMIN")
```

---

## 10. Migrations

`db/migration/V12__create_table_orders.sql`

```sql
CREATE TABLE orders (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id           UUID NOT NULL REFERENCES customers(id),
    status                VARCHAR(30) NOT NULL,
    total_amount          NUMERIC(10,2) NOT NULL DEFAULT 0,
    recipient_name        VARCHAR(100),
    recipient_phone       VARCHAR(20),
    shipping_zip_code     VARCHAR(8),
    shipping_street       VARCHAR(255),
    shipping_number       VARCHAR(20),
    shipping_complement   VARCHAR(255),
    shipping_neighborhood VARCHAR(100),
    shipping_city         VARCHAR(100),
    shipping_state        VARCHAR(2),
    tracking_code         VARCHAR(100),
    checkout_started_at   TIMESTAMP,
    refund_reason         VARCHAR(500),
    refund_requested_at   TIMESTAMP,
    refund_decided_at     TIMESTAMP,
    status_before_refund  VARCHAR(30),
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_status_checkout ON orders (status, checkout_started_at);
```

`db/migration/V13__create_table_order_items.sql`

```sql
CREATE TABLE order_items (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id      UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    piece_id      UUID REFERENCES pieces(id),
    piece_set_id  UUID REFERENCES piece_sets(id),
    item_name     VARCHAR(150) NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    quantity      INTEGER NOT NULL,
    subtotal      NUMERIC(10,2) NOT NULL,
    CONSTRAINT chk_order_items_exactly_one_reference CHECK (
        (piece_id IS NOT NULL AND piece_set_id IS NULL) OR
        (piece_id IS NULL AND piece_set_id IS NOT NULL)
    )
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
```

**Nota sobre o `CHECK` constraint:** garante no banco, de forma física e inquebrável, que todo `order_item` referencia exatamente uma coisa (peça OU kit), nunca as duas nem nenhuma — mesmo que um bug futuro no `OrderService` tentasse inserir os dois juntos, o Postgres rejeitaria o INSERT.

`db/migration/V14__add_fk_stock_reservations_order.sql`

```sql
-- Pendência aberta desde o módulo stock: agora que a tabela orders existe,
-- adiciona a constraint física que faltava. Nenhuma mudança de código Java
-- foi necessária — o campo order_id já existia como UUID solto.
ALTER TABLE stock_reservations
ADD CONSTRAINT fk_stock_reservations_order
FOREIGN KEY (order_id) REFERENCES orders(id);
```

---

## 11. Configuração

Adicionar em `application-dev.yml` (junto de `app.stock.reservation-expiration-minutes`):

```yaml
app:
  order:
    checkout-expiration-minutes: 2  # curto em dev, pra testar o job sem esperar muito
```

---

## Pendências e decisões em aberto

1. **`ResourceNotFoundException`/`InvalidStateException`** já vêm da hierarquia nova do `common/exception` — confirma que ela já está aplicada antes de rodar `order`.
2. **Numeração das migrations** — assumi `V12`–`V14` partindo do `V11` (stock_reservations) já existente; confirma antes de criar os arquivos.
3. **`mark-paid` é provisório** — quando `payment` for implementado, esse endpoint provavelmente é substituído (ou complementado) por um webhook automático do gateway, usando o mesmo `OrderService.markAsPaid(orderId)` internamente.
4. **Estorno aprovado (`ESTORNADO`) não mexe no estoque** — decisão consciente, não presumida: como o produto físico normalmente precisa ser devolvido antes de voltar ao estoque (logística de devolução, fora de escopo aqui), o estorno aprovado não incrementa `Piece.stock` automaticamente. Se um dia isso for necessário, entra como ação manual do admin via `PUT /api/catalog/pieces/{id}` ajustando o estoque à mão, ou uma lógica nova quando `payment`/logística reversa for desenhada.

---

## 12. Teste via curl

Cobre o fluxo completo: criar carrinho → adicionar item → checkout (reserva estoque) → confirmar pagamento → enviar → entregar. Depois, um segundo cenário testando cancelamento e devolução de estoque.

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"

echo "== 1. Login como CLIENTE (não-admin) =="
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "miguel@teste.com", "password": "senha12345"}')
CUSTOMER_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')
echo "Token cliente: ${CUSTOMER_TOKEN:0:20}..."

echo ""
echo "== 2. Login como ADMIN (pra criar peça e depois confirmar pagamento) =="
ADMIN_LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "joao@teste.com", "password": "senha12345"}')
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN_RESPONSE" | jq -r '.accessToken')

echo ""
echo "== 3. Criar peça de teste com stock: 5 =="
PIECE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Peça teste order", "description": "peça de teste", "price": 40.00, "stock": 5, "imageUrls": [], "categoryIds": []}')
PIECE_ID=$(echo "$PIECE_RESPONSE" | jq -r '.id')
echo "Peça criada: $PIECE_ID (stock: 5)"

echo ""
echo "== 4. Garantir que o cliente tem um endereço cadastrado =="
ADDRESS_RESPONSE=$(curl -s -X POST "$BASE_URL/api/customers/me/addresses" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label": "Casa", "recipientName": "Miguel Costa", "recipientPhone": "(11) 91234-5678", "zipCode": "01310100", "street": "Av Paulista", "number": "1000", "neighborhood": "Bela Vista", "city": "São Paulo", "state": "SP", "isDefault": true}')
ADDRESS_ID=$(echo "$ADDRESS_RESPONSE" | jq -r '.id')
echo "Endereço criado: $ADDRESS_ID"

echo ""
echo "== 5. Criar/pegar carrinho do cliente =="
CART_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN")
ORDER_ID=$(echo "$CART_RESPONSE" | jq -r '.id')
echo "Carrinho: $ORDER_ID"
echo "$CART_RESPONSE" | jq '{id, status, totalAmount}'

echo ""
echo "== 6. Adicionar 2 unidades da peça ao carrinho =="
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 2}" | jq '{status, totalAmount, items}'

echo ""
echo "== 7. Fazer checkout =="
CHECKOUT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}")
echo "$CHECKOUT_RESPONSE" | jq '{status, totalAmount, recipientName, shippingCity}'
echo "Esperado: status = AGUARDANDO_PAGAMENTO"

echo ""
echo "== 8. Conferir que o stock da peça caiu para 3 =="
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'
echo "Esperado: stock = 3"

echo ""
echo "== 9. Admin confirma o pagamento (mark-paid) =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID/mark-paid" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status}'
echo "Esperado: status = PAGO"

echo ""
echo "== 10. Confirmar pagamento de novo (idempotência) — não deve dar erro =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID/mark-paid" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status}'
echo "Esperado: status = PAGO (sem erro, sem duplicar nada)"

echo ""
echo "== 11. Admin envia o pedido =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID/ship" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"trackingCode": "BR123456789"}' | jq '{status, trackingCode}'
echo "Esperado: status = ENVIADO"

echo ""
echo "== 12. Admin marca como entregue =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID/deliver" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status}'
echo "Esperado: status = ENTREGUE"

echo ""
echo "=========================================="
echo "== CENÁRIO 2: cancelamento devolve estoque =="
echo "=========================================="

echo ""
echo "== 13. Novo carrinho, adicionar item, fazer checkout de novo =="
curl -s -X POST "$BASE_URL/api/orders/me" -H "Authorization: Bearer $CUSTOMER_TOKEN" > /dev/null
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 1}" > /dev/null

CHECKOUT_2_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}")
ORDER_ID_2=$(echo "$CHECKOUT_2_RESPONSE" | jq -r '.id')
echo "Pedido 2: $ORDER_ID_2"

curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'
echo "Esperado: stock = 2 (3 - 1)"

echo ""
echo "== 14. Cliente cancela o próprio pedido =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID_2/cancel" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -w "\nStatus HTTP: %{http_code}\n"

echo ""
echo "== 15. Conferir que o stock voltou para 3 =="
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'
echo "Esperado: stock = 3 (devolvido pelo cancelamento)"

echo ""
echo "=========================================="
echo "== CENÁRIO 3: comprar um kit e solicitar estorno =="
echo "=========================================="

echo ""
echo "== 16. Criar uma segunda peça e um kit com as duas =="
PIECE_2_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Peça 2 teste order", "description": "peça de teste", "price": 30.00, "stock": 4, "imageUrls": [], "categoryIds": []}')
PIECE_2_ID=$(echo "$PIECE_2_RESPONSE" | jq -r '.id')

SET_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/sets" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Kit teste order\", \"price\": 60.00, \"pieceIds\": [\"$PIECE_ID\", \"$PIECE_2_ID\"]}")
SET_ID=$(echo "$SET_RESPONSE" | jq -r '.id')
echo "Kit criado: $SET_ID (estoque disponível esperado: min(3, 4) = 3)"

echo ""
echo "== 17. Novo carrinho, adicionar o KIT (não peça avulsa) =="
curl -s -X POST "$BASE_URL/api/orders/me" -H "Authorization: Bearer $CUSTOMER_TOKEN" > /dev/null
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceSetId\": \"$SET_ID\", \"quantity\": 1}" | jq '{status, totalAmount, items}'

echo ""
echo "== 18. Checkout do kit =="
CHECKOUT_3_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}")
ORDER_ID_3=$(echo "$CHECKOUT_3_RESPONSE" | jq -r '.id')
echo "Pedido 3 (kit): $ORDER_ID_3"

echo ""
echo "== 19. Conferir que AS DUAS peças componentes tiveram estoque decrementado =="
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'
echo "Esperado: stock = 2 (3 - 1, decrementado pelo kit)"
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_2_ID" | jq '{stock}'
echo "Esperado: stock = 3 (4 - 1, decrementado pelo kit)"

echo ""
echo "== 20. Admin confirma pagamento do pedido do kit =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID_3/mark-paid" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status}'
echo "Esperado: status = PAGO"

echo ""
echo "== 21. Cliente solicita estorno =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID_3/refund-request" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Peça veio com defeito"}' -w "\nStatus HTTP: %{http_code}\n"

curl -s "$BASE_URL/api/orders/$ORDER_ID_3" -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status, refundReason, refundRequestedAt}'
echo "Esperado: status = ESTORNO_SOLICITADO"

echo ""
echo "== 22. Admin nega o estorno — deve voltar pro status anterior (PAGO) =="
curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID_3/refund-deny" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status, refundDecidedAt}'
echo "Esperado: status = PAGO (voltou, não ficou preso em ESTORNO_SOLICITADO)"

echo ""
echo "== 23. Cliente solicita estorno de novo, dessa vez admin APROVA =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID_3/refund-request" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Desisti da compra"}' > /dev/null

curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID_3/refund-approve" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '{status, refundDecidedAt}'
echo "Esperado: status = ESTORNADO (final)"

echo ""
echo "== Teste completo =="
```

### O que cada bloco valida

| Passo | O que confirma |
|---|---|
| 1–4 | Setup — clientes, peça, endereço |
| 5–6 | Carrinho criado uma vez só, itens adicionados corretamente, total recalculado |
| 7–8 | Checkout muda status e **reserva estoque de verdade** (integração com `stock`) |
| 9–10 | Confirmação de pagamento muda status e é **idempotente** (chamar duas vezes não quebra) |
| 11–12 | Máquina de estados avança até `ENTREGUE` |
| 13–15 | Cancelamento em `AGUARDANDO_PAGAMENTO` **devolve o estoque** corretamente |
| 16–19 | Comprar um **kit** decrementa **cada peça componente**, não um contador próprio do kit |
| 20–23 | Fluxo de estorno: solicitar → negar (volta ao status anterior) → solicitar de novo → aprovar (final) |

**Não testado neste script:** expiração automática via job (`OrderExpirationJob`) — segue o mesmo princípio do teste do `stock` (precisa esperar o intervalo configurado); e concorrência real entre dois clientes disputando o mesmo carrinho/estoque simultaneamente — mesma limitação já registrada nos módulos anteriores.
