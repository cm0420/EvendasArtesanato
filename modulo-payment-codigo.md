# Módulo `payment` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.payment`
> Depende de `order` (referencia `Order`, chama `OrderService.markAsPaid`) — `order` nunca importa `payment` de volta.
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas

- **Checkout não dispara pagamento.** `POST /api/orders/me/checkout` continua só reservando estoque. Pagar é uma ação separada, do cliente, depois do checkout — bate com a realidade de qualquer loja online (você finaliza o carrinho, depois escolhe/preenche a forma de pagamento).
- **Recusa (`DECLINED`) não cancela o pedido.** Fica em `AGUARDANDO_PAGAMENTO`, cliente pode tentar de novo (`POST` de pagamento de novo, cria uma nova tentativa). O `OrderExpirationJob` que já existe cobre o risco de abandono — se ninguém pagar dentro do prazo, expira e libera o estoque sozinho. Cancelar na primeira recusa não protegeria nada a mais, só pioraria a experiência.
- **Gateway fake determinístico.** Campo `simulateOutcome` no request (`APPROVED`/`DECLINED`/`PENDING`), default `APPROVED` se omitido — permite testar os três caminhos via `curl` sem depender de sorte.
- **`PaymentInitiator` separado de `PaymentWebhookHandler`** (ISP, já sugerido no `CLAUDE.md`). O fake resolve tudo "síncrono" (sem gateway de verdade, não tem round-trip real), mas a separação de interface já deixa o código pronto pro dia que um gateway real chamar o webhook de verdade, de forma assíncrona — **zero mudança no `PaymentWebhookHandlerImpl`** quando isso acontecer, só troca o `PaymentInitiator`.
- **Idempotência via `transactionId` único** — a mesma regra que o `CLAUDE.md` já pedia desde o início: dedup pelo id da transação do gateway, não pelo pedido.
- **`OrderService.markAsPaid` não muda nada** — o webhook handler chama exatamente o mesmo método que o endpoint `ADMIN` manual já chamava. Os dois caminhos (automático via gateway, manual via admin — ex: pagamento em dinheiro na entrega) coexistem, sem duplicar lógica.
- **Múltiplas tentativas de pagamento por pedido são permitidas e guardadas** — cada `POST` de pagamento cria um novo registro `Payment`, nunca sobrescreve o anterior. Isso dá trilha de auditoria (mesmo espírito do fluxo de estorno).
- **Webhook público (`permitAll`)** — gateways de pagamento reais não têm como autenticar com o nosso JWT. Isso é seguro **só** quando existir verificação de assinatura (o gateway assina o payload, a gente confere antes de confiar) — **não implementado aqui**, fica marcado como pendência real de segurança pra quando um gateway de verdade entrar.

---

## 1. Enum `PaymentStatus`

`payment/entity/PaymentStatus.java`

```java
package com.TadeuArtesanatos.demo.payment.entity;

public enum PaymentStatus {
    PENDING,
    APPROVED,
    DECLINED
}
```

---

## 2. Entity `Payment`

`payment/entity/Payment.java`

```java
package com.TadeuArtesanatos.demo.payment.entity;

import com.TadeuArtesanatos.demo.order.entity.Order;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio ----

    public boolean isResolved() {
        return this.status != PaymentStatus.PENDING;
    }

    public void approve() {
        this.status = PaymentStatus.APPROVED;
    }

    public void decline() {
        this.status = PaymentStatus.DECLINED;
    }
}
```

---

## 3. Repository

`payment/repository/PaymentRepository.java`

```java
package com.TadeuArtesanatos.demo.payment.repository;

import com.TadeuArtesanatos.demo.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findAllByOrderId(UUID orderId);
}
```

---

## 4. Interfaces do gateway (DIP + ISP)

`payment/gateway/PaymentInitiator.java`

```java
package com.TadeuArtesanatos.demo.payment.gateway;

import com.TadeuArtesanatos.demo.order.entity.Order;

public interface PaymentInitiator {

    /**
     * Inicia uma cobrança. Num gateway real, isso normalmente devolve uma
     * URL de checkout ou client secret — o resultado final chega depois,
     * via webhook. No fake, já devolve o resultado calculado.
     */
    PaymentChargeResult charge(Order order, String simulateOutcome);

    record PaymentChargeResult(String transactionId, String resultingStatus) {}
}
```

`payment/gateway/PaymentWebhookPayload.java`

```java
package com.TadeuArtesanatos.demo.payment.gateway;

public record PaymentWebhookPayload(String transactionId, String status) {}
```

`payment/gateway/PaymentWebhookHandler.java`

