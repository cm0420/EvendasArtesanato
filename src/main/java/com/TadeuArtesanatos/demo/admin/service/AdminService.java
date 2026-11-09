package com.TadeuArtesanatos.demo.admin.service;

import com.TadeuArtesanatos.demo.admin.dto.*;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderItemRepository;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.payment.dto.PaymentResponseDto;
import com.TadeuArtesanatos.demo.payment.mapper.PaymentMapper;
import com.TadeuArtesanatos.demo.payment.repository.PaymentRepository;
import com.TadeuArtesanatos.demo.stock.entity.StockAdjustment;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final StockService stockService;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;

    // ---- Ajuste de estoque ----

    @Transactional
    public StockAdjustmentResponseDto adjustStock(UUID pieceId, Integer delta, String reason, String adminEmail) {
        StockAdjustment adjustment = stockService.adjustStock(pieceId, delta, reason, adminEmail);

        return new StockAdjustmentResponseDto(
                adjustment.getId(),
                adjustment.getPiece().getId(),
                adjustment.getDelta(),
                adjustment.getPiece().getStock(),
                adjustment.getReason(),
                adjustment.getAdjustedByEmail(),
                adjustment.getCreatedAt()
        );
    }

    // ---- Detalhe consolidado de pedido ----

    @Transactional(readOnly = true)
    public AdminOrderDetailResponseDto getOrderDetail(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        Customer customer = order.getCustomer();

        List<PaymentResponseDto> payments = paymentRepository.findAllByOrderId(orderId).stream()
                .map(paymentMapper::toResponseDto)
                .toList();

        OrderResponseDto orderDto = orderMapper.toResponseDto(order);

        return new AdminOrderDetailResponseDto(
                orderDto,
                customer.getFirstName() + " " + customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                payments
        );
    }

    // ---- Relatórios ----

    @Transactional(readOnly = true)
    public SalesReportResponseDto getSalesReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

        long count = orderRepository.countByPaidAtGreaterThanEqualAndPaidAtLessThan(start, endExclusive);
        BigDecimal total = orderRepository.sumTotalPaidBetween(start, endExclusive);

        return new SalesReportResponseDto(startDate, endDate, count, total);
    }

    @Transactional(readOnly = true)
    public List<BestSellingPieceResponseDto> getBestSellingPieces(int limit) {
        return orderItemRepository.findBestSellingPieces(PageRequest.of(0, limit)).stream()
                .map(row -> new BestSellingPieceResponseDto(
                        (UUID) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getPendingShipmentOrders(Pageable pageable) {
        return orderRepository.findAllByStatus(OrderStatus.PAGO, pageable)
                .map(orderMapper::toResponseDto);
    }
}
