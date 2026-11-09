package com.TadeuArtesanatos.demo.admin.controller;

import com.TadeuArtesanatos.demo.admin.dto.*;
import com.TadeuArtesanatos.demo.admin.service.AdminService;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping(value = "/pieces/{id}/stock-adjustment",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockAdjustmentResponseDto> adjustStock(
            @AuthenticationPrincipal User adminUser,
            @PathVariable UUID id,
            @Valid @RequestBody StockAdjustmentRequestDto dto
    ) {
        StockAdjustmentResponseDto result = adminService.adjustStock(id, dto.delta(), dto.reason(), adminUser.getEmail());
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminOrderDetailResponseDto> getOrderDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getOrderDetail(id));
    }

    @GetMapping(value = "/reports/sales", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SalesReportResponseDto> getSalesReport(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate
    ) {
        return ResponseEntity.ok(adminService.getSalesReport(startDate, endDate));
    }

    @GetMapping(value = "/reports/sales-daily", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DailySalesResponseDto>> getDailySales(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate
    ) {
        return ResponseEntity.ok(adminService.getDailySales(startDate, endDate));
    }

    @GetMapping(value = "/reports/orders-by-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<OrderStatusCountResponseDto>> getOrdersByStatus() {
        return ResponseEntity.ok(adminService.getOrdersByStatus());
    }

    @GetMapping(value = "/reports/best-selling-pieces", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BestSellingPieceResponseDto>> getBestSellingPieces(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(adminService.getBestSellingPieces(limit));
    }

    @GetMapping(value = "/reports/pending-shipment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<OrderResponseDto>> getPendingShipmentOrders(Pageable pageable) {
        return ResponseEntity.ok(adminService.getPendingShipmentOrders(pageable));
    }
}
