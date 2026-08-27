# Módulo `shipping` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.shipping`
> Depende de `order` (referencia `Order`) — precisa de **edições** em `order` e `payment` já existentes,
> não só arquivos novos. Marcado claramente abaixo.
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas

- **Frete é escolhido depois do checkout**, endpoint separado (mesmo padrão do `payment`) — cliente vê cotações, escolhe uma, só depois paga.
- **Múltiplas opções, tipo PAC/SEDEX** — o fake devolve `STANDARD` (mais barato, mais lento) e `EXPRESS` (mais caro, mais rápido), simulando a variedade real de transportadora.
- **Escolher frete atualiza o total do pedido** — `Order.totalAmount` passa a ser `soma dos itens + custo do frete`. Isso significa que `payment` precisa cobrar o valor certo (com frete incluído), então **pagamento exige frete já selecionado** — não dá pra pagar um pedido sem frete escolhido, senão cobraria errado.
- **Cotação (`GET`) não persiste nada** — só calcula e mostra. Só a seleção (`POST`) grava no pedido. Evita que "só olhar o preço do frete" tenha efeito colateral.
- **Frete só pode ser escolhido/trocado enquanto `AGUARDANDO_PAGAMENTO`** — depois de pago, mudar frete não faz sentido (mudaria o valor já cobrado).
- **Fake é declaradamente arbitrário** — não simula distância real nem peso (peça não tem campo de peso/dimensão ainda). Fórmula simples e documentada como tal, não como algo realista.

---

## ⚠️ Edições necessárias em `order` (não são arquivos novos)

### `order/entity/Order.java` — adicionar campos e método

Adicionar estes campos (junto dos outros campos de snapshot já existentes):

```java
@Column(name = "shipping_cost", precision = 10, scale = 2)
private BigDecimal shippingCost;

@Column(name = "shipping_option_code")
private String shippingOptionCode;

@Column(name = "shipping_option_name")
private String shippingOptionName;

@Column(name = "estimated_delivery_days")
private Integer estimatedDeliveryDays;
```

Adicionar este método (junto dos outros métodos de regra de negócio):

```java
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
```

E **atualizar** `recalculateTotal()` já existente, pra somar o frete:

```java
public void recalculateTotal() {
    BigDecimal itemsTotal = items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal shipping = (shippingCost != null) ? shippingCost : BigDecimal.ZERO;

    this.totalAmount = itemsTotal.add(shipping);
}
```

### `order/dto/OrderResponseDto.java` — adicionar campos

```java
public record OrderResponseDto(
        UUID id,
        String status,
        List<OrderItemResponseDto> items,
        BigDecimal totalAmount,
        String recipientName,
        String shippingCity,
        String shippingState,
        BigDecimal shippingCost,
        String shippingOptionName,
        Integer estimatedDeliveryDays,
        String trackingCode,
        LocalDateTime checkoutStartedAt,
        String refundReason,
        LocalDateTime refundRequestedAt,
        LocalDateTime refundDecidedAt,
        LocalDateTime createdAt
) {}
```

`OrderMapper` não precisa de `@Mapping` extra pra esses campos — nome e tipo já batem entre entity e DTO, o MapStruct resolve sozinho.

---

## ⚠️ Edição necessária em `payment`

`payment/service/PaymentService.java` — dentro de `initiatePayment`, adicionar a checagem logo depois de confirmar `AGUARDANDO_PAGAMENTO`:

```java
if (order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
    throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
}

if (!order.hasShippingSelected()) {
    throw new InvalidStateException("Selecione o frete antes de pagar");
}
```

---

## 1. `ShippingProvider` (interface, DIP)

`shipping/gateway/ShippingProvider.java`

```java
package com.TadeuArtesanatos.demo.shipping.gateway;

import com.TadeuArtesanatos.demo.order.entity.Order;

import java.util.List;

public interface ShippingProvider {

    List<ShippingQuote> quote(Order order);

    record ShippingQuote(String code, String name, java.math.BigDecimal price, Integer estimatedDeliveryDays) {}
}
```

---

## 2. `FakeShippingProvider`

`shipping/gateway/fake/FakeShippingProvider.java`

```java
package com.TadeuArtesanatos.demo.shipping.gateway.fake;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.shipping.gateway.ShippingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.shipping.provider", havingValue = "fake")
public class FakeShippingProvider implements ShippingProvider {

    // Fórmula deliberadamente arbitrária — não simula Correios de verdade.
    // Não existe campo de peso/dimensão na Piece ainda; base pela quantidade de itens.
    private static final BigDecimal BASE_FEE = BigDecimal.valueOf(15.00);
    private static final BigDecimal PER_ITEM_FEE = BigDecimal.valueOf(2.50);
    private static final BigDecimal EXPRESS_MULTIPLIER = BigDecimal.valueOf(1.8);

    @Override
    public List<ShippingQuote> quote(Order order) {
        int totalItems = order.getItems().stream().mapToInt(item -> item.getQuantity()).sum();

        BigDecimal baseCost = BASE_FEE.add(PER_ITEM_FEE.multiply(BigDecimal.valueOf(totalItems)))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal expressCost = baseCost.multiply(EXPRESS_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

        return List.of(
                new ShippingQuote("STANDARD", "Entrega padrão", baseCost, 7),
                new ShippingQuote("EXPRESS", "Entrega expressa", expressCost, 3)
        );
    }
}
```

