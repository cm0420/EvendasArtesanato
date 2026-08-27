# Contexto do projeto — Loja de artesanato (backend)

Este arquivo resume as decisões de arquitetura definidas em sessão de planejamento antes do início da implementação. Leia isso antes de sugerir código ou estrutura.

## Modelo de negócio

- Loja de peças de artesanato pintadas por um único artesão.
- **Estoque real, não sob encomenda**: o artesão produz a peça, fotografa e cadastra no site com estoque já existente. O cliente compra o que já existe fisicamente — não há fluxo de "pedido customizado → produção → entrega".
- Peças podem ser únicas, mas com quantidade > 1 (mais de uma unidade igual).
- Existem **conjuntos** (kits) que agrupam peças, mas cada peça do conjunto também pode ser vendida separadamente. O estoque da peça é a única fonte de verdade — o conjunto não tem estoque próprio, é calculado dinamicamente (mínimo entre as peças componentes).
- Frete calculado automaticamente (Correios via agregador).
- **Conta obrigatória para o cliente final** (sem guest checkout). Decisão fechada pensando em duas coisas: (1) o módulo `security`/JWT já planejado é reaproveitado 100% para autenticação do cliente, sem precisar de um fluxo paralelo de acesso a pedido sem login (ex: link mágico por e-mail); (2) estorno fica mais seguro amarrado a uma identidade autenticada — o pedido tem `cliente_id`, e só o dono do pedido (ou admin) pode solicitar/autorizar estorno, com trilha de auditoria clara.

## Princípio arquitetural central

**Monólito modular** em Spring Boot, organizado por domínio (não por camada técnica no nível raiz). Não usar microsserviços agora — over-engineering para o porte atual. A separação por domínio existe para que, no futuro, um módulo possa ser extraído como serviço independente sem reescrita.

**Revisão em relação à primeira versão desta seção:** os domínios ficam mais granulares — em vez de agrupar peça, conjunto e estoque num único pacote genérico (`catalog`), cada conceito com responsabilidade própria vira seu próprio módulo. Cada domínio segue o mesmo padrão interno de camadas usado em outros projetos do Miguel (incluindo `mapper`, que não estava listado explicitamente antes).

**Revisão 2 — separação de `user` e `security`:** originalmente o módulo `security` acumulava tanto a entidade de usuário (dado de domínio) quanto o mecanismo de autenticação/autorização (JWT, filtros, config). Foi decidido separar em dois módulos, aplicando o mesmo princípio de responsabilidade única já usado entre `catalog` e `stock`:
- `user` — dado de domínio: quem é o usuário (entidade `User`, `Role`, CRUD básico).
- `security` — mecanismo: como autenticar e autorizar (filtro JWT, `SecurityConfig`, `UserDetailsServiceImpl`, endpoints de login).
- Dependência é unidirecional: `security` depende de `user` (usa `User`/`UserRepository` pra autenticar), nunca o contrário.
- Isso evita que outros módulos que só precisam da entidade `User` (como `customer`, via `@OneToOne` físico) tenham que importar toda a infraestrutura de segurança (filtros, config JWT) junto.

### Módulos (pacotes por domínio)

- `user` — usuário do sistema (entidade `User`, enum `Role`), CRUD básico. Base para os demais módulos que precisam referenciar um usuário (ex: `customer`)
- `security` — autenticação e autorização (filtro JWT, `SecurityConfig`, `UserDetailsServiceImpl`, login). Depende de `user`, nunca o contrário
- `customer` — cliente, endereço. Referencia `User` (módulo `user`) via `@OneToOne` físico — decisão fechada priorizando integridade referencial garantida pelo banco sobre desacoplamento entre módulos
- `catalog` — peça, conjunto/kit, categoria
- `stock` — controle e reserva de estoque (estoque disponível, reserva com expiração). Referencia a entidade `Peça` de `catalog` (import cruzado, esperado), mas a responsabilidade de lock pessimista e liberação de reserva fica isolada aqui, separada do CRUD de peça — mantendo o `StockService` com responsabilidade única, como já definido na seção de SOLID. Não referencia `order`: o campo `orderId` é um `UUID` solto (sem `@ManyToOne`/`@JoinColumn`) — decisão fechada para evitar dependência circular, já que o fluxo real é `order` chamando `StockService`, nunca o contrário; a FK física (se fizer sentido) nasce como migration do módulo `order`, não daqui
- `order` — pedido, item de pedido, máquina de estados. **Não existe módulo separado para carrinho**: `CARRINHO` é o primeiro estado do próprio pedido na máquina de estados já definida na seção de concorrência (`CARRINHO → AGUARDANDO_PAGAMENTO → PAGO → ENVIADO → ENTREGUE`) — assumido assim para não duplicar conceito; avisar se a intenção era ter carrinho como entidade/módulo à parte
- `payment` — interface `PaymentGateway`, implementação fake para dev, DTOs
- `shipping` — interface `ShippingProvider`, implementação fake para dev
- `admin` — endpoints e regras específicas de backoffice (combina dados de vários domínios; não tem entidade própria)
- `common` / `shared` — exceções globais, configs, utilitários. Hierarquia própria de exceções de negócio (`BusinessException` abstrata, com `HttpStatus` embutido): `ResourceNotFoundException` (404), `DuplicateResourceException` (409), `InvalidStateException` (409). Um único `@ExceptionHandler(BusinessException.class)` no `GlobalExceptionHandler` cobre qualquer subclasse — módulo novo não precisa criar handler próprio, só lançar a exceção certa. Detalhes em `modulo-common-exceptions.md`

