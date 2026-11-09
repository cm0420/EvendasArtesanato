package com.TadeuArtesanatos.demo.admin;

import com.TadeuArtesanatos.demo.TestcontainersConfiguration;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminReportsTests {

    @Autowired MockMvc mvc;

    private final User admin = User.builder().id(UUID.randomUUID()).email("admin@teste.com").password("x").role(Role.ADMIN).build();

    @Test
    void vendasPorDiaDevolveUmItemPorDiaMesmoSemVenda() throws Exception {
        mvc.perform(get("/api/admin/reports/sales-daily").with(user(admin))
                        .param("startDate", "2020-01-01").param("endDate", "2020-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].date").value("2020-01-01"))
                .andExpect(jsonPath("$[6].totalOrders").value(0));
    }

    @Test
    void vendasPorDiaRecusaPeriodoInvertidoOuLongoDemais() throws Exception {
        mvc.perform(get("/api/admin/reports/sales-daily").with(user(admin))
                        .param("startDate", "2020-02-01").param("endDate", "2020-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/reports/sales-daily").with(user(admin))
                        .param("startDate", "2020-01-01").param("endDate", "2022-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relatoriosExigemAdmin() throws Exception {
        User cliente = User.builder().id(UUID.randomUUID()).email("c@teste.com").password("x").role(Role.CLIENTE).build();
        mvc.perform(get("/api/admin/reports/orders-by-status").with(user(cliente))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reports/orders-by-status").with(user(admin))).andExpect(status().isOk());
    }

    @Test
    void adminNaoAlteraOProprioPapelNemSeExclui() throws Exception {
        mvc.perform(patch("/api/users/" + admin.getId() + "/role").with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CLIENTE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/users/" + admin.getId()).with(user(admin)))
                .andExpect(status().isBadRequest());
    }
}
