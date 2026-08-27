package com.TadeuArtesanatos.demo.order.service;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceSetRepository;
import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.AddressRepository;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.dto.*;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.mapper.OrderMapper;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import com.TadeuArtesanatos.demo.stock.service.StockService;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final PieceRepository pieceRepository;
    private final PieceSetRepository pieceSetRepository;
    private final StockService stockService;
    private final StockReservationRepository stockReservationRepository;
    private final OrderMapper orderMapper;

    // ---- Carrinho (CARRINHO) ----

    @Transactional
    public OrderResponseDto getOrCreateCart(User authenticatedUser) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order cart = orderRepository.findByCustomerIdAndStatus(customer.getId(), OrderStatus.CARRINHO)
                .orElseGet(() -> orderRepository.save(
                        Order.builder()
                                .customer(customer)
                                .status(OrderStatus.CARRINHO)
                                .totalAmount(BigDecimal.ZERO)
                                .build()
                ));

        return orderMapper.toResponseDto(cart);
    }

    @Transactional
    public OrderResponseDto addItem(User authenticatedUser, AddOrderItemRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);

        boolean temPeca = dto.pieceId() != null;
        boolean temConjunto = dto.pieceSetId() != null;

        if (temPeca == temConjunto) {
            throw new IllegalArgumentException("Informe exatamente um: pieceId OU pieceSetId, nunca os dois nem nenhum");
        }

        OrderItem item = temPeca ? buildPieceItem(dto) : buildSetItem(dto);
        item.recalculateSubtotal();

        cart.addItem(item);

        return orderMapper.toResponseDto(cart);
    }

    private OrderItem buildPieceItem(AddOrderItemRequestDto dto) {
        Piece piece = pieceRepository.findById(dto.pieceId())
                .filter(Piece::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

        return OrderItem.builder()
                .piece(piece)
                .itemName(piece.getName())
                .unitPrice(piece.getPrice())
                .quantity(dto.quantity())
                .build();
    }

    private OrderItem buildSetItem(AddOrderItemRequestDto dto) {
        PieceSet pieceSet = pieceSetRepository.findById(dto.pieceSetId())
                .filter(PieceSet::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Conjunto não encontrado"));

        return OrderItem.builder()
                .pieceSet(pieceSet)
                .itemName(pieceSet.getName())
                .unitPrice(pieceSet.getPrice())
                .quantity(dto.quantity())
                .build();
    }

    @Transactional
    public OrderResponseDto updateItemQuantity(User authenticatedUser, UUID itemId, UpdateOrderItemRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);
        OrderItem item = findItemOrThrow(cart, itemId);

        item.setQuantity(dto.quantity());
        item.recalculateSubtotal();
        cart.recalculateTotal();

        return orderMapper.toResponseDto(cart);
    }

    @Transactional
    public OrderResponseDto removeItem(User authenticatedUser, UUID itemId) {
        Order cart = getOwnCartOrThrow(authenticatedUser);
        OrderItem item = findItemOrThrow(cart, itemId);

        cart.removeItem(item);

        return orderMapper.toResponseDto(cart);
    }

    // ---- Checkout (CARRINHO → AGUARDANDO_PAGAMENTO) ----

    @Transactional
    public OrderResponseDto checkout(User authenticatedUser, CheckoutRequestDto dto) {
        Order cart = getOwnCartOrThrow(authenticatedUser);

        if (cart.getItems().isEmpty()) {
            throw new InvalidStateException("Carrinho vazio — adicione ao menos um item antes de finalizar");
        }

        Address address = addressRepository.findById(dto.addressId())
                .filter(a -> a.getCustomer().getId().equals(cart.getCustomer().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Endereço não encontrado"));

        applyShippingSnapshot(cart, address);

        // Reserva estoque de cada item — se qualquer peça estiver esgotada,
        // a transação inteira faz rollback (nenhuma reserva parcial fica presa).
        // Item de kit: reserva a mesma quantidade em CADA peça componente.
        for (OrderItem item : cart.getItems()) {
            if (item.isPieceItem()) {
                reserveForPiece(item.getPiece().getId(), item.getQuantity(), cart.getId());
            } else {
                for (Piece component : item.getPieceSet().getPieces()) {
                    reserveForPiece(component.getId(), item.getQuantity(), cart.getId());
                }
            }
        }

        cart.startCheckout();

        return orderMapper.toResponseDto(cart);
    }

    private void reserveForPiece(UUID pieceId, Integer quantity, UUID orderId) {
        ReserveStockRequestDto reserveDto = new ReserveStockRequestDto(pieceId, quantity, orderId);
        stockService.reserve(reserveDto);
    }

    // ---- Confirmação de pagamento (temporária, manual — até `payment` existir) ----

    @Transactional
    public OrderResponseDto markAsPaid(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        // Idempotência: se já está pago (ou além), não repete a confirmação de estoque.
        if (order.isPaid()) {
            return orderMapper.toResponseDto(order);
        }

        if (!order.isAwaitingPayment()) {
            throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
        for (StockReservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                stockService.confirm(reservation.getId());
            }
        }

        order.markAsPaid();

        return orderMapper.toResponseDto(order);
    }

    // ---- Envio / entrega (ADMIN) ----

    @Transactional
    public OrderResponseDto ship(UUID orderId, ShipOrderRequestDto dto) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.PAGO) {
            throw new InvalidStateException("Só é possível enviar pedidos pagos (status atual: " + order.getStatus() + ")");
        }

        order.ship(dto.trackingCode());

        return orderMapper.toResponseDto(order);
    }

    @Transactional
    public OrderResponseDto deliver(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (order.getStatus() != OrderStatus.ENVIADO) {
            throw new InvalidStateException("Só é possível entregar pedidos enviados (status atual: " + order.getStatus() + ")");
        }

        order.deliver();

        return orderMapper.toResponseDto(order);
    }

    // ---- Cancelamento (dono ou admin, só CARRINHO/AGUARDANDO_PAGAMENTO) ----

    @Transactional
    public void cancelOwnOrder(User authenticatedUser, UUID orderId) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        cancelInternal(order);
    }

    @Transactional
    public void cancelAsAdmin(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        cancelInternal(order);
    }

    private void cancelInternal(Order order) {
        if (order.getStatus() != OrderStatus.CARRINHO && order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Só é possível cancelar pedidos em carrinho ou aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        if (order.isAwaitingPayment()) {
            List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
            for (StockReservation reservation : reservations) {
                if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                    stockService.release(reservation.getId());
                }
            }
        }

        order.cancel();
    }

    // ---- Estorno (fluxo/auditoria — ver decisão no topo do arquivo) ----

    @Transactional
    public void requestRefund(User authenticatedUser, UUID orderId, RequestRefundDto dto) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (!order.isPaid()) {
            throw new InvalidStateException("Só é possível solicitar estorno de pedidos pagos (status atual: " + order.getStatus() + ")");
        }

        order.requestRefund(dto.reason());
    }

    @Transactional
    public OrderResponseDto approveRefund(UUID orderId) {
        Order order = getRefundRequestedOrderOrThrow(orderId);
        order.approveRefund();
        return orderMapper.toResponseDto(order);
    }

    @Transactional
    public OrderResponseDto denyRefund(UUID orderId) {
        Order order = getRefundRequestedOrderOrThrow(orderId);
        order.denyRefund();
        return orderMapper.toResponseDto(order);
    }

    private Order getRefundRequestedOrderOrThrow(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        if (!order.hasRefundRequested()) {
            throw new InvalidStateException("Este pedido não tem estorno solicitado (status atual: " + order.getStatus() + ")");
        }

        return order;
    }

    // ---- Consultas ----

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> listOwnOrders(User authenticatedUser, Pageable pageable) {
        Customer customer = getCustomerOrThrow(authenticatedUser);
        return orderRepository.findAllByCustomerId(customer.getId(), pageable).map(orderMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public OrderResponseDto findOwnOrder(User authenticatedUser, UUID orderId) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return orderMapper.toResponseDto(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDto> listAll(Pageable pageable, OrderStatus status) {
        Page<Order> page = (status != null)
                ? orderRepository.findAllByStatus(status, pageable)
                : orderRepository.findAll(pageable);

        return page.map(orderMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public OrderResponseDto findByIdAsAdmin(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));

        return orderMapper.toResponseDto(order);
    }

    // ---- Expiração automática (chamada pelo job) ----

    @Transactional
    public void expireStaleCheckouts(long expirationMinutes) {
        var cutoff = java.time.LocalDateTime.now().minusMinutes(expirationMinutes);
        List<Order> stale = orderRepository.findAllByStatusAndCheckoutStartedAtBefore(OrderStatus.AGUARDANDO_PAGAMENTO, cutoff);

        for (Order order : stale) {
            List<StockReservation> reservations = stockReservationRepository.findAllByOrderId(order.getId());
            for (StockReservation reservation : reservations) {
                if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                    stockService.release(reservation.getId());
                }
            }
            order.expire();
        }
    }

    // ---- helpers internos ----

    private Customer getCustomerOrThrow(User authenticatedUser) {
        return customerRepository.findByUserId(authenticatedUser.getId())
                .filter(Customer::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de cliente não encontrado"));
    }

    private Order getOwnCartOrThrow(User authenticatedUser) {
        Customer customer = getCustomerOrThrow(authenticatedUser);
        return orderRepository.findByCustomerIdAndStatus(customer.getId(), OrderStatus.CARRINHO)
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum carrinho aberto — crie um antes"));
    }

    private OrderItem findItemOrThrow(Order order, UUID itemId) {
        return order.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado no carrinho"));
    }

    private void applyShippingSnapshot(Order order, Address address) {
        order.setRecipientName(address.getRecipientName());
        order.setRecipientPhone(address.getRecipientPhone());
        order.setShippingZipCode(address.getZipCode());
        order.setShippingStreet(address.getStreet());
        order.setShippingNumber(address.getNumber());
        order.setShippingComplement(address.getComplement());
        order.setShippingNeighborhood(address.getNeighborhood());
        order.setShippingCity(address.getCity());
        order.setShippingState(address.getState());
    }
}
