package com.TadeuArtesanatos.demo.order;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderConcurrencyTests {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PieceRepository pieceRepository;
    @Autowired UserRepository userRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired StockReservationRepository reservationRepository;

    record Buyer(User user, UUID addressId) {}

    @Test
    void soUmClienteConsegueComprarPecaUnica() throws Exception {
        Piece piece = newPiece(1);
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Buyer b = newBuyer();
            cartWith(b, piece);
            buyers.add(b);
        }

        List<Throwable> errors = runConcurrently(buyers.stream()
                .<Callable<Object>>map(b -> () -> checkout(b))
                .toList());

        assertThat(errors).hasSize(9);
        assertThat(stockOf(piece)).isZero();
        assertThat(reservationsOf(piece, ReservationStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void checkoutsComPecasEmOrdemInversaNaoDaoDeadlock() throws Exception {
        Piece a = newPiece(100);
        Piece b = newPiece(100);
        Buyer b1 = newBuyer();
        Buyer b2 = newBuyer();

        for (int round = 0; round < 10; round++) {
            cartWith(b1, a, b);
            cartWith(b2, b, a);
            List<Throwable> errors = runConcurrently(List.of(() -> checkout(b1), () -> checkout(b2)));
            assertThat(errors).as("rodada %d", round).isEmpty();
        }

        assertThat(stockOf(a)).isEqualTo(80);
        assertThat(stockOf(b)).isEqualTo(80);
    }

    @Test
    void pagamentoAposExpiracaoReReservaQuandoAindaHaEstoque() {
        Piece piece = newPiece(1);
        Buyer buyer = newBuyer();
        cartWith(buyer, piece);
        UUID orderId = checkout(buyer);

        orderService.expireStaleCheckouts(0);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.EXPIRADO);
        assertThat(stockOf(piece)).isEqualTo(1);

        orderService.markAsPaid(orderId);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAGO);
        assertThat(stockOf(piece)).isZero();
        assertThat(reservationsOf(piece, ReservationStatus.CONFIRMED)).isEqualTo(1);
        assertThat(reservationsOf(piece, ReservationStatus.ACTIVE)).isZero();
    }

    @Test
    void pagamentoAposExpiracaoSemEstoqueViraEstornoSolicitado() {
        Piece piece = newPiece(1);
        Buyer late = newBuyer();
        cartWith(late, piece);
        UUID lateOrder = checkout(late);
        orderService.expireStaleCheckouts(0);

        Buyer other = newBuyer();
        cartWith(other, piece);
        checkout(other);

        orderService.markAsPaid(lateOrder);

        assertThat(statusOf(lateOrder)).isEqualTo(OrderStatus.ESTORNO_SOLICITADO);
        assertThat(stockOf(piece)).isZero();
        assertThat(reservationsOf(piece, ReservationStatus.ACTIVE)).isEqualTo(1);
        assertThat(reservationsOf(piece, ReservationStatus.CONFIRMED)).isZero();
    }

    @Test
    void pagamentoEExpiracaoSimultaneosNaoPerdemEstoque() throws Exception {
        for (int round = 0; round < 10; round++) {
            Piece piece = newPiece(1);
            Buyer buyer = newBuyer();
            cartWith(buyer, piece);
            UUID orderId = checkout(buyer);

            List<Throwable> errors = runConcurrently(List.of(
                    () -> orderService.markAsPaid(orderId),
                    () -> { orderService.expireStaleCheckouts(0); return null; }
            ));

            assertThat(errors).as("rodada %d", round).isEmpty();
            assertThat(statusOf(orderId)).as("rodada %d", round).isEqualTo(OrderStatus.PAGO);
            assertThat(stockOf(piece)).as("rodada %d", round).isZero();
            assertThat(reservationsOf(piece, ReservationStatus.CONFIRMED)).as("rodada %d", round).isEqualTo(1);
            assertThat(reservationsOf(piece, ReservationStatus.ACTIVE)).as("rodada %d", round).isZero();
        }
    }

    // ---- fixtures ----

    private Piece newPiece(int stock) {
        return pieceRepository.save(Piece.builder()
                .name("Peça " + UUID.randomUUID())
                .price(new BigDecimal("50.00"))
                .stock(stock)
                .build());
    }

    private Buyer newBuyer() {
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
        return new Buyer(user, address.getId());
    }

    private void cartWith(Buyer buyer, Piece... pieces) {
        orderService.getOrCreateCart(buyer.user());
        for (Piece p : pieces) {
            orderService.addItem(buyer.user(), new AddOrderItemRequestDto(p.getId(), null, 1));
        }
    }

    private UUID checkout(Buyer buyer) {
        return orderService.checkout(buyer.user(), new CheckoutRequestDto(buyer.addressId())).id();
    }

    private int stockOf(Piece piece) {
        return pieceRepository.findById(piece.getId()).orElseThrow().getStock();
    }

    private OrderStatus statusOf(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private long reservationsOf(Piece piece, ReservationStatus status) {
        return reservationRepository.findAll().stream()
                .filter(r -> r.getPiece().getId().equals(piece.getId()))
                .filter(r -> r.getStatus() == status)
                .mapToInt(StockReservation::getQuantity)
                .sum();
    }

    /** Dispara todas as tarefas ao mesmo tempo; devolve as exceções lançadas. */
    private List<Throwable> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = tasks.stream()
                    .map(t -> pool.submit(() -> { start.await(); return t.call(); }))
                    .toList();
            start.countDown();
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            for (Future<Object> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            return errors;
        } finally {
            pool.shutdownNow();
        }
    }
}
