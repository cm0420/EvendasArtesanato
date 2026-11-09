package com.TadeuArtesanatos.demo.support;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.dto.AddOrderItemRequestDto;
import com.TadeuArtesanatos.demo.order.dto.CheckoutRequestDto;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Dados de teste da loja: peças, clientes com endereço, carrinho e checkout. */
@TestComponent
public class ShopFixtures {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PieceRepository pieceRepository;
    @Autowired UserRepository userRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired StockReservationRepository reservationRepository;

    public record Buyer(User user, UUID customerId, UUID addressId) {}

    public Piece newPiece(int stock) {
        return pieceRepository.save(Piece.builder()
                .name("Peça " + UUID.randomUUID())
                .price(new BigDecimal("50.00"))
                .stock(stock)
                .build());
    }

    public Buyer newBuyer() {
        String tag = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder()
                .email(tag + "@teste.com").password("x").role(Role.CLIENTE).build());
        Customer customer = Customer.builder()
                .user(user).firstName("Cliente").lastName("Teste").email(tag + "@teste.com")
                .phone("11999999999")
                .cpf(String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L)))
                .build();
        Address address = Address.builder()
                .label("Casa").recipientName("Cliente").recipientPhone("11999999999")
                .zipCode("01001000").street("Rua A").number("1").neighborhood("Centro")
                .city("São Paulo").state("SP").build();
        customer.addAddress(address);
        customerRepository.save(customer);
        return new Buyer(user, customer.getId(), address.getId());
    }

    public void cartWith(Buyer buyer, Piece... pieces) {
        orderService.getOrCreateCart(buyer.user());
        for (Piece p : pieces) {
            orderService.addItem(buyer.user(), new AddOrderItemRequestDto(p.getId(), null, 1));
        }
    }

    public UUID checkout(Buyer buyer) {
        return orderService.checkout(buyer.user(), new CheckoutRequestDto(buyer.addressId())).id();
    }

    public int stockOf(Piece piece) {
        return pieceRepository.findById(piece.getId()).orElseThrow().getStock();
    }

    public OrderStatus statusOf(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    public long reservationsOf(Piece piece, ReservationStatus status) {
        return reservationRepository.findAll().stream()
                .filter(r -> r.getPiece().getId().equals(piece.getId()))
                .filter(r -> r.getStatus() == status)
                .mapToInt(StockReservation::getQuantity)
                .sum();
    }
}
