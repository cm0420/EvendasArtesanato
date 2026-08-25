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
