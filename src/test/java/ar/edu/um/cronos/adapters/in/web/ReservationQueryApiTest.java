package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consultar mis reservas con la aplicación completa y PostgreSQL real: cada usuario ve solo las suyas (sin acceso cruzado),
 * el orden, el filtro, la paginación, los errores, y que se responde sin llamar a la cátedra ni a atlas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
class ReservationQueryApiTest {

    private static final String CLAVE = "clave-de-prueba-1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;

    private int seq;

    @BeforeEach
    void limpiar() {
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

    /** Una reserva del usuario creada hace {@code haceSegundos}, en el estado pedido. */
    private Reservation reserva(long userId, long haceSegundos, ReservationStatus estado) {
        seq++;
        Instant creada = Instant.now().minusSeconds(haceSegundos);
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2031, 1, 1).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0)),
                "Juan", "Pérez", creada));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-secreto-" + seq, "process-secreto-" + seq, creada.plusSeconds(600), creada));
        if (estado == ReservationStatus.HELD) {
            return r;
        }
        r = reservations.save(r.waitingForPhone(creada.plusSeconds(600), creada)
                .informationRequested("request-event-secreto-" + seq, creada));
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8000 + seq, creada));
            case EXPIRED -> reservations.save(r.expired(creada));
            case INVALID -> reservations.save(r.invalid("REQUEST_EVENT_MISMATCH", creada));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    private ResultActions pedir(String ruta, String token) throws Exception {
        var pedido = get(ruta);
        if (token != null) {
            pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    // ---- una reserva ------------------------------------------------------------------------------------------

    @Test
    void elDuenoVeSuReservaYEnQueVa() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), 60, ReservationStatus.WAITING_FOR_PHONE);

        pedir("/api/reservations/" + r.id(), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(r.id()))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
                .andExpect(jsonPath("$.professionalId").value(218))
                .andExpect(jsonPath("$.professionalFirstName").value("Romina"))
                .andExpect(jsonPath("$.patientFirstName").value("Juan"))
                .andExpect(jsonPath("$.phoneSubmitted").value(false))
                .andExpect(jsonPath("$.lastRejectionReason").value(nullValue()))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void elUsuarioVeElResultadoQueInformoLaCatedra() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        var confirmada = reserva(ana, 50, ReservationStatus.CONFIRMED);
        var expirada = reserva(ana, 40, ReservationStatus.EXPIRED);
        var invalida = reserva(ana, 30, ReservationStatus.INVALID);
        var rechazada = reservations.save(reserva(ana, 20, ReservationStatus.WAITING_FOR_PHONE)
                .phoneSubmitted(Instant.now()).informationRejected("INVALID_PHONE_NUMBER", Instant.now()));

        pedir("/api/reservations/" + confirmada.id(), token).andExpect(jsonPath("$.status").value("CONFIRMED"));
        pedir("/api/reservations/" + expirada.id(), token).andExpect(jsonPath("$.status").value("EXPIRED"));
        pedir("/api/reservations/" + invalida.id(), token).andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.failureCode").value("REQUEST_EVENT_MISMATCH"));
        // Un teléfono rechazado no cierra el proceso: sigue esperando y el usuario sabe por qué y que puede mandar otro.
        pedir("/api/reservations/" + rechazada.id(), token).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
                .andExpect(jsonPath("$.lastRejectionReason").value("INVALID_PHONE_NUMBER"))
                .andExpect(jsonPath("$.phoneSubmitted").value(false));
    }

    @Test
    void laRespuestaNoExponeLoInternoDeLaCatedraNiDelUsuario() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), 60, ReservationStatus.CONFIRMED);

        String cuerpo = pedir("/api/reservations/" + r.id(), token).andReturn().getResponse().getContentAsString();
        String lista = pedir("/api/reservations", token).andReturn().getResponse().getContentAsString();

        for (String texto : new String[]{cuerpo, lista}) {
            assertThat(texto).doesNotContain("secreto").doesNotContain("holdId").doesNotContain("reservationProcessId")
                    .doesNotContain("requestEventId").doesNotContain("userId").doesNotContain("catedraReservationId")
                    .doesNotContain("phoneNumber");
        }
    }

    @Test
    void laReservaDeOtroUsuarioYUnaInexistenteDanExactamenteElMismo404() throws Exception {
        String tokenAna = tokenDe("ana");
        tokenDe("beto");
        var deBeto = reserva(idDe("beto"), 60, ReservationStatus.CONFIRMED);

        String ajena = pedir("/api/reservations/" + deBeto.id(), tokenAna).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String inexistente = pedir("/api/reservations/987654", tokenAna).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();

        assertThat(ajena.replace("/api/reservations/" + deBeto.id(), "RUTA")).isEqualTo(inexistente.replace("/api/reservations/987654", "RUTA"));
        assertThat(ajena).doesNotContain("Romina").doesNotContain("CONFIRMED");
    }

    @Test
    void sinTokenDa401UnTokenSinRolDa403YUnIdQueNoEsUnNumeroDa400() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), 60, ReservationStatus.CONFIRMED);

        pedir("/api/reservations/" + r.id(), null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO")).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        pedir("/api/reservations", null).andExpect(status().isUnauthorized());
        pedir("/api/reservations/" + r.id(), tokenConRol("ROLE_INVITADO")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        pedir("/api/reservations", tokenConRol("ROLE_INVITADO")).andExpect(status().isForbidden());
        pedir("/api/reservations/abc", token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void soloSePuedeLeerLaReservaNoBorrarlaNiCambiarlaPorEstaRuta() throws Exception {
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), 60, ReservationStatus.CONFIRMED);
        String auth = "Bearer " + token;

        mvc.perform(delete("/api/reservations/" + r.id()).header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
        mvc.perform(put("/api/reservations/" + r.id()).header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isForbidden());
        pedir("/api/reservations/" + r.id() + "/phone", token).andExpect(status().isForbidden());

        assertThat(reservations.findById(r.id()).orElseThrow().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // ---- el listado -------------------------------------------------------------------------------------------

    @Test
    void cadaUsuarioVeSoloLasSuyasLaMasRecientePrimero() throws Exception {
        String tokenAna = tokenDe("ana");
        String tokenBeto = tokenDe("beto");
        long ana = idDe("ana");
        long beto = idDe("beto");
        var a1 = reserva(ana, 300, ReservationStatus.CONFIRMED);
        var a2 = reserva(ana, 200, ReservationStatus.EXPIRED);
        var a3 = reserva(ana, 100, ReservationStatus.WAITING_FOR_PHONE);
        var b1 = reserva(beto, 150, ReservationStatus.CONFIRMED);
        var b2 = reserva(beto, 50, ReservationStatus.CONFIRMED);

        pedir("/api/reservations", tokenAna).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(a3.id())).andExpect(jsonPath("$.items[1].id").value(a2.id()))
                .andExpect(jsonPath("$.items[2].id").value(a1.id())).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalPages").value(1));
        pedir("/api/reservations", tokenBeto).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(b2.id())).andExpect(jsonPath("$.items[1].id").value(b1.id()));
    }

    @Test
    void unUsuarioSinReservasRecibeUnaListaVacia() throws Exception {
        pedir("/api/reservations", tokenDe("ana")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void sePuedeFiltrarPorEstado() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        var confirmada = reserva(ana, 300, ReservationStatus.CONFIRMED);
        reserva(ana, 200, ReservationStatus.EXPIRED);
        reserva(ana, 100, ReservationStatus.WAITING_FOR_PHONE);
        tokenDe("beto");
        reserva(idDe("beto"), 50, ReservationStatus.CONFIRMED);

        pedir("/api/reservations?status=CONFIRMED", token).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(confirmada.id())).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void paginaYDevuelveElTotal() throws Exception {
        String token = tokenDe("ana");
        long ana = idDe("ana");
        for (int i = 0; i < 5; i++) {
            reserva(ana, 500 - i * 10, ReservationStatus.CONFIRMED);
        }

        pedir("/api/reservations?page=0&size=2", token).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalItems").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        pedir("/api/reservations?page=2&size=2", token).andExpect(jsonPath("$.items", hasSize(1)));
        pedir("/api/reservations?page=9&size=2", token).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(5));
    }

    @Test
    void unParametroInvalidoDa400NombrandoElParametroSinRepetirElValor() throws Exception {
        String token = tokenDe("ana");

        for (String consulta : new String[]{"status=INVENTADO", "size=0", "size=101", "page=-1", "size=abc", "page=1.5"}) {
            String cuerpo = pedir("/api/reservations?" + consulta, token).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).as(consulta).doesNotContain("INVENTADO").doesNotContain("abc");
        }
    }

    @Test
    void nadaPermitePedirLasReservasDeOtroUsuario() throws Exception {
        String tokenAna = tokenDe("ana");
        tokenDe("beto");
        var deBeto = reserva(idDe("beto"), 60, ReservationStatus.CONFIRMED);

        // Ningún parámetro de este tipo existe: se ignoran y se devuelven solo las del token.
        pedir("/api/reservations?userId=" + idDe("beto") + "&user=beto&login=beto&owner=beto", tokenAna)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(reservations.findById(deBeto.id())).isPresent();
    }

    @Test
    void respondeSinLlamarANadieAunqueLaCatedraYAtlasEstenCaidos() throws Exception {
        // La configuración de las pruebas apunta a hosts que no existen (catedra.invalid, atlas.invalid): si la consulta
        // llamara a alguno, fallaría. Enunciado 8: seguir atendiendo lo que depende solo de datos propios.
        String token = tokenDe("ana");
        var r = reserva(idDe("ana"), 60, ReservationStatus.CONFIRMED);

        long inicio = System.nanoTime();
        pedir("/api/reservations", token).andExpect(status().isOk());
        pedir("/api/reservations/" + r.id(), token).andExpect(status().isOk());

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
    }
}
