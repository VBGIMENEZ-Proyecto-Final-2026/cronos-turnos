package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
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

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/reservations/{id}/cancel} con la aplicación completa, PostgreSQL real y una cátedra de mentira por HTTP:
 * quién puede cancelar, qué estado exige, qué pasa cuando la cátedra rechaza o no responde, la idempotencia y que el motivo
 * del usuario ni se guarda ni se registra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
// Un @ContextConfiguration directo reemplaza al de @IntegrationTestSupport: hay que nombrar los dos inicializadores.
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, ReservationCancelApiTest.Servidores.class})
class ReservationCancelApiTest {

    private static final String CLAVE = "clave-de-prueba-1";
    private static final String TOKEN_TECNICO = "token-tecnico-de-prueba-cancelacion";
    private static final String MOTIVO = "Cambio de planes, avisen a Juan Pérez";
    static final StubServer CATEDRA = new StubServer();

    @Order(100)
    static class Servidores implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of("catedra.rest.base-url=" + CATEDRA.url(), "catedra.rest.max-retries=0",
                    "catedra.rest.id-token=" + TOKEN_TECNICO).applyTo(context);
        }
    }

    @AfterAll
    static void apagar() {
        CATEDRA.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;

    private int seq;

    @BeforeEach
    void limpiar() {
        CATEDRA.reset();
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    private String tokenDe(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
    }

    private long idDe(String login) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE login = ?", Long.class, login);
    }

    private static String tokenConRol(String rol) {
        Instant ahora = Instant.now();
        var usuario = new User(1L, "otro", "hash", null, null, null, null, "es", true, Set.of(rol), ahora, ahora);
        return new NimbusTokenIssuer((RSAPrivateKey) TestKeys.privateKey(), (RSAPublicKey) TestKeys.publicKey(),
                Duration.ofHours(1), Duration.ofDays(30), Clock.systemUTC()).issue(usuario, false);
    }

    private Reservation reserva(long userId, ReservationStatus estado) {
        seq++;
        Instant t = Instant.now();
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2032, 1, 1).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0)), "Juan", "Pérez", t));
        r = reservations.save(r.held("hold-" + seq, "process-cancel-" + seq, t.plusSeconds(600), t));
        r = reservations.save(r.waitingForPhone(t.plusSeconds(600), t));
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8000 + seq, t));
            case CANCELLED -> reservations.save(r.confirmed(8000 + seq, t).cancelled(t));
            case EXPIRED -> reservations.save(r.expired(t));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    private static String cancelada(Reservation r) {
        return "{\"reservationId\":" + r.catedraReservationId() + ",\"reservationProcessId\":\"" + r.reservationProcessId()
                + "\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-22T15:10:00Z\"}";
    }

    private ResultActions cancelar(String token, Object id, String cuerpo) throws Exception {
        var pedido = post("/api/reservations/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON);
        if (cuerpo != null) {
            pedido.content(cuerpo);
        }
        if (token != null) {
            pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    private String estado(Reservation r) {
        return jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?", String.class, r.id());
    }

    // ---- el camino feliz -------------------------------------------------------------------------------------

    @Test
    void cancelaLaReservaPropiaConfirmadaYLaDejaCanceladaConElMotivoSoloEnLaCatedra() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(r)));

        String cuerpo = cancelar(token, r.id(), "{\"reason\":\"" + MOTIVO + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(r.id())).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn().getResponse().getContentAsString();

        var pedido = CATEDRA.requests().get(0);
        assertThat(pedido.method() + " " + pedido.uri()).isEqualTo("POST /api/appointments/" + r.reservationProcessId() + "/cancel");
        assertThat(pedido.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        assertThat(pedido.body()).contains(MOTIVO);
        assertThat(estado(r)).isEqualTo("CANCELLED");
        // El motivo es texto libre del usuario: viajó a la cátedra y no quedó en ninguna parte de cronos.
        var guardado = new StringBuilder();
        for (String tabla : List.of("reservation_process", "processed_event", "unmatched_event")) {
            jdbc.queryForList("SELECT * FROM " + tabla).forEach(fila -> guardado.append(fila).append('\n'));
        }
        assertThat(guardado.toString()).doesNotContain("Cambio de planes").doesNotContain("avisen");
        assertThat(cuerpo).doesNotContain("Cambio de planes").doesNotContain("reason");
    }

    @Test
    void elCuerpoEsOpcional() throws Exception {
        String token = tokenDe("ana");
        var sinCuerpo = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        var vacio = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        var sinMotivo = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(sinCuerpo)), Reply.json(200, cancelada(vacio)), Reply.json(200, cancelada(sinMotivo)));

        cancelar(token, sinCuerpo.id(), null).andExpect(status().isOk());
        cancelar(token, vacio.id(), "{}").andExpect(status().isOk());
        cancelar(token, sinMotivo.id(), "{\"reason\":\"  \"}").andExpect(status().isOk());

        assertThat(CATEDRA.requests()).hasSize(3);
        assertThat(CATEDRA.requests()).allSatisfy(p -> assertThat(p.body() == null ? "" : p.body()).doesNotContain("reason"));
        assertThat(List.of(estado(sinCuerpo), estado(vacio), estado(sinMotivo))).containsOnly("CANCELLED");
    }

    @Test
    void cancelarDosVecesEsIdempotenteYLaCatedraSoloSeEntera1Vez() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(r)));

        cancelar(token, r.id(), null).andExpect(status().isOk());
        cancelar(token, r.id(), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(CATEDRA.requests()).hasSize(1);
    }

    // ---- quién y en qué estado -------------------------------------------------------------------------------

    @Test
    void laReservaDeOtroUsuarioYUnaInexistenteDanExactamenteElMismo404SinLlamarALaCatedra() throws Exception {
        String tokenAna = tokenDe("ana");
        tokenDe("beto");
        var deBeto = reserva(idDe("beto"), ReservationStatus.CONFIRMED);

        String ajena = cancelar(tokenAna, deBeto.id(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String inexistente = cancelar(tokenAna, 987_654, null).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString();

        assertThat(ajena.replace("/" + deBeto.id() + "/", "/ID/")).isEqualTo(inexistente.replace("/987654/", "/ID/"));
        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(estado(deBeto)).as("la de beto sigue confirmada").isEqualTo("CONFIRMED");
    }

    @Test
    void unaReservaQueNoEstaConfirmadaDa409SinLlamarALaCatedra() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        var esperando = reserva(ana, ReservationStatus.WAITING_FOR_PHONE);
        var vencida = reserva(ana, ReservationStatus.EXPIRED);

        cancelar(token, esperando.id(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
        cancelar(token, vencida.id(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(estado(esperando)).isEqualTo("WAITING_FOR_PHONE");
    }

    @Test
    void sinTokenDa401UnTokenSinRolDa403YElGetNoExisteEnEstaRuta() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);

        cancelar(null, r.id(), null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        cancelar(tokenConRol("ROLE_INVITADO"), r.id(), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mvc.perform(get("/api/reservations/" + r.id() + "/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        cancelar(token, "abc", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(estado(r)).isEqualTo("CONFIRMED");
    }

    // ---- el motivo -------------------------------------------------------------------------------------------

    @Test
    void unMotivoDeMasDeQuinientosCaracteresDa400SinRepetirloNiLlamarALaCatedra() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(r)));

        String cuerpo = cancelar(token, r.id(), "{\"reason\":\"" + "x".repeat(501) + "Pérez\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
                .andReturn().getResponse().getContentAsString();
        cancelar(token, r.id(), "{\"reason\":\"" + "x".repeat(500) + "\"}").andExpect(status().isOk());

        assertThat(cuerpo).doesNotContain("xxxxx").doesNotContain("Pérez");
        assertThat(CATEDRA.requests()).as("solo la del motivo de 500").hasSize(1);
    }

    @Test
    void unCuerpoQueNoEsJsonDa400SinRepetirNada() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);

        String cuerpo = cancelar(token, r.id(), "{\"reason\":\"" + MOTIVO + "\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("Cambio de planes");
        assertThat(CATEDRA.requests()).isEmpty();
    }

    // ---- lo que responde la cátedra ---------------------------------------------------------------------------

    @Test
    void siLaCatedraDiceQueNoSePuedeCancelarDa409YElEstadoLocalNoCambia() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.problem(409, "RESERVATION_NOT_CANCELLABLE"));

        cancelar(token, r.id(), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));

        assertThat(estado(r)).isEqualTo("CONFIRMED");
        assertThat(CATEDRA.requests()).as("un rechazo no se reintenta").hasSize(1);
    }

    @Test
    void siLaCatedraNoConoceLaReservaOEsDeOtraCuentaDa502PorqueNoDeberiaPasar() throws Exception {
        String token = tokenDe("ana");
        var uno = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        var otro = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.problem(404, "RESERVATION_NOT_FOUND"), Reply.problem(403, "RESERVATION_OWNER_MISMATCH"));

        cancelar(token, uno.id(), null).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CATEDRA_ERROR"));
        cancelar(token, otro.id(), null).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CATEDRA_ERROR"));

        assertThat(estado(uno)).isEqualTo("CONFIRMED");
        assertThat(estado(otro)).isEqualTo("CONFIRMED");
    }

    @Test
    void siNoSeSabeSiLaCatedraCanceloSeRepiteUnaVezYSiSeRecuperaQuedaCancelada() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.problem(503, "X"), Reply.json(200, cancelada(r)));

        cancelar(token, r.id(), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(CATEDRA.requests()).as("el primero y una reconciliación").hasSize(2);
    }

    @Test
    void siSigueSinSaberseDa503LaReservaSigueConfirmadaYRepetirElPedidoFunciona() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.problem(503, "X"), Reply.problem(503, "X"));

        cancelar(token, r.id(), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(CATEDRA.requests()).hasSize(2);
        assertThat(estado(r)).isEqualTo("CONFIRMED");

        CATEDRA.reset();
        CATEDRA.reply(Reply.json(200, cancelada(r)));
        cancelar(token, r.id(), null).andExpect(status().isOk());
        assertThat(estado(r)).isEqualTo("CANCELLED");
    }

    @Test
    void elEventoDeLaCatedraQueLlegaDespuesNoCambiaNadaPorqueYaEsEstadoFinal() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(r)));
        cancelar(token, r.id(), null).andExpect(status().isOk());
        var cancelada = reservations.findById(r.id()).orElseThrow();

        // Lo que hace el consumidor al recibir AppointmentCancelled después: un estado final no cambia.
        assertThat(cancelada.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelada.status().isFinal()).isTrue();
    }

    // ---- lo que no se registra ---------------------------------------------------------------------------------

    @Test
    void niElMotivoNiElTokenTecnicoApareceEnElLogAunConTodoEnTrace() throws Exception {
        String token = tokenDe("ana");
        var exito = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        var rechazo = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        var incierto = reserva(idDe("ana"), ReservationStatus.CONFIRMED);
        CATEDRA.reply(Reply.json(200, cancelada(exito)), Reply.problem(409, "RESERVATION_NOT_CANCELLABLE"),
                Reply.problem(503, "X"), Reply.problem(503, "X"));
        String conMotivo = "{\"reason\":\"" + MOTIVO + "\"}";
        try (var log = new LogCapture()) {
            cancelar(token, exito.id(), conMotivo).andExpect(status().isOk());
            cancelar(token, rechazo.id(), conMotivo).andExpect(status().isConflict());
            cancelar(token, incierto.id(), conMotivo).andExpect(status().isServiceUnavailable());
            cancelar(token, rechazo.id(), "{\"reason\":\"" + "x".repeat(600) + MOTIVO + "\"}").andExpect(status().isBadRequest());

            assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
            String deCronos = log.list().stream().filter(e -> e.getLoggerName().startsWith("ar.edu.um.cronos"))
                    .map(e -> e.getFormattedMessage()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(deCronos).doesNotContain("Cambio de planes").doesNotContain("Juan").doesNotContain("Pérez")
                    .doesNotContain(TOKEN_TECNICO);
            // Con todo en TRACE, Spring escribe los objetos que lee del cuerpo ("Read ... to [CancelRequest[...]]"): su toString
            // no debe imprimir el motivo. (El nombre del paciente no sirve de testigo: Hibernate escribe sus parámetros.)
            assertThat(log.textExcluding("org.hibernate", "org.postgresql", "org.apache.hc", "jdk.internal.net.http", "sun.net",
                    "org.springframework.jdbc")).doesNotContain("Cambio de planes").doesNotContain("avisen a").doesNotContain(TOKEN_TECNICO);
            assertThat(log.eventos("reserva.cancelacion.confirmada")).hasSize(1);
            assertThat(log.eventos("reserva.cancelacion.rechazada")).hasSize(1);
            assertThat(log.eventos("reserva.cancelacion.incierta")).hasSize(1);
            assertThat(log.eventos("reserva.cancelacion.denegada")).hasSize(1);
            assertThat(log.eventos("reserva.cancelacion.confirmada").get(0).getMDCPropertyMap()).containsKey("requestId");
            // El token y el motivo tampoco en nada que escriba el cliente HTTP a niveles normales.
            assertThat(log.list().stream().filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
                    .map(e -> e.getFormattedMessage()).reduce("", (a, b) -> a + b)).doesNotContain(TOKEN_TECNICO)
                    .doesNotContain("Cambio de planes");
        }
    }
}
