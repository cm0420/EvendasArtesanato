# Módulo `admin` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.admin`
> **Sem entidade própria** (já fechado no `CLAUDE.md`) — combina dados de `order`, `catalog`, `stock`, `payment`.
> Precisa de edições/adições em módulos já existentes antes dos arquivos novos aqui. Marcado bem claro.

---

## Decisões aplicadas

- **Gestão de pedidos não duplica o que já existe.** `GET /api/orders`, `mark-paid`, `ship`, `deliver`, `cancel`, `refund-approve/deny` já são `ADMIN` desde o módulo `order` — não recriei nada disso aqui. O `admin` só adiciona uma **visão de detalhe consolidada** (`GET /api/admin/orders/{id}`) que junta pedido + dados do cliente + histórico de pagamento numa resposta só, porque o `OrderResponseDto` sozinho não tem isso.
- **Ajuste de estoque é auditado**, não é só um `PUT` sobrescrevendo o número — precisa saber quem ajustou, quanto, e por quê (o `CLAUDE.md` já pedia isso: "possibilidade de zerar/ajustar estoque manualmente, ex: venda fora do site"). Criei uma entity nova pra isso, mas ela mora no **`stock`**, não no `admin` — é conceitualmente "mudança de estoque", o mesmo domínio de `StockReservation`. `admin` só expõe o endpoint.
- **`paidAt` precisa existir** pra relatório de vendas por período fazer sentido de verdade — hoje `Order` só tem `createdAt` (quando o carrinho foi criado) e `checkoutStartedAt`, nenhum dos dois é "quando foi pago". Adicionei o campo.
- **"Peças mais vendidas" só conta itens de peça avulsa**, não quebra kits nos componentes — limitação documentada, não escondida (contar kit exigiria decidir se cada componente conta como "vendido" ou só o kit em si; não presumi a resposta).

---

## ⚠️ Edições necessárias em `order`

### `order/entity/Order.java` — adicionar campo e atualizar método

```java
@Column(name = "paid_at")
private LocalDateTime paidAt;
```

Atualizar `markAsPaid()` já existente:

```java
public void markAsPaid() {
    this.status = OrderStatus.PAGO;
    this.paidAt = LocalDateTime.now();
}
```

### `order/repository/OrderItemRepository.java` — arquivo **novo** (não existia; `OrderItem` só era acessado via `Order.getItems()`)

```java
package com.TadeuArtesanatos.demo.order.repository;

import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<com.TadeuArtesanatos.demo.order.entity.OrderItem, UUID> {

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
```

**Nota sobre `itemName` vindo de `oi` e não de `oi.piece.name`:** de propósito — usa o *snapshot* gravado no momento da compra (campo `itemName` em `OrderItem`, ver módulo `order`), não o nome atual da peça (que pode ter mudado desde então). Consistente com a decisão de snapshot já fechada no módulo `order`. **Ajuste aplicado nesta sessão**: o rascunho original desse `.md` chamava o campo de `pieceName`, mas o campo real em `OrderItem` é `itemName` — corrigido aqui pra bater com o código implementado.

---

## ⚠️ Edição necessária em `stock`

### `stock/entity/StockAdjustment.java` — arquivo **novo**

```java
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
```

### `stock/repository/StockAdjustmentRepository.java` — arquivo **novo**

```java
package com.TadeuArtesanatos.demo.stock.repository;

import com.TadeuArtesanatos.demo.stock.entity.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {
}
```

### `stock/service/StockService.java` — adicionar método (não é arquivo novo, é edição no já existente)

```java
@Transactional
public StockAdjustment adjustStock(UUID pieceId, Integer delta, String reason, String adjustedByEmail) {
    Piece piece = pieceRepository.findByIdForUpdate(pieceId)
            .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

    int novoEstoque = piece.getStock() + delta;
    if (novoEstoque < 0) {
        throw new InvalidStateException("Ajuste deixaria o estoque negativo (atual: " + piece.getStock() + ", delta: " + delta + ")");
    }

    piece.setStock(novoEstoque);

    StockAdjustment adjustment = StockAdjustment.builder()
            .piece(piece)
            .delta(delta)
            .reason(reason)
            .adjustedByEmail(adjustedByEmail)
            .build();

    return stockAdjustmentRepository.save(adjustment);
}
```

Precisa injetar `StockAdjustmentRepository` no construtor do `StockService` (mais um campo `private final StockAdjustmentRepository stockAdjustmentRepository;`), e os imports de `StockAdjustment`, `InvalidStateException`.

---

## 1. DTOs (módulo `admin`)

`admin/dto/StockAdjustmentRequestDto.java`

```java
package com.TadeuArtesanatos.demo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockAdjustmentRequestDto(

        @NotNull
        Integer delta,

        @NotBlank
        String reason
) {}
```

`admin/dto/StockAdjustmentResponseDto.java`

```java
package com.TadeuArtesanatos.demo.admin.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StockAdjustmentResponseDto(
        UUID id,
        UUID pieceId,
        Integer delta,
        Integer newStock,
        String reason,
        String adjustedByEmail,
        LocalDateTime createdAt
) {}
```

