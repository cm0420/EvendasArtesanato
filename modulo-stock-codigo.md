# Módulo `stock` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.stock`
> Referencia `Piece` de `catalog` (import cruzado, esperado — já documentado no `CLAUDE.md`).
> Este é o módulo mais crítico do sistema: protege contra dois clientes comprando a mesma peça ao mesmo tempo.
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas (já fechadas no `CLAUDE.md`, seção "Concorrência")

- **Lock pessimista** (`SELECT ... FOR UPDATE`) na linha da peça durante a reserva — não lock otimista com `@Version`.
- **Decremento imediato, não separação "disponível vs. reservado"**: ao reservar, o `stock` da `Piece` (fonte única de verdade, já mora em `catalog`) é decrementado na hora. Isso simplifica o modelo — não existe um segundo campo "estoque disponível" calculado, é sempre `piece.stock` direto.
- **Reserva com expiração**: cada reserva vira um registro (`StockReservation`) com `expiresAt`. Um job assíncrono (`@Scheduled`) libera reservas expiradas, devolvendo a quantidade ao `stock` da peça.
- **Sem FK pra `order` ainda** — o campo `orderId` é um `UUID` solto (sem `@ManyToOne`/`@JoinColumn`), porque o módulo `order` ainda não existe. Quando ele for implementado, dá pra decidir se vira FK física (seguindo o mesmo padrão que fizemos entre `Customer` e `User`) ou continua desacoplado — fica como decisão em aberto, não presumida aqui.
- **Endpoints protegidos por `ADMIN`** por enquanto — o fluxo real (cliente reservando estoque no checkout) só existe quando `order` for implementado e chamar o `StockService` internamente. Os endpoints REST daqui servem pra teste manual agora; quando `order` existir, provavelmente esses endpoints somem ou viram uso interno apenas.
- **Janela de expiração configurável** via `application.yml` (`app.stock.reservation-expiration-minutes`), não hardcoded — facilita ajustar sem recompilar.

---

## 1. Entity `StockReservation`

`stock/entity/StockReservation.java`

```java
package com.TadeuArtesanatos.demo.stock.entity;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "piece_id", nullable = false)
    private Piece piece;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * Sem FK física — o módulo `order` ainda não existe.
     * Vira referência lógica por enquanto (ver nota no topo do arquivo).
     */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ---- Regras de negócio da entidade ----

    public boolean isExpired() {
        return this.status == ReservationStatus.ACTIVE && this.expiresAt.isBefore(LocalDateTime.now());
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
    }

    public void release() {
        this.status = ReservationStatus.RELEASED;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }
}
```

`stock/entity/ReservationStatus.java`

```java
package com.TadeuArtesanatos.demo.stock.entity;

public enum ReservationStatus {
    ACTIVE,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
```

**Nota sobre os 4 status:** `ACTIVE` (reserva em andamento, dentro da janela de expiração), `CONFIRMED` (pagamento aprovado, estoque decrementado em definitivo — a reserva "virou venda"), `RELEASED` (cancelada manualmente antes de expirar, ex: cliente desistiu no checkout), `EXPIRED` (o job assíncrono encontrou e liberou automaticamente). `CONFIRMED` e `RELEASED`/`EXPIRED` são estados finais — só `ACTIVE` pode transicionar.

---

## 2. Alteração necessária no `PieceRepository` (módulo `catalog`)

Precisa adicionar um método com lock pessimista — isso é o cross-import esperado, documentado no `CLAUDE.md`. Edita o arquivo existente, **não** cria um novo:

`catalog/repository/PieceRepository.java` (adicionar este método à interface já existente)

```java
package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    Page<Piece> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Piece> findAllByCategories_IdAndDeletedAtIsNull(UUID categoryId, Pageable pageable);

    /**
     * Lock pessimista (SELECT ... FOR UPDATE) — usado pelo módulo `stock` durante
     * a reserva/liberação de estoque. A transação que chamar isso bloqueia a linha
     * até fazer commit; um segundo request concorrente espera até a primeira terminar.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Piece p WHERE p.id = :id")
    Optional<Piece> findByIdForUpdate(@Param("id") UUID id);
}
```