Cada pacote de domínio segue internamente: `entity / mapper / repository / service / controller / dto`.

Exemplo de estrutura resultante:

```
com.TadeuArtesanatos.demo
├── user/
│   ├── entity        (User, Role)
│   ├── repository
│   ├── service
│   ├── controller
│   └── dto
├── security/
│   ├── config         (SecurityConfig)
│   ├── filter          (filtro JWT)
│   ├── service          (UserDetailsServiceImpl, JwtService)
│   └── dto             (login request/response)
├── customer/
│   ├── entity
│   ├── mapper
│   ├── repository
│   ├── service
│   ├── controller
│   └── dto
├── catalog/
│   └── (mesmas camadas)
├── stock/
│   └── (mesmas camadas)
├── order/
│   └── (mesmas camadas)
├── payment/
│   └── (interface, fake impl, dto — sem entity/repository próprios ainda)
├── shipping/
│   └── (interface, fake impl, dto — sem entity/repository próprios ainda)
├── admin/
│   └── (controller, service — sem entity própria)
└── common / shared/
    └── (exceções globais, configs, utils)
```

## Pagamento e frete: abstração desde o início (sem integrar ainda)

Não integrar Mercado Pago/Stripe/Melhor Envio agora. Mas o design já precisa estar pronto para isso:

- `OrderService`/`CheckoutService` **nunca** conhece o provedor concreto — só conhece as interfaces `PaymentGateway` e `ShippingProvider` (Dependency Inversion Principle).
- Implementar `FakePaymentGateway` e `FakeShippingProvider` controladas por profile Spring (`application-dev.yml`), simulando respostas realistas (aprovado, recusado, pendente).
- Quando for integrar de verdade: nova classe `@Component` implementando a interface (ex: `MercadoPagoGateway`), troca de profile ativo. Zero mudança no core.
- Considerar separar `PaymentInitiator` (criar cobrança) de `PaymentWebhookHandler` (processar retorno assíncrono) — Interface Segregation Principle.
- Processamento de confirmação de pagamento deve ser **idempotente** desde o início (usar id da transação do gateway como chave de deduplicação), mesmo antes de existir gateway real — isso evita debitar estoque duas vezes ou marcar pedido como pago repetidamente quando o webhook real chegar.

## Concorrência: o ponto mais crítico do sistema

Cenário a proteger: dois clientes tentando comprar a mesma peça (estoque único) ao mesmo tempo.

- **Lock pessimista** (`SELECT ... FOR UPDATE` / `@Lock` do JPA) na linha da peça durante a reserva de estoque, dentro de transação. O segundo cliente espera a transação do primeiro terminar; se o estoque já zerou, recebe erro claro ("peça esgotada").
- Lock pessimista é a escolha certa para este porte (não lock otimista com `@Version` — isso só compensaria com volume alto de concorrência).
- **Reserva com expiração, não decremento definitivo**: ao iniciar checkout, pedido entra em `AGUARDANDO_PAGAMENTO` e o estoque é reservado (decrementado do disponível, pedido ainda não confirmado). Job assíncrono (`@Scheduled`, polling a cada 1-2 min) libera reservas expiradas (ex: 15-30 min sem pagamento), devolvendo a peça ao estoque disponível. Quartz só valeria a pena se o volume crescer muito — não é o caso agora.
- **Máquina de estados do pedido**: `CARRINHO → AGUARDANDO_PAGAMENTO (estoque reservado) → PAGO → ENVIADO → ENTREGUE`, com ramo `CANCELADO/EXPIRADO` que devolve estoque.
- Testar concorrência de verdade com Testcontainers (Postgres real, não H2): teste de integração disparando múltiplas threads simultâneas comprando a mesma peça, validando que só uma consegue.

## SOLID aplicado ao domínio

- **S**: `StockService` só cuida de reservar/liberar estoque com lock; `OrderService` só orquestra o pedido; `PaymentGateway` só fala com o provedor.
- **O**: novo gateway/transportadora = nova classe implementando a interface, sem editar o core.
- **L**: qualquer implementação de `PaymentGateway` (real ou fake) deve manter o mesmo contrato semântico (status consistente, idempotência garantida, mesmas exceções previstas).
- **I**: interfaces pequenas e específicas em vez de uma interface gigante (ex: separar iniciação de pagamento de tratamento de webhook).
- **D**: services dependem de abstrações (`PaymentGateway`, `ShippingProvider`), injetadas via Spring DI — nunca importam SDK de provedor diretamente.

## Admin / backoffice

- CRUD de peças (nome, descrição, preço, fotos, estoque, vínculo com conjunto). Soft delete — peça removida some da loja mas mantém integridade do histórico de pedidos antigos.
- Possibilidade de zerar/ajustar estoque manualmente (ex: venda fora do site).
- Gestão de pedidos: listar por status, atualizar status manualmente, inserir código de rastreio, ver detalhe (itens, cliente, endereço, valor, pagamento).
- Relatórios básicos: total vendido por período, peças mais vendidas, pedidos pendentes de envio.
- Endpoints protegidos por role `ADMIN`, distinta de `CLIENTE`.
- Não existe fila de produção nem status "em produção" — modelo é de estoque pronto, não sob encomenda.

