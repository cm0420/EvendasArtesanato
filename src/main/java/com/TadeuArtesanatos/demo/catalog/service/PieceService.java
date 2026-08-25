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