**Importante:** o método com `@Lock` só bloqueia de verdade se for chamado **dentro de uma transação ativa** (`@Transactional` no Service que o chama). Fora de transação, o Hibernate ignora o lock silenciosamente — é um erro fácil de cometer sem perceber.

---

## 3. Repository

`stock/repository/StockReservationRepository.java`

```java
package com.TadeuArtesanatos.demo.stock.repository;

import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findAllByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime now);

    List<StockReservation> findAllByOrderId(UUID orderId);
}
```

---

## 4. DTOs

`stock/dto/ReserveStockRequestDto.java`

```java
package com.TadeuArtesanatos.demo.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveStockRequestDto(

        @NotNull
        UUID pieceId,

        @NotNull
        @Min(1)
        Integer quantity,

        @NotNull
        UUID orderId
) {}
```

`stock/dto/StockReservationResponseDto.java`

```java
package com.TadeuArtesanatos.demo.stock.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StockReservationResponseDto(
        UUID id,
        UUID pieceId,
        Integer quantity,
        UUID orderId,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {}
```

---

## 5. Mapper (MapStruct)

`stock/mapper/StockReservationMapper.java`

```java
package com.TadeuArtesanatos.demo.stock.mapper;

import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface StockReservationMapper {

    @Mapping(target = "pieceId", source = "piece.id")
    StockReservationResponseDto toResponseDto(StockReservation reservation);
}
```

---

## 6. Service

`stock/service/StockService.java`

```java
package com.TadeuArtesanatos.demo.stock.service;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.mapper.StockReservationMapper;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockReservationRepository reservationRepository;
    private final PieceRepository pieceRepository;
    private final StockReservationMapper reservationMapper;

    @Value("${app.stock.reservation-expiration-minutes:20}")
    private long reservationExpirationMinutes;

    /**
     * Reserva estoque de uma peça, com lock pessimista.
     * Decrementa o stock da peça imediatamente — a reserva é o que permite
     * devolver essa quantidade depois, se expirar ou for cancelada.
     */
    @Transactional
    public StockReservationResponseDto reserve(ReserveStockRequestDto dto) {
        Piece piece = pieceRepository.findByIdForUpdate(dto.pieceId())
                .filter(Piece::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

        if (piece.getStock() < dto.quantity()) {
            throw new InvalidStateException("Peça esgotada — estoque insuficiente para a quantidade solicitada");
        }

        piece.setStock(piece.getStock() - dto.quantity());

        StockReservation reservation = StockReservation.builder()
                .piece(piece)
                .quantity(dto.quantity())
                .orderId(dto.orderId())
                .status(ReservationStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(reservationExpirationMinutes))
                .build();

        return reservationMapper.toResponseDto(reservationRepository.save(reservation));
    }

    /**
     * Confirma a reserva (pagamento aprovado) — estoque já decrementado
     * permanece decrementado, só muda o status. Vai ser chamado pelo módulo
     * `order`/`payment` quando existir.
     */
    @Transactional
    public StockReservationResponseDto confirm(UUID reservationId) {
        StockReservation reservation = getActiveOrThrow(reservationId);
        reservation.confirm();
        return reservationMapper.toResponseDto(reservation);
    }

    /**
     * Libera a reserva manualmente (cliente cancelou antes de expirar) —
     * devolve a quantidade ao stock da peça, com lock pessimista.
     */
    @Transactional
    public StockReservationResponseDto release(UUID reservationId) {
        StockReservation reservation = getActiveOrThrow(reservationId);
        returnStockWithLock(reservation);
        reservation.release();
        return reservationMapper.toResponseDto(reservation);
    }

    @Transactional(readOnly = true)
    public StockReservationResponseDto findById(UUID id) {
        StockReservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reserva não encontrada"));
        return reservationMapper.toResponseDto(reservation);
    }

    /**
     * Chamado pelo job assíncrono (StockReservationCleanupJob) — varre reservas
     * ACTIVE cujo prazo já passou, devolve o estoque e marca como EXPIRED.
     */
    @Transactional
    public void releaseExpiredReservations() {
        List<StockReservation> expired = reservationRepository
                .findAllByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, LocalDateTime.now());

        for (StockReservation reservation : expired) {
            returnStockWithLock(reservation);
            reservation.expire();
        }
    }

    // ---- helpers internos ----

    private StockReservation getActiveOrThrow(UUID reservationId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reserva não encontrada"));

        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new InvalidStateException("Reserva não está mais ativa (status atual: " + reservation.getStatus() + ")");
        }

        return reservation;
    }

    private void returnStockWithLock(StockReservation reservation) {
        Piece piece = pieceRepository.findByIdForUpdate(reservation.getPiece().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

        piece.setStock(piece.getStock() + reservation.getQuantity());
    }
}
```

