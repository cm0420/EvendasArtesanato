package com.TadeuArtesanatos.demo.order;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.order.dto.OrderResponseDto;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
import com.TadeuArtesanatos.demo.order.service.OrderService;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.support.ShopFixtures;
import com.TadeuArtesanatos.demo.support.ShopFixtures.Buyer;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, ShopFixtures.class})
@SpringBootTest
class OrderConcurrencyTests {

    @Autowired ShopFixtures f;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PieceRepository pieceRepository;

    @Test
    void soUmClienteConsegueComprarPecaUnica() throws Exception {
        Piece piece = f.newPiece(1);
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Buyer b = f.newBuyer();
            f.cartWith(b, piece);
            buyers.add(b);
        }

        List<Throwable> errors = runConcurrently(buyers.stream()
                .<Callable<Object>>map(b -> () -> f.checkout(b))
                .toList());

        assertThat(errors).hasSize(9);
        assertThat(f.stockOf(piece)).isZero();
        assertThat(f.reservationsOf(piece, ReservationStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void checkoutsComPecasEmOrdemInversaNaoDaoDeadlock() throws Exception {
        Piece a = f.newPiece(100);
        Piece b = f.newPiece(100);
        Buyer b1 = f.newBuyer();
        Buyer b2 = f.newBuyer();

        for (int round = 0; round < 10; round++) {
            f.cartWith(b1, a, b);
            f.cartWith(b2, b, a);
            List<Throwable> errors = runConcurrently(List.of(() -> f.checkout(b1), () -> f.checkout(b2)));
            assertThat(errors).as("rodada %d", round).isEmpty();
        }

        assertThat(f.stockOf(a)).isEqualTo(80);
        assertThat(f.stockOf(b)).isEqualTo(80);
    }

    @Test
    void pagamentoAposExpiracaoReReservaQuandoAindaHaEstoque() {
        Piece piece = f.newPiece(1);
        Buyer buyer = f.newBuyer();
        f.cartWith(buyer, piece);
        UUID orderId = f.checkout(buyer);

        orderService.expireStaleCheckouts(0);
        assertThat(f.statusOf(orderId)).isEqualTo(OrderStatus.EXPIRADO);
        assertThat(f.stockOf(piece)).isEqualTo(1);

        orderService.markAsPaid(orderId);

        assertThat(f.statusOf(orderId)).isEqualTo(OrderStatus.PAGO);
        assertThat(f.stockOf(piece)).isZero();
        assertThat(f.reservationsOf(piece, ReservationStatus.CONFIRMED)).isEqualTo(1);
        assertThat(f.reservationsOf(piece, ReservationStatus.ACTIVE)).isZero();
    }

    @Test
    void pagamentoAposExpiracaoSemEstoqueViraEstornoSolicitado() {
        Piece piece = f.newPiece(1);
        Buyer late = f.newBuyer();
        f.cartWith(late, piece);
        UUID lateOrder = f.checkout(late);
        orderService.expireStaleCheckouts(0);

        Buyer other = f.newBuyer();
        f.cartWith(other, piece);
        f.checkout(other);

        orderService.markAsPaid(lateOrder);

        assertThat(f.statusOf(lateOrder)).isEqualTo(OrderStatus.ESTORNO_SOLICITADO);
        assertThat(f.stockOf(piece)).isZero();
        assertThat(f.reservationsOf(piece, ReservationStatus.ACTIVE)).isEqualTo(1);
        assertThat(f.reservationsOf(piece, ReservationStatus.CONFIRMED)).isZero();
    }

    @Test
    void pagamentoDePedidoCanceladoNaoSePerde() {
        Piece piece = f.newPiece(1);
        Buyer buyer = f.newBuyer();
        f.cartWith(buyer, piece);
        UUID orderId = f.checkout(buyer);
        orderService.cancelOwnOrder(buyer.user(), orderId);

        orderService.markAsPaid(orderId); // ex: Pix pago depois de cancelar

        assertThat(f.statusOf(orderId)).isEqualTo(OrderStatus.PAGO);
        assertThat(f.stockOf(piece)).isZero();
    }

    @Test
    void pagamentoEExpiracaoSimultaneosNaoPerdemEstoque() throws Exception {
        for (int round = 0; round < 10; round++) {
            Piece piece = f.newPiece(1);
            Buyer buyer = f.newBuyer();
            f.cartWith(buyer, piece);
            UUID orderId = f.checkout(buyer);

            List<Throwable> errors = runConcurrently(List.of(
                    () -> orderService.markAsPaid(orderId),
                    () -> { orderService.expireStaleCheckouts(0); return null; }
            ));

            assertThat(errors).as("rodada %d", round).isEmpty();
            assertThat(f.statusOf(orderId)).as("rodada %d", round).isEqualTo(OrderStatus.PAGO);
            assertThat(f.stockOf(piece)).as("rodada %d", round).isZero();
            assertThat(f.reservationsOf(piece, ReservationStatus.CONFIRMED)).as("rodada %d", round).isEqualTo(1);
            assertThat(f.reservationsOf(piece, ReservationStatus.ACTIVE)).as("rodada %d", round).isZero();
        }
    }

    @Test
    void abrirCarrinhoAoMesmoTempoNaoCriaDoisCarrinhos() throws Exception {
        Buyer buyer = f.newBuyer();

        List<Throwable> errors = runConcurrently(Collections.nCopies(5,
                () -> orderService.getOrCreateCart(buyer.user())));

        assertThat(errors).isEmpty();
        assertThat(orderRepository.findAll().stream()
                .filter(o -> o.getCustomer().getId().equals(buyer.customerId()))
                .filter(o -> o.getStatus() == OrderStatus.CARRINHO)
                .count()).isEqualTo(1);
    }

    @Test
    void checkoutCobraOPrecoAtualDaPeca() {
        Piece piece = f.newPiece(1); // R$ 50,00 ao entrar no carrinho
        Buyer buyer = f.newBuyer();
        f.cartWith(buyer, piece);

        Piece reloaded = pieceRepository.findById(piece.getId()).orElseThrow();
        reloaded.setPrice(new BigDecimal("80.00"));
        pieceRepository.save(reloaded);

        OrderResponseDto order = orderService.checkout(buyer.user(),
                new com.TadeuArtesanatos.demo.order.dto.CheckoutRequestDto(buyer.addressId()));

        assertThat(order.totalAmount()).isEqualByComparingTo("80.00");
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
            for (Future<Object> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            return errors;
        } finally {
            pool.shutdownNow();
        }
    }
}
