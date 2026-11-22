package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/reservations/{id}/phone} con la aplicación completa y PostgreSQL real, con un doble en lugar de Kafka:
 * quién puede mandar el teléfono, en qué estado, qué códigos de error se devuelven y que el número no se guarda, no se
 * devuelve y no se registra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
class PhoneApiTest {

    private static final String NUMERO = "+5492617778899";
    private static final String CLAVE = "clave-de-prueba-1";

    /** Un envío de mentira que recuerda lo que se le manda y puede fallar. */
    static class GatewayDePrueba implements PhoneGateway {
        final List<PhoneSubmission> sent = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public void submit(PhoneSubmission submission) {
            if (failure != null) {
                throw failure;
            }
            sent.add(submission);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracion {
        @Bean
        @Primary
        GatewayDePrueba gatewayDePrueba() {
            return new GatewayDePrueba();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;
    @Autowired ar.edu.um.cronos.application.port.out.UserRepository users;
    @Autowired GatewayDePrueba gateway;

    private int seq;

    @BeforeEach
    void limpiar() {
        gateway.sent.clear();
        gateway.failure = null;
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    private String tokenDe(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
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

    /** Un proceso del usuario, en el estado pedido, con vencimiento a diez minutos del reloj real de la aplicación. */
    private Reservation proceso(long userId, ReservationStatus estado, boolean pedido, Instant vence) {
        seq++;
        Instant t = Instant.now();
        var turno = new Slot(LocalDate.of(2030, 1, 1).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0));
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta", turno, "Juan", "Pérez", t));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, vence, t));
        if (estado == ReservationStatus.HELD) {
            return r;
        }
        r = reservations.save(r.waitingForPhone(vence, t));
        if (pedido) {
            r = reservations.save(r.informationRequested("request-event-" + seq, t));
        }
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8251, t));
            case EXPIRED -> reservations.save(r.expired(t));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    private Reservation esperando(long userId) {
        return proceso(userId, ReservationStatus.WAITING_FOR_PHONE, true, Instant.now().plusSeconds(600));
    }

    private ResultActions conCuerpo(String token, Object id, String json) throws Exception {
        var pedido = post("/api/reservations/" + id + "/phone").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    private ResultActions telefono(String token, Object id, String numero) throws Exception {
        return conCuerpo(token, id, "{\"phoneNumber\":\"" + numero + "\"}");
    }

    private String todoLoGuardado() {
        var out = new StringBuilder();
        for (String tabla : List.of("reservation_process", "processed_event", "unmatched_event")) {
            jdbc.queryForList("SELECT * FROM " + tabla).forEach(fila -> out.append(fila).append('\n'));
        }
        return out.toString();
    }

    // ---- el camino feliz -------------------------------------------------------------------------------------

    @Test
    void mandaElTelefonoResponde202YDejaMarcadoQueSeEnvio() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        telefono(token, r.id(), NUMERO).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(r.id()))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
                .andExpect(jsonPath("$.phoneSubmitted").value(true));

        assertThat(gateway.sent).singleElement().satisfies(s -> {
            assertThat(s.reservationProcessId()).isEqualTo(r.reservationProcessId());
            assertThat(s.holdId()).isEqualTo(r.holdId());
            assertThat(s.requestEventId()).isEqualTo(r.requestEventId());
            assertThat(s.phone().value()).isEqualTo(NUMERO);
            assertThat(s.eventId()).startsWith("phone-submitted-");
        });
        assertThat(jdbc.queryForObject("SELECT phone_submitted_at FROM reservation_process WHERE id = ?",
                java.sql.Timestamp.class, r.id())).isNotNull();
    }

    @Test
    void elNumeroSeLimpiaComoLoHaceLaCatedra() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        telefono(token, r.id(), "+54 9 (261) 777-8899").andExpect(status().isAccepted());

        assertThat(gateway.sent.get(0).phone().value()).isEqualTo(NUMERO);
    }

    @Test
    void elNumeroNoSeGuardaNiSeDevuelveEnNingunaParte() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        String cuerpo = telefono(token, r.id(), NUMERO).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("7778899").doesNotContain("phoneNumber");
        assertThat(todoLoGuardado()).as("ni en reservation_process ni en las tablas de eventos")
                .doesNotContain("7778899").doesNotContain("5492617778899");
    }

