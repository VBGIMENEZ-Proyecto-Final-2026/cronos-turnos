package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cátedra y atlas caídos <b>a la vez</b> (issue #13, criterio 4): lo que los necesita responde {@code 503} sin iniciar
 * nada ni dejar un proceso colgado, y lo que depende solo de datos propios sigue funcionando.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, ServiciosCaidosApiTest.Servidores.class})
class ServiciosCaidosApiTest {

    private static final String CLAVE = "clave-de-prueba-123";
    private static final StubServer ATLAS = new StubServer();
    private static final StubServer CATEDRA = new StubServer();
    private static final LocalDate MARTES = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).plusWeeks(3);

    private static final String PROFESIONAL = "{\"id\":218,\"firstName\":\"Romina\",\"lastName\":\"Acosta\","
            + "\"enabled\":true,\"schedules\":[{\"dayOfWeek\":\"TUESDAY\",\"startTime\":\"13:00:00\","
            + "\"endTime\":\"16:00:00\",\"slotDurationMinutes\":30,\"enabled\":true}]}";
    private static final String HOLD = "{\"holdId\":\"hold-1\",\"reservationProcessId\":\"process-1\","
            + "\"status\":\"HELD\",\"expiresAt\":\"2030-01-01T10:00:00Z\"}";
    private static final String CONFIRMADO = "{\"reservationProcessId\":\"process-1\","
            + "\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2030-01-01T10:01:00Z\"}";

    @Order(100)
    static class Servidores implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "cronos.atlas.base-url=" + ATLAS.url(),
                    "catedra.rest.base-url=" + CATEDRA.url(),
                    "cronos.atlas.max-retries=0",
                    "catedra.rest.max-retries=0").applyTo(context);
        }
    }

    @AfterAll
    static void apagar() {
        ATLAS.close();
        CATEDRA.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        ATLAS.reset();
        CATEDRA.reset();
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    private String tokenDe(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions reservar(String token, String hora) throws Exception {
        return mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content("{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"" + hora
                        + "\",\"patientFirstName\":\"Juan\",\"patientLastName\":\"Pérez\"}"));
    }

    private ResultActions pedir(String token, String ruta) throws Exception {
        return mvc.perform(get(ruta).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private long filas() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_process", Long.class);
    }

    @Test
    void conLaCatedraYAtlasCaidosSeRechazaLoQueLosNecesitaYSigueAndandoLoPropio() throws Exception {
        String token = tokenDe("ana");
        // Antes de la caída: una reserva propia que espera el teléfono.
        ATLAS.reply(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        reservar(token, "14:30").andExpect(status().isAccepted());
        assertThat(filas()).isEqualTo(1);

        // Se caen los dos.
        ATLAS.reset();
        CATEDRA.reset();
        ATLAS.reply(Reply.problem(503, "DOWN"));
        CATEDRA.reply(Reply.problem(503, "DOWN"));

        // Lo que los necesita: 503, sin iniciar nada nuevo ni dejar un proceso a medias.
        reservar(token, "15:00").andExpect(status().isServiceUnavailable());
        pedir(token, "/api/professionals/218/availability?from=" + MARTES + "&to=" + MARTES)
                .andExpect(status().isServiceUnavailable());
        assertThat(filas()).as("ningún proceso nuevo: se rechazó antes de guardar nada").isEqualTo(1);
        assertThat(CATEDRA.requests()).as("con atlas caído no se llega a pedir un hold").isEmpty();

        // Lo que depende solo de datos propios: sigue funcionando.
        pedir(token, "/api/account").andExpect(status().isOk()).andExpect(jsonPath("$.login").value("ana"));
        pedir(token, "/api/reservations").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("WAITING_FOR_PHONE"));
        long id = jdbc.queryForObject("SELECT id FROM reservation_process", Long.class);
        pedir(token, "/api/reservations/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));
    }

    @Test
    void conAtlasVivoYLaCatedraCaidaNoSeIniciaNingunaOperacionQueLaNecesiteYElProcesoNoQuedaColgado() throws Exception {
        String token = tokenDe("ana");
        ATLAS.reply(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.problem(503, "DOWN"));

        reservar(token, "14:30").andExpect(status().isServiceUnavailable());

        // El proceso quedó en un estado final recuperable (FAILED), no en STARTING: nada que dependa de un barrido.
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_process", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM reservation_process", String.class))
                .isEqualTo("CATEDRA_UNAVAILABLE");
        // Y se puede volver a pedir el mismo turno cuando la cátedra vuelva.
        CATEDRA.reset();
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        reservar(token, "14:30").andExpect(status().isAccepted());
    }
}
