# Módulo `user` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.user`
> Baseado na modelagem já fechada (entity, repository, dto, service, controller, migration).
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## 1. Enum `Role`

`user/entity/Role.java`

```java
package com.TadeuArtesanatos.demo.user.entity;

public enum Role {
    ADMIN,
    CLIENTE
}
```

---

## 2. Entity `User`

`user/entity/User.java`

```java
package com.TadeuArtesanatos.demo.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio da entidade ----

    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.deletedAt == null;
    }

    // ---- Contrato UserDetails (Spring Security) ----

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return this.email;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return this.deletedAt == null;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return this.deletedAt == null;
    }
}
```

**Nota sobre Lombok:** usei `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder` em vez de `@Data`, porque `@Data` gera `equals()`/`hashCode()` em cima de todos os campos, o que é arriscado numa entity JPA (pode causar problema com proxies do Hibernate em coleções). Se seus outros projetos usam `@Data` em entity sem problema, pode trocar — só um ponto de atenção.

---

## 3. Repository

`user/repository/UserRepository.java`

```java
package com.TadeuArtesanatos.demo.user.repository;

import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<User> findAllByDeletedAtIsNull(Pageable pageable);

    Page<User> findAllByRoleAndDeletedAtIsNull(Role role, Pageable pageable);
}
```

---

## 4. DTOs

`user/dto/UserRequestDto.java`

```java
package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserRequestDto(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        String password,

        @NotNull
        String role
) {}
```

`user/dto/UserUpdateRequestDto.java`

```java
package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserUpdateRequestDto(

        @NotBlank
        @Email
        String email
) {}
```

`user/dto/UserRoleUpdateDto.java`

```java
package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.NotNull;

public record UserRoleUpdateDto(

        @NotNull
        String role
) {}
```

`user/dto/UserPasswordUpdateDto.java`

```java
package com.TadeuArtesanatos.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPasswordUpdateDto(

        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 64)
        String newPassword,

        @NotBlank
        String confirmPassword
) {}
```

`user/dto/UserResponseDto.java`

```java
package com.TadeuArtesanatos.demo.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponseDto(
        UUID id,
        String email,
        String role,
        LocalDateTime createdAt
) {}
```

---

## 5. Mapper (MapStruct)

`user/mapper/UserMapper.java`

```java
package com.TadeuArtesanatos.demo.user.mapper;

import com.TadeuArtesanatos.demo.user.dto.UserResponseDto;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponseDto toResponseDto(User user);
}
```

`role` é enum e `UserResponseDto.role` é `String` — o MapStruct converte automaticamente via `.name()`, não precisa de método manual.

Criação (`UserRequestDto → User`) e atualização (`UserUpdateRequestDto → User`) ficam com lógica manual no Service, porque envolvem regra de negócio (hash de senha, checagem de duplicidade) que não é mapeamento puro — não faz sentido colocar isso no mapper.

---

## 6. Service

`user/service/UserService.java`

```java
package com.TadeuArtesanatos.demo.user.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.user.dto.*;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.mapper.UserMapper;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponseDto createUser(UserRequestDto dto) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        User user = User.builder()
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .role(Role.valueOf(dto.role()))
                .build();

        User saved = userRepository.save(user);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponseDto> listUsers(Pageable pageable, Role role) {
        Page<User> page = (role != null)
                ? userRepository.findAllByRoleAndDeletedAtIsNull(role, pageable)
                : userRepository.findAllByDeletedAtIsNull(pageable);

        return page.map(userMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public UserResponseDto findById(UUID id) {
        User user = getActiveUserOrThrow(id);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto dto) {
        User user = getActiveUserOrThrow(id);

        boolean emailPertenceAOutroUsuario = userRepository.findByEmail(dto.email())
                .map(existing -> !existing.getId().equals(id))
                .orElse(false);

        if (emailPertenceAOutroUsuario) {
            throw new IllegalArgumentException("Email já está em uso por outro usuário");
        }

        user.setEmail(dto.email());

        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateRole(UUID id, UserRoleUpdateDto dto) {
        User user = getActiveUserOrThrow(id);
        user.setRole(Role.valueOf(dto.role()));
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public void changePassword(UUID id, UserPasswordUpdateDto dto) {
        User user = getActiveUserOrThrow(id);

        if (!passwordEncoder.matches(dto.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }

        if (!dto.newPassword().equals(dto.confirmPassword())) {
            throw new IllegalArgumentException("Confirmação de senha não confere");
        }

        user.setPassword(passwordEncoder.encode(dto.newPassword()));
    }

    @Transactional
    public void softDeleteUser(UUID id) {
        User user = getActiveUserOrThrow(id);
        user.softDelete();
    }

    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    // ---- helper interno ----

    private User getActiveUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
```