`admin/dto/AdminOrderDetailResponseDto.java`

```java
package com.TadeuArtesanatos.demo.admin.dto;

import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;

import java.util.List;

public record AdminOrderDetailResponseDto(
        OrderResponseDto order,
        String customerName,
        String customerEmail,
        String customerPhone,
        List<PaymentResponseDto> payments
) {}
```

`admin/dto/SalesReportResponseDto.java`

```java
package com.TadeuArtesanatos.demo.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesReportResponseDto(
        LocalDate startDate,
        LocalDate endDate,
        long totalOrders,
        BigDecimal totalRevenue
) {}
```

`admin/dto/BestSellingPieceResponseDto.java`

```java
package com.TadeuArtesanatos.demo.admin.dto;

import java.util.UUID;

public record BestSellingPieceResponseDto(
        UUID pieceId,
        String pieceName,
        long totalQuantitySold
) {}
```

---

## 2. Service

`admin/service/AdminService.java`

```java
package com.TadeuArtesanatos.demo.admin.service;

import com.TadeuArtesanatos.demo.admin.dto.*;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderItemRepository;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.mapper.PaymentMapper;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import com.TadeuArtesanatos.demo.stock.entity.StockAdjustment;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final StockService stockService;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;

    // ---- Ajuste de estoque ----

    @Transactional
    public StockAdjustmentResponseDto adjustStock(UUID pieceId, Integer delta, String reason, String adminEmail) {
        StockAdjustment adjustment = stockService.adjustStock(pieceId, delta, reason, adminEmail);

        return new StockAdjustmentResponseDto(
                adjustment.getId(),
                adjustment.getPiece().getId(),
                adjustment.getDelta(),
                adjustment.getPiece().getStock(),
                adjustment.getReason(),
                adjustment.getAdjustedByEmail(),
                adjustment.getCreatedAt()
        );
    }

    // ---- Detalhe consolidado de pedido ----

    @Transactional(readOnly = true)
    public AdminOrderDetailResponseDto getOrderDetail(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        Customer customer = order.getCustomer();

        List<PaymentResponseDto> payments = paymentRepository.findAllByOrderId(orderId).stream()
                .map(paymentMapper::toResponseDto)
                .toList();

        OrderResponseDto orderDto = orderMapper.toResponseDto(order);

        return new AdminOrderDetailResponseDto(
                orderDto,
                customer.getFirstName() + " " + customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                payments
        );
    }

    // ---- Relatórios ----

    @Transactional(readOnly = true)
    public SalesReportResponseDto getSalesReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);

        List<Order> paidOrders = orderRepository.findAll().stream()
                .filter(o -> o.getPaidAt() != null)
                .filter(o -> !o.getPaidAt().isBefore(start) && !o.getPaidAt().isAfter(end))
                .toList();

        BigDecimal total = paidOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SalesReportResponseDto(startDate, endDate, paidOrders.size(), total);
    }

    @Transactional(readOnly = true)
    public List<BestSellingPieceResponseDto> getBestSellingPieces(int limit) {
        return orderItemRepository.findBestSellingPieces(PageRequest.of(0, limit)).stream()
                .map(row -> new BestSellingPieceResponseDto(
                        (UUID) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getPendingShipmentOrders(Pageable pageable) {
        return orderRepository.findAllByStatus(OrderStatus.PAGO, pageable)
                .map(orderMapper::toResponseDto);
    }
}
```

**Nota sobre `getSalesReport` buscar tudo com `findAll()` e filtrar em memória:** funciona bem pro volume atual do projeto, mas não escala — pra um relatório de verdade em produção, o certo seria uma query com `WHERE paid_at BETWEEN :start AND :end` direto no banco (`OrderRepository.findAllByPaidAtBetween`). Deixei assim de propósito, simples, mas documentado como ponto de melhoria futura, não escondido.

---

## 3. Controller

`admin/controller/AdminController.java`

```java
package com.TadeuArtesanatos.demo.admin.controller;

import com.TadeuArtesanatos.demo.admin.dto.*;
import com.TadeuArtesanatos.demo.admin.service.AdminService;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping(value = "/pieces/{id}/stock-adjustment",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockAdjustmentResponseDto> adjustStock(
            @AuthenticationPrincipal User adminUser,
            @PathVariable UUID id,
            @Valid @RequestBody StockAdjustmentRequestDto dto
    ) {
        StockAdjustmentResponseDto result = adminService.adjustStock(id, dto.delta(), dto.reason(), adminUser.getEmail());
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminOrderDetailResponseDto> getOrderDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getOrderDetail(id));
    }

    @GetMapping(value = "/reports/sales", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SalesReportResponseDto> getSalesReport(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate
    ) {
        return ResponseEntity.ok(adminService.getSalesReport(startDate, endDate));
    }

    @GetMapping(value = "/reports/best-selling-pieces", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BestSellingPieceResponseDto>> getBestSellingPieces(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(adminService.getBestSellingPieces(limit));
    }

    @GetMapping(value = "/reports/pending-shipment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> getPendingShipmentOrders(Pageable pageable) {
        return ResponseEntity.ok(adminService.getPendingShipmentOrders(pageable));
    }
}
```

