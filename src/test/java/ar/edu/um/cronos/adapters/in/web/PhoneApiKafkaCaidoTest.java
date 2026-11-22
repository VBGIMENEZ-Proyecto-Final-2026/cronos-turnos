package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El envío del teléfono con Kafka caído (el servidor existe en el nombre y nadie escucha): el endpoint responde 503 dentro del
 * plazo, no marca nada, y el log no escribe el teléfono, el groupId técnico, el topic ni la dirección del servidor. La
 * librería de Kafka los escribía en WARN en cada intento de conexión; quedan en ERROR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, PhoneApiKafkaCaidoTest.ConKafkaCaido.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhoneApiKafkaCaidoTest {

    @Order(1)
    static class ConKafkaCaido implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of("catedra.phone.enabled=true", "catedra.phone.bootstrap-servers=localhost:1",
                    "catedra.phone.topic=catedra.turnos.telefono.cuenta-caida", "catedra.phone.group-id=proyecto-caido-privado",
                    "catedra.phone.send-timeout=1s").applyTo(context);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;

    @Test
    void conKafkaCaidoDa503DentroDelPlazoSinMarcarNadaYSinEscribirNadaDeLaCuentaNiDelTelefono() throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"ana\",\"password\":\"clave-de-prueba-1\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ana\",\"password\":\"clave-de-prueba-1\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
        long ana = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'ana'", Long.class);
        Instant t = Instant.now();
        var r = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2030, 4, 1), LocalTime.of(14, 30), LocalTime.of(15, 0)), "Juan", "Pérez", t));
        r = reservations.save(r.held("hold-caido", "process-caido", t.plusSeconds(600), t));
        long id = reservations.save(r.waitingForPhone(t.plusSeconds(600), t).informationRequested("request-event-caido", t)).id();

        try (var log = new LogCapture()) {
            long inicio = System.nanoTime();
            mvc.perform(post("/api/reservations/" + id + "/phone").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content("{\"phoneNumber\":\"+5492617778899\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PHONE_DELIVERY_UNAVAILABLE"));
            Thread.sleep(1500); // deja que la librería reintente conectarse

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).as("no se queda esperando de más").isLessThan(Duration.ofSeconds(10));
            assertThat(reservations.findById(id).orElseThrow().phoneSubmittedAt()).isNull();
            String conNivelDeProduccion = log.list().stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                    .map(e -> e.getLoggerName() + " " + e.getFormattedMessage()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(conNivelDeProduccion).contains("reserva.telefono.no_enviado")
                    .doesNotContain("7778899").doesNotContain("proyecto-caido-privado").doesNotContain("cuenta-caida")
                    .doesNotContain("localhost:1").doesNotContain("127.0.0.1");
        }
    }
}