```java
package com.TadeuArtesanatos.demo.payment.gateway;

public interface PaymentWebhookHandler {

    /**
     * Processa o retorno do gateway (real: assíncrono, via HTTP; fake: chamado
     * direto pelo PaymentService logo após o charge()). Idempotente — chamar
     * duas vezes com o mesmo transactionId não duplica efeito nenhum.
     */
    void handle(PaymentWebhookPayload payload);
}
```

---

## 5. `FakePaymentGateway`

`payment/gateway/fake/FakePaymentGateway.java`

```java
package com.TadeuArtesanatos.demo.payment.gateway.fake;

import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentInitiator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "fake")
public class FakePaymentGateway implements PaymentInitiator {

    private static final java.util.Set<String> VALID_OUTCOMES =
            java.util.Set.of("APPROVED", "DECLINED", "PENDING");

    @Override
    public PaymentChargeResult charge(Order order, String simulateOutcome) {
        String outcome = (simulateOutcome != null) ? simulateOutcome.toUpperCase() : "APPROVED";

        if (!VALID_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("simulateOutcome inválido — use APPROVED, DECLINED ou PENDING");
        }

        String transactionId = "fake_txn_" + UUID.randomUUID();
        return new PaymentChargeResult(transactionId, outcome);
    }
}
```

**Nota sobre `@ConditionalOnProperty`:** essa property (`app.payment.provider: fake`) já existe no `application.yml` desde o Marco 1, criada exatamente pra esse propósito — não é novidade. Quando um gateway real for integrado, basta uma nova classe `@Component` com `@ConditionalOnProperty(havingValue = "mercadopago")` e trocar o valor da property no profile de produção. `PaymentService` e `PaymentWebhookHandlerImpl` não mudam nada.

---

## 6. DTOs

`payment/dto/InitiatePaymentRequestDto.java`

```java
package com.TadeuArtesanatos.demo.payment.dto;

public record InitiatePaymentRequestDto(
        String simulateOutcome
) {}
```

Sem `@NotBlank` de propósito — é opcional, `null` vira `APPROVED` (default) dentro do `FakePaymentGateway`.

`payment/dto/PaymentResponseDto.java`

```java
package com.TadeuArtesanatos.demo.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentResponseDto(
        UUID id,
        UUID orderId,
        String transactionId,
        String status,
        BigDecimal amount,
        String provider,
        LocalDateTime createdAt
) {}
```

`payment/dto/FakeWebhookRequestDto.java`

```java
package com.TadeuArtesanatos.demo.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record FakeWebhookRequestDto(

        @NotBlank
        String transactionId,

        @NotBlank
        String status
) {}
```

---

## 7. Mapper

`payment/mapper/PaymentMapper.java`

```java
package com.TadeuArtesanatos.demo.payment.mapper;

import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.entity.Payment;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "orderId", source = "order.id")
    PaymentResponseDto toResponseDto(Payment payment);
}
```

---

## 8. `PaymentWebhookHandlerImpl`

`payment/service/PaymentWebhookHandlerImpl.java`

```java
package com.TadeuArtesanatos.demo.payment.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.payment.entity.Payment;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookHandler;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookPayload;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWebhookHandlerImpl implements PaymentWebhookHandler {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;

    @Override
    @Transactional
    public void handle(PaymentWebhookPayload payload) {
        Payment payment = paymentRepository.findByTransactionId(payload.transactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada: " + payload.transactionId()));

        // Idempotência: se já foi processado antes (APPROVED ou DECLINED),
        // não repete nada — chamar duas vezes com o mesmo transactionId é seguro.
        if (payment.isResolved()) {
            return;
        }

        PaymentStatus novoStatus = PaymentStatus.valueOf(payload.status().toUpperCase());

        if (novoStatus == PaymentStatus.APPROVED) {
            payment.approve();
            orderService.markAsPaid(payment.getOrder().getId());
        } else if (novoStatus == PaymentStatus.DECLINED) {
            payment.decline();
            // Pedido continua AGUARDANDO_PAGAMENTO — decisão fechada: cliente pode tentar de novo.
        }
        // Se vier PENDING de novo, não faz nada — continua aguardando resolução.
    }
}
```

---

## 9. `PaymentService`

`payment/service/PaymentService.java`