**Nota:** `updateUser` e `changePassword` não chamam `userRepository.save()` explicitamente — como o método está `@Transactional` e a entity foi carregada dentro da mesma transação (`managed`/`persistent`), o Hibernate detecta a mudança nos campos (`dirty checking`) e persiste sozinho no commit. Se preferir deixar explícito por clareza, pode adicionar `userRepository.save(user)` no final de cada método — funciona igual, só um pouco mais verboso.

`ResourceNotFoundException` estou assumindo que mora em `common/exception` (módulo compartilhado citado no CLAUDE.md) — se ainda não existe, precisa criar antes (uma `RuntimeException` simples com `@ResponseStatus(HttpStatus.NOT_FOUND)`).

---

## 7. Controller

`user/controller/UserController.java`

```java
package com.TadeuArtesanatos.demo.user.controller;

import com.TadeuArtesanatos.demo.user.dto.*;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(
        value = "/api/users",
        produces = MediaType.APPLICATION_JSON_VALUE,
        consumes = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<UserResponseDto>> listUsers(
            Pageable pageable,
            @RequestParam(required = false) Role role
    ) {
        return ResponseEntity.ok(userService.listUsers(pageable, role));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserRequestDto dto) {
        UserResponseDto created = userService.createUser(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDto> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UserUpdateRequestDto dto
    ) {
        return ResponseEntity.ok(userService.updateUser(id, dto));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<UserResponseDto> updateRole(
            @PathVariable UUID id,
            @Valid @RequestBody UserRoleUpdateDto dto
    ) {
        return ResponseEntity.ok(userService.updateRole(id, dto));
    }

    @PatchMapping("/{id}/senha")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @Valid @RequestBody UserPasswordUpdateDto dto
    ) {
        userService.changePassword(id, dto);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDeleteUser(@PathVariable UUID id) {
        userService.softDeleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Modelo híbrido de autorização adotado:** regras simples (baseadas só em role, sem lógica condicional) saem do Controller e vão pro `SecurityConfig` centralizado, no módulo `security`. `@PreAuthorize` fica reservado só pro caso que realmente precisa de SpEL — comparar o `id` do path com o usuário autenticado (`/senha`), que não dá pra expressar só com matcher de URL.

Isso ainda é spec antecipada — só "liga" de verdade quando o módulo `security` existir, com `@EnableMethodSecurity` habilitado (necessário pro `@PreAuthorize` do `/senha` funcionar) e o filtro JWT populando o `SecurityContext`.

**Regras que devem entrar no `SecurityConfig` (módulo `security`, ainda não implementado):**

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET, "/api/users/*").hasRole("ADMIN")
    .requestMatchers(HttpMethod.POST, "/api/users").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PUT, "/api/users/*").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PATCH, "/api/users/*/role").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/api/users/*").hasRole("ADMIN")
    // /api/users/{id}/senha NÃO entra aqui — fica só no @PreAuthorize do controller,
    // pois a regra depende do id do path, não é fixa por role
    .anyRequest().authenticated()
)

**Nota sobre `@PreAuthorize`:** o `#id == authentication.principal.id` do endpoint de troca de senha só funciona quando o módulo `security` já estiver implementado (JWT preenchendo o `principal` com o `User` autenticado). Por enquanto, com `security` ainda não feito, essas anotações não têm efeito nenhum — ainda não existe cadeia de filtro protegendo nada. Vão "ativar" de verdade quando vocês montarem o `SecurityConfig`.

---

## 8. Migration

**Ordem das migrations:** `user` é a base do sistema — nenhum outro módulo referencia `users` de volta, mas `customer` referencia `users(id)` via FK física (`@OneToOne`). Por isso essa migration precisa ser a **V1**, rodando antes de qualquer outra. A migration de `customer` (`V2__create_table_customers.sql`, com a FK `user_id UUID NOT NULL UNIQUE REFERENCES users(id)`) só pode vir depois, senão o Postgres rejeita a criação da FK apontando pra uma tabela inexistente.

Ordem confirmada:
1. `V1__create_table_users.sql` (este módulo)
2. `V2__create_table_customers.sql` (módulo `customer`, ainda não implementado)
3. `V3__create_table_addresses.sql` (módulo `customer`, ainda não implementado)

`db/migration/V1__create_table_users.sql`

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_role  ON users (role);
```

---

## Pendências pra rodar isso de verdade

1. `ResourceNotFoundException` — se ainda não existe no `common/exception`, precisa criar antes do `UserService` compilar.
2. Bean de `PasswordEncoder` — precisa existir em algum lugar (`security/config/SecurityConfig` ou uma config mínima temporária) retornando `new BCryptPasswordEncoder()`, senão o Spring não injeta no `UserService`.
3. Enquanto `security` (JWT/filtros) não existir, os endpoints estão desprotegidos na prática — o Spring Security padrão pode até bloquear tudo com login básico automático (comportamento padrão da starter), o que pode atrapalhar teste manual via Postman/Insomnia até a config real existir.