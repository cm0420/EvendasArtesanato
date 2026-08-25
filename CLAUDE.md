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
- `stock` — controle e reserva de estoque (estoque disponível, reserva com expiração). Referencia a entidade `Peça` de `catalog` (import cruzado, esperado), mas a responsabilidade de lock pessimista e liberação de reserva fica isolada aqui, separada do CRUD de peça — mantendo o `StockService` com responsabilidade única, como já definido na seção de SOLID
- `order` — pedido, item de pedido, máquina de estados. **Não existe módulo separado para carrinho**: `CARRINHO` é o primeiro estado do próprio pedido na máquina de estados já definida na seção de concorrência (`CARRINHO → AGUARDANDO_PAGAMENTO → PAGO → ENVIADO → ENTREGUE`) — assumido assim para não duplicar conceito; avisar se a intenção era ter carrinho como entidade/módulo à parte
- `payment` — interface `PaymentGateway`, implementação fake para dev, DTOs
- `shipping` — interface `ShippingProvider`, implementação fake para dev
- `admin` — endpoints e regras específicas de backoffice (combina dados de vários domínios; não tem entidade própria)
- `common` / `shared` — exceções globais, configs, utilitários

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
- Modelo de entidades detalhado (Peça, Conjunto, Pedido, Reserva de estoque) — ainda não desenhado.
- `application.yml` conceitual por profile (dev, docker-local, prod) — ainda não desenhado.