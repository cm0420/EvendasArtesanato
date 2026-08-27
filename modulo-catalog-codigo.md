# Módulo `catalog` — Código de referência

> Pacote base: `com.TadeuArtesanatos.demo.catalog`
> Não depende de nenhum outro módulo de domínio — é a base que `stock` e `order` vão referenciar depois.
> Use isso como referência/gabarito — tente reescrever com suas próprias mãos antes de copiar direto.

---

## Decisões aplicadas (fechadas em conversa antes deste código)

- **Categoria N:N com Peça** — uma peça pode ter várias categorias, e vice-versa.
- **Fotos como lista de URLs** — `@ElementCollection`, tabela própria (`piece_images`), sem infraestrutura de upload real por enquanto (fica pra quando existir armazenamento de arquivo definido — S3, Cloudinary, etc.).
- **Preço do conjunto é fixo**, definido pelo admin — não calculado a partir das peças.
- **Estoque bruto (`stock`) vive na `Piece`** — é o campo que o CRUD do admin edita. O módulo `stock` (próximo) vai construir a lógica de **reserva com expiração e lock pessimista** em cima dessa mesma coluna, via import cruzado — não duplica o dado, só adiciona comportamento.
- **Estoque do conjunto é sempre calculado**, nunca persistido — é o mínimo entre o `stock` das peças componentes, calculado em tempo de consulta (é exatamente a regra já fechada no `CLAUDE.md`: "o conjunto não tem estoque próprio").
- **Navegação do catálogo é pública** (`GET`s sem autenticação) — não faz sentido exigir login só pra ver produtos; a conta obrigatória vale pro checkout, não pra navegação. CRUD (`POST`/`PUT`/`DELETE`) é `ADMIN`.

---

## 1. Entity `Category`

`catalog/entity/Category.java`

```java
package com.TadeuArtesanatos.demo.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;
}
```

**Nota:** sem soft delete aqui — categoria não tem valor histórico como peça (não aparece em pedido antigo diretamente). Se uma categoria com peças vinculadas for excluída, o vínculo N:N some (FK com `ON DELETE CASCADE` só na tabela de junção, nunca apagando peça nenhuma).

---

## 2. Entity `Piece` (Peça)

`catalog/entity/Piece.java`

```java
package com.TadeuArtesanatos.demo.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "pieces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Piece {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "piece_images", joinColumns = @JoinColumn(name = "piece_id"))
    @Column(name = "url", nullable = false)
    @OrderColumn(name = "display_order")
    private List<String> imageUrls = new ArrayList<>();

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "piece_categories",
            joinColumns = @JoinColumn(name = "piece_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new HashSet<>();

    @Builder.Default
    @ManyToMany(mappedBy = "pieces")
    private Set<PieceSet> sets = new HashSet<>();

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

    public boolean isInStock() {
        return this.stock != null && this.stock > 0;
    }
}
```

**Nota sobre `sets` (`mappedBy`):** o lado "dono" da relação N:N fica em `PieceSet` (mais natural pensar "o conjunto agrupa peças" do que o contrário). Aqui em `Piece` é só o lado inverso, pra navegação (`piece.getSets()`), sem gerar tabela de junção duplicada.

---

## 3. Entity `PieceSet` (Conjunto/Kit)

`catalog/entity/PieceSet.java`

```java
package com.TadeuArtesanatos.demo.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "piece_sets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PieceSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "set_pieces",
            joinColumns = @JoinColumn(name = "set_id"),
            inverseJoinColumns = @JoinColumn(name = "piece_id")
    )
    private Set<Piece> pieces = new HashSet<>();

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

    /**
     * Estoque do conjunto NUNCA é persistido — é sempre o mínimo entre
     * as peças componentes, calculado em tempo real (regra fechada no CLAUDE.md).
     * Peça inativa (soft-deleted) conta como 0, tirando o conjunto de estoque.
     */
    public int calculateAvailableStock() {
        return pieces.stream()
                .mapToInt(piece -> piece.isActive() ? piece.getStock() : 0)
                .min()
                .orElse(0);
    }
}
```

---

## 4. Repositories

`catalog/repository/CategoryRepository.java`

```java
package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByName(String name);

    boolean existsByName(String name);
}
```

`catalog/repository/PieceRepository.java`

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

**Adicionado pelo módulo `stock`**: o método `findByIdForUpdate` (e os imports/anotações que ele exige) não existia na versão original deste gabarito — foi acrescentado quando o módulo `stock` precisou de lock pessimista sobre `Piece`. Ver `modulo-stock-codigo.md`. (A nota anterior no `.md` do `stock` dizia que isso já estava sincronizado aqui — não estava; corrigido nesta sessão de auditoria.)

`catalog/repository/PieceSetRepository.java`

