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