**Regra a adicionar no `SecurityConfig`:**

```java
// admin — tudo ADMIN, sem exceção
.requestMatchers("/api/admin/**").hasRole("ADMIN")
```

---

## 4. Migrations

`db/migration/V17__add_paid_at_to_orders.sql`

```sql
ALTER TABLE orders
ADD COLUMN paid_at TIMESTAMP;
```

`db/migration/V18__create_table_stock_adjustments.sql`

```sql
CREATE TABLE stock_adjustments (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    piece_id           UUID NOT NULL REFERENCES pieces(id),
    delta              INTEGER NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    adjusted_by_email  VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_adjustments_piece_id ON stock_adjustments (piece_id);
```

---

## 5. Teste via curl

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"

echo "== 1. Login ADMIN e CLIENTE =="
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"email": "joao@teste.com", "password": "senha12345"}' | jq -r '.accessToken')
CUSTOMER_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"email": "miguel@teste.com", "password": "senha12345"}' | jq -r '.accessToken')

echo ""
echo "== 2. Criar peça com stock: 10 =="
PIECE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Peça teste admin", "description": "teste", "price": 40.00, "stock": 10, "imageUrls": [], "categoryIds": []}')
PIECE_ID=$(echo "$PIECE_RESPONSE" | jq -r '.id')
echo "Peça: $PIECE_ID (stock: 10)"

echo ""
echo "== 3. Ajustar estoque: -3 (venda fora do site) =="
curl -s -X POST "$BASE_URL/api/admin/pieces/$PIECE_ID/stock-adjustment" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"delta": -3, "reason": "Venda balcão"}' | jq
echo "Esperado: newStock = 7"

echo ""
echo "== 4. Tentar ajuste que deixaria negativo — deve dar 409 =="
curl -s -X POST "$BASE_URL/api/admin/pieces/$PIECE_ID/stock-adjustment" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"delta": -100, "reason": "teste"}' -w "\nStatus HTTP: %{http_code}\n"

echo ""
echo "== 5. Montar e pagar um pedido, pra gerar dado pros relatórios =="
ADDRESS_ID=$(curl -s -X POST "$BASE_URL/api/customers/me/addresses" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"label": "Casa", "recipientName": "Miguel Costa", "recipientPhone": "(11) 91234-5678", "zipCode": "01310100", "street": "Av Paulista", "number": "1000", "neighborhood": "Bela Vista", "city": "São Paulo", "state": "SP", "isDefault": true}' | jq -r '.id')

curl -s -X POST "$BASE_URL/api/orders/me" -H "Authorization: Bearer $CUSTOMER_TOKEN" > /dev/null
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 2}" > /dev/null

ORDER_ID=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}" | jq -r '.id')

curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/shipping" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"code": "STANDARD"}' > /dev/null

curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "APPROVED"}' > /dev/null

echo "Pedido pago: $ORDER_ID"

echo ""
echo "== 6. Ver detalhe consolidado do pedido (cliente + pagamento juntos) =="
curl -s "$BASE_URL/api/admin/orders/$ORDER_ID" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
echo "Esperado: order + customerName/Email/Phone + lista de payments"

echo ""
echo "== 7. Relatório de vendas do dia de hoje =="
HOJE=$(date +%Y-%m-%d)
curl -s "$BASE_URL/api/admin/reports/sales?startDate=$HOJE&endDate=$HOJE" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
echo "Esperado: totalOrders >= 1, totalRevenue > 0"

echo ""
echo "== 8. Peças mais vendidas =="
curl -s "$BASE_URL/api/admin/reports/best-selling-pieces?limit=5" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
echo "Esperado: a peça criada aparece com totalQuantitySold = 2"

echo ""
echo "== 9. Pedidos pendentes de envio (status PAGO) =="
curl -s "$BASE_URL/api/admin/reports/pending-shipment" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.content[] | {id, status}'
echo "Esperado: o pedido criado aparece na lista"

echo ""
echo "== 10. Confirmar que endpoints admin bloqueiam CLIENTE comum =="
curl -s -X POST "$BASE_URL/api/admin/pieces/$PIECE_ID/stock-adjustment" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"delta": 1, "reason": "teste"}' -w "\nStatus HTTP: %{http_code}\n"
echo "Esperado: 403"

echo ""
echo "== Teste completo =="
```

---

## Pendências e decisões em aberto

1. **Edições em `order` e `stock` precisam ser aplicadas antes de testar** — campo `paidAt`, método `adjustStock`, e os dois arquivos novos (`OrderItemRepository`, `StockAdjustment` + repository).
2. Numeração de migration — assumi `V17`/`V18` partindo do `V16` (shipping).
3. **"Peças mais vendidas" não conta itens de kit** — limitação documentada, decisão de escopo não presumida (contar componente de kit como "vendido" é uma escolha que depende de como vocês querem apresentar o relatório).
4. **`getSalesReport` filtra em memória**, não escala pra volume grande — funciona bem agora, marcado como melhoria futura.
5. Esse é o **último módulo da lista original do `CLAUDE.md`** — depois desse, o projeto cobre os 9 domínios planejados desde o início.