```java
package com.TadeuArtesanatos.demo.catalog.repository;

import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PieceSetRepository extends JpaRepository<PieceSet, UUID> {

    Page<PieceSet> findAllByDeletedAtIsNull(Pageable pageable);
}
```

---

## 5. DTOs

`catalog/dto/CategoryRequestDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryRequestDto(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 255)
        String description
) {}
```

`catalog/dto/CategoryResponseDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import java.util.UUID;

public record CategoryResponseDto(
        UUID id,
        String name,
        String description
) {}
```

`catalog/dto/PieceRequestDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PieceRequestDto(

        @NotBlank
        @Size(max = 150)
        String name,

        String description,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal price,

        @NotNull
        @Min(0)
        Integer stock,

        List<String> imageUrls,

        Set<UUID> categoryIds
) {}
```

`catalog/dto/PieceResponseDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PieceResponseDto(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        boolean inStock,
        List<String> imageUrls,
        List<CategoryResponseDto> categories,
        LocalDateTime createdAt
) {}
```

`catalog/dto/PieceSetRequestDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record PieceSetRequestDto(

        @NotBlank
        @Size(max = 150)
        String name,

        String description,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal price,

        @NotEmpty(message = "Um conjunto precisa de pelo menos uma peça")
        Set<UUID> pieceIds
) {}
```

`catalog/dto/PieceSetResponseDto.java`

```java
package com.TadeuArtesanatos.demo.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PieceSetResponseDto(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        int availableStock,
        List<PieceResponseDto> pieces,
        LocalDateTime createdAt
) {}
```

---

## 6. Mappers (MapStruct)

`catalog/mapper/CategoryMapper.java`

```java
package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.CategoryResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Category;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponseDto toResponseDto(Category category);
}
```

`catalog/mapper/PieceMapper.java`

```java
package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.PieceResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = CategoryMapper.class)
public interface PieceMapper {

    @Mapping(target = "inStock", expression = "java(piece.isInStock())")
    PieceResponseDto toResponseDto(Piece piece);
}
```

`catalog/mapper/PieceSetMapper.java`

```java
package com.TadeuArtesanatos.demo.catalog.mapper;

import com.TadeuArtesanatos.demo.catalog.dto.PieceSetResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = PieceMapper.class)
public interface PieceSetMapper {

    @Mapping(target = "availableStock", expression = "java(pieceSet.calculateAvailableStock())")
    PieceSetResponseDto toResponseDto(PieceSet pieceSet);
}
```

**Nota sobre `expression = "java(...)"`:** é a forma do MapStruct chamar um método que não é um getter simples — aqui, `isInStock()` e `calculateAvailableStock()` são regra de negócio da própria entidade, não um campo. O MapStruct gera a chamada direta no código compilado.

Criação/atualização (`PieceRequestDto → Piece`, `PieceSetRequestDto → PieceSet`) ficam manuais no Service — precisam resolver `categoryIds`/`pieceIds` (buscar as entidades reais no banco), que não é mapeamento direto de campo.

---

## 7. Services

`catalog/service/CategoryService.java`

```java
package com.TadeuArtesanatos.demo.catalog.service;

import com.TadeuArtesanatos.demo.catalog.dto.CategoryRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.CategoryResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Category;
import com.TadeuArtesanatos.demo.catalog.mapper.CategoryMapper;
import com.TadeuArtesanatos.demo.catalog.repository.CategoryRepository;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Transactional
    public CategoryResponseDto create(CategoryRequestDto dto) {
        if (categoryRepository.existsByName(dto.name())) {
            throw new IllegalArgumentException("Categoria já existe");
        }

        Category category = Category.builder()
                .name(dto.name())
                .description(dto.description())
                .build();

        return categoryMapper.toResponseDto(categoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDto> listAll() {
        return categoryRepository.findAll().stream()
                .map(categoryMapper::toResponseDto)
                .toList();
    }

    @Transactional
    public void delete(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada"));
        categoryRepository.delete(category);
    }
}
```

`catalog/service/PieceService.java`