## Stack e dependências (Spring Initializr)

- Maven, Java 21, Spring Boot 4.x, packaging Jar
- **Spring Web**, **Spring Data JPA**, **PostgreSQL Driver**, **Flyway Migration**
- **Spring Security**
- **Validation** (Jakarta Validation)
- **Spring Boot Actuator** (observabilidade/health checks)
- **Docker Compose Support** (dependência oficial do Spring Boot — sobe Postgres automaticamente em dev, evita conflito de porta manual)
- **Testcontainers**
- **Spring Boot DevTools** (apenas dev)

Adicionadas manualmente no `pom.xml` (não estão no Initializr):
- **MapStruct** — mapeamento Entity ↔ DTO
- **Lombok**
- **springdoc-openapi** — documentação Swagger/OpenAPI automática

Fora de escopo por enquanto: SDKs de gateway de pagamento/frete, Spring Cloud, mensageria (Kafka/RabbitMQ).

## Docker

- **Dev**: Docker Compose Support do Spring gerencia o Postgres automaticamente via `compose.yaml`.
- **Produção/imagem final**: `Dockerfile` multi-stage (build com Maven, runtime com JRE enxuta, sem JDK/Maven na imagem final) — reduz tamanho e superfície de ataque.
- Segredos (senha do banco, secret JWT, futuras chaves de gateway) via variáveis de ambiente, nunca hardcoded no `application.yml` nem commitados. `.env` local no `.gitignore` para o Compose.

## Regras de comportamento (Claude Code) — obrigatórias

- **Nunca corrigir, refatorar, formatar ou alterar nada que não foi explicitamente pedido**, mesmo que pareça um erro, code smell, ou melhoria óbvia. Se notar algo que merece correção, **apontar e perguntar antes**, nunca aplicar direto.
- **Nunca agir por conta própria** fora do que foi pedido no momento — sem adicionar funcionalidades extras, sem "aproveitar e já fazer" outra coisa relacionada, sem instalar dependências não solicitadas.
- Manter modo de permissão que exija aprovação antes de editar arquivo ou rodar comando (nunca modo de auto-aceite).
- Se a tarefa pedida tocar em código que teria outros problemas, resolver **apenas** o que foi pedido e listar o resto como sugestão, não como ação.

## Preferências de desenvolvimento do Miguel (já conhecidas de outros projetos)

- Sem prompts de confirmação antes de gerar código (na conversa de chat/planejamento — no Claude Code vale a regra acima, de sempre pedir permissão antes de agir).
- Preferir fixes pontuais a reescritas completas de arquivo.
- Padrão de soft delete.
- Endpoints PATCH estilo toggle quando aplicável.
- Nomes de campos em inglês em entidades e DTOs.

## Rotina de atualização deste arquivo — obrigatória

- **Ao final de cada sessão de trabalho, ou quando os tokens do contexto estiverem perto de esgotar**, atualizar **apenas** a seção "Progresso" abaixo com um resumo do que foi feito (arquivos criados/alterados, decisões tomadas, o que ficou pendente).
- **Nunca reescrever, resumir, encurtar ou remover conteúdo de qualquer outra seção deste arquivo** (modelo de negócio, arquitetura, SOLID, regras de comportamento, decisões pendentes, etc.) ao atualizar o Progresso. Se uma decisão nova surgir durante a sessão e contradisser algo já escrito em outra seção, **apontar o conflito e perguntar** antes de alterar a seção original — nunca sobrescrever silenciosamente.
- Cada atualização de Progresso deve virar um **novo marco**, no topo da lista (mais recente primeiro), sem apagar os marcos anteriores. Isso mantém um histórico legível de como o projeto evoluiu, em vez de um bloco de texto único que só cresce e fica confuso.
- Resumo de cada marco deve ser objetivo: o que foi implementado, o que falta, e qualquer decisão nova que não estava no restante deste arquivo antes (essa decisão nova deve também ser adicionada na seção apropriada, não só citada aqui).

## Progresso

_(mais recente primeiro — novo marco vai no topo, sem apagar os anteriores)_

