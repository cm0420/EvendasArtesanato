# Módulo `customer` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.customer`
> Depende do módulo `user` (FK física `@OneToOne`).
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisão de design aplicada aqui: rotas `/me` vs. `/{id}`

Diferente do `user` (onde a maioria dos endpoints é simplesmente "só ADMIN"), no `customer` quase tudo é "o próprio dono do perfil, ou ADMIN". Comparar isso via `@PreAuthorize` com SpEL exigiria buscar o `Customer` no banco só pra comparar `customer.getUser().getId()` com o usuário autenticado — complicado de expressar direto na anotação.

Solução adotada: dois grupos de rota:
- **`/api/customers/me/**`** — o cliente autenticado mexe no **próprio** perfil. Não precisa comparar nenhum id — o `Customer` é sempre resolvido a partir do usuário autenticado (`authentication.principal`). Acesso: `isAuthenticated()`.
- **`/api/customers/{id}`** — ADMIN olhando/gerenciando qualquer cliente pelo id. Regra fixa por role, então entra no `SecurityConfig` centralizado (modelo híbrido, mesmo padrão do `user`).

---

## 1. Entity `Customer`

`customer/entity/Customer.java`

```java
package com.TadeuArtesanatos.demo.customer.entity;

import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "cpf", nullable = false, unique = true, length = 11)
    private String cpf;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio da entidade ----

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.deletedAt == null;
    }

    public void addAddress(Address address) {
        this.addresses.add(address);
        address.setCustomer(this);
    }
}
```

---

## 2. Entity `Address`

`customer/entity/Address.java`

```java
package com.TadeuArtesanatos.demo.customer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "label", nullable = false, length = 50)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zip_code", nullable = false, length = 8)
    private String zipCode;

    @Column(name = "street", nullable = false)
    private String street;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "complement")
    private String complement;

    @Column(name = "neighborhood", nullable = false, length = 100)
    private String neighborhood;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 2)
    private String state;

    @Builder.Default
    @Column(name = "country", nullable = false, length = 50)
    private String country = "BR";

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    // ---- Regra de negócio da entidade ----

    public void markAsDefault() {
        this.isDefault = true;
    }
}
```

---

## 3. Repositories

`customer/repository/CustomerRepository.java`

```java
package com.TadeuArtesanatos.demo.customer.repository;

import com.TadeuArtesanatos.demo.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByCpf(String cpf);

    Optional<Customer> findByUserId(UUID userId);

    boolean existsByEmail(String email);

    boolean existsByCpf(String cpf);

    Page<Customer> findAllByDeletedAtIsNull(Pageable pageable);
}
```

`customer/repository/AddressRepository.java`

```java
package com.TadeuArtesanatos.demo.customer.repository;

import com.TadeuArtesanatos.demo.customer.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findAllByCustomerId(UUID customerId);

    Optional<Address> findByCustomerIdAndIsDefaultTrue(UUID customerId);
}
```

---

## 4. DTOs

`customer/dto/CustomerRequestDTO.java`

```java
package com.TadeuArtesanatos.demo.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.br.CPF;

public record CustomerRequestDTO(

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
        @Pattern(regexp = "^\\(?\\d{2}\\)?[\\s-]?9?\\d{4}-?\\d{4}$", message = "Telefone inválido")
        String phone,

        @NotBlank
        @CPF(message = "CPF inválido")
        String cpf
) {}
```

`customer/dto/CustomerUpdateRequestDTO.java`

```java
package com.TadeuArtesanatos.demo.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerUpdateRequestDTO(

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
        @Pattern(regexp = "^\\(?\\d{2}\\)?[\\s-]?9?\\d{4}-?\\d{4}$", message = "Telefone inválido")
        String phone
) {}
```

CPF não entra aqui — decisão pendente de confirmação sua (deixei como imutável por padrão, como conversamos).