**Nota sobre `app.shipping.provider`:** essa property já existe no `application.yml` desde o Marco 1 (`app.shipping.provider: fake`), mesmo padrão do `payment` — quando um agregador real (Melhor Envio, Correios direto) for integrado, é só uma classe nova `@Component` com `@ConditionalOnProperty(havingValue = "melhorenvio")`, sem tocar em `ShippingService`.

---

## 3. DTOs

`shipping/dto/ShippingQuoteResponseDto.java`

```java
package com.TadeuArtesanatos.demo.shipping.dto;

import java.math.BigDecimal;

public record ShippingQuoteResponseDto(
        String code,
        String name,
        BigDecimal price,
        Integer estimatedDeliveryDays
) {}
```

`shipping/dto/SelectShippingRequestDto.java`

```java
package com.TadeuArtesanatos.demo.shipping.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectShippingRequestDto(

        @NotBlank
        String code
) {}
```

---

## 4. Service

`shipping/service/ShippingService.java`

```java
package com.TadeuArtesanatos.demo.shipping.service;

import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.shipping.dto.ShippingQuoteResponseDto;
import com.TadeuArtesanatos.demo.shipping.gateway.ShippingProvider;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShippingService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ShippingProvider shippingProvider;
    private final OrderMapper orderMapper;

    @Transactional(readOnly = true)
    public List<ShippingQuoteResponseDto> getQuotes(User authenticatedUser, UUID orderId) {
        Order order = getOwnOrderAwaitingPaymentOrThrow(authenticatedUser, orderId);

        return shippingProvider.quote(order).stream()
                .map(q -> new ShippingQuoteResponseDto(q.code(), q.name(), q.price(), q.estimatedDeliveryDays()))
                .toList();
    }

    @Transactional
    public OrderResponseDto selectShipping(User authenticatedUser, UUID orderId, String code) {
        Order order = getOwnOrderAwaitingPaymentOrThrow(authenticatedUser, orderId);

        ShippingProvider.ShippingQuote selected = shippingProvider.quote(order).stream()
                .filter(q -> q.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Opção de frete inválida: " + code));

        // Recalcula o preço de novo (não confia no que o cliente mandou) —
        // a cotação é sempre recomputada aqui, o `code` só seleciona qual das opções.
        order.applyShipping(selected.code(), selected.name(), selected.price(), selected.estimatedDeliveryDays());

        return orderMapper.toResponseDto(order);
    }

    private Order getOwnOrderAwaitingPaymentOrThrow(User authenticatedUser, UUID orderId) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Frete só pode ser consultado/alterado enquanto o pedido aguarda pagamento (status atual: " + order.getStatus() + ")");
        }

        return order;
    }
}
```

**Nota sobre recalcular o preço no `selectShipping`:** de propósito, não uso o `price` que viria do cliente (nem existe esse campo no `SelectShippingRequestDto` — só `code`). Isso evita o cliente manipular o valor do frete direto no request; o preço sempre vem de uma nova chamada ao `ShippingProvider`, a fonte de verdade.

---

## 5. Controller

`shipping/controller/ShippingController.java`

```java
package com.TadeuArtesanatos.demo.shipping.controller;

import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.shipping.dto.SelectShippingRequestDto;
import com.TadeuArtesanatos.demo.shipping.dto.ShippingQuoteResponseDto;
import com.TadeuArtesanatos.demo.shipping.service.ShippingService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders/me/{orderId}/shipping")
@RequiredArgsConstructor
public class ShippingController {

    private final ShippingService shippingService;

    @GetMapping(value = "/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ShippingQuoteResponseDto>> getQuotes(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(shippingService.getQuotes(user, orderId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponseDto> selectShipping(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @Valid @RequestBody SelectShippingRequestDto dto
    ) {
        return ResponseEntity.ok(shippingService.selectShipping(user, orderId, dto.code()));
    }
}
```

**`SecurityConfig`:** nenhuma regra nova necessária — `/api/orders/me/{orderId}/shipping/**` já cai na regra `"/api/orders/me/**".authenticated()` que existe desde o módulo `order`.

---

## 6. Migration

`db/migration/V16__add_shipping_columns_to_orders.sql`

```sql
ALTER TABLE orders
ADD COLUMN shipping_cost NUMERIC(10,2),
ADD COLUMN shipping_option_code VARCHAR(50),
ADD COLUMN shipping_option_name VARCHAR(100),
ADD COLUMN estimated_delivery_days INTEGER;
```