### Marco 9 — módulo `shipping` implementado, testado ponta a ponta e commitado
- **Módulo `shipping` implementado por completo** seguindo `modulo-shipping-codigo.md`: interface `ShippingProvider` (DIP) + `FakeShippingProvider` (`@ConditionalOnProperty(app.shipping.provider=fake)`, fórmula arbitrária base + valor por item, `STANDARD`/`EXPRESS` com multiplicador 1.8), 2 DTOs (`ShippingQuoteResponseDto`, `SelectShippingRequestDto`), `ShippingService` (`getQuotes` só consulta sem persistir, `selectShipping` recalcula o preço a partir do provider — nunca confia no valor vindo do cliente), `ShippingController` (`/api/orders/me/{orderId}/shipping/quotes` GET, `/api/orders/me/{orderId}/shipping` POST — sem regra nova no `SecurityConfig`, já cai em `/api/orders/me/**`), migration `V16__add_shipping_columns_to_orders.sql`.
- **Edições em código já existente**, conforme exigido pelo `.md` (não são módulos novos, são mudanças em `order` e `payment`): `Order` ganhou campos `shippingCost`/`shippingOptionCode`/`shippingOptionName`/`estimatedDeliveryDays`, métodos `applyShipping(...)`/`hasShippingSelected()`, e `recalculateTotal()` passou a somar `itensTotal + frete` (antes só somava os itens); `OrderResponseDto` ganhou os 3 campos de frete no record; `PaymentService.initiatePayment` ganhou a checagem `if (!order.hasShippingSelected()) throw new InvalidStateException(...)` logo após validar que o pedido está `AGUARDANDO_PAGAMENTO` — pagar sem frete escolhido agora é bloqueado.
- Compilação (`mvnw compile`, JDK 21 Temurin) passou sem erros — 106 arquivos-fonte.
- **Testado ponta a ponta via curl**, aplicação rodando: pedido chega em `AGUARDANDO_PAGAMENTO` com `totalAmount = 100.00` (só itens) → tentar pagar sem frete devolve 409 "Selecione o frete antes de pagar" → cotação devolve `STANDARD` (20.00/7 dias) e `EXPRESS` (36.00/3 dias) sem alterar o pedido → selecionar `EXPRESS` recalcula o total pra `136.00` → pagamento aprovado cobra o valor **com frete incluído** (`amount: 136.00`), pedido vira `PAGO` → tentar trocar o frete depois de pago devolve 409.
- Pendências abertas (já citadas no `.md` de referência): fórmula do fake é arbitrária, não usa peso/dimensão real (`Piece` não tem esses campos ainda); testes automatizados (unitário/integração) ainda não escritos, mesma situação pendente dos módulos anteriores.
- Commit único na branch `feature/shipping` (incluindo as edições em `order`/`payment` no mesmo commit, já que fazem parte da mesma entrega funcional), fast-forward de `main` feito na sequência.
- Próximo módulo: `admin` — último da lista original do `CLAUDE.md`. Ainda não existe `.md` de referência (`modulo-admin-codigo.md`) no repositório até o fim desta sessão.

### Marco 8 — módulo `payment` implementado, testado ponta a ponta e commitado
- **Módulo `payment` implementado por completo** seguindo `modulo-payment-codigo.md`: `PaymentStatus` (`PENDING`/`APPROVED`/`DECLINED`), `Payment` (entity com `transactionId` único, idempotência via `isResolved()`), `PaymentRepository`, interfaces `PaymentInitiator`/`PaymentWebhookHandler` (ISP) + `FakePaymentGateway` (`@ConditionalOnProperty(app.payment.provider=fake)`, campo `simulateOutcome` determinístico), 3 DTOs, `PaymentMapper`, `PaymentWebhookHandlerImpl` (idempotente, chama `OrderService.markAsPaid` na aprovação — reaproveitado sem alteração), `PaymentService` (`initiatePayment`, `listOwnOrderPayments`, `findByIdAsAdmin`, `processWebhook`), `PaymentController` (`/api/orders/me/{orderId}/payments`, `/api/payments/{id}` admin, `/api/payments/webhook/fake` público), migration `V15__create_table_payments.sql`.
- Regra `/api/payments/**` adicionada ao `SecurityConfig` (webhook `permitAll()`, `GET /api/payments/*` restrito a `ADMIN`).
- Compilação (`mvnw compile`, JDK 21 Temurin) passou sem erros.
- **Testado ponta a ponta via curl**, aplicação rodando: recusa (`DECLINED`) não cancela o pedido (continua `AGUARDANDO_PAGAMENTO`) → nova tentativa aprovada (`APPROVED`) dispara `OrderService.markAsPaid` via webhook, pedido vira `PAGO` → histórico de tentativas mostra as duas linhas (auditoria) → reenviar o mesmo webhook (mesmo `transactionId`) devolve 204 sem duplicar nada (idempotência real confirmada) → validação de `simulateOutcome` inválido devolve 400 com mensagem clara (testado isoladamente, ver nota abaixo).
- **Observação sobre o script de teste do `modulo-payment-codigo.md` (seção 12), não é bug de código**: o passo 8 (testar `simulateOutcome` inválido) roda depois do passo 5, quando o pedido usado já está `PAGO` — nesse estado o `InvalidStateException` ("pedido não está aguardando pagamento") dispara antes mesmo de chegar na validação do `FakePaymentGateway`, então o script como está devolve 409 em vez do 400 esperado. Isolando o mesmo teste num pedido ainda `AGUARDANDO_PAGAMENTO`, o 400 esperado ocorre normalmente. Fica registrado como ajuste a fazer no `.md` de referência (rodar o passo 8 antes do pagamento ser aprovado, ou usar um pedido separado), não como correção de código.
- Commit único na branch `feature/payment`, fast-forward de `main` feito na sequência, mesmo padrão dos módulos anteriores.
- Pendências abertas (já citadas no `.md` de referência): verificação de assinatura do webhook não implementada (aceitável só enquanto for gateway fake, marcado como pendência de segurança real); testes automatizados (unitário/integração) do `payment` ainda não escritos, mesma situação pendente dos módulos anteriores.
- Próximo módulo: `shipping`.