**Nota sobre `@CPF` + coluna `VARCHAR(11)`:** a anotação `@CPF` valida o CPF formatado ou não (`123.456.789-00` ou `12345678900`, ambos passam). Como a coluna no banco fica travada em 11 caracteres (mais restritivo, mais seguro), o `CustomerService` normaliza o valor (remove pontuação) antes de persistir — a validação do dígito verificador roda sobre o valor original recebido, e só depois ele é limpo pra gravação.

`customer/dto/CustomerResponseDTO.java`

```java
package com.TadeuArtesanatos.demo.customer.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CustomerResponseDTO(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String cpf,
        List<AddressResponseDTO> addresses,
        LocalDateTime createdAt
) {}
```

`customer/dto/AddressRequestDTO.java`

```java
package com.TadeuArtesanatos.demo.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequestDTO(

        @NotBlank
        @Size(max = 50)
        String label,

        @NotBlank
        @Size(max = 100)
        String recipientName,

        @NotBlank
        @Pattern(regexp = "^\\(?\\d{2}\\)?[\\s-]?9?\\d{4}-?\\d{4}$", message = "Telefone inválido")
        String recipientPhone,

        @NotBlank
        @Pattern(regexp = "^\\d{8}$", message = "CEP deve conter 8 dígitos")
        String zipCode,

        @NotBlank
        String street,

        @NotBlank
        String number,

        String complement,

        @NotBlank
        String neighborhood,

        @NotBlank
        String city,

        @NotBlank
        @Size(min = 2, max = 2)
        String state,

        Boolean isDefault
) {}
```

`customer/dto/AddressResponseDTO.java`

```java
package com.TadeuArtesanatos.demo.customer.dto;

import java.util.UUID;

public record AddressResponseDTO(
        UUID id,
        String label,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String street,
        String number,
        String complement,
        String neighborhood,
        String city,
        String state,
        String country,
        Boolean isDefault
) {}
```

---

## 5. Mapper (MapStruct)

`customer/mapper/CustomerMapper.java`

```java
package com.TadeuArtesanatos.demo.customer.mapper;

import com.TadeuArtesanatos.demo.customer.dto.AddressResponseDTO;
import com.TadeuArtesanatos.demo.customer.dto.CustomerResponseDTO;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerResponseDTO toResponseDTO(Customer customer);

    AddressResponseDTO toResponseDTO(Address address);
}
```

Criação e atualização (`CustomerRequestDTO → Customer`, `AddressRequestDTO → Address`) ficam manuais no Service — envolvem regra de negócio (checagem de duplicidade, vínculo com `User`, regra de `isDefault`), não é mapeamento puro.

---

## 6. Service

`customer/service/CustomerService.java`