    @Test
    void elDuenoSeTomaDelTokenNoDelCuerpo() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        conCuerpo(token, r.id(), "{\"phoneNumber\":\"" + NUMERO + "\",\"userId\":999,\"status\":\"CONFIRMED\","
                + "\"requestEventId\":\"inventado\"}").andExpect(status().isAccepted());

        assertThat(gateway.sent.get(0).requestEventId()).as("el de la cátedra, no el del cuerpo")
                .isEqualTo(r.requestEventId());
        assertThat(reservations.findById(r.id()).orElseThrow().status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    // ---- quién puede ----------------------------------------------------------------------------------------

    @Test
    void sinTokenDa401YUnTokenSinRolDa403SinMandarNada() throws Exception {
        var r = esperando(tokenYUsuario());

        telefono(null, r.id(), NUMERO).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        telefono(tokenConRol("ROLE_INVITADO"), r.id(), NUMERO).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        assertThat(gateway.sent).isEmpty();
    }

    private long tokenYUsuario() throws Exception {
        tokenDe("dueno");
        return idDe("dueno");
    }

    @Test
    void laReservaDeOtroUsuarioYUnaInexistenteDanExactamenteElMismo404() throws Exception {
        String tokenAna = tokenDe("ana");
        tokenDe("beto");
        var deBeto = esperando(idDe("beto"));

        String ajena = telefono(tokenAna, deBeto.id(), NUMERO).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String inexistente = telefono(tokenAna, 987_654, NUMERO).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();

        assertThat(ajena.replace(String.valueOf(deBeto.id()), "ID")).isEqualTo(inexistente.replace("987654", "ID"));
        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.findById(deBeto.id()).orElseThrow().phoneSubmittedAt()).isNull();
    }

    // ---- el formato -----------------------------------------------------------------------------------------

    @Test
    void unFormatoInvalidoDa400SinRepetirElValorNiMandarNada() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        for (String malo : new String[]{"26155a1234-secreto", "123", "+", "1".repeat(60)}) {
            String cuerpo = telefono(token, r.id(), malo).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER")).andReturn().getResponse()
                    .getContentAsString();
            assertThat(cuerpo).doesNotContain("secreto").doesNotContain("26155a1234");
        }
        conCuerpo(token, r.id(), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
        conCuerpo(token, r.id(), "{\"phoneNumber\":null}").andExpect(status().isBadRequest());

        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.findById(r.id()).orElseThrow().phoneSubmittedAt()).isNull();
    }

