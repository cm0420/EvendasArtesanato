package com.TadeuArtesanatos.demo.security;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
import com.TadeuArtesanatos.demo.payment.gateway.fake.FakePaymentGateway;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roda como "fora de dev": simulação de pagamento fechada. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.payment.fake-open-simulation=false")
@AutoConfigureMockMvc
class ApiHardeningTests {

    @Autowired MockMvc mvc;
    @Autowired FakePaymentGateway fakePaymentGateway;

    private static final String WEBHOOK_BODY = "{\"transactionId\":\"fake_txn_x\",\"status\":\"APPROVED\"}";

    // ---- item 4: pagamento fake fora de dev ----

    @Test
    void clienteNaoEscolheResultadoDoPagamentoFake() {
        assertThat(fakePaymentGateway.charge(null, "APPROVED").resultingStatus()).isEqualTo("PENDING");
    }

    @Test
    void webhookFakeExigeAdmin() throws Exception {
        mvc.perform(post("/api/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/webhook/fake").with(user(fakeUser(Role.CLIENTE)))
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isForbidden());
        // ADMIN passa pela segurança; a transação não existe → 404 de negócio
        mvc.perform(post("/api/payments/webhook/fake").with(user(fakeUser(Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isNotFound());
    }

    // ---- item 6: refresh token por hash, com rotação ----

    @Test
    void refreshTokenFuncionaUmaVezSo() throws Exception {
        Cookie first = signup(UUID.randomUUID() + "@teste.com");

        Cookie second = mvc.perform(post("/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/auth/refresh").cookie(first)).andExpect(status().isBadRequest());
        mvc.perform(post("/auth/refresh").cookie(second)).andExpect(status().isOk());
    }

    // ---- item 9: limite de login e CORS ----

    @Test
    void loginBloqueiaDepoisDeCincoSenhasErradas() throws Exception {
        String email = UUID.randomUUID() + "@teste.com";
        signup(email);

        for (int i = 0; i < 5; i++) {
            mvc.perform(login(email, "senha-errada")).andExpect(status().isUnauthorized());
        }
        // bloqueado mesmo com a senha certa, até a janela passar
        mvc.perform(login(email, "senha12345")).andExpect(status().isTooManyRequests());
        // outro IP não é afetado
        mvc.perform(login(email, "senha12345").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void corsAceitaOFrontAngular() throws Exception {
        mvc.perform(options("/api/catalog/pieces").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/api/catalog/pieces").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // ---- item 7: erros do cliente não viram 500 ----

    @Test
    void errosDeRequisicaoDevolvemStatusCorreto() throws Exception {
        mvc.perform(get("/api/catalog/pieces/nao-e-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/refresh")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/rota-que-nao-existe")).andExpect(status().isNotFound());
    }

    @Test
    void preAuthorizeNegadoDevolve403() throws Exception {
        mvc.perform(patch("/api/users/" + UUID.randomUUID() + "/senha").with(user(fakeUser(Role.CLIENTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"senha12345\",\"confirmPassword\":\"senha12345\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- item 8: JWT inválido / ausente ----

    @Test
    void tokenInvalidoOuAusenteDevolve401() throws Exception {
        mvc.perform(get("/api/orders/me").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/me")).andExpect(status().isUnauthorized());
        // rota pública com token ruim também avisa, para o front renovar o token
        mvc.perform(get("/api/catalog/pieces").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
    }

    // ---- fixtures ----

    private Cookie signup(String email) throws Exception {
        return mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Ana","lastName":"Teste","email":"%s","password":"senha12345",
                         "phone":"11999999999","cpf":"%s"}""".formatted(email, validCpf())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("refresh_token");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email, String password) {
        return post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private static User fakeUser(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@teste.com").password("x").role(role).build();
    }

    private static String validCpf() {
        int[] d = new int[11];
        for (int i = 0; i < 9; i++) d[i] = ThreadLocalRandom.current().nextInt(10);
        d[0] = 1; // evita todos os dígitos iguais
        for (int pos = 9; pos < 11; pos++) {
            int sum = 0;
            for (int i = 0; i < pos; i++) sum += d[i] * (pos + 1 - i);
            int r = (sum * 10) % 11;
            d[pos] = r == 10 ? 0 : r;
        }
        StringBuilder sb = new StringBuilder();
        for (int x : d) sb.append(x);
        return sb.toString();
    }
}