**Nota crítica sobre o lock:** tanto `reserve()` quanto `returnStockWithLock()` chamam `findByIdForUpdate`, que trava a linha da peça até a transação terminar. Se dois clientes tentarem reservar a mesma peça ao mesmo tempo, o segundo **espera** o primeiro terminar (commit ou rollback) antes de conseguir ler a linha — é exatamente o comportamento que impede duas reservas de "verem" o mesmo estoque disponível e ambas passarem.

---

## 7. Job assíncrono — liberação de reservas expiradas

`stock/job/StockReservationCleanupJob.java`

```java
package com.TadeuArtesanatos.demo.stock.job;

import com.TadeuArtesanatos.demo.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockReservationCleanupJob {

    private final StockService stockService;

    /**
     * Roda a cada 2 minutos (120_000 ms) — libera reservas ACTIVE cujo
     * prazo já passou, devolvendo a quantidade ao stock da peça.
     */
    @Scheduled(fixedRate = 120_000)
    public void releaseExpiredReservations() {
        stockService.releaseExpiredReservations();
    }
}
```

**⚠️ Necessário: habilitar `@EnableScheduling`** — sem isso, o `@Scheduled` acima é só uma anotação inerte, o Spring nunca chama o método sozinho. Adiciona na classe principal:

`DemoApplication.java` (editar o arquivo existente, adicionar a anotação)

```java
package com.TadeuArtesanatos.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

---

## 8. Controller

`stock/controller/StockController.java`

> Endpoints pra teste manual por enquanto — quando `order` existir, o fluxo real chama `StockService` internamente (o cliente não reserva estoque "na mão" via API pública).

```java
package com.TadeuArtesanatos.demo.stock.controller;

import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/stock/reservations")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> reserve(@Valid @RequestBody ReserveStockRequestDto dto) {
        StockReservationResponseDto created = stockService.reserve(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.findById(id));
    }

    @PostMapping(value = "/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> confirm(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.confirm(id));
    }

    @PostMapping(value = "/{id}/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> release(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.release(id));
    }
}
```

**Regra a adicionar no `SecurityConfig`** (junto dos blocos já existentes):

```java
// stock — endpoints de teste manual, ADMIN só por enquanto (fluxo real vem com order)
.requestMatchers("/api/stock/**").hasRole("ADMIN")
```

---

## 9. Configuração — janela de expiração por profile

Adicionar em `application-dev.yml` (e opcionalmente nos outros profiles, com valores diferentes se fizer sentido — em dev pode valer usar um tempo bem curto tipo 1-2 min pra testar o job sem esperar 20 minutos de verdade):

```yaml
app:
  stock:
    reservation-expiration-minutes: 2  # curto em dev, só pra testar o job sem esperar muito