```java
package com.TadeuArtesanatos.demo.catalog.service;

import com.TadeuArtesanatos.demo.catalog.dto.PieceRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.PieceResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Category;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.mapper.PieceMapper;
import com.TadeuArtesanatos.demo.catalog.repository.CategoryRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PieceService {

    private final PieceRepository pieceRepository;
    private final CategoryRepository categoryRepository;
    private final PieceMapper pieceMapper;

    @Transactional
    public PieceResponseDto create(PieceRequestDto dto) {
        Piece piece = Piece.builder()
                .name(dto.name())
                .description(dto.description())
                .price(dto.price())
                .stock(dto.stock())
                .imageUrls(dto.imageUrls() != null ? dto.imageUrls() : List.of())
                .categories(resolveCategories(dto.categoryIds()))
                .build();

        return pieceMapper.toResponseDto(pieceRepository.save(piece));
    }

    @Transactional(readOnly = true)
    public Page<PieceResponseDto> list(Pageable pageable, UUID categoryId) {
        Page<Piece> page = (categoryId != null)
                ? pieceRepository.findAllByCategories_IdAndDeletedAtIsNull(categoryId, pageable)
                : pieceRepository.findAllByDeletedAtIsNull(pageable);

        return page.map(pieceMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public PieceResponseDto findById(UUID id) {
        return pieceMapper.toResponseDto(getActiveOrThrow(id));
    }

    @Transactional
    public PieceResponseDto update(UUID id, PieceRequestDto dto) {
        Piece piece = getActiveOrThrow(id);

        piece.setName(dto.name());
        piece.setDescription(dto.description());
        piece.setPrice(dto.price());
        piece.setStock(dto.stock());
        piece.setImageUrls(dto.imageUrls() != null ? dto.imageUrls() : List.of());
        piece.setCategories(resolveCategories(dto.categoryIds()));

        return pieceMapper.toResponseDto(piece);
    }

    @Transactional
    public void softDelete(UUID id) {
        Piece piece = getActiveOrThrow(id);
        piece.softDelete();
    }

    // ---- helpers internos ----

    private Piece getActiveOrThrow(UUID id) {
        return pieceRepository.findById(id)
                .filter(Piece::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));
    }

    private Set<Category> resolveCategories(Set<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return new HashSet<>();
        }

        Set<Category> categories = new HashSet<>(categoryRepository.findAllById(categoryIds));

        if (categories.size() != categoryIds.size()) {
            throw new IllegalArgumentException("Uma ou mais categorias informadas não existem");
        }

        return categories;
    }
}
```

`catalog/service/PieceSetService.java`

```java
package com.TadeuArtesanatos.demo.catalog.service;

import com.TadeuArtesanatos.demo.catalog.dto.PieceSetRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.PieceSetResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import com.TadeuArtesanatos.demo.catalog.mapper.PieceSetMapper;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceSetRepository;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PieceSetService {

    private final PieceSetRepository pieceSetRepository;
    private final PieceRepository pieceRepository;
    private final PieceSetMapper pieceSetMapper;

    @Transactional
    public PieceSetResponseDto create(PieceSetRequestDto dto) {
        PieceSet pieceSet = PieceSet.builder()
                .name(dto.name())
                .description(dto.description())
                .price(dto.price())
                .pieces(resolvePieces(dto.pieceIds()))
                .build();

        return pieceSetMapper.toResponseDto(pieceSetRepository.save(pieceSet));
    }

    @Transactional(readOnly = true)
    public Page<PieceSetResponseDto> list(Pageable pageable) {
        return pieceSetRepository.findAllByDeletedAtIsNull(pageable)
                .map(pieceSetMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public PieceSetResponseDto findById(UUID id) {
        return pieceSetMapper.toResponseDto(getActiveOrThrow(id));
    }

    @Transactional
    public PieceSetResponseDto update(UUID id, PieceSetRequestDto dto) {
        PieceSet pieceSet = getActiveOrThrow(id);

        pieceSet.setName(dto.name());
        pieceSet.setDescription(dto.description());
        pieceSet.setPrice(dto.price());
        pieceSet.setPieces(resolvePieces(dto.pieceIds()));

        return pieceSetMapper.toResponseDto(pieceSet);
    }

    @Transactional
    public void softDelete(UUID id) {
        PieceSet pieceSet = getActiveOrThrow(id);
        pieceSet.softDelete();
    }

    // ---- helpers internos ----

    private PieceSet getActiveOrThrow(UUID id) {
        return pieceSetRepository.findById(id)
                .filter(PieceSet::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Conjunto não encontrado"));
    }

    private Set<Piece> resolvePieces(Set<UUID> pieceIds) {
        Set<Piece> pieces = new HashSet<>(pieceRepository.findAllById(pieceIds));

        if (pieces.size() != pieceIds.size()) {
            throw new IllegalArgumentException("Uma ou mais peças informadas não existem");
        }

        boolean temPecaInativa = pieces.stream().anyMatch(piece -> !piece.isActive());
        if (temPecaInativa) {
            throw new IllegalArgumentException("Uma ou mais peças informadas estão inativas (removidas da loja)");
        }

        return pieces;
    }
}
```

---

## 8. Controllers

`catalog/controller/CategoryController.java`

