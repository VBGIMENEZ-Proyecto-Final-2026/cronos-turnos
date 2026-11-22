package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iniciar una reserva de punta a punta: la aplicación completa con PostgreSQL real y dos servidores HTTP simulados en
 * lugar de atlas y de la cátedra, con tokens reales emitidos por el login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
// Un @ContextConfiguration directo reemplaza al de @IntegrationTestSupport: hay que nombrar los dos inicializadores.
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, ReservationApiTest.Servidores.class})
class ReservationApiTest {

    private static final String CLAVE = "clave-de-prueba-123";
    private static final String TOKEN_TECNICO = "token-tecnico-de-prueba-reservas";

    static final StubServer ATLAS = new StubServer();
    static final StubServer CATEDRA = new StubServer();

    /** Un martes lejano: ningún turno "ya empezó" aunque se corra a cualquier hora. */
    private static final LocalDate MARTES = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).plusWeeks(3);

    private static final String PROFESIONAL = profesional(true);
    private static final String HOLD = "{\"holdId\":\"hold-6c0c2f41\",\"reservationProcessId\":\"process-a92b47d0\","
            + "\"status\":\"HELD\",\"expiresAt\":\"2030-01-01T10:00:00Z\"}";
    private static final String CONFIRMADO = "{\"reservationProcessId\":\"process-a92b47d0\","
            + "\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2030-01-01T10:01:00Z\"}";

    private static String profesional(boolean habilitado) {
        return "{\"id\":218,\"firstName\":\"Romina\",\"lastName\":\"Acosta\",\"enabled\":" + habilitado
                + ",\"schedules\":[{\"dayOfWeek\":\"TUESDAY\",\"startTime\":\"13:00:00\",\"endTime\":\"16:00:00\","
                + "\"slotDurationMinutes\":30,\"enabled\":true}]}";
    }

    /** Apunta atlas y la cátedra a los servidores de mentira. Corre después del inicializador base y le gana. */
    @Order(100)
    static class Servidores implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "cronos.atlas.base-url=" + ATLAS.url(),
                    "catedra.rest.base-url=" + CATEDRA.url(),
                    "cronos.atlas.max-retries=0",
                    "catedra.rest.max-retries=0",
                    "catedra.rest.id-token=" + TOKEN_TECNICO).applyTo(context);
        }
    }

    @AfterAll
    static void apagar() {
        ATLAS.close();
        CATEDRA.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        ATLAS.reset();
        CATEDRA.reset();
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
        ATLAS.reply(Reply.json(200, PROFESIONAL));
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

    private static String cuerpo(String hora) {
        return "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"" + hora
                + "\",\"patientFirstName\":\"Juan\",\"patientLastName\":\"Pérez\"}";
    }

    private ResultActions reservar(String token, String json) throws Exception {
        var pedido = post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    private ResultActions reservar(String token) throws Exception {
        return reservar(token, cuerpo("14:30"));
    }

    private static String tokenConRol(String rol) {
        Instant ahora = Instant.now();
        var usuario = new User(1L, "otro", "hash", null, null, null, null, "es", true, Set.of(rol), ahora, ahora);
        return new NimbusTokenIssuer((RSAPrivateKey) TestKeys.privateKey(), (RSAPublicKey) TestKeys.publicKey(),
                Duration.ofHours(1), Duration.ofDays(30), Clock.systemUTC()).issue(usuario, false);
    }

    private List<Map<String, Object>> filas() {
        return jdbc.queryForList("SELECT * FROM reservation_process ORDER BY id");
    }

    private long pedidosACatedra(String metodoYRuta) {
        return CATEDRA.requests().stream().filter(r -> (r.method() + " " + r.uri()).startsWith(metodoYRuta)).count();
    }

    // ---- el camino feliz -------------------------------------------------------------------------------------

    @Test
    void iniciaLaReservaYLaDejaEsperandoElTelefonoAsociadaAlUsuarioDelToken() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));

        reservar(token).andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
                .andExpect(jsonPath("$.professionalId").value(218))
                .andExpect(jsonPath("$.professionalFirstName").value("Romina"))
                .andExpect(jsonPath("$.date").value(MARTES.toString()))
                .andExpect(jsonPath("$.startTime").value("14:30:00"))
                .andExpect(jsonPath("$.endTime").value("15:00:00"))
                .andExpect(jsonPath("$.patientFirstName").value("Juan"))
                .andExpect(jsonPath("$.patientLastName").value("Pérez"))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T10:01:00Z"));

        var filas = filas();
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("user_id")).isEqualTo(idDe("ana"));
        assertThat(filas.get(0).get("status")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(filas.get(0).get("hold_id")).isEqualTo("hold-6c0c2f41");
        assertThat(filas.get(0).get("reservation_process_id")).isEqualTo("process-a92b47d0");
    }

    @Test
    void laRespuestaNoExponeLosIdentificadoresInternosDeLaCatedraNiAlUsuario() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));

        String cuerpo = reservar(token).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString();

        assertThat(cuerpo).doesNotContain("hold-6c0c2f41", "process-a92b47d0", "holdId", "reservationProcessId",
                "userId", "user_id", "failureCode");
    }

    @Test
    void laCatedraRecibeElTurnoElPacienteOpacoYElTokenTecnicoYAtlasElTokenDelUsuario() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));

        reservar(token).andExpect(status().isAccepted());

        assertThat(CATEDRA.requests()).hasSize(2);
        var hold = CATEDRA.requests().get(0);
        assertThat(hold.method() + " " + hold.uri()).isEqualTo("POST /api/appointment-holds");
        assertThat(hold.body()).contains("\"professionalId\":218", "\"date\":\"" + MARTES + "\"",
                "\"startTime\":\"14:30\"", "\"externalPatientId\":\"user-" + idDe("ana") + "\"");
        assertThat(hold.body()).as("la cátedra no conoce el login").doesNotContain("ana\"");
        var confirmacion = CATEDRA.requests().get(1);
        assertThat(confirmacion.uri()).isEqualTo("/api/appointment-holds/hold-6c0c2f41/confirm");
        assertThat(confirmacion.body()).contains("\"patientFirstName\":\"Juan\"", "\"patientLastName\":\"Pérez\"",
                "\"externalPatientId\":\"user-" + idDe("ana") + "\"");
        assertThat(hold.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        assertThat(confirmacion.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        assertThat(ATLAS.requests()).hasSize(1);
        assertThat(ATLAS.requests().get(0).header("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void elClienteNoPuedeElegirDuenoIdEstadoNiPacienteDeLaCatedra() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        String malicioso = "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\","
                + "\"patientFirstName\":\"Juan\",\"patientLastName\":\"Pérez\",\"id\":999,\"userId\":1,"
                + "\"status\":\"CONFIRMED\",\"externalPatientId\":\"user-1\",\"holdId\":\"hold-ajeno\","
                + "\"reservationProcessId\":\"process-ajeno\"}";

        reservar(token, malicioso).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(999)))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));

        assertThat(filas().get(0).get("user_id")).isEqualTo(idDe("ana"));
        assertThat(CATEDRA.requests().get(0).body()).contains("\"externalPatientId\":\"user-" + idDe("ana") + "\"")
                .doesNotContain("user-1\"", "hold-ajeno");
    }

    // ---- el mismo pedido dos veces / dos usuarios ---------------------------------------------------------------

    @Test
    void elMismoPedidoDosVecesNoCreaDosHoldsYDevuelveElMismoProceso() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));

        String primero = reservar(token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String segundo = reservar(token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(segundo).isEqualTo(primero);
        assertThat(pedidosACatedra("POST /api/appointment-holds")).isEqualTo(2); // el hold y su confirmación, una vez
        assertThat(pedidosACatedra("POST /api/appointment-holds/")).isEqualTo(1);
        assertThat(filas()).hasSize(1);
    }

    @Test
    void conUnVencimientoConNanosegundosComoElDeLaCatedraRealElPedidoRepetidoResponderExactamenteLoMismo()
            throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD.replace("2030-01-01T10:00:00Z", "2030-01-01T10:00:00.905856025Z")),
                Reply.json(202, CONFIRMADO.replace("2030-01-01T10:01:00Z", "2030-01-01T10:01:00.123456789Z")));

        String primero = reservar(token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String segundo = reservar(token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(primero).contains("\"expiresAt\":\"2030-01-01T10:01:00.123456Z\"");
        assertThat(segundo).isEqualTo(primero);
    }

    @Test
    void siElHoldYaVencioEnLaCatedraPedirDeNuevoElMismoTurnoCierraElProcesoViejoYCreaUnoNuevo() throws Exception {
        String token = tokenDe("ana");
        // El vencimiento ya pasó (lo que se vio con la cátedra real: el hold vence solo y cronos todavía no se entera).
        CATEDRA.reply(Reply.json(201, HOLD.replace("2030-01-01T10:00:00Z", "2020-01-01T10:00:00Z")),
                Reply.json(202, CONFIRMADO.replace("2030-01-01T10:01:00Z", "2020-01-01T10:01:00Z")));
        long primero = ((Number) com.jayway.jsonpath.JsonPath.read(
                reservar(token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();

        CATEDRA.reset();
        CATEDRA.reply(Reply.json(201, "{\"holdId\":\"hold-nuevo\",\"reservationProcessId\":\"process-nuevo\","
                + "\"status\":\"HELD\",\"expiresAt\":\"2030-01-01T10:00:00Z\"}"), Reply.json(202, CONFIRMADO.replace(
                "process-a92b47d0", "process-nuevo")));
        reservar(token).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not((int) primero)))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));

        assertThat(filas()).extracting(f -> f.get("status")).containsExactly("EXPIRED", "WAITING_FOR_PHONE");
        assertThat(pedidosACatedra("POST /api/appointment-holds")).as("el hold nuevo y su confirmación").isEqualTo(2);
    }

    @Test
    void siOtroUsuarioPideElMismoTurnoYLaCatedraYaLoTieneTomadoRecibeUn409YSuProcesoQuedaFallido() throws Exception {
        String ana = tokenDe("ana");
        String beto = tokenDe("beto");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO), Reply.problem(409, "SLOT_ALREADY_HELD"));

        reservar(ana).andExpect(status().isAccepted());
        reservar(beto).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SLOT_ALREADY_HELD"));

        var filas = filas();
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).get("user_id")).isEqualTo(idDe("ana"));
        assertThat(filas.get(0).get("status")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(filas.get(1).get("user_id")).isEqualTo(idDe("beto"));
        assertThat(filas.get(1).get("status")).isEqualTo("FAILED");
        assertThat(filas.get(1).get("failure_code")).isEqualTo("SLOT_ALREADY_HELD");
    }

    // ---- validación y autenticación ------------------------------------------------------------------------

    @Test
    void unCuerpoInvalidoDa400SinLlamarANadieNiGuardarNada() throws Exception {
        String token = tokenDe("ana");
        String[] cuerpos = {
                "{}",
                "",
                "esto no es json",
                "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\",\"patientFirstName\":\"J\","
                        + "\"patientLastName\":\"Pérez\"}",
                "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\",\"patientFirstName\":\"Juan\","
                        + "\"patientLastName\":\"  \"}",
                "{\"professionalId\":-1,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\",\"patientFirstName\":\"Juan\","
                        + "\"patientLastName\":\"Pérez\"}",
                "{\"professionalId\":218,\"date\":\"mañana\",\"startTime\":\"14:30\",\"patientFirstName\":\"Juan\","
                        + "\"patientLastName\":\"Pérez\"}",
                "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"tarde\",\"patientFirstName\":\"Juan\","
                        + "\"patientLastName\":\"Pérez\"}",
                "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\",\"patientFirstName\":\""
                        + "x".repeat(101) + "\",\"patientLastName\":\"Pérez\"}"
        };
        for (String json : cuerpos) {
            reservar(token, json).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        assertThat(ATLAS.requests()).isEmpty();
        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    @Test
    void sinTokenDa401YUnTokenSinRolDa403SinLlamarANadie() throws Exception {
        reservar(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        reservar(tokenConRol("ROLE_INVITADO")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        assertThat(ATLAS.requests()).isEmpty();
        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    @Test
    void unTokenValidoDeUnUsuarioQueYaNoExisteDa401() throws Exception {
        String token = tokenDe("fantasma");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");

        reservar(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));

        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void soloSePermitePostYNoHayListadoTodavia() throws Exception {
        String token = tokenDe("ana");

        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ---- lo que dice atlas -----------------------------------------------------------------------------------

    @Test
    void unProfesionalInexistenteDa404SinCrearNadie() throws Exception {
        String token = tokenDe("ana");
        ATLAS.reset();
        ATLAS.reply(Reply.problem(404, "PROFESSIONAL_NOT_FOUND"));

        reservar(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    @Test
    void unProfesionalDeshabilitadoDa400SinCrearNada() throws Exception {
        String token = tokenDe("ana");
        ATLAS.reset();
        ATLAS.reply(Reply.json(200, profesional(false)));

        reservar(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFESSIONAL_DISABLED"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    @Test
    void unTurnoQueNoEstaEnLaAgendaDa400SinCrearNada() throws Exception {
        String token = tokenDe("ana");

        reservar(token, cuerpo("14:45")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SLOT"));
        reservar(token, cuerpo("17:00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SLOT"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    @Test
    void unTurnoPasadoDa400() throws Exception {
        String token = tokenDe("ana");
        String ayerMartes = "{\"professionalId\":218,\"date\":\"2020-01-07\",\"startTime\":\"14:30\","
                + "\"patientFirstName\":\"Juan\",\"patientLastName\":\"Pérez\"}";

        reservar(token, ayerMartes).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SLOT"));

        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void siAtlasEstaCaidoDa503SinCrearNada() throws Exception {
        String token = tokenDe("ana");
        ATLAS.reset();
        ATLAS.reply(Reply.problem(503, "DOWN"));

        reservar(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(filas()).isEmpty();
    }

    // ---- lo que dice la cátedra: el hold ---------------------------------------------------------------------

    @Test
    void cadaRechazoDelHoldSeTraduceYDejaElProcesoFallidoConSuMotivo() throws Exception {
        Object[][] casos = {
                {409, "SLOT_ALREADY_HELD", 409, "SLOT_ALREADY_HELD"},
                {409, "SLOT_ALREADY_RESERVED", 409, "SLOT_ALREADY_RESERVED"},
                {400, "INVALID_SLOT", 400, "INVALID_SLOT"},
                {400, "PROFESSIONAL_DISABLED", 400, "PROFESSIONAL_DISABLED"},
                {404, "PROFESSIONAL_NOT_FOUND", 404, "PROFESSIONAL_NOT_FOUND"},
                {403, "HOLD_OWNER_MISMATCH", 502, "CATEDRA_ERROR"},
                {400, "VALIDATION_ERROR", 502, "CATEDRA_ERROR"},
                {400, "CODIGO_NUEVO", 502, "CATEDRA_ERROR"},
        };
        String token = tokenDe("ana");
        for (Object[] caso : casos) {
            jdbc.update("DELETE FROM reservation_process");
            CATEDRA.reset();
            CATEDRA.reply(Reply.problem((int) caso[0], (String) caso[1]));

            reservar(token).andExpect(status().is((int) caso[2]))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value((String) caso[3]));

            var filas = filas();
            assertThat(filas).as(caso[1].toString()).hasSize(1);
            assertThat(filas.get(0).get("status")).isEqualTo("FAILED");
            assertThat(filas.get(0).get("failure_code")).isIn((String) caso[1], "UNEXPECTED");
        }
    }

    @Test
    void despuesDeUnHoldRechazadoSePuedeVolverAIntentar() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.problem(409, "SLOT_ALREADY_HELD"));
        reservar(token).andExpect(status().isConflict());

        CATEDRA.reset();
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        reservar(token).andExpect(status().isAccepted());

        assertThat(filas()).extracting(f -> f.get("status")).containsExactly("FAILED", "WAITING_FOR_PHONE");
    }

    @Test
    void siLaCatedraNoRespondeAlPedirElHoldDa503YElProcesoSeCierraComoFallido() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.problem(503, "DOWN"));

        reservar(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(filas().get(0).get("status")).isEqualTo("FAILED");
        assertThat(filas().get(0).get("failure_code")).isEqualTo("CATEDRA_UNAVAILABLE");
        assertThat(pedidosACatedra("POST /api/appointment-holds")).as("sin reintentos").isEqualTo(1);
    }

    // ---- lo que dice la cátedra: la confirmación -----------------------------------------------------------------

    @Test
    void siElHoldVencioAntesDeConfirmarDa409ConSuCodigoYElProcesoExpira() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.problem(404, "HOLD_EXPIRED"));

        reservar(token).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        assertThat(filas().get(0).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    void siNoSeSabeSiConfirmoElProcesoQuedaHeldYRepetirElPedidoRetomaSoloLaConfirmacion() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.problem(503, "DOWN"), Reply.problem(503, "DOWN"));

        reservar(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(filas().get(0).get("status")).isEqualTo("HELD");
        assertThat(filas().get(0).get("hold_id")).isEqualTo("hold-6c0c2f41");
        assertThat(pedidosACatedra("POST /api/appointment-holds/")).as("la confirmación y una reconciliación")
                .isEqualTo(2);

        // La cátedra vuelve: el mismo pedido retoma desde la confirmación, sin crear otro hold.
        CATEDRA.reset();
        CATEDRA.reply(Reply.json(202, CONFIRMADO));
        reservar(token).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));

        assertThat(pedidosACatedra("POST /api/appointment-holds")).isEqualTo(1);
        assertThat(pedidosACatedra("POST /api/appointment-holds/hold-6c0c2f41/confirm")).isEqualTo(1);
        assertThat(filas()).hasSize(1);
        assertThat(filas().get(0).get("status")).isEqualTo("WAITING_FOR_PHONE");
    }

    @Test
    void unaConfirmacionQueYaEstabaHechaSeTomaComoExito() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.problem(409, "RESERVATION_PROCESS_ALREADY_CONFIRMED"));

        reservar(token).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));
    }

    // ---- los eventos del log (norma de logs, ADR 0006) --------------------------------------------------------

    @Test
    void elCaminoFelizDejaUnaLineaPorHitoEnOrdenYTodasConElMismoRequestId() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        try (var log = new LogCapture()) {
            reservar(token).andExpect(status().isAccepted());

            var lineas = log.list().stream().map(e -> e.getFormattedMessage())
                    .filter(m -> m.startsWith("evento=reserva.") || m.startsWith("evento=http.pedido")).toList();
            assertThat(lineas).hasSize(4);
            assertThat(lineas.get(0)).startsWith("evento=reserva.proceso.iniciado usuario=" + idDe("ana") + " reserva=")
                    .contains("profesional=218 fecha=" + MARTES + " hora=14:30");
            assertThat(lineas.get(1)).startsWith("evento=reserva.hold.creado usuario=" + idDe("ana") + " reserva=")
                    .contains("proceso=process-a92b47d0 hold=hold-6c0c2f41");
            assertThat(lineas.get(2)).startsWith("evento=reserva.hold.confirmado usuario=" + idDe("ana"))
                    .contains("proceso=process-a92b47d0 estado=WAITING_FOR_PHONE")
                    .contains("siguiente=espera_el_telefono");
            assertThat(lineas.get(3)).startsWith("evento=http.pedido.resuelto metodo=POST ruta=/api/reservations estado=202");
            // Todas las líneas del pedido comparten el identificador, así se las sigue juntas.
            var ids = log.list().stream().filter(e -> e.getFormattedMessage().startsWith("evento=reserva."))
                    .map(e -> e.getMDCPropertyMap().get("requestId")).distinct().toList();
            assertThat(ids).hasSize(1).doesNotContainNull();
            // Ni el paciente ni la cátedra: solo ids. (Lo que escriben Spring o el driver con todo en TRACE no es de
            // cronos y es un límite documentado; la norma controla lo que escribe cronos.)
            assertThat(log.list().stream().filter(e -> e.getLoggerName().startsWith("ar.edu.um.cronos"))
                    .map(e -> e.getFormattedMessage()).toList()).isNotEmpty()
                    .noneMatch(m -> m.contains("Juan") || m.contains("Pérez"));
        }
    }

    @Test
    void unHoldRechazadoSeRegistraConSuEtapaYSuMotivo() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.problem(409, "SLOT_ALREADY_HELD"));
        try (var log = new LogCapture()) {
            reservar(token).andExpect(status().isConflict());

            assertThat(log.eventos("reserva.proceso.fallido")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("etapa=hold motivo=SLOT_ALREADY_HELD")
                            .contains("accion=proceso_cerrado"));
            assertThat(log.eventos("catedra.operacion.rechazada")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("operacion=crear_hold estado=409"));
        }
    }

    @Test
    void siNoSeSabeSiConfirmoSeRegistraLaReconciliacionYLaIncertidumbre() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.problem(503, "X"), Reply.problem(503, "X"));
        try (var log = new LogCapture()) {
            reservar(token).andExpect(status().isServiceUnavailable());

            assertThat(log.eventos("reserva.confirmacion.reconciliando")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("proceso=process-a92b47d0 intento=2"));
            assertThat(log.eventos("reserva.confirmacion.incierta")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("intentos=2")
                            .contains("accion=queda_en_HELD_repetir_el_pedido_la_retoma"));
        }
    }

    @Test
    void repetirUnPedidoSeRegistraConLoQueSeHizo() throws Exception {
        String token = tokenDe("ana");
        CATEDRA.reply(Reply.json(201, HOLD), Reply.json(202, CONFIRMADO));
        reservar(token).andExpect(status().isAccepted());
        try (var log = new LogCapture()) {
            reservar(token).andExpect(status().isAccepted());

            assertThat(log.eventos("reserva.pedido.repetido")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("estado=WAITING_FOR_PHONE")
                            .contains("accion=se_devuelve_el_existente"));
        }
    }

    // ---- secretos y datos personales ------------------------------------------------------------------------

    @Test
    void niLosTokensNiLosNombresDelPacienteApareceEnLosLogsAunConTodoFallando() throws Exception {
        String token = tokenDe("ana");
        String conNombres = "{\"professionalId\":218,\"date\":\"" + MARTES + "\",\"startTime\":\"14:30\","
                + "\"patientFirstName\":\"NombreSecreto\",\"patientLastName\":\"ApellidoSecreto\"}";
        try (var log = new LogCapture()) {
            CATEDRA.reply(Reply.problem(409, "SLOT_ALREADY_HELD"));
            reservar(token, conNombres).andExpect(status().isConflict());

            CATEDRA.reset();
            CATEDRA.reply(Reply.json(201, HOLD), Reply.problem(401, "X"));
            reservar(token, conNombres.replace("14:30", "15:00")).andExpect(status().isServiceUnavailable());

            CATEDRA.reset();
            ATLAS.reset();
            ATLAS.reply(Reply.problem(503, "DOWN"));
            reservar(token, conNombres.replace("14:30", "15:30")).andExpect(status().isServiceUnavailable());

            assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
            // Los tokens no aparecen en ninguna parte, ni siquiera con todo en TRACE.
            assertThat(log.text()).doesNotContain(token, TOKEN_TECNICO);
            // Los nombres del paciente no los escribe ninguna clase de cronos. Con TRACE en org.hibernate o en
            // org.postgresql, Hibernate y el driver escriben los valores que enlazan al SQL (incluidos los datos que
            // se guardan): es un límite conocido, como el del rejected value de Spring, y no se activa fuera del
            // desarrollo.
            assertThat(log.textExcluding("org.hibernate", "org.postgresql")).doesNotContain("NombreSecreto", "ApellidoSecreto");
        }
    }
}
