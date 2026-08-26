package com.TadeuArtesanatos.demo.stock.controller;

import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/stock/reservations")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> reserve(@Valid @RequestBody ReserveStockRequestDto dto) {
        StockReservationResponseDto created = stockService.reserve(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.findById(id));
    }

    @PostMapping(value = "/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> confirm(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.confirm(id));
    }

    @PostMapping(value = "/{id}/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockReservationResponseDto> release(@PathVariable UUID id) {
        return ResponseEntity.ok(stockService.release(id));
    }
}