### Marco 7 — módulo `order` implementado, ainda NÃO testado nem commitado
- **Módulo `order` implementado por completo** seguindo `modulo-order-codigo.md`: `OrderStatus` (`CARRINHO → AGUARDANDO_PAGAMENTO → PAGO → ENVIADO → ENTREGUE`, ramos `CANCELADO`/`EXPIRADO`/`ESTORNO_SOLICITADO`/`ESTORNADO`), `Order` (snapshot de endereço e de estorno, sem soft delete), `OrderItem` (polimórfico peça/kit, snapshot de nome/preço), `OrderRepository`, 7 DTOs, `OrderMapper`, `OrderService` completo (carrinho único por cliente, checkout reservando estoque via `StockService` — kit reserva em cada peça componente —, `markAsPaid` idempotente, envio/entrega, cancelamento devolvendo estoque, fluxo de estorno solicitar/aprovar/negar, expiração automática), `OrderExpirationJob` (`@Scheduled` 2min), `OrderController` (`/api/orders`).
- Migrations criadas (ainda não confirmadas contra Postgres real nesta sessão, mas o Miguel confirmou a aplicação rodando): `V12` (orders), `V13` (order_items, com `CHECK` de exclusividade peça/kit), `V14` (resolve a pendência do `stock`: FK física `stock_reservations.order_id → orders.id`, sem mudança de código Java).
- Regra `/api/orders/**` adicionada ao `SecurityConfig` (rotas `/me/**` autenticadas, resto `ADMIN`) e `app.order.checkout-expiration-minutes: 2` no `application-dev.yml`.
- Compilação (`mvnw compile`, JDK 21 Temurin) passou sem erros — 86 arquivos-fonte.
- **Sessão encerrada aqui a pedido do Miguel, ANTES de rodar o script de teste do `.md` (seção 12) e ANTES do commit.** Pendências pra retomar na próxima sessão, em ordem:
  1. Confirmar/criar conta `CLIENTE` `miguel@teste.com` / `senha12345` (o script de teste assume que ela já existe).
  2. Rodar o script de teste via curl do `modulo-order-codigo.md` (fluxo completo até `ENTREGUE`; cenário de cancelamento devolvendo estoque; cenário de kit + estorno solicitar/negar/aprovar).
  3. Se tudo passar: fazer o commit do módulo `order` (seguindo o padrão de branch usado nos módulos anteriores — provavelmente 1 commit só, já que não há reescrita de módulo já existente misturada como aconteceu no Marco 6) e o fast-forward de `main`.
  4. Depois disso, o próximo módulo em aberto é `payment` (ou `admin`/`shipping`, a decidir) — ver seção "Módulos" e "Pagamento e frete" para o que já está fechado.
- Estado do git no fim desta sessão: working tree com o módulo `order` todo **untracked/modified, nada staged, nada commitado**. Branch atual: `main` (fast-forward feito até o módulo `stock` no Marco 6; `order` ainda não tem branch `feature/order` criada).

### Marco 6 — reescrita do `GlobalExceptionHandler` e módulo `stock` completo, testado ponta a ponta
- **`common`/exceções reescrito** seguindo `modulo-common-exceptions.md`: nova hierarquia `BusinessException` (abstrata, `HttpStatus` embutido) → `ResourceNotFoundException` (404, mesmo construtor de antes), `DuplicateResourceException` (409, nova, ainda não usada nos módulos existentes), `InvalidStateException` (409, nova, usada pelo `stock`). `GlobalExceptionHandler` reescrito: um handler genérico pra `BusinessException` cobre qualquer subclasse; mantidos os handlers de `AuthenticationException`/`ExpiredJwtException`/validação; mantido fallback de compatibilidade pra `IllegalArgumentException` (400) e `IllegalStateException` (409), já que `user`/`customer`/`catalog` ainda lançam essas diretamente; adicionado `handleUnexpected` genérico pra `Exception`, evitando vazar detalhe interno. Adicionado `server.error.include-stacktrace`/`include-message`: `always` em dev, `never` em prod/docker. Migrar os `IllegalArgumentException` existentes pra `DuplicateResourceException` fica registrado como sugestão futura, não aplicada (módulo por módulo, só quando pedido).
- **Módulo `stock` implementado por completo** seguindo `modulo-stock-codigo.md`: entity `StockReservation` (status `ACTIVE`/`CONFIRMED`/`RELEASED`/`EXPIRED`, `orderId` como `UUID` solto — ver decisão na seção de módulos), `StockReservationRepository`, DTOs (`ReserveStockRequestDto`, `StockReservationResponseDto`), `StockReservationMapper`, `StockService` (`reserve`/`confirm`/`release`/`findById`/`releaseExpiredReservations`, todos com lock pessimista via `findByIdForUpdate`), `StockReservationCleanupJob` (`@Scheduled` a cada 2min), `StockController` (`/api/stock/reservations`, restrito a `ADMIN`), migration `V11__create_table_stock_reservations.sql`.
- Alterações em módulos existentes, necessárias pro `stock` funcionar: `PieceRepository` (`catalog`) ganhou `findByIdForUpdate` com `@Lock(PESSIMISTIC_WRITE)`; `SecurityConfig` ganhou a regra `/api/stock/**` → `ADMIN`; `DemoApplication` ganhou `@EnableScheduling` (sem isso o job nunca dispara sozinho); `application-dev.yml` ganhou `app.stock.reservation-expiration-minutes: 2` (curto, só pra testar o job sem esperar o valor real de produção).
- **Testado ponta a ponta via curl** (script em `modulo-stock-teste-curl.md`, rodado com o app já de pé): login → criar categoria/peça (`stock: 3`) → reservar 2 unidades (stock cai pra 1) → tentar reservar 5 (mais que o disponível) → **409 Conflict** "Peça esgotada" via `InvalidStateException` → liberar reserva manualmente (stock volta pra 3) → reservar 1 de novo → aguardar o job assíncrono → reserva vira `EXPIRED` sozinha e stock volta pra 3 sem intervenção manual. O passo de expiração não bateu no primeiro `sleep` do script (200s não alinhou com o ciclo fixo de 2min do `@Scheduled`, que conta a partir do start da aplicação, não da criação da reserva) — conferido manualmente minutos depois, confirmou que o job funcionou corretamente; não é bug, é característica do `fixedRate`.
- Pendências abertas: teste de concorrência real (múltiplas threads simultâneas disputando a mesma peça) continua não feito, mesma situação já prevista na seção "Concorrência"; testes automatizados (unitário/integração) do `stock` ainda não escritos, mesma situação pendente de `security`/`catalog`; módulo `stock` ainda não commitado no git.