```java
package com.TadeuArtesanatos.demo.customer.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.dto.*;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.mapper.CustomerMapper;
import com.TadeuArtesanatos.demo.customer.repository.AddressRepository;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final CustomerMapper customerMapper;

    @Transactional
    public CustomerResponseDTO createCustomer(CustomerRequestDTO dto, User authenticatedUser) {
        String cpfNormalizado = dto.cpf().replaceAll("\\D", "");

        if (customerRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }
        if (customerRepository.existsByCpf(cpfNormalizado)) {
            throw new IllegalArgumentException("CPF já cadastrado");
        }

        Customer customer = Customer.builder()
                .user(authenticatedUser)
                .firstName(dto.firstName())
                .lastName(dto.lastName())
                .email(dto.email())
                .phone(dto.phone())
                .cpf(cpfNormalizado)
                .build();

        Customer saved = customerRepository.save(customer);
        return customerMapper.toResponseDTO(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponseDTO findOwnProfile(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return customerMapper.toResponseDTO(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponseDTO findById(UUID id) {
        Customer customer = getActiveOrThrow(id);
        return customerMapper.toResponseDTO(customer);
    }

    @Transactional
    public CustomerResponseDTO updateOwnProfile(User authenticatedUser, CustomerUpdateRequestDTO dto) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return applyUpdate(customer, dto);
    }

    @Transactional
    public void softDeleteOwnProfile(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        customer.softDelete();
    }

    @Transactional
    public AddressResponseDTO addOwnAddress(User authenticatedUser, AddressRequestDTO dto) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return persistAddress(customer, dto);
    }

    @Transactional
    public List<AddressResponseDTO> listOwnAddresses(User authenticatedUser) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());
        return addressRepository.findAllByCustomerId(customer.getId())
                .stream()
                .map(customerMapper::toResponseDTO)
                .toList();
    }

    @Transactional
    public void removeOwnAddress(User authenticatedUser, UUID addressId) {
        Customer customer = getActiveByUserOrThrow(authenticatedUser.getId());

        Address address = addressRepository.findById(addressId)
                .filter(a -> a.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Endereço não encontrado"));

        addressRepository.delete(address);
    }

    // ---- helpers internos ----

    private CustomerResponseDTO applyUpdate(Customer customer, CustomerUpdateRequestDTO dto) {
        boolean emailPertenceAOutroCliente = customerRepository.findByEmail(dto.email())
                .map(existing -> !existing.getId().equals(customer.getId()))
                .orElse(false);

        if (emailPertenceAOutroCliente) {
            throw new IllegalArgumentException("Email já está em uso por outro cliente");
        }

        customer.setFirstName(dto.firstName());
        customer.setLastName(dto.lastName());
        customer.setEmail(dto.email());
        customer.setPhone(dto.phone());

        return customerMapper.toResponseDTO(customer);
    }

    private AddressResponseDTO persistAddress(Customer customer, AddressRequestDTO dto) {
        if (Boolean.TRUE.equals(dto.isDefault())) {
            addressRepository.findAllByCustomerId(customer.getId())
                    .forEach(existing -> existing.setIsDefault(false));
        }

        Address address = Address.builder()
                .label(dto.label())
                .recipientName(dto.recipientName())
                .recipientPhone(dto.recipientPhone())
                .zipCode(dto.zipCode())
                .street(dto.street())
                .number(dto.number())
                .complement(dto.complement())
                .neighborhood(dto.neighborhood())
                .city(dto.city())
                .state(dto.state())
                .isDefault(Boolean.TRUE.equals(dto.isDefault()))
                .build();

        customer.addAddress(address);
        Address saved = addressRepository.save(address);
        return customerMapper.toResponseDTO(saved);
    }

    private Customer getActiveOrThrow(UUID id) {
        return customerRepository.findById(id)
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente não encontrado"));
    }

    private Customer getActiveByUserOrThrow(UUID userId) {
        return customerRepository.findByUserId(userId)
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));
    }
}
```

**Nota:** os métodos `findOwnProfile`, `updateOwnProfile` etc. recebem `User authenticatedUser` como parâmetro — no Controller, esse `User` vem de `@AuthenticationPrincipal User user`, populado pelo filtro JWT (ainda não implementado). Enquanto `security` não existir, esse parâmetro não terá como ser preenchido de verdade — é spec antecipada, igual avisamos no módulo `user`.

---

## 7. Controller

`customer/controller/CustomerController.java`

```java
package com.TadeuArtesanatos.demo.customer.controller;

import com.TadeuArtesanatos.demo.customer.dto.*;
import com.TadeuArtesanatos.demo.customer.service.CustomerService;
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
@RequestMapping(
        value = "/api/customers",
        produces = MediaType.APPLICATION_JSON_VALUE,
        consumes = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    // ---- Rotas /me — o próprio cliente autenticado, sem comparação de id ----

    @PostMapping("/me")
    public ResponseEntity<CustomerResponseDTO> createOwnProfile(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody CustomerRequestDTO dto
    ) {
        CustomerResponseDTO created = customerService.createCustomer(dto, authenticatedUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerResponseDTO> findOwnProfile(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        return ResponseEntity.ok(customerService.findOwnProfile(authenticatedUser));
    }

    @PutMapping("/me")
    public ResponseEntity<CustomerResponseDTO> updateOwnProfile(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody CustomerUpdateRequestDTO dto
    ) {
        return ResponseEntity.ok(customerService.updateOwnProfile(authenticatedUser, dto));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> softDeleteOwnProfile(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        customerService.softDeleteOwnProfile(authenticatedUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/addresses")
    public ResponseEntity<AddressResponseDTO> addOwnAddress(
            @AuthenticationPrincipal User authenticatedUser,
            @Valid @RequestBody AddressRequestDTO dto
    ) {
        AddressResponseDTO created = customerService.addOwnAddress(authenticatedUser, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/me/addresses")
    public ResponseEntity<List<AddressResponseDTO>> listOwnAddresses(
            @AuthenticationPrincipal User authenticatedUser
    ) {
        return ResponseEntity.ok(customerService.listOwnAddresses(authenticatedUser));
    }

    @DeleteMapping("/me/addresses/{addressId}")
    public ResponseEntity<Void> removeOwnAddress(
            @AuthenticationPrincipal User authenticatedUser,
            @PathVariable UUID addressId
    ) {
        customerService.removeOwnAddress(authenticatedUser, addressId);
        return ResponseEntity.noContent().build();
    }

    // ---- Rota admin — qualquer cliente pelo id, regra fixa, vai no SecurityConfig ----

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponseDTO> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(customerService.findById(id));
    }
}
```

