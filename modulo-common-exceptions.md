# Módulo `common` — Exceptions de referência

> Pacote base: `com.TadeuArtesanatos.demo.common.exception`
> Usado por todos os módulos (`user`, `customer`, `security`, `catalog`, `stock`, e os que vierem depois).

---

## Por que essa reescrita

O `GlobalExceptionHandler` original tinha 3 problemas acumulados, notados ao longo da implementação de vários módulos:

1. **`IllegalArgumentException` virou catch-all genérico** — "email duplicado", "CPF duplicado", "categoria inexistente" e outras coisas semanticamente diferentes caíam todas em `400 Bad Request`. Duplicidade de recurso é conceitualmente `409 Conflict`, não requisição malformada.
2. **Stack trace vazando em respostas de erro não tratadas** — comportamento padrão do Spring Boot (`server.error.include-stacktrace`), aceitável em dev, mas falha de segurança real em produção (expõe estrutura interna, nomes de classe, versões de biblioteca).
3. **Handlers crescendo sem hierarquia** — cada exceção nova (`AuthenticationException`, `ExpiredJwtException`, e agora `IllegalStateException` do módulo `stock`) exigia um método novo repetindo a mesma estrutura.

**Solução:** uma hierarquia própria de exceções de negócio, com o status HTTP já embutido na exceção — um único handler genérico no `GlobalExceptionHandler` cobre qualquer subclasse, sem precisar de método novo a cada exceção.

**Compatibilidade:** os módulos já implementados (`user`, `customer`, `catalog`) usam `throw new IllegalArgumentException(...)` em vários pontos. Mantive um handler de fallback pra essas chamadas continuarem funcionando (mapeadas pra 400, como já eram) — **não fui mexer no código desses módulos**, já que isso seria refatoração de arquivo já implementado, fora do que foi pedido agora. Migrar esses pontos pra `DuplicateResourceException`/`InvalidStateException` fica registrado como sugestão futura, não ação aplicada.

---

## 1. `BusinessException` (classe base abstrata)

`common/exception/BusinessException.java`

```java
package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;

public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;

    protected BusinessException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
```

---

## 2. `ResourceNotFoundException` (404)

`common/exception/ResourceNotFoundException.java`

```java
package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
```

**Compatibilidade:** mesmo construtor de antes (`new ResourceNotFoundException("mensagem")`) — nenhum código que já usa essa classe precisa mudar.

---

## 3. `DuplicateResourceException` (409)

`common/exception/DuplicateResourceException.java`

```java
package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;

public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
```

Uso pretendido (não aplicado nos módulos existentes, ver nota de compatibilidade acima): `email já cadastrado`, `CPF já cadastrado`, `categoria já existe` — qualquer "esse recurso já existe" que hoje usa `IllegalArgumentException`.

---

## 4. `InvalidStateException` (409)

`common/exception/InvalidStateException.java`

```java
package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateException extends BusinessException {

    public InvalidStateException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
```

Uso pretendido: "peça esgotada" (módulo `stock`), "reserva não está mais ativa", "peça inativa não pode entrar num conjunto" — qualquer operação recusada por causa do **estado atual** do recurso, não por dado inválido na requisição. `409 Conflict` é o status semanticamente correto pra esse caso (o servidor entende a requisição, mas o estado atual do recurso impede a operação).

---

## 5. `GlobalExceptionHandler` (reescrito)

`common/exception/GlobalExceptionHandler.java`

```java
package com.TadeuArtesanatos.demo.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.jsonwebtoken.ExpiredJwtException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- Hierarquia própria de negócio — um handler cobre todas as subclasses ----

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(buildBody(ex.getStatus(), ex.getMessage()));
    }

    // ---- Autenticação / JWT ----

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(buildBody(HttpStatus.UNAUTHORIZED, "Credenciais inválidas"));
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<Map<String, Object>> handleExpiredJwt(ExpiredJwtException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(buildBody(HttpStatus.UNAUTHORIZED, "Token expirado, faça login novamente"));
    }

    // ---- Validação de Bean Validation (@Valid nos DTOs) ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Dados inválidos");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildBody(HttpStatus.BAD_REQUEST, message));
    }

    // ---- Fallback de compatibilidade — módulos existentes ainda usam essas diretamente ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(buildBody(HttpStatus.CONFLICT, ex.getMessage()));
    }

    // ---- Fallback final — qualquer exceção não tratada não vaza detalhe interno ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildBody(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado"));
    }

    private Map<String, Object> buildBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
```

**Nota sobre o `handleUnexpected` (fallback final):** captura qualquer `Exception` que nenhum handler específico tratou, devolvendo uma mensagem genérica sem detalhe interno — mesmo que o `include-stacktrace` do `application.yml` esteja mal configurado em algum profile, esse handler garante que nada sensível vaza pro cliente da API. O log real do erro continua no console do servidor (o Spring já loga a exceção antes de chegar aqui); só a **resposta HTTP** fica sanitizada.

---

## 6. Configuração — desligar stacktrace nas respostas de erro por profile

Adicionar em `application-prod.yml` e `application-docker.yml`:

```yaml
server:
  error:
    include-stacktrace: never
    include-message: never
```

Em `application-dev.yml`, pode manter o padrão (ou deixar explícito `always`, já que em dev é útil ver o stacktrace enquanto debuga):

```yaml
server:
  error:
    include-stacktrace: always
    include-message: always
```

**Por que isso importa mesmo com o `handleUnexpected` cobrindo:** o handler genérico só entra em ação pra exceções que passam pelo Spring MVC normalmente. Alguns erros (falha de negociação de conteúdo, erro de parsing de URL, certos erros de filtro de segurança) podem cair na página de erro **padrão** do Spring Boot antes mesmo de chegar num `@ExceptionHandler` — é aí que o `include-stacktrace: never` em produção garante a segunda camada de proteção.

---

## Pendência registrada (não aplicada agora)

Migrar os `throw new IllegalArgumentException(...)` já existentes em `UserService`, `CustomerService`, `CategoryService`, `PieceService`, `PieceSetService`, `AuthService` para `DuplicateResourceException` (email/CPF/categoria duplicados) — hoje continuam funcionando via handler de fallback (mapeados pra 400), mas o status semanticamente correto seria 409. Fica como sugestão de refatoração futura, a aplicar só quando você pedir explicitamente, módulo por módulo.

---

## Histórico — bug original que motivou o primeiro `AuthenticationException` handler

**Sintoma:** `POST /auth/login` com senha errada devolvia `403 Forbidden` vazio, sem nenhum log de erro visível, mesmo com o request passando corretamente pela regra `permitAll()`.

**Causa raiz:** `AuthenticationException` (lançada por `authenticationManager.authenticate(...)` quando a senha não bate) não tinha handler nenhum no `GlobalExceptionHandler` original. Sem handler, a exceção escapava sem tratamento, o Spring MVC redirecionava internamente para `/error` (comportamento padrão dele), e como `/error` **também não estava liberado** no `SecurityConfig`, o Spring Security bloqueava essa segunda requisição interna com 403 — mascarando completamente o erro real (que era só "senha errada").

**Correção aplicada na época:**
1. Adicionado `@ExceptionHandler(AuthenticationException.class)`, devolvendo `401 Unauthorized` com corpo JSON claro.
2. Liberado `/error` no `SecurityConfig` (`permitAll()`).

**Como diagnosticamos:** ativando `logging.level.org.springframework.security: debug` no `application-dev.yml`, o log passou a mostrar a sequência exata de filtros — revelou o padrão `Secured POST /auth/login` seguido de `Securing GET /error` bloqueado.