### Marco 5 — módulo `catalog` completo, implementado e testado ponta a ponta
- Entities criadas seguindo `modulo-catalog-codigo.md`: `Category` (sem soft delete — sem valor histórico), `Piece` (Peça, com `stock` bruto como fonte de verdade, soft delete via `deletedAt`, fotos em `piece_images` via `@ElementCollection` normalizada, categorias N:N via `piece_categories`), `PieceSet` (Conjunto/Kit, preço fixo definido pelo admin, peças N:N via `set_pieces`, estoque **nunca persistido** — `calculateAvailableStock()` calcula o mínimo entre as peças componentes em tempo real, tratando peça inativa como estoque zero).
- **Decisão nova**: campo de estoque bruto (`stock`) vive na entidade `Piece` (módulo `catalog`), não em `stock` — o módulo `stock` (próximo) vai construir a lógica de *reserva com expiração e lock pessimista* em cima dessa mesma coluna via import cruzado, sem duplicar o dado. Já refletida na seção de módulos.
- Repositories, DTOs (`CategoryRequestDto`/`ResponseDto`, `PieceRequestDto`/`ResponseDto`, `PieceSetRequestDto`/`ResponseDto`), mappers MapStruct (com `expression = "java(...)"` para `inStock`/`availableStock`, que são regra de negócio da entidade, não campo simples) e services criados seguindo o gabarito.
- Controllers com rotas de navegação pública (`GET`) e CRUD restrito a `ADMIN` (`POST`/`PUT`/`DELETE`), regras centralizadas no `SecurityConfig` (nenhum `@PreAuthorize` nos controllers do `catalog` — todas as regras são fixas por método HTTP).
- Migrations `V5`–`V10` criadas e validadas contra Postgres real: `categories`, `pieces`, `piece_images`, `piece_categories`, `piece_sets`, `set_pieces`.
- Bugs encontrados e corrigidos durante a implementação, já refletidos no `.md` de referência:
    - Import `java.util.List` faltando em `PieceService.java` (usado em `dto.imageUrls() != null ? dto.imageUrls() : List.of()`).
    - `resolvePieces()` em `PieceSetService` não validava se as peças informadas estavam ativas — aceitava vincular peça soft-deleted a um conjunto novo. Corrigido: lança `IllegalArgumentException` se alguma peça estiver inativa.
    - `ExpiredJwtException` (token JWT vencido) não tinha handler no `GlobalExceptionHandler`, virando `500 Internal Server Error` em vez de `401`. Adicionado `@ExceptionHandler(ExpiredJwtException.class)` retornando 401 "Token expirado, faça login novamente".
- **Testado ponta a ponta via Postman, sequência completa validada**: criar categoria (201) → criar peça com categoria vinculada (201) → listar peças sem login, rota pública (200) → criar peça sem ser ADMIN, bloqueado (403) → criar segunda peça (201) → criar conjunto com as duas peças, `availableStock` = mínimo entre elas (201, valor correto) → soft delete de uma peça componente → conjunto reconsultado com `availableStock: 0` (regra de estoque dinâmico funcionando) → consulta direta da peça deletada retorna 404 "Peça não encontrada" (soft delete escondendo corretamente de consultas normais, mantendo o registro físico intacto no banco).
- `createdAt` vindo `null` na resposta do `POST` (mas correto no `GET` subsequente) é comportamento esperado do `@CreationTimestamp` — o objeto retornado na resposta de criação é montado antes do Hibernate popular o campo gerado; não é bug.
- Pendências abertas: numeração de migrations futuras precisa continuar a partir de `V10`; nenhuma peça/conjunto testada com múltiplas categorias simultâneas ainda; testes automatizados (unitário/integração) do `catalog` ainda não escritos, mesma situação pendente do módulo `security`.