```java
package com.TadeuArtesanatos.demo.payment.service;

import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.entity.Payment;
import com.TadeuArtesanatos.demo.payment.entity.PaymentStatus;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentInitiator;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookHandler;
import com.TadeuArtesanatos.demo.payment.gateway.PaymentWebhookPayload;
import com.TadeuArtesanatos.demo.payment.mapper.PaymentMapper;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final PaymentInitiator paymentInitiator;
    private final PaymentWebhookHandler paymentWebhookHandler;
    private final PaymentMapper paymentMapper;

    @Transactional
    public PaymentResponseDto initiatePayment(User authenticatedUser, UUID orderId, String simulateOutcome) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        PaymentInitiator.PaymentChargeResult chargeResult = paymentInitiator.charge(order, simulateOutcome);

        Payment payment = Payment.builder()
                .order(order)
                .transactionId(chargeResult.transactionId())
                .status(PaymentStatus.PENDING)
                .amount(order.getTotalAmount())
                .provider("fake")
                .build();
        paymentRepository.save(payment);

        // Simula o "round-trip" assíncrono do gateway real — aqui, síncrono mesmo,
        // porque não existe rede de verdade envolvida. Um gateway real chamaria
        // o endpoint público /api/payments/webhook/... em vez disso, mas o código
        // que processa o resultado (PaymentWebhookHandlerImpl) é o mesmo.
        paymentWebhookHandler.handle(new PaymentWebhookPayload(chargeResult.transactionId(), chargeResult.resultingStatus()));

        Payment atualizado = paymentRepository.findByTransactionId(chargeResult.transactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Pagamento não encontrado após processamento"));

        return paymentMapper.toResponseDto(atualizado);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponseDto> listOwnOrderPayments(User authenticatedUser, UUID orderId) {
        Customer customer = customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return paymentRepository.findAllByOrderId(order.getId()).stream()
                .map(paymentMapper::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponseDto findByIdAsAdmin(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Pagamento não encontrado"));
        return paymentMapper.toResponseDto(payment);
    }

    /** Chamado pelo endpoint público de webhook — usado por gateways reais no futuro. */
    @Transactional
    public void processWebhook(String transactionId, String status) {
        paymentWebhookHandler.handle(new PaymentWebhookPayload(transactionId, status));
    }
}
```

---

## 10. Controller

`payment/controller/PaymentController.java`

```java
package com.TadeuArtesanatos.demo.payment.controller;

import com.TadeuArtesanatos.demo.payment.dto.FakeWebhookRequestDto;
import com.TadeuArtesanatos.demo.payment.dto.InitiatePaymentRequestDto;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.service.PaymentService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // ---- Cliente autenticado (pagamento do próprio pedido) ----

    @PostMapping(value = "/api/orders/me/{orderId}/payments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponseDto> initiatePayment(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId,
            @RequestBody(required = false) InitiatePaymentRequestDto dto
    ) {
        String simulateOutcome = (dto != null) ? dto.simulateOutcome() : null;
        PaymentResponseDto result = paymentService.initiatePayment(user, orderId, simulateOutcome);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping(value = "/api/orders/me/{orderId}/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PaymentResponseDto>> listOwnOrderPayments(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(paymentService.listOwnOrderPayments(user, orderId));
    }

    // ---- Admin ----

    @GetMapping(value = "/api/payments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponseDto> findByIdAsAdmin(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.findByIdAsAdmin(id));
    }

    // ---- Webhook público — sem autenticação (gateway real não tem nosso JWT) ----

    @PostMapping(value = "/api/payments/webhook/fake", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> fakeWebhook(@Valid @RequestBody FakeWebhookRequestDto dto) {
        paymentService.processWebhook(dto.transactionId(), dto.status());
        return ResponseEntity.noContent().build();
    }
}
```

**⚠️ Pendência de segurança real, não implementada:** `/api/payments/webhook/fake` está `permitAll()` sem verificação nenhuma — qualquer um poderia chamar esse endpoint e forçar um pedido a ser marcado como pago. Isso é **aceitável só enquanto for fake**, pro fluxo de teste. Quando um gateway real (Mercado Pago, Stripe) for integrado, esse endpoint **precisa** validar a assinatura do payload (cada gateway tem seu próprio mecanismo — header `X-Signature`, HMAC, etc.) antes de processar. Marcado aqui pra não ser esquecido.

**Regra a adicionar no `SecurityConfig`:**

```java
// payment — webhook público, resto exige autenticação (rotas /me já cobertas por regra existente)
.requestMatchers("/api/payments/webhook/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/payments/*").hasRole("ADMIN")
```

Repara que **não precisa** adicionar regra pra `/api/orders/me/{orderId}/payments` — já cai na regra `"/api/orders/me/**".authenticated()` que existe desde o módulo `order`.

---

## 11. Migration

`db/migration/V15__create_table_payments.sql`

```sql
CREATE TABLE payments (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id         UUID NOT NULL REFERENCES orders(id),
    transaction_id   VARCHAR(255) NOT NULL UNIQUE,
    status           VARCHAR(20) NOT NULL,
    amount           NUMERIC(10,2) NOT NULL,
    provider         VARCHAR(50) NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
```

---

## 12. Teste via curl

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"

echo "== 1. Login como CLIENTE =="
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "miguel@teste.com", "password": "senha12345"}')
CUSTOMER_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')

echo ""
echo "== 2. Login como ADMIN =="
ADMIN_LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "joao@teste.com", "password": "senha12345"}')
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN_RESPONSE" | jq -r '.accessToken')