    @Test
    void unCuerpoQueNoEsJsonYUnIdQueNoEsUnNumeroDan400() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));

        String roto = conCuerpo(token, r.id(), "{\"phoneNumber\":\"" + NUMERO + "\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn().getResponse().getContentAsString();
        telefono(token, "abc", NUMERO).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(roto).doesNotContain("7778899");
        assertThat(gateway.sent).isEmpty();
    }

    // ---- en qué estado --------------------------------------------------------------------------------------

    @Test
    void siLaCatedraTodaviaNoPidioElTelefonoDa409PhoneNotRequested() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        var enHeld = proceso(ana, ReservationStatus.HELD, false, Instant.now().plusSeconds(600));
        var sinPedido = proceso(ana, ReservationStatus.WAITING_FOR_PHONE, false, Instant.now().plusSeconds(600));

        telefono(token, enHeld.id(), NUMERO).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_NOT_REQUESTED"));
        telefono(token, sinPedido.id(), NUMERO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_NOT_REQUESTED"));

        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void unaReservaYaConfirmadaOVencidaDa409NotAwaitingPhone() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        var confirmada = proceso(ana, ReservationStatus.CONFIRMED, true, Instant.now().plusSeconds(600));
        var expirada = proceso(ana, ReservationStatus.EXPIRED, true, Instant.now().plusSeconds(600));

        telefono(token, confirmada.id(), NUMERO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_AWAITING_PHONE"));
        telefono(token, expirada.id(), NUMERO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_AWAITING_PHONE"));

        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void unProcesoCuyoVencimientoYaPasoDa409ReservationExpiredSinCambiarSuEstado() throws Exception {
        String token = tokenDe("ana");
        var r = proceso(idDe("ana"), ReservationStatus.WAITING_FOR_PHONE, true, Instant.now().minusSeconds(5));

        telefono(token, r.id(), NUMERO).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.findById(r.id()).orElseThrow().status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void mandarOtroTelefonoSinQueLaCatedraRespondaDa409PhoneAlreadySubmitted() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));
        telefono(token, r.id(), NUMERO).andExpect(status().isAccepted());

        telefono(token, r.id(), "2615550000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_SUBMITTED"));

        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void despuesDeUnRechazoDeLaCatedraSePuedeMandarOtro() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));
        telefono(token, r.id(), NUMERO).andExpect(status().isAccepted());
        // Lo que haría el consumidor al recibir AdditionalInformationRejected.
        reservations.save(reservations.findById(r.id()).orElseThrow().informationRejected("INVALID_PHONE_NUMBER", Instant.now()));

        telefono(token, r.id(), "2615550000").andExpect(status().isAccepted()).andExpect(jsonPath("$.phoneSubmitted").value(true));

        assertThat(gateway.sent).hasSize(2);
        assertThat(gateway.sent.get(1).eventId()).isNotEqualTo(gateway.sent.get(0).eventId());
    }

    // ---- si Kafka falla --------------------------------------------------------------------------------------

    @Test
    void siKafkaNoConfirmaDa503NadaQuedaMarcadoYSePuedeReintentar() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));
        gateway.failure = new PhoneUnavailableException(new IllegalStateException("broker caído"));

        telefono(token, r.id(), NUMERO).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PHONE_DELIVERY_UNAVAILABLE"));

        assertThat(reservations.findById(r.id()).orElseThrow().phoneSubmittedAt()).isNull();
        gateway.failure = null;
        telefono(token, r.id(), NUMERO).andExpect(status().isAccepted());
    }

    // ---- el número no se registra ----------------------------------------------------------------------------

    @Test
    void elNumeroNoApareceEnElLogEnNingunCasoAunConTodoEnTrace() throws Exception {
        String token = tokenDe("ana");
        var r = esperando(idDe("ana"));
        var otra = esperando(idDe("ana"));
        try (var log = new LogCapture()) {
            telefono(token, r.id(), NUMERO).andExpect(status().isAccepted());
            telefono(token, r.id(), NUMERO).andExpect(status().isConflict());
            telefono(token, otra.id(), "26155a1234-7778899").andExpect(status().isBadRequest());
            gateway.failure = new PhoneUnavailableException(new IllegalStateException("x"));
            telefono(token, otra.id(), NUMERO).andExpect(status().isServiceUnavailable());
            conCuerpo(token, otra.id(), "{\"phoneNumber\":\"" + NUMERO + "\"").andExpect(status().isBadRequest());

            assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
            assertThat(log.text()).doesNotContain("5492617778899").doesNotContain("7778899");
            assertThat(log.eventos("reserva.telefono.enviado")).hasSize(1);
            assertThat(log.eventos("reserva.telefono.rechazado")).extracting(e -> e.getFormattedMessage()).hasSize(2)
                    .anySatisfy(m -> assertThat(m).contains("motivo=ya_enviado_sin_respuesta"))
                    .anySatisfy(m -> assertThat(m).contains("motivo=formato_invalido"));
            assertThat(log.eventos("reserva.telefono.no_enviado")).hasSize(1);
            // Cada línea del pedido lleva su identificador.
            assertThat(log.eventos("reserva.telefono.enviado").get(0).getMDCPropertyMap()).containsKey("requestId");
        }
    }
}
