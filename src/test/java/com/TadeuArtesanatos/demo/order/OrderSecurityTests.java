package com.TadeuArtesanatos.demo.order;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.support.ShopFixtures;
import com.TadeuArtesanatos.demo.support.ShopFixtures.Buyer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roda como "fora de dev": pagamento fake fica PENDING. */
@Import({TestcontainersConfiguration.class, ShopFixtures.class})
@SpringBootTest(properties = "app.payment.fake-open-simulation=false")
@AutoConfigureMockMvc
class OrderSecurityTests {

    @Autowired MockMvc mvc;
    @Autowired ShopFixtures f;

    @Test
    void clienteNaoMexeNoPedidoDeOutroCliente() throws Exception {
        Piece piece = f.newPiece(1);
        Buyer owner = f.newBuyer();
        Buyer intruder = f.newBuyer();
        f.cartWith(owner, piece);
        UUID orderId = f.checkout(owner);
        String base = "/api/orders/me/" + orderId;

        List<MockHttpServletRequestBuilder> attempts = List.of(
                get(base),
                post(base + "/cancel"),
                json(post(base + "/refund-request"), "{\"reason\":\"quero\"}"),
                get(base + "/payments"),
                json(post(base + "/payments"), "{}"),
                get(base + "/shipping/quotes"),
                json(post(base + "/shipping"), "{\"code\":\"STANDARD\"}")
        );
        for (MockHttpServletRequestBuilder attempt : attempts) {
            mvc.perform(attempt.with(user(intruder.user()))).andExpect(status().isNotFound());
        }

        assertThat(f.statusOf(orderId)).isEqualTo(OrderStatus.AGUARDANDO_PAGAMENTO);
    }

    @Test
    void depoisDeIniciarOPagamentoNaoTrocaFreteNemPagaDuasVezes() throws Exception {
        Piece piece = f.newPiece(1);
        Buyer buyer = f.newBuyer();
        f.cartWith(buyer, piece);
        String base = "/api/orders/me/" + f.checkout(buyer);

        mvc.perform(json(post(base + "/shipping"), "{\"code\":\"STANDARD\"}").with(user(buyer.user())))
                .andExpect(status().isOk());
        mvc.perform(json(post(base + "/payments"), "{}").with(user(buyer.user())))
                .andExpect(status().isCreated());

        // trocar para o frete mais caro depois de o valor do pagamento já estar gravado
        mvc.perform(json(post(base + "/shipping"), "{\"code\":\"EXPRESS\"}").with(user(buyer.user())))
                .andExpect(status().isConflict());
        mvc.perform(json(post(base + "/payments"), "{}").with(user(buyer.user())))
                .andExpect(status().isConflict());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
