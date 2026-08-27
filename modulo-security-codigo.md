# Módulo `security` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.security`
> Depende de `user` (autentica via `UserRepository`) e, no signup, de `customer` (cria o perfil junto).
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas (fechadas em conversa antes deste código)

- **Access token + refresh token.** Access token curto (ex: 15 min), no header `Authorization: Bearer`, guardado em memória pelo front (nunca `localStorage`). Refresh token mais longo (ex: 7 dias), entregue como cookie `HttpOnly` + `Secure` + `SameSite=Strict` — inacessível a JavaScript (mitiga XSS) e só trafega em HTTPS mesmo domínio (mitiga CSRF).
- **Refresh token persistido no banco** (tabela `refresh_tokens`), não só validado por assinatura — isso é o que permite revogar no logout. Um refresh token JWT "stateless" não dá pra invalidar antes da expiração; guardando no banco, o logout marca `revoked = true` e o token para de funcionar imediatamente.
- **Signup único** (`POST /auth/signup`): cria `User` e `Customer` na mesma transação — ou os dois existem, ou nenhum.

## ⚠️ Dependência nova necessária

O projeto ainda não tem biblioteca de JWT no `pom.xml`. Isso não é "aproveitar pra instalar outra coisa" — é parte inerente do módulo que você pediu pra implementar. Precisa adicionar:

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
<groupId>io.jsonwebtoken</groupId>
<artifactId>jjwt-impl</artifactId>
<version>0.12.6</version>
<scope>runtime</scope>
</dependency>
<dependency>
<groupId>io.jsonwebtoken</groupId>
<artifactId>jjwt-jackson</artifactId>
<version>0.12.6</version>
<scope>runtime</scope>
</dependency>
```

---

## 1. Entity `RefreshToken`

`security/entity/RefreshToken.java`

```java
package com.TadeuArtesanatos.demo.security.entity;

import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(name = "revoked", nullable = false)
    private Boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ---- Regras de negócio ----

    public boolean isValid() {
        return !revoked && expiresAt.isAfter(LocalDateTime.now());
    }

    public void revoke() {
        this.revoked = true;
    }
}
```

**Nota:** guardamos o **hash** do refresh token (`tokenHash`), não o valor puro — mesma lógica de nunca guardar senha em texto puro. Se o banco vazar, o token não pode ser reutilizado diretamente.

---

## 2. Repository

`security/repository/RefreshTokenRepository.java`

```java
package com.TadeuArtesanatos.demo.security.repository;

import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
```

---

## 3. DTOs

`security/dto/SignupRequestDto.java`

```java
package com.TadeuArtesanatos.demo.security.dto;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.br.CPF;

public record SignupRequestDto(

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        String password,

        @NotBlank
        @Pattern(regexp = "^\\(?\\d{2}\\)?[\\s-]?9?\\d{4}-?\\d{4}$", message = "Telefone inválido")
        String phone,

        @NotBlank
        @CPF(message = "CPF inválido")
        String cpf
) {}
```

`security/dto/LoginRequestDto.java`

```java
package com.TadeuArtesanatos.demo.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequestDto(

        @NotBlank
        @Email
        String email,

        @NotBlank
        String password
) {}
```

`security/dto/TokenResponseDto.java`

```java
package com.TadeuArtesanatos.demo.security.dto;

public record TokenResponseDto(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static TokenResponseDto of(String accessToken, long expiresIn) {
        return new TokenResponseDto(accessToken, "Bearer", expiresIn);
    }
}
```

O refresh token **não** aparece nesse DTO — ele vai só no cookie `HttpOnly`, nunca no corpo da resposta (se fosse no JSON, JavaScript conseguiria ler, perdendo a proteção do `HttpOnly`).

---

## 4. JwtService

`security/service/JwtService.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long accessTokenExpirationMs
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, String expectedEmail) {
        String email = extractEmail(token);
        return email.equals(expectedEmail) && !isExpired(token);
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }
}
```

---

## 5. RefreshTokenService

`security/service/RefreshTokenService.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.security.repository.RefreshTokenRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final long REFRESH_TOKEN_EXPIRATION_DAYS = 7;

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_EXPIRATION_DAYS))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public RefreshToken validate(String rawToken) {
        return refreshTokenRepository.findAll().stream()
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()))
                .filter(RefreshToken::isValid)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refresh token inválido ou expirado"));
    }

    @Transactional
    public void revoke(RefreshToken refreshToken) {
        refreshToken.revoke();
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
```

**Correção:** a versão original deste gabarito chamava `generateRawToken()` em `issue()` mas não trazia a implementação do método, apesar de já importar `SecureRandom` e `Base64` (que só fazem sentido junto de um método assim). Preenchido durante a implementação: 32 bytes aleatórios, codificados em Base64 URL-safe sem padding.

**Nota sobre `validate`:** varrer todos os tokens comparando hash um a um (`findAll().stream()...`) funciona, mas não escala — é `O(n)` no número total de refresh tokens ativos no sistema. Pra esse porte de projeto está OK por enquanto; se o volume crescer, a alternativa é guardar o token em texto puro só pra indexação (não pro segredo em si) ou usar um hash determinístico (SHA-256) em vez de BCrypt — SHA-256 permite `findByTokenHash` direto via índice, porque o mesmo input sempre gera o mesmo hash (diferente do BCrypt, que usa salt aleatório). Fica registrado como possível otimização futura, não implementada agora pra não fazer over-engineering cedo demais.

---

## 6. UserDetailsServiceImpl

`security/service/UserDetailsServiceImpl.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado"));
    }
}
```

Como `User` já implementa `UserDetails` diretamente (decisão fechada no módulo `user`), esse service só faz a busca — não precisa converter nada.

---

## 7. JwtAuthenticationFilter

`security/filter/JwtAuthenticationFilter.java`

```java
package com.TadeuArtesanatos.demo.security.filter;