---

## 7. Teste via curl

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"

echo "== 1. Login e montar pedido até AGUARDANDO_PAGAMENTO =="
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "miguel@teste.com", "password": "senha12345"}')
CUSTOMER_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')

ADMIN_LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "joao@teste.com", "password": "senha12345"}')
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN_RESPONSE" | jq -r '.accessToken')

PIECE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Peça teste shipping", "description": "teste", "price": 50.00, "stock": 5, "imageUrls": [], "categoryIds": []}')
PIECE_ID=$(echo "$PIECE_RESPONSE" | jq -r '.id')

ADDRESS_RESPONSE=$(curl -s -X POST "$BASE_URL/api/customers/me/addresses" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"label": "Casa", "recipientName": "Miguel Costa", "recipientPhone": "(11) 91234-5678", "zipCode": "01310100", "street": "Av Paulista", "number": "1000", "neighborhood": "Bela Vista", "city": "São Paulo", "state": "SP", "isDefault": true}')
ADDRESS_ID=$(echo "$ADDRESS_RESPONSE" | jq -r '.id')

curl -s -X POST "$BASE_URL/api/orders/me" -H "Authorization: Bearer $CUSTOMER_TOKEN" > /dev/null
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 2}" > /dev/null

CHECKOUT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}")
ORDER_ID=$(echo "$CHECKOUT_RESPONSE" | jq -r '.id')
echo "Pedido: $ORDER_ID — totalAmount antes do frete: $(echo "$CHECKOUT_RESPONSE" | jq -r '.totalAmount')"
echo "Esperado: 100.00 (2 x 50.00, sem frete ainda)"

echo ""
echo "== 2. Tentar pagar SEM escolher frete — deve dar 409 =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "APPROVED"}' -w "\nStatus HTTP: %{http_code}\n"
echo "Esperado: 409, mensagem 'Selecione o frete antes de pagar'"

echo ""
echo "== 3. Consultar cotações de frete =="
QUOTES_RESPONSE=$(curl -s "$BASE_URL/api/orders/me/$ORDER_ID/shipping/quotes" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN")
echo "$QUOTES_RESPONSE" | jq
echo "Esperado: 2 opções — STANDARD e EXPRESS"

echo ""
echo "== 4. Conferir que só consultar NÃO mudou o pedido =="
curl -s "$BASE_URL/api/orders/me/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq '{totalAmount, shippingCost, shippingOptionName}'
echo "Esperado: shippingCost ainda null, totalAmount ainda 100.00"

echo ""
echo "== 5. Selecionar frete EXPRESS =="
SELECT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/shipping" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"code": "EXPRESS"}')
echo "$SELECT_RESPONSE" | jq '{totalAmount, shippingCost, shippingOptionName, estimatedDeliveryDays}'
echo "Esperado: shippingCost preenchido, totalAmount = 100.00 + custo do frete"

echo ""
echo "== 6. Pagar agora, com frete selecionado — deve funcionar =="
PAY_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "APPROVED"}')
echo "$PAY_RESPONSE" | jq '{status, amount}'
echo "Esperado: status = APPROVED, amount = totalAmount COM frete (não só os itens)"

curl -s "$BASE_URL/api/orders/me/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq '{status, totalAmount}'
echo "Esperado: status = PAGO"

echo ""
echo "== 7. Tentar trocar o frete depois de pago — deve dar 409 =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/shipping" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"code": "STANDARD"}' -w "\nStatus HTTP: %{http_code}\n"
echo "Esperado: 409 — não pode mudar frete de pedido já pago"

echo ""
echo "== Teste completo =="
```

### O que cada bloco valida

| Passo | O que confirma |
|---|---|
| 1 | Pedido chega em `AGUARDANDO_PAGAMENTO` sem frete |
| 2 | Pagamento **bloqueado** sem frete selecionado (edição no `payment`) |
| 3–4 | Cotação não tem efeito colateral (só consulta) |
| 5 | Seleção grava no pedido e **recalcula o total** |
| 6 | Pagamento cobra o valor certo, **com frete incluído** |
| 7 | Frete trava depois de pago |

---

## Pendências e decisões em aberto

1. **Edições em `order` e `payment` precisam ser aplicadas antes de testar** — não são arquivos novos, são mudanças em código já existente (marcado bem claro no topo do arquivo).
2. Numeração de migration — assumi `V16` partindo do `V15` (payments).
3. **Fórmula do fake é arbitrária** — não usa peso/dimensão real (a `Piece` não tem esses campos). Se algum dia quiser simular frete mais realista, precisaria adicionar `weight`/`dimensions` na `Piece` (módulo `catalog`) — não fiz isso agora pra não mexer em outro módulo sem necessidade clara.
4. `admin` continua o único módulo pendente da lista original do `CLAUDE.md`.