**Regras que devem entrar no `SecurityConfig` (módulo `security`, ainda não implementado):**

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/customers/me/**").authenticated()
    .requestMatchers(HttpMethod.GET, "/api/customers/*").hasRole("ADMIN")
    .anyRequest().authenticated()
)
```

Nenhum `@PreAuthorize` sobrando no Controller — como toda a lógica de "dono do recurso" foi resolvida pelo próprio desenho das rotas `/me` (sempre resolve o `Customer` a partir do usuário autenticado, nunca de um `id` na URL), não sobrou nenhuma regra condicional que precisasse de SpEL. Só a rota `/{id}` de ADMIN, que é regra fixa por role — cabe inteira no `SecurityConfig`.

---

## 8. Migrations

**Ordem confirmada:** `V1` (users, já existe) → `V2` (customers) → `V3` (addresses).

`db/migration/V2__create_table_customers.sql`

```sql
CREATE TABLE customers (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID NOT NULL UNIQUE REFERENCES users(id),
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    phone         VARCHAR(20)  NOT NULL,
    cpf           VARCHAR(11)  NOT NULL UNIQUE,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP
);

CREATE INDEX idx_customers_email ON customers (email);
CREATE INDEX idx_customers_cpf   ON customers (cpf);
```

`db/migration/V3__create_table_addresses.sql`

```sql
CREATE TABLE addresses (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id      UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    label            VARCHAR(50)  NOT NULL,
    recipient_name   VARCHAR(100) NOT NULL,
    recipient_phone  VARCHAR(20)  NOT NULL,
    zip_code         VARCHAR(8)   NOT NULL,
    street           VARCHAR(255) NOT NULL,
    number           VARCHAR(20)  NOT NULL,
    complement       VARCHAR(255),
    neighborhood     VARCHAR(100) NOT NULL,
    city             VARCHAR(100) NOT NULL,
    state            CHAR(2)      NOT NULL,
    country          VARCHAR(50)  NOT NULL DEFAULT 'BR',
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_addresses_customer_id ON addresses (customer_id);
```

---

## Pendências pra rodar isso de verdade

1. `ResourceNotFoundException` — mesma classe já usada no módulo `user` (`common/exception`).
2. `@AuthenticationPrincipal User authenticatedUser` só funciona depois que o filtro JWT (módulo `security`) estiver populando o `SecurityContext` com o `User`. Até lá, esses parâmetros chegam `null` em qualquer teste manual.
3. **Confirmar com você:** CPF fica mesmo fora do `CustomerUpdateRequestDTO` (imutável após cadastro)?
4. **Confirmar com você:** o endpoint `POST /api/customers/me` assume que o `User` já foi criado antes (via módulo `user` ou um fluxo de registro público que ainda não desenhamos) — o cadastro completo do cliente final (criar `User` + criar `Customer` em sequência) ainda não tem um endpoint único de "signup" pensado. Vale discutir isso quando chegarmos em `security`/fluxo de registro.