echo ""
echo "== 3. Criar peça e montar um pedido até AGUARDANDO_PAGAMENTO =="
PIECE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Peça teste payment", "description": "teste", "price": 100.00, "stock": 5, "imageUrls": [], "categoryIds": []}')
PIECE_ID=$(echo "$PIECE_RESPONSE" | jq -r '.id')

ADDRESS_RESPONSE=$(curl -s -X POST "$BASE_URL/api/customers/me/addresses" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"label": "Casa", "recipientName": "Miguel Costa", "recipientPhone": "(11) 91234-5678", "zipCode": "01310100", "street": "Av Paulista", "number": "1000", "neighborhood": "Bela Vista", "city": "São Paulo", "state": "SP", "isDefault": true}')
ADDRESS_ID=$(echo "$ADDRESS_RESPONSE" | jq -r '.id')

curl -s -X POST "$BASE_URL/api/orders/me" -H "Authorization: Bearer $CUSTOMER_TOKEN" > /dev/null
curl -s -X POST "$BASE_URL/api/orders/me/items" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 1}" > /dev/null

CHECKOUT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/checkout" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"addressId\": \"$ADDRESS_ID\"}")
ORDER_ID=$(echo "$CHECKOUT_RESPONSE" | jq -r '.id')
echo "Pedido: $ORDER_ID (status: $(echo "$CHECKOUT_RESPONSE" | jq -r '.status'))"

echo ""
echo "== 4. Pagar com DECLINED — pedido deve continuar AGUARDANDO_PAGAMENTO =="
PAY_DECLINE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "DECLINED"}')
echo "$PAY_DECLINE_RESPONSE" | jq '{status, transactionId}'
echo "Esperado: status = DECLINED"

curl -s "$BASE_URL/api/orders/me/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq '{status}'
echo "Esperado: status = AGUARDANDO_PAGAMENTO (não cancelou)"

echo ""
echo "== 5. Tentar de novo, dessa vez APROVADO =="
PAY_APPROVE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "APPROVED"}')
echo "$PAY_APPROVE_RESPONSE" | jq '{status, transactionId}'
TRANSACTION_ID=$(echo "$PAY_APPROVE_RESPONSE" | jq -r '.transactionId')
echo "Esperado: status = APPROVED"

curl -s "$BASE_URL/api/orders/me/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq '{status}'
echo "Esperado: status = PAGO (o markAsPaid foi disparado pelo webhook, não pelo admin)"

echo ""
echo "== 6. Conferir histórico de tentativas (DECLINED + APPROVED, duas linhas) =="
curl -s "$BASE_URL/api/orders/me/$ORDER_ID/payments" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq '[.[] | {status, transactionId}]'
echo "Esperado: 2 registros"

echo ""
echo "== 7. Testar idempotência — reenviar o MESMO webhook de aprovação de novo =="
curl -s -X POST "$BASE_URL/api/payments/webhook/fake" \
  -H "Content-Type: application/json" \
  -d "{\"transactionId\": \"$TRANSACTION_ID\", \"status\": \"APPROVED\"}" -w "\nStatus HTTP: %{http_code}\n"
echo "Esperado: 204, sem erro, sem duplicar nada (Payment já estava resolvido)"

echo ""
echo "== 8. Testar simulateOutcome inválido — deve dar 400 =="
curl -s -X POST "$BASE_URL/api/orders/me/$ORDER_ID/payments" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
  -d '{"simulateOutcome": "LIXO_INVALIDO"}' -w "\nStatus HTTP: %{http_code}\n"

echo ""
echo "== Teste completo =="
```

### O que cada bloco valida

| Passo | O que confirma |
|---|---|
| 3 | Pedido chega em `AGUARDANDO_PAGAMENTO` (reaproveitando o `order`) |
| 4 | Recusa não cancela o pedido — decisão fechada, testada de verdade |
| 5 | Aprovação dispara `OrderService.markAsPaid` via webhook, não via admin |
| 6 | Múltiplas tentativas ficam registradas (trilha de auditoria) |
| 7 | **Idempotência real** — reenviar o mesmo webhook não duplica nada |
| 8 | Validação de entrada rejeitando valor inválido |

---

## Pendências e decisões em aberto

1. `ResourceNotFoundException`/`InvalidStateException` da hierarquia `BusinessException` precisam estar aplicadas antes de rodar.
2. Numeração da migration — assumi `V15` partindo do `V14` já existente.
3. **Verificação de assinatura do webhook** — não implementada, marcada como pendência de segurança real pra quando um gateway de verdade for integrado (ver nota no Controller).
4. Regra do `SecurityConfig` pro `/api/payments/**` — adicionar junto das outras.
5. `shipping` continua não implementado — fora do escopo deste módulo.
