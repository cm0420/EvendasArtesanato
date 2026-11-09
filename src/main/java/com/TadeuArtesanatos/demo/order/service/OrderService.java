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
import com.TadeuArtesanatos.demo.stock.service.StockService;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final OrderMapper orderMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    // ---- Carrinho (CARRINHO) ----

    @Transactional
    public OrderResponseDto getOrCreateCart(User authenticatedUser) {
        Customer customer = getCustomerOrThrow(authenticatedUser);

        orderRepository.insertCartIfAbsent(customer.getId());
        Order cart = orderRepository.findByCustomerIdAndStatus(customer.getId(), OrderStatus.CARRINHO).orElseThrow();

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
        Order cart = lockOrder(getOwnCartOrThrow(authenticatedUser).getId());

        // Duplo clique: o segundo checkout espera o lock e encontra o pedido já fora do carrinho.
        if (!cart.isCart()) {
            throw new InvalidStateException("Este carrinho já foi finalizado");
        }

        if (cart.getItems().isEmpty()) {
            throw new InvalidStateException("Carrinho vazio — adicione ao menos um item antes de finalizar");
        }

        Address address = addressRepository.findById(dto.addressId())
                .filter(a -> a.getCustomer().getId().equals(cart.getCustomer().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Endereço não encontrado"));

        applyShippingSnapshot(cart, address);
        applyCurrentPrices(cart);

        // Tudo ou nada: se qualquer peça estiver esgotada, nenhuma reserva é feita.
        if (!stockService.tryReserveAll(quantityByPiece(cart), cart.getId())) {
            throw new InvalidStateException("Peça esgotada — estoque insuficiente para a quantidade solicitada");
        }

        cart.startCheckout();

        return orderMapper.toResponseDto(cart);
    }

    // ---- Confirmação de pagamento (temporária, manual — até `payment` existir) ----

    @Transactional
    public OrderResponseDto markAsPaid(UUID orderId) {
        // Lock no pedido: webhook, job de expiração e cancelamento não podem decidir o status ao mesmo tempo.
        Order order = lockOrder(orderId);

        // Idempotência: se já está pago (ou além), não repete a confirmação de estoque.
        if (order.isPaid()) {
            return orderMapper.toResponseDto(order);
        }

        if (order.isAwaitingPayment()) {
            stockService.confirmAllForOrder(order.getId());
            order.markAsPaid();
        } else if (order.getStatus() == OrderStatus.EXPIRADO || order.getStatus() == OrderStatus.CANCELADO) {
            // Pagamento chegou depois da expiração/cancelamento (ex: Pix pendente): as peças já voltaram ao estoque.
            // Tenta reservar de novo; se alguma esgotou, o dinheiro já foi cobrado → estorno para o admin.
            if (stockService.tryReserveAll(quantityByPiece(order), order.getId())) {
                stockService.confirmAllForOrder(order.getId());
                order.markAsPaid();
            } else {
                order.requestRefund("Pagamento aprovado após a expiração do pedido, mas uma ou mais peças esgotaram");
            }
        } else {
            throw new InvalidStateException("Pedido não está aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

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

        Order order = lockOrder(orderId);
        if (!order.getCustomer().getId().equals(customer.getId())) {
            throw new ResourceNotFoundException("Pedido não encontrado");
        }

        cancelInternal(order);
    }

    @Transactional
    public void cancelAsAdmin(UUID orderId) {
        cancelInternal(lockOrder(orderId));
    }

    private void cancelInternal(Order order) {
        if (order.getStatus() != OrderStatus.CARRINHO && order.getStatus() != OrderStatus.AGUARDANDO_PAGAMENTO) {
            throw new InvalidStateException("Só é possível cancelar pedidos em carrinho ou aguardando pagamento (status atual: " + order.getStatus() + ")");
        }

        if (order.isAwaitingPayment()) {
            stockService.releaseAllForOrder(order.getId());
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

    /** Cada pedido expira na sua própria transação: locks curtos e uma falha não desfaz as outras. */
    public void expireStaleCheckouts(long expirationMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(expirationMinutes);
        List<UUID> staleIds = orderRepository.findIdsByStatusAndCheckoutStartedAtBefore(OrderStatus.AGUARDANDO_PAGAMENTO, cutoff);

        for (UUID orderId : staleIds) {
            transactionTemplate.executeWithoutResult(tx -> {
                Order order = lockOrder(orderId);
                // Reconfere depois do lock: o pagamento pode ter chegado entre a busca e o lock.
                if (order.isAwaitingPayment() && order.getCheckoutStartedAt().isBefore(cutoff)) {
                    stockService.releaseAllForOrder(order.getId());
                    order.expire();
                }
            });
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

    /**
     * O preço é copiado ao adicionar no carrinho; sem isso, um carrinho antigo compraria
     * pelo preço de semanas atrás. Item que saiu do catálogo barra o checkout.
     */
    private void applyCurrentPrices(Order cart) {
        for (OrderItem item : cart.getItems()) {
            boolean active = item.isPieceItem() ? item.getPiece().isActive() : item.getPieceSet().isActive();
            if (!active) {
                throw new InvalidStateException("\"" + item.getItemName() + "\" não está mais disponível — remova do carrinho");
            }
            item.setUnitPrice(item.isPieceItem() ? item.getPiece().getPrice() : item.getPieceSet().getPrice());
            item.recalculateSubtotal();
        }
        cart.recalculateTotal();
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

    /** Trava a linha primeiro e só depois relê o estado (mesmo motivo do lockPiece no StockService). */
    private Order lockOrder(UUID orderId) {
        orderRepository.lockById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado"));
        Order order = orderRepository.findById(orderId).orElseThrow();
        entityManager.refresh(order);
        return order;
    }

    /** Soma por peça: item avulso conta a peça, item de kit conta cada peça componente. */
    private Map<UUID, Integer> quantityByPiece(Order order) {
        Map<UUID, Integer> quantities = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            List<Piece> pieces = item.isPieceItem() ? List.of(item.getPiece()) : List.copyOf(item.getPieceSet().getPieces());
            for (Piece piece : pieces) {
                quantities.merge(piece.getId(), item.getQuantity(), Integer::sum);
            }
        }
        return quantities;
    }
}