### Marco 4 — repositório git, módulo `customer`, módulo `security` e ajustes finais
- Repositório git criado em `demo/` (raiz correta), branch `main` com commit inicial cobrindo o estado até o Marco 3. Branches `feature/customer` e `feature/security` criadas a partir da `main` pra cada módulo novo.
- Módulo `customer` implementado por completo seguindo `modulo-customer-codigo.md`: entities `Customer`/`Address`, `CustomerRepository`/`AddressRepository`, DTOs (`CustomerRequestDto`, `CustomerUpdateRequestDto` sem CPF — imutável, `CustomerResponseDto`, `AddressRequestDto`, `AddressResponseDto`), `CustomerMapper`, `CustomerService`, `CustomerController` (rotas `/me/**` sem anotação de segurança + `GET /{id}` admin), migrations `V2`/`V3`. Rascunho antigo `costumer/entity/Costumer.java` (grafia errada, comentado) removido pelo Miguel — grafia definitiva confirmada: `customer`.
- Módulo `security` implementado por completo seguindo `modulo-security-codigo.md`: dependências JJWT no `pom.xml`, entity `RefreshToken`, `RefreshTokenRepository`, DTOs (`SignupRequestDto`, `LoginRequestDto`, `TokenResponseDto`), `JwtService`, `RefreshTokenService` (com `generateRawToken()` implementado — faltava no gabarito original), `UserDetailsServiceImpl`, `JwtAuthenticationFilter`, `SecurityConfig` definitivo (substituindo o mínimo criado antes só pro bean `PasswordEncoder`, que foi removido), `AuthService`, `AuthController`, migration `V4`. Ajustado `app.cookie.secure` por profile (`false` em dev, default `true` nos demais) via `@Value` no `AuthController`.
- Correções do Miguel em cima do gabarito de `security`, já refletidas nos `.md` de referência: `SecurityConfig.authenticationProvider` usando `new DaoAuthenticationProvider(userDetailsService)` (construtor) em vez do `new DaoAuthenticationProvider()` + setter deprecated do gabarito original (que também tinha um typo de digitação); rota `/swagger-ui.html` adicionada ao `permitAll()`.
- Tentativa de implementar os testes de `modulo-security-testes.md` (unitários + integração) esbarrou em vários problemas de ambiente: build local só compila com `JAVA_HOME` apontando pro JDK 21 Temurin (o JDK 25 default da máquina falha no `maven-compiler-plugin` com `--release 21`); `TestcontainersConfiguration` é package-private, bloqueando uso fora do pacote raiz; `@AutoConfigureMockMvc` mudou de pacote no Spring Boot 4.1 (`org.springframework.boot.webmvc.test.autoconfigure`, não mais `org.springframework.boot.test.autoconfigure.web.servlet` como no gabarito). **A pedido do Miguel, toda essa tentativa foi revertida por completo** — os 4 arquivos de teste, as dependências do Mockito no `pom.xml` e o `public` do `TestcontainersConfiguration` foram removidos. Os testes do módulo `security` continuam pendentes de implementação.
- Ajustes do Miguel nesta sessão, já refletidos nos `.md` de referência correspondentes: `GlobalExceptionHandler` ganhou `@ExceptionHandler(AuthenticationException.class)` retornando 401 "Credenciais inválidas" (cobre login com senha errada); `CustomerController` teve `produces`/`consumes` movidos do `@RequestMapping` de classe pra cada endpoint individualmente (só onde faz sentido — `DELETE` sem corpo não declara nada); `application-dev.yml` ganhou `org.springframework.security: debug` no logging.
- Pendências abertas: rodar as migrations `V2`–`V4` contra um Postgres de verdade (só foram criadas, não validadas em banco real ainda); ajustar `allowedOrigins` do CORS quando o front existir; testes do módulo `security` (unitários e integração) ainda não escritos; endpoint de signup unificado já existe (`AuthService.signup`), mas não foi testado ponta a ponta.

