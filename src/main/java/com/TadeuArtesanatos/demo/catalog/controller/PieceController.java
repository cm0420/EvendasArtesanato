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