```java
package com.TadeuArtesanatos.demo.catalog.controller;

import com.TadeuArtesanatos.demo.catalog.dto.CategoryRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.CategoryResponseDto;
import com.TadeuArtesanatos.demo.catalog.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CategoryResponseDto>> listAll() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CategoryResponseDto> create(@Valid @RequestBody CategoryRequestDto dto) {
        CategoryResponseDto created = categoryService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

`catalog/controller/PieceController.java`

```java
package com.TadeuArtesanatos.demo.catalog.controller;

import com.TadeuArtesanatos.demo.catalog.dto.PieceRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.PieceResponseDto;
import com.TadeuArtesanatos.demo.catalog.service.PieceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/pieces")
@RequiredArgsConstructor
public class PieceController {

    private final PieceService pieceService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<PieceResponseDto>> list(
            Pageable pageable,
            @RequestParam(required = false) UUID categoryId
    ) {
        return ResponseEntity.ok(pieceService.list(pageable, categoryId));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(pieceService.findById(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceResponseDto> create(@Valid @RequestBody PieceRequestDto dto) {
        PieceResponseDto created = pieceService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceResponseDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody PieceRequestDto dto
    ) {
        return ResponseEntity.ok(pieceService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDelete(@PathVariable UUID id) {
        pieceService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
```

`catalog/controller/PieceSetController.java`

```java
package com.TadeuArtesanatos.demo.catalog.controller;

import com.TadeuArtesanatos.demo.catalog.dto.PieceSetRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.PieceSetResponseDto;
import com.TadeuArtesanatos.demo.catalog.service.PieceSetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/sets")
@RequiredArgsConstructor
public class PieceSetController {

    private final PieceSetService pieceSetService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<PieceSetResponseDto>> list(Pageable pageable) {
        return ResponseEntity.ok(pieceSetService.list(pageable));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceSetResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(pieceSetService.findById(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceSetResponseDto> create(@Valid @RequestBody PieceSetRequestDto dto) {
        PieceSetResponseDto created = pieceSetService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PieceSetResponseDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody PieceSetRequestDto dto
    ) {
        return ResponseEntity.ok(pieceSetService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDelete(@PathVariable UUID id) {
        pieceSetService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Regras que devem entrar no `SecurityConfig`** (adicionar aos blocos já existentes de `user`/`customer`):

```java
// catálogo — navegação pública, CRUD só ADMIN
.requestMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
.requestMatchers(HttpMethod.POST, "/api/catalog/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.PUT, "/api/catalog/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/catalog/**").hasRole("ADMIN")
```

Nenhum `@PreAuthorize` nos controllers — todas as regras são fixas por método HTTP, cabem inteiras no `SecurityConfig` centralizado.

---

## 9. Migrations

**Ordem:** `V5` (categories) → `V6` (pieces) → `V7` (piece_images) → `V8` (piece_categories) → `V9` (piece_sets) → `V10` (set_pieces). Numeração seguindo depois de `V4` (refresh_tokens, já existente).

`db/migration/V5__create_table_categories.sql`

```sql
CREATE TABLE categories (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(100) NOT NULL UNIQUE,
    description   VARCHAR(255)
);
```

`db/migration/V6__create_table_pieces.sql`

```sql
CREATE TABLE pieces (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(150)   NOT NULL,
    description   TEXT,
    price         NUMERIC(10,2)  NOT NULL,
    stock         INTEGER        NOT NULL DEFAULT 0,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP
);

CREATE INDEX idx_pieces_deleted_at ON pieces (deleted_at);
```

`db/migration/V7__create_table_piece_images.sql`

```sql
CREATE TABLE piece_images (
    piece_id        UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    url             VARCHAR(500) NOT NULL,
    display_order   INTEGER NOT NULL,
    PRIMARY KEY (piece_id, display_order)
);
```

`db/migration/V8__create_table_piece_categories.sql`

```sql
CREATE TABLE piece_categories (
    piece_id      UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    category_id   UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (piece_id, category_id)
);
```

`db/migration/V9__create_table_piece_sets.sql`

```sql
CREATE TABLE piece_sets (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(150)   NOT NULL,
    description   TEXT,
    price         NUMERIC(10,2)  NOT NULL,
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP
);
```

`db/migration/V10__create_table_set_pieces.sql`

```sql
CREATE TABLE set_pieces (
    set_id        UUID NOT NULL REFERENCES piece_sets(id) ON DELETE CASCADE,
    piece_id      UUID NOT NULL REFERENCES pieces(id) ON DELETE CASCADE,
    PRIMARY KEY (set_id, piece_id)
);
```

---

## Pendências pra rodar isso de verdade

1. `ResourceNotFoundException` — mesma classe já usada nos outros módulos (`common/exception`).
2. Confirmar a numeração real das próximas migrations no seu projeto — assumi `V5` em diante partindo de `V4` (refresh_tokens), mas confirma o que já existe antes de criar os arquivos, pra não colidir.
