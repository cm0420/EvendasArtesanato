# Módulo `security` — Testes de referência

> Dois níveis: **unitários** (JUnit 5 + Mockito, rápidos, lógica isolada) e **integração**
> (Testcontainers + MockMvc, fluxo real ponta a ponta com Postgres de verdade).
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## ⚠️ Checagem de dependência antes de começar

O projeto usa starters de teste **granulares** (`spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, etc.) em vez do `spring-boot-starter-test` "guarda-chuva". O Mockito normalmente vem transitivamente pelo `webmvc-test`, mas se der `ClassNotFoundException: org.mockito.Mockito` ao rodar, adicione:

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

---

# PARTE 1 — Testes unitários (Mockito)

Não sobem Spring, não tocam banco. Testam só a lógica de cada classe, com as dependências mockadas.

## 1.1 `JwtServiceTest`

`src/test/java/com/TadeuArtesanatos/demo/security/service/JwtServiceTest.java`

Não precisa de Mockito — `JwtService` não tem dependências externas (só a chave secreta e o tempo de expiração, injetados via `@Value`), então testamos direto, instanciando a classe manualmente.

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "chave-de-teste-com-tamanho-minimo-para-hmac-sha256-funcionar";
    private static final long EXPIRATION_MS = 60_000; // 1 minuto

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION_MS);

        user = User.builder()
                .id(UUID.randomUUID())
                .email("cliente@teste.com")
                .password("hash-qualquer")
                .role(Role.CLIENTE)
                .build();
    }

    @Test
    void deveGerarTokenComEmailCorretoNoSubject() {
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.extractEmail(token)).isEqualTo(user.getEmail());
    }

    @Test
    void tokenValidoParaOEmailCorretoDeveSerAceito() {
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(token, user.getEmail())).isTrue();
    }

    @Test
    void tokenNaoDeveSerValidoParaEmailDiferente() {
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(token, "outro@email.com")).isFalse();
    }

    @Test
    void tokenExpiradoDeveFalharNaValidacao() throws InterruptedException {
        JwtService jwtServiceComExpiracaoCurta = new JwtService(SECRET, 1); // 1 ms
        String token = jwtServiceComExpiracaoCurta.generateAccessToken(user);

        Thread.sleep(50); // garante que o token já expirou

        assertThat(jwtServiceComExpiracaoCurta.isTokenValid(token, user.getEmail())).isFalse();
    }

    @Test
    void tokenComAssinaturaInvalidaDeveLancarExcecao() {
        JwtService outroServiceComOutraChave = new JwtService(
                "outra-chave-completamente-diferente-para-forcar-assinatura-invalida",
                EXPIRATION_MS
        );
        String tokenAssinadoComOutraChave = outroServiceComOutraChave.generateAccessToken(user);

        assertThatThrownBy(() -> jwtService.extractEmail(tokenAssinadoComOutraChave))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
```

**Nota:** esse teste assume `Assertions` do AssertJ (`org.assertj.core.api.Assertions`), que já vem transitivamente com `spring-boot-starter-webmvc-test`. Se preferir os asserts nativos do JUnit (`assertEquals`, `assertTrue`), funciona igual, só menos legível em cadeia.

---

## 1.2 `RefreshTokenServiceTest`

`src/test/java/com/TadeuArtesanatos/demo/security/service/RefreshTokenServiceTest.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.security.repository.RefreshTokenRepository;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email("cliente@teste.com").build();
    }

    @Test
    void issueDeveGerarTokenESalvarComHash() {
        when(passwordEncoder.encode(anyString())).thenReturn("hash-fake");

        String rawToken = refreshTokenService.issue(user);

        assertThat(rawToken).isNotBlank();
        verify(refreshTokenRepository).save(argThat(rt ->
                rt.getUser().equals(user) &&
                rt.getTokenHash().equals("hash-fake") &&
                !rt.getRevoked()
        ));
    }

    @Test
    void validateDeveEncontrarTokenValidoPorHash() {
        RefreshToken tokenValido = RefreshToken.builder()
                .user(user)
                .tokenHash("hash-existente")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .build();

        when(refreshTokenRepository.findAll()).thenReturn(List.of(tokenValido));
        when(passwordEncoder.matches("token-cru", "hash-existente")).thenReturn(true);

        RefreshToken resultado = refreshTokenService.validate("token-cru");

        assertThat(resultado).isEqualTo(tokenValido);
    }

    @Test
    void validateDeveRejeitarTokenRevogado() {
        RefreshToken tokenRevogado = RefreshToken.builder()
                .user(user)
                .tokenHash("hash-existente")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(true)
                .build();

        when(refreshTokenRepository.findAll()).thenReturn(List.of(tokenRevogado));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenService.validate("token-cru"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido ou expirado");
    }

    @Test
    void validateDeveRejeitarTokenExpirado() {
        RefreshToken tokenExpirado = RefreshToken.builder()
                .user(user)
                .tokenHash("hash-existente")
                .expiresAt(LocalDateTime.now().minusDays(1)) // já passou
                .revoked(false)
                .build();

        when(refreshTokenRepository.findAll()).thenReturn(List.of(tokenExpirado));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenService.validate("token-cru"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateDeveRejeitarQuandoNenhumHashBate() {
        when(refreshTokenRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> refreshTokenService.validate("token-inexistente"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeDeveMarcarTokenComoRevogado() {
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash("hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .build();

        refreshTokenService.revoke(token);

        assertThat(token.getRevoked()).isTrue();
    }
}
```

---

## 1.3 `AuthServiceTest`

`src/test/java/com/TadeuArtesanatos/demo/security/service/AuthServiceTest.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.security.dto.LoginRequestDto;
import com.TadeuArtesanatos.demo.security.dto.SignupRequestDto;
import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    private SignupRequestDto signupDto;

    @BeforeEach
    void setUp() {
        signupDto = new SignupRequestDto(
                "Maria", "Silva", "maria@teste.com", "senha12345",
                "(11) 91234-5678", "111.444.777-35" // CPF válido de teste (dígito verificador correto)
        );
    }

    @Test
    void signupDeveLancarExcecaoSeEmailJaExiste() {
        when(userRepository.existsByEmail(signupDto.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(signupDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email já cadastrado");

        verifyNoInteractions(customerRepository);
    }

    @Test
    void signupDeveLancarExcecaoSeCpfJaExiste() {
        when(userRepository.existsByEmail(signupDto.email())).thenReturn(false);
        when(customerRepository.existsByCpf("11144477735")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(signupDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CPF já cadastrado");

        verify(userRepository, never()).save(any());
    }

    @Test
    void signupComDadosValidosDeveCriarUserECustomerEEmitirTokens() {
        when(userRepository.existsByEmail(signupDto.email())).thenReturn(false);
        when(customerRepository.existsByCpf("11144477735")).thenReturn(false);
        when(passwordEncoder.encode(signupDto.password())).thenReturn("hash-senha");
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token-fake");
        when(jwtService.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(any(User.class))).thenReturn("refresh-token-fake");

        AuthService.AuthResult result = authService.signup(signupDto);

        verify(userRepository).save(argThat(u ->
                u.getEmail().equals(signupDto.email()) &&
                u.getPassword().equals("hash-senha") &&
                u.getRole() == Role.CLIENTE
        ));
        verify(customerRepository).save(argThat(c ->
                c.getFirstName().equals("Maria") &&
                c.getCpf().equals("11144477735") // normalizado, sem pontuação
        ));

        assertThat(result.tokens().accessToken()).isEqualTo("access-token-fake");
        assertThat(result.rawRefreshToken()).isEqualTo("refresh-token-fake");
    }

    @Test
    void loginComCredenciaisValidasDeveEmitirTokens() {
        LoginRequestDto loginDto = new LoginRequestDto("maria@teste.com", "senha12345");
        User user = User.builder().id(UUID.randomUUID()).email("maria@teste.com").role(Role.CLIENTE).build();

        when(userRepository.findByEmail("maria@teste.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token-fake");
        when(jwtService.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(user)).thenReturn("refresh-token-fake");

        AuthService.AuthResult result = authService.login(loginDto);

        verify(authenticationManager).authenticate(any());
        assertThat(result.tokens().accessToken()).isEqualTo("access-token-fake");
    }

    @Test
    void refreshDeveRevogarTokenAntigoEEmitirNovoParaOMesmoUsuario() {
        User user = User.builder().id(UUID.randomUUID()).email("maria@teste.com").role(Role.CLIENTE).build();
        RefreshToken tokenAntigo = RefreshToken.builder()
                .user(user)
                .tokenHash("hash-antigo")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .build();

        when(refreshTokenService.validate("refresh-cru")).thenReturn(tokenAntigo);
        when(jwtService.generateAccessToken(user)).thenReturn("novo-access-token");
        when(jwtService.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.issue(user)).thenReturn("novo-refresh-token");

        AuthService.AuthResult result = authService.refresh("refresh-cru");

        verify(refreshTokenService).revoke(tokenAntigo);
        assertThat(result.tokens().accessToken()).isEqualTo("novo-access-token");
        assertThat(result.rawRefreshToken()).isEqualTo("novo-refresh-token");
    }

    @Test
    void logoutDeveRevogarORefreshToken() {
        RefreshToken token = RefreshToken.builder().build();
        when(refreshTokenService.validate("refresh-cru")).thenReturn(token);

        authService.logout("refresh-cru");

        verify(refreshTokenService).revoke(token);
    }
}
```

**Nota sobre o CPF de teste:** `111.444.777-35` é um CPF que passa na validação de dígito verificador (padrão amplamente usado em ambientes de teste — nunca corresponde a uma pessoa real). Se o `@CPF` do Hibernate Validator rejeitar, gere outro em qualquer validador online.

---

# PARTE 2 — Teste de integração (Testcontainers + MockMvc)

Sobe o contexto Spring completo e um Postgres real em container. Roda o fluxo inteiro: signup → endpoint protegido → sem token → refresh → logout → refresh de novo (deve falhar).

`src/test/java/com/TadeuArtesanatos/demo/security/AuthIntegrationTest.java`

```java
package com.TadeuArtesanatos.demo.security;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fluxoCompletoDeAutenticacaoDeveFuncionar() throws Exception {
        String signupPayload = objectMapper.writeValueAsString(Map.of(
                "firstName", "João",
                "lastName", "Pereira",
                "email", "joao.integracao@teste.com",
                "password", "senhaSegura123",
                "phone", "(11) 98888-7777",
                "cpf", "111.444.777-35"
        ));

        // 1. Signup
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(signupPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andReturn();

        String accessToken = extractAccessToken(signupResult);
        String refreshCookie = signupResult.getResponse().getCookie("refresh_token").getValue();

        // 2. Endpoint protegido COM token — deve passar
        mockMvc.perform(get("/api/customers/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("joao.integracao@teste.com"));

        // 3. Endpoint protegido SEM token — deve bloquear
        mockMvc.perform(get("/api/customers/me"))
                .andExpect(status().isUnauthorized());

        // 4. Refresh — deve emitir novo access token
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("refresh_token"))
                .andReturn();

        String novoAccessToken = extractAccessToken(refreshResult);
        assertThat(novoAccessToken).isNotEqualTo(accessToken);

        String novoRefreshCookie = refreshResult.getResponse().getCookie("refresh_token").getValue();

        // 5. Logout — revoga o refresh token atual
        mockMvc.perform(post("/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", novoRefreshCookie)))
                .andExpect(status().isNoContent());

        // 6. Tentar usar o refresh token já revogado — deve falhar
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", novoRefreshCookie)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupComEmailDuplicadoDeveRetornar400() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "firstName", "Ana",
                "lastName", "Costa",
                "email", "duplicado@teste.com",
                "password", "senhaSegura123",
                "phone", "(11) 97777-6666",
                "cpf", "111.444.777-35"
        ));

        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated());

        // segunda tentativa com o mesmo email
        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(payload.replace("Costa", "Souza")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginComSenhaErradaDeveFalhar() throws Exception {
        String signupPayload = objectMapper.writeValueAsString(Map.of(
                "firstName", "Carlos",
                "lastName", "Lima",
                "email", "carlos.login@teste.com",
                "password", "senhaCorreta123",
                "phone", "(11) 96666-5555",
                "cpf", "529.982.247-25" // outro CPF válido de teste
        ));

        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(signupPayload))
                .andExpect(status().isCreated());

        String loginErrado = objectMapper.writeValueAsString(Map.of(
                "email", "carlos.login@teste.com",
                "password", "senhaErrada"
        ));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(loginErrado))
                .andExpect(status().is4xxClientError());
    }

    private String extractAccessToken(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
```

**Nota importante sobre `@Import(TestcontainersConfiguration.class)`:** o projeto já tem essa classe (`TestcontainersConfiguration.java`, visível na estrutura do projeto), gerada automaticamente pelo Spring Initializr quando você marcou "Testcontainers" — ela já sobe um Postgres real em container e configura o `DataSource` de teste sozinha. Não precisa escrever nada de configuração de container aqui, só importar.

**Nota sobre `status().is4xxClientError()` no teste de senha errada:** deixei genérico porque, dependendo de como o `AuthenticationManager` propaga a falha (`BadCredentialsException` não tratada especificamente no `GlobalExceptionHandler` ainda), pode cair em 401 ou 403 dependendo da configuração exata do Spring Security. Se quiser um assert mais preciso, precisa adicionar um `@ExceptionHandler` específico pra `BadCredentialsException` no `GlobalExceptionHandler`, retornando 401 explícito — posso te ajudar com isso se decidir fechar esse detalhe.

**Atualizado pelo Miguel:** o `GlobalExceptionHandler` já ganhou um `@ExceptionHandler(AuthenticationException.class)` retornando 401 explícito (`BadCredentialsException` é subclasse de `AuthenticationException`, então já cai nele). Se/quando os testes deste módulo forem reescritos, `loginComSenhaErradaDeveFalhar` pode trocar `status().is4xxClientError()` por `status().isUnauthorized()`, que agora é garantido.

---

## Pendências pra rodar isso de verdade

1. Confirmar se `mockito-core`/`mockito-junit-jupiter` estão no classpath (ver aviso no topo).
2. `TestcontainersConfiguration` precisa estar configurada corretamente (deve já estar, já que veio pronta do Initializr) — se o teste de integração não conseguir subir o Postgres, o erro aparece logo no início, geralmente relacionado a Docker não estar rodando na sua máquina (Testcontainers precisa do Docker ativo pra criar o container).
3. Os testes de integração usam Flyway de verdade (todas as migrations rodam no Postgres do container) — se `V4__create_table_refresh_tokens.sql` ainda não estiver criada/correta, o teste vai falhar na inicialização do contexto, não no teste em si.
4. Rodar via `mvn test` ou pela IDE (botão de play verde do JUnit) — os testes de integração são mais lentos (sobem container Docker), então rode os unitários primeiro pra feedback rápido durante o desenvolvimento.