```

Em produção, o valor real (15-30 min, conforme já fechado no `CLAUDE.md`) fica como default no `@Value` (`20`) ou explícito no `application-prod.yml`.

---

## 10. Migration

`db/migration/V11__create_table_stock_reservations.sql`

```sql
CREATE TABLE stock_reservations (
                                    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    piece_id      UUID NOT NULL REFERENCES pieces(id),
                                    quantity      INTEGER NOT NULL,
                                    order_id      UUID NOT NULL,
                                    status        VARCHAR(20) NOT NULL,
                                    expires_at    TIMESTAMP NOT NULL,
                                    created_at    TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_reservations_piece_id ON stock_reservations (piece_id);
CREATE INDEX idx_stock_reservations_status_expires ON stock_reservations (status, expires_at);
```

**Nota sobre o índice composto `(status, expires_at)`:** é exatamente a combinação que o job assíncrono consulta a cada 2 minutos (`findAllByStatusAndExpiresAtBefore`) — sem esse índice, a query faria full scan na tabela conforme ela crescer.

---

## Como testar o lock pessimista de verdade (concorrência)

Testar via Postman sozinho **não prova** que o lock funciona — duas requisições sequenciais nunca colidem de verdade. Pra validar concorrência real, precisa de duas threads/conexões simultâneas tentando reservar a mesma peça ao mesmo tempo — é exatamente o motivo do `CLAUDE.md` já prever "teste de integração disparando múltiplas threads simultâneas comprando a mesma peça". Isso é candidato natural pra quando os testes automatizados (ainda pendentes, mesma situação de `security` e `catalog`) forem retomados.

Um teste manual "fraco" mas ainda útil no Postman: cria uma peça com `stock: 1`, reserva uma vez (deve dar 201), tenta reservar de novo (deve dar `409 Conflict` — "Peça esgotada", via `InvalidStateException`, já coberta pelo `GlobalExceptionHandler` atualizado).

---

## Pendências pra rodar isso de verdade

1. ✅ **`@EnableScheduling` na `DemoApplication`** — edição de uma linha no arquivo já existente:
   ```java
   import org.springframework.scheduling.annotation.EnableScheduling;

   @SpringBootApplication
   @EnableScheduling
   public class DemoApplication {
   ```
   Sem isso, o job (`StockReservationCleanupJob`) nunca roda sozinho.

2. ✅ **`findByIdForUpdate` no `PieceRepository`** — já sincronizado no `.md` oficial do `catalog` (`modulo-catalog-codigo.md`). O arquivo `catalog/repository/PieceRepository.java` já nasce com o método, não precisa editar em dois lugares.

3. 🔲 **Rodar a migration `V11`** contra o Postgres — ação de execução, não é código a gerar. Sobe a aplicação depois de criar o arquivo `V11__create_table_stock_reservations.sql`, o Flyway aplica sozinho.

4. ✅ **Regra `/api/stock/**` no `SecurityConfig`** — já sincronizada no `.md` oficial do `security` (`modulo-security-codigo.md`), junto com a regra de `catalog` que também estava faltando lá:
   ```java
   // stock — endpoints de teste manual, ADMIN só por enquanto (fluxo real vem com order)
   .requestMatchers("/api/stock/**").hasRole("ADMIN")
   ```

5. ✅ **FK do `orderId` — decisão fechada, mesmo sem `order` existir ainda.** `orderId` continua como `UUID` solto no `stock`, **sem** `@ManyToOne`/`@JoinColumn` — o `stock` nunca importa a classe `Order`. Motivo: o fluxo real vai ser `order` chamando `StockService.reserve(...)` (order depende de stock); se `stock` também importasse `Order` pra montar o FK, criaria dependência circular entre os módulos. A constraint de FK física no banco (`ALTER TABLE stock_reservations ADD CONSTRAINT ... FOREIGN KEY (order_id) REFERENCES orders(id)`) **não pertence a este módulo** — só pode existir depois que a tabela `orders` for criada, então essa migration vai nascer junto do módulo `order`, não aqui. O `stock` não ganha nenhuma migration nova por causa disso.

6. ✅ **`GlobalExceptionHandler` com a hierarquia `BusinessException`** — já reescrito em `modulo-common-exceptions.md`, com `InvalidStateException` (409) cobrindo o "Peça esgotada" usado neste módulo. Precisa estar aplicado no código **antes** de rodar o `stock`, senão `InvalidStateException` cai no handler genérico de `Exception` (500 em vez de 409).