### Marco 3 — módulo `user` completo (dto, mapper, service, controller, migration)
- DTOs finalizados em `user/dto`: criados `UserRoleUpdateDto`, `UserPasswordUpdateDto`, `UserResponseDto` (nomenclatura `Dto`, não `DTO` como no gabarito de `modulo-user-codigo.md`, seguindo o padrão já usado nos DTOs existentes). `UserRequestDto` e `UserUpdateRequestDto` já existiam de sessão anterior.
- Corrigidos pelo Miguel durante a sessão: `Role.java` (`ADMIN, USER` → `ADMIN, CLIENTE`), `User.java` (adicionado `@Enumerated(EnumType.STRING)` no campo `role` — faltava e quebraria a persistência contra a coluna `VARCHAR(20)` da migration), `UserRequestDto.java` (`role` trocado de `@NonNull` do Lombok — não validado pelo Bean Validation — para `@NotNull` do `jakarta.validation`).
- `UserRepository`: adicionado pelo Miguel o finder `findAllByRoleAndDeletedAtIsNull(Role, Pageable)`, usado no filtro opcional de listagem por role.
- Criados `user/mapper/UserMapper.java` (MapStruct, `toResponseDto`), `user/service/UserService.java` (`createUser`, `listUsers` com filtro opcional por role, `findById`, `updateUser`, `updateRole`, `changePassword`, `softDeleteUser`, `findByEmail`) e `user/controller/UserController.java` (endpoints REST em `/api/users`), seguindo o gabarito de `modulo-user-codigo.md`.
- `UserController` ajustado pelo Miguel depois da criação inicial: `@RequestMapping` passou a declarar `produces`/`consumes = MediaType.APPLICATION_JSON_VALUE`; as anotações `@PreAuthorize` foram removidas de todos os endpoints. Hoje isso não muda comportamento (sem efeito prático ainda, já que `security`/JWT não existe), mas fica registrado como pendência **re-adicionar controle de role nos endpoints de `user`** quando o módulo `security` for implementado — senão o módulo fica sem proteção nenhuma mesmo depois do JWT existir, o que contradiria a decisão já fechada na seção "Admin / backoffice" (endpoints protegidos por role `ADMIN`).
- Criada `db/migration/V1__create_table_users.sql`, primeira migration do projeto (pasta estava vazia) — cria tabela `users` com índices em `email` e `role`, seguindo exatamente o gabarito do md.
- Pendência que continua aberta (já citada no md de referência): bean de `PasswordEncoder` ainda não existe em lugar nenhum do projeto — `UserService` não sobe sem isso; depende do módulo `security`.
- Próximo passo a decidir: módulo `security` (JWT, `SecurityConfig`, bean `PasswordEncoder`) ou seguir para o próximo módulo de domínio (`customer`).

### Marco 2 — correção de credenciais e validação end-to-end
- Corrigida a divergência entre `compose.yaml` (usava `mydatabase`/`myuser`/`secret`) e os defaults de `application-dev.yml` (`demo`/`postgres`/`postgres`): `compose.yaml` alinhado para usar `demo`/`postgres`/`postgres`, batendo com `.env`, `.env.example` e os fallbacks do `application-dev.yml`.
- Identificado e corrigido problema de "Working directory" na Run Configuration do IntelliJ (`DemoApplication`): estava apontando para a pasta pai (`projetotadeu`) em vez da pasta do módulo Maven (`projetotadeu/demo`), causando erro `No Docker Compose file found`. Corrigido via Modify options → Working directory → caminho explícito da pasta `demo`.
- Validação completa rodando pela IDE (IntelliJ, não só `mvnw` via terminal): Docker Compose Support sobe `demo-postgres-1` automaticamente e o marca como `Healthy`, Flyway conecta e cria a tabela de controle (`flyway_schema_history`, sem migrations ainda), Hibernate/JPA inicializa, Tomcat sobe na porta 8080. Aplicação inicia sem erros.
- Nota: o Spring Security gera uma senha aleatória de desenvolvimento no log (comportamento esperado, sem configuração de autenticação real ainda — pendente no módulo `security`).
- Próximo passo: modelo de entidades (Peça, Conjunto, Pedido, Reserva de estoque).

### Marco 1 — dependências e configuração de ambiente
- `pom.xml`: adicionadas Lombok (1.18.46), MapStruct (1.6.3) e springdoc-openapi (springdoc-openapi-starter-webmvc-ui 3.1.0), com `maven-compiler-plugin` configurado com `annotationProcessorPaths` (lombok + lombok-mapstruct-binding + mapstruct-processor).
- Criados os arquivos de configuração por profile em `src/main/resources/`: `application.yml` (base, substituiu o antigo `application.yaml` que só tinha o nome da app), `application-dev.yml`, `application-docker.yml`, `application-prod.yml` — seguindo o que estava definido na seção "Stack e dependências" e "Docker".
- `.env.example` (vai pro git) e `.env` (valores de dev local, `postgres`/`postgres`) criados na raiz do projeto.
- `.gitignore` atualizado para ignorar `.env` e `.env.local` (antes não ignorava).
- `Dockerfile` multi-stage criado: build com `maven:3.9-eclipse-temurin-21`, runtime com `eclipse-temurin:21-jre-alpine`.
- `docker-compose.local.yml` criado (serviços `app` e `db`, volume nomeado `db-data`) para simular produção localmente — coexiste com o `compose.yaml` existente, que continua sendo o do Docker Compose Support do Spring para dev automático (não foi alterado).
- Testado localmente pelo Miguel: modo dev (`mvnw spring-boot:run`) e `docker-compose.local.yml` — ambos funcionando.
- Pendências continuam as mesmas da seção "Decisões ainda em aberto" (nada resolvido neste marco além da config de ambiente).

### Marco 0 — planejamento inicial
- Nada implementado ainda. Projeto no esqueleto padrão do Spring Initializr.
- Decisões de arquitetura, SOLID, concorrência, stack e Docker já fechadas (ver seções acima).
- Pendências: modelo de entidades, `application.yml` por profile, autenticação do cliente, hospedagem, notificação de pedido novo.

## Decisões ainda em aberto (a fechar antes ou durante a implementação)

- Hospedagem definitiva (VPS, Railway, Render, Oracle Cloud Free Tier etc.).
- Notificação de pedido novo para o artesão (email/WhatsApp vs. só painel admin).
- Modelo de entidades detalhado de `Pedido` (`order`) — ainda não desenhado.
- `application.yml` conceitual por profile (dev, docker-local, prod) — ainda não desenhado.