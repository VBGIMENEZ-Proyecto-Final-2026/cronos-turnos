package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con el envío del teléfono apagado ({@code catedra.phone.enabled=false}) el endpoint responde 503 y no marca nada. */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
class PhoneApiEnvioApagadoTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;
    @Autowired UserRepository users;

    @Test
    void conElEnvioApagadoDa503YNoMarcaNada() throws Exception {
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"ana\",\"password\":\"clave-de-prueba-1\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ana\",\"password\":\"clave-de-prueba-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
        long ana = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'ana'", Long.class);
        Instant t = Instant.now();
        var r = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2030, 2, 1), LocalTime.of(14, 30), LocalTime.of(15, 0)), "Juan", "Pérez", t));
        r = reservations.save(r.held("hold-x", "process-x", t.plusSeconds(600), t));
        r = reservations.save(r.waitingForPhone(t.plusSeconds(600), t).informationRequested("request-event-x", t));

        mvc.perform(post("/api/reservations/" + r.id() + "/phone").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content("{\"phoneNumber\":\"+5492617778899\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PHONE_DELIVERY_UNAVAILABLE"));

        assertThat(reservations.findById(r.id()).orElseThrow().phoneSubmittedAt()).isNull();
    }
}
