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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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

    /** Máximo de dias por consulta: limita o tamanho da resposta e o custo da query. */
    static final long MAX_REPORT_DAYS = 366;

    @Transactional(readOnly = true)
    public List<DailySalesResponseDto> getDailySales(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("A data final deve ser igual ou posterior à inicial");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= MAX_REPORT_DAYS) {
            throw new IllegalArgumentException("O período máximo é de " + MAX_REPORT_DAYS + " dias");
        }

        Map<LocalDate, DailySalesResponseDto> byDay = new HashMap<>();
        for (Object[] row : orderRepository.sumPaidByDay(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay())) {
            LocalDate day = row[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) row[0];
            byDay.put(day, new DailySalesResponseDto(day, ((Number) row[1]).longValue(), toBigDecimal(row[2])));
        }

        // Dias sem venda entram com zero: o gráfico precisa do eixo contínuo.
        return startDate.datesUntil(endDate.plusDays(1))
                .map(day -> byDay.getOrDefault(day, new DailySalesResponseDto(day, 0, BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderStatusCountResponseDto> getOrdersByStatus() {
        return orderRepository.countByStatusExcluding(OrderStatus.CARRINHO).stream()
                .map(row -> new OrderStatusCountResponseDto(((OrderStatus) row[0]).name(), ((Number) row[1]).longValue()))
                .toList();
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
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