import com.TadeuArtesanatos.demo.security.service.JwtService;
import com.TadeuArtesanatos.demo.security.service.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(HEADER_NAME);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        String email = jwtService.extractEmail(token);

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            if (jwtService.isTokenValid(token, userDetails.getUsername())) {
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()
                );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

---

## 8. SecurityConfig

`security/config/SecurityConfig.java`

```java
package com.TadeuArtesanatos.demo.security.config;

import com.TadeuArtesanatos.demo.security.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless + JWT: CSRF clássico não se aplica ao header Bearer
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // públicos
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // user — regras fixas por role (ver módulo user)
                        .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/users/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/role").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/users/*").hasRole("ADMIN")
                        // /api/users/*/senha fica de fora — regra condicional, resolvida via @PreAuthorize no controller

                        // customer — rotas /me autenticadas, rota admin fixa (ver módulo customer)
                        .requestMatchers("/api/customers/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/customers/*").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider(passwordEncoder()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000")); // trocar pelo domínio real do front em prod
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true); // obrigatório para o cookie HttpOnly do refresh token trafegar

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

**Correções feitas pelo Miguel em relação à versão original deste gabarito:**
- `authenticationProvider`: a versão original tinha um typo de digitação (`new 1DaoAuthenticationProvider()`, não compilava) e usava `new DaoAuthenticationProvider()` (construtor vazio) + `provider.setUserDetailsService(userDetailsService)`. Corrigido para o construtor `new DaoAuthenticationProvider(userDetailsService)`, que já recebe o `UserDetailsService` direto — evita o setter (marcado como deprecated em versões recentes do Spring Security).
- Rota pública `/swagger-ui.html` adicionada ao `permitAll()`, além de `/swagger-ui/**` e `/v3/api-docs/**` — cobre o entrypoint clássico do Swagger UI que algumas versões ainda usam além dos assets em `/swagger-ui/**`.

**Isso substitui** a config mínima de `PasswordEncoder` que você criou como gambiarra temporária pra destravar o `UserService` — pode remover aquela classe avulsa, o bean agora vive aqui, no lugar definitivo.

**Nota sobre CORS:** `allowedOrigins` está fixo em `localhost:3000` como placeholder — ajuste pro endereço real do seu front quando existir. `allowCredentials(true)` é obrigatório porque o refresh token viaja em cookie; sem isso, o navegador não envia o cookie em requisições cross-origin.

---

## 9. AuthService

`security/service/AuthService.java`

```java
package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.security.dto.LoginRequestDto;
import com.TadeuArtesanatos.demo.security.dto.SignupRequestDto;
import com.TadeuArtesanatos.demo.security.dto.TokenResponseDto;
import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public record AuthResult(TokenResponseDto tokens, String rawRefreshToken) {}

    @Transactional
    public AuthResult signup(SignupRequestDto dto) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        String cpfNormalizado = dto.cpf().replaceAll("\\D", "");
        if (customerRepository.existsByCpf(cpfNormalizado)) {
            throw new IllegalArgumentException("CPF já cadastrado");
        }

        User user = User.builder()
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .role(Role.CLIENTE)
                .build();
        userRepository.save(user);

        Customer customer = Customer.builder()
                .user(user)
                .firstName(dto.firstName())
                .lastName(dto.lastName())
                .email(dto.email())
                .phone(dto.phone())
                .cpf(cpfNormalizado)
                .build();
        customerRepository.save(customer);

        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginRequestDto dto) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.email(), dto.password())
        );

        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(() -> new IllegalArgumentException("Credenciais inválidas"));

        return issueTokens(user);
    }

    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        RefreshToken current = refreshTokenService.validate(rawRefreshToken);
        refreshTokenService.revoke(current); // rotação: o refresh usado não pode ser reusado

        return issueTokens(current.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        RefreshToken current = refreshTokenService.validate(rawRefreshToken);
        refreshTokenService.revoke(current);
    }

    private AuthResult issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = refreshTokenService.issue(user);

        TokenResponseDto tokenResponse = TokenResponseDto.of(
                accessToken, jwtService.getAccessTokenExpirationMs() / 1000
        );

        return new AuthResult(tokenResponse, rawRefreshToken);
    }
}
```

**Nota sobre rotação de refresh token:** a cada `/auth/refresh`, o token antigo é revogado e um novo é emitido. Isso limita o estrago se um refresh token vazar — ele só é válido até o próximo uso legítimo. É uma prática comum em APIs profissionais (OAuth2 chama isso de "refresh token rotation").

---

## 10. AuthController

`security/controller/AuthController.java`

```java
package com.TadeuArtesanatos.demo.security.controller;

import com.TadeuArtesanatos.demo.security.dto.LoginRequestDto;
import com.TadeuArtesanatos.demo.security.dto.SignupRequestDto;
import com.TadeuArtesanatos.demo.security.dto.TokenResponseDto;
import com.TadeuArtesanatos.demo.security.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
        value = "/auth",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final long REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60; // 7 dias

    private final AuthService authService;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponseDto> signup(@Valid @RequestBody SignupRequestDto dto) {
        AuthService.AuthResult result = authService.signup(dto);
        return respondWithTokens(result, HttpStatus.CREATED);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponseDto> login(@Valid @RequestBody LoginRequestDto dto) {
        AuthService.AuthResult result = authService.login(dto);
        return respondWithTokens(result, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponseDto> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME) String refreshToken
    ) {
        AuthService.AuthResult result = authService.refresh(refreshToken);
        return respondWithTokens(result, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME) String refreshToken,
            HttpServletResponse response
    ) {
        authService.logout(refreshToken);

        ResponseCookie expiredCookie = buildRefreshCookie("", 0);
        response.addHeader("Set-Cookie", expiredCookie.toString());

        return ResponseEntity.noContent().build();
    }

    // ---- helper ----

    private ResponseEntity<TokenResponseDto> respondWithTokens(AuthService.AuthResult result, HttpStatus status) {
        ResponseCookie refreshCookie = buildRefreshCookie(result.rawRefreshToken(), REFRESH_COOKIE_MAX_AGE_SECONDS);

        return ResponseEntity.status(status)
                .header("Set-Cookie", refreshCookie.toString())
                .body(result.tokens());
    }

    private ResponseCookie buildRefreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
```

**Como fica por profile:**

- `application.yml` (base) — não declara `app.cookie.secure`, então o default do `@Value("${app.cookie.secure:true}")` vale: `true`. Se um profile novo esquecer de declarar essa property, o comportamento seguro prevalece.
- `application-dev.yml` — adiciona:
  ```yaml
  app:
    cookie:
      secure: false
  ```
- `application-prod.yml` e `application-docker.yml` — **não** precisam declarar nada (herdam `true` do default), mas se preferir deixar explícito por clareza/auditoria, pode adicionar `app.cookie.secure: true` mesmo assim, sem efeito prático.

Nenhum `@PreAuthorize` nesse controller — todas as rotas `/auth/**` são públicas (`permitAll()` no `SecurityConfig`), faz sentido, já que é exatamente onde alguém sem token ainda chega.

---

## 11. Migration

`db/migration/V4__create_table_refresh_tokens.sql`

```sql
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES users(id),
    token_hash   VARCHAR(255) NOT NULL UNIQUE,
    expires_at   TIMESTAMP    NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
```

---

## Pendências pra rodar isso de verdade

1. **Adicionar as 3 dependências do JJWT no `pom.xml`** (seção do topo) — sem isso, nada compila.
2. **Remover a classe `SecurityConfig` mínima temporária** que você criou só pra expor o bean `PasswordEncoder` (que resolveu o erro de inicialização do `UserService`) — o bean agora vive no `SecurityConfig` completo deste módulo. Se ficarem as duas, vai dar conflito de bean duplicado.
3. **Rodar a migration `V4`** — cria a tabela `refresh_tokens`, que ainda não existe.
4. **Ajustar `allowedOrigins`** no CORS quando o front tiver domínio definido.
5. **Adicionar `app.cookie.secure: false` no `application-dev.yml`** — sem isso, o default `true` bloqueia o cookie em dev local sem HTTPS. **Atenção:** `application-dev.yml` já tem um bloco `app:` (com `payment`/`shipping`) — `cookie.secure` precisa ser mesclado dentro desse mesmo bloco, nunca um segundo `app:` separado no arquivo (YAML não faz merge de chaves de mapeamento duplicadas no mesmo nível; a segunda ocorrência sobrescreveria a primeira, apagando `payment`/`shipping`).
6. `ResourceNotFoundException`/`GlobalExceptionHandler` já existentes continuam cobrindo os erros — `IllegalArgumentException` (email/CPF duplicado, refresh inválido) já cai no handler que criamos. **Atualizado pelo Miguel:** adicionado `@ExceptionHandler(AuthenticationException.class)` no `GlobalExceptionHandler`, retornando 401 com mensagem "Credenciais inválidas" — cobre o caso de login com senha errada (o `AuthenticationManager.authenticate()` lança `BadCredentialsException`, que é subclasse de `AuthenticationException`), que antes não tinha handler específico